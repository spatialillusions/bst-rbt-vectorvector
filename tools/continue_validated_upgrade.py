"""Audit/import a frozen upgrade, then start its frozen planet supervisor.

The import is separately memory/disk guarded. No supervisor starts if any import
or identity check fails. Existing producer artifacts/fingerprints stay untouched.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('old','new','snapshot','validation','identity-helper'):
        p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--java',required=True)
    a=p.parse_args()
    tools=Path(__file__).resolve().parent
    new=a.new.resolve()
    def stage(value):
        status={'stage':value,'old':str(a.old.resolve()),'new':str(new)}
        temp=new/'upgrade-status.json.tmp';temp.write_text(json.dumps(status,indent=2));temp.replace(new/'upgrade-status.json')
        print(value,flush=True)
    stage('auditing-and-importing-completed-shards')
    command=[sys.executable,str(tools/'adopt_validated_shards.py'),
        '--old',str(a.old.resolve()),'--new',str(new),'--snapshot',str(a.snapshot.resolve()),
        '--validation',str(a.validation.resolve()),'--identity-helper',str(a.identity_helper.resolve()),'--java',a.java]
    try:
        monitor=new/'import-monitor'
        attempt=1
        while monitor.exists():
            attempt+=1;monitor=new/f'import-monitor-{attempt:04d}'
        subprocess.run([sys.executable,str(tools/'monitor_job.py'),'--out',str(monitor),
            '--low-memory-priority','--min-available-mib','3072','--min-disk-free-gib','20',
            '--disk-path',str(new),'--',*command],check=True)
        stage('checking-frozen-supervisor-identities')
        supervisor=[sys.executable,str(new/'operations/planet_runner.py'),str(new)]
        subprocess.run(supervisor+['--check-only'],check=True)
        stage('planet-supervisor-starting')
        with (new/'supervisor.stdout.log').open('a') as out,(new/'supervisor.stderr.log').open('a') as err:
            child=subprocess.Popen(supervisor,cwd=new,stdout=out,stderr=err)
            (new/'runner.pid').write_text(str(child.pid))
            code=child.wait()
        if code:
            raise RuntimeError('Planet supervisor stopped with exit code '+str(code))
        stage('planet-supervisor-finished')
    except Exception:
        stage('upgrade-stopped-inspect-logs')
        raise


if __name__=='__main__':
    main()
