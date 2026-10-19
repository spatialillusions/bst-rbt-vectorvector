# Glacier topology recovery, 19 October 2026

## Failure and cause

The optimized planet build stopped at 22:56 Europe/Stockholm on 18 October with
3,388 completed shards. Tile `10/972/845` in Antarctic shard `x60-y52` raised a
JTS `TopologyException` during contour/glacier intersection. It was not a memory
or disk guard: the worker retained at least 10,489 MiB available physical RAM.
The supervisor correctly stopped on this unexpected error rather than retrying
indefinitely or publishing the incomplete shard.

The exact failure reproduces with the frozen producer. Its projected glacier
mask has a ring self-intersection. The prepared z6 root is valid, but validity
does not automatically survive clipping and non-linear projection of polygon
vertices. Repair at input loading alone is insufficient. The repaired tile mask
has 229 vertices instead of 240 and the same measured area
(`20,781,558.797342565` tile units squared).

## Planetiler comparison

Reviewed upstream `main` on 19 October:

- [FeatureRenderer](https://github.com/onthegomap/planetiler/blob/main/planetiler-core/src/main/java/com/onthegomap/planetiler/render/FeatureRenderer.java)
  attempts clipping, repairs the geometry and retries if clipping fails. A failed
  retry is logged and that feature/zoom can be omitted. Before encoding tile
  polygons it also calls `GeoUtils.snapAndFixPolygon`.
- [GeoUtils](https://github.com/onthegomap/planetiler/blob/main/planetiler-core/src/main/java/com/onthegomap/planetiler/geo/GeoUtils.java)
  uses `buffer(0)` for `fixPolygon`. Its precision/encoding repair path also uses
  `GeometryFixer`, topology-aware precision reduction and, as a last attempt,
  expansion/contraction by a small buffer. Geometry errors are counted.
- [JTS GeometryFixer](https://locationtech.github.io/jts/javadoc/org/locationtech/jts/geom/util/GeometryFixer.html)
  repairs polygon rings, subtracts intersecting holes and unions overlapping
  multipolygon parts. Even valid inputs can be slightly altered, so applying it
  to every mask would unnecessarily change our previously successful output.

We adopt the repair-and-retry pattern, not Planetiler's feature-omission policy
or its tile-coordinate rounding. Our glacier boundaries split smooth contour
lines before the existing final clipping and rounding; rounding them earlier
would change the visual/encoding contract.

## Implementation

`GlacierIceSplitter` retains the original fast predicates and robust-overlay
operations. Only a `TopologyException` triggers repair:

1. Apply JTS `GeometryFixer` to that tile's glacier mask and verify polygonal
   validity. No expansion/contraction buffers, simplification or coordinate rounding are introduced.
2. Prepare/cache the repaired mask once in that splitter. Log the tile, original
   failure, vertex counts and areas. Both global and prepared input modes supply
   tile context.
3. Retry both ice intersection and land difference together. Subsequent lines
   in that tile reuse the repaired mask.
4. If repair/retry fails, propagate the error with the original failure attached.
   Never omit contours or substitute an all-land mask just to continue the build.

No full-planet geometry prepass or persistent global cache is added. Successfully
processed masks incur no validation/repair scan. All completed tiles from the old
producer necessarily avoided the error branch; their original path remains intact.
The uncompleted shard is rebuilt with the corrected producer.

## Tests and evidence

The JDK 21 `verify` run passed: **161 JVM tests passed, four optional-data tests
skipped, zero failures/errors**. New synthetic tests cover both lobes of a
self-crossing polygon, holes, preserved contour length, repair-once behavior,
valid masks without repair, and a valid geographic polygon whose near-edge hole
becomes invalid under Mercator projection. These fixtures are constructed in
code; no external RBT data or local glacier dataset is required.

All **33 Python tests passed**, including negative checks that an import or
identity-preflight failure cannot launch the continuation supervisor.

Real-data evidence is under `out/topology-recovery-20261019/` (outside Git).
The frozen old producer fails on the exact tile; the corrected producer succeeds
and the standalone output verifier finds **11 valid contour features**.
Candidate JAR SHA-256:
`efa185b9a74ce322144172a33671c8e9ce07ab64041946683063134191f7a6ff`.

The paired protocol compares old/new prepared producers with the same eight
workers, 6 GiB G1 heap, 8 MiB regions, inputs and cache/traversal settings. It
requires the independent quality pilot, two mixed repeats, complete Iceland and
Tibet shard equality, reproduction of the old failure, and successful generation
of the complete previously failing Antarctic shard. Every successful tile in the
old partial Antarctic archive must remain byte-identical in the corrected output.

All gates passed. The independent pilot matched 800 stored tiles, both mixed
repeats matched 638, complete Iceland matched 9,209 and complete Tibet matched
21,134. Mixed wall-time medians were 9.98 s old / 9.94 s new, with peak RSS
2,254 / 2,205 MiB. The dense Tibet candidate took 247.36 s; that is 4.9% longer
than the earlier candidate's 235.89 s historical wall interval, not a fresh
paired full-shard comparison.

The complete Antarctic shard passed in 106.0 s at 1,386.8 MiB peak RSS:
18,782 stored tiles, 201,801 contour features and 129 peak features. A separate
standalone scan verified its MVT schema, intervals, ice flags and coordinate
bounds. All **249 previously successful partial-shard tiles** remained raw-byte
identical. Two masks required recovery: `10/972/845` and `11/1945/1690`.

A class-by-class comparison of old/new JARs found changes only in `MainKt`,
`PreparedSpatialStore` (tile context), `GlacierIceSplitter` and its two new helper
classes. Elevation, smoothing, tracing, clipping, encoding and dependency classes
are byte-identical between the frozen JARs.

For this bug-fix transition, performance/peak-RSS gates allow at most 20% variation
(processing logs have one-second resolution), rather than demanding the earlier
memory upgrade's 25% RSS reduction. This is an explicit validation kind with
additional failure/recovery evidence, not a general identity-check override.

## Preserving and continuing the run

The snapshot records all 3,388 marker contents, the frozen manifest and fingerprint
`68d0f88e610355440e2187297d4f3a16d7fac9d2609f71b96a3d4066225df90d`.
The old deployment and inputs remain preserved. A corrected producer requires a
new deployment and fingerprint; no frozen JAR or old fingerprint is edited.

The audited import validates stamps, SQLite integrity and payload hashes before
copying/linking completed archives. It publishes the new fingerprint last. This
filesystem previously required copies, so the import has a substantial one-time
I/O cost. The incomplete Antarctic archive is not imported.

`continue_validated_upgrade.py` runs the import under memory/disk guards, then
checks the frozen supervisor identities and launches it only on success. It stops
if any stage fails. The supervisor retains checkpoint recycling, resource guards,
progress limits and automatic full merge/raw-payload/sample verification.

The corrected frozen deployment is `F:/vectorvector-planet-repaired-20261019`,
with producer source commit `c5dd36d`. The continuation driver started on
19 October at approximately 08:40 local (initial driver PID 5304). At handoff
it is auditing/importing the 3,388 completed archives; generation has not yet
restarted. It proceeds automatically after successful import and identity checks.

Follow `upgrade-status.json` and `import-monitor/run.log` during adoption, then
`status.json` and `supervisor.stdout.log` once the supervisor starts. Per-worker
logs are under `monitor/generation-NNNN/run.log` and rotate at planned restarts.
The old deployment's status records a pointer to this recovery; its frozen JAR,
inputs, fingerprint and completed archives remain unchanged.
