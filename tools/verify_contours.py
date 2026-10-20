"""Read-only, streaming checks of RBT-profile output. No reference dataset required.

Checks SQLite integrity, unique/valid keys, metadata, MVT decoding, contour
geometry/schema/intervals and peak schema. Does not establish terrain accuracy
or seam quality. One decoded tile is retained at a time.
"""
import argparse
from collections import Counter
from contextlib import closing
import gzip
import json
import math
from pathlib import Path
import sqlite3

import mapbox_vector_tile


def require(condition, message):
    if not condition:
        raise ValueError(message)


def coordinates(value):
    if value and isinstance(value[0], (int, float)):
        yield value
    else:
        for child in value:
            yield from coordinates(child)


def verify(path):
    counts = Counter()
    with closing(sqlite3.connect(Path(path).resolve().as_uri() + '?mode=ro', uri=True)) as db:
        db.execute('PRAGMA cache_size=-16384')
        require(db.execute('PRAGMA integrity_check').fetchall() == [('ok',)], 'SQLite integrity failed')
        metadata = dict(db.execute('SELECT name,value FROM metadata'))
        require(metadata.get('format') == 'pbf', 'Expected pbf metadata')
        low, high = int(metadata['minzoom']), int(metadata['maxzoom'])
        require(9 <= low <= high <= 13, 'Expected RBT-profile zooms 9..13')
        require(db.execute('SELECT 1 FROM tiles GROUP BY zoom_level,tile_column,tile_row HAVING count(*)>1 LIMIT 1').fetchone() is None, 'Duplicate tile key')
        for z, x, y, blob in db.execute('SELECT zoom_level,tile_column,tile_row,tile_data FROM tiles'):
            require(type(z) is int and low <= z <= high and 0 <= x < 2**z and 0 <= y < 2**z, 'Invalid tile key')
            tile = mapbox_vector_tile.decode(gzip.decompress(blob) if blob[:2] == b'\x1f\x8b' else blob, default_options={'y_coord_down': True})
            counts['tiles'] += 1
            require(set(tile) <= {'contours', 'peaks'}, 'Unexpected layer')
            for name, layer in tile.items():
                require(layer['extent'] == 4096, 'Expected extent 4096')
                for feature in layer['features']:
                    p, g = feature['properties'], feature['geometry']
                    require(all(len(xy) == 2 and all(math.isfinite(v) and -80 <= v <= 4176 for v in xy) for xy in coordinates(g['coordinates'])), 'Invalid buffered coordinates')
                    if name == 'contours':
                        e = p.get('ele')
                        require(type(e) is int and e != 0, 'Invalid elevation')
                        interval_ok = e % (200 if z <= 10 else 100) == 0 if z <= 11 else (e % 20 == 0 or e % 50 == 0)
                        require(interval_ok, 'Invalid contour interval')
                        expected = next((i for i in (800,400,200,100,50,20) if e % i == 0), None)
                        require(type(p.get('index')) is int and p['index'] == expected, 'Invalid contour index')
                        require(type(p.get('is_ice')) is int and p['is_ice'] in (0,1), 'Invalid ice flag')
                        require(g['type'] in ('LineString','MultiLineString'), 'Invalid contour geometry')
                        lines = [g['coordinates']] if g['type'] == 'LineString' else g['coordinates']
                        require(bool(lines) and all(len(line) >= 2 for line in lines), 'Empty/short contour')
                    else:
                        require(g['type'] == 'Point' and p.get('class') in ('peak','volcano') and type(p.get('rank')) is int and p['rank'] > 0, 'Invalid peak')
                        require('ele' not in p or (type(p['ele']) is int and p.get('ele_source') in ('osm','dem')), 'Invalid peak elevation')
                    counts[name] += 1
    require(counts['tiles'] > 0 and counts['contours'] > 0, 'No contour output')
    return {'archive': str(Path(path).resolve()), 'checks': 'passed', 'counts': dict(counts)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('--report', type=Path)
    a = parser.parse_args()
    try:
        report = verify(a.archive)
    except (ValueError, KeyError, sqlite3.Error) as error:
        parser.exit(1, str(error) + '\n')
    content = json.dumps(report, indent=2) + '\n'
    if a.report:
        with a.report.open('x', encoding='utf-8') as destination:
            destination.write(content)
    print(content, end='')

if __name__ == '__main__':
    main()
