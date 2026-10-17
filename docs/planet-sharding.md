# Running resumable planet contour builds

The current implementation adapts the bounded worker queue and rectangular sharding from
`C:\Users\DarkStar\Documents\GitHub\vectorvector`. It retains this project's contour generation,
smoothing, clipping, glacier splitting, and layer schemas. The older copy was read as a reference;
it was not modified.

## Run

From the project directory in PowerShell:

```powershell
.\run-planet-sharded.ps1 -DryRun
.\run-planet-sharded.ps1
```

The launcher uses the existing `target/vectorvector-cli.jar`; it builds with the local JDK 21/Maven
toolchain if the jar is absent. Pass `-Build` to explicitly rebuild before a **new** run.

Defaults:

| Setting | Value |
| --- | --- |
| Elevation | `D:\Mapterhorn\mapterhorn.pmtiles` |
| Glaciers and peaks | `D:\OpenStreetMap\planet-260921.osm.pbf` |
| Output directory | `out\planet` under this project |
| Output archive | `contours.mbtiles` inside the output directory |
| Profile / output zooms | RBT, z9–13 |
| Partition roots | z6, 64 × 64 partitions: one z6 cell per shard |
| Workers | 8 tile workers, bounded queue of 16 tile IDs |
| JVM maximum heap | 16 GiB |
| Elevation caches | 512 entries each, including the shared payload cache |
| Glacier clipping cache | 1,024 entries |

The processing range begins at z6 so shards can be small; the RBT profile emits contours only
at z9–13. Each default shard visits 21,845 tile IDs through z13. This is a recovery unit,
not a prediction of output size or running time. Empty output shards are valid.

To select a prepared OSM feature extract, output disk, heap, or worker count:

```powershell
.\run-planet-sharded.ps1 -OsmPbf D:\OpenStreetMap\contour-features.osm.pbf `
    -GlacierWkb D:\OpenStreetMap\antarctica-ice.wkb `
    -OutputDir F:\contour-planet -Parallelism 8 -JavaHeap 16g
```

The OSM path in that example must exist. A filtered PBF must retain complete glacier ways,
multipolygon member ways, their referenced nodes, and peak/volcano tags. The full planet PBF is
also accepted, but startup normally reads it four times: peaks once and glaciers three times,
with additional passes for referenced nested relations when needed.
Only selected glacier and peak features are retained; the complete OSM planet is not held in RAM.
Selected features and their spatial indexes still occupy RAM. The
[global-index regional pilot](planet-pilot-results.md) measures their actual process footprint.

`-GlacierWkb` adds WGS84 polygon WKB/EWKB to the OSM glacier mask. For a global run, prepare the
Antarctic implicit ice polygons using [preflight](planet-preflight.md); the glacier PBF alone
does not contain all Antarctic ice. The loader preserves rock holes and repairs polygon pieces
without first unioning a continent. This file also forms part of the resume fingerprint.
`-NoOsm` cannot be combined with `-GlacierWkb`.

The launcher also exposes `-ElevationCacheTiles`, `-GlacierCacheTiles`, `-GarbageCollector g1|parallel`,
and `-G1HeapRegionSize auto|4m|8m|16m`. See [performance.md](performance.md) for the tuning study;
these controls allow its measured settings to be reproduced without editing the launcher.
The launcher retains a 16 GiB G1 heap for full-OSM memory headroom. The fastest measured regional
configuration used `-JavaHeap 8g -GarbageCollector parallel`; it used more resident RAM than G1
despite the smaller heap limit. Validate the complete OSM feature set before adopting that limit.

`-NoOsm` omits both glaciers and peaks. `-AdditionalPmtiles @('D:\...\6-33-22.pmtiles')` adds
higher-resolution elevation archives. The available `D:\Mapterhorn\mapterhorn.pmtiles` ends at z12;
z13 output uses the existing ancestor overzoom behavior unless higher-zoom archives are supplied.

Logs are timestamped under `<output>\logs`. Thirty-second metrics include heap usage. Shards run
sequentially, with parallel tile work inside each shard; elevation readers, OSM indexes, and caches
are shared across shards.

For a bounded pilot, pass `-Bbox 'minLon,minLat,maxLon,maxLat'` or `-TileList <file>` with a fresh
output directory. These select output tiles; they do not restrict OSM index loading. This makes
it possible to measure the global feature indexes while rendering a small area. See
[planet preflight](planet-preflight.md) and the measured readiness report before an unattended full run.

## Resume and recovery

Rerun **the same command with the same executable and inputs**. Do not rebuild first.
JVM heap/GC settings may be changed on retry: the fingerprint covers application arguments and
executable contents, not JVM options. Keep CLI worker/cache and other application options unchanged.

The work directory defaults to `<output>\.shards`. The CLI can override it with `--shardWorkDir`.
It contains a run fingerprint, an exclusive build lock, one MBTiles archive per output per shard,
and a `.done` marker per completed shard. A marker is published only after all tile work and all
asynchronous writers finish successfully. An incomplete shard is discarded and rebuilt on retry;
completed shards with matching file size and modification time are reused.

The fingerprint includes the exact CLI argument sequence, executable bytecode, and local input
paths, sizes, and modification times. Reordering arguments also changes the fingerprint.
Changed runs are rejected instead of mixing outputs. Start a new output/work directory for changed
inputs, settings, or builds. This deliberately uses file metadata rather than hashing hundreds of
gigabytes of input; keep input files immutable throughout a build. Resumable mode requires local
PMTiles files; ordinary nonresumable runs still support remote archives.

Merge copies tile blobs through SQLite with bounded memory into a sibling `.merging` file.
It rejects duplicate tile keys and atomically replaces the final archive only after a successful
close. A failed or interrupted merge preserves the previous final archive. Retry reruns the merge
from completed shards; merge itself has no checkpoint. The disposable merge database uses
`journal_mode=OFF` and `synchronous=OFF`, copies blobs without decoding them, and builds the unique
tile index once after copying every shard. Any overlap or other error discards that temporary
database. Completed shard archives are attached read-only. The finished database is closed and
explicitly flushed before atomic replacement of the final archive.

The final archive uses 64 KiB SQLite pages. Source tables are scanned directly, with at most
256 MiB of read-only mapping for the currently attached shard. The destination cache remains
bounded; sources are detached between shards. This avoids needless source sorting and reduces
small-page output I/O. Compressed tile payloads are unchanged.

Keep space for the shards, the new merged archive, index-sort temporary files, and any previous
final archive. The merge no longer creates rollback journals or a WAL; shard generation retains
its normal transaction behavior. Shards remain available after merging. See the
[merge benchmark](merge-performance.md) for measured results and recovery limits.

Tile failures stop the current build and identify the tile. No failed shard receives a completion
marker. If an already completed shard is externally damaged, remove its `.done` marker to force
that shard to rebuild; file stamps are not a full corruption audit. Final output filenames are
unchanged from existing CLI behavior: `-o` always names a directory.

## Scaling changes

- Fixed-size tile workers replace unbounded coroutine submission. Worker failures cancel queued work.
- Tile counts and progress use 64-bit values. Bounding boxes are counted with binary searches, and
  tile lists iterate their selected tiles without scanning the planet.
- SQLite writes use transactions of up to 1,000 tiles and a bounded queue of 200 writes. Writer
  errors fail the build; all writes drain before completion is recorded.
- Mapterhorn caches are configurable. Identical PMTiles payload offsets share decoded elevation
  grids, reducing repeated ocean decodes without changing terrain samples or contour decisions.
- Glacier node IDs are packed, sorted, and deduplicated as primitive longs. OSM decoding uses at
  most eight threads by default. The startup log no longer unions every glacier on the planet.
- Missing peak elevations are resolved and cached per peak when a tile needs them, before ranking,
  instead of sampling every peak in the world at startup.
- RBT blur reuses thread-confined scratch arrays while keeping returned grids independent. Elevation
  resampling computes column coordinates once per row set, preserving float arithmetic. A completely
  valid zero-elevation blur footprint skips box passes, and an empty contour-level set skips cell scans.
- Encoded DEM images use a memory-backed stream through the registered ImageIO provider, avoiding
  temporary disk copies of bytes already loaded into memory.

## Verification and scope

See [planet-validation.md](planet-validation.md) for measured checks. A streaming comparison tool,
using only the Python standard library, supports planet-size archives without loading every key:

```powershell
python tools\compare_tiles.py original.mbtiles sharded.mbtiles
```

The historical [planet plan](history/planet-build-plan.md) and [tickets](history/planet-implementation-tickets.md)
contain additional proposals. This change does not implement all of them: distributed workers,
land-only shard selection, a globally sorted bulk writer, and broader ocean pruning remain
possible follow-ups. The zero-elevation fast path checks the complete buffered blur footprint,
including neighbour samples, to preserve buffered boundary geometry.
A [six-region RBT comparison](rbt-global-validation.md) now covers 270 tiles outside Iceland;
it identifies unresolved Greenland position differences and a few missing Dutch 20 m contours.
The [subsequent readiness pilots](planet-pilot-results.md) measure sustained global-index memory,
recovery, storage and wider glacier/peak quality. The full planet remains a production pilot;
reference differences and native-z13 terrain limitations remain documented.
