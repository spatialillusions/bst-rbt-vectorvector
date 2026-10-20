# vectorvector

Generate contour vector tiles, hillshade and Terrarium elevation tiles. This fork adds
[Mapterhorn](https://mapterhorn.com) PMTiles input, contours styled like
[Releasable Basemap Tiles (RBT)](https://github.com/ReleasableBasemapTiles/RBT-Public),
OSM glacier/peak support, and resumable builds for large datasets.

A [completed planet build](docs/planet-completion-20261019.md) passed full payload
verification: 23.46 million tiles at z9-13 in a 49.09 GiB archive.

The RBT profile reproduces the layer schema, contour intervals and a similar visual appearance.
It has been compared against RBT in Iceland and several other regions; it is not an exact copy
of RBT's geometry. See the [quality comparison](docs/rbt-global-validation.md) for known differences.
The [planet readiness pilots](docs/planet-pilot-results.md) record global OSM preparation,
memory/recovery measurements, larger-than-RAM merging and additional quality checks.
The [memory investigation](docs/memory-footprint-investigation.md) gives five code-based
recommendations and focused measurements for further memory and throughput improvements.

## Build

Use **JDK 21** and Maven:

```sh
mvn verify
java -jar target/vectorvector-cli.jar --help
```

The executable JAR includes its dependencies. If the optional project-local JDK/Maven tools
are already installed under `.tools/`, PowerShell users can run `. .\env.ps1` first; Git Bash
users can run `source ./env.sh`. Those helpers do not install a toolchain.

For local Windows builds, use `./build-jdk21.ps1 -MavenArgs verify`; it checks JDK 21 before
running Maven. Set `JDK21_HOME` or pass `-JavaHome <jdk-directory>` when no local SDK is present.
The [migration plan](docs/migration-plan.md) and [compatibility notes](docs/migration-compatibility.md)
describe the move from the older planet implementation and its changed options.
The [cutover checklist](docs/migration-cutover.md) records current validation and
the remaining steps before publication and switching the original checkout.

## Repository-only example

No RBT data is required to build, test, generate or verify contours. After building,
run this small example using the committed PMTiles fixture (Python 3, standard
library only; `java` must select JDK 21):

```sh
python tools/generate_example.py
```

It creates `out/example-contours/contours.mbtiles` with z9?10 contours near
16.675? W, 64? N. Use a fresh `--out <directory>` for another run; existing output
is preserved. Pass `--java <jdk21-java-executable>` to select a JDK explicitly.
To exercise the optional native WebP decoder, use
`python tools/generate_example.py --decoder native --out out/example-native`.
The fixture is deliberately tiny and does not represent regional coverage.
No OSM input is needed; glaciers and peaks are optional enrichments.

To verify the generated RBT-profile output independently of any reference archive:

```sh
python -m pip install -r tools/requirements-verify.txt
python tools/verify_contours.py out/example-contours/contours.mbtiles
```

The validator streams every tile and checks SQLite integrity, keys, MVT decoding,
contour fields, intervals and buffered geometry. It checks structure, not terrain
accuracy or seam quality. CI runs this same example and validator using only
committed inputs. The Java tests also use committed/synthetic fixtures; optional
regional integration tests skip when their local elevation/OSM inputs are absent.
CI checks both Linux and Windows, including raw output equality between the Java
and native decoders on the committed fixture. Regional speed measurements are
recorded in the [throughput results](docs/planet-speed-results.md).
See the [standalone style](styles/README.md) to display contours without RBT
basemap files, sprites or fonts. Real regional/planet builds need elevation data
as described below, but do not need an RBT reference archive.

## Inputs and outputs

| Input | CLI selection |
|---|---|
| Mapterhorn Terrarium WEBP tiles in local or HTTP(S) PMTiles archives | `--dataFormat mapterhorn --pmtiles <archive>` |
| ALOS AW3D30 GeoTIFF directory | `--dataFormat aw3d30 --data <directory>` |
| SRTM GeoTIFF directory | `--dataFormat srtm --data <directory>` |
| Optional OSM PBF glacier polygons and peak/volcano nodes | `--glaciers <file> --peaks` with the RBT profile |
| Optional supplemental ice polygons in WGS84 WKB/EWKB | `--glacierWkb <file>` with the RBT profile |

Output formats are `mbtiles` and `files`. The output argument **`-o` names a directory**, not an
MBTiles filename. The default profile retains the original `ele`/`level` attributes and
`vector.mbtiles` name. [GeoTIFF examples and historical benchmarks](docs/geotiff-workflow.md)
are documented separately.

## Create RBT-style contours from Mapterhorn

### Prepare regional data

Mapterhorn's global archive contains zooms 0–12. Higher-resolution regional archives supply
z13+ where available; see [data access](https://mapterhorn.com/data-access/).
Use [go-pmtiles](https://github.com/protomaps/go-pmtiles) to extract a region:

```sh
pmtiles extract https://download.mapterhorn.com/planet.pmtiles iceland.pmtiles --bbox=-24.8,63.1,-13.2,66.8
```

Obtain an OSM PBF for the same area, such as the
[Geofabrik Iceland extract](https://download.geofabrik.de/europe/iceland.html).
For a full planet, use local elevation archives and the preparation steps in
[planet preflight](docs/planet-preflight.md).

Antarctica also needs the [OSM ice-sheet polygons](https://osmdata.openstreetmap.de/data/icesheet.html):
its implicit ice cover is absent from a `natural=glacier` PBF filter. Prepare this optional input
once, then pass it alongside the OSM PBF:

```powershell
python -m pip install -r tools/requirements-ice.txt
python tools/prepare_ice_sheet.py --download --out antarctica-ice.wkb
```

Use `--zip <downloaded-archive>` instead of `--download` for an existing source archive.
Add `--glacierWkb antarctica-ice.wkb` to the Java command, or `-GlacierWkb antarctica-ice.wkb`
to the launcher. Conversion preserves rock holes and writes source hashes/provenance beside the
WKB. Keep that input immutable during a resumable run and retain OSM attribution and ODbL terms.

### Generate tiles

PowerShell example, with the downloaded inputs in the current directory:

```powershell
java -Xmx8g -jar target/vectorvector-cli.jar `
  --profile rbt --dataFormat mapterhorn --pmtiles iceland.pmtiles `
  --glaciers iceland-latest.osm.pbf --peaks `
  --bbox=-24.6,63.2,-13.3,66.7 -minZ 9 -maxZ 13 `
  --parallelism 8 --elevationCacheTiles 512 --glacierCacheTiles 1024 `
  -f mbtiles -o out/contours
```

This creates `out/contours/contours.mbtiles`. Omit `--glaciers` and `--peaks` for contours without
OSM inputs; their `is_ice` values will be zero and no peaks layer will be written.

| Layer | Geometry | Fields |
|---|---|---|
| `contours` | Lines | `ele` in metres, `index`, `is_ice` (0/1) |
| `peaks` (optional) | Points | `name`, `name_en`, `ele`, `ele_source`, `class`, `rank` |

The contour `index` is the largest of 800, 400, 200, 100, 50 or 20 dividing the elevation.
Zero-elevation contours are excluded; negative inland contours are allowed.

| Output zoom | Contour levels stored |
|---|---|
| 9–10 | Multiples of 200 m |
| 11 | Multiples of 100 m |
| 12–13 | Multiples of 20 m **or** 50 m |

Contours are smoothed, split at OSM glacier outlines and clipped to an 80-unit buffer at extent
4096. The RBT profile defaults to gzip-compressed vector tiles and disables hillshade. Its
glacier splitter repairs a tile-local mask once if a geometry operation fails, then retries
both ice and land segments. It logs the tile and repair; an unsuccessful retry stops the build
instead of silently dropping contours. Valid geometry follows the original path.
The profile's
512-pixel elevation grids match Mapterhorn's native tile size. The legacy `--epsilon` option
applies to the default profile; use `--rbtSmoothing` to adjust RBT smoothing.

### Useful options

- Repeat `--pmtiles` to combine the global archive with regional high-resolution archives.
  Sources with higher native maximum zoom are tried first. Missing tiles fall back to ancestors.
- HTTP(S) archives use range requests; `--pmtilesCacheDir <directory>` caches remote ranges.
  Resumable builds require local input archives.
- `--bbox=minLon,minLat,maxLon,maxLat` selects intersecting tiles. A named `--area` overrides
  explicit Z/X/Y bounds; the bounding box still filters that area.
- `--tileList <file>` limits generation to one `z/x/y` ID per line within the selected range.
- `--peaks` reads the glacier PBF by default, or accepts its own PBF path.
- `--elevationCacheMiB` (384 by default) bounds the estimated retained sample data across
  source, native and derived elevation caches. `--glacierCacheMiB` defaults to 128.
  Entry limits remain secondary bounds; zero MiB restores count-only caching. These are
  cache budgets, not limits on total JVM or Windows file-cache memory.
- Mapterhorn tiles are visited in 8-by-8 spatial blocks to reuse nearby elevation data.
  `--tileBlockSize 0` selects the original strip traversal.
- The [throughput validation](docs/planet-speed-results.md) describes conservative empty-area
  rejection, bounded smoothing/contour optimizations and an experimental native WebP decoder.
  Java decoding remains the default; native selection does not change the source data.
- `--rbtSmoothing '13=14/2/2.5'` overrides sigma in elevation pixels, Chaikin iterations and
  simplification epsilon in vector units. See the [tuning record](docs/tuning-log.md).

### Display the contours

Use the RBT-style filters on `index` and `is_ice`; the legacy `level` filters do not apply.
The [standalone style instructions](styles/README.md) show how to display generated contours
with no external RBT data or assets. Optional RBT comparison tools are documented separately
there. This CLI emits contours and optional peaks.

Mapterhorn and OSM attribution links are written into MBTiles metadata when those inputs are used.
Retain attribution to [Mapterhorn and its sources](https://mapterhorn.com/attribution/) and
[OpenStreetMap contributors](https://www.openstreetmap.org/copyright) when distributing or displaying output.

## Resumable planet builds

On the configured Windows machine:

```powershell
.\run-planet-sharded.ps1 -DryRun
.\run-planet-sharded.ps1
```

Defaults are eight tile workers, 384 MiB of elevation cache budgets (512-entry bounds),
128 MiB of glacier cache budgets (1,024-entry bounds), a
16 GiB G1 heap, and 64 × 64 partitions rooted at z6. Completed shards are reused after interruption;
final merging copies compressed blobs into a disposable database and builds its index once.

The launcher supports `-ElevationArchive`, `-AdditionalPmtiles`, `-OsmPbf`, `-OutputDir`, `-Bbox`
and `-TileList`, plus resource controls. Its default input locations are machine-specific;
customize them for another system. Run a pilot before the full planet:

```powershell
.\run-planet-sharded.ps1 -OsmPbf D:\OpenStreetMap\contour-features.osm.pbf `
  -Bbox '-24.6,63.2,-13.3,66.7' -OutputDir out/planet-pilot
```

That pilot still loads the **global** selected OSM feature set. Its output is restricted to
Iceland so startup/index memory can be measured without rendering the entire planet.
The filtered PBF must first be prepared; see [preflight](docs/planet-preflight.md).

For a planet build, prepare glacier masks and peak records once into z6 partitions.
The generator then loads at most two nearby partitions instead of building global OSM
indexes in every worker session. No RBT archive is needed. After building the JAR:

```powershell
java -Xmx6g -cp target/vectorvector-cli.jar `
  com.greensopinion.vectorvector.osm.PrepareSpatialInputs `
  prepared-spatial contour-features.osm.pbf antarctica-ice.wkb contour-features.osm.pbf

.\run-planet-sharded.ps1 -OsmPbf contour-features.osm.pbf `
  -GlacierWkb antarctica-ice.wkb -SpatialData prepared-spatial `
  -JavaHeap 6g -G1HeapRegionSize 8m -OutputDir out/planet-prepared
```

Use `-` for any absent preparation input, for example `prepared-spatial features.osm.pbf -
features.osm.pbf` when no supplemental ice WKB is available. The preparer records source
identities, checksums each partition, resumes interrupted preparation, and publishes its
completion manifest last. Keep the completed directory unchanged during generation. With
`--spatialData <directory>`, the CLI uses these masks/records; any supplied original OSM/WKB
paths must match their preparation provenance. Prepared inputs require the RBT profile and
a starting zoom of at least 6. Preparation still needs the global selected feature set once.

`-ReopenArchivesAfterShard` (CLI `--reopenArchivesAfterShard`) reopens local elevation
archives after workers and writers finish a shard. This provides a shorter handle lifetime
on Windows; it does not impose a system cache limit. Keep memory/disk guards and bounded
worker sessions for unattended runs. The [implementation plan](docs/memory-implementation-plan.md)
describes the validation and explicit compatibility-import procedure for an existing run.

**Resume with the same command, executable and inputs.** Do not rebuild on resume. CLI argument
order, input paths/stamps and executable bytes form the run fingerprint. JVM heap/GC settings
can change on retry. Keep space for completed shards, the new merged archive, index-sort files,
and any previous final archive; old GeoTIFF disk estimates do not apply.

Read the [full runbook](docs/planet-sharding.md) and [preflight](docs/planet-preflight.md) before
starting. Global index memory, sustained recovery and larger-than-RAM merging are covered in the
[readiness pilots](docs/planet-pilot-results.md); those bounded tests do not replace a full run.

## Validation and development tools

- [Sharding and recovery results](docs/planet-validation.md)
- [Hardware benchmarks](docs/performance.md) and [bulk merge measurements](docs/merge-performance.md)
- [Memory implementation and validation](docs/memory-implementation-results.md)
- [Glacier topology repair and Planetiler comparison](docs/glacier-topology-recovery.md)
- [Output size versus RBT contours](docs/contour-output-size.md): matched global samples and lossless compression trials.
- [RBT quality comparisons](docs/rbt-global-validation.md)
- [10 m / zoom-14 investigation](docs/ten-meter-contours-investigation.md): a tested research prototype;
  the optional extension is **not implemented in the production CLI**.
- [Tool setup and reproducibility](tools/README.md)
- [Check-in preparation](docs/check-in.md)

`mvn verify` runs unit and PMTiles fixture tests. Tests requiring uncommitted regional data skip
when those inputs are absent. Fixtures under `src/test/resources/` belong in the repository;
large input datasets, generated styles, outputs, local toolchains and caches do not.
Historical design proposals are preserved under [docs/history/](docs/history/README.md).

## License

BSD 3-Clause, copyright David Green. See [LICENSE](LICENSE). This fork builds on
[greensopinion/vectorvector](https://github.com/greensopinion/vectorvector).


## Code and dependency licenses

The upstream BSD 3-Clause [license](LICENSE) is retained. The executable includes
its runtime dependencies' license and notice texts; see
[third-party notices](THIRD-PARTY-NOTICES.md) and the
[packaging audit](docs/dependency-notices.md). CI verifies the shaded JAR's notice
inventory on Linux and Windows. External elevation/OSM data and optional Python
environments have their own attribution and license terms.
