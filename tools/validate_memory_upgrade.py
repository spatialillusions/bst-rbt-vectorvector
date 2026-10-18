"""Paired frozen-producer validation. Reads old planet data; writes only a fresh --out.

Uses the saved producer/input identities and independent pilot, never RBT data.
Performance tests must run without a competing generation/preparation job.
"""
import argparse
from contextlib import closing
from datetime import datetime
import hashlib
import json
import math
from pathlib import Path
import re
import sqlite3
import statistics
import subprocess
import sys

TOOLS = Path(__file__).resolve().parent


def historical_shard_seconds(deployment, shard):
    """Find a completed processing interval in preserved planet logs."""
    completed = []
    for log in sorted((deployment/'monitor').glob('generation-*/run.log')):
        selected = False
        started = None
        for line in log.read_text(encoding='utf-8').splitlines():
            if 'Processing shard ' in line:
                selected = f'Processing shard {shard} (' in line
                started = None
            if selected and 'Processing area of ' in line:
                started = datetime.fromisoformat(line[:19])
            if selected and started and 'Completed in ' in line:
                completed.append((datetime.fromisoformat(line[:19])-started).total_seconds())
                selected = False
    if not completed:
        raise ValueError('No completed historical processing interval for '+shard)
    return completed[-1]


def compare_archives(old, new):
    def connect(path):
        db = sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True)
        db.execute('PRAGMA cache_size=-16384')
        return db
    with closing(connect(old)) as a, closing(connect(new)) as b:
        if b.execute('PRAGMA integrity_check').fetchall() != [('ok',)]:
            raise ValueError('Candidate SQLite integrity failed')
        query = 'SELECT zoom_level,tile_column,tile_row,tile_data FROM tiles ORDER BY 1,2,3'
        left, right = iter(a.execute(query)), iter(b.execute(query))
        count = 0
        digest = hashlib.sha256()
        while True:
            x, y = next(left, None), next(right, None)
            if x is None and y is None:
                break
            if x != y:
                raise ValueError(f'Raw tile mismatch: old={x[:3] if x else None}, new={y[:3] if y else None}')
            digest.update(f'{x[0]}/{x[1]}/{x[2]}:'.encode())
            digest.update(x[3]); count += 1
        return {'raw_identical_tiles': count, 'sha256': digest.hexdigest(), 'integrity': 'ok'}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--old-deployment', type=Path, required=True)
    p.add_argument('--candidate', type=Path, required=True)
    p.add_argument('--spatial', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--java', required=True)
    p.add_argument('--workers', type=int, default=8)
    p.add_argument('--repeats', type=int, default=2)
    p.add_argument('--heap', default='6g')
    p.add_argument('--skip-full-shards', action='store_true')
    a = p.parse_args()
    if a.workers <= 0 or a.repeats < 2:
        p.error('Use positive workers and at least two paired repetitions')
    a.out.mkdir(parents=True, exist_ok=False)
    manifest = json.loads((a.old_deployment / 'manifest.json').read_text())
    original = manifest['java_command']
    old_jar = Path(original[original.index('-jar')+1])
    def argument(flag):
        return original[original.index(flag)+1]
    common = ['--profile','rbt','--dataFormat','mapterhorn','--pmtiles',argument('--pmtiles'),
        '--glaciers',argument('--glaciers'),'--peaks','--glacierWkb',argument('--glacierWkb'),
        '--parallelism',str(a.workers),'--elevationCacheTiles','512','--glacierCacheTiles','1024',
        '-f','mbtiles']
    report = {'old_jar':str(old_jar),'candidate':str(a.candidate.resolve()),
        'candidate_sha256':hashlib.sha256(a.candidate.read_bytes()).hexdigest(),
        'settings':vars(a).copy(),'runs':{},'comparisons':{}}
    report['settings']={k:str(v) if isinstance(v,Path) else v for k,v in report['settings'].items()}
    def save():
        (a.out/'report.json').write_text(json.dumps(report,indent=2))
    def run(label, candidate, selection, reopen=True):
        folder = a.out/label
        extra = (['--spatialData',str(a.spatial.resolve()),'--elevationCacheMiB','384',
                  '--glacierCacheMiB','128'] + (['--reopenArchivesAfterShard'] if reopen else [])) if candidate else []
        cmd = [a.java,'-Xms1g',f'-Xmx{a.heap}','-XX:+UseG1GC','-XX:G1HeapRegionSize=8m',
            '-XX:+ExitOnOutOfMemoryError','-jar',str(a.candidate.resolve() if candidate else old_jar),
            *common,*selection,*extra,'-o',str(folder)]
        monitor = folder.with_name(folder.name+'-monitor')
        with folder.with_suffix('.driver.log').open('w') as log:
            result = subprocess.run([sys.executable,str(TOOLS/'monitor_job.py'),'--out',str(monitor),
                '--min-available-mib','3072','--min-disk-free-gib','20','--',*cmd],stdout=log,stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError(f'{label} failed; see {monitor}')
        metrics=json.loads((monitor/'result.json').read_text())
        text=(monitor/'run.log').read_text()
        starts=re.findall(r'(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d).*Processing area of .*\((\d+) tiles\)',text)
        ends=re.findall(r'(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d).*Completed in ',text)
        if starts and ends:
            metrics['processing_seconds_log_resolution_1s']=(datetime.fromisoformat(ends[-1])-datetime.fromisoformat(starts[0][0])).total_seconds()
            metrics['requested_tiles']=int(starts[0][1])
        report['runs'][label]=metrics;save()
        print(label,json.dumps({k:metrics[k] for k in ('seconds','peak_rss_mib','cpu_seconds')}),flush=True)
        return folder/'contours.mbtiles'
    world=['-minZ','6','-maxZ','13','-minX','0','-maxX','63','-minY','0','-maxY','63']
    quality=run('quality',True,world+['--tileList',manifest['quality_tiles']])
    report['comparisons']['independent_quality']=compare_archives(Path(manifest['quality_reference']),quality);save()
    keys=set()
    for lon,lat in [(-16.7,64.1),(7.7,46),(86.9,28),(-42,65.5),(-65,-66.5)]:
        for z in (12,13):
            x=int((lon+180)/360*2**z)
            y=int((1-math.asinh(math.tan(math.radians(lat)))/math.pi)/2*2**z)
            keys.update((z,xx,yy) for xx in range(x-4,x+4) for yy in range(y-4,y+4))
    tiles=a.out/'mixed.tiles.txt';tiles.write_text(''.join(f'{z}/{x}/{y}\n' for z,x,y in sorted(keys)))
    for iteration in range(a.repeats):
        results={}
        for candidate in ((False,True) if iteration%2==0 else (True,False)):
            label=f'mixed-{"new" if candidate else "old"}-{iteration}'
            results[candidate]=run(label,candidate,world+['--tileList',str(tiles.resolve())])
        report['comparisons'][f'mixed-{iteration}']=compare_archives(results[False],results[True]);save()
    if not a.skip_full_shards:
        for x,y in ((29,16),(45,25)):
            output=run(f'full-x{x}-y{y}',True,['-minZ','6','-maxZ','13','-minX',str(x),'-maxX',str(x),'-minY',str(y),'-maxY',str(y)])
            old=a.old_deployment/f'tiles/.shards/contours/x{x}-y{y}.mbtiles'
            report['comparisons'][f'full-x{x}-y{y}']=compare_archives(old,output);save()
    for variant in ('old','new'):
        runs=[v for k,v in report['runs'].items() if k.startswith('mixed-'+variant)]
        report[variant+'_medians']={k:statistics.median(v[k] for v in runs) for k in
            ('seconds','peak_rss_mib','cpu_seconds','processing_seconds_log_resolution_1s')}
    report['checks']='passed';save();print('VALIDATION_PASSED',flush=True)


if __name__=='__main__':
    main()
