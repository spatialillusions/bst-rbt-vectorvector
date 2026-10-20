"""Explicit admission policy for the paired native-speed upgrade; no resume overrides."""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import statistics
import zipfile

REGIONS = ('x29-y16','x45-y25','x62-y58')


def digest(path):
    with Path(path).open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def identity_properties(command):
    return [v for v in command[1:command.index('-jar')]
            if v.startswith('-Dvectorvector.webpDecoder=')]


def check_benchmark(b):
    if b.get('checks') != 'passed' or b.get('timings') != 'controlled' or b.get('quiet_seconds',0) < 15:
        raise ValueError('A complete transfer-free controlled benchmark is required')
    required = {'quality-java','quality-native','x60-y52-java','x60-y52-native'} | {
        f'{region}-{variant}-{i}' for region in REGIONS for variant in ('old','java','native') for i in range(2)}
    if not required <= b.get('runs',{}).keys() or not required <= b.get('comparisons',{}).keys():
        raise ValueError('Independent quality, three paired regions and repaired glacier shard are required')
    for label in required:
        r,c = b['runs'][label],b['comparisons'][label]
        if r.get('exit_code') != 0 or r.get('memory_floor_stop') or r.get('disk_floor_stop'):
            raise ValueError('Unsuccessful/guard-stopped benchmark: '+label)
        if c.get('integrity') != 'ok' or c.get('raw_identical_tiles',0) <= 0:
            raise ValueError('Missing raw equality/integrity evidence: '+label)
        if r.get('explorer_transfer_bytes') is None or r['explorer_transfer_bytes'] > max(8*2**20,r['seconds']*2*2**20):
            raise ValueError('Transfer contention: '+label)
        if 'native' in label and (r['peak_rss_mib'] > 6144 or r['minimum_available_mib'] < 4096):
            raise ValueError('Native memory safety envelope exceeded: '+label)
    groups = [[f'{region}-{v}-{i}' for v in ('old','java','native') for i in range(2)] for region in REGIONS]
    groups += [['quality-java','quality-native'],['x60-y52-java','x60-y52-native']]
    for labels in groups:
        signatures={(b['comparisons'][v]['sha256'],b['comparisons'][v]['raw_identical_tiles']) for v in labels}
        if len(signatures) != 1: raise ValueError('Outputs differ within workload: '+str(labels))
    for region in REGIONS:
        old = statistics.median(b['runs'][f'{region}-old-{i}']['seconds'] for i in range(2))
        new = statistics.median(b['runs'][f'{region}-native-{i}']['seconds'] for i in range(2))
        if new > old*.90: raise ValueError('Native requires at least 10% median improvement in each region')


def archive_scope(old, new):
    prefix='com/greensopinion/vectorvector/pmtiles/'
    with zipfile.ZipFile(old) as a, zipfile.ZipFile(new) as b:
        left={n for n in a.namelist() if n.startswith(prefix) and n.endswith('.class')}
        right={n for n in b.namelist() if n.startswith(prefix) and n.endswith('.class')}
        if not left or left != right or any(a.read(n) != b.read(n) for n in left):
            raise ValueError('Archive implementation changed; prior archive-lifetime validation cannot be inherited')
        return {'unchanged_archive_classes':len(left)}


def validate_evidence(report, old):
    old=Path(old).resolve();manifest=json.loads((old/'manifest.json').read_text())
    command=manifest['java_command'];old_jar=Path(command[command.index('-jar')+1])
    candidate=Path(report['candidate'])
    path=Path(report['benchmark_path']);b=json.loads(path.read_text())
    if digest(path)!=report['benchmark_sha256'] or b!=report['benchmark']:
        raise ValueError('Underlying clean benchmark changed')
    check_benchmark(b)
    if digest(old_jar)!=b['baseline_sha256'] or digest(candidate)!=b['candidate_sha256'] or b['candidate_sha256']!=report['candidate_sha256']:
        raise ValueError('Benchmark producer identity changed')
    if Path(report['old_jar']).resolve()!=old_jar.resolve() or Path(report['settings']['old_deployment']).resolve()!=old:
        raise ValueError('Validation belongs to another frozen deployment')
    def arg(flag):return command[command.index(flag)+1]
    common=['--profile','rbt','--dataFormat','mapterhorn','--pmtiles',arg('--pmtiles'),
            '--glaciers',arg('--glaciers'),'--peaks','--glacierWkb',arg('--glacierWkb'),
            '--spatialData',arg('--spatialData'),'--parallelism','8','--elevationCacheTiles','512',
            '--glacierCacheTiles','1024','--elevationCacheMiB','384','--glacierCacheMiB','128',
            '--tileBlockSize','8','-f','mbtiles']
    if report['settings']['heap']!='6g' or Path(report['settings']['spatial']).resolve()!=Path(arg('--spatialData')).resolve():
        raise ValueError('Resource/input settings differ from validation')
    for label in b['runs']:
        variant='native' if 'native' in label else 'java' if 'java' in label else 'old'
        actual=json.loads((path.parent/(label+'-monitor')/'command.json').read_text())
        expected=[command[0],'-Xms1g','-Xmx6g','-XX:+UseG1GC','-XX:G1HeapRegionSize=8m','-XX:+ExitOnOutOfMemoryError']
        if variant=='native':expected+=['-Dvectorvector.webpDecoder=native']
        expected+=['-jar',str(old_jar if variant=='old' else candidate),*common]
        if label.startswith('quality-'):
            selection=['-minZ','6','-maxZ','13','-minX','0','-maxX','63','-minY','0','-maxY','63','--tileList',manifest['quality_tiles']]
        else:
            match=re.match(r'x(\d+)-y(\d+)-',label)
            if not match:raise ValueError('Unexpected workload '+label)
            x,y=match.groups();selection=['-minZ','6','-maxZ','13','-minX',x,'-maxX',x,'-minY',y,'-maxY',y]
        expected += [*selection,'-o',str(path.parent/label)]
        if actual!=expected:raise ValueError('Actual benchmark command differs from frozen admission policy: '+label)
        if json.loads((path.parent/(label+'-monitor')/'result.json').read_text()) != {
                k:v for k,v in b['runs'][label].items() if k!='explorer_transfer_bytes'}:
            raise ValueError('Monitor metrics differ from clean benchmark: '+label)
    scope=archive_scope(old_jar,candidate)
    if scope != report['archive_scope']:raise ValueError('Archive class scope changed')
    def phase(label):
        text=(path.parent/(label+'-monitor')/'run.log').read_text()
        start=re.findall(r'(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d).*Processing area of ',text)
        end=re.findall(r'(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d).*Completed in ',text)
        if not start or not end:raise ValueError('Missing processing interval '+label)
        return (datetime.fromisoformat(end[-1])-datetime.fromisoformat(start[0])).total_seconds()
    previous=statistics.median(phase(f'x45-y25-old-{i}') for i in range(2))
    current=statistics.median(phase(f'x45-y25-native-{i}') for i in range(2))
    if current>previous*.90+1:raise ValueError('Native dense processing phase did not improve by 10%')
    return previous,current


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('old','candidate','benchmark','out'):p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args();old=a.old.resolve();candidate=a.candidate.resolve();benchmark=a.benchmark.resolve()
    if a.out.exists():p.error('Use a fresh output report')
    manifest=json.loads((old/'manifest.json').read_text());cmd=manifest['java_command']
    old_jar=Path(cmd[cmd.index('-jar')+1]);b=json.loads(benchmark.read_text())
    report={'validation_kind':'native-speed','checks':'passed','old_jar':str(old_jar),'candidate':str(candidate),
        'candidate_sha256':digest(candidate),'benchmark_path':str(benchmark),'benchmark_sha256':digest(benchmark),
        'benchmark':b,'archive_scope':archive_scope(old_jar,candidate),
        'settings':{'old_deployment':str(old),'spatial':cmd[cmd.index('--spatialData')+1],'heap':'6g'}}
    previous,current=validate_evidence(report,old)
    report.update(dense_previous_processing_seconds=previous,dense_new_processing_seconds=current)
    a.out.write_text(json.dumps(report,indent=2));print('NATIVE_SPEED_ADMISSION_PASSED',flush=True)


if __name__=='__main__':main()
