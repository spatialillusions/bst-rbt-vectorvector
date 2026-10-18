"""Compare archive reopening on/off across four real dense-terrain shards."""
import argparse
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
from validate_memory_upgrade import compare_archives


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--validation', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    a = p.parse_args()
    paired = json.loads(a.validation.read_text())
    if paired.get('checks') != 'passed':
        p.error('Run the main paired validation first')
    settings = paired['settings']
    old = Path(settings['old_deployment'])
    original = json.loads((old/'manifest.json').read_text())['java_command']
    def arg(flag): return original[original.index(flag)+1]
    a.out.mkdir(parents=True, exist_ok=False)
    keys = set()
    # 32x32 windows at each high zoom, including root boundaries and dense ice.
    for x in (44,45):
        for y in (24,25):
            for z in (12,13):
                scale = 1 << (z-6)
                keys.update((z,xx,yy) for xx in range((x+1)*scale-32,(x+1)*scale)
                            for yy in range((y+1)*scale-32,(y+1)*scale))
    selected = a.out/'tiles.txt'
    selected.write_text(''.join(f'{z}/{x}/{y}\n' for z,x,y in sorted(keys)))
    reference = a.out/'previous-selected.mbtiles'
    with closing(sqlite3.connect(reference)) as target:
        target.execute('CREATE TABLE tiles(zoom_level,tile_column,tile_row,tile_data)')
        handles = {}
        try:
            for z,x,y in sorted(keys):
                root = (x >> (z-6),y >> (z-6))
                if root not in handles:
                    path = old/f'tiles/.shards/contours/x{root[0]}-y{root[1]}.mbtiles'
                    handles[root] = sqlite3.connect(path.resolve().as_uri()+'?mode=ro',uri=True)
                    handles[root].execute('PRAGMA cache_size=-16384')
                tms = (z,x,(1<<z)-1-y)
                row = handles[root].execute('SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?',tms).fetchone()
                if row: target.execute('INSERT INTO tiles VALUES(?,?,?,?)',(*tms,row[0]))
            target.commit()
        finally:
            for db in handles.values(): db.close()
    common = ['--profile','rbt','--dataFormat','mapterhorn','--pmtiles',arg('--pmtiles'),
        '--glaciers',arg('--glaciers'),'--peaks','--glacierWkb',arg('--glacierWkb'),
        '--spatialData',settings['spatial'],'--parallelism',str(settings['workers']),
        '--elevationCacheTiles','512','--glacierCacheTiles','1024',
        '--elevationCacheMiB','384','--glacierCacheMiB','128','--tileBlockSize','8',
        '-minZ','6','-maxZ','13','-minX','44','-maxX','45','-minY','24','-maxY','25',
        '--shardsX','2','--shardsY','2','--resumableShards=true',
        '--tileList',str(selected.resolve()),'-f','mbtiles']
    report = {'source_validation':str(a.validation.resolve()),'requested_tiles':len(keys),'runs':{},'comparisons':{}}
    for reopen in (False,True):
        name = 'reopen' if reopen else 'keep'
        folder = a.out/name
        command = [settings['java'],'-Xms1g','-Xmx'+settings['heap'],'-XX:+UseG1GC',
            '-XX:G1HeapRegionSize=8m','-XX:+ExitOnOutOfMemoryError','-jar',paired['candidate'],
            *common,'-o',str(folder.resolve()),*(['--reopenArchivesAfterShard'] if reopen else [])]
        monitor = a.out/(name+'-monitor')
        subprocess.run([sys.executable,str(Path(__file__).with_name('monitor_job.py')),
            '--out',str(monitor),'--min-available-mib','3072','--min-disk-free-gib','20','--',*command],check=True)
        report['runs'][name] = json.loads((monitor/'result.json').read_text())
        report['comparisons'][name] = compare_archives(reference,folder/'contours.mbtiles')
        (a.out/'report.json').write_text(json.dumps(report,indent=2))
        print(name,json.dumps(report['runs'][name]),flush=True)
    report['checks'] = 'passed'
    (a.out/'report.json').write_text(json.dumps(report,indent=2))
    print('ROTATION_VALIDATION_PASSED',flush=True)


if __name__ == '__main__':
    main()
