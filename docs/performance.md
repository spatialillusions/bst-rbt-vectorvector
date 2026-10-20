# Contour performance study — 16 October 2026

The subsequent [bulk-merge study](merge-performance.md) measures the journal-free merge change.
The contour-generation measurements below describe the earlier executable and remain unchanged.
The [17 October planet pilots](planet-pilot-results.md) add complete global OSM indexes,
sustained regional recovery, glacier-clipping optimization and a merge larger than system RAM.
The [18 October memory implementation results](memory-implementation-results.md) cover
prepared spatial partitions, byte budgets, primitive line processing and spatial tile blocks.
Those newer measurements distinguish startup savings from sustained generation speed.

Measured on the local Core Ultra 7 258V (8 logical CPUs), 31.49 GiB RAM, plugged in, Windows Balanced power plan. Mapterhorn input is on D: (Kingston USB); normal output is on F: (Kingston USB). C: is the internal SK hynix NVMe drive.

Explorer's file transfer was allowed to finish before the clean suite started. Earlier contended diagnostic runs in `out/performance` are excluded from every timing below.

The final launcher defaults processed the 21,824-tile shard in **44.4 s**, versus **93.5 s** for the baseline. Including startup, writing and merging, wall time fell from **97.4 to 49.1 s** (1.98× faster). All **9,209 output tiles** are byte-identical.

## Recommended settings

- Tile workers: **8**.
- Elevation cache entries: **512** per cache.
- Glacier clipping cache entries: **1024**.
- Launcher JVM default: **16 GiB, G1** (`-Xmx16g -XX:+UseG1GC`).
- Alternative tested throughput mode: **8 GiB, Parallel GC** (`-Xmx8g -XX:+UseParallelGC`).
- Completed runs: **69**; comparison status: **all matched**.

GC timing differences are not a statistically settled winner: the two mixed-run medians for 8 GiB G1/8 MiB regions and Parallel GC were within 2%, with substantial run variation. On the final shard, G1/16 GiB was within about 3% of the fastest final-code processing rate. The launcher keeps G1/16 GiB for full-OSM heap headroom. The complete planet glacier/peak indexes need their own memory pilot before lowering that limit.

Run the tuned defaults with `./run-planet-sharded.ps1`; use `-DryRun` to inspect the command.

## Worker scaling

Two fresh JVMs per executable/worker count, interleaved in fixed randomized order. Each visits the same 1,280 z12–13 tiles across Iceland, the Alps, Himalaya and Netherlands. Glacier splitting and peaks use the Iceland OSM extract; outside Iceland this test measures DEM processing without those OSM features. The max heap is 16 GiB with G1.

This table compares the baseline to the compute optimizations. The later memory-backed image-stream change is measured separately below, and final recommendations also use full-shard trials.

| Workers | Baseline median (range), tiles/s | Optimized median (range), tiles/s | Speedup | Optimized CPU | Peak RSS MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 34.9 (33.1–36.7) | 37.4 (31.2–43.6) | 1.07× | 15.4% | 1795 |
| 2 | 57.5 (52.3–62.8) | 62.6 (60.1–65.0) | 1.09× | 26.4% | 2226 |
| 4 | 80.4 (66.0–94.8) | 97.6 (95.3–99.9) | 1.21× | 48.5% | 3152 |
| 6 | 100.9 (86.5–115.2) | 101.4 (99.7–103.0) | 1.00× | 62.7% | 3224 |
| 8 | 114.0 (100.7–127.3) | 133.7 (109.9–157.6) | 1.17× | 77.9% | 3883 |
| 12 | 126.6 (119.6–133.6) | 130.2 (120.6–139.8) | 1.03× | 72.0% | 4027 |
| 16 | 147.1 (140.9–153.4) | 110.8 (100.4–121.2) | 0.75× | 73.4% | 4691 |

CPU is Java process CPU time divided by elapsed processing time and eight CPUs. Tiles/s counts visited tiles, including tiles that correctly produce no output. Startup and final writer draining/merge are excluded from that rate; wall time includes both.

## Cache and garbage collector comparisons

Longer mixed runs visit 5,120 tiles. Entries are medians of two runs.

| Configuration | Cache entries | Tiles/s | CPU | Peak RSS MiB | GC pause ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| cache32 | 32 | 96.3 | 68.7% | 2787 | 746 |
| cache128 | 128 | 123.8 | 66.2% | 4145 | 1007 |
| cache512 | 512 | 150.4 | 70.9% | 4708 | 975 |
| g1-8g-region8 | 512 | 162.4 | 81.0% | 3597 | 1294 |
| parallel-8g | 512 | 165.1 | 72.7% | 8242 | 1466 |

## Complete production-sized shard and storage

All 21,824 tiles at z9–13 under z6/29/16, with OSM features, real resumable shard writes and final merge. These longer runs test column-scan cache behavior absent from tiny windows. Each row is one sustained run, so small differences should not be treated as statistically settled.

| Configuration | Workers | DEM / glacier cache | Heap / GC | Output tiles | Processing tiles/s | Wall seconds | CPU | Peak RSS MiB |
| --- | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: |
| sustained-baseline | 8 | 512 / 256 | 16g / g1 | 9209 | 233.5 | 97.4 | 61.3% | 6310 |
| sustained-optimized | 8 | 512 / 256 | 8g / parallel | 9209 | 335.8 | 68.6 | 54.4% | 8257 |
| sustained-optimized-cache128 | 8 | 128 / 256 | 8g / parallel | 9209 | 296.4 | 77.3 | 57.4% | 5100 |
| storage-nvme | 8 | 512 / 256 | 8g / parallel | 9209 | 361.6 | 63.1 | 57.2% | 8225 |
| confirm-worker | 12 | 512 / 256 | 8g / parallel | 9209 | 376.4 | 61.5 | 63.7% | 8270 |
| confirm-worker | 16 | 512 / 256 | 8g / parallel | 9209 | 411.7 | 56.7 | 66.5% | 8334 |
| confirm-repeat | 16 | 512 / 256 | 8g / parallel | 9209 | 378.7 | 61.3 | 67.0% | 8264 |
| confirm-repeat | 12 | 512 / 256 | 8g / parallel | 9209 | 424.3 | 55.1 | 63.6% | 8340 |
| glacier-cache512 | 12 | 512 / 512 | 8g / parallel | 9209 | 435.8 | 53.7 | 73.9% | 8412 |
| glacier-cache1024 | 12 | 512 / 1024 | 8g / parallel | 9209 | 533.9 | 44.5 | 80.1% | 8172 |
| memory-io-optimized | 12 | 512 / 1024 | 8g / parallel | 9209 | 506.1 | 46.6 | 81.3% | 8198 |
| memory-io-baseline | 12 | 512 / 1024 | 8g / parallel | 9209 | 446.2 | 53.0 | 79.7% | 8129 |
| memory-final-worker | 8 | 512 / 1024 | 8g / parallel | 9209 | 506.9 | 46.8 | 68.2% | 8401 |
| memory-final-worker | 16 | 512 / 1024 | 8g / parallel | 9209 | 464.7 | 50.4 | 82.8% | 8272 |
| production-defaults | 8 | 512 / 1024 | 16g / g1 | 9209 | 491.9 | 49.1 | 69.7% | 7974 |

NVMe output artifacts are under `C:\Temp\vectorvector-performance`; other artifacts are under `out/performance-clean`. The input archive is unchanged for both output-disk tests. Windows file cache is warm; the whole planet was not copied to NVMe or RAM.

## Ocean and polar checks

| Case | Baseline tiles/s | Optimized tiles/s | Speedup | Payload comparison |
| --- | ---: | ---: | ---: | --- |
| ocean | 9862.1 | 16715.5 | not interpreted | identical |
| polar | 190.4 | 306.0 | 1.61× | identical |

Polar cases cover Greenland and the Antarctic coast using the planet DEM. They do not include their local OSM glacier geometries in this study. The Pacific ocean window has no output and almost no DEM payload reads; its 0.07–0.24 second processing intervals check missing-data handling but are too short for a production speed claim.

## Image input stream I/O

The same already-loaded encoded DEM bytes were decoded through the registered ImageIO provider with disk caching disabled for that stream. Pixel readers and parameters are unchanged. This avoids temporary file copies without changing global ImageIO settings.

| Case / stream | Median tiles/s | Read MiB | Write MiB |
| --- | ---: | ---: | ---: |
| mixed / disk cache | 230.1 | 621.2 | 246.4 |
| mixed / memory cache | 232.1 | 211.2 | 36.9 |
| shard / disk cache | 446.2 | 822.9 | 308.8 |
| shard / memory cache | 506.1 | 286.8 | 23.8 |

I/O figures are Java logical process I/O during tile processing. Startup and final writer draining/merge are outside that interval. Mixed runs have two repetitions per stream; full-shard stream pairs have one each, so small timing differences are not interpreted as conclusive. All payloads match.

## First visit to additional terrain

Andes and New Zealand windows were visited after tuning, without deliberately flushing Windows caches or evicting other applications. The final build ran first; the original baseline then supplied an exact payload reference. These are not a fair cold/warm speed pair.

| Run | Tiles/s | CPU | Physical disk reads MiB/s | Matching payloads |
| --- | ---: | ---: | ---: | --- |
| first-visit-final-fresh-w8-c512-parallel-r0 | 216.6 | 78.6% | 10.9 | yes |
| fresh-payload-reference-fresh-w8-c512-parallel-r0 | 188.4 | 80.4% | 0.1 | yes |

Physical counters cover the whole machine. First visit does not guarantee a completely cold filesystem cache; actual observed read traffic is reported instead.

## Changes and correctness

- Resampling hoists the x coordinate mapping out of the pixel-row loop, keeping the original float operation order.
- Blur scratch arrays are reused per processing thread. Returned grids retain independent output arrays.
- A completely valid zero-elevation blur footprint returns an exact zero grid. The check includes the kernel halo and neighbours.
- Isoline generation with no requested levels returns immediately instead of scanning every cell.
- Encoded image bytes use a memory-backed ImageIO stream, preserving registered pixel-reader selection.

All comparisons hash ordered tile keys and raw compressed tile payloads. Missing, extra, or different output causes the suite to stop. Unit checks cover float bit patterns, NaNs, clamped edges, retained blur outputs and relief in the neighbour halo. Smoothing, intervals, DEM resolution and glacier geometry rules are unchanged.

The final Maven package run completed **132 tests: 129 passed, 3 existing diagnostic skips, 0 failures/errors**. Default contour golden hashes remain unchanged.

## Reproduce and interpret

`tools/run_performance_suite.py` waits for 60 seconds of quiet Explorer I/O, then runs the serialized study. `tools/benchmark_contours.py` can run individual cases and records the exact JVM/CLI command, jar hash, per-run GC log, Windows process telemetry and physical disk counters. Disk counters cover the whole machine, not just Java. JFR is used only in separate profile runs, excluded from timing tables.

The sustained follow-up scripts are `tools/confirm_shard_workers.py` and `tools/benchmark_memory_stream.py`; `tools/report_performance.py` renders the combined results. Supply new output directories for new runs. Immutable jars and exact command arrays are retained alongside each result; the final jar SHA-256 is in `suite-summary.json`.

The small worker sweep includes JVM warmup; the full shard is the sustained check. Repeated-run medians reduce noise but are not confidence intervals. Laptop temperature, background apps and power policy can affect results. No system power or security settings were changed. There is no multi-day/full-planet throughput claim, and no native-z13 DEM benchmark: the provided planet archive ends at z12 and z13 is overzoomed.
