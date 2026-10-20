# Compatibility with the older planet implementation

Reviewed 17 October 2026 against preserved commit `dab55fa` on
`preservation/older-planet-20261017`. The complete older source and tests remain recoverable.

The migration imports the tested F: pipeline; it does not silently promise every option added
in the older, unpublished working copy.

| Older feature / files | Migration decision | Replacement or follow-up |
|---|---|---|
| `MbtilesTerrariumArchive`, `PmtilesTerrariumArchive`, `TerrariumElevationDataStore`, `TerrariumImageDecoder`, `TerrariumTileArchive` and their three archive/store tests | Preserve on older branch; defer general Terrarium input | New `pmtiles/` and `elevation/mapterhorn/` support Mapterhorn Terrarium WEBP with ancestor fallback, ranges and bounded caches. Generic Terrarium PNG and MBTiles input are not supported by the new Mapterhorn mode. |
| `PmtilesVectorArchive`, `GlacierContourClassifier` | Replace in migrated pipeline | Reference-complete OSM glacier PBF, nested relations, dateline-aware splitting and optional Antarctic WKB. Protomaps `landuse.kind=glacier` PMTiles masks are not accepted. |
| `ContourTopologyValidator` and its test | Preserve/defer diagnostic | Older diagnostic performs pairwise segment tests per line and is not part of the measured pipeline. A future bounded diagnostic should operate on final encoded geometry and have measured cost. |
| `BboxBoundsTest`, `ProcessorTest`, `ShardingTest`, `VectorTileSinkTest` | Replace with current regressions | Current CLI/range, bounded-worker, sharding/recovery, async writer, metadata and output-byte tests cover corresponding new behavior. Old option/filename expectations are incompatible. |
| `ContourIntervalsByZoomTest`, `ContourVectorTileMapperNthLineTest` | Preserve older schema tests; defer custom options | RBT has fixed z9-13 intervals and `ele/index/is_ice`; default GeoTIFF profile retains `ele/level`. Older `nth_line` and arbitrary intervals are not part of either compatibility contract. |
| `MarchingSquaresAmbiguousCaseTest` | Retain useful regression intent; corrected in `9866a3b` | Both generators now emit two saddle segments with shared bilinear pairing. Five focused regressions and renewed real-data quality/storage/render checks pass; see the correction report. |
| `build-jdk21.ps1`, `AGENTS.md` | Retain helper interface and guidance | Add a portable checked-in JDK 21 helper with explicit `-JavaHome`. No SDK binaries are committed. |
| `run-wholeworld-sharded.ps1` | Preserve older wrapper, replace active entry point | Use `run-planet-sharded.ps1`; no aliases that silently reinterpret incompatible arguments. |
| `.idea/codeStyles/Project.xml`, `.idea/codeStyles/codeStyleConfig.xml`, `.mvn/jvm.config` | Retain existing upstream configuration initially | Small existing tracked configuration, not copied local IDE state. |

Every older-only source/test file identified by the inventory is covered above. The initially
retained `FaultBarrierTileSink`, `LogTileSink` and `HillshadeOptions` were removed after the
merge audit confirmed that no application or test code references them. The supported
default-profile behavior remains in the active sinks and elevation stores.

## Commands and output changes

| Older usage | Migrated usage |
|---|---|
| `--dataFormat terrarium --terrariumSource pmtiles --data archive.pmtiles` | `--profile rbt --dataFormat mapterhorn --pmtiles archive.pmtiles` for compatible Mapterhorn WEBP |
| `--terrariumOverzoomFromParents` | Mapterhorn ancestor fallback is built in |
| `--terrariumCacheSize` | `--elevationCacheTiles` |
| `--glacierMaskPmtiles` | `--glaciers contour-features.osm.pbf --glacierWkb antarctica-ice.wkb` |
| `-o contours.mbtiles`, automatic format inference | `-f mbtiles -o <directory>`; RBT creates `<directory>/contours.mbtiles` |
| `--equidistanceByZoom`, `--z11MinorContourInterval` | Documented RBT intervals; optional z14/10 m remains research only |
| `--minContourLinePoints`, `--validateContourTopology`, `--failOnInvalidContours` | Not accepted; no silent approximation |
| `nth_line` / separate glacier contours | RBT filters use `index` and `is_ice` on `contours` |
| Older shard markers and build identity | New output directory/fingerprint; no cross-version reuse |

The new `--bbox` filters intersecting tiles at each zoom and can combine a tile list with the
chosen range. Read [the runbook](planet-sharding.md) and CLI help rather than copying old scripts.

## Release constraints

General archive compatibility, arbitrary intervals and topology logging are preserved development
options, not prerequisites for the requested Mapterhorn/RBT workflow. Saddle-cell correctness
is a prerequisite and has now passed focused and real-data validation. The migrated planet
executable is frozen separately from the earlier F: pilot. Old source remains on its preserved branch.
