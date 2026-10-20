"""Compare contour-only bytes on the same stratified global tile sample.

Copies encoded layers without geometry re-encoding. Original archives are read-only.
Compression comparisons use identical gzip settings for both sources. No production
contour parameters or input files are changed.
"""
import argparse
from collections import Counter
import gzip
import hashlib
import json
from pathlib import Path
import sqlite3
import statistics
import sys
import time

sys.path.insert(0, str(Path(__file__).parent / 'rbt_reference'))
from extract_reference import filter_layers


def raw(blob):
    return gzip.decompress(blob) if blob and blob[:2] == b'\x1f\x8b' else blob or b''


def connect(path):
    c = sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True)
    c.execute('PRAGMA cache_size=-16384')
    return c


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--reference', type=Path, required=True)
    parser.add_argument('--sample', type=Path, required=True, help='Directory containing sample.json and tiles.txt')
    parser.add_argument('--out', type=Path, required=True)
    a = parser.parse_args()
    a.out.mkdir(parents=True, exist_ok=False)
    manifest = json.loads((a.sample / 'sample.json').read_text())
    content = (a.sample / 'tiles.txt').read_text()
    assert hashlib.sha256(content.encode()).hexdigest() == manifest['tile_list_sha256']
    keys = [tuple(map(int, line.split('/'))) for line in content.splitlines()]
    records = []
    counters = Counter()
    compression_times = Counter()
    seen = Counter()
    databases = {}
    for name in ('candidate6', 'candidate9', 'reference6', 'reference9'):
        c = sqlite3.connect(a.out / (name + '.mbtiles'))
        c.execute('PRAGMA page_size=65536')
        c.execute('CREATE TABLE metadata (name TEXT PRIMARY KEY,value TEXT)')
        c.execute('CREATE TABLE tiles (zoom_level INTEGER,tile_column INTEGER,tile_row INTEGER,tile_data BLOB)')
        c.executemany('INSERT INTO metadata VALUES (?,?)', [('format','pbf'),('minzoom','9'),('maxzoom','13'),
            ('json',json.dumps({'vector_layers':[{'id':'contours','fields':{'ele':'Number','index':'Number','is_ice':'Number'}}]}))])
        databases[name] = c
    query = 'SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?'
    started = time.perf_counter()
    with connect(a.candidate) as candidate, connect(a.reference) as reference:
        for i, (z, x, y) in enumerate(keys):
            key = (z, x, (1 << z) - 1 - y)
            row = {'z': z, 'x': x, 'y': y}
            for label, connection in (('candidate', candidate), ('reference', reference)):
                found = connection.execute(query, key).fetchone()
                blob = found[0] if found else b''
                tile = raw(blob)
                layer = filter_layers(tile, {b'contours'}) if tile else b''
                row[label + '_raw'] = len(layer)
                if label == 'candidate':
                    row['candidate_stored'] = len(blob)
                    peak = filter_layers(tile, {b'peaks'}) if tile else b''
                    row['candidate_peaks_gzip6'] = len(gzip.compress(peak, compresslevel=6, mtime=0)) if peak else 0
                    seen[hashlib.sha256(blob).hexdigest()] += bool(blob)
                for level in (6, 9):
                    start = time.perf_counter()
                    compressed = gzip.compress(layer, compresslevel=level, mtime=0) if layer else b''
                    compression_times[label + str(level)] += time.perf_counter() - start
                    assert raw(compressed) == layer
                    row[label + str(level)] = len(compressed)
                    if compressed:
                        databases[label + str(level)].execute('INSERT INTO tiles VALUES (?,?,?,?)', (*key, compressed))
                counters[label + '_contour_tiles'] += bool(layer)
            counters['both'] += bool(row['candidate_raw'] and row['reference_raw'])
            counters['candidate_only'] += bool(row['candidate_raw'] and not row['reference_raw'])
            counters['reference_only'] += bool(row['reference_raw'] and not row['candidate_raw'])
            records.append(row)
            if (i + 1) % 1000 == 0:
                print('tiles', i + 1, 'seconds', round(time.perf_counter() - started, 1), flush=True)
    archives = {}
    for name, c in databases.items():
        c.execute('CREATE UNIQUE INDEX tile_index ON tiles (zoom_level,tile_column,tile_row)')
        c.commit()
        assert c.execute('PRAGMA integrity_check').fetchall() == [('ok',)]
        archives[name] = {'bytes': (a.out / (name + '.mbtiles')).stat().st_size,
                          'payload_bytes': c.execute('SELECT coalesce(sum(length(tile_data)),0) FROM tiles').fetchone()[0]}
        c.close()
    fields = [k for k in records[0] if k not in ('z','x','y')]
    strata = []
    estimates = Counter()
    variances = Counter()
    for z, expected in manifest['counts'].items():
        z = int(z)
        rows = [r for r in records if r['z'] == z]
        assert len(rows) == expected
        population = 4 ** z
        result = {'zoom': z, 'sampled': len(rows), 'both': sum(bool(r['candidate_raw'] and r['reference_raw']) for r in rows)}
        for field in fields:
            values = [r[field] for r in rows]
            estimates[field] += population * statistics.mean(values)
            variances[field] += population**2 * (1-len(rows)/population) * statistics.variance(values)/len(rows)
            result[field] = sum(values)
        for field in ('candidate6','reference6'):
            estimates[field + '_matched_coverage'] += population * statistics.mean([
                r[field] if r['candidate_raw'] and r['reference_raw'] else 0 for r in rows])
        for label in ('candidate','reference'):
            estimates[label + '_nonempty_tiles'] += population * statistics.mean([bool(r[label + '_raw']) for r in rows])
        paired_difference = [r['candidate6'] - r['reference6'] for r in rows]
        variances['paired_difference6'] += population**2 * (1-len(rows)/population) * statistics.variance(paired_difference)/len(rows)
        strata.append(result)
    intervals = {field:[max(0, value - 1.96 * variances[field]**.5), value + 1.96 * variances[field]**.5]
                 for field,value in estimates.items() if field in variances}
    difference = estimates['candidate6']-estimates['reference6']
    archive_estimates = {}
    for name, archive in archives.items():
        label = 'candidate' if name.startswith('candidate') else 'reference'
        nonempty = counters[label + '_contour_tiles']
        overhead = max(0, archive['bytes']-archive['payload_bytes']-5*65536)/nonempty
        archive_estimates[name] = {'sample_overhead_bytes_per_tile':overhead,
            'estimated_archive_bytes':estimates[name]+overhead*estimates[label+'_nonempty_tiles']+5*65536}
    result = {'sample': manifest, 'candidate':str(a.candidate.resolve()),'reference':str(a.reference.resolve()),
        'coverage':dict(counters),'strata':strata,'estimated_payload_bytes':dict(estimates),
        'approximate_sampling_95pct_bytes':intervals,
        'paired_difference6_95pct_bytes':[difference-1.96*variances['paired_difference6']**.5,difference+1.96*variances['paired_difference6']**.5],
        'sample_archives_64k':archives,'estimated_archives_64k':archive_estimates,'compression_seconds':dict(compression_times),
        'exact_duplicate_candidate_sample_tiles':sum(max(0,count-1) for count in seen.values()),
        'limits':'Same sampled z9-13 keys, encoded contour layers copied verbatim, gzip recompressed equally. Coverage differs. Sampling intervals exclude systematic source and page-fill differences. No planet archive is scanned in full.'}
    (a.out/'tile-sizes.json').write_text(json.dumps(records))
    (a.out/'results.json').write_text(json.dumps(result,indent=2))
    print(json.dumps(result),flush=True)


if __name__ == '__main__':
    main()
