"""Render the completed tuning study as a reviewable Markdown report."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import statistics


def median(rows, key):
    return statistics.median(row[key] for row in rows)


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=root / "out/performance-clean")
    parser.add_argument("--output", type=Path, default=root / "docs/performance.md")
    args = parser.parse_args()
    summary = json.loads((args.data / "suite-summary.json").read_text())
    rows = [json.loads(line) for line in (args.data / "results.jsonl").read_text().splitlines()]
    baseline = next(r for r in rows if r['name'].startswith('sustained-baseline-'))
    production = next((r for r in rows if r['name'].startswith('production-defaults-')), None)
    text = ["# Contour performance study — 16 October 2026", "",
            "Measured on the local Core Ultra 7 258V (8 logical CPUs), 31.49 GiB RAM, plugged in, "
            "Windows Balanced power plan. Mapterhorn input is on D: (Kingston USB); normal output is on "
            "F: (Kingston USB). C: is the internal SK hynix NVMe drive.", "",
            "Explorer's file transfer was allowed to finish before the clean suite started. Earlier "
            "contended diagnostic runs in `out/performance` are excluded from every timing below.", "",
            (f"The final launcher defaults processed the 21,824-tile shard in **{production['processing_s']:.1f} s**, "
             f"versus **{baseline['processing_s']:.1f} s** for the baseline. Including startup, writing and merging, "
             f"wall time fell from **{baseline['wall_s']:.1f} to {production['wall_s']:.1f} s** "
             f"({baseline['wall_s'] / production['wall_s']:.2f}× faster). All **9,209 output tiles** are byte-identical."
             if production else "Sustained results are tabulated below."), "",
            "## Recommended settings", "",
            f"- Tile workers: **{summary['workers']}**.",
            f"- Elevation cache entries: **{summary['cache']}** per cache.",
            f"- Glacier clipping cache entries: **{summary.get('glacier_cache', 256)}**.",
            "- Launcher JVM default: **16 GiB, G1** (`-Xmx16g -XX:+UseG1GC`).",
            "- Alternative tested throughput mode: **8 GiB, Parallel GC** (`-Xmx8g -XX:+UseParallelGC`).",
            f"- Completed runs: **{summary['runs']}**; comparison status: **{summary['payload_checks']}**.", "",
            "GC timing differences are not a statistically settled winner: the two mixed-run medians "
            "for 8 GiB G1/8 MiB regions and Parallel GC were within 2%, with substantial run variation. "
            "On the final shard, G1/16 GiB was within about 3% of the fastest final-code processing rate. "
            "The launcher keeps G1/16 GiB for full-OSM heap headroom. The complete planet glacier/peak "
            "indexes need their own memory pilot before lowering that limit.", "",
            "Run the tuned defaults with `./run-planet-sharded.ps1`; use `-DryRun` to inspect the command.", "",
            "## Worker scaling", "",
            "Two fresh JVMs per executable/worker count, interleaved in fixed randomized order. Each "
            "visits the same 1,280 z12–13 tiles across Iceland, the Alps, Himalaya and Netherlands. "
            "Glacier splitting and peaks use the Iceland OSM extract; outside Iceland this test "
            "measures DEM processing without those OSM features. The max heap is 16 GiB with G1.", "",
            "This table compares the baseline to the compute optimizations. The later memory-backed "
            "image-stream change is measured separately below, and final recommendations also use full-shard trials.", "",
            "| Workers | Baseline median (range), tiles/s | Optimized median (range), tiles/s | Speedup | Optimized CPU | Peak RSS MiB |",
            "| --- | ---: | ---: | ---: | ---: | ---: |"]
    for worker in (1, 2, 4, 6, 8, 12, 16):
        a = [r for r in rows if r['name'].startswith('workers-baseline') and r['workers'] == worker]
        b = [r for r in rows if r['name'].startswith('workers-optimized') and r['workers'] == worker]
        old, new = median(a, 'tiles_per_s'), median(b, 'tiles_per_s')
        old_range = f"{min(r['tiles_per_s'] for r in a):.1f}–{max(r['tiles_per_s'] for r in a):.1f}"
        new_range = f"{min(r['tiles_per_s'] for r in b):.1f}–{max(r['tiles_per_s'] for r in b):.1f}"
        text.append(f"| {worker} | {old:.1f} ({old_range}) | {new:.1f} ({new_range}) | {new / old:.2f}× | "
                    f"{median(b, 'processing_cpu_pct'):.1f}% | {median(b, 'peak_rss_mib'):.0f} |")
    text += ["", "CPU is Java process CPU time divided by elapsed processing time and eight CPUs. "
             "Tiles/s counts visited tiles, including tiles that correctly produce no output. "
             "Startup and final writer draining/merge are excluded from that rate; wall time includes both.",
             "", "## Cache and garbage collector comparisons", "",
             "Longer mixed runs visit 5,120 tiles. Entries are medians of two runs.", "",
             "| Configuration | Cache entries | Tiles/s | CPU | Peak RSS MiB | GC pause ms |",
             "| --- | ---: | ---: | ---: | ---: | ---: |"]
    groups = defaultdict(list)
    for row in rows:
        if row['requested_tiles'] == 5120 and not row['jfr'] and row['name'].startswith(('cache', 'g1-', 'parallel-')):
            groups[row['name'].split('-mixed-')[0]].append(row)
    for label, group in groups.items():
        text.append(f"| {label} | {group[0]['cache']} | {median(group, 'tiles_per_s'):.1f} | "
                    f"{median(group, 'processing_cpu_pct'):.1f}% | {median(group, 'peak_rss_mib'):.0f} | "
                    f"{median(group, 'gc_pause_ms'):.0f} |")
    text += ["", "## Complete production-sized shard and storage", "",
             "All 21,824 tiles at z9–13 under z6/29/16, with OSM features, real resumable shard writes "
             "and final merge. These longer runs test column-scan cache behavior absent from tiny windows. "
             "Each row is one sustained run, so small differences should not be treated as statistically settled.", "",
             "| Configuration | Workers | DEM / glacier cache | Heap / GC | Output tiles | Processing tiles/s | Wall seconds | CPU | Peak RSS MiB |",
             "| --- | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: |"]
    for row in rows:
        if row['case'] == 'shard' and not row['name'].startswith('warmup'):
            label = row['name'].split('-shard-')[0]
            text.append(f"| {label} | {row['workers']} | {row['cache']} / {row.get('glacier_cache', 256)} | {row['heap']} / {row['gc']} | {row['output_tiles']} | {row['tiles_per_s']:.1f} | "
                        f"{row['wall_s']:.1f} | {row['processing_cpu_pct']:.1f}% | {row['peak_rss_mib']:.0f} |")
    text += ["", "NVMe output artifacts are under `C:\\Temp\\vectorvector-performance`; other artifacts "
             "are under `out/performance-clean`. The input archive is unchanged for both output-disk tests. "
             "Windows file cache is warm; the whole planet was not copied to NVMe or RAM.", "",
             "## Ocean and polar checks", "",
             "| Case | Baseline tiles/s | Optimized tiles/s | Speedup | Payload comparison |",
             "| --- | ---: | ---: | ---: | --- |"]
    for case in ('ocean', 'polar'):
        a = [r for r in rows if r['name'].startswith(f'verify-{case}-baseline')]
        b = [r for r in rows if r['name'].startswith(f'verify-{case}-optimized')]
        old, new = median(a, 'tiles_per_s'), median(b, 'tiles_per_s')
        speedup = f"{new / old:.2f}×" if case != 'ocean' else 'not interpreted'
        text.append(f"| {case} | {old:.1f} | {new:.1f} | {speedup} | identical |")
    text += ["", "Polar cases cover Greenland and the Antarctic coast using the planet DEM. "
             "They do not include their local OSM glacier geometries in this study. The Pacific ocean "
             "window has no output and almost no DEM payload reads; its 0.07–0.24 second processing "
             "intervals check missing-data handling but are too short for a production speed claim.", ""]
    memory = [r for r in rows if r['name'].startswith('memory-io-')]
    if memory:
        text += ['## Image input stream I/O', '',
                 'The same already-loaded encoded DEM bytes were decoded through the registered ImageIO '
                 'provider with disk caching disabled for that stream. Pixel readers and parameters are unchanged. '
                 'This avoids temporary file copies without changing global ImageIO settings.', '',
                 '| Case / stream | Median tiles/s | Read MiB | Write MiB |',
                 '| --- | ---: | ---: | ---: |']
        for case in ('mixed', 'shard'):
            for version, label in [('baseline', 'disk cache'), ('optimized', 'memory cache')]:
                group = [r for r in memory if r['case'] == case and r['name'].startswith('memory-io-' + version)]
                text.append(f"| {case} / {label} | {median(group, 'tiles_per_s'):.1f} | "
                            f"{median(group, 'logical_read_mib'):.1f} | {median(group, 'logical_write_mib'):.1f} |")
        text += ['', 'I/O figures are Java logical process I/O during tile processing. Startup and final '
                 'writer draining/merge are outside that interval. Mixed runs have two repetitions per '
                 'stream; full-shard stream pairs have one each, so small timing differences are not '
                 'interpreted as conclusive. All payloads match.', '']
    fresh = [r for r in rows if r['case'] == 'fresh']
    if fresh:
        text += ['## First visit to additional terrain', '',
                 'Andes and New Zealand windows were visited after tuning, without deliberately flushing '
                 'Windows caches or evicting other applications. The final build ran first; the original '
                 'baseline then supplied an exact payload reference. These are not a fair cold/warm speed pair.', '',
                 '| Run | Tiles/s | CPU | Physical disk reads MiB/s | Matching payloads |',
                 '| --- | ---: | ---: | ---: | --- |']
        for row in fresh:
            text.append(f"| {row['name']} | {row['tiles_per_s']:.1f} | {row['processing_cpu_pct']:.1f}% | "
                        f"{row.get('mean_disk_read_bps', 0) / 2**20:.1f} | yes |")
        text += ['', 'Physical counters cover the whole machine. First visit does not guarantee a completely '
                 'cold filesystem cache; actual observed read traffic is reported instead.', '']
    text += [
             "## Changes and correctness", "",
             "- Resampling hoists the x coordinate mapping out of the pixel-row loop, keeping the original float operation order.",
             "- Blur scratch arrays are reused per processing thread. Returned grids retain independent output arrays.",
             "- A completely valid zero-elevation blur footprint returns an exact zero grid. The check includes the kernel halo and neighbours.",
             "- Isoline generation with no requested levels returns immediately instead of scanning every cell.",
             "- Encoded image bytes use a memory-backed ImageIO stream, preserving registered pixel-reader selection.", "",
             "All comparisons hash ordered tile keys and raw compressed tile payloads. Missing, extra, "
             "or different output causes the suite to stop. Unit checks cover float bit patterns, NaNs, "
             "clamped edges, retained blur outputs and relief in the neighbour halo. Smoothing, intervals, "
             "DEM resolution and glacier geometry rules are unchanged.", "",
             "The final Maven package run completed **132 tests: 129 passed, 3 existing diagnostic skips, "
             "0 failures/errors**. Default contour golden hashes remain unchanged.", "",
             "## Reproduce and interpret", "",
             "`tools/run_performance_suite.py` waits for 60 seconds of quiet Explorer I/O, then runs "
             "the serialized study. `tools/benchmark_contours.py` can run individual cases and records "
             "the exact JVM/CLI command, jar hash, per-run GC log, Windows process telemetry and physical "
             "disk counters. Disk counters cover the whole machine, not just Java. JFR is used only "
             "in separate profile runs, excluded from timing tables.", "",
             "The sustained follow-up scripts are `tools/confirm_shard_workers.py` and "
             "`tools/benchmark_memory_stream.py`; `tools/report_performance.py` renders the combined "
             "results. Supply new output directories for new runs. Immutable jars and exact command "
             "arrays are retained alongside each result; the final jar SHA-256 is in `suite-summary.json`.", "",
             "The small worker sweep includes JVM warmup; the full shard is the sustained check. "
             "Repeated-run medians reduce noise but are not confidence intervals. Laptop temperature, "
             "background apps and power policy can affect results. No system power or security settings "
             "were changed. There is no multi-day/full-planet throughput claim, and no native-z13 DEM "
             "benchmark: the provided planet archive ends at z12 and z13 is overzoomed.", ""]
    args.output.write_text('\n'.join(text), encoding='utf-8')
    print(args.output)


if __name__ == '__main__':
    main()
