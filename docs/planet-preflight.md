# Before the first full planet run

The bounded worker, sharding, resume and merge paths are implemented and tested. The global
OSM feature PBF has now been prepared and reference-checked. See the
[17 October readiness pilots](planet-pilot-results.md) for measured status, executable/input
identities and corrections found during review. The full run remains a measured production pilot,
not already validated global output.

## 1. Freeze the build and inputs

Run `mvn verify`, save the executable hash, and record the input paths, byte sizes and timestamps.
Use local, immutable PMTiles and PBF files. Choose a fresh output directory and the exact command
to reuse on restart. Do not rebuild or replace inputs while a resumable build is in progress.
Keep the initial planet test at the implemented z9–13 profile; z14/10 m remains research tooling.

The launcher `-DryRun` prints the command; it does not prove data completeness, available space,
or full-OSM memory suitability. A bounding-box pilot and a full planet are different fingerprints
and must use different output/work directories.

## 2. Prepare OSM once

The complete planet PBF is supported, but normally needs four scans: once for peaks and three
for glacier relations, ways and nodes. Nested relation discovery adds passes when needed.
Preparing a smaller global feature PBF reduces repeated I/O without making the indexes regional.

The bundled alternative to Osmium Tool uses optional Python dependencies:

```powershell
python -m pip install -r tools/requirements-osm.txt
python tools/prepare_contour_osm.py D:/OpenStreetMap/planet-260921.osm.pbf D:/OpenStreetMap/contour-features.osm.pbf
```

Use a fresh output path. This tool checks every retained node/way/relation reference before
publishing, preserves tags and writes counts, timing and a SHA-256 sidecar. The local full-planet
preparation produced 138 MB in 33 minutes with zero missing references; it peaked at 2.63 GiB RSS.

With [Osmium Tool](https://docs.osmcode.org/osmium/latest/osmium-tags-filter.html) installed:

```sh
osmium tags-filter planet-260921.osm.pbf nwr/natural=glacier n/natural=peak,volcano -o contour-features.osm.pbf
osmium check-refs -r contour-features.osm.pbf
```

`tags-filter` includes referenced objects by default. Do not use `--omit-referenced`; glacier
member ways and all their nodes are needed. Keep peak tags and elevations. Use a fresh output
name instead of overwriting a source. Record filtering time, resulting size and reference-check
results. The bundled preparation has now been run successfully on the local full planet.
The [`check-refs -r` check](https://docs.osmcode.org/osmium/latest/osmium-check-refs.html)
includes relation references as well as nodes in ways.

## 3. Include Antarctic ice and pilot with global indexes

Antarctic ice south of 60 degrees is partly implicit in OSM and is not all tagged
`natural=glacier`. Prepare the supplemental polygon input for global coverage:

```powershell
python -m pip install -r tools/requirements-ice.txt
python tools/prepare_ice_sheet.py --download --out D:/OpenStreetMap/antarctica-ice.wkb
```

The converter accepts `--zip` for an existing official archive, preserves holes and writes
hashes/provenance. The CLI loader clips to Web Mercator limits. The
[OSM source documentation](https://osmdata.openstreetmap.de/data/icesheet-polygons.html)
describes the split polygons and ODbL terms. Retain this immutable input with the PBF.

Use the **global feature PBF**, even when restricting the output area:

```powershell
.\run-planet-sharded.ps1 -OsmPbf D:\OpenStreetMap\contour-features.osm.pbf `
  -GlacierWkb D:\OpenStreetMap\antarctica-ice.wkb `
  -Bbox '-24.6,63.2,-13.3,66.7' -OutputDir out/planet-osm-pilot
```

Alternatively `-TileList <file>` selects a small set of tiles, one `z/x/y` per line. The existing
`tools/rbt_reference/tuning-tiles.txt` is a bounded Iceland example. Tile-list entries must lie
within the selected zoom range and any bounding box. Neither filter restricts OSM index loading.
`-NoOsm` is useful for terrain diagnostics but cannot establish OSM memory readiness.

Record startup time, loaded glacier and peak counts, open ways/broken relations, heap and process
working set. The launcher logs heap metrics every 30 seconds, including during OSM loading.
Check available physical memory and paging as well as JVM heap; native decoders, geometry and
the filesystem cache also need memory. The measured global-index pilot peaked at 7.51 GiB RSS;
retain the current 16 GiB G1 limit for headroom. A smaller heap having worked on Iceland is
insufficient evidence for changing the global default.

Then run several consecutive representative shards with those global indexes: dense mountain
terrain, large glaciers, lowlands, coast/ocean, dateline islands and high latitudes. Verify that
memory settles as bounded caches cycle and that GC and throughput remain acceptable over time.
Do not infer a whole-planet ETA from a small, warm-cache, terrain-rich window.

## 4. Prove recovery and budget storage

Stop the pilot after at least one completed shard, rerun the **same command**, and confirm that
completed shards are reused and incomplete work is rebuilt. Save the log and shard timestamps.
Small-scale forced-interruption and merge-error tests already exist; repeating with the global
feature indexes checks the complete deployment path.

Budget each volume for its actual role:

- Source PMTiles, original PBF and filtered PBF on their input volumes.
- Completed shard databases plus the new merged archive on the output volume.
- Any previous final archive, which is retained until replacement.
- Index-sort temporary space (normally the JVM/SQLite temporary directory), and logs.

The merge itself no longer creates a rollback journal or WAL. It still copies all payloads and
requires space for the full destination and index. The
[readiness report](planet-pilot-results.md) extends the initial 760 MiB study to 40 GiB of sources,
larger than this machine's RAM, and records a stratified global storage estimate. Use those
capacity figures and actual free space rather than the mountainous Iceland sample alone.
The old GeoTIFF size estimates are not a Mapterhorn/RBT capacity plan.

## 5. Inspect output before publishing it

Check SQLite integrity, layer metadata/maxzoom, a sample of tile payloads, glacier classification,
peaks, adjacent tile joins and actual map-style rendering. Compare external areas with the RBT
archive read-only. The existing [global quality report](rbt-global-validation.md) records Greenland
position differences and a few Dutch 20 m omissions. These need investigation before claiming
global RBT equivalence; a full build alone does not resolve them.

After the pilot is satisfactory, start the full run in a **new** output directory using the same
frozen executable and inputs. Retain the pilot evidence. See [the runbook](planet-sharding.md)
for supported arguments, resume rules and current limitations.
