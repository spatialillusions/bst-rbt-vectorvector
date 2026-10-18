"""Supervise an existing frozen planet deployment, with bounded JVM sessions.

Requires <root>/manifest.json and its original runtime verification tools.
Never builds code, rewrites the shard fingerprint or adopts changed inputs.
"""
import argparse
import ctypes as C
from monitor_job import SystemMemory, kernel
from contextlib import closing
import hashlib
import json
import msvcrt
from pathlib import Path
import sqlite3
import subprocess
import sys
import time


parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('root', type=Path, help='Existing frozen deployment directory')
parser.add_argument('--check-only',action='store_true')
parser.add_argument('--verify-only',action='store_true')
args=parser.parse_args()
root=args.root.resolve()
runtime=root/'runtime'
operations=Path(__file__).resolve().parent
manifest=json.loads((root/'manifest.json').read_text())
expected_shards=manifest.get('expected_shards',4096)
settings=manifest.get('supervision',{})
SESSION_SECONDS=settings.get('session_seconds',2700)
RECYCLE_MIB=settings.get('recycle_mib',3072)
RESUME_MIB=settings.get('resume_mib',8192)
STABLE_SECONDS=settings.get('stable_seconds',10)
CHECKPOINT_WAIT=settings.get('checkpoint_wait_seconds',120)


def status(stage,**extra):
    value={'stage':stage,'utc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),**extra}
    temp=root/'status.json.tmp'
    temp.write_text(json.dumps(value,indent=2))
    temp.replace(root/'status.json')
    print(json.dumps(value),flush=True)

def identities():
    operations_manifest=operations/'operations.json'
    if operations_manifest.exists():
        for filename,expected in json.loads(operations_manifest.read_text())['sha256'].items():
            with (operations/filename).open('rb') as source:
                if hashlib.file_digest(source,'sha256').hexdigest()!=expected:
                    raise ValueError('Deployed supervisor changed: '+filename)
    for item in manifest['immutable_files']:
        path=Path(item['path'])
        stat=path.stat()
        if stat.st_size!=item['bytes'] or stat.st_mtime_ns!=item['mtime_ns']:
            raise ValueError('Frozen input/runtime changed: '+str(path))
        if item.get('sha256'):
            with path.open('rb') as f:
                if hashlib.file_digest(f,'sha256').hexdigest()!=item['sha256']:
                    raise ValueError('Frozen hash changed: '+str(path))

with (root/'runner.lock').open('a+b') as lock:
    lock.seek(0,2)
    if lock.tell()==0:
        lock.write(b'0');lock.flush()
    lock.seek(0)
    try:
        msvcrt.locking(lock.fileno(),msvcrt.LK_NBLCK,1)
    except OSError:
        raise SystemExit('This frozen planet runner is already active')
    try:
        identities()
        if args.check_only:
            print('Frozen runtime/input identity checks passed; no generation started',flush=True)
            raise SystemExit(0)
        attempts=root/'monitor'
        attempts.mkdir(exist_ok=True)
        monitor=[sys.executable,str(operations/'monitor_job.py'),'--min-available-mib','1024',
            '--min-disk-free-gib','20','--disk-path',str(root),'--cwd',str(root)]
        if not args.verify_only:
            no_progress=0
            floor_stops=0
            while True:
                identities()
                before=len(list((root/'tiles/.shards').glob('*.done')))
                sequence=len(list(attempts.glob('generation-*')))+1
                folder=attempts/f'generation-{sequence:04d}'
                status('planet-generation-running',attempt=sequence,expected_shards=expected_shards,
                    completed_shards_at_start=before,supervision='checkpoint-recycling',session_seconds=SESSION_SECONDS)
                generation_options=['--low-memory-priority-on-pressure-mib','6144',
                    '--low-memory-priority-after','Merging shard']
                if before < expected_shards:
                    generation_options+=['--shard-dir',str(root/'tiles/.shards'),
                        '--recycle-after-seconds',str(SESSION_SECONDS),
                        '--recycle-below-available-mib',str(RECYCLE_MIB),
                        '--recycle-checkpoint-wait-seconds',str(CHECKPOINT_WAIT)]
                result=subprocess.run(monitor+generation_options+['--out',str(folder),'--']+manifest['java_command'],cwd=root)
                if result.returncode==0:
                    break
                evidence=json.loads((folder/'result.json').read_text())
                after=len(list((root/'tiles/.shards').glob('*.done')))
                planned=result.returncode==75 and evidence.get('worker_recycled')
                memory_stop=evidence.get('memory_floor_stop') and not evidence.get('disk_floor_stop')
                no_progress=no_progress+1 if after<=before else 0
                floor_stops=floor_stops+1 if memory_stop else 0
                if (not planned and not memory_stop) or no_progress>=2 or floor_stops>=3:
                    status('planet-generation-stopped',attempt=sequence,exit_code=result.returncode,
                        completed_shards=after,no_progress_sessions=no_progress,memory_floor_stops=floor_stops,
                        recovery='Inspect the last monitor result; completed shards are retained. Resume only this supervisor after resolving the cause.')
                    raise SystemExit(result.returncode)
                # Closing the owned JVM releases its archive handles and caches.
                # Restart only after stable physical headroom; never purge global caches.
                status('planet-memory-recovery-wait',attempt=sequence,completed_shards=after,
                    reason=evidence.get('recycle_reason') if planned else 'memory-floor',resume_available_mib=RESUME_MIB)
                deadline=time.monotonic()+1200
                stable=None
                while True:
                    memory=SystemMemory();memory.length=C.sizeof(memory)
                    if not kernel.GlobalMemoryStatusEx(C.byref(memory)):
                        raise C.WinError(C.get_last_error())
                    now=time.monotonic()
                    if memory.available>=RESUME_MIB*2**20:
                        stable=now if stable is None else stable
                        if now-stable>=STABLE_SECONDS:
                            break
                    else:
                        stable=None
                    if now>=deadline:
                        raise RuntimeError('Physical memory did not recover within 20 minutes; supervisor stopped safely')
                    time.sleep(2)
        identities()
        verification=len(list(attempts.glob('verification-*')))+1
        status('planet-full-payload-verification',verification=verification)
        result=subprocess.run(monitor+['--low-memory-priority','--out',str(attempts/f'verification-{verification:04d}'),'--',
            sys.executable,str(runtime/'verify_shard_merge.py'),str(root/'tiles'),'--expected-shards',str(expected_shards),
            '--report',str(root/'full-verification.json')],cwd=root)
        if result.returncode:
            status('planet-verification-stopped',exit_code=result.returncode,
                recovery='Inspect verification logs; rerun this frozen runner with --verify-only after resolving the stop reason.')
            raise SystemExit(result.returncode)
        expected=Path(manifest['quality_reference'])
        def ro(path):return sqlite3.connect(path.resolve().as_uri()+'?mode=ro',uri=True)
        with closing(ro(expected)) as pilot,closing(ro(root/'tiles/contours.mbtiles')) as planet:
            selected=[tuple(map(int,line.split('/'))) for line in Path(manifest['quality_tiles']).read_text().splitlines()]
            differences=[]
            query='SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?'
            for z,x,y in selected:
                key=(z,x,(1<<z)-1-y)
                if pilot.execute(query,key).fetchone()!=planet.execute(query,key).fetchone():differences.append([z,x,y])
        sample={'requested':len(selected),'different_raw_tiles':differences}
        (root/'quality-sample-verification.json').write_text(json.dumps(sample,indent=2))
        identities()
        if differences:
            status('planet-quality-sample-mismatch',different_tiles=len(differences))
            raise SystemExit(1)
        status('planet-complete-and-verified',quality_tiles_checked=len(selected),
            archive_bytes=(root/'tiles/contours.mbtiles').stat().st_size)
    except Exception as exc:
        status('planet-runner-error',error=str(exc))
        raise
    finally:
        lock.seek(0)
        msvcrt.locking(lock.fileno(),msvcrt.LK_UNLCK,1)
