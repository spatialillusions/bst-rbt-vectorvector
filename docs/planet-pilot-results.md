# Planet readiness pilots, 17 October 2026

The complete global glacier/peak input has been prepared and reference-validated. Eight regional
shards completed with those global indexes, including a forced interruption and successful resume.
The review found and corrected global glacier-clipping and Antarctic ice-coverage problems.
Measurements are retained under `out/planet-pilot-20261017/`. No full planet tile build has started.

## Build and inputs

- Final executable: `out/planet-pilot-20261017/final/pilot-cli-mapped.jar`, SHA-256
  `0a4d93ced07167a30c80ae8417b9ca1cd34fa5b6d0dc5ab14181a062f95bfc5d`.
- The sustained/recovery run used the earlier clip-first executable, SHA-256
  `96837ade25afd3cf407800e492d4a098d8267e69a7f509a99b720a41f3db4d06`.
  Later changes add the supplemental ice input, recover one malformed glacier relation and tune
  merging. Final global quality, storage and large merging use the final executable above.
- JDK 21.0.12.1; `mvn verify` passed: 149 tests reported, 146 passed, three existing skips.
  Six optional Python regressions also passed: four preparation and two reference-extent tests.
- Planet elevation: `D:/Mapterhorn/mapterhorn.pmtiles`, 663,868,447,387 bytes, native z0–12.
  This pilot uses ancestor overzooming at z13, not native z13 terrain.
- Original OSM: `D:/OpenStreetMap/planet-260921.osm.pbf`, 94,983,340,321 bytes.
- Reference: `H:/RBT/tileserver-gl/data/RBT.mbtiles`, RBT 2.0.0, production date 2026-09-07.
- Machine: Core Ultra 7 258V, eight logical processors, 31.49 GiB RAM; D/F are external USB SSDs.
  Other applications remain running. No user transfer or system setting was changed.

The initial union-first contour pilot was stopped after diagnosing repeated whole-ice-sheet
unions. Its partial data, telemetry and thread samples remain in the parent directory. The
optimized pilot uses a **new** work directory and fingerprint; no completed state is mixed
between executables. The final executable and inputs remain unchanged during stop/resume.

## Global OSM preparation

`tools/prepare_contour_osm.py` selected all glacier features and peak/volcano nodes, collected
their referenced objects, preserved tags, validated references, flushed and published a new file.

| Measurement | Result |
|---|---:|
| Filtered file | 138,128,325 bytes (131.73 MiB) |
| Preparation and validation | 1,982.74 seconds / 33.05 minutes |
| Peak process working set | 2,693.01 MiB |
| Peak private memory | 2,911.27 MiB |
| Minimum available physical RAM | 7,514.33 MiB |
| Retained nodes / ways / relations | 16,529,032 / 181,025 / 11,977 |
| Peak / volcano nodes | 1,107,939 / 10,263 |
| Glacier ways / relations | 104,295 / 11,972 |
| Missing node / way / relation references | 0 / 0 / 0 |

The filtered SHA-256 is `ac0388a22a20419e2c00eb9f4d75b4dbb8da41605fb54dea2fd95121c97ca49a`.
Original size/mtime were checked before and after preparation. Preparation read approximately
four source-file lengths; it was not an isolated speed comparison, since small diagnostics and
build verification also ran during this one-time step. Filtered input greatly reduces repeated
startup I/O while leaving the feature indexes global.

Two preparation regressions passed: preserving nested references and peak tags, and rejecting
missing references. Existing or incomplete outputs are refused. Reference completeness does
not imply that all source geometry is valid.

## Corrections found by readiness review

- Mapterhorn elevation kernels wrap x across the dateline while invalid polar y remains empty.
- Peak and glacier buffers query the opposite side of the dateline. Peak DEM lookup keeps the
  original longitude; shifted glacier copies leave source geometry unchanged.
- Sparse tile lists prune unselected partition roots, retaining one metadata-only partition for
  a completely empty selection.
- Seven global glacier relations reference nine nested members. The loader now discovers only
  referenced relations, combines nested outer/inner geometries and preserves holes within holes.
  Missing members and cycles reject the affected relation instead of silently filling its hole.
  A depth limit of 64 prevents excessive recursion. Untagged descendants are not emitted as
  independent glaciers. Tiny synthetic PBF fixtures cover these cases without external data.
- Base glacier queries clip candidates locally before unioning them, rather than unioning entire
  overlapping ice sheets first. Thread samples from the initial Greenland diagnostic showed
  workers repeatedly blocked on that shared operation as the bounded cache cycled. Regression
  checks compare local masks with union-then-clip, including holes, overlaps and cache eviction.
- Duplicate relation members are deduplicated within each role group before ring assembly.
  This recovers Dragon Peak Glacier; seven remaining rejected relations have only inner members.
- Antarctic implicit ice is supplied separately through `--glacierWkb`, not inferred from the
  absence of `natural=glacier` tags. The loader validates WGS84 polygon WKB/EWKB, preserves holes,
  clips polar geometry safely and includes this file in the resume fingerprint. CLI/launcher and
  synthetic tests cover the new input without requiring Python at contour-generation time.

### Antarctic supplemental data

[OSM documents implicit ice south of 60 degrees](https://osmdata.openstreetmap.de/data/icesheet.html).
RBT also imports these ice-sheet polygons; a glacier-tag-only PBF is insufficient. The official
[split polygon dataset](https://osmdata.openstreetmap.de/data/icesheet-polygons.html) was converted
without a global union, preserving rock holes and clipping to Web Mercator latitude limits.

The prepared file has 14,799 polygons, 4,172,059 coordinates and 67,132,640 bytes. Conversion took
10.28 seconds. Its SHA-256 is
`1968b25aff8952b66b703026d02e999a48c28a9f68cd6d691fd46185a9af79c9`.
The source ZIP SHA-256 is `c77b05a0a8f68a86912e14e1191d094c9792fd387978deceb2aeb2058d83639d`;
the server reported last modification 2026-10-17 04:55:45 GMT. Provenance is saved beside the WKB.
This input is newer than the September RBT reference; polygon agreement is a comparison, not
proof that either snapshot has the definitive ice boundary. OSM attribution and ODbL apply.

## Sustained processing and recovery

The global loader retained **1,118,202 peaks** (335,171 without usable OSM elevation) and
**116,201 glaciers**, using 15,410,438 referenced glacier nodes. It reported 58 unclosed glacier
ways and eight rejected relations. Inspection found seven relations with only inner members,
and one with a repeated outer member; IDs/tags/members are saved in `skipped-osm-features.json`
in the parent artifact directory. These are documented omissions, not missing PBF references.

### Glacier clipping bottleneck

The initial diagnostic stopped after 223 seconds and about 8,000 requested tiles, before a
completed shard. Thread samples showed repeated whole-ice-sheet union work blocking the other
tile workers. This run is not counted as the intentional recovery test.

The focused probe loads the same global glacier set, then times each query with a fresh index
and empty clipping cache. Input loading, WKB export and verification are outside query timing.

| Root | Repeat | Union before clipping | Clip before union |
|---|---:|---:|---:|
| z6/24/16 | 0 | 26.964 s | 0.142 s |
| z6/23/15 | 0 | 18.676 s | 0.078 s |
| z6/24/16 | 1 | 19.030 s | 0.144 s |
| z6/23/15 | 1 | 23.304 s | 0.068 s |

All four mask comparisons are topologically equal, with **zero symmetric-difference area and
zero Hausdorff distance**. One output has an extra redundant vertex; its boundary is unchanged.
This measures root clipping, not an equivalent speedup of the complete contour build. Both
executables and raw logs/masks are retained; source loading and other applications can affect
whole-job timings. The old executable hash is `fed59277cd6ad7d62a7303f4129b4bdbc9c60a90dd4685cca0f0d51c75af6919`.

Workload: eight complete z6 roots, 174,592 requested z9–13 tiles,
global OSM indexes, eight workers, 512 elevation-cache entries, 1,024 glacier-cache entries,
16 GiB G1 maximum heap. Regions include Greenland, Iceland, Sweden, the Netherlands, Alps,
Himalayas, New Zealand and Andes. The test deliberately stops after two completed shards and
reruns the identical application command, checking their file sizes and timestamps.

| Measurement | Interrupted process | Identical restart |
|---|---:|---:|
| Elapsed, including startup | 115.62 s | 824.84 s |
| Peak working set | 7,326.5 MiB | 7,688.0 MiB (7.51 GiB) |
| Peak private memory | 7,400.3 MiB | 7,737.3 MiB |
| Minimum available system RAM | 6,122.4 MiB | 4,675.6 MiB (4.57 GiB) |
| Completed shards reused | — | 2, unchanged size/mtime |

The interrupted shard was rebuilt and all eight shards completed. Neither process reached the
available-memory guard. These are global indexes with regionally selected output, not regional
OSM indexes. The 16 GiB G1 limit remains appropriate on this machine; a faster regional 8 GiB
Parallel-GC result alone does not justify changing the global default. Other applications remain
part of the memory budget. See `resume-verified.json`, both monitored results and raw GC logs.

Full SQLite integrity checks on all eight shard databases and the merged archive passed.
A bounded streaming multiway comparison verified **all 122,667 output keys and compressed tile
payloads byte for byte**, with no missing, duplicate or different tiles. Ordered key/length/payload
SHA-256: `ad341fda4249b4cbbd8e46c58dec5e30dd21e75d7124b9106f7de5fc7f89f0dd`.
The eight shards occupy 737,423,360 bytes and their merged archive 737,329,152 bytes under the
older 4 KiB layout used by this sustained executable.

The resumed process spent **14.84 seconds in 1,061 GC pauses (1.80% of wall time)**, with no full
GC. Pause p95 was 23.57 ms and the maximum 97.79 ms. Its final post-GC heap was 3,881 MiB.
Mean process CPU utilization was 58.9% of eight logical processors including startup and merge;
tile throughput varies greatly with terrain. Completed full-root processing ranged from nine
seconds for the mostly empty Iceland partition to 211 seconds for the Himalayan partition.
These are requested visits, not equal units of geometry work. The
`out/planet-pilot-20261017/clip-first/sustained-memory-cpu.png`, per-shard
log timings and GC summary remain under `clip-first/`. This bounded run demonstrates cache
cycling and recovery; it cannot prove multi-day behavior or a worldwide completion time.

## Merge larger than RAM

### Page size and read latency

The actual production JDBC merge was measured six times, in page-size order
4/16/64/64/16/4 KiB, using 32 synthetic shards containing real Iceland contour payloads.
Every run copied 294,688 tiles, included the final file flush and passed full integrity plus
every-key/payload verification outside the timed interval.

| SQLite page size | Median merge | Final bytes | Median 5,000 random reads |
|---|---:|---:|---:|
| 4 KiB | 23.843 s | 797,433,856 | 0.612 s |
| 16 KiB | 6.201 s | 775,045,120 | 0.544 s |
| 64 KiB | 2.812 s | 696,909,824 | 0.604 s |

Random reads use a 2 MiB SQLite cache, not a cleared OS cache. These are aggregate query timings,
not network rendering latency. All payload digests match. The smaller 64 KiB archive reflects
less overflow-page overhead on these payloads; it is not a universal compression ratio.

The final implementation uses 64 KiB pages, a direct source table scan and at most 256 MiB of
read-only mapping for the currently attached shard. The destination cache is 16 MiB and index
sorting uses disk. It does not decode/re-encode tiles or create a merge rollback journal/WAL.
The disposable file is closed and flushed before atomic publication; failures preserve the old
archive and completed shards. Generation still uses normal shard transactions.

### Beyond physical RAM

The larger workload contains **1,720 disjoint shards, 43,030,265,856 input bytes (40.07 GiB),
and 15,839,480 tiles**. Both its sources and approximately 35 GiB output exceed this machine's
31.49 GiB RAM. Payloads are repeated real Iceland tiles with disjoint synthetic keys. This
exercises cold storage, copying and sorting; it is not a geographic planet or an ETA model.

The earlier 64 KiB implementation took **605.525 seconds** including copy, index and flush.
The final implementation took **448.958 seconds**, a **25.9% reduction** in this one large-file
comparison. Its output is 37,460,836,352 bytes; the earlier ordered-source output was
37,562,875,904 bytes. Physical row order affects packing, so the full verification checks expected
keys and payloads rather than requiring identical physical-order digests. Small-page study
results above use alternating repeats; this larger comparison has one timed run per executable.

Large-file verification is reported separately. During early attempts, Windows' system file
cache consumed memory during random integrity scans even with a small JVM. Failed/guard-stopped
attempts are retained and are not counted as passes. Verification now uses a bounded 512 MiB
SQLite integrity cache and the child process's very-low memory priority, as recommended in
[Microsoft's cache guidance](https://learn.microsoft.com/en-us/windows-server/administration/performance-tuning/subsystem/cache-memory-management/troubleshoot).
Only verification changes memory priority; timed merging uses normal priority. No system cache
settings or other processes were changed. Streaming and random-query caches are smaller still.

**Both large outputs passed** full `PRAGMA integrity_check`, expected tile count, uniqueness,
every key/payload against the real seed mapping and 5,000 random tile reads. The final check
covered all 15,839,480 tiles; its random reads took 1.734 seconds with a 2 MiB SQLite cache.
The final process used at most 698.3 MiB RSS across merging and verification, with at least
1,389.8 MiB available system RAM. Its monitored wall time was 617.56 seconds; this includes
verification and JVM startup and must not be confused with the 448.96-second merge interval.
The guard remained enabled. Verified disposable outputs were removed; original synthetic
sources, commands, telemetry and result logs remain under `clip-first/large-merge-sources/`,
`final/baseline-merge-verification-bounded-cache/` and `final/large-merge/`.

## Regional quality

The final global-OSM candidate uses the fixed quality list of 1,060 requested tiles
across the eight sustained regions, Antarctica, dateline windows and latitude limits. Checks
cover schema, ice masks, peak attributes/ranking and adjacent-tile crossings. Real TileServer GL
renders use RBT-TOPO, local sprites/fonts and the original RBT/TERRAIN archives read-only.

The final loader retained 1,118,202 peaks and 131,001 glacier pieces: 116,202 from the PBF plus
14,799 supplemental Antarctic polygons. It reported 58 unclosed ways and seven rejected
inner-only relations. The final generation process completed in 62.93 seconds, peaked at
4,681.4 MiB RSS and left at least 12,766.5 MiB available system RAM. This sparse quality window
is not the sustained workload or a full-planet speed estimate.

- SQLite integrity passed. **230,592 contour features** have zero schema violations.
- **8,243 buffered peak occurrences** have zero attribute/rank/coordinate violations:
  6,540 use OSM elevations, 1,703 use lazy DEM elevations; 8,226 are peaks and 17 volcanoes.
  These are tile occurrences, including buffer duplicates, not unique worldwide peak counts.
- Antarctic peninsula candidate ice lines fall inside reference ice polygons for **100%**
  of tested line length. Land-classified length lies outside for **100%**. At the southern
  Mercator limit, both fractions are 100%, and 65 matched boundary crossings stay within
  2.31 extent units. The prior glacier-PBF-only output omitted most of this implicit ice.
- Antarctica-dateline and northern-limit windows have no contour crossings. Their empty output
  exercises safe handling but does not establish contour-seam quality where terrain is absent.
  Fiji and Chukotka windows include populated contour boundaries; dateline unit tests additionally
  exercise wrapped elevation samples and glacier/peak buffers.

The comparison now scales each glacier polygon layer to contour coordinates before computing
length fractions. **RBT uses extent 16,384 for z13 glacier polygons and 4,096 for contours.**
The initial analysis ignored that difference and produced misleading ice disagreements, including
the historical Iceland z13 score. Two new regressions cover mixed extents and invalid extents.
Initial reports are preserved under `final/incorrect-reference-extent-analysis/` and marked invalid
for ice metrics; the final reports use normalized extents. This correction changes analysis only.

Corrected ice-inside fractions are 100% in Iceland, Alps, Greenland, Andes, New Zealand, Sweden
and Antarctica; Himalayas are 99.9857%. Land-outside fractions are at least 99.9895% in sampled
regions with contour output. Fractions use an eight-unit (one rendered pixel) polygon tolerance.
Snapshots and geometry differ, so this is a sampled agreement test, not independent ground truth.
The reference has ice polygons but no contour lines in the selected Antarctic peninsula tiles;
those windows establish mask agreement and candidate continuity, not RBT contour-position agreement.
The follow-up also checks RBT's own classified contours against its polygons per zoom. For
example, its Alps z13 ice-inside fraction is 99.9963%, versus the candidate's 100%; Himalayas z13
are 100% for RBT and 99.9684% for the candidate. Small polygon/line discrepancies therefore
remain measurable after fixing the coordinate-system error. See `final/edge-review.json`.

The first edge test covers 1,575 adjacent tile pairs, including 60 dateline pairs, and matches
32,859 classified contour crossings. It flags 161 unmatched crossings and 268 matches displaced
by more than one pixel along the edge. This is a diagnostic, not a clean zero-mismatch result:
nearly tangent curves can move their edge intersection far while remaining close in normal
distance, and greedy matching can pair crossings incorrectly after a count difference. The
follow-up compares actual contour geometry in overlap strips against the neighbor's buffered
context, both with and without ice classification; its results are recorded below.

The geometric review covers all **190 flagged edges** and 751,075 extent units of strip line
length, counting both sides. **Zero elevation-matched contour length lies more than one pixel
from the neighbor's buffered geometry.** With `is_ice` also required to match, 71.56 extent units
(about nine pixels of line length across all reviewed strips) remain outside that tolerance,
near glacier splits. This supports continuous sampled geometry while retaining a documented
small classification discrepancy; it is not a claim of exact crossing or worldwide equality.

The final production tiles also match the earlier clip-first quality tiles byte for byte in
all 840 selected non-Antarctic positions. Changes are confined to 80 Antarctic peninsula tiles
and 15 southern-limit tiles; empty Antarctic-dateline output remains unchanged. These checks
separate the new Antarctic mask from unrelated contour-generation changes.

Native TileServer GL rendered all ten candidate/reference images using RBT-TOPO, local fonts
and sprites, then shut down its own server. Candidate contours and peaks replace those layers;
other basemap layers and hillshade use the original reference archives. The five side-by-side
images were visually inspected: glacier lines render blue, peak labels work and mountain
contours remain smooth. The Greenland displacement remains visible; the Dutch view is largely
flat and does not independently validate the missing 20 m lines.

Local render evidence (candidate left, reference right):
`out/planet-pilot-20261017/final/rendered/iceland-comparison.png`,
`out/planet-pilot-20261017/final/rendered/alps-comparison.png`,
`out/planet-pilot-20261017/final/rendered/matterhorn-comparison.png`,
`out/planet-pilot-20261017/final/rendered/greenland-comparison.png`,
`out/planet-pilot-20261017/final/rendered/netherlands-comparison.png`.
These generated artifacts remain ignored; their commands/results are recorded locally.

The focused surface probe already diagnoses the earlier Greenland and Dutch differences:

- At Greenland reference vertices, median smoothed Mapterhorn height minus reference contour
  height is +0.42 m at z12 and +0.27 m at z13. Candidate vertices lie on the smoothed surface
  within roughly 0.01 m. Gentle reference slopes (median about 0.8–0.9 degrees) amplify small
  vertical differences; the local linear estimate of normal displacement has a median about
  37–40 m. This is a terrain-gradient diagnostic, not surveyed positional accuracy.
- In the three Dutch tiles missing a reference 20 m line, raw Mapterhorn maxima are 13.14,
  15.81 and 12.21 m. Smoothing alone cannot explain their absence: the raw input does not reach
  20 m. Reference z13 vertices in the affected tile sample current raw terrain near 2.3 m.

These findings identify source/surface differences, not a proven datum, source-version or
DTM-versus-DSM cause. Do not fabricate 20 m lines or globally shift contours to fit this reference.
Raw evidence is in `surface-input.csv` and `surface.csv`; `ContourSurfaceProbe.java` samples raw
and production-smoothed terrain at the same MVT coordinates.

## Global storage estimate

A later [matched RBT contour-size comparison](contour-output-size.md) estimates 47.33 GiB of our
contour payloads versus 54.80 GiB for RBT. Our current output, including peaks and database
overhead, is estimated smaller than a similarly packed RBT contour-only archive.

The final executable generated a **seeded, uniform sample of 11,400 tile IDs** across the full
Web Mercator world, stratified by zoom: 200/400/800/2,000/8,000 at z9/10/11/12/13. Missing output
rows count as zero bytes. This avoids extrapolating a mountainous Iceland shard to ocean and
lowlands. The population contains 89,391,104 tile positions; 3,058 sampled positions had output.

| Capacity item | Estimate |
|---|---:|
| Compressed payloads | 47.51 GiB |
| Final 64 KiB-page archive | 52.39 GiB |
| Approximate 95% sampling interval for archive | 48.90–55.88 GiB |
| Retained 4 KiB-page shards, including 4,096 database headers | 54.72 GiB |
| Archive planning budget: upper sampling bound plus 20% | 67.06 GiB |
| Shards plus new archive, upper combined bound plus 20% | 136.95 GiB |
| Additional index-sort scratch reserve | 4.00 GiB |

Reserve **about 141 GiB across output and scratch volumes**, plus any previous final archive,
separately stored inputs and logs. At the end of the sample, F: still had at least 1,103 GiB free
and C: 153.5 GiB, even while retaining the 40 GiB synthetic sources. There is ample capacity on
this machine for this estimate; check free space again immediately before production.

Archive and shard overhead are measured separately from the actual sample databases, not
assumed equal. The approximate intervals cover sampling variability only: future source changes,
native high-resolution archives, different page fill and 10 m/z14 output are outside them.
The 20% allowance is a planning margin, not a guaranteed maximum. Keep monitoring free space.
Raw seed/list hash, per-zoom estimates, page sizes and formulas are in
`final/storage-sample/estimate.json`; both sample databases passed full SQLite integrity.

Generation with the final global indexes and supplemental ice took **153.29 seconds**, peaking
at **8,956.6 MiB RSS (8.75 GiB)** and 9,029.6 MiB private memory. At least 7,524.0 MiB system RAM
remained available. This wider sparse workload uses more resident memory than the sustained
regional run, supports retaining the 16 GiB G1 default, and is not an extrapolated planet ETA.

## Reproducing the deployment command

The following dry run was checked with the prepared inputs and current final executable:

```powershell
.\run-planet-sharded.ps1 -DryRun `
  -OsmPbf out\planet-pilot-20261017\planet-features.osm.pbf `
  -GlacierWkb out\planet-pilot-20261017\antarctica-ice.wkb `
  -OutputDir F:\contour-planet-production
```

Before starting, retain those immutable inputs, check the storage budget in this report, compare
`target/vectorvector-cli.jar` with the recorded final hash and use a fresh output directory.
Remove `-DryRun` only when starting the actual production pilot. Resume with that exact command
and executable; do not rebuild. Default settings remain eight workers, 16 GiB G1, 512 elevation
cache entries, 1,024 glacier-cache entries and z6 partition roots. No full planet command was run
as part of these tests, and no multi-day ETA is inferred from the selected terrain.
