"""Freeze a validated producer in a fresh deployment. Does not import or launch it."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--old', type=Path, required=True)
    p.add_argument('--new', type=Path, required=True)
    p.add_argument('--validation', type=Path, required=True)
    p.add_argument('--snapshot', type=Path, required=True)
    p.add_argument('--rotation-validation', type=Path, required=True)
    p.add_argument('--reopen-archives', action='store_true')
    p.add_argument('--spatial', type=Path, required=True)
    p.add_argument('--commit', required=True)
    a = p.parse_args()
    old, new = a.old.resolve(), a.new.resolve()
    if old == new or old in new.parents or new in old.parents or new.exists():
        p.error('Use a fresh, separate deployment directory')
    report = json.loads(a.validation.read_text())
    previous = json.loads((old/'manifest.json').read_text())
    candidate = Path(report['candidate'])
    if report.get('checks') != 'passed' or digest(candidate) != report['candidate_sha256']:
        p.error('A passed report and its unchanged candidate are required')
    if Path(report['settings']['old_deployment']).resolve() != old:
        p.error('Validation belongs to another deployment')
    if Path(report['settings']['spatial']).resolve() != a.spatial.resolve():
        p.error('Validation used another prepared store')
    rotation = json.loads(a.rotation_validation.read_text())
    if rotation.get('checks') != 'passed' or digest(Path(rotation['source_validation'])) != digest(a.validation):
        p.error('Matching passed archive-rotation validation is required')
    # Verify original immutable inputs before creating anything.
    for item in previous['immutable_files']:
        path = Path(item['path']); st = path.stat()
        if st.st_size != item['bytes'] or st.st_mtime_ns != item['mtime_ns'] or (
                item.get('sha256') and digest(path) != item['sha256']):
            raise ValueError('Original identity changed: '+str(path))
    runtime = new/'runtime'; runtime.mkdir(parents=True)
    operations = new/'operations'; operations.mkdir()
    (new/'tiles/.shards').mkdir(parents=True)
    jar = runtime/'vectorvector-cli.jar'; shutil.copy2(candidate, jar)
    tools = Path(__file__).resolve().parent
    shutil.copy2(tools/'verify_shard_merge.py', runtime/'verify_shard_merge.py')
    shutil.copy2(a.validation, runtime/'validation.json')
    shutil.copy2(a.snapshot, runtime/'baseline-snapshot.json')
    shutil.copy2(a.rotation_validation, runtime/'rotation-validation.json')
    operational = ['planet_runner.py','monitor_job.py','benchmark_contours.py',
                   'recycle_policy.py','watch_memory.py']
    for name in operational:
        shutil.copy2(tools/name, operations/name)
    (operations/'operations.json').write_text(json.dumps({'sha256':{
        name:digest(operations/name) for name in operational}}, indent=2))
    command = previous['java_command'].copy()
    cli = command[command.index('-jar')+2:]
    cli[cli.index('-o')+1] = str(new/'tiles')
    cli += ['--spatialData',str(a.spatial.resolve()),'--elevationCacheMiB','384',
            '--glacierCacheMiB','128','--tileBlockSize','8']
    if a.reopen_archives:
        cli += ['--reopenArchivesAfterShard']
    command = [command[0],'-Xms1g','-Xmx'+report['settings']['heap'],
               '-XX:+UseG1GC','-XX:G1HeapRegionSize=8m','-XX:+ExitOnOutOfMemoryError',
               '-Xlog:gc*:file="'+str(new/'gc.log').replace('\\','/')+'":time,uptime,level,tags:filecount=10,filesize=64m',
               '-jar',str(jar),*cli]
    items = [item for item in previous['immutable_files']
             if Path(item['path']).parent != old/'runtime']
    def frozen(path):
        st = path.stat()
        return {'path':str(path.resolve()),'bytes':st.st_size,'mtime_ns':st.st_mtime_ns,'sha256':digest(path)}
    items += [frozen(path) for path in runtime.iterdir() if path.is_file()]
    items += [frozen(path) for path in sorted(a.spatial.resolve().rglob('*')) if path.is_file()]
    manifest = {**previous,'source_code_commit':a.commit,'jar_sha256':digest(jar),
        'java_command':command,'immutable_files':items,'expected_shards':4096,
        'previous_deployment':str(old),'validation_report':str(runtime/'validation.json'),
        'archive_reopening':a.reopen_archives,'archive_rotation_validation':str(runtime/'rotation-validation.json'),
        'supervision':{'session_seconds':2700,'recycle_mib':3072,'resume_mib':8192,
                       'stable_seconds':10,'checkpoint_wait_seconds':120},
        'resume':'Run operations/planet_runner.py with this deployment root; do not rebuild or change the frozen CLI.'}
    (new/'manifest.json').write_text(json.dumps(manifest,indent=2))
    print('FROZEN_DEPLOYMENT_CREATED',new,flush=True)


if __name__ == '__main__':
    main()
