# vectorvector-rbt: Implementation Plan

Historical implementation notes. Current commands and readiness are in
[the runbook](../planet-sharding.md) and [planet preflight](../planet-preflight.md).
The phase statuses and machine paths below describe the original investigation.
The quoted z13 ice-agreement figure later proved invalid because the analysis ignored differing
layer extents; see the correction in [the tuning log](../tuning-log.md).

**Goal:** Extend vectorvector so it can:

1. Read **Mapterhorn** elevation tiles from **PMTiles** archives, either local files or remote over HTTP range requests.
2. Read **glacier polygons** (`natural=glacier`) from an **OSM PBF** file.
3. Produce a `contours.mbtiles` whose `contours` layer matches the RBT reference schema and intervals. Each contour line is **split at glacier edges** and tagged `is_ice=1` (on ice) or `is_ice=0` (on land), so a style can color each part differently.
4. *(Bonus)* Add a `peaks` layer with OSM mountain peaks to the same `contours.mbtiles`.

**Test and evaluation area:** Iceland.
**Reference:** the `contours` layer in `E:\RBT\tileserver-gl\data\RBT.mbtiles`.

Upstream base: `greensopinion/vectorvector` @ `63767d8f6200606984bbbb9d8f724473f228e884` (2025-02-16).

---

## 0. Background every developer should read first

### 0.1 How vectorvector works today (≈2,600 lines of Kotlin)

| Step | Class | What it does |
|---|---|---|
| CLI | `cli/CliOptions.kt` | picocli options. `--data` (a GeoTIFF folder) is required. Named areas set the tile range. |
| Wiring | `Main.kt` | Builds an `ElevationDataStore` and one `TileSink` per output, each writing to its own `TileRepository` (`<name>.mbtiles`). |
| Loop | `Processor.kt` | For every tile in `TileRange` (all zooms), it calls `sink.accept(Tile)` in parallel on `Dispatchers.IO`. |
| Elevation | `elevation/ElevationDataStore` | `get(TileId): ElevationTile`. An `ElevationTile` is a square grid (`extent`, 256 today) of `Elevation(meters)`. `INVALID_ELEVATION` means no data. |
| Neighbours | `ElevationTileArea` | Wraps a tile so that `get(x,y)` outside `0..extent` reads the 8 neighbouring tiles. This is how lines continue across tile edges. |
| Contours | `sink/contour/ContourLineGenerator` → `MarchingSquares` | Generates isolines every `minorLevel` metres. Output is `Map<Elevation, List<Line>>` in **tile coordinates of extent 4096**, with y pointing down, which matches MVT. |
| Simplify | `LineSimplifier` | Ramer–Douglas–Peucker with `epsilon` in 4096-units (default 3). |
| Encode | `ContourVectorTileMapper` | One MVT layer `contours` with tags `ele` and `level` (1 = major). |
| Write | `VectorTileSink` → `MbtilesTileRepository` | Writes the PBF into `vector.mbtiles` with TileJSON metadata from `vectorSchema()`. |

### 0.2 The RBT reference schema (from `RBT-Public` styles, RBT_2_0_0 and current-release)

The RBT-TOPO and RBT-DARK styles read source-layer **`contours`** from source `RBT`:

| Field | Type | Meaning (**verified in Phase 0**, see `docs/rbt-reference-report.md`) |
|---|---|---|
| `ele` | int | Contour elevation in metres. Can be negative (inland depressions only; no bathymetry). Never 0. |
| `index` | int ∈ {20, 50, 100, 200, 400, 800} | Largest value in that set that divides `ele`. Examples: 800 → 800, 1200 → 400, 600 → 200, 300 → 100, 150 → 50, 40 → 20. Verified: 0 mismatches. |
| `is_ice` | int 0/1 | 1 = segment lies on a glacier. Land lines are colored `#955058`, ice lines `#00a0df`. |

**What the data actually stores (Phase 0, Iceland):**

| Zoom | `ele` values stored | Notes |
|---|---|---|
| 9–10 | multiples of 200 | |
| 11 | multiples of 100 | |
| 12–13 | multiples of 20 **and** multiples of 50 (union) | the style hides what it doesn't want per zoom |
| ≥14 | none | archive `maxzoom = 13`; clients overzoom |

Tiles have extent 4096 and are clipped with an **80-unit buffer** (coordinates −80…4176). No other attributes exist.

The style's line filters imply these **visible intervals per zoom**. Layer minzoom is 9.

| Zoom | Lines shown (`index in …`) | Effective interval | Labels (`index in …`) |
|---|---|---|---|
| 9–10 | 200, 400, 800 | 200 m | 800 |
| 11 | 100, 200, 400, 800 | 100 m | 400, 800 |
| 12 | 50, 100, 200, 400, 800 | 50 m | 200, 400, 800 |
| ≥13 | 20, 100, 200, 400, 800 | 20 m (the 50-multiples that are not 20-multiples are hidden) | 100, 200, 400, 800 |

Glaciers in RBT come from layer `glacier_polygon` with `source = "osm"` at z≥8, so OSM `natural=glacier` is the right equivalent.

> **Peaks (bonus):** no published RBT style, in any commit of `RBT-Public` (119 commits searched), contains a peak layer, and `RBT_1_1_0` only has placeholder files. Unless someone provides the old style, we use an OpenMapTiles-like `peaks` schema (§6).

### 0.3 Mapterhorn facts

* Terrain RGB tiles in **Terrarium** encoding, stored as **WebP**, **512×512 px**.
  `elevation_m = (R * 256 + G + B / 256) - 32768`
* `https://download.mapterhorn.com/planet.pmtiles` holds z0–z12. High zooms (z13–z17) are split into files named `6-{x}-{y}.pmtiles`, one per **z6 parent tile**, in areas with high-resolution sources.
  * Full list: `https://download.mapterhorn.com/download_urls.json`
  * Single tiles: `https://tiles.mapterhorn.com/{z}/{x}/{y}.webp`
* Iceland covers **z6 tiles x = 27…29, y = 15…17**, so the candidate high-zoom files are `6-27-15` … `6-29-17`. **Checked (Phase 0): only `6-27-15` exists, and its data lies at 68.3–68.7 N (Greenland), so Iceland has no z13+ Mapterhorn data.** z13 contours come from z12 elevation. Iceland's sources are ÍslandsDEM v1.0 (CC BY 4.0) and Copernicus GLO-30.
* A 512 px tile at zoom *z* covers the same ground as a 256 px tile at zoom *z*. So Mapterhorn z12 gives about 8 m/px at Iceland's latitude.

### 0.4 PMTiles v3 essentials (spec: https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md)

* **Header:** 127 bytes, little-endian. Read the first 16 KiB in one request, because the root directory usually follows the header.

| Offset | Size | Field |
|---|---|---|
| 0 | 7 | magic `PMTiles` |
| 7 | 1 | version (= 3) |
| 8 / 16 | 8 / 8 | root dir offset / length |
| 24 / 32 | 8 / 8 | metadata offset / length |
| 40 / 48 | 8 / 8 | leaf dirs offset / length |
| 56 / 64 | 8 / 8 | tile data offset / length |
| 72 / 80 / 88 | 8 each | addressed tiles / tile entries / tile contents counts |
| 96 | 1 | clustered |
| 97 | 1 | internal compression (1 = none, 2 = gzip, 3 = brotli, 4 = zstd) |
| 98 | 1 | tile compression |
| 99 | 1 | tile type (2 = png, 3 = jpeg, 4 = webp, …) |
| 100 / 101 | 1 / 1 | min zoom / max zoom |
| 102…117 | 4 × int32 | min lon, min lat, max lon, max lat (×1e7) |
| 118 | 1 | center zoom |
| 119 / 123 | int32 / int32 | center lon / lat |

* **Directory** (decompress with *internal compression* first). All numbers are unsigned varints:
  1. `numEntries`
  2. `numEntries` tile-id **deltas** (cumulative sum)
  3. `numEntries` run lengths
  4. `numEntries` lengths
  5. `numEntries` offsets. A value of `0` for entry *i > 0* means `offset = prev.offset + prev.length`. Otherwise `offset = value - 1`.
* An entry with `runLength == 0` points to a **leaf directory** at `leafDirsOffset + offset`. Otherwise the tile is at `tileDataOffset + offset`, and the entry covers tile ids `[tileId, tileId + runLength)`.
* **TileID** = (number of tiles in all zooms < z) + Hilbert index of (x, y) at z. Port `zxyToTileId` from the reference implementation and test it with these vectors: `(0,0,0)=0`, `(1,0,0)=1`, `(1,0,1)=2`, `(1,1,1)=3`, `(1,1,0)=4`, `(2,0,0)=5`.
* **Lookup** = binary search for the last entry with `tileId <= target`. Follow leaf entries recursively, at most 3 levels deep.

### 0.5 Environment constraints (important)

* The cloud workspace that produced this plan **cannot reach** Maven Central, `*.mapterhorn.com` or `download.geofabrik.de`. Builds and data runs happen on a developer PC unless the organization's egress allow-list is widened.
* Developer PC needs: JDK 21, Maven 3.9+, Git, Python 3.11+ (`pip install mapbox-vector-tile shapely numpy matplotlib`), and optionally the `pmtiles` CLI (go-pmtiles) and `osmium-tool`.
* tileserver-gl is already present at `E:\RBT\tileserver-gl` for visual comparison.
* **Actual setup on the developer PC (2026-10-13):** everything lives inside the repo folder. JDK 21 (Temurin) and Maven 3.9.9 are unpacked into the git-ignored `.tools/`, and `. .\env.ps1` puts them on `PATH`. `pmtiles.exe` v1.31.2 is in `.tools/bin`. Python is a 3.10 venv in `.tools/venv` (3.11 is not required). osmium is not installed and not needed for Iceland. `E:\RBT` is used read-only as the reference; nothing is written there.

---

## Phase 0: Data acquisition and reference analysis (no Kotlin) — ✅ done 2026-10-13

> Status: baseline `mvn test` green on JDK 21 (53 tests). Data downloaded (see `data/iceland/SOURCES.md`). The z ≥ 13 sample windows exist only at z13, because RBT stops there. The reference subset keeps only the `contours`, `glacier_polygon`, `water_polygon` and `ocean_polygon` layers, with layer bytes copied verbatim. Findings are in `docs/rbt-reference-report.md` and are merged into §0.2.

**Goal:** Have local Iceland test data and a written fact sheet about the RBT contours, so all later tuning has a target.

### Tasks

1. **Build baseline.** Clone the repo, run `mvn -q test`, and record that it is green. Fix anything platform-specific before changing code.
2. **Create a data folder** `data/iceland/`. It is git-ignored: add `data/` to `.gitignore`.
3. **Download OSM.** Get `https://download.geofabrik.de/europe/iceland-latest.osm.pbf` (~60 MB). Note its date in `data/iceland/SOURCES.md`.
4. **Download Mapterhorn.**
   * Download `download_urls.json` and list which `6-x-y.pmtiles` exist for x 27–29, y 15–17.
   * Make a local Iceland extract for fast iteration:
     ```
     pmtiles extract https://download.mapterhorn.com/planet.pmtiles data/iceland/mapterhorn-iceland-z0-12.pmtiles --bbox=-24.8,63.1,-13.2,66.8
     ```
     Do the same for each existing high-zoom file covering Iceland. Use `--dry-run` first to see the size.
   * Read Mapterhorn's **attribution** page and record which sources cover Iceland and their licenses in `SOURCES.md`. These go into the mbtiles `attribution`.
5. **Write the reference extraction script** `tools/rbt_reference/extract_reference.py`. Use plain Python `sqlite3`; the file is 169 GB, so never copy it whole.
   * Iceland tile ranges (XYZ):

     | Zoom | x range | y range |
     |---|---|---|
     | 9 | 221–237 | 127–139 |
     | 12 | 1768–1896 | 1018–1112 |
     | 13 | 3536–3793 | 2037–2225 |
     | 14 | 7072–7586 | 4074–4451 |

     Compute other zooms with the standard lon/lat → tile formula over bbox `-24.6, 63.2, -13.3, 66.7`.
   * MBTiles uses **TMS rows**: `tile_row = 2^z - 1 - y`.
   * Query: `SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles WHERE zoom_level=? AND tile_column BETWEEN ? AND ? AND tile_row BETWEEN ? AND ?`
   * Copy all Iceland tiles for z ≤ 12. For z ≥ 13, copy only three sample windows of about 8×8 tiles each:
     * Öræfajökull / Hvannadalshnúkur (63.99 N, 16.67 W): glacier edges and high relief
     * Eyjafjallajökull (63.63 N, 19.62 W)
     * Esja / Reykjavík (64.25 N, 21.65 W): land only
   * Write the output as `data/iceland/rbt-reference-iceland.mbtiles` with the same `metadata` rows.
   * Tiles may be gzip-compressed. Detect the gzip magic bytes `1f 8b` and keep the bytes as they are.
6. **Write the analysis script** `tools/rbt_reference/analyze_contours.py <mbtiles> [--layer contours]`. It decodes tiles with `mapbox_vector_tile` and writes a JSON + Markdown report with:
   * `metadata` table rows, including `json`/`vector_layers`, minzoom, maxzoom, and extent
   * Per zoom:
     * tile count, and all property keys and value types seen in `contours`
     * the set of distinct `ele` values, and from that the **actual interval** (GCD of the differences)
     * `index` histogram, and a check that `index` equals the largest divisor in {800,400,200,100,50,20}
     * `is_ice` ratio, and whether negative or zero `ele` exists
     * geometry type: LineString vs MultiLineString
   * **Smoothness metrics** per zoom (median and 90th percentile). These are the tuning targets in Phase 7:
     * `vertex_spacing`: mean segment length in tile units
     * `turn_angle`: mean absolute turning angle per interior vertex, in degrees
     * `sharp_ratio`: share of vertices with turn > 45°
     * `length_per_tile`: total line length per tile, per 100 m of elevation interval
     * `features_per_tile`
   * The tile `extent`, and whether lines are clipped at the tile edge or carry a buffer
7. **Write it down.** Run the analysis on the reference file and commit the report as `docs/rbt-reference-report.md`. **Update the tables in §0.2 if the data disagrees with the style-derived assumptions.** In particular, check:
   * the interval actually stored per zoom
   * the max zoom
   * whether contours exist below sea level
   * whether there are extra attributes

**Done when:** `data/iceland/` contains the OSM PBF, the Mapterhorn extract(s) and the RBT reference subset, and `docs/rbt-reference-report.md` exists and has been reviewed by a senior developer.

**Pitfalls:**

* Don't open the 169 GB file in DB Browser for SQLite; it may try to scan it.
* Open it read-only with the URI `file:E:/RBT/tileserver-gl/data/RBT.mbtiles?mode=ro` and `uri=True`.

---

## Phase 1: Housekeeping and configuration — ✅ done 2026-10-13

> Status: implemented as specified. Extras: `--bbox` without `--area` derives the tile range at `minZ`; `--hillshadeRaster` now defaults to false for the rbt profile or a 512 extent; `--glaciers` requires `--profile rbt`. `DefaultContourGoldenTest` pins the upstream contour bytes (hashes recorded before any change), so "byte-identical" is checked on every `mvn test`.

**Goal:** Prepare the CLI and wiring so the new sources and profile can be plugged in without breaking existing behaviour.

### Tasks

1. **`CliOptions.kt`**
   * Make `--data` **not required**, and validate it manually: it is required only when `dataFormat` is `srtm` or `aw3d30`.
   * Add `DataSourceFormat.mapterhorn`.
   * Add these options:

     | Option | Purpose |
     |---|---|
     | `--pmtiles <path-or-url>` (repeatable) | Mapterhorn archives. Example: one `planet`, plus zero or more `6-x-y` files. |
     | `--pmtilesCacheDir <dir>` | Optional disk cache for remote tile bytes. |
     | `--elevationExtent <256\|512>` | Default 512 for Mapterhorn; see Phase 3. |
     | `--glaciers <file.osm.pbf>` | Turns on is_ice splitting. |
     | `--peaks <file.osm.pbf>` | Defaults to the `--glaciers` file if given. Turns on the peaks layer. |
     | `--profile <default\|rbt>` | Selects intervals and attributes. |
     | `--vectorName <name>` | Default `vector`. The `rbt` profile sets `contours`. |

2. **Named area.** Add to `NamedArea`:
   `iceland(AreaBounds(minZ = 6, maxZ = 13, minX = 27, maxX = 29, minY = 15, maxY = 17))` (maxZ 13 = RBT max zoom).
   Note: `TileRange` expands min-zoom x/y to all child tiles at higher zooms, so check `TileRangeTest` to confirm.
3. **Clipping to the real area.** Add an optional `--bbox minLon,minLat,maxLon,maxLat` that skips tiles outside the box. Iceland's z6 tiles include a lot of ocean, and skipping it saves time.
4. **`Main.kt` cleanup.** Move the inline contour-interval lambda into a `ContourProfile` interface with `DefaultProfile` (today's behaviour, unchanged) and `RbtProfile` (Phase 4).
5. **Disable hillshade by default** when `--profile rbt` is used. RBT already has `TERRAIN.mbtiles`.
6. **Tests:**
   * CLI parsing tests: valid and invalid combinations
   * A `TileRange` test for the `iceland` area

**Done when:** `mvn test` is green, and running with the old GeoTIFF options gives byte-identical output to before. Check with a tiny SRTM sample if one is available; otherwise review the diff carefully.

---

## Phase 2: PMTiles reader (pure, well-tested component) — ✅ done 2026-10-13

> Status: implemented as specified, 8 tests. `PmtilesInfoKt <path-or-url> [z/x/y…]` prints the header and tile digests; local extract and remote planet return identical bytes for Iceland tiles (remote: 9.4 M entries, leaf directories exercised). Deviation: with `--pmtilesCacheDir`, directory reads are cached on disk too, not only tile payloads (simpler, and makes restarts cheap). An HTTP concurrency limit (`Semaphore(16)`, Phase 8.1) is already built into `HttpRangeSource`. Fixture: `src/test/resources/pmtiles/fixture.pmtiles` (290 KB, z8–10 around Hvannadalshnúkur).

**Goal:** `PmtilesReader` returns raw tile bytes for (z, x, y) from a local file or an HTTP(S) URL.

### New files (`src/main/kotlin/com/greensopinion/vectorvector/pmtiles/`)

| File | Responsibility |
|---|---|
| `RangeSource.kt` | `interface RangeSource : Closeable { fun read(offset: Long, length: Int): ByteArray; val name: String }` |
| `FileRangeSource.kt` | Uses `FileChannel` positional reads. Thread-safe; no shared position. |
| `HttpRangeSource.kt` | Uses `java.net.http.HttpClient` with header `Range: bytes=start-end` (end inclusive). Expects `206`; treat `200` as an error unless the length matches. Retry 3× with exponential backoff on IOException/5xx/429. Send `User-Agent: vectorvector-rbt/<version>`. Store the `ETag` from the first response; if it changes mid-run, fail loudly because the archive was replaced. |
| `CachingRangeSource.kt` | Optional on-disk cache keyed by `sha1(url) + offset + length`. Used only for tile payload reads. Directories are cached in memory. |
| `PmtilesHeader.kt` | Data class + `parse(bytes: ByteArray)`, using `ByteBuffer.order(LITTLE_ENDIAN)`. Validate the magic and that version = 3. |
| `PmtilesDirectory.kt` | `data class Entry(tileId: Long, offset: Long, length: Int, runLength: Int)`, plus `deserialize(bytes)` following §0.4 exactly. |
| `TileIds.kt` | `zxyToTileId(z, x, y): Long`, ported from the official JS/Go reference. |
| `PmtilesReader.kt` | `class PmtilesReader(source: RangeSource) { val header; fun getTile(z: Int, x: Int, y: Int): ByteArray? }` |

### `PmtilesReader` details

* Constructor:
  1. Read bytes `0..16383`.
  2. Parse the header.
  3. Slice the root directory from that buffer, or read it separately if it lies beyond.
  4. Decompress with gzip (`GZIPInputStream`) when internal compression is 2; use the bytes as-is when it is 1.
  5. Throw `UnsupportedOperationException` for brotli/zstd with a clear message.
* `getTile`:
  1. Return `null` when the zoom is outside `[minZoom, maxZoom]`.
  2. Compute the tile id and search the root directory, then follow leaves.
  3. Cache leaf directories in a bounded LRU: Guava `CacheBuilder`, max 256 entries, keyed by `(offset, length)`.
  4. Decompress the tile with *tile compression* if it is not 1. For WebP it should be 1.
  5. Return `null` if the tile is not found. Iceland ocean tiles may be absent.
* Thread safety: the reader is shared across coroutines, so all caches must be concurrent.

### Tests (`src/test/.../pmtiles/`)

* `TileIdsTest`: the vectors from §0.4, plus round trips at z = 12 for random x, y if you also port `tileIdToZxy`.
* `PmtilesDirectoryTest`: hand-build a directory using a varint writer in the test, then deserialize it and check the offset-0 rule.
* `PmtilesReaderTest`: commit a tiny fixture `src/test/resources/pmtiles/fixture.pmtiles`. Create it with `pmtiles extract` of 2–3 Mapterhorn tiles around Hvannadalshnúkur at z10, keeping it under 1 MB. Assert that a known tile returns non-null bytes that start with `RIFF....WEBP`.
* `HttpRangeSourceTest`: use the JDK `com.sun.net.httpserver.HttpServer` on localhost to serve the fixture with Range support. Assert the same bytes as `FileRangeSource`.

**Done when:** all tests are green, and a small `main` or IT prints the Iceland header (`minZoom`, `maxZoom`, `bounds`, `tileType = webp`) for both the local extract and the remote planet URL.

---

## Phase 3: Mapterhorn elevation data store — ✅ done 2026-10-13

> Status: implemented (`elevation/mapterhorn/`). Findings:
> * **TwelveMonkeys 3.12.0 fails** on 7 of 23k Iceland tiles (WebP lossless colour-indexed bug). **3.15.3** decodes all of them, bit-exact against libwebp, so both `imageio-tiff` and `imageio-webp` are now 3.15.3. `WebpScanDataTest` (opt-in with `-DargLine=-DwebpScan=12`) re-checks a whole extract.
> * **No bathymetry:** Mapterhorn returns exactly 0 m over the sea around Iceland, so negative contours can be kept, as in RBT (Phase 4.3).
> * Hvannadalshnúkur's summit is at **64.0147 N, 16.6767 W**; the 63.999 N used below is about 1.7 km south. At z12, Mapterhorn reads 2089 m there, and Reykjavík harbour reads 0 m.
> * The store keeps its own Guava caches (1,500 decoded native tiles and 1,500 output tiles) instead of `CachingElevationDataStore`. Tiles outside the world or absent from every archive (after up to 4 overzoom levels) are `EmptyTile`.
> * Terrarium output (z6–11, extent 256) matches the Mapterhorn quadrants exactly (max abs diff 0.0). Visual tileserver-gl checks are deferred to Phase 7, so nothing is written to `E:`.

**Goal:** `MapterhornElevationDataStore : ElevationDataStore` returns correct `ElevationTile`s for any output tile.

### Tasks

1. **WebP decoding.** Add the dependency `com.twelvemonkeys.imageio:imageio-webp`, using the same version as the existing `imageio-tiff`. Decode with `ImageIO.read(ByteArrayInputStream(bytes))`. If it returns `null`, the plugin is not registered: call `ImageIO.scanForPlugins()` once at startup. With the shade plugin, also verify that `META-INF/services` files are merged with `ServicesResourceTransformer` in `pom.xml`. Write a test that decodes the fixture tile and reads pixel (0,0).
2. **Terrarium decoding:** `TerrariumDecoder.decode(img: BufferedImage): FloatArray` (row-major, y down). Use `img.getRGB` in bulk (`getRGB(0, 0, w, h, null, 0, w)`) for speed. Pixels with alpha = 0 → `NaN` → `INVALID_ELEVATION`.
3. **Source routing:** `MapterhornSourceSet(readers: List<PmtilesReader>)`.
   * `readerFor(z, x, y)`:
     * For z ≤ 12, use the reader whose header `maxZoom ≤ 12` (the planet file).
     * For z ≥ 13, compute the z6 parent `(x >> (z-6), y >> (z-6))`. Use the reader whose file name is `6-px-py` or whose header bounds contain the tile.
     * Otherwise return `null`.
   * `fetch(z, x, y): FloatArray?`. If no reader or no tile, **fall back to overzoom**: fetch the parent `(z-1, x/2, y/2)`, take the quadrant, and bilinearly upsample. Repeat up to 4 levels. Log overzoom usage as a metric (`Mapterhorn.overzoom`).
4. **Map output tiles to elevation grids** (`--elevationExtent`):
   * **512 (default, "native")**: for output tile (z, x, y), return the full 512×512 Mapterhorn tile (z, x, y) as an `ElevationTile` with `extent = 512`. `MarchingSquares` already scales by `extent / tile.extent` (4096 / 512 = 8).
   * **256 ("quadrant")**: return the quadrant `(x % 2, y % 2)` of Mapterhorn tile (z-1, x/2, y/2), which is exact 256×256 native pixels with no resampling. Use this to compare smoothness.
   * Check every place that assumes 256: `Main.kt` (`tileExtent = 256`), `ElevationTileArea`, `HillshadeRasterSink`, `TerrariumSink`. Hillshade and terrarium may remain 256-only; reject `--elevationExtent 512` combined with them in CLI validation.
5. **Caching.** Wrap the store in the existing `CachingElevationDataStore`. Check its size limit; at 512² floats a tile is 1 MB, so cap it at about 2,000 tiles. The Mapterhorn tile cache should be keyed by Mapterhorn (z, x, y) so that neighbours requested via `ElevationTileArea` are not fetched twice.
6. **Wire it up.** In `Main.kt`, `cachingElevationDataStore` gets a `mapterhorn` branch.
7. **Pixel registration.** Terrarium pixels are cell-centred; the existing code treats index i as position i. Accept the half-pixel shift for now (≤ 4 m at z12). Note it in code comments, and revisit in Phase 7 if the comparison shows a systematic offset.

### Tests

* Unit: Terrarium decode of synthetic colors, e.g. `R=128, G=0, B=0` → 0 m, and `R=136, G=62, B=0` → 2110 m. Also quadrant extraction indexes, and overzoom upsampling on a 2×2 ramp.
* Integration (`MapterhornIcelandDataTest`, skipped without `data/iceland`): the elevation at Hvannadalshnúkur (64.0147 N, 16.6767 W) at z12 is between 2,050 and 2,130 m, and Reykjavík harbour is between −5 and 10 m.

**Done when:** `java -jar target/vectorvector-cli.jar --dataFormat mapterhorn --pmtiles data/iceland/mapterhorn-iceland-z0-12.pmtiles -a iceland -maxZ 12 -f mbtiles -o out --terrarium --hillshadeRaster=false --vector=false` produces a terrarium mbtiles. It should look correct in tileserver-gl as a raster-dem source (encoding `terrarium`) next to the RBT `TERRAIN.mbtiles`.

---

## Phase 4: RBT contour profile (intervals, attributes, smoothing) — ✅ done 2026-10-13

> Status: implemented. The upstream contour path is untouched (golden test); the RBT path is `ContourPostProcessor`:
> blur → isolines → Chaikin → RDP → ice split → clip to the 80-unit buffer → round. Deviations and findings:
> * **Performance:** upstream `MarchingSquares` scans the whole grid once per level and joins segments with linear
>   scans; at 512 px and about 120 levels per z12–13 tile, a full Iceland run would take more than 2 h. The new
>   `FastIsolines` visits each cell once and emits only the levels between the corner min and max. It chains
>   segments by exact point lookups, which works because edge crossings are computed in a canonical direction. It
>   matches `MarchingSquares` point for point (`FastIsolinesTest`).
> * Levels: z12–13 generate on a 10 m grid and keep `ele % 20 == 0 || ele % 50 == 0` (see §0.2).
> * Negative contours are kept (`minLevelExclusive = Int.MIN_VALUE`), as in RBT, since Mapterhorn has no bathymetry.
> * Blur with sigma > 2 px uses three box passes (`GaussianBlur.boxRadii`), so cost no longer grows with sigma; the tuned metrics are unchanged.
> * RDP epsilon is fractional for RBT (`rdpEpsilon`); `--rbtSmoothing` overrides the per-zoom settings from the CLI.
> * Metadata: `vectorSchema()` takes a list of `LayerSchema`. Metadata keys are now the spec's lowercase
>   `minzoom`/`maxzoom` (upstream wrote `minZoom`/`maxZoom`), plus `attribution`, `description` and the `--bbox` `bounds`.

**Goal:** For land-only areas, our `contours` layer has the same schema, zoom range and intervals as RBT.

### Tasks

1. **`RbtProfile` contour options per zoom.** Default to the values from §0.2, and update them from Phase 0 findings:

   | Zoom | Levels generated (verified against RBT in Phase 0) |
   |---|---|
   | 9–10 | multiples of 200 m |
   | 11 | multiples of 100 m |
   | 12–13 | multiples of 20 m **or** 50 m (e.g. …, 140, 150, 160, 180, 200, 220, 240, 250, …) |

   * Zooms below 9 and above 13 produce no contour tiles.
   * Implementation: generate on a 10 m grid at z12–13 and keep levels where `ele % 20 == 0 || ele % 50 == 0`.
2. **Attributes.** Extend `ContourOptions` with `attributeStyle: AttributeStyle` (`VECTORVECTOR` or `RBT`). For `RBT`, each feature gets:
   * `ele` (int)
   * `index`: `listOf(800, 400, 200, 100, 50, 20).first { ele % it == 0 }`, or the interval itself if none match. Note that Kotlin `%` of a negative number is ≤ 0, so use `Math.floorMod` or `abs`.
   * `is_ice` (int 0/1; always 0 until Phase 5)

   Leave `level` out in RBT mode, unless the report shows RBT has extra keys, in which case add them.
3. **Sea level and below.** Keep `isSeaLevel` exclusion: no 0 m line. Mapterhorn may include bathymetry offshore, which would create underwater contours. Add `minElevation` to the profile:
   * Phase 0: RBT has a few negative contours (−20…−60 m) in one inland depression and none offshore. Default to `> 0` anyway unless Mapterhorn turns out to have no bathymetry; inland negatives are rare enough to lose.
   * Also drop any line whose every vertex lies where the elevation ≤ 0 m. This is optional and only needed if offshore artifacts appear.
4. **Schema metadata.** Generalise `vectorSchema()` to take a list of `LayerSchema(id, description, minzoom, maxzoom, fields)`. In the RBT profile, emit `contours` with fields `ele`, `index`, `is_ice`. Also write the `attribution` metadata, e.g. "Elevation: Mapterhorn (…sources…); Glaciers & peaks: © OpenStreetMap contributors".
5. **Smoothing pipeline.** Each step is configurable per zoom in `ContourOptions` (`smoothing: SmoothingOptions`):
   1. **DEM pre-smoothing:** a separable Gaussian blur, `sigmaPx`, default 0 (off), applied to the *materialized area*. Materialize with `buffer = max(contourBuffer, ceil(3 * sigmaPx))` so blur results at tile edges match the neighbouring tile. Without this, tile seams appear. Then run `MarchingSquares` on the blurred grid.
   2. **Chaikin smoothing:** `iterations`, default 0, applied to each line after marching squares. **Endpoints of open lines must be kept fixed**, otherwise lines no longer meet at tile edges. Closed rings (first == last) are smoothed cyclically.
   3. **RDP simplification:** keep the existing `LineSimplifier`, with `epsilon` per zoom.
   4. Round to integer tile coordinates; this is what MVT encoding does today. Drop lines with fewer than 2 distinct points.

   Put the steps in a `ContourPostProcessor` class so the order is testable. Initial values: `sigmaPx = 1.0`, `chaikin = 2`, `epsilon = 2`. Tune them in Phase 7.
6. **Tile buffer.** RBT clips lines at an **80-unit buffer** (−80…4176, Phase 0). Match it: generate with enough elevation buffer (≥ 10 px at 512 px) and clip the output lines to the buffered tile box. Current behaviour: `buffer = 1` elevation pixel, which is 8 tile units at 512 px.

### Tests

* `index` computation table test, including 0 and negatives
* Chaikin keeps endpoints fixed and closed rings stay closed
* Gaussian blur of a constant grid is unchanged, and blur of a step is symmetric
* `ContourVectorTileMapper` in RBT mode emits exactly the keys `ele`, `index`, `is_ice`, with int values

**Done when:** a `contours.mbtiles` for Iceland at z9–z12 (and z13–14 for the sample windows) opens in tileserver-gl with the converted style (Phase 7.4) and shows intervals that match RBT at each zoom.

---

## Phase 5: Glacier index and contour splitting (`is_ice`) — ✅ done 2026-10-13

> Status: implemented (`osm/`). Deviations and findings:
> * **No OSM library:** `OsmPbfScanner` is a small dependency-free PBF decoder (zlib blobs, dense nodes, ways,
>   relations), decoded in parallel and delivered in file order. This avoids the protobuf-java 3 vs 4 clash entirely.
> * **Overlaps never cancel out:** relation rings are built by joining member ways end to end (`LineMerger`), not
>   by `Polygonizer` even-odd extraction. Each ring is fixed on its own, outers are unioned and inners subtracted.
>   Per-tile glacier geometry is the union of all glaciers touching the tile. Tests cover overlapping outers of one
>   relation, overlapping separate glaciers, and a self-overlapping ring.
> * Per-tile clipping is hierarchical: z ≥ 7 is clipped from the cached parent tile, so Vatnajökull is not re-intersected in full for every z13 tile.
> * Iceland: 314 glaciers, 10,265 km², 0 broken relations, 0 unclosed ways, loaded in about 6 s.
> * Order: the split happens after RDP and before clipping, on unrounded coordinates.

**Goal:** Every contour segment on a glacier has `is_ice=1`, every other segment has `is_ice=0`, and the split lies exactly on the glacier edge.

### 5.1 Read glaciers from OSM PBF

* **Dependencies:**
  * `org.locationtech.jts:jts-core` (geometry)
  * An OSM PBF parser: preferred `org.openstreetmap.pbf:osmpbf` (small), or the Osmosis PBF reader. Run `mvn dependency:tree` and check that the `protobuf-java` version does not conflict with the project's `protobuf-kotlin`. If it does, the fallback is to generate classes from OSM's `fileformat.proto` and `osmformat.proto` using the project's existing `generate-protobuf.xml`, and decode blobs with `java.util.zip.Inflater`.
* **New package** `osm/`:
  * `OsmPbfScanner`: a streaming reader with callbacks for nodes, ways and relations, with tags as `Map<String, String>`. Read *DenseNodes* too; that is how most nodes are stored.
  * `GlacierLoader`: a 3-pass read of the same file.
    1. **Relations:** collect `type=multipolygon` + `natural=glacier` relations, with member way ids and roles (`outer`/`inner`). **Ways:** remember ids of ways tagged `natural=glacier`.
    2. **Ways:** for every way needed (tagged ones and relation members), store its node id list.
    3. **Nodes:** for every node id needed, store lon/lat. Use a `HashMap<Long, Long>` that packs two int32 E7 coordinates, or a sorted `LongArray` + binary search to save memory.
  * **Build geometry:**
    * A closed tagged way → `Polygon`.
    * A relation → polygonize outers and inners separately, then `outerUnion.difference(innerUnion)`. **Do not** use even-odd ("odd number of outer rings") logic or `symDifference`: overlapping glacier polygons must never cancel each other out.
    * **Overlaps:** separate glacier ways/relations may overlap each other, and outer rings of one relation may overlap too. Every combination step uses **union** semantics (`unaryUnion` / `CascadedPolygonUnion`), so a point covered by two glaciers is still ice. `GeometryFixer` runs per polygon *before* the union, because fixing a self-overlapping MultiPolygon on its own can also produce holes.
    * Log and skip broken relations; count them in metrics.
    * Run `GeometryFixer.fix()` (JTS ≥ 1.18) on every result.
  * Result: `GlacierIndex` holding an `STRtree` of `PreparedGeometry` in **EPSG:4326 lon/lat**, plus the union area for sanity logging. Iceland should be about 11,000 km² of glaciers.
* **Performance note:** the Iceland PBF loads in seconds. For planet scale, document that users should pre-filter with `osmium tags-filter planet.osm.pbf nwr/natural=glacier nwr/natural=peak,volcano -o rbt-features.osm.pbf`.

### 5.2 Per-tile glacier geometry

* `GlacierTileProjector.forTile(tileId, extent = 4096, bufferUnits = 256): Geometry?`
  1. Compute the tile's lon/lat bbox, expanded by the buffer, and query the `STRtree`.
  2. Clip each candidate to the expanded bbox *in lon/lat* with `Geometry.intersection(bboxPolygon)`.
  3. Transform coordinates to tile space with a JTS `CoordinateFilter`:
     ```
     px = ((lon + 180) / 360 * 2^z - x) * extent
     py = ((1 - asinh(tan(latRad)) / π) / 2 * 2^z - y) * extent   // y down, matches MVT
     ```
  4. Union the results. Return `null` if empty.
  5. Cache per tile id for the duration of the tile. Optionally precompute at z9 and re-clip for children.

### 5.3 Splitting contour lines

* `IceSplitter(glacier: Geometry?)`, `split(line: Line): List<Pair<Line, Boolean>>`:
  * If `glacier == null`, return `[(line, false)]`.
  * Convert the line to a JTS `LineString` and use `PreparedGeometry`:
    * `disjoint` → `[(line, false)]`
    * `containsProperly` → `[(line, true)]`
    * otherwise, `ice = line.intersection(glacier)` and `land = line.difference(glacier)`. Use `LineMerger` on each to rejoin pieces, then emit `(piece, true)` / `(piece, false)`.
  * Drop pieces shorter than `minPieceLength` (default 4 tile units) to avoid 1-pixel stubs where a line just grazes the edge.
* **Order:** marching squares → smoothing → RDP → **split** → round → encode. Splitting after simplification keeps the cut points exactly on the glacier edge.
* `ContourVectorTileMapper` takes `List<ContourFeature(ele, isIce, line)>` instead of `Map<Elevation, List<Line>>`.

### Tests

* Synthetic square glacier (tile coordinates 1000–2000) and a horizontal line from 0 to 4096 at y = 1500 → 3 pieces: land, ice, land. The ice piece runs from x = 1000 to x = 2000.
* A line fully inside or fully outside → 1 piece with the correct flag.
* A glacier relation with a hole (inner ring) → a line crossing the hole gets ice / land / ice.
* **Overlapping glaciers:** two overlapping square glaciers (two separate ways, and also two outers of one relation) → a line crossing both, including the overlap, is a single ice piece. The overlap must not come out as land.
* Projection test: lon/lat of a tile's corners map to (0,0) and (4096,4096).
* Integration: at z12, the tile containing Hvannadalshnúkur has more than 80% of its 1,000–2,000 m contour length with `is_ice=1`. The tile containing Reykjavík has 0%.
* **Orientation check:** render one z10 tile with glacier polygons drawn over the lines (Python debug script in `tools/`). This catches y-axis flips.

**Done when:** in tileserver-gl, blue contour segments stop and start exactly at the OSM glacier outline from the RBT `glacier_polygon` layer, at z9–z14.

---

## Phase 6 (bonus): Peaks layer — ✅ done 2026-10-13

> Status: implemented as specified (`osm/Peaks.kt`); feature id = OSM node id. Iceland: 2,909 peaks (2,766 named,
> 2,417 with a usable `ele`; the rest get `ele_source = dem` from z12 elevation). Hvannadalshnúkur 2110 m and Snæfell are present.

**Goal:** A `peaks` point layer in the same `contours.mbtiles`.

### Schema (OpenMapTiles `mountain_peak`-like)

| Field | Type | Source |
|---|---|---|
| `name` | string | `name` |
| `name_en` | string | `name:en` (optional) |
| `ele` | int (m) | OSM `ele` parsed. Accept `"2110"`, `"2110 m"`, `"2,110"`, `"6923 ft"` (convert to m); otherwise sample the DEM at the node. |
| `ele_source` | string | `osm` or `dem` |
| `class` | string | `peak`, `volcano` (and `saddle` if wanted later) |
| `rank` | int | 1 = most important in the tile (highest `ele`, named first). Styles filter by rank per zoom. |

### Tasks

1. `PeakLoader`: a one-pass node scan for `natural in (peak, volcano)`, kept as an in-memory list. Reuse `OsmPbfScanner`.
2. Use `PeakIndex` (STRtree of points) and query per tile with the same buffer as contours. Points outside the tile proper but within the buffer **are** included; MVT allows this, and it avoids labels vanishing at edges.
3. Set `rank` per tile: sort by (named first, `ele` desc) and rank 1..n. To thin labels, keep at most `maxPeaksPerTile` (default 50) at z9–11, and all peaks at z ≥ 12.
4. Emit a second layer `peaks` (`POINT` geometry, `command(1,1)` + zigzag x/y) from `VectorTileSink` in the **same tile**, and add it to `vector_layers` metadata. A tile with peaks but no contours must still be written; today the sink returns early when there are no contours.
5. Min zoom for peaks is 9 by default (configurable `--peaksMinZoom`).

### Tests

* `ele` parsing table test
* Rank ordering
* Point encoding round trip with `mapbox_vector_tile` in the Python tool

**Done when:** Hvannadalshnúkur (2,110 m) and Snæfell show as labelled peaks in the comparison style.

---

## Phase 7: Evaluation and tuning against RBT — ✅ done 2026-10-13 (sign-off pending)

> Status: `compare.py`, `tune.sh`, `make_tile_list.py`, `make_compare_style.py`, `styles/config.json`. Tuning
> (`docs/tuning-log.md`): run J passes **all 41 checks** (zooms, intervals, keys and types, index, and smoothness
> ratios within 0.8–1.25 at every zoom). Positional agreement within 2 px is 0.98 / 0.97 / 0.93 / 0.84 / 0.48 at z9–13;
> the decline at higher zooms is expected because RBT uses a coarser DEM. `is_ice` lines lie inside RBT's
> `glacier_polygon` 100 % of the time at z9–12 and 83 % at z13. `--elevationExtent 512` stays the default (256 is
> 2× faster but less accurate).

**Goal:** Show, with numbers and pictures, that intervals match exactly and that smoothness is comparable.

### Tasks

1. **Produce candidates** with the same tile set as the reference subset:
   ```
   java -jar target/vectorvector-cli.jar --profile rbt --dataFormat mapterhorn \
     --pmtiles data/iceland/mapterhorn-iceland-z0-12.pmtiles \
     --glaciers data/iceland/iceland-latest.osm.pbf -a iceland --bbox=-24.6,63.2,-13.3,66.7 \
     -minZ 9 -maxZ 13 -f mbtiles -o out/
   → out/contours.mbtiles
   ```
2. **Comparison script** `tools/rbt_reference/compare.py ref.mbtiles cand.mbtiles` reuses `analyze_contours.py` and adds the following:

   **Exact-match checks (must pass):**
   * the same set of zooms that contain contours
   * the same `ele` interval per zoom
   * the same attribute keys and types
   * `index` is consistent with `ele` everywhere

   **Smoothness checks:** the ratio candidate/reference of median `vertex_spacing`, median `turn_angle` and `sharp_ratio` per zoom should be **within 0.8–1.25**.

   **Positional agreement (informational, since sources differ):** for each zoom, compute the share of candidate line length within *d* of a reference line with the same `ele` (shapely buffer). Use *d* = 2 px at that zoom. Report it; it is not a hard gate.

   **`is_ice` agreement:** the share of candidate ice length lying inside the reference ice lines' buffer should be > 0.8.

   **Visual output:** render side-by-side PNGs per sample tile (reference in red, candidate in blue, ice dashed) into `out/compare/`.
3. **Tuning loop.** Adjust `sigmaPx`, Chaikin iterations, `epsilon` and `--elevationExtent` (512 vs 256) per zoom until the smoothness ratios pass. Record each run's parameters and metrics in `docs/tuning-log.md`. Commit the winning values as the `RbtProfile` defaults.
4. **Comparison style** `styles/RBT-TOPO-contours/style.json`:
   * Copy the 4 `elevationContour--*` layers from RBT-Public `current-release/styles/RBT-TOPO/style.json` and change `"source": "RBT"` to `"source": "CONTOURS"` with `"url": "mbtiles://{contours}"`.
   * Add a toggleable copy that still reads from `RBT`, colored differently, to overlay both.
   * Add a `peaks` symbol layer (triangle icon + `name` + `ele`).
   * Add matching entries to a tileserver-gl `config.json` sample in `styles/README.md`.
   * Fonts `NGATopo_Cn_ita` / `NotoSansItalic` must exist in the tileserver fonts folder.
5. **Sign-off checklist** (senior reviewer):
   * Intervals and attributes match exactly
   * Smoothness within tolerance
   * No visible seams at tile borders at z9–14 (pan across several borders)
   * Ice/land color changes exactly at glacier outlines
   * No offshore artifacts
   * Peaks render

---

## Phase 8: Performance, documentation and packaging — ✅ done 2026-10-13

> Status:
> * HTTP concurrency is bounded by a Semaphore(16) in `HttpRangeSource`. Requests, MB downloaded and disk-cache hits are logged on close.
>   Remote planet test (Öræfajökull bbox, z9–13, 198 tiles): 136 range requests, 6.5 MB on the first run (one
>   transient connection abort, retried automatically); the second run read everything from `--pmtilesCacheDir` (145 hits, 0 requests).
> * Profiling with JFR led to: three-pass box blur with a row-major vertical pass; terrarium decoding straight
>   from the raster bytes; index-based RDP (same output as `LineSimplifier`, tested); array-indexed level
>   lists and a skip for cells without levels in `FastIsolines`. What remains is mostly TwelveMonkeys' pure-Java
>   WebP decoding and the overzoom resampling for z13.
> * Full Iceland z9–13 with glaciers and peaks: see the run in `out/contours/run.log` (`-Xmx8g`, 8 cores).
> * **Gzip (found 2026-10-14):** RBT stores gzip-compressed tiles, and QGIS only draws gzipped vector tiles (it
>   draws nothing for uncompressed ones; checked by rendering with PyQGIS 3.40). `--gzipTiles` defaults to true for
>   the rbt profile; upstream output stays uncompressed. Per tile, our contours are the same size as RBT's
>   (z12: 31.2 vs 32.4 MB raw; z13 on the same 191 tiles: 0.98 vs 0.99 MB). The reference file only looks
>   half as big because it holds just 192 z13 tiles, against 24,070 in a full Iceland run.
> * README section "Mapterhorn + OSM glaciers (RBT profile)" and `.github/workflows/ci.yml` (`mvn -B verify`; data tests skip themselves) are added.

### Tasks

1. **Concurrency.** `Processor` launches one coroutine per tile on `Dispatchers.IO`. Remote PMTiles needs a bound, so add a `Semaphore(16)` around HTTP reads. Also run a full Iceland job (z9–14) from the **remote** planet URL with `--pmtilesCacheDir` and record the time, requests and bytes downloaded via `PeriodicMetrics`.
2. **Memory.** Profile with the Iceland job using `-Xmx4g`. Check cache sizes (elevation tiles and glacier tile geometry).
3. **README.** Add a section "Mapterhorn + OSM glaciers (RBT profile)" with the exact commands, data downloads and attribution requirements.
4. **CI (optional).** Add a GitHub Actions workflow that runs `mvn verify` with unit tests only, plus the fixture-based PMTiles tests.
5. **Release.** Tag `rbt-v0.1.0`; the jar is `target/vectorvector-cli.jar`.

---

## Phase 9: Planet readiness (review 2026-10-14) — ⏳ not started

> The historical execution plan is in [planet-build-plan.md](planet-build-plan.md), with its proposed [implementation tickets](planet-implementation-tickets.md). These are design history, not the current supported interface.

**Verdict:** the per-tile output is ready, but the run machinery is not safe for a planet build as it is. A planet
run would take days on one machine, and today a single bad tile or a crash on day 5 loses everything.
Rough size, extrapolated from Iceland measurements (estimates, not measured on the planet):

| | Iceland (measured) | Planet z9–13 (estimate) |
|---|---|---|
| Land tiles | 32.6k, about 55 tiles/s on 8 cores | 35–45 M (Antarctica and Greenland are huge in Web Mercator), so 7–10 days on 8 cores |
| Sea tiles | about 170 tiles/s, produce nothing | 50–60 M, so about 4 more days unless skipped |
| Output | 92 MB | roughly 50–100 GB in one mbtiles (RBT: 169 GB for all layers) |

### Blockers (must fix before a planet run)

1. **No fault isolation.** `FaultBarrierTileSink` exists but is not wired in. An exception in any tile (for example
   a JTS topology error on an odd glacier, or an HTTP failure after retries) cancels the whole run.
   Fix: wrap the sink, count failed tiles, write their ids to a file, and fail at the end if any are left.
2. **No resume.** `MbtilesTileRepository` deletes the output on start (`truncate = true`). Fix: work in shards
   (below) with a done-marker per shard; a restart skips finished shards.
3. **Planet-wide glacier union at startup.** `Main` logs `GlacierIndex.areaKm2`, which unions *every* glacier
   (Antarctica, Greenland and about 200k others). That is cheap for Iceland but could take hours or run out of
   memory for the planet. Fix: drop it or make it optional.
4. **Peak DEM lookup is eager and single-threaded.** `PeakIndex` samples z12 elevation for every peak without `ele`
   before tile processing starts. For the planet (about 1 M peaks, many without `ele`) that is about 10⁵ random
   tile decodes on one thread, i.e. hours. Fix: resolve lazily per tile from the tile already in memory, or sort by
   tile and parallelise.

### Major (big cost or risk)

5. **Sea tiles cost real time.** Mapterhorn stores sea tiles, mostly identical copies (Iceland extract: 18,027
   addressed vs 9,158 distinct). Fixes: (a) cache decoded grids by PMTiles content offset, so each distinct tile is
   decoded once; (b) skip a tile early when its elevation tile is constant (flat sea) and no level is crossed,
   before the blur.
6. **SQLite writes.** Every insert is its own autocommit transaction with default sync. That's fine at 40 tiles/s,
   but a bottleneck with many cores. Fix: batch inserts in transactions (e.g. 1,000 tiles), and set `journal_mode`
   and `synchronous` for bulk loading.
7. **Glacier loader memory.** `NodeLocations.load` boxes every glacier node id into a `List<Long>` before sorting
   (planet: tens of millions of nodes, so GBs of boxed longs). Fix: fill a primitive `LongArray` directly. Always
   pre-filter the planet PBF with osmium (README) so the three passes read a few GB, not 80 GB three times.
8. **Remote PMTiles for a planet run.** Tens of millions of range requests against `download.mapterhorn.com` is not
   reasonable. Download `planet.pmtiles` (355 GB) plus the needed `6-x-y` files locally. Remote stays for small areas.

### Minor

9. Antimeridian: neighbours at x = −1 / 2^z are not wrapped, so lines are not buffered across 180° (Chukotka, Fiji).
   Cosmetic.
10. `Dispatchers.IO` caps parallelism at 64 threads; on bigger machines set `kotlinx.coroutines.io.parallelism` or use
    `Dispatchers.Default`.
11. High-zoom Mapterhorn archives (z13+ native data in about 450 `6-x-y` files) were not exercised for Iceland. The
    per-zoom blur is in source pixels, so native z13 data gets the same blur in metres; spot-check one area
    (e.g. the Alps) against RBT before the planet run.

### Sharding design (recommended)

* **Unit:** one z6 tile (4,096 shards; about 2,000 contain land). A shard is today's command with
  `-minZ 6 -maxZ 13 -minX x -maxX x -minY y -maxY y`; no new tile logic is needed. Tiles read neighbours from the
  shared inputs, so **shard edges have no seams**.
* **Runner:** a `--shards <file>` mode in one JVM that loads glaciers and peaks once, then works through a shard
  queue. Each shard writes `out/shards/6-x-y.mbtiles`, then a `.done` marker. Several machines can take disjoint
  shard lists. Shards with no land (z6 tile max elevation ≤ 0 m, checked conservatively at z8) are skipped.
* **Merge:** `tools/merge_shards.py` (or a Kotlin command): `ATTACH` each shard and `INSERT` into one mbtiles in one
  transaction per shard, then write the metadata and `VACUUM`.
* **Order of work:** 1–4 → 5–7 → sharding → a pilot on 5–10 shards (Alps, Himalaya, Greenland edge, Antarctic
  coast, a flat area, an all-sea shard) → planet.

## Suggested work split and order

| Dev | Phases | Depends on |
|---|---|---|
| A | 0 (data + reference scripts), then 7 (compare tooling) | none |
| B | 1, then 2 (PMTiles) | none |
| C | 3 (Mapterhorn store) | 2 |
| D | 5.1–5.2 (OSM + glacier index), then 6 (peaks) | 1 |
| B/C | 4 (profile + smoothing), 5.3 (splitting) | 3, 5.2 |
| All | 7 tuning, 8 | everything |

Rough effort: Phase 0 ≈ 2 days · Phase 1 ≈ 1 · Phase 2 ≈ 3 · Phase 3 ≈ 3 · Phase 4 ≈ 3 · Phase 5 ≈ 5 · Phase 6 ≈ 2 · Phase 7 ≈ 4 · Phase 8 ≈ 2.

## Open questions and risks

| # | Item | Mitigation |
|---|---|---|
| 1 | The RBT intervals and max zoom stored in the data may differ from what the style implies. | Phase 0 report is the source of truth; update §0.2. |
| 2 | Which high-resolution sources Mapterhorn has in Iceland, and whether z13+ files exist. | Phase 0 step 4. Overzoom fallback covers the gaps. |
| 3 | Glacier polygons differ between the current OSM extract and whatever date RBT used. | Expect small `is_ice` differences at edges. Don't gate on them. |
| 4 | No published RBT peak schema. | Use the §6 schema; adapt it if the old style turns up. |
| 5 | The protobuf version clash with the OSM PBF library. | Fallback: generate OSM proto classes ourselves (§5.1). |
| 6 | Mapterhorn may include bathymetry, which gives offshore contours. | `minElevation` filter (Phase 4.3). |
| 7 | Licenses: Mapterhorn source licenses and OSM ODbL. | Record them in `SOURCES.md` and the mbtiles `attribution`. |
