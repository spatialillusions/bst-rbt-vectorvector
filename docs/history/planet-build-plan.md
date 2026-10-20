# Planet build plan: RBT contours from Mapterhorn + OSM

**Historical design proposal:** the current implementation and supported commands are documented in
[planet-sharding.md](../planet-sharding.md), with measured checks in [planet-validation.md](../planet-validation.md).
The proposals below are not all implemented, and their example CLI options are not the current interface.

Goal: one `contours.mbtiles` for the whole planet (z9–13, layers `contours` and `peaks`), produced reliably on one
or a few machines, using the same per-tile pipeline that matched RBT over Iceland (`docs/tuning-log.md`).

Implementation tickets for this plan: [planet-implementation-tickets.md](planet-implementation-tickets.md).

This plan starts from how the code works today (commit `5ee1941` and later), and lists the changes, data
preparation, pilot and production steps in order. Estimates are marked as such; everything else was measured or
read from the code.

---

## 1. Where we are

### 1.1 What already scales

* **Per-tile work is independent.** `VectorTileSink` computes one output tile from its own elevation tile and the
  8 neighbours (`ElevationTileArea`) and the glacier geometry clipped to that tile. Nothing depends on the order
  tiles are processed in, so the area can be split into shards with **no seams** at shard edges.
* **Inputs are read-only and random-access**: PMTiles via `FileRangeSource` (positional reads, thread-safe), OSM
  loaded once into memory (`GlacierIndex`, `PeakIndex`).
* **Tile output is gzip-compressed** (`--gzipTiles`, default for `--profile rbt`).

### 1.2 What blocks a planet run (from the Phase 9 review in `PLAN.md`)

| # | Problem in today's code | Effect at planet scale |
|---|---|---|
| B1 | `FaultBarrierTileSink` exists but isn't used | one bad tile aborts a multi-day run |
| B2 | `MbtilesTileRepository` truncates the output on start; no resume | a crash means starting over |
| B3 | `Main` logs `GlacierIndex.areaKm2`, a union of *all* glaciers | hours or out-of-memory with Antarctica and Greenland |
| B4 | `PeakIndex` samples DEM elevation for every peak without `ele`, eagerly and on one thread | hours before the first tile |
| B5 | Sea tiles are decoded and blurred like land (measured: about 170 tiles/s, no output) | about 4 extra days on 8 cores (estimate) |
| B6 | SQLite writer: one autocommit `INSERT` per tile, default rollback journal and `synchronous=FULL`, unique index created first, inserts in processing order | fsync per tile, random B-tree inserts, free pages (Iceland: 16 MB of 92 MB unused) |
| B7 | `NodeLocations.load` boxes every glacier node id into a `List<Long>` | GBs of heap for planet glacier nodes |

### 1.3 Size of the job (estimates)

| Item | Value | Source |
|---|---|---|
| Mapterhorn `planet.pmtiles` (z0–12) | 355.6 GB | `download_urls.json` |
| Mapterhorn high-zoom archives | 458 files, **9,578 GB** total (z13–z18) | `download_urls.json` |
| …of which only z13 is needed | about 100–200 GB (measure with `pmtiles extract --dry-run`) | estimate |
| OSM planet PBF | about 80 GB, filtered to a few GB | estimate |
| Output tiles z9–13 | about 35–45 M land tiles (Antarctica and Greenland are huge in Web Mercator) | estimate |
| Land throughput | about 55 tiles/s on 8 cores (Iceland) | measured |
| Output size | about 50–100 GB (RBT, all layers: 169 GB) | estimate |

At Iceland speed that is **7–10 days on 8 cores**, or about **1–1.5 days on 64 cores**. The pilot (step 4) replaces
these estimates with measured numbers.

---

## 2. How to write very large MBTiles (what Planetiler does, and what we'll do)

Planetiler's writer (`planetiler-core/.../mbtiles/Mbtiles.java`, `archive/TileArchiveWriter.java`) is the reference
for writing planet-scale MBTiles. What it does:

| Setting | Planetiler | Why |
|---|---|---|
| `journal_mode` | **OFF** | no rollback journal and no WAL. Each page is written once, not twice. |
| `synchronous` | **OFF** | no fsync per commit; the OS flushes in the background |
| `locking_mode` | **EXCLUSIVE** | one writer, no lock churn |
| `cache_size` | 1,000,000 pages (about 1 GB) | keeps the B-tree's upper levels in memory |
| `temp_store` | MEMORY | sorts and temp B-trees stay off disk |
| `application_id` | MBTiles id | identifies the file type |
| Inserts | multi-row `INSERT … VALUES (?,?,?,?),(…)…` with up to 999 parameters (249 tiles per statement), one writer thread | far fewer statement executions and commits |
| Order | tiles written in **TMS order** (zoom, column, row). Encoding runs in parallel; the writer consumes results in order | each insert appends at the right edge of the primary-key B-tree: no page splits, no random I/O, no free pages |
| Schema (non-compact) | `tiles(zoom_level, tile_column, tile_row, tile_data, primary key(zoom_level, tile_column, tile_row))` | the PK is the only index |
| Compact mode | `tiles_shallow` (z, x, y, data_id) + `tiles_data` (id, blob) + `tiles` view, deduplicated by content hash | pays off when many tiles are identical (sea, land fill); RBT's file uses a similar map/images layout |
| Optional | skip index creation and `CREATE UNIQUE INDEX` afterwards; `VACUUM; ANALYZE` | not needed when keys arrive sorted |

About "regenerating the index on each insert": SQLite never rebuilds an index on insert. The cost comes from
**inserting keys in random order**. Each insert lands somewhere in the middle of the B-tree, splitting pages and
causing random reads and writes, and with a journal every changed page is also written to the journal (or WAL)
first. Sorted keys plus no journal plus no fsync turn the write into a near-sequential append. That is the
Planetiler approach, and what we copy.

**Our choices:**

* **Non-compact schema with a primary key.** Our sea tiles are not written at all and contour tiles are rarely
  identical, so deduplication saves little. (Check during the pilot: count duplicate hashes. If more than 10 % are
  duplicates, switch the final file to compact mode.)
* **Journal OFF and sync OFF are only safe if a crash can't hurt us.** A crash can leave such a file corrupt, so
  every file we write is disposable: shard files are written as `*.tmp` and renamed when complete, and the final
  merge writes `planet.mbtiles.tmp` and renames at the end. A crash only loses the file being written, which is
  regenerated.
* **Sorted writes:** inside a shard, tiles come out in processing order (depth-first per z9 tile). Shards are
  small (thousands to tens of thousands of tiles), so shard files are written **without an index** (pure append)
  and get `CREATE UNIQUE INDEX` once at the end of the shard (a sort of ≤ 100k rows, under a second). The **merge**
  then writes the planet file strictly in TMS order (§3.6), so the big B-tree is only ever appended to.

---

## 3. Code changes (in order)

Each item ends with its acceptance check. All of them keep `DefaultContourGoldenTest` green.

### 3.1 Fault isolation (B1)

* Wrap the sink in `FaultBarrierTileSink` in `Main`. Extend it to count failures and append `z/x/y<TAB>exception`
  lines to `<output>.failed.txt`.
* At the end of a run, exit non-zero if there were failures. A shard with failures is not renamed to `.mbtiles`
  (§3.5).
* Retry failed tiles with `--tileList <output>.failed.txt` (already supported).
* *Check:* a unit test with a sink that throws for one tile: the other tiles are written, the id is in the
  failure file, and the exit code is non-zero.

### 3.2 Startup costs (B3, B4, B7)

* B3: remove `areaKm2` from the startup log (keep it as an opt-in diagnostic, e.g. `--stats`).
* B4: make DEM lookup for peaks lazy. In `PeakIndex.layer(tile)`, resolve peaks with no `ele` from the elevation
  tile the sink already has in memory (for z ≥ 12), otherwise from the z12 tile. Cache the result per peak id in a
  concurrent map, so every zoom shows the same value.
* B7: `NodeLocations.load` collects ids into a growable primitive `LongArray` (the existing `LongList`), then sorts
  and deduplicates in place. No boxing.
* *Check:* glaciers and peaks loaded from a filtered **Europe** extract (Alps glaciers, about 1/10 of planet
  glacier nodes) in under 2 minutes, with peak heap usage logged.

### 3.3 Skip the sea cheaply (B5)

* **Decode once per distinct tile.** Add the directory entry's data offset to `PmtilesReader` (e.g.
  `getTileWithKey(z, x, y): Pair<Long, ByteArray>?`). `MapterhornElevationDataStore` keys a small decoded-grid
  cache by `(archive, offset)`, so the thousands of identical sea tiles in a run decode once.
* **Early exit for flat tiles.** In `VectorTileSink.contourLayer`, before blurring, check the centre elevation
  tile's min and max (cheap: one pass over 512² floats, or cached alongside the grid). If `min == max` and no level
  equals that value, return null. Lines from neighbours that would only reach into our 80-unit buffer are dropped;
  the neighbours draw them up to the shared edge, so nothing visible is lost.
* *Check:* the Iceland sea bbox used in the review (1,031 tiles, 6 s) must run at least 5× faster, and the full
  Iceland run must still pass `compare.py` with 0 failed checks.

### 3.4 Planetiler-style writer (B6)

Replace `MbtilesTileRepository` internals (keep the `TileRepository` interface):

* Open with `journal_mode=OFF`, `synchronous=OFF`, `locking_mode=EXCLUSIVE`, `temp_store=MEMORY`,
  `cache_size=-1000000` (about 1 GB, in KiB), `application_id=0x4d504258` ("MPBX"). Page size: benchmark the
  default 4096 against 32768 on the pilot; the blobs average 2–3 KB gzipped.
* Two table modes:
  * `indexOnClose` (shards): `CREATE TABLE tiles(...)` with no index; `CREATE UNIQUE INDEX tile_index ON tiles
    (zoom_level, tile_column, tile_row)` in `close()`.
  * `sortedPrimaryKey` (merge): `primary key(zoom_level, tile_column, tile_row)`. The writer **asserts** that keys
    arrive strictly increasing, which catches merge bugs immediately.
* Batched multi-row `INSERT` of 249 tiles per statement, from the existing single writer thread; flush the
  remainder on close.
* Metadata: written in `close()` (`minzoom`, `maxzoom`, `bounds`, `json`, `attribution`, …) as today.
* *Check:* a benchmark test that writes 1 M synthetic 2 KB tiles in TMS order should sustain more than 50k
  tiles/s on this PC (Planetiler sustains far more). The Iceland output must be byte-identical tile for tile
  (compare `tile_data` per key) and the file smaller (no free pages).

### 3.5 Shard runner and resume (B2)

New options:

* `--shards <file>`: one `6/x/y` per line. When given, the run processes those z6 tiles one after another in a
  single JVM. Glaciers, peaks, PMTiles readers and caches are loaded **once**.
* `--shardDir <dir>`: shard outputs go to `<dir>/6-x-y.mbtiles`.
* `--worker i/n`: this process takes every n-th shard starting at i, so several machines (or JVMs) can split one
  list without coordination.

Per shard:

1. If `6-x-y.mbtiles` exists, skip it. **That is the resume.**
2. Write `6-x-y.mbtiles.tmp` with the `indexOnClose` writer, over tile range
   `-minZ 6 -maxZ 13 -minX x -maxX x -minY y -maxY y` (contours start at z9; z6–8 return immediately).
3. On success with 0 failed tiles: write shard metadata (tile count, failures, seconds, Mapterhorn archives
   used), close (builds the index), then rename to `6-x-y.mbtiles`.
4. On failure: leave the `.tmp` and the failure file, log, and continue with the next shard. A rerun redoes only
   unfinished shards.

Shard order: Hilbert order of the z6 tiles, so consecutive shards are neighbours and the elevation caches keep
their edge tiles.

*Check:* kill the process mid-run, then restart it. Finished shards are skipped, the interrupted shard is redone,
and the merged output equals a clean run.

### 3.6 Shard list and merge

* `shard-list` command (or `tools/make_shard_list.py`): for every z6 tile, decode the 16 Mapterhorn z8 tiles
  under it (about 65k decodes in total, a few minutes) and keep the shard if any pixel is above 0 m, or if the
  shard has any OSM glacier or peak. This is conservative: islets below z8 resolution are kept by the OSM test or
  by a neighbouring pixel. Expected: about 2,000 of 4,096 shards.
* `merge` command (Kotlin, reusing the writer from 3.4):
  1. Check that every shard in the list has a final `.mbtiles` and no failure file; otherwise stop with the list
     of missing shards.
  2. Write `planet.mbtiles.tmp` in **strict TMS order** with the `sortedPrimaryKey` writer:
     ```
     for z in 9..13:
       for sx in 0..63:                       # z6 column; covers columns [sx·2^(z-6), (sx+1)·2^(z-6))
         shards = land shards with x == sx, sorted by y descending   # TMS row ascending
         cursors = for each shard: SELECT tile_column, tile_row, tile_data FROM tiles
                                   WHERE zoom_level = z ORDER BY tile_column, tile_row   # uses the shard index
         k-way merge the cursors by (tile_column, tile_row) and append to the planet file
     ```
     Shards in one z6 column cover disjoint row ranges, so the merge is a simple ordered interleave, and memory
     stays at one row per open cursor.
  3. Write metadata: `bounds -180,-85.0511,180,85.0511`, zooms 9–13, `vector_layers` from `RbtProfile` +
     `PeakIndex.schema()`, attribution (Mapterhorn link + OSM), `description`, `version`.
  4. `ANALYZE` (cheap), close, rename to `planet.mbtiles`. No `VACUUM` is needed: sorted appends leave no free
     pages.
* *Check:* merging the Iceland run split into its 9 z6 shards gives a file with the same tiles as the single-run
  file; `PRAGMA integrity_check` is ok; `PRAGMA freelist_count` is 0.

### 3.7 Smaller items

* `Dispatchers.IO` caps at 64 threads. On machines with more cores, run tile work on a bounded dispatcher sized to
  the core count (`--threads`, default = cores).
* Antimeridian: wrap x for neighbour lookups in `MapterhornElevationDataStore.get` and `GlacierIndex`
  (x = −1 → 2^z − 1). Cosmetic, but cheap.
* `Processor`/`Progress` log per-zoom counts and ETA per shard.

---

## 4. Data preparation

All local; disk budget **about 1 TB NVMe** (inputs about 0.6 TB, shards and final output about 0.2 TB, scratch).

1. **Mapterhorn planet (z0–12):** download `https://download.mapterhorn.com/planet.pmtiles` (355.6 GB) with a
   resumable downloader (e.g. `aria2c -x8 -c`). Check the MD5 against `download_urls.json`, and
   `pmtiles verify planet.pmtiles`.
2. **Mapterhorn z13:** for each of the 458 `6-x-y.pmtiles`, extract **only z13**:
   `pmtiles extract https://download.mapterhorn.com/6-x-y.pmtiles 6-x-y-z13.pmtiles --maxzoom=13`.
   Run `--dry-run` over all files first to get the real total. The full files are 9.6 TB and must not be
   downloaded. The archive router already picks the z13 archive by header zoom range and bounds.
3. **OSM:** download `planet-latest.osm.pbf` and check its MD5, then
   `osmium tags-filter planet-latest.osm.pbf nwr/natural=glacier nwr/natural=peak,volcano -o rbt-features.osm.pbf`.
   osmium keeps the referenced ways and nodes, so relations stay complete. Record the date in `SOURCES.md`.
4. **Attribution:** collect the source list from Mapterhorn `attribution.json` for the metadata and README.
5. **Reference subsets for validation:** extend `tools/rbt_reference/extract_reference.py` with `--bbox` and
   `--name`, and extract RBT windows for the pilot regions (Alps, Himalaya, Netherlands, Greenland edge, Antarctic
   coast, Fiji/antimeridian).

---

## 5. Pilot (before the planet)

Run about 8 shards that cover the risky cases:

| Shard (z6) | Why |
|---|---|
| 6-33-22 (Alps, largest Mapterhorn archive, native z13) | native z13 data instead of overzoom; dense glaciers; heaviest tile cost |
| a Himalaya shard | extreme relief: many levels per tile, worst-case tile size |
| Greenland ice-sheet edge | huge glacier polygons, mostly ice |
| Antarctic coast | largest OSM glacier geometry; high latitude (tiny ground area per tile) |
| Netherlands | flat, below-sea-level land: negative contours, early-exit correctness |
| an all-sea Pacific shard | should finish in seconds after 3.3 |
| Fiji / antimeridian shard | x wrap |
| Iceland (all 9 shards) | regression check against the tuned results |

For each pilot shard record tiles, seconds, tiles/s, peak heap, failures, and file size. Run `compare.py` against
the RBT windows: 0 failed checks expected, and in the Alps also check that the smoothness ratios hold with native
z13 data. If they don't, add z13-native settings (`--rbtSmoothing`).
Then extrapolate: total time is about Σ(land shards) × median seconds per shard, weighted by shard tile count.

**Go/no-go for the planet:** all pilot checks pass; no memory growth across 8 consecutive shards in one JVM;
the merge of the pilot shards passes `integrity_check`; the measured planet estimate fits the machine budget.

---

## 6. Production run

1. **Machine:** for example 32–64 cores, 128 GB RAM (`-Xmx64g`: about 3 GB of caches, OSM indexes, per-thread
   arrays), 1–2 TB NVMe. Or several smaller machines sharing the input (read-only) and an output directory, each
   with its own `--worker i/n`.
2. **Generate the shard list** (§3.6) and commit it alongside the run log, for reproducibility.
3. **Run:**
   ```
   java -Xmx64g -jar vectorvector-cli.jar --profile rbt --dataFormat mapterhorn \
     --pmtiles /data/planet.pmtiles --pmtiles /data/z13/*.pmtiles \
     --glaciers /data/rbt-features.osm.pbf --peaks \
     --shards shards.txt --shardDir /out/shards --worker 0/1 -f mbtiles
   ```
   (`--pmtiles` takes repeated values; a shell glob expands to them.)
4. **Monitor:** shard completion count and ETA from the log; failure files; disk space.
5. **Retry:** rerun the same command. Finished shards are skipped; failed shards are redone (after a fix, if the
   failure is deterministic).
6. **Merge** (§3.6), then check: `PRAGMA integrity_check`, tile counts per zoom equal the sum over shards, a sample
   decode of 1,000 random tiles, and `compare.py` on the pilot windows from the final file.
7. **Visual check** in tileserver-gl with `styles/RBT-TOPO-contours` and in QGIS, across a few shard edges
   (seams) and the antimeridian.
8. **Publish:** `planet.mbtiles`, `SOURCES.md` (data dates, archive list, MD5s), the run log and the shard list.

---

## 7. Risks

| Risk | Mitigation |
|---|---|
| Native z13 (Alps etc.) looks different from overzoomed z12 | Pilot compares the Alps with RBT; per-zoom settings can differ for native data |
| A glacier geometry throws a JTS error | Fault barrier (3.1); the tile is retried after a fix; the shard is not marked done |
| Shard time varies a lot (Antarctica, Himalaya) | `--worker i/n` interleaves shards; Hilbert order; slow shards show in the logs |
| Corrupted file after a crash (journal OFF) | Write to `.tmp` and rename; merge restarts from shards |
| Disk fills during merge | The merge needs about the final size free; check before starting |
| Mapterhorn updates archives during preparation | Record MD5 and download date; all inputs are local before the run starts |
| Licences | Mapterhorn sources (several CC-BY, Copernicus) and OSM ODbL: attribution in metadata and README |

---

## 8. Work breakdown

| Step | Content | Effort (estimate) |
|---|---|---|
| 1 | 3.1 fault isolation + 3.2 startup fixes | 0.5 day |
| 2 | 3.3 sea skip (offset dedupe + flat early exit) | 0.5 day |
| 3 | 3.4 Planetiler-style writer + benchmark | 1 day |
| 4 | 3.5 shard runner + resume, 3.6 shard list + merge | 1.5 days |
| 5 | 4 data preparation (mostly download time) | 1–2 days elapsed |
| 6 | 5 pilot + any tuning for native z13 | 1–2 days |
| 7 | 6 production run + merge + checks | 1–3 days elapsed, depending on machine |
