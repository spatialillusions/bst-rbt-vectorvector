# Planet build: implementation tickets (handover)

Historical proposals, including features that were not implemented. Use
[the runbook](../planet-sharding.md) and [preflight](../planet-preflight.md) for current behavior.

This document breaks [planet-build-plan.md](planet-build-plan.md) into small tickets. Each ticket says **why** it
exists, **which files** to touch, **what to do step by step**, **how to prove it works**, and **what to watch out
for**. Read sections 0 and 1 before starting any ticket.

Ticket ids are `PB-xx`. Work in the order of the dependency graph in section 2; tickets on the same level can be
done in parallel by different people.

---

## 0. Before you start

### 0.1 Set up

1. Clone the repo. Tools are not committed. Install JDK 21 and Maven 3.9+, or unpack them into `.tools/` the way
   `env.ps1` / `env.sh` expect (`.tools/jdk-21*`, `.tools/apache-maven-*`), then:
   * PowerShell: `. .\env.ps1`
   * Git Bash: `source ./env.sh`
2. `mvn -q -B test` must pass (about 120 tests) before you change anything.
3. Get the Iceland test data into `data/iceland/` (see `data/iceland/SOURCES.md` for the exact downloads):
   `mapterhorn-iceland-z0-12.pmtiles`, `iceland-latest.osm.pbf`, `rbt-reference-iceland.mbtiles`.
   Tests whose name ends in `DataTest` use these files and **skip themselves** when they are missing; run them
   once locally anyway.
4. Python tools: create a venv with `pip install mapbox-vector-tile shapely numpy matplotlib pillow`
   (the repo uses `.tools/venv`).
5. Do one full Iceland run so you know what "normal" looks like (about 15 minutes on 8 cores):
   ```
   mvn -q -B package -DskipTests
   java -Xmx8g -jar target/vectorvector-cli.jar --profile rbt --dataFormat mapterhorn \
     --pmtiles data/iceland/mapterhorn-iceland-z0-12.pmtiles --glaciers data/iceland/iceland-latest.osm.pbf --peaks \
     --bbox=-24.6,63.2,-13.3,66.7 -minZ 9 -maxZ 13 -f mbtiles -o out/contours
   python tools/rbt_reference/compare.py data/iceland/rbt-reference-iceland.mbtiles out/contours/contours.mbtiles --out out/compare/check
   ```
   The last line must say `41 checks, 0 failed`. **Keep this output.** Several tickets compare against it, so copy
   it to `out/baseline/contours.mbtiles`.

### 0.2 How the code works (the parts you will touch)

| Where | What it does |
|---|---|
| `Main.kt` | Parses options, builds the elevation store (`cachingElevationDataStore`), the contour profile, glaciers (`iceSplitterProvider`), peaks (`peakIndex`), one sink per output and one repository per output, then runs `Processor` once. |
| `Processor.kt` | Iterates `TileRange.tiles()` (depth-first: for each tile at `minZ`, all zooms below it) and calls `sink.accept(tile)` on up to 64 threads (`Dispatchers.IO`). |
| `TileRange.kt` | The set of tiles: a rectangle at `minZ` plus everything below it; optional `bounds` (`--bbox`) and `only` (`--tileList`) filters. |
| `sink/VectorTileSink.kt` | Per tile: contour layer (`contourLayer`) + extra layers (peaks), gzip, `repository.store(...)`. |
| `sink/contour/ContourPostProcessor.kt` | RBT pipeline: blur → isolines → Chaikin → RDP → ice split → clip → round. |
| `elevation/mapterhorn/MapterhornElevationDataStore.kt` | Turns Mapterhorn PMTiles into 512×512 elevation grids. `nativeCache` caches decoded tiles by `z/x/y`, `tileCache` caches output tiles. `MapterhornSourceSet.fetch` asks each archive in turn. |
| `pmtiles/PmtilesReader.kt` | Reads one PMTiles archive. `getTile(z, x, y)` finds the directory entry and reads the bytes. |
| `osm/GlacierLoader.kt`, `osm/GlacierIndex.kt` | Load glaciers from OSM PBF (3 passes), build per-tile glacier geometry. |
| `osm/Peaks.kt` | `PeakLoader` (1 pass) and `PeakIndex` (per-tile `peaks` layer). |
| `repository/MbtilesTileRepository.kt` | Writes tiles to SQLite on **one** writer thread fed by a queue. Today: one `INSERT` per tile, default journal and sync. |
| `sink/FaultBarrierTileSink.kt` | Catches exceptions per tile. **Exists but is not used yet.** |

### 0.3 Rules for every ticket

* **Never change the default (non-RBT) output.** `DefaultContourGoldenTest` pins upstream bytes. If it fails, your
  change leaked into the default path.
* **No change to RBT tile content** unless the ticket says so. The check is: run Iceland and compare with
  `out/baseline` using `tools/compare_tiles.py` (written in PB-00). Every tile must be byte-identical, except in
  PB-07, where empty tiles may disappear.
* Write tests in `src/test/kotlin/...` next to the code they test; use JUnit 5 + AssertJ like the existing tests.
  Tests that need big data files end in `DataTest` and use `assumeTrue(file.exists())`.
* Commit per ticket with a message starting with the ticket id, e.g. `PB-03: primitive node id collection`.
* Update the ticket's checkbox in this file when it is done.

### 0.4 Definition of done (all tickets)

- [ ] Code + tests committed, `mvn -q -B test` green.
- [ ] The ticket's own acceptance checks pass (write the numbers into the PR description).
- [ ] No new warnings in the Iceland run log.
- [ ] README / `--help` text updated if you added an option.

---

## 1. Glossary

* **Shard:** all output tiles under one z6 tile (`6/x/y`). The planet has 4,096; about 2,000 contain land.
* **TMS row:** MBTiles stores `tile_row = 2^z − 1 − y` (y flipped). The code already has `Int.toTms(z)`.
* **TMS order:** sorted by `(zoom_level, tile_column, tile_row)`. Writing in this order is what makes huge
  SQLite files fast (see planet plan §2).
* **Content key:** the byte offset of a tile's data inside a PMTiles archive. Identical tiles (e.g. sea) share one
  offset, so equal keys mean equal bytes.

---

## 2. Dependency graph

```
PB-00 (tools)
  ├─ PB-01 fault barrier ─────────────┐
  ├─ PB-02 startup log                │
  ├─ PB-03 node ids                   │
  ├─ PB-04 lazy peak ele              │
  ├─ PB-05 content key ─ PB-06 dedupe │
  ├─ PB-07 flat early exit            │
  ├─ PB-08 writer pragmas+batch ─ PB-09 writer modes ─┐
  └─ PB-10 RunContext refactor ───────┴───────────────┴─ PB-11 shard runner ─ PB-13 merge
                                                         PB-12 shard list ───┘
PB-14 threads/antimeridian (any time)   PB-15 pilot references (any time)
PB-16 data prep runbook → PB-17 pilot → PB-18 production
```

| Ticket | Title | Size | Depends on |
|---|---|---|---|
| PB-00 | Tile-by-tile compare tool | S | – |
| PB-01 | Fault barrier, failure file, exit code | S | PB-00 |
| PB-02 | Remove the planet-wide glacier union from startup | XS | – |
| PB-03 | Primitive node id collection | S | – |
| PB-04 | Lazy DEM elevation for peaks | M | PB-00 |
| PB-05 | PMTiles content key | S | – |
| PB-06 | Decoded-grid dedupe by content key | M | PB-05 |
| PB-07 | Early exit for flat tiles | S | PB-00 |
| PB-08 | SQLite writer: pragmas + batched inserts | M | PB-00 |
| PB-09 | Writer modes + order assertion + benchmark | M | PB-08 |
| PB-10 | Split `Main` into a shared RunContext + per-output run | M | – |
| PB-11 | Shard runner with resume and workers | L | PB-01, PB-09, PB-10 |
| PB-12 | Shard list generator | M | PB-05 |
| PB-13 | Merge command | L | PB-09, PB-11 |
| PB-14 | Thread count option + antimeridian wrap | S | – |
| PB-15 | RBT reference windows for pilot regions | S | – |
| PB-16 | Data preparation runbook + scripts | M | – |
| PB-17 | Pilot run and report | M | all code tickets, PB-15, PB-16 |
| PB-18 | Production run | – | PB-17 go |

Sizes: XS < 2 h, S ≈ half a day, M ≈ 1 day, L ≈ 2 days.

---

## 3. Tickets

### PB-00 — Tile-by-tile compare tool  `[ ]`

**Why:** many tickets must prove "same tiles as before". `compare.py` compares statistics, not bytes.

**Files:** new `tools/compare_tiles.py`.

**Steps:**
1. Usage: `python tools/compare_tiles.py A.mbtiles B.mbtiles [--decode]`.
2. Open both read-only (`file:...?mode=ro`, `uri=True`). Read all `(zoom_level, tile_column, tile_row)` keys
   from both.
3. Report: keys only in A, keys only in B, keys where `tile_data` differs. Compare **gunzipped** bytes: if data
   starts with `1f 8b`, gunzip it first. The gzip header contains no timestamp from Java's `GZIPOutputStream`
   (mtime 0), but be safe.
4. `--decode`: for differing tiles, decode both with `mapbox_vector_tile` and print the layer and feature counts.
5. Exit code 0 when identical, 1 otherwise. Print counts per zoom.

**Acceptance:** comparing `out/baseline/contours.mbtiles` with itself → identical, exit 0. Comparing it with the
`out/phase5` (old, ungzipped) run → shows differences only where expected.

---

### PB-01 — Fault barrier, failure file, exit code  `[ ]`

**Why:** today one exception in one tile cancels the whole run (`Processor` awaits all tiles; an exception
propagates through `runBlocking`).

**Files:** `sink/FaultBarrierTileSink.kt`, `Main.kt`, new test `sink/FaultBarrierTileSinkTest.kt`.

**Steps:**
1. Extend `FaultBarrierTileSink`:
   * constructor param `failureFile: File?`;
   * `AtomicLong failures`; on exception: log the error (already done), increment, append
     `"${z}/${x}/${y}\t${e.javaClass.simpleName}: ${e.message}\n"` to the file. Use a `synchronized` block,
     since many threads call `accept`.
   * `val failureCount: Long`.
2. In `Main.main`, wrap the `CompositeTileSink`:
   `val sink = FaultBarrierTileSink(CompositeTileSink(...), failureFile = File(options.outputDir, "failed-tiles.txt"))`.
   Delete an old failure file at start.
3. After `Processor(...).process()` and after closing repositories: if `sink.failureCount > 0`, log
   `"N tiles failed, see <file>; rerun them with --tileList <file>"` and `exitProcess(3)`.
4. Make the failure file compatible with `--tileList`: `CliOptions.resolvedTileList` must ignore everything after a
   tab. Change `line.split("/")` to work on `line.substringBefore('\t')`.

**Tests:**
* A test sink that throws for `TileId(9, 1, 1)` and counts the others: after running 4 tiles through
  `FaultBarrierTileSink`, 3 are accepted, `failureCount == 1`, and the file contains `9/1/1\t`.
* `CliOptionsTest`: a tile list line `9/1/1\tSomeException: x` parses to `TileId(9, 1, 1)`.

**Watch out:** don't swallow `Error`s (e.g. `OutOfMemoryError`); catch `Exception` only, as today.

---

### PB-02 — Remove the planet-wide glacier union from startup  `[ ]`

**Why:** `Main.iceSplitterProvider` logs `index.areaKm2`, which unions **all** glaciers. For the planet that can
take hours or run out of memory.

**Files:** `Main.kt`, `cli/CliOptions.kt`.

**Steps:**
1. Add option `--stats` (boolean, default false): "Log expensive input statistics such as total glacier area".
2. In `iceSplitterProvider`, log `index.size` always, and `areaKm2` only when `options.stats` is true.

**Acceptance:** an Iceland run without `--stats` doesn't log km². `GlacierIcelandDataTest` still passes (it calls
`areaKm2` directly).

---

### PB-03 — Primitive node id collection  `[ ]`

**Why:** `NodeLocations.load` does `ways.flatMap { it.asIterable() }`. That boxes every node id into a `Long`
object (about 16 bytes plus a list slot each) before it becomes a `LongArray`. With tens of millions of planet
glacier nodes this costs gigabytes.

**Files:** `osm/GlacierLoader.kt` (class `NodeLocations`), `osm/ProtoReader.kt` (`LongList`).

**Steps:**
1. Build the id array without boxing:
   ```kotlin
   val all = LongList(ways.sumOf { it.size })
   for (way in ways) for (id in way) all.add(id)
   val sorted = all.toLongArray().also { it.sort() }
   // dedupe in place
   var n = 0
   for (i in sorted.indices) if (i == 0 || sorted[i] != sorted[i - 1]) sorted[n++] = sorted[i]
   val ids = sorted.copyOf(n)
   ```
2. Leave the rest of `load` unchanged.

**Tests:** a unit test for the dedupe helper (extract it as `internal fun sortedDistinct(ways: Collection<LongArray>): LongArray`)
with duplicates, an empty input and a single way. `GlacierIcelandDataTest` gives the same glacier count (314)
and area.

---

### PB-04 — Lazy DEM elevation for peaks  `[ ]`

**Why:** `PeakIndex`'s constructor looks up DEM elevation for **every** peak without `ele`, on one thread, before
any tile is processed. For about 1 M planet peaks this takes hours.

**Files:** `osm/Peaks.kt`, `Main.kt` (`peakIndex`), `src/test/.../osm/PeaksTest.kt`.

**Steps:**
1. Remove the eager `peaks.map { ... demElevation ... }` from the constructor. Keep `demElevation` as a field.
2. Add `private val resolved = ConcurrentHashMap<Long, Optional<Int>>()` (key: OSM id).
3. Add `private fun withEle(p: Peak): Peak`: if `p.ele != null` return `p`; otherwise
   `resolved.computeIfAbsent(p.osmId) { Optional.ofNullable(demElevation(p.lon, p.lat)?.roundToInt()) }` and return
   `p.copy(ele = it, eleSource = "dem")` when present.
4. In `ranked(tile)`, map the query candidates through `withEle` **before** sorting (the rank uses `ele`).
5. The cache makes every zoom show the same DEM value, because the lookup always uses z12 (see `Main.peakIndex`).

**Tests:** existing `PeakIndexTest` must still pass unchanged (it checks `eleSource = "dem"`). Add a test that
counts `demElevation` calls: it is called 0 times in the constructor, once per peak across several `ranked` calls.

**Acceptance:** Iceland run → `compare_tiles.py` against baseline: identical.

**Watch out:** `demElevation` reads `dataStore.get(z12 tile)`. It is now called from tile worker threads; the
store is thread-safe (Guava caches), so this is fine.

---

### PB-05 — PMTiles content key  `[ ]`

**Why:** identical tiles (sea) share one data offset in a PMTiles archive. Exposing it lets PB-06 decode each
distinct tile once, and lets PB-12 test for land cheaply.

**Files:** `pmtiles/PmtilesReader.kt`, `elevation/mapterhorn/MapterhornElevationDataStore.kt`
(`MapterhornSourceSet`), tests in `pmtiles/PmtilesReaderTest.kt`.

**Steps:**
1. In `PmtilesReader`, add `data class TileData(val contentKey: Long, val bytes: ByteArray)` and
   `fun getTileData(z, x, y): TileData?`, where `contentKey = header.tileDataOffset + entry.offset`.
   Reimplement `getTile` as `getTileData(z, x, y)?.bytes`.
2. Add `fun getContentKey(z, x, y): Long?`, which looks up the directory **without reading the tile bytes**.
   Refactor the directory walk into `private fun findEntry(z, x, y): Entry?`, used by both.
3. In `MapterhornSourceSet`, add `fun fetchData(z, x, y): Pair<PmtilesReader, PmtilesReader.TileData>?` (same
   loop as `fetch`).

**Tests:** in the fixture, two different tiles have different keys, and the same tile asked twice has the same
key. `getContentKey` equals `getTileData(...).contentKey`.

---

### PB-06 — Decoded-grid dedupe by content key  `[ ]`

**Why:** without it, every sea tile is decoded from WebP again (measured: sea runs at about 170 tiles/s).

**Files:** `elevation/mapterhorn/MapterhornElevationDataStore.kt`.

**Steps:**
1. Add a second Guava cache `decodedByContent: Cache<Pair<String, Long>, ElevationGrid>` (key: reader name +
   content key), max about 2,000 entries.
2. In `nativeCache`'s loader: call `sources.fetchData(...)`, then
   `decodedByContent.get(Pair(reader.name, data.contentKey)) { TerrariumDecoder.decode(data.bytes) }`.
3. **Grids are shared now. Never mutate an `ElevationGrid`.** Check that nothing writes into `grid.data`
   (`resample` and `crop` create new arrays; keep it that way). Add a KDoc note on `ElevationGrid`.
4. Add a metric `Mapterhorn.decoded` (real decodes) next to `Mapterhorn.fetched`.

**Acceptance:** on the Iceland sea bbox `--bbox=-24.5,63.15,-21,63.3 -minZ 9 -maxZ 13`, the log shows
`Mapterhorn.decoded` far below `Mapterhorn.fetched`. Full Iceland → `compare_tiles.py`: identical.

---

### PB-07 — Early exit for flat tiles  `[ ]`

**Why:** even after PB-06, every sea tile still goes through blur and isolines.

**Files:** `sink/VectorTileSink.kt` (`contourLayer`), maybe `elevation/mapterhorn/ElevationGrid.kt`.

**Steps:**
1. In `contourLayer`, after `val elevationTile = elevationDataStore.get(tile.id)` and the `empty` check, and
   **only for `AttributeStyle.RBT`**:
   ```kotlin
   val bounds = elevationTile.elevationBounds   // lazy min/max over the tile
   if (bounds.min.valid && bounds.min.meters == bounds.max.meters) {
       return null   // flat: no contour level can cross this tile
   }
   ```
   Contour levels never include 0, and a flat tile at a non-zero level (a lake surface) has no crossing either.
2. `elevationBounds` loops 512² `get()` calls. That's acceptable, but it can be made faster: for
   `GridElevationTile`, override with a min/max over the float array (optional).

**Acceptance:**
* `compare_tiles.py` against the baseline: the only differences are **tiles missing in the new file**, and each
  of them is a sea-edge tile whose contours lay only in the 80-unit buffer. Use `--decode` to check a few. If any
  tile *inside* the coast changes, stop and ask.
* `compare.py` still says 0 failed.
* The sea bbox run from PB-06 is at least 5× faster than the 6 s measured before PB-06.

---

### PB-08 — SQLite writer: pragmas + batched inserts  `[ ]`

**Why:** today every tile is one `INSERT` in its own transaction, with a rollback journal and `synchronous=FULL`
(an fsync per tile). Planetiler's settings (planet plan §2) make writes about 100× cheaper.

**Files:** `repository/MbtilesTileRepository.kt`, tests in `repository/MbtilesTileRepositoryTest.kt`.

**Steps:**
1. Open the connection with xerial's `SQLiteConfig` instead of empty `Properties()`:
   ```kotlin
   val config = SQLiteConfig().apply {
       setJournalMode(SQLiteConfig.JournalMode.OFF)
       setSynchronous(SQLiteConfig.SynchronousMode.OFF)
       setLockingMode(SQLiteConfig.LockingMode.EXCLUSIVE)
       setTempStore(SQLiteConfig.TempStore.MEMORY)
       setCacheSize(-1_000_000)          // negative = KiB, so about 1 GB
       setApplicationId(0x4d504258)      // "MPBX", MBTiles
   }
   JDBC.createConnection("jdbc:sqlite:${outputFile.absolutePath}", config.toProperties())
   ```
2. Batch inserts on the existing single writer thread:
   * keep `private val batch = ArrayList<Triple<TileId, ByteArray>>()` (or a small data class);
   * `write()` adds to the batch; when it reaches **249** tiles (999 SQLite parameters / 4 columns), run one
     multi-row statement `INSERT INTO tiles (zoom_level,tile_column,tile_row,tile_data) VALUES (?,?,?,?),(?,?,?,?),…`
     prepared once for 249 rows;
   * `close()` flushes the remainder with a statement prepared for exactly that many rows, **then** shuts down.
     All of this runs on the writer thread: submit a final flush task before `executor.shutdown()`.
3. `read()` (used by tests) must see unflushed tiles: flush the batch before reading.
4. Keep the existing error handling (collect exceptions, rethrow on close).

**Tests:** existing repository tests pass. New test: write 1,000 tiles, close, reopen read-only, and all 1,000
are there with the right bytes. Also `PRAGMA journal_mode` on the open connection returns `off`.

**Acceptance:** Iceland run → `compare_tiles.py` identical; the run is not slower.

**Watch out:** with `journal_mode=OFF`, a crash corrupts the file. That is accepted: PB-11 writes to `.tmp` and
renames. Don't use this writer to append to an existing file.

---

### PB-09 — Writer modes, order assertion, benchmark  `[ ]`

**Why:** the planet file must be written in TMS order into a table whose primary key exists from the start
(append-only B-tree). Shard files are written in processing order and indexed at the end.

**Files:** `repository/MbtilesTileRepository.kt`, new test `repository/MbtilesWriteBenchmarkTest.kt`.

**Steps:**
1. Add `enum class IndexMode { UNIQUE_INDEX_UPFRONT /* today */, INDEX_ON_CLOSE, SORTED_PRIMARY_KEY }` and a
   constructor param (default `UNIQUE_INDEX_UPFRONT`, so nothing else changes).
2. Table DDL per mode:
   * `INDEX_ON_CLOSE`: `CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)`
     with no index. In `close()`, after the final flush:
     `CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row)`.
   * `SORTED_PRIMARY_KEY`: `CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob, PRIMARY KEY (zoom_level, tile_column, tile_row))`.
     In `write()`, check that `(z, x, tmsRow)` is strictly greater than the previous key. Otherwise throw
     `IllegalStateException("tiles out of order: … after …")`.
3. Add `fun storeTms(z: Int, x: Int, tmsRow: Int, bytes: ByteArray)`, used by the merge (PB-13), which already has
   TMS rows.
4. Benchmark test (opt-in, `assumeTrue(System.getProperty("bench") != null)`): write 1,000,000 random 2 KB blobs
   in TMS order with `SORTED_PRIMARY_KEY` and print tiles/s and file size. Run it with
   `mvn test -Dtest=MbtilesWriteBenchmarkTest -DargLine=-Dbench=1`.

**Tests:** out-of-order write in `SORTED_PRIMARY_KEY` throws; `INDEX_ON_CLOSE` file has the index after close
(`SELECT name FROM sqlite_master WHERE type='index'`); `PRAGMA freelist_count` is 0 for a sorted-written file.

**Acceptance:** the benchmark sustains more than 50,000 tiles/s on a dev PC. Write the number in the PR.

---

### PB-10 — Split `Main` into a shared RunContext + per-output run  `[ ]`

**Why:** sharding needs to build the expensive things (elevation store, glaciers, peaks, profile) **once**, and the
cheap things (repository, sink, processor) **per shard**. Today `main` builds everything for one run.

**Files:** `Main.kt`, new `RunContext.kt`.

**Steps:**
1. Create `class RunContext(options, metricsProvider) : Closeable` that holds `profile`, `dataStore`,
   `iceSplitterProvider`, `peaks`. Move the existing private functions (`cachingElevationDataStore`,
   `iceSplitterProvider`, `peakIndex`, `contourProfile`) into it or next to it, unchanged.
2. Create `fun runTiles(context: RunContext, range: TileRange, repositoryFactory: RepositoryFactory): RunResult`.
   It contains today's middle part of `main`: build sinks via `tileSinkToRepository`, wrap in the fault barrier
   (PB-01), run `Processor`, close repositories, and return `RunResult(tiles, failures, seconds)`.
3. `main` becomes: parse options → `RunContext(...).use { ctx -> runTiles(ctx, options.toTileRange(), tileRepositoryFactory(options, ...)) }` → exit code.
4. Pass the repository factory's output directory as a parameter instead of reading `options.outputDir` inside,
   so a shard can redirect it.

**Acceptance:** pure refactor: Iceland run → `compare_tiles.py` identical; all tests green; `--help` unchanged.

---

### PB-11 — Shard runner with resume and workers  `[ ]`

**Why:** this is what makes a multi-day run safe to stop and restart.

**Files:** `cli/CliOptions.kt`, `Main.kt`, new `planet/ShardRunner.kt`, tests `planet/ShardRunnerTest.kt`.

**New options:**
* `--shards <file>`: lines `6/x/y` (comments `#` allowed).
* `--shardDir <dir>`: where shard files go.
* `--worker i/n`: take shards where `index % n == i` (default `0/1`).

Validation: `--shards` requires `--shardDir`, `-f mbtiles` and `--profile rbt`, and cannot be combined with
`--area`, `--tileList` or `-minX/…`.

**Steps:**
1. Parse the list, keep this worker's share, and sort it in **Hilbert order** of `(x, y)` at z6 (reuse
   `TileIds.zxyToTileId(6, x, y)` as the sort key). Neighbouring shards then share cached edge tiles.
2. Build one `RunContext`.
3. For each shard `6/x/y`:
   1. `final = shardDir/6-x-y.mbtiles`. If it exists → log "skip" and continue. **That is the resume.**
   2. Delete stale `6-x-y.mbtiles.tmp` and `6-x-y.failed.txt`.
   3. Range: `TileRange(minZ = 6, maxZ = 13, minX = x, maxX = x, minY = y, maxY = y, bounds = options.resolvedBbox)`.
   4. Repository factory that creates `MbtilesTileRepository(tmp, …, indexMode = INDEX_ON_CLOSE)` with metadata
      (same as today plus `shard=6/x/y`, `tiles=N`, `seconds=S`).
   5. `runTiles(...)`. If failures == 0: rename `tmp` → `final` with
      `Files.move(..., ATOMIC_MOVE)`. Otherwise keep the `.tmp`, move the failure file to `6-x-y.failed.txt`, log,
      and continue.
4. At the end log `done / skipped / failed` counts and exit 3 if any shard failed.
5. Progress log per shard: `shard 6/33/22 (17/2034): 41,233 tiles in 812 s`.

**Tests:**
* With a fake `runTiles` (inject a function), shards whose final file exists are skipped, failing shards don't
  produce a final file, and `--worker 1/3` takes indices 1, 4, 7…
* Real data (`ShardRunnerDataTest`, skipped without data): run the Iceland shard `6/28/16` into a temp dir. Delete
  the final file of one shard, rerun: only that shard runs again.

**Acceptance:** run all 9 Iceland shards (`6/27..29/15..17`) with `--bbox` → each shard file exists. Then kill the
JVM during a second fresh run, restart it, and check that the finished shards are skipped.

**Watch out:** z6–8 tiles are visited but produce nothing (contours start at z9); that's fine and quick.

---

### PB-12 — Shard list generator  `[ ]`

**Why:** about half of the 4,096 z6 tiles are all sea, and they shouldn't be run at all.

**Files:** new `planet/ShardList.kt` with a `main` (run as
`java -cp target/vectorvector-cli.jar com.greensopinion.vectorvector.planet.ShardListKt …`).

**Usage:** `ShardListKt --pmtiles planet.pmtiles [--glaciers rbt-features.osm.pbf] [--bbox …] -o shards.txt`

**Steps:**
1. For each z6 tile (optionally within `--bbox`): fetch the 16 z8 tiles below it from the planet archive and
   decode them (`TerrariumDecoder`). Use the content key (PB-05) to decode each distinct tile once. Keep the
   shard if any pixel is **> 0 m or < −1 m** (below-sea-level land such as the Netherlands or the Dead Sea).
2. Also keep the shard if any OSM glacier or peak lies in it (load with `PeakLoader`, plus glacier envelopes from
   `GlacierLoader`), so small islands smaller than a z8 pixel aren't lost.
3. Parallelise over z6 tiles (e.g. `parallelStream()`); write `6/x/y` lines sorted, with a header comment (date,
   inputs, counts).

**Acceptance:** with the Iceland extract and `--bbox` of Iceland, all 9 Iceland shards that contain land are
listed (check against the shards with tiles in `out/baseline`). Runtime for the whole planet archive is under 30
minutes on 8 cores (estimate; record the real number).

---

### PB-13 — Merge command  `[ ]`

**Why:** produce one planet `contours.mbtiles` from the shard files, written in strict TMS order so the big file is
an append-only B-tree.

**Files:** new `planet/MergeShards.kt` with a `main`; tests `planet/MergeShardsTest.kt`.

**Usage:** `MergeShardsKt --shards shards.txt --shardDir out/shards -o out/planet/contours.mbtiles [--minZ 9 --maxZ 13]`

**Steps:**
1. **Preflight:** every shard in the list has `6-x-y.mbtiles`, and none has a `.failed.txt`. Otherwise print the
   missing or failed shards and exit 2. Check free disk space ≥ the sum of shard file sizes × 1.1.
2. Open all shard files read-only (as needed; at most one z6 column, ≤ 64 files, at a time).
3. Writer: `MbtilesTileRepository(out.tmp, indexMode = SORTED_PRIMARY_KEY)`.
4. Loop. Shards in one z6 column cover disjoint row ranges, so a k-way merge by `(tile_column, tile_row)`
   gives global TMS order:
   ```kotlin
   for (z in minZ..maxZ) {
     for (sx in 0..63) {
       val column = shards.filter { it.x == sx }
       // one cursor per shard: SELECT tile_column, tile_row, tile_data FROM tiles
       //                       WHERE zoom_level = ? ORDER BY tile_column, tile_row   (uses the shard index)
       val queue = PriorityQueue<Cursor>(compareBy({ it.column }, { it.row }))
       column.mapNotNull { openCursor(it, z) }.forEach(queue::add)
       while (queue.isNotEmpty()) {
         val c = queue.poll()
         writer.storeTms(z, c.column, c.row, c.data)
         if (c.next()) queue.add(c) else c.close()
       }
     }
   }
   ```
   The writer's order assertion (PB-09) catches any mistake immediately.
5. Metadata: `name`, `format=pbf`, `minzoom/maxzoom`, `bounds -180,-85.0511,180,85.0511` (or the union of shard
   bounds), `json` with `vector_layers` built like `createMbTilesRepository` does (`RbtProfile.contourLayer` +
   `PeakIndex.schema()`; make a small shared function), `attribution`, `description`, `version`.
6. `ANALYZE`, close, rename `.tmp` → final.
7. Log tiles per zoom; they must equal the sum over shards (query each shard's count per zoom up front).

**Tests:**
* Synthetic: create 3 small shard files in one column and 1 in another (with tiles inserted in **random** order),
  merge, then check the output order (`SELECT … ORDER BY rowid` is sorted), that all tiles are present, and
  `PRAGMA freelist_count = 0`.
* Data: merge the 9 Iceland shards from PB-11 and compare with `compare_tiles.py` against a single Iceland run
  made with the same build → identical.

**Watch out:** don't `VACUUM` the planet file. It rewrites the whole file and isn't needed for an append-only write.

---

### PB-14 — Thread count option + antimeridian wrap  `[ ]`

**Files:** `Processor.kt`, `cli/CliOptions.kt`, `elevation/mapterhorn/MapterhornElevationDataStore.kt`,
`osm/GlacierIndex.kt`.

**Steps:**
1. `--threads N` (default = available processors). In `Processor`, run tile work on
   `Dispatchers.IO.limitedParallelism(N)` instead of plain `Dispatchers.IO` (which caps at 64).
2. Antimeridian: in `MapterhornElevationDataStore.get`, wrap `x` with `Math.floorMod(x, 1 shl z)` instead of
   returning `EmptyTile` for `x` out of range (`y` out of range stays empty).
3. `GlacierIndex.bufferedEnvelope` may go past ±180°. Leave it, but add a comment; glaciers at the antimeridian are
   rare.

**Tests:** `get(TileId(10, -1, 300))` returns the same tile as `get(TileId(10, 1023, 300))`.

---

### PB-15 — RBT reference windows for pilot regions  `[ ]`

**Why:** the pilot compares against RBT outside Iceland.

**Files:** `tools/rbt_reference/extract_reference.py`.

**Steps:**
1. Add `--bbox minLon,minLat,maxLon,maxLat`, `--name`, `--z13-windows lat,lon;lat,lon` (keep today's Iceland
   defaults when the flags are missing).
2. Extract, into `data/pilot/rbt-<name>.mbtiles`: Alps (`5.9,45.8,10.5,47.8`), Himalaya (`86.0,27.5,87.5,28.5`),
   Netherlands (`3.3,50.7,7.3,53.6`), Greenland edge (`-51,64,-48,66`), Antarctic coast (e.g. `160,-78,168,-76`),
   Fiji (`177,-19,180,-16`). All are read-only from `E:\RBT\tileserver-gl\data\RBT.mbtiles`.
3. Run `analyze_contours.py` on each and store the reports under `docs/pilot/`.

**Acceptance:** each file opens in QGIS and shows contours; each report has z9–13 rows.

---

### PB-16 — Data preparation runbook + scripts  `[ ]`

**Files:** new `tools/planet/` with scripts, and new `docs/planet-runbook.md`.

**Steps** (scripts must be restartable and print what they do):
1. `download_mapterhorn_planet.sh`: `aria2c -x8 -c` (or `curl -C -`) of `planet.pmtiles` (355.6 GB). Verify the MD5
   from `download_urls.json`, then `pmtiles verify`.
2. `extract_mapterhorn_z13.sh`: for every non-planet entry of `download_urls.json`, run
   `pmtiles extract <url> z13/<name> --maxzoom=13` (only z13 is needed; the full files total 9.6 TB). First pass:
   `--dry-run` for all and print the total. Skip files that already exist (resume).
3. `prepare_osm.sh`: download `planet-latest.osm.pbf` and its `.md5`, verify, then
   `osmium tags-filter planet-latest.osm.pbf nwr/natural=glacier nwr/natural=peak,volcano -o rbt-features.osm.pbf`.
4. Write `SOURCES.md` next to the data: dates, sizes, MD5s, the Mapterhorn `attribution.json` copy.
5. `docs/planet-runbook.md`: disk layout, the exact commands from the planet plan §6, how to resume, how to retry
   failed shards, and how to merge.

**Acceptance:** a dry run of all three scripts on a small box (Iceland bbox) works end to end.

---

### PB-17 — Pilot run and report  `[ ]`

**Steps:**
1. Build a shard list with the pilot shards (planet plan §5): `6/33/22` (Alps), a Himalaya shard, a Greenland-edge
   shard, an Antarctic-coast shard, a Netherlands shard, an all-sea Pacific shard, a Fiji shard, plus the 9 Iceland
   shards.
2. Run them with the shard runner on the planet inputs (PB-16). Record per shard: tiles, seconds, tiles/s, peak
   heap (`-Xlog:gc` or JFR), failures and file size.
3. Merge the pilot shards (PB-13) and check `PRAGMA integrity_check`.
4. Run `compare.py` per region against PB-15's references. In the Alps, check that smoothness ratios hold with
   **native z13** data; if not, propose z13 settings via `--rbtSmoothing` and re-run.
5. Count duplicate tile hashes in the merged file; if > 10 %, raise compact mode as a follow-up.
6. Write `docs/pilot-report.md`: the table, the estimated planet runtime and size, go/no-go.

**Go criteria:** 0 failed checks in every region (or explained deviations); no heap growth over the shards in one
JVM; the merge passes `integrity_check`; the estimated runtime fits the machine budget.

---

### PB-18 — Production run

Follow `docs/planet-runbook.md` (PB-16) and the planet plan §6: shard list → shard runner (one or more workers) →
retry failures → merge → checks → visual check in tileserver-gl and QGIS → publish with `SOURCES.md`, run log and
shard list.

---

## 4. FAQ for implementers

* **Why not WAL mode?** WAL writes every page twice (into the `-wal` file, then a checkpoint into the database)
  and keeps readers consistent, which we don't need while bulk loading. A rollback journal also writes every
  changed page twice. With `journal_mode=OFF` each page is written once; the price is that a crash can corrupt
  the file, which is why every file is written as `.tmp` and renamed.
* **Why sorted inserts?** SQLite stores the primary key as a B-tree. Keys arriving in sorted order always go into
  the last page, so pages fill up completely and the file grows sequentially. Random keys split pages all over the
  tree, which means random I/O, half-empty pages and free space at the end.
* **Can I run several shards in parallel in one JVM?** Not in the first version. Tiles inside a shard already run
  on all cores, and running shards in sequence keeps memory predictable. For more machines use `--worker i/n`.
* **A data test is skipped. Is that ok?** Only if you really don't have `data/iceland/`. Get the data (section 0.1)
  before submitting tickets that touch elevation, glaciers or peaks.
* **Where do I ask about RBT behaviour?** `docs/rbt-reference-report.md` (facts) and `docs/tuning-log.md`
  (smoothing). Don't retune smoothing as part of these tickets.
