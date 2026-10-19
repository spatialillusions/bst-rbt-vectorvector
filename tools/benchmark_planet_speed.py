"""Serial paired planet-shard comparisons. Run without competing generation jobs.

Uses a frozen deployment as baseline and exact raw output reference. Never writes
to that deployment. Fresh outputs, bounded monitors and failures are retained.
"""
import argparse
from pathlib import Path
import hashlib
import json
import statistics
import subprocess
import sys
from validate_memory_upgrade import compare_archives, historical_shard_seconds


def transfer_bytes(before, after):
    """An Explorer restart makes the interval unmeasurable, so require a retry."""
    if before.keys() != after.keys() or any(after[k] < before[k] for k in before):
        return None
    return sum(after[k]-before[k] for k in before)


def transfer_contended(moved, seconds):
    return moved is None or moved > max(8*2**20,seconds*2*2**20)


def explorer_io():
    command = "@(Get-CimInstance Win32_Process -Filter \"Name = 'explorer.exe'\" | Select-Object ProcessId,ReadTransferCount,WriteTransferCount) | ConvertTo-Json -Compress"
    raw = subprocess.check_output(['powershell','-NoProfile','-Command',command],text=True,
                                  creationflags=subprocess.CREATE_NO_WINDOW)
    processes = json.loads(raw.strip() or '[]')
    if isinstance(processes,dict): processes=[processes]
    return {int(v['ProcessId']):int(v.get('ReadTransferCount') or 0)+int(v.get('WriteTransferCount') or 0)
            for v in processes or []}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--deployment', type=Path, required=True)
    p.add_argument('--candidate', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--repeats', type=int, default=2)
    p.add_argument('--quiet-seconds', type=int, default=15,
                   help='Require disk/Explorer quiet before trials; zero labels timings as diagnostic')
    a = p.parse_args()
    if a.repeats < 2:
        p.error('At least two alternating paired repetitions are required')
    if a.quiet_seconds < 0:
        p.error('Quiet seconds must be nonnegative')
    a.out.mkdir(exist_ok=False, parents=True)
    if a.quiet_seconds:
        from benchmark_contours import wait_for_explorer
        wait_for_explorer(a.quiet_seconds, timeout_seconds=120)
    manifest = json.loads((a.deployment/'manifest.json').read_text())
    original = manifest['java_command']
    def arg(flag): return original[original.index(flag)+1]
    baseline = Path(arg('-jar'))
    report = {'baseline_sha256':hashlib.sha256(baseline.read_bytes()).hexdigest(),
              'candidate_sha256':hashlib.sha256(a.candidate.read_bytes()).hexdigest(),
              'runs':{}, 'comparisons':{}, 'historical_seconds':{}, 'medians':{},
              'quiet_seconds':a.quiet_seconds, 'timings':'controlled' if a.quiet_seconds else 'diagnostic'}
    def save(): (a.out/'report.json').write_text(json.dumps(report,indent=2))
    common = ['--profile','rbt','--dataFormat','mapterhorn','--pmtiles',arg('--pmtiles'),
              '--glaciers',arg('--glaciers'),'--peaks','--glacierWkb',arg('--glacierWkb'),
              '--spatialData',arg('--spatialData'),'--parallelism','8',
              '--elevationCacheTiles','512','--glacierCacheTiles','1024',
              '--elevationCacheMiB','384','--glacierCacheMiB','128','--tileBlockSize','8','-f','mbtiles']
    def run(label, variant, selection, reference):
        output = a.out/label
        jar = baseline if variant == 'old' else a.candidate
        cmd = [original[0],'-Xms1g','-Xmx6g','-XX:+UseG1GC','-XX:G1HeapRegionSize=8m',
               '-XX:+ExitOnOutOfMemoryError']
        if variant == 'native': cmd += ['-Dvectorvector.webpDecoder=native']
        cmd += ['-jar',str(jar),*common,*selection,'-o',str(output)]
        monitor = a.out/(label+'-monitor')
        explorer_before = explorer_io()
        with (a.out/(label+'.driver.log')).open('w') as log:
            result = subprocess.run([sys.executable,str(Path(__file__).with_name('monitor_job.py')),
                '--out',str(monitor),'--min-available-mib','3072','--min-disk-free-gib','20','--',*cmd],
                stdout=log,stderr=subprocess.STDOUT)
        if result.returncode: raise RuntimeError('Run failed: '+label)
        report['runs'][label] = json.loads((monitor/'result.json').read_text())
        moved = transfer_bytes(explorer_before, explorer_io())
        seconds = report['runs'][label]['seconds']
        report['runs'][label]['explorer_transfer_bytes'] = moved
        if a.quiet_seconds and transfer_contended(moved, seconds):
            report['checks']='contended'; save()
            raise RuntimeError('Explorer transfer resumed during '+label+'; discard timing and retry after quiet')
        report['comparisons'][label] = compare_archives(reference, output/'contours.mbtiles')
        save()
        print(label,json.dumps({k:report['runs'][label][k] for k in ['seconds','cpu_seconds','peak_rss_mib']}),flush=True)
    world = ['-minZ','6','-maxZ','13','-minX','0','-maxX','63','-minY','0','-maxY','63']
    for variant in ['java','native']:
        run('quality-'+variant,variant,world+['--tileList',manifest['quality_tiles']],Path(manifest['quality_reference']))
    regions = [(29,16),(45,25),(62,58)]
    for x,y in regions:
        key=f'x{x}-y{y}'
        try: report['historical_seconds'][key] = historical_shard_seconds(a.deployment,key)
        except ValueError: pass
        reference=a.deployment/f'tiles/.shards/contours/{key}.mbtiles'
        selection=['-minZ','6','-maxZ','13','-minX',str(x),'-maxX',str(x),'-minY',str(y),'-maxY',str(y)]
        for iteration in range(a.repeats):
            variants=['old','java','native'] if iteration%2 == 0 else ['native','java','old']
            for variant in variants: run(f'{key}-{variant}-{iteration}',variant,selection,reference)
        report['medians'][key]={v:{metric:statistics.median(report['runs'][f'{key}-{v}-{i}'][metric]
            for i in range(a.repeats)) for metric in ['seconds','cpu_seconds','peak_rss_mib']}
            for v in ['old','java','native']}
        save()
    # Full formerly failing root checks repaired glacier processing with both decoders.
    for variant in ['java','native']:
        run('x60-y52-'+variant,variant,['-minZ','6','-maxZ','13','-minX','60','-maxX','60','-minY','52','-maxY','52'],
            a.deployment/'tiles/.shards/contours/x60-y52.mbtiles')
    report['checks']='passed'; save(); print('PLANET_SPEED_VALIDATION_PASSED',flush=True)


if __name__ == '__main__': main()
