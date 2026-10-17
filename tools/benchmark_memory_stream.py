"""A/B the image-stream backend and confirm the final worker count without mixing executables."""
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
    parser.add_argument('--old', type=Path, default=root / 'out/performance/optimized-cli.jar')
    parser.add_argument('--new', type=Path, default=root / 'out/performance/optimized-memory-cli.jar')
    args = parser.parse_args()
    summary_path = args.data / 'suite-summary.json'
    summary = json.loads(summary_path.read_text())
    destination = args.data / 'memory-stream'
    common = ['--out', str(destination), '--cache', str(summary['cache']),
              '--glacier-cache', str(summary['glacier_cache']), '--heap', '16g',
              '--osm', r'D:\OpenStreetMap\iceland-260630.osm.pbf'] + summary['gc_args']
    driver = root / 'tools/benchmark_contours.py'

    def execute(options):
        subprocess.run([sys.executable, str(driver)] + common + options, check=True)

    for case, options in [('mixed', ['--width', '16', '--repeats', '2']), ('shard', ['--resumable'])]:
        execute(['--jar', str(args.old), '--compare-jar', str(args.new), '--label', 'memory-io',
                 '--workers', str(summary['workers']), '--case', case] + options)
    rows = [json.loads(line) for line in (destination / 'results.jsonl').read_text().splitlines()]
    for case in ('mixed', 'shard'):
        selected = [r for r in rows if r['case'] == case]
        if len({r['payload_sha256'] for r in selected}) != 1:
            raise RuntimeError('Memory-backed image stream changed output')
    for workers in sorted({8, 12, 16} - {summary['workers']}):
        execute(['--jar', str(args.new), '--label', 'memory-final-worker', '--workers', str(workers),
                 '--case', 'shard', '--resumable'])
    rows = [json.loads(line) for line in (destination / 'results.jsonl').read_text().splitlines()]
    shard_rows = [r for r in rows if r['case'] == 'shard']
    if len({r['payload_sha256'] for r in shard_rows}) != 1:
        raise RuntimeError('Final worker comparison changed output')
    final_rows = [r for r in shard_rows if not r['name'].startswith('memory-io-baseline')]
    rates = {w: statistics.median(r['tiles_per_s'] for r in final_rows if r['workers'] == w)
             for w in {r['workers'] for r in final_rows}}
    summary['workers'] = min(w for w, speed in rates.items() if speed >= max(rates.values()) * .98)
    summary['memory_stream_worker_rates'] = rates
    summary['memory_stream_mixed_rates'] = {
        version: statistics.median(r['tiles_per_s'] for r in rows if r['case'] == 'mixed' and
                                  r['name'].startswith('memory-io-' + version))
        for version in ('baseline', 'optimized')}
    summary['memory_stream_validated'] = True
    for row in rows:
        with (args.data / 'results.jsonl').open('a') as stream:
            stream.write(json.dumps(row) + '\n')
    summary['runs'] = len((args.data / 'results.jsonl').read_text().splitlines())
    summary_path.write_text(json.dumps(summary, indent=2))
    print('MEMORY STREAM COMPLETE', json.dumps(summary), flush=True)


if __name__ == '__main__':
    main()
