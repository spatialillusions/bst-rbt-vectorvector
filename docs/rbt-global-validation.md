# RBT comparison outside Iceland — 16 October 2026

The current planet-build code produces similar contour shapes in four sampled mountain regions,
but this check also found substantial position differences on Greenland's ice sheet and a few
missing lowland contours. The Iceland match does not establish equivalent quality everywhere.
No contour generation parameters or production code were changed for this comparison.
The subsequent [17 October readiness pilots](planet-pilot-results.md) investigate those source
differences and add global OSM/ice inputs, boundary checks and native RBT-style rendering.

## Inputs and scope

- Reference: `H:\RBT\tileserver-gl\data\RBT.mbtiles`, 169,306,779,648 bytes;
  RBT 2.0.0, production date `2026-09-07T03:02:05.442000Z`. Opened read-only.
- Candidate DEM: `D:\Mapterhorn\mapterhorn.pmtiles`, native zooms 0–12. Output z13 uses ancestor overzoom.
- Candidate executable SHA-256: `580309f693a1d74f23bfdc11b26aa6df58e830ce70bb51fbf7877884e87a9abf`.
- Six fixed centers, each with a 3×3 window at every zoom 9–13: **270 requested tiles**.
  These are geographically different windows at each zoom, rather than a constant ground footprint.
- Reference layers are copied verbatim into small regional SQLite archives, without geometry re-encoding.
  All requested tiles were present in the reference archive.
- **No OSM input** for this run. Glacier classification and peak coverage outside Iceland are unvalidated.
  Candidate ice attributes are therefore zero; dashed RBT ice lines and solid candidate lines in the
  renders reflect that input difference. Position metrics ignore ice classification.

This is a bounded geographic sample, not a global census, seam test, or full-planet memory test.

## Position agreement

Each number is the percentage of candidate contour length within **16 vector units**, equivalent
to **two pixels in a 512-pixel tile**, of a reference contour with the **same elevation**.
Measurements clip both datasets to the tile interior. The ground-distance tolerance gets smaller
at higher zooms and latitudes. A low percentage can reflect an elevation/source difference on a
gentle slope; it does not by itself measure visual smoothness or establish which dataset is correct.

| Region (center lon, lat) | z9 | z10 | z11 | z12 | z13 |
|---|---:|---:|---:|---:|---:|
| Alps (7.7, 46.0) | 99.6% | 99.5% | 97.2% | 87.8% | 69.7% |
| Himalayas (86.9, 28.0) | 99.9% | 97.7% | 91.1% | 73.9% | 50.3% |
| Andes (−72.9, −49.3) | 96.8% | 94.8% | 96.0% | 93.1% | 71.6% |
| New Zealand (171.5, −43.5) | 99.4% | 98.4% | 95.5% | 90.7% | 74.7% |
| Greenland (−42.0, 65.5) | 83.6% | 54.1% | 41.1% | 21.4% | 18.2% |
| Netherlands (5.1, 52.1) | no contours | no contours | no contours | 6.5% | no candidate contours |

The machine-readable report also includes the reverse measurement, reference length near the
candidate, so omissions are not hidden by a one-way score: `out/rbt-global/report.json`.

## Geometry and coverage

All **34,050 candidate contour features** passed checks for integer `ele/index/is_ice` fields,
allowed elevation levels, the correct index divisor, binary ice flags, extent 4096, line geometry,
and coordinates within the −80…4176 buffer. No index mismatch was found.

Each of the five mountainous/ice-sheet regions has contours in all 45 requested tiles in both
datasets. In the Dutch sample, RBT has contours in three z12 tiles and one z13 tile; the candidate
has contours in one z12 tile and none at z13. All these Dutch contours are **20 m**. The remaining
Dutch tiles have no contours in either dataset. Candidate output contains **226 tiles**, versus
229 reference tiles containing contours.

At z13, the ratio of candidate to reference median contour length per tile is 0.95–1.00 in the
four mountain regions. Their mean-turn-angle median ratios are 0.97–1.03, while vertex-spacing
ratios are 1.16–1.23: similar smoothness and density, with fewer vertices. At z10, vertex spacing
is 1.31–1.39 times RBT and contour length is 0.86–0.95 times RBT. The Iceland tuning is therefore
not an exact smoothness match in every region and zoom.

The six center-tile renders were inspected. The mountain examples show similar major terrain
forms and smooth lines. Greenland's broad curves are visibly displaced even though overall
line density is similar. This remains unresolved. DEM source/version, vertical offsets,
resampling and smoothing are possible contributors; this run does not isolate their effects.
The absence of glacier masks explains the ice-style difference, not the position difference.
The Dutch 20 m omissions also remain unresolved and need focused DEM/threshold inspection.

`out/rbt-global/overview.png`. Full-resolution images and
regional reference extracts are under `out/rbt-global/<region>/`.

## Reproduce

Use an unused `--out` directory for extraction; the script deliberately refuses to overwrite an
existing reference subset. The commands below use the original, initially empty output path.
Python dependencies are `numpy`, `shapely`, `mapbox-vector-tile`, `matplotlib` and `Pillow`.

```powershell
python tools/rbt_reference/compare_global.py prepare
. .\env.ps1
java -Xms1g -Xmx16g -XX:+UseG1GC -jar target/vectorvector-cli.jar `
  --profile rbt --dataFormat mapterhorn --pmtiles D:\Mapterhorn\mapterhorn.pmtiles `
  --tileList out/rbt-global/tiles.txt -minZ 6 -maxZ 13 `
  -minX 0 -maxX 63 -minY 0 -maxY 63 --parallelism 8 `
  --elevationCacheTiles 512 --glacierCacheTiles 1024 `
  -f mbtiles -o out/rbt-global/candidate
python tools/rbt_reference/compare_global.py compare
```

On this machine, Python is available at
`C:\Users\DarkStar\AppData\Local\Programs\Python\Python314\python.exe`.
The comparison tool validates candidate attributes and geometry, records the executable hash,
and writes per-region/per-zoom statistics plus renders. Artifacts are local and excluded from git.
The short candidate build took roughly six seconds including JVM startup; use the dedicated
[performance study](performance.md) for throughput conclusions, not this small quality sample.

The [later readiness pilots](planet-pilot-results.md) investigate Greenland and the Dutch 20 m
cases, validate global OSM indexing and sustained memory, and add glacier/peak checks and native
style renders. They document source/surface differences without claiming global RBT equivalence.
