"""Confirm the short-sweep worker choice on warmed, complete production-sized shards."""
import argparse
import json
from pathlib import Path
import statistics
import subprocess
import sys


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, default=root / 'out/performance-clean')
    parser.add_argument('--jar', type=Path, default=root / 'out/performance/optimized-cli.jar')
    args = parser.parse_args()
    summary_file = args.data / 'suite-summary.json'
    summary = json.loads(summary_file.read_text())
    all_rows = [json.loads(line) for line in (args.data / 'results.jsonl').read_text().splitlines()]
    existing = [r for r in all_rows if r['name'].startswith('sustained-optimized') and r['cache'] == summary['cache']]
    expected = {r['payload_sha256'] for r in existing}
    assert len(expected) == 1
    destination = args.data / 'worker-confirm'
    candidates = sorted({8, 12, 16, summary['workers']})
    records = list(existing)

    def execute(worker, label, glacier_cache=256):
        command = [sys.executable, str(root / 'tools/benchmark_contours.py'), '--jar', str(args.jar),
                   '--out', str(destination), '--label', label, '--workers', str(worker), '--cache', str(summary['cache']),
                   '--heap', '16g', '--case', 'shard', '--resumable',
                   '--glacier-cache', str(glacier_cache),
                   '--osm', r'D:\OpenStreetMap\iceland-260630.osm.pbf'] + summary['gc_args']
        subprocess.run(command, check=True)
        fresh = json.loads((destination / 'results.jsonl').read_text().splitlines()[-1])
        if fresh['payload_sha256'] not in expected:
            raise RuntimeError('Output mismatch in sustained worker confirmation')
        records.append(fresh)
        with (args.data / 'results.jsonl').open('a') as stream:
            stream.write(json.dumps(fresh) + '\n')

    for worker in candidates:
        if not any(r['workers'] == worker for r in records):
            execute(worker, 'confirm-worker')
    medians = {worker: statistics.median(r['tiles_per_s'] for r in records if r['workers'] == worker)
               for worker in candidates}
    ranked = sorted(candidates, key=medians.get, reverse=True)
    # Verify the leading pair again if there is a material apparent difference.
    if medians[ranked[0]] > medians[ranked[1]] * 1.05:
        for worker in ranked[:2]:
            execute(worker, 'confirm-repeat')
        medians = {worker: statistics.median(r['tiles_per_s'] for r in records if r['workers'] == worker)
                   for worker in candidates}
    summary['workers'] = min(w for w, speed in medians.items() if speed >= max(medians.values()) * .98)
    summary['sustained_worker_rates'] = medians
    # Glacier parents are also hierarchical LRU entries; verify their cache at production scan height.
    selected = summary['workers']
    for capacity in (512, 1024):
        execute(selected, f'glacier-cache{capacity}', glacier_cache=capacity)
    glacier_rates = {capacity: statistics.median(r['tiles_per_s'] for r in records
                     if r['workers'] == selected and r.get('glacier_cache', 256) == capacity)
                     for capacity in (256, 512, 1024)}
    summary['glacier_cache'] = min(c for c, speed in glacier_rates.items() if speed >= max(glacier_rates.values()) * .98)
    summary['glacier_cache_rates'] = glacier_rates
    summary['runs'] = len([line for line in (args.data / 'results.jsonl').read_text().splitlines() if line])
    summary_file.write_text(json.dumps(summary, indent=2))
    print('SUSTAINED WORKER CONFIRMATION', json.dumps(medians), 'selected', summary['workers'], flush=True)


if __name__ == '__main__':
    main()
