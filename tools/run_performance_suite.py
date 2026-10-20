"""Run the hardware tuning study serially, after Explorer transfers finish.

Uses immutable baseline/optimized jars and verifies payload equality before recommendations.
All results are retained. No power settings, transfers or unrelated processes are changed.
"""
import argparse
from pathlib import Path
import json
import statistics
import subprocess
import sys
import time

import benchmark_contours as bench


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    root = Path(__file__).resolve().parents[1]
    parser.add_argument("--baseline", type=Path, default=root / "out/performance/baseline-cli.jar")
    parser.add_argument("--optimized", type=Path, default=root / "out/performance/optimized-cli.jar")
    parser.add_argument("--out", type=Path, default=root / "out/performance-clean")
    parser.add_argument("--osm", type=Path, default=Path(r"D:\OpenStreetMap\iceland-260630.osm.pbf"))
    parser.add_argument("--nvme-out", type=Path, default=Path(r"C:\Temp\vectorvector-performance"))
    parser.add_argument("--quiet-seconds", type=int, default=60)
    args = parser.parse_args()
    args.out = args.out.resolve()
    args.out.mkdir(parents=True, exist_ok=True)
    bench.wait_for_explorer(args.quiet_seconds)
    (args.out / "transfer-finished.json").write_text(json.dumps({"observed_idle_at": time.time(),
                                                              "quiet_seconds": args.quiet_seconds}))
    driver = root / "tools/benchmark_contours.py"

    def execute(label, jar, extra=(), osm=True, out=None):
        destination = out or args.out
        command = [sys.executable, str(driver), "--jar", str(jar.resolve()), "--out", str(destination.resolve()),
                   "--label", label, "--heap", "16g"]
        if osm:
            command += ["--osm", str(args.osm)]
        command += list(extra)
        print("RUN", json.dumps(command), flush=True)
        subprocess.run(command, check=True)

    def results(out=None):
        file = (out or args.out) / "results.jsonl"
        return [json.loads(line) for line in file.read_text().splitlines()] if file.exists() else []

    def check_equal(rows):
        hashes = {row["payload_sha256"] for row in rows}
        if len(hashes) != 1:
            raise RuntimeError("Output changed between configurations; stop tuning and inspect payloads")

    execute("workers", args.baseline, ["--compare-jar", str(args.optimized), "--workers", "1,2,4,6,8,12,16", "--repeats", "2"])
    calibration = results()
    check_equal(calibration)
    optimized = [r for r in calibration if r["name"].startswith("workers-optimized")]
    medians = {w: statistics.median(r["tiles_per_s"] for r in optimized if r["workers"] == w)
               for w in sorted({r["workers"] for r in optimized})}
    best = max(medians.values())
    workers = min(w for w, speed in medians.items() if speed >= best * .98)
    print("WORKER MEDIANS", json.dumps(medians), "selected", workers, flush=True)

    # Longer mixed-region runs: enough work to reduce JVM warmup as a fraction of processing time.
    for cache in (32, 128, 512):
        execute(f"cache{cache}", args.optimized,
                ["--workers", str(workers), "--width", "16", "--cache", str(cache), "--repeats", "2"])
    cache_rows = [r for r in results() if r["name"].startswith("cache")]
    check_equal(cache_rows)
    cache_medians = {c: statistics.median(r["tiles_per_s"] for r in cache_rows if r["cache"] == c) for c in (32, 128, 512)}
    cache_best = max(cache_medians.values())
    cache = min(c for c, speed in cache_medians.items() if speed >= cache_best * .98)
    print("CACHE MEDIANS", json.dumps(cache_medians), "selected", cache, flush=True)
    common = ["--workers", str(workers), "--cache", str(cache)]

    execute("g1-8g-region8", args.optimized, common + ["--width", "16", "--heap", "8g", "--jvm-arg=-XX:G1HeapRegionSize=8m", "--repeats", "2"])
    execute("parallel-8g", args.optimized, common + ["--width", "16", "--heap", "8g", "--gc", "parallel", "--repeats", "2"])
    gc_rows = [r for r in results() if r["requested_tiles"] == 5120]
    check_equal(gc_rows)
    choices = [("g1-16g", [r for r in cache_rows if r["cache"] == cache], [])]
    choices += [(label, [r for r in gc_rows if r["name"].startswith(label)], options) for label, options in (
        ("g1-8g-region8", ["--heap", "8g", "--jvm-arg=-XX:G1HeapRegionSize=8m"]),
        ("parallel-8g", ["--heap", "8g", "--gc", "parallel"]))]
    chosen = max(choices, key=lambda choice: statistics.median(r["tiles_per_s"] for r in choice[1]))
    tuned = common + chosen[2]
    print("GC SELECTED", chosen[0], flush=True)

    # Independent regions and exact ocean-output regression check.
    for case in ("ocean", "polar"):
        execute(f"verify-{case}", args.baseline, ["--compare-jar", str(args.optimized), "--case", case,
                "--width", "16", "--workers", str(workers), "--cache", str(cache), "--repeats", "2"], osm=case != "ocean")
        check_equal([r for r in results() if r["case"] == case])

    # Whole production-sized shard: all z9-13 tiles under z6/29/16, with real shard writes and merge.
    execute("warmup-full-shard", args.baseline, common + ["--case", "shard", "--resumable"])
    execute("sustained-baseline", args.baseline, common + ["--case", "shard", "--resumable"])
    execute("sustained-optimized", args.optimized, tuned + ["--case", "shard", "--resumable"])
    alternate_cache = 512 if cache != 512 else 128
    execute(f"sustained-optimized-cache{alternate_cache}", args.optimized,
            tuned + ["--cache", str(alternate_cache), "--case", "shard", "--resumable"])
    sustained = [r for r in results() if r["case"] == "shard"]
    check_equal(sustained)
    # Full z6 shards have much taller column scans than small windows. Prefer their cache result.
    cache_trials = [r for r in sustained if r["name"].startswith("sustained-optimized")]
    fastest_shard = max(cache_trials, key=lambda r: r["tiles_per_s"])
    cache = fastest_shard["cache"]
    tuned += ["--cache", str(cache)]
    nvme = args.nvme_out / args.out.name
    execute("storage-nvme", args.optimized, tuned + ["--case", "shard", "--resumable"], out=nvme)
    check_equal(sustained + results(nvme))
    for row in results(nvme):
        with (args.out / "results.jsonl").open("a") as stream:
            stream.write(json.dumps(row) + "\n")

    execute("profile-optimized", args.optimized, tuned + ["--width", "16", "--jfr"])
    check_equal([r for r in results() if r["requested_tiles"] == 5120])
    summary = {"workers": workers, "cache": cache, "gc_choice": chosen[0], "gc_args": chosen[2],
               "worker_medians": medians, "cache_medians": cache_medians,
               "full_shard_cache_trials": [{k: r[k] for k in ("cache", "processing_s", "wall_s", "peak_rss_mib")}
                                           for r in cache_trials],
               "runs": len(results()), "payload_checks": "all matched", "finished_at": time.time()}
    (args.out / "suite-summary.json").write_text(json.dumps(summary, indent=2))
    print("SUITE COMPLETE", json.dumps(summary), flush=True)


if __name__ == "__main__":
    main()
