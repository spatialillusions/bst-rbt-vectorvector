# RBT reference contours: Iceland fact sheet (Phase 0)

Source: `E:\RBT\tileserver-gl\data\RBT.mbtiles` (RBT 2.0.0, production date 2026-09-07), read-only.
Subset: `data/iceland/rbt-reference-iceland.mbtiles`, built by `tools/rbt_reference/extract_reference.py`.
Full machine-generated numbers: [rbt-reference-analysis.md](rbt-reference-analysis.md) (and `.json`).

## Findings

| Topic | Finding | Effect on plan |
|---|---|---|
| Max zoom | Archive `maxzoom = 13`; `contours` layer is z9–z13. | We write contours for z9–z13 only. Clients overzoom z13. |
| Schema | Exactly `ele`, `index`, `is_ice`, all ints. No other keys. | As planned. |
| z9, z10 | All multiples of **200 m** (200…2000). | As planned. |
| z11 | All multiples of **100 m**. | As planned. |
| z12, z13 | Every multiple of **20 m and every multiple of 50 m** (union, e.g. 140, 150, 160). | Differs from plan: z12 is not 50 m only and z13 is not 20 m only. Both use the 20 ∪ 50 set; the style then picks per zoom. |
| `index` | Always the largest of {800,400,200,100,50,20} dividing `ele`. 0 mismatches. | As planned. |
| Negative / zero | No `ele = 0`. Six negative features (−20…−60) at z12 in one tile (x=1787, y=1056), i.e. a real inland depression. No offshore contours. | Allow negative contours, but no bathymetry: mask out sea (Phase 4.3). |
| Elevation max | 2000 at z9–11, 2060 at z12–13 (Hvannadalshnúkur is 2110 m), so RBT's DEM is coarse. | Our max will be higher; not an error. |
| Extent / buffer | Extent 4096. Coordinates clipped to −80…4176, i.e. a **buffer of 80 units**. | Clip our lines to the same 80-unit buffer. |
| Geometry | LineString and MultiLineString. Features are not strictly one-per-(ele, is_ice). Open lines dominate (tile-clipped). | Any grouping is fine. |
| `is_ice` | ≈ 5.6 % of contour length at z9–z12 over all of Iceland; 25 % in the z13 sample windows. | Sanity target for Phase 5. |
| Peaks | No peak/mountain layer in RBT. `physical_labels` is Natural Earth. | Use OpenMapTiles-like `peaks` (§6). |

## Smoothness targets (median per tile)

| z | vertex_spacing | turn_angle (°) | sharp_ratio (>45°) |
|---|---|---|---|
| 9 | 29.2 | 27.4 | 0.130 |
| 10 | 38.4 | 19.7 | 0.043 |
| 11 | 52.4 | 14.2 | 0.012 |
| 12 | 69.5 | 10.6 | 0.003 |
| 13 | 92.3 | 7.4 | 0.000 |

Vertex spacing *grows* with zoom and turn angles shrink: RBT lines are heavily generalised and smooth, and at z12–13 they look like they come from a low-resolution DEM (about 160 m between vertices at z12).

## Mapterhorn coverage of Iceland

* `planet.pmtiles` covers z0–z12. Sources in Iceland: **ÍslandsDEM v1.0** (Natural Science Institute of Iceland, CC BY 4.0) and **Copernicus GLO-30**.
* No z13+ archive covers Iceland. The only candidate `6-27-15.pmtiles` has data bounds 68.3–68.7 N (east Greenland coast). So **z13 contours must come from z12 elevation** (overzoom, or the native 512 px tile with an extent of 256 quadrants).
