# RBT smoothing tuning log (Phase 7)

Setup: `tools/rbt_reference/tune.sh <label> <rbtSmoothing>` renders the tuning tile list
(`tools/rbt_reference/tuning-tiles.txt`: up to 150 sampled reference tiles per zoom for z9–12, plus all 191 z13
sample-window tiles) and runs `compare.py` against `data/iceland/rbt-reference-iceland.mbtiles`.
Elevation: Mapterhorn z0–12 Iceland extract, `--elevationExtent 512` unless noted. Glaciers: Geofabrik Iceland, 2026-10-12.

Settings are `z=sigmaPx/chaikinIterations/epsilon` (blur in elevation pixels, RDP epsilon in tile units).
The table cells are median per tile: `vertex_spacing / turn_angle (°) / sharp_ratio`; `pos` is the share of
candidate length within 2 px (16 units) of an RBT line with the same `ele`.
Pass band: candidate/reference ratio 0.8–1.25 for each metric (sharp_ratio compared with +0.005 on both
sides, since it is near zero at z12–13).

| Reference | z9 | z10 | z11 | z12 | z13 |
|---|---|---|---|---|---|
| RBT | 29.2 / 27.4 / 0.130 | 38.4 / 19.6 / 0.040 | 54.3 / 13.8 / 0.011 | 70.2 / 10.5 / 0.003 | 92.3 / 7.4 / 0.000 |

| Run | settings | z9 | z10 | z11 | z12 | z13 | pos z10/z12/z13 | failed checks |
|---|---|---|---|---|---|---|---|---|
| base | `*=1/2/2` | 29.2 / 25.7 / 0.101 | 34.8 / 21.8 / 0.062 | 42.0 / 18.9 / 0.035 | 47.9 / 16.2 / 0.018 | 62.2 / 11.5 / 0.002 | .954/.639/.319 | 11 |
| A | `9=0.6/2/2,10=1.5/2/3,11=2/2/3,12=3/2/4,13=5/2/4` | 27.9 / 28.3 / 0.143 | 47.5 / 24.5 / 0.083 | 58.5 / 19.6 / 0.040 | 83.0 / 18.6 / 0.033 | 104.4 / 13.5 / 0.005 | .966/.705/.351 | many |
| B | `9=0.8/1/2,10=1.5/3/3,11=2.5/3/4,12=4/3/5,13=7/3/6` | 29.1 / 26.9 / 0.121 | 47.4 / 24.4 / 0.082 | 74.0 / 21.1 / 0.053 | 99.9 / 19.2 / 0.038 | 136.4 / 15.2 / 0.008 | .966/.749/.381 | many |
| C | `9=0.5/2/3,10=2/2/4,11=3/2/5,12=5/2/6,13=8/2/8` | 34.4 / 34.5 / 0.232 | 59.6 / 26.1 / 0.103 | 86.7 / 22.2 / 0.065 | 117.6 / 19.3 / 0.039 | 164.1 / 16.9 / 0.015 | .970/.790/.400 | many |
| D | `9=0.8/1/2,10=2.5/2/3,11=4/2/3,12=7/2/3,13=12/2/3` | 29.1 / 26.9 / 0.121 | 52.4 / 21.4 / 0.050 | 68.6 / 16.1 / 0.017 | 85.7 / 12.5 / 0.004 | 105.4 / 9.1 / 0.000 | .968/.838/.460 | some |
| E | `9=0.8/1/2,10=3.5/2/2,11=6/2/2,12=10/2/3,13=18/2/3` | 29.1 / 26.9 / 0.121 | 44.8 / 15.9 / 0.015 | 59.8 / 11.7 / 0.003 | 94.6 / 10.9 / 0.002 | 116.9 / 7.9 / 0.000 | .941/.774/.471 | some |
| F | `9=0.8/1/2,10=3/2/2,11=4.5/2/2,12=8/2/2,13=14/2/2` | 29.1 / 26.9 / 0.121 | 44.0 / 16.7 / 0.018 | 55.7 / 12.7 / 0.006 | 71.7 / 9.7 / 0.001 | 87.8 / 7.1 / 0.000 | .960/.838/.483 | 3 (sharp z10–12) |
| G | `9=0.8/1/2,10=3/2/3,11=5/2/3,12=8/2/3,13=15/2/3` | 29.1 / 26.9 / 0.121 | 54.1 / 20.3 / 0.041 | 71.9 / 15.1 / 0.012 | 88.9 / 11.8 / 0.004 | 111.4 / 8.5 / 0.000 | .959/.837/.488 | 3 (spacing z10–12) |
| H | `9=0.8/1/2,10=2.5/2/2,11=4/2/2,12=7/2/2,13=14/2/2` | 29.1 / 26.9 / 0.121 | 41.8 / 17.6 / 0.023 | 54.8 / 13.1 / 0.007 | 69.2 / 10.1 / 0.001 | 87.8 / 7.1 / 0.000 | .969/.838/.483 | 3 (sharp z10–12) |
| I | as H with 1 Chaikin iteration | ≈ H | 41.7 / 17.6 / 0.024 | 54.8 / 13.1 / 0.007 | 68.8 / 10.1 / 0.001 | 88.0 / 7.1 / 0.000 | .969/.838/.483 | 3 |
| **J** | **`9=0.8/1/2,10=2.5/2/2.5,11=4/2/2.5,12=7/2/2.5,13=14/2/2.5`** | 29.1 / 26.9 / 0.121 | 47.6 / 19.6 / 0.035 | 62.4 / 14.7 / 0.011 | 77.6 / 11.3 / 0.003 | 99.1 / 7.9 / 0.000 | **.969/.838/.483** | **0 of 41** |
| K | `9=0.8/1/2,10=2.2/2/2.6,11=3.5/2/2.6,12=6/2/2.6,13=12/2/2.6` | 29.1 / 26.9 / 0.121 | 47.1 / 20.8 / 0.043 | 60.8 / 15.6 / 0.016 | 75.4 / 12.1 / 0.004 | 97.4 / 8.5 / 0.000 | .970/.820/.460 | 1 (sharp z11) |
| L | J with `10=2.5/2/2.2` | = J | 44.2 / 18.4 / 0.028 | = J | = J | = J | .969/.838/.483 | 1 (sharp z10) |
| M | J's blur in metres at `--elevationExtent 256` (`9=0.4/1/2,10=1.25/2/2.5,11=2/2/2.5,12=3.5/2/2.5,13=7/2/2.5`) | 30.0 / 26.5 / 0.118 | 48.4 / 19.5 / 0.042 | 61.8 / 14.6 / 0.015 | 77.5 / 11.3 / 0.004 | 98.6 / 7.9 / 0.000 | .941/.769/.442 | 2 |

## Findings

* Increasing the RDP epsilon makes vertex spacing longer but raises turn angles. RBT has both long segments and
  small turns, i.e. smooth curves at a large scale, so the main lever is the DEM blur (sigma), which grows
  roughly ×1.7–2 per zoom level, while epsilon stays around 2.5 units.
* Chaikin iterations barely matter once RDP runs after them (H vs I).
* z13 is overzoomed from z12 (no Mapterhorn z13+ data for Iceland), so its blur in source pixels is the same as
  z12's (14 px at 512 overzoomed = 7 px at z12).
* `--elevationExtent 256` is about 2× faster but positional agreement drops (z12 0.84 → 0.77), because a 256 px
  tile at z uses the coarser z−1 data. 512 stays the default.
* Positional agreement falls with zoom (0.98 at z9, 0.48 at z13) because RBT is derived from a much coarser DEM
  (its maximum is 2060 m vs Hvannadalshnúkur 2110 m). This is expected, and is not a gate.
* The original ice comparison reported 100% agreement at z9–12 and 83% at z13. The z13 figure
  was invalid: the analysis treated RBT's 16384-extent glacier polygons as 4096-extent geometry.
  The tool now normalizes each polygon layer's extent; see the corrected
  [global readiness comparison](planet-pilot-results.md). The original explanation attributing
  that discrepancy to newer OSM outlines is withdrawn. Contour-generation parameters are unchanged.

**Winner: J**, committed as `RbtProfile.DEFAULT_SETTINGS`.
