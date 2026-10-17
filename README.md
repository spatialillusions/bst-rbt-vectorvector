# vectorvector

Generate contour vector tiles, hillshade and Terrarium elevation tiles. This fork adds
[Mapterhorn](https://mapterhorn.com) PMTiles input, contours styled like
[Releasable Basemap Tiles (RBT)](https://github.com/ReleasableBasemapTiles/RBT-Public),
OSM glacier/peak support, and resumable builds for large datasets.

The RBT profile reproduces the layer schema, contour intervals and a similar visual appearance.
It has been compared against RBT in Iceland and several other regions; it is not an exact copy
of RBT's geometry. See the [quality comparison](docs/rbt-global-validation.md) for known differences.
The [planet readiness pilots](docs/planet-pilot-results.md) record global OSM preparation,
memory/recovery measurements, larger-than-RAM merging and additional quality checks.

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

Defaults are eight tile workers, 512-entry elevation caches, a 1,024-entry glacier cache, a
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
