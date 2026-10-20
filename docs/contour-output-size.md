# Contour output size compared with RBT

Measured 17 October 2026 using the existing seeded, uniform global sample: 11,400 tile positions
at z9–13. **Our output is estimated to be smaller than RBT's contour-only output**, rather than
substantially larger. This is a sampled planet estimate, not a complete extraction of RBT.

| Estimated global storage | Our output | RBT contours |
|---|---:|---:|
| Contour payloads, both recompressed with gzip level 6 | 47.33 GiB | 54.80 GiB |
| Contour payloads where both sources have contours | 44.23 GiB | 54.68 GiB |
| Contour-only MBTiles, same 64 KiB page layout | 51.55 GiB | 61.14 GiB |
| Our current output including peaks and database overhead | 52.39 GiB | — |

Our contour payload estimate is **13.63% smaller overall**, and **19.11% smaller on shared
coverage**. The paired approximate 95% sampling interval for the overall payload difference is
6.46–8.48 GiB smaller. Database estimates additionally depend on empirical page packing, which
is outside that sampling interval. The prior [141 GiB working-space budget](planet-pilot-results.md)
still includes retained shards, the new output, scratch space and planning margin.

## Fair comparison and coverage

- Both sources use exactly the same sampled z/x/y positions and the z9–13 range.
- Only the encoded `contours` layer is extracted. Layer bytes, attributes and geometry are copied
  without decoding/re-encoding, then both sources use identical deterministic gzip settings.
- RBT's roads, buildings, landcover, glacier polygons and other basemap layers are excluded.
  Its full 169 GB basemap file is not a valid contour-only size comparison.
- Peaks are measured separately because our archive includes that optional layer. Their estimated
  incremental compressed cost is about 0.19 GiB; separate peak-only gzip streams total 0.24 GiB.
- Missing contour output counts as zero. There are 2,149 sampled positions with contours in both
  sources, 904 with only ours and 35 with only RBT's. Shared-coverage figures avoid attributing
  all differences to geometry density; these positions do not have equal sampling weight across
  zooms. Empty positions remain part of the full-world estimate.
- The 64 KiB archive estimates use separately measured table/index/page-fill overhead for each
  source. Four sample archives passed full SQLite integrity checks. Recompression round-trips
  were checked against every extracted layer's original bytes.

| Zoom | Our contour payload relative to RBT, including coverage differences |
|---|---:|
| 9 | 2.1% larger |
| 10 | 27.8% smaller |
| 11 | 19.7% smaller |
| 12 | 13.3% smaller |
| 13 | 13.3% smaller |

Size does not establish equal terrain quality. Our global elevation source ends at native z12,
with z13 overzooming; the documented Greenland and Dutch source/surface differences remain.
The sample also includes Antarctic contours where the reference lacks contour output. See the
[readiness quality checks](planet-pilot-results.md) for geometry, ice, peak and rendering evidence.

## Reductions that preserve appearance

The current bulk merge already uses **64 KiB pages**. In the earlier alternating page-size study,
this reduced the same sample archive from 797,433,856 to 696,909,824 bytes (12.6%) without changing
any tile payload. This storage improvement is already implemented; it is included in the estimates.

Additional compression trials on every nonempty sampled contour tile tested gzip levels 1–9
with default/filtered strategies, plus Huffman-only and run-length strategies. Every result
decompressed to identical encoded layer bytes. None provided a worthwhile reduction: the best
weighted result, level 8, saved only **0.0047%** versus level 6; level 9 was **0.076% larger**.
These are size trials, not reliable production CPU comparisons. Keep the existing gzip settings.

There were **zero exact duplicate stored payloads** among the 3,058 nonempty candidate sample
tiles. This does not prove that a planet has no duplicates, but provides no evidence for a large
deduplication saving. A normalized tile-map database would add complexity without a measured benefit.

A possible later experiment is grouping contour fragments with identical elevation/index/ice
attributes into multipart features. This can retain geometry while reducing repeated feature/tag
headers, but changes in feature grouping can affect label placement. Savings and native-style
renders should be measured before considering it for production. It is not implemented here.

**Recommendation:** keep the current contour intervals, smoothing, simplification and buffer for
the first planet run. We have not found a size reason to reduce visible detail. More aggressive
simplification, smaller buffers or dropping levels would need a separate visual/seam study.
The optional 10 m/z14 research extension is outside all estimates above.

## Reproduce

```powershell
python tools/compare_contour_sizes.py `
  --candidate out/planet-pilot-20261017/final/storage-sample/tiles/contours.mbtiles `
  --reference H:/RBT/tileserver-gl/data/RBT.mbtiles `
  --sample out/planet-pilot-20261017/final/storage-sample `
  --out out/contour-size-new
```

Use a fresh destination. Original inputs are opened read-only. Main results and all per-tile
sizes are retained under `out/contour-size-comparison-20261017-final/`; the additional compression
trial results are under `out/contour-size-comparison-20261017/lossless-compression.json`.
No production contour code or generation parameters were changed for this investigation.
