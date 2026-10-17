# Development and validation tools

The Java CLI needs JDK 21; Python is optional. Run tools from the project root. Do not treat
research scripts as supported production CLI options.

## Repository-only workflow

`python tools/generate_example.py` generates contours from committed elevation
fixtures after the Java build. It uses only Python's standard library. Install
`requirements-verify.txt`, then run `python tools/verify_contours.py <archive>`
for streaming, read-only schema/integrity checks with no RBT data. Run its tests
with `python -m unittest discover -s tools -p test_verify_contours.py -v`.
The default style in `styles/` likewise uses only our generated output.

RBT extraction, tuning, comparison and comparison-rendering scripts are optional
research tools. They require separately supplied reference inputs; historical
reports and machine-specific benchmark defaults are not setup requirements.

## Python setup

The analysis dependencies in `requirements.txt` are the versions tested here with Python 3.14.
Use an isolated environment, rather than the old machine-specific `.tools/venv`:

```powershell
py -3.14 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r tools/requirements.txt
.\.venv\Scripts\python.exe tools/rbt_reference/compare.py --help
```

If the Python launcher is unavailable, substitute the installed interpreter path. Activate the
environment or use its interpreter explicitly. In Git Bash, `tune.sh` uses `python` on PATH;
`PYTHON` can name another interpreter. Tuning labels must be simple filenames and each run uses
a fresh output directory, preserving earlier results.

## Tool groups

| Purpose | Tools | Requirements/limits |
|---|---|---|
| Compare ordered MBTiles keys and payloads | `compare_tiles.py` | Python standard library; streams data with bounded memory |
| Extract/analyze/compare RBT samples | `rbt_reference/` | Optional analysis packages; original archive read-only |
| Generate candidate/RBT comparison style | `make_compare_style.py` | Python standard library; style/sprite inputs documented in `styles/README.md` |
| Measure CLI contour performance | `benchmark_contours.py`, `run_performance_suite.py` | Windows telemetry; fixed workloads and saved executable versions; see `docs/performance.md` |
| Reproduce focused tuning measurements | `confirm_shard_workers.py`, `benchmark_memory_stream.py`, `report_performance.py` | Existing suite artifacts and baseline executables; historical experiment configuration |
| Compare merge SQL and actual JDBC merge | `benchmark_merge.py`, `MergeBenchmark.java` | Disposable synthetic sources under `out/`; see `docs/merge-performance.md` |
| Prepare global glacier/peak OSM inputs | `prepare_contour_osm.py` | Install `requirements-osm.txt`; preserves references and validates before publishing |
| Prepare supplemental Antarctic ice polygons | `prepare_ice_sheet.py` | Install `requirements-ice.txt`; official ZIP to WGS84 WKB, with source hashes and provenance |
| Estimate global output/shard storage | `estimate_planet_storage.py` | Seeded uniform per-zoom samples; standard library; measured page-layout overhead |
| Compare contour-only output size with RBT | `compare_contour_sizes.py` | Standard library; same stratified sample, encoded layers copied verbatim and equally compressed |
| Verify every merged key/payload against completed shards | `verify_shard_merge.py` | Standard library; frozen merger copy order, full destination integrity, completion stamps; at most two open databases |
| Measure global-index, recovery and quality pilots | `run_planet_pilot.py`, `monitor_job.py` | Windows telemetry; frozen executable, fresh output directories, optional low-memory stop |
| Optional reference comparisons: seams, ice and peak output; render RBT comparison style | `rbt_reference/check_planet_pilot.py`, `render_planet_pilot.py` | Analysis dependencies; rendering also requires TileServer GL and RBT data/styles/fonts |
| Investigate 10 m / z14 contours | `ContourDetailProbe.java`, `investigate_contour_detail.py`, `analyze_contour_detail.py` | Research-only harness; production option not implemented; see the investigation report |
| Diagnose terrain/reference and glacier clipping differences | `ContourSurfaceProbe.java`, `GlacierClipProbe.java` | Research harnesses using real inputs; clipping probe exports local masks for geometric comparison |

Performance runs should be serialized, with unrelated transfers finished. Retain commands,
executable hashes, source identities and output checks alongside measurements. Default benchmark
paths reflect the documented local test machine and can be overridden through tool arguments.
The Java research harnesses use current internal constructor/method signatures and may need
updating after refactoring. They are not included in the production executable.

Reference layers can use different coordinate extents. The comparison normalizes glacier polygons
to 4096 contour units (RBT uses 16384 for z13 polygons). Run the regression with
`python -m unittest discover -s tools -p test_reference_ice_extent.py -v` after installing the
analysis requirements. Historical ice-agreement scores from before this correction are invalid
at affected zooms; contour geometry and production tiles are unchanged.

## Reference-complete OSM preparation

```powershell
python -m pip install -r tools/requirements-osm.txt
python tools/prepare_contour_osm.py D:/OpenStreetMap/planet-260921.osm.pbf D:/OpenStreetMap/contour-features.osm.pbf
python -m unittest discover -s tools -p test_prepare_contour_osm.py -v
```

The source is read-only. Use a new destination: existing or incomplete outputs are refused.
The tool selects glacier features and peak/volcano nodes, collects their referenced objects,
preserves tags, checks every node/way/relation reference, flushes the file and publishes it.
It writes size, time, counts and SHA-256 evidence next to the result. The default relation-depth
limit is eight; incomplete references fail validation rather than silently publishing a partial
extract. This does not repair invalid source geometry or guarantee that every OSM glacier is valid.

`run_planet_pilot.py prepare` freezes the current executable and writes fixed workloads;
`sustained` deliberately stops after two completed shards and resumes the same build, while
`quality` generates bounded windows including dateline and latitude-limit tiles. Its local
defaults are configurable with `--help`. The monitor stops only its own subprocess when the
configured available-memory floor is crossed for five consecutive samples.
`--min-disk-free-gib` similarly stops only that child after five samples below the requested
free-space floor on any watched volume (working directory, temporary directory and `--disk-path`).
It records `disk_floor_stop` separately from memory stops. Use a reserve for long production runs.

For a large synthetic merge, pass `--shards N --prepare-only` to `benchmark_merge.py`, then
use `MergeBenchmark.java ... --streaming-verify`. This mode is only for those synthetic sources:
it verifies every output key/payload against the seed, checks the full SQLite integrity and tile
count, and removes only the verified output. Its digest follows physical row order and must not
be compared with the helper's default ordered digest. Verification is outside the merge timing.

`--reuse-sources` avoids regenerating an existing complete synthetic workload. The Java helper
accepts `--page-size=4096|16384|65536`, `--random-lookups=N` and `--verify-existing` for controlled
layout studies and separate verification. Its full integrity check uses a bounded 512 MiB cache;
streaming checks and random reads use smaller caches.

On Windows, a random integrity scan can grow the system file cache even with a small JVM heap.
The monitor's `--low-memory-priority` changes only its child process's memory priority.
`--low-memory-priority-after MERGE_TIMED_COMPLETE` keeps timed copying at normal priority and
applies low memory priority during verification. Serialize builds, benchmarks and verification;
do not change system-wide cache settings to improve a result.

## Supplemental Antarctic ice

```powershell
python -m pip install -r tools/requirements-ice.txt
python tools/prepare_ice_sheet.py --download --out antarctica-ice.wkb
python -m unittest discover -s tools -p test_prepare_ice_sheet.py -v
```

Use `--zip <archive>` for an existing download. The converter reads the official split
EPSG:3857 polygon shapefile without extracting it and preserves holes while converting to WGS84.
The CLI loader enforces Mercator latitude limits. The converter refuses an existing output and records source
and output hashes. Pass `--glacierWkb <file>` to the CLI or `-GlacierWkb <file>` to the launcher;
`run_planet_pilot.py` accepts `--glacier-wkb`. These polygons supplement the global glacier PBF.

## Windows memory pressure

See [the planet memory-pressure investigation](../docs/windows-memory-pressure.md).
`monitor_job.py --low-memory-priority-on-pressure-mib 4096` can lower its own
child's memory retention priority after five low-headroom samples while keeping
the stop guard enabled. This is opt-in and is not a hard cache cap. The read-only
`watch_memory.py --pid <pid> --out <fresh.jsonl>` records per-application attribution
alongside existing jobs. Both use only Python's standard library on Windows.
