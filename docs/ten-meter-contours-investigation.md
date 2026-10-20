# Optional 10 m contours at zoom 14

Investigation completed 16 October 2026. **Recommendation: add an opt-in 10 m contour level at
zoom 14, keeping the existing zooms 9–13 unchanged.** Start with the same ground smoothing as
z13, and expose weaker smoothing through the existing override mechanism. Enable the extra
level for selected regions first rather than adding it to the default planet build.

This work adds an executable research harness and repeatable measurements. **The production
profile, CLI, launcher and map style have not been changed to enable the feature.**

## What “more detail” means

The RBT profile currently writes multiples of **20 m or 50 m** at z12–13. The map style displays
the 20 m set at z13 and above. Internally, generation already works in 10 m increments and
filters out unwanted levels; the encoder already assigns `index=10` to the additional levels.
The new level would keep all nonzero multiples of 10 m, including negative inland contours.

At z14, a map covers half the width and height per tile compared with z13, so halving the
displayed contour interval is a reasonable visual progression. It adds intermediate elevation
lines; it does not, by itself, improve the DEM or establish 10 m vertical accuracy.

The local `D:\Mapterhorn\mapterhorn.pmtiles` has native data only through z12. Its 512-pixel tiles
have approximately 19.1 Web Mercator metres per sample at z12; ground spacing also depends on
latitude. Output at z14 bilinearly interpolates this data. Mapterhorn distributes finer regional
data separately at higher zooms, and its underlying source resolutions vary considerably.
See [Mapterhorn data access](https://mapterhorn.com/data-access/),
[the maintainer's archive explanation](https://oliverwipfli.ch/mapterhorn-update-2026-04-14/),
and [source coverage/resolutions](https://mapterhorn.com/).

Horizontal sampling, vertical accuracy, and contour interval are different properties. No
survey-control or independent height-accuracy validation was performed here. Existing differences
identified in the [global RBT study](rbt-global-validation.md) remain unresolved by this feature.

## Prototype and datasets

`tools/ContourDetailProbe.java` uses the actual production DEM reader, Gaussian blur, marching
squares, contour mapper, eight-worker processor and MBTiles writer. It injects experimental
options into a standalone harness, without rebuilding or modifying the executable.
Executable SHA-256:
`5997cf086a3c1863018b64b3f536b77fc9d51e32201f9015e4844f31d8f52cec`.

Eight planet-data regions cover Iceland/Esja, the Alps, Himalayas, Andes, New Zealand, Greenland,
the Netherlands and Sweden. Each has an 8×8 z13 window and all of its z14 children: **512 parent
tiles and 2,048 child tiles over exactly the same ground areas**. Coordinates are in
`out/contour-detail-study/manifest.json`.

For native high-zoom terrain, a bounded Alpine extract was downloaded from
`https://download.mapterhorn.com/6-33-22.pmtiles`, bbox `7.45,45.80,7.95,46.20`, zooms 13–14 only.
It contains 789 tiles and is **125,362,819 bytes**, rather than the roughly 615 GB whole regional
archive. SHA-256: `773efa3f7d3c6fbe9edb1eadd04b4b2dd16cd181cc0ea7c21ee8ac6196ad5e64`.
A native z14 center tile was confirmed present. Its 64 z13 parents/256 children were tested
with both the planet and regional archives supplied to the existing source router.

The existing local `6-27-15.pmtiles` was also probed, but the selected area produced no contours.
Those empty regional runs are retained in the artifacts and excluded from quality and throughput
conclusions. **No OSM glacier or peak input** was used in these runs; those integrations need
separate acceptance tests when implementing the option.

## Smoothing choice

Current z13 smoothing uses sigma 14 elevation pixels. At z14, **sigma 28** preserves the same
ground smoothing distance. All experiments retain two Chaikin iterations, epsilon 2.5 in vector
units, extent 4096, and buffer 80.

| z14 sigma | Ground blur relative to current z13 | Finding |
|---|---:|---|
| 28 | Same | Adds intermediate lines with a very consistent transition from z13. Recommended initial default. |
| 14 | Half | Reveals smaller shapes, particularly with native high-resolution data, but visibly moves existing contours. Useful advanced override. |
| 7 | Quarter | Much more small-scale texture and substantially more geometry. Not recommended as a general default. |

The 10 m/sigma-28 output preserves **every existing 20/50 m feature geometry and attribute**
from the z14/sigma-28 control across **2,304 tile locations** (2,048 planet + 256 native Alpine).
Only the additional elevation levels are added. In the illustrated parent footprints, practically
100% of the common-level length is also within two z14 display pixels of the existing z13 output.

Reducing sigma to 14 or 7 changes the underlying smoothed surface, not just line density.
For the central mountain/ice-sheet examples, sigma 14 retains only roughly 22–56% of common-level
length within that same two-pixel tolerance. This is a generalization change, not proof that the
new terrain interpretation is wrong. In the selected Dutch example the percentage is lower still.
Small texture in weaker-smoothed native terrain may be real relief or DEM artifacts; this study
does not distinguish them with ground truth.

Identical-ground-footprint renders, inspected after clipping child buffers to prevent duplicate
drawing at joins:

- `out/contour-detail-study/planet-iceland-comparison.png`
- `out/contour-detail-study/native-alps-alps-comparison.png`
- `out/contour-detail-study/planet-sweden-comparison.png`
- `out/contour-detail-study/planet-netherlands-comparison.png`

The existing-output panel uses the current 20 m display filter; dense panels show every 10 m
level, with intervening lines lighter. Panels cover one z13 parent, with four z14 children
mosaicked into the same coordinate system. Centers are used except for the Netherlands, whose
empty center is replaced deterministically by the most populated sigma-28 parent in that window.

## Build and storage cost

Two serialized runs per planet-data variant, fresh JVMs, JDK 21, 16 GiB G1 heap, eight workers,
512-entry DEM caches, D: input and F: output, on the previously benchmarked Core Ultra 7 machine.
Times include contour generation, compression and writer drain. They exclude JVM startup/source
initialization and a final shard merge. Inputs can be warm in the Windows filesystem cache.

| Variant over the same ground area | Requested tiles | Median time | Compressed tile payload |
|---|---:|---:|---:|
| Current z13, existing level set, sigma 14 | 512 | 4.30 s | 3.28 MiB |
| z14, existing level set, sigma 28 (control) | 2,048 | 7.40 s | 5.49 MiB |
| z14, 10 m, sigma 28 | 2,048 | 8.22 s | 8.96 MiB |
| z14, 10 m, sigma 14 | 2,048 | 8.36 s | 11.19 MiB |
| z14, 10 m, sigma 7 | 2,048 | 8.10 s | 13.37 MiB |

The fair comparison for the extra contour levels alone is **7.40 → 8.22 s (+11%)** and
**5.49 → 8.96 MiB (+63%)**, both at z14 and identical smoothing. Timing ranges are 7.25–7.55 s
for the control and 8.10–8.34 s for the 10 m variant. Smoothing alternatives are close in runtime;
two repeats do not establish a speed ranking among them.

Adding the entire z14 level is a larger decision. In these samples, the recommended z14 add-on
alone costs **1.91× the current z13 processing time** and **2.73× its compressed payload**.
Keeping both levels therefore costs approximately 2.91× the z13-only processing time and 3.73×
the z13-only payload. These ratios concern this z13 slice, not the complete existing build.
The largest compressed 10 m/sigma-28 tile was 22.35 KiB, versus 13.68 KiB for the z14 control.

Without land filtering, the global contour-level candidate count increases from **89,391,104**
tiles at z9–13 to **357,826,560** at z9–14: approximately **4× the tile visits**. Empty tiles are
not stored, but they still have to be considered. The eight windows are mostly terrain-rich
regions and cannot predict whole-planet runtime, output gigabytes, cold-disk behavior, or OSM
index memory. Regional native-data runs were single quality probes, not replicated speed tests.

## Verification

- All twenty run outputs passed SQLite integrity checks. The ten planet runs have identical
  ordered tile-key/payload digests within each repeated configuration, despite physical SQLite
  layout/size variation from parallel writes.
- 44,690 sampled contour features passed level, integer-attribute, index, ice-flag and geometry
  buffer checks. The additional 10 m levels use index 10, without adding schema fields.
- Existing-level retention was checked at all 2,304 z14 locations noted above.
- All 45 overlapping nonempty z13 tiles from the earlier global comparison are byte-identical
  to this harness's baseline, confirming that the baseline reproduces production output.

Raw `out/contour-detail-study/analysis.json`,
`out/contour-detail-study/runs.json`, commands, logs, input manifests and
regional-source details are under `out/contour-detail-study`. The full experiment made 19,584
tile visits, including the excluded empty regional probes. PNGs are illustrative feature renders,
not an end-to-end browser/style validation.

## Proposed implementation

Use an explicit flag such as **`--rbt10mContours`**, default off. The name is a proposal and is
**not currently a supported production CLI option**. Implementation should cover these together:

1. RBT profile: add z14 with all nonzero multiples of 10 m and sigma 28/Chaikin 2/epsilon 2.5;
   keep every z9–13 setting unchanged. Explicit `14=...` smoothing overrides should work.
2. CLI and launcher: validate the flag for the RBT profile, allow max zoom 14, make the launcher
   resolve its default max zoom to 14 when enabled, and reject incompatible explicit max zooms.
   Make smoothing wildcard behavior account for the enabled zoom range.
3. Metadata: advertise maxzoom 14 in MBTiles/TileJSON and the contours layer schema. Additional
   PMTiles inputs already support routing to native high-zoom data with ancestor fallback.
4. Map style: add a z14 branch accepting index 10 **and 50**, alongside the existing index values.
   Otherwise the new lines remain invisible. Keep minor lines thin and retain the current 100 m
   label selection initially. Change the generated candidate style, preserving the RBT overlay.
5. Peaks and glaciers: remove the RBT-specific peak cap of 13 when the option is active so peaks
   do not disappear from requested z14 tiles. Glacier clipping has no hard-coded z13 cap, but
   needs z14 integration tests with independent OSM inputs.
6. Verification: pin unchanged z9–13 payloads, test 10 m/index behavior, positive and negative
   levels with zero excluded, metadata/style visibility, adjacent-tile joins, native/fallback
   source boundaries, peak continuity, and sharded resume/merge at z14. Make RBT analysis tools
   aware that index 10 is intentional in this optional extension.

The source-code fingerprint will change when the feature is implemented; existing resumable
builds remain subject to the current compatibility rules. Start a regional pilot before a
whole-planet z14 run. This investigation does not ship a new default or alter completed shards.

## Reproduce

Python dependencies: numpy, shapely, mapbox-vector-tile and matplotlib. Run from the project root.
Use an unused output directory or preserve the original artifacts before re-running.

```powershell
. .\env.ps1
python tools/investigate_contour_detail.py prepare
python tools/investigate_contour_detail.py run --java "$env:JAVA_HOME\bin\java.exe"
.tools\bin\pmtiles.exe extract https://download.mapterhorn.com/6-33-22.pmtiles `
  out/contour-detail-study/alps-native-z13-14.pmtiles `
  --bbox=7.45,45.80,7.95,46.20 --minzoom=13 --maxzoom=14
python tools/investigate_contour_detail.py run-native-alps `
  --java "$env:JAVA_HOME\bin\java.exe" `
  --regional out/contour-detail-study/alps-native-z13-14.pmtiles
python tools/analyze_contour_detail.py
```

Use the explicit Python 3.14 path documented in the other local studies if `python` is not on PATH.
The Java harness requires an executable with the current public constructor signatures; it is
research tooling, not a stable replacement for the production CLI.
