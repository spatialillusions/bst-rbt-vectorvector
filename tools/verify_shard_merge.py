"""Verify this CLI's merged MBTiles against completed shards with two open databases.

The current merger scans shard tables in rowid order, in y/x partition order.
Check every raw key/payload in that same order, plus full destination integrity.
All inputs are read-only; no archive is deleted. This is not a generic archive diff.
"""
import argparse
from contextlib import contextmanager
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import struct


@contextmanager
def open_ro(path):
    c = sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True)
    c.execute('PRAGMA cache_size=-16384')
    try:
        yield c
    finally:
        c.close()


def verify(output, expected_shards=None):
    work = output / '.shards'
    paths = list((work / 'contours').glob('*.mbtiles'))
    if not paths or (expected_shards is not None and len(paths) != expected_shards):
        raise ValueError('Unexpected completed-shard count')
    def partition(path):
        match = re.fullmatch(r'x(\d+)-y(\d+)', path.stem)
        if not match:
            raise ValueError('Unexpected shard filename')
        return int(match[2]), int(match[1])
    paths.sort(key=partition)
    if {p.stem for p in paths} != {p.stem for p in work.glob('*.done')}:
        raise ValueError('Shard databases and completion markers differ')
    for path in paths:
        marker = (work / (path.stem + '.done')).read_text().strip()
        stat = path.stat()
        expected = f'contours:{stat.st_size}:{stat.st_mtime_ns // 1000000}'
        if marker != expected:
            raise ValueError(f'Incomplete or changed shard: {path.name}')
    merged_path = output / 'contours.mbtiles'
    digest = hashlib.sha256()
    count = payload_bytes = 0
    with open_ro(merged_path) as merged:
        print('VERIFY full merged SQLite integrity', flush=True)
        merged.execute('PRAGMA cache_size=-524288')
        if merged.execute('PRAGMA integrity_check').fetchall() != [('ok',)]:
            raise ValueError('Merged SQLite integrity failed')
        merged.execute('PRAGMA cache_size=-16384')
        unique = [row for row in merged.execute('PRAGMA index_list(tiles)') if row[1] == 'tile_index' and row[2]]
        if not unique or [row[2] for row in merged.execute('PRAGMA index_info(tile_index)')] != ['zoom_level','tile_column','tile_row']:
            raise ValueError('Missing expected unique tile index')
        rows = merged.execute('SELECT zoom_level,tile_column,tile_row,tile_data FROM tiles ORDER BY rowid')
        for i, path in enumerate(paths):
            with open_ro(path) as shard:
                for row in shard.execute('SELECT zoom_level,tile_column,tile_row,tile_data FROM tiles ORDER BY rowid'):
                    if next(rows, None) != row:
                        raise ValueError(f'Merged raw key/payload mismatch at tile {count}, shard {path.name}')
                    digest.update(struct.pack('>iiiQ', *row[:3], len(row[3])))
                    digest.update(row[3])
                    count += 1
                    payload_bytes += len(row[3])
            if (i + 1) % 32 == 0:
                print('VERIFY completed shards', i + 1, 'tiles', count, flush=True)
        if next(rows, None) is not None:
            raise ValueError('Merged archive contains extra tiles')
        metadata = dict(merged.execute('SELECT name,value FROM metadata'))
    result = {'integrity':'ok','shards':len(paths),'raw_tiles_verified':count,'payload_bytes':payload_bytes,
        'archive_bytes':merged_path.stat().st_size,'physical_copy_sha256':digest.hexdigest(),
        'metadata':metadata,'verification':'Every raw key/payload compared in the frozen merger copy order; two database connections maximum.'}
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--expected-shards', type=int)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    result = verify(args.output, args.expected_shards)
    args.report.write_text(json.dumps(result, indent=2))
    print('VERIFY_RESULT', json.dumps({k:v for k,v in result.items() if k != 'metadata'}), flush=True)
