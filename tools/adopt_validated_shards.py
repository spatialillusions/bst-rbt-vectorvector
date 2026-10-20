"""Explicit audited import into a NEW fingerprint/work directory after paired validation.

Never edits the old fingerprint or payloads. Windows byte-range locks protect both
deployments; archives are integrity-checked and hashed before linking/copying.
"""
import argparse
from contextlib import closing, ExitStack
from datetime import datetime, timezone
import hashlib
import json
import msvcrt
from pathlib import Path
import os
import re
import shutil
import sqlite3
import subprocess
from validate_memory_upgrade import historical_shard_seconds
from native_speed_validation import validate_evidence, identity_properties


def digest(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def lock(stack, path):
    handle = stack.enter_context(path.open('a+b'))
    handle.seek(0, 2)
    if not handle.tell():
        handle.write(b'0'); handle.flush()
    handle.seek(0)
    msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
    def release():
        handle.seek(0); msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
    stack.callback(release)


def identity(java, jar, helper, command):
    cli = command[command.index('-jar')+2:]
    value = subprocess.check_output([java,*identity_properties(command),'-cp',str(helper)+os.pathsep+str(jar),
                                    'PrintBuildIdentity',*cli],text=True).strip()
    if len(value)!=64 or any(c not in '0123456789abcdef' for c in value):
        raise ValueError('Unexpected build identity response')
    return value


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--old',type=Path,required=True)
    p.add_argument('--new',type=Path,required=True)
    p.add_argument('--snapshot',type=Path,required=True)
    p.add_argument('--validation',type=Path,required=True)
    p.add_argument('--identity-helper',type=Path,required=True)
    p.add_argument('--java',required=True)
    a=p.parse_args()
    old,new=a.old.resolve(),a.new.resolve()
    if old==new or old in new.parents or new in old.parents:
        p.error('Old and new deployments must be separate directories')
    report=json.loads(a.validation.read_text())
    if report.get('checks')!='passed':
        p.error('Paired validation has not passed')
    speed=report.get('validation_kind')=='native-speed'
    if speed:
        dense_previous,dense_current=validate_evidence(report,old)
    required=set() if speed else {'independent_quality','full-x29-y16','full-x45-y25'}
    if not required<=report.get('comparisons',{}).keys():
        p.error('Independent quality and both completed-shard regressions are required')
    for label in required:
        if report['comparisons'][label]['raw_identical_tiles']<=0:
            p.error('A required regression has no matching output')
    topology=report.get('validation_kind')=='topology-recovery'
    if topology:
        recovery=report['comparisons'].get('topology_recovery',{})
        if not report.get('baseline_failure',{}).get('topology_exception') or not recovery.get('failed_tile_recovered') or recovery.get('complete_shard_integrity')!='ok' or recovery.get('previous_partial_raw_identical_tiles',0)<=0:
            p.error('Frozen failure, repaired full shard and unchanged successful partial output are required')
    allowance=1.20 if topology else 1.05
    if not speed and report['new_medians']['seconds']>report['old_medians']['seconds']*allowance:
        p.error('Candidate median runtime exceeds the validation-kind allowance')
    if not speed and report['new_medians']['processing_seconds_log_resolution_1s']>report['old_medians']['processing_seconds_log_resolution_1s']*allowance+ (1 if topology else 0):
        p.error('Candidate median processing phase exceeds the validation-kind allowance')
    if not speed and report['new_medians']['peak_rss_mib']>report['old_medians']['peak_rss_mib']*(1.20 if topology else .75):
        p.error('Candidate median peak RSS exceeds the validation-kind allowance')
    if not speed:
        dense_previous = (json.loads(Path(json.loads((old/'manifest.json').read_text())['validation_report']).read_text())['runs']['full-x45-y25']['processing_seconds_log_resolution_1s']
                          if topology else historical_shard_seconds(old, 'x45-y25'))
        dense_current = report['runs']['full-x45-y25']['processing_seconds_log_resolution_1s']
    if not speed and dense_current > dense_previous*allowance+1:
        p.error('Candidate full dense shard regressed against the preserved planet interval')
    before=json.loads(a.snapshot.read_text())
    previous=json.loads((old/'manifest.json').read_text())
    current=json.loads((new/'manifest.json').read_text())
    old_cmd,new_cmd=previous['java_command'],current['java_command']
    old_jar=Path(old_cmd[old_cmd.index('-jar')+1]);new_jar=Path(new_cmd[new_cmd.index('-jar')+1])
    if Path(report['old_jar']).resolve()!=old_jar.resolve() or Path(report['settings']['old_deployment']).resolve()!=old:
        p.error('Validation belongs to a different old deployment')
    if digest(new_jar)!=report['candidate_sha256'] or digest(old_jar)!=previous['jar_sha256']:
        p.error('Validated producer identity changed')
    source=old/'tiles/.shards';target=new/'tiles/.shards';target.mkdir(parents=True,exist_ok=True)
    with ExitStack() as stack:
        lock(stack,old/'runner.lock');lock(stack,source/'build.lock')
        lock(stack,new/'runner.lock');lock(stack,target/'build.lock')
        for item in previous['immutable_files']:
            f=Path(item['path']);st=f.stat()
            if st.st_size!=item['bytes'] or st.st_mtime_ns!=item['mtime_ns'] or (item.get('sha256') and digest(f)!=item['sha256']):
                raise ValueError('Old frozen input/runtime changed: '+str(f))
        old_id=identity(a.java,old_jar,a.identity_helper,old_cmd)
        if old_id!=(source/'build.sha256').read_text().strip() or old_id!=before['build_identity'].strip():
            raise ValueError('Old fingerprint mismatch')
        new_id=identity(a.java,new_jar,a.identity_helper,new_cmd)
        markers={f.name:f.read_text() for f in source.glob('*.done')}
        if markers!=before['markers']:
            raise ValueError('Preserved completion markers changed')
        for name in markers:
            match=re.fullmatch(r'x(\d+)-y(\d+)\.done',name)
            if not match or any(int(v)>63 for v in match.groups()):
                raise ValueError('Unexpected shard name: '+name)
        intent={'old':str(old),'new':str(new),'old_identity':old_id,'new_identity':new_id,
                'candidate_sha256':report['candidate_sha256'],'validation_sha256':digest(a.validation),
                'snapshot_sha256':digest(a.snapshot),'count':len(markers)}
        intent_path=new/'shard-import-intent.json'
        if intent_path.exists():
            if json.loads(intent_path.read_text())!=intent:raise ValueError('Import intent changed')
        else:
            if any(f.name!='build.lock' for f in target.iterdir()):
                raise ValueError('Destination has unrelated shard artifacts')
            intent_path.write_text(json.dumps(intent,indent=2))
        records=[]
        dest=target/'contours';dest.mkdir(exist_ok=True)
        for number,(name,stamp) in enumerate(sorted(markers.items()),1):
            source_file=source/'contours'/name.replace('.done','.mbtiles')
            st=source_file.stat()
            expected=f'contours:{st.st_size}:{st.st_mtime_ns//1000000}'
            if stamp!=expected:raise ValueError('Completion stamp mismatch: '+name)
            with closing(sqlite3.connect(source_file.as_uri()+'?mode=ro',uri=True)) as db:
                db.execute('PRAGMA cache_size=-16384')
                if db.execute('PRAGMA integrity_check').fetchall()!=[('ok',)]:
                    raise ValueError('Source integrity failed: '+name)
            checksum=digest(source_file)
            destination=dest/source_file.name
            method='existing'
            if destination.exists():
                if digest(destination)!=checksum:raise ValueError('Destination mismatch: '+name)
            else:
                try:os.link(source_file,destination);method='hardlink'
                except OSError:shutil.copy2(source_file,destination);method='copy'
            info=destination.stat()
            if info.st_size!=st.st_size or digest(destination)!=checksum:
                raise ValueError('Imported payload mismatch: '+name)
            new_stamp=f'contours:{info.st_size}:{info.st_mtime_ns//1000000}'
            (target/name).write_text(new_stamp)
            records.append({'shard':name,'bytes':st.st_size,'sha256':checksum,'method':method})
            if number%32==0:print(f'Verified/imported {number}/{len(markers)} shards',flush=True)
        audit={**intent,'utc':datetime.now(timezone.utc).isoformat(),'archives':records,
               'dense_previous_processing_seconds':dense_previous,'dense_new_processing_seconds':dense_current,
               'source_unchanged':True,'checks':'passed'}
        temporary=new/'shard-import-report.json.tmp';temporary.write_text(json.dumps(audit,indent=2))
        temporary.replace(new/'shard-import-report.json')
        pending=target/'build.sha256.tmp'
        with pending.open('w') as f:
            f.write(new_id);f.flush();os.fsync(f.fileno())
        pending.replace(target/'build.sha256')
        print('SHARD_IMPORT_PASSED',len(records),flush=True)


if __name__=='__main__':main()
