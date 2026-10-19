# Planet throughput validation, 19 October 2026

Implementation follows [the throughput plan](planet-speed-plan.md). The active
planet keeps its repaired frozen producer. Performance trials run serially in a
bounded checkpoint pause; a separate driver resumes that producer even if a
trial fails. Trial outputs never replace completed planet shards.

## Clean results

The complete serial protocol passed on 19 October at approximately 19:13 local:
**22 runs, 22 exact raw-payload comparisons, no SQLite integrity errors and no
memory/disk guard stops**. Explorer transfers were quiet before trials and
monitored during each interval. Evidence:
`out/planet-speed-clean-20261019-01/report.json` (`checks=passed`).

Wall times below include JVM startup. Each regional value is the median of two
runs, reversing baseline/Java/native execution order in the second repetition.

| Full shard | Frozen baseline | New Java | New native | Native reduction |
|---|---:|---:|---:|---:|
| Iceland, x29-y16 | 50.32 s | 49.59 s | 29.55 s | 41.3% |
| Tibet, x45-y25 | 248.83 s | 234.97 s | 210.70 s | 15.3% |
| Sparse Antarctica, x62-y58 | 56.65 s | 58.11 s | 35.02 s | 38.2% |

The largest measured win is native decoding. The combined Java changes were
roughly unchanged in Iceland, 5.6% faster in Tibet and 2.6% slower in sparse
Antarctica. Small differences should not be treated as statistically settled
with two repetitions. These are combined-producer comparisons, not isolated
ablations proving an individual shortcut's contribution. In particular, empty
output alone does not prove the early-rejection path will avoid its computation.
No whole-planet speedup is extrapolated from three regional shards.

All 9,209 Iceland, 21,134 Tibet and 380 sparse Antarctic stored tiles matched
the previous producer exactly, including absence of additional rows. Both new
backends also matched 800 stored tiles from the independent 1,060-position pilot.
The full formerly failing glacier shard matched all **18,782 stored tiles**:
92.95 s Java and 46.72 s native. Those glacier times are single runs, not the
repeated baseline protocol above.

Median per-run peak process RSS:

| Full shard | Frozen baseline | New Java | New native |
|---|---:|---:|---:|
| Iceland | 1,710 MiB | 1,406 MiB | 1,933 MiB |
| Tibet | 3,134 MiB | 3,010 MiB | 3,682 MiB |
| Sparse Antarctica | 1,478 MiB | 1,553 MiB | 2,518 MiB |

The worst process peak across all trials was **3,834.6 MiB (3.75 GiB)**; minimum
system available RAM was **8,233.9 MiB (8.04 GiB)**. All trials retained the
6 GiB heap and existing cache budgets. Native improves speed at a higher process
memory cost, so it remains opt-in. Separate fresh JVM trials do not establish a
multi-hour native memory plateau; retain the supervisor's bounded sessions and
resource guards for future large native builds.

After successful validation the queue encountered a PowerShell singleton-process
JSON parsing error in its restart cleanup. Its process-list normalization was
corrected and checked against empty/null/singleton/multiple results. The original
frozen supervisor was explicitly restarted at 19:13:27 local, preserving all
3,838 completed shards. This post-test controller error did not affect measured
intervals, input identities or compared outputs. The current planet continues
with its original repaired producer, not the experimental candidate.
Frozen input/runtime preflight passed and generation session 0015 started at
19:14:21. A new checkpoint at 19:15:28 advanced the total to **3,839/4,096** and
generation proceeded into `x63-y59`, confirming actual continuation.

## Changes

* Mapterhorn grids cache a conservative whole-grid constant-zero fact. Overzoom
  views inherit it only when their entire parent is zero. Before contour work,
  the pipeline checks every tile intersecting the complete smoothing footprint.
  A coastal neighbour, unknown tile or Mercator-edge no-data prevents this shortcut.
  Peak layers remain independent, so rejection cannot discard peak-only tiles.
  Candidate 02 also caches whole-source elevation bounds. Child views expand
  these for float interpolation rounding. The union of all kernel-footprint
  bounds can reject a lowland tile before allocating or copying its sample grid.
  Unknown bounds and non-default elevation multipliers retain normal processing.
* After materializing the complete kernel footprint, a padded raw elevation range
  can reject contour-free tiles before blur passes. Padding includes a full metre
  beyond outward-rounded extrema; unusual magnitudes/sizes use normal processing.
  This is conservative, rather than a heuristic based on the centre pixel or
  a land/ocean polygon. Zero contours remain excluded; negative contours remain valid.
* Finite bounded grids use running-sum smoothing without per-pixel NaN counts.
  The accumulation order, division, kernel radii and grid dimensions remain the
  same. Grids with missing/extreme samples retain the existing path. No shared
  full-tile blur cache or enlarged elevation cache is introduced.
* Marching squares uses a per-tile integer elevation lookup instead of a binary
  search in each candidate cell. ceil(value) preserves inclusive lower/exclusive
  upper level boundaries. Storage is capped at 256 KiB per active tile; unusually
  wide ranges use the original binary search. Level filters still run before tracing.

## WebP investigation

[WebP4j 2.5.0](https://github.com/MrNanko/webp4j) provides a Java 21-compatible JNI
decoder based on libwebp 1.6.0, including Windows x64. It decodes the unchanged
source WebP bytes. A local probe compared all float samples in **288 real tiles**
across Iceland, Alps, Himalaya, Greenland, Antarctica, ocean/dateline and Dutch
locations at z9-12: elevation bits and no-data values matched the Java decoder.
This is sample evidence, not a proof covering all inputs.

The default remains the portable TwelveMonkeys decoder. Select the experimental
native backend explicitly with a JVM option **before** `-jar`:

```powershell
java -Dvectorvector.webpDecoder=native -jar target/vectorvector-cli.jar <normal arguments>
```

`java` and `native` are the only accepted property values. PNG inputs still use
ImageIO. Native selection fails on an unavailable platform rather than silently
changing a benchmark's backend. Normal builds require no external RBT data or
native library installation on supported bundled platforms; default Java decoding
does not load the native library. Native images are decoded one tile at a time
inside the existing bounded worker/cache limits.
The selected backend is included in the resumable-build fingerprint; switching
between Java and native requires a separate output deployment.

[NightMonkeys](https://github.com/gotson/NightMonkeys) requires Java 22+ for its
native path, so it cannot replace decoding in this Java 21 build. The older
[Sejda wrapper](https://github.com/sejda-pdf/webp-imageio) was not selected.
WebP4j, libwebp and giflib license/patent/copyright texts are packaged under
`META-INF/licenses/`. A publication review should include bundled native dependencies.

## Measurement protocol

`tools/benchmark_planet_speed.py` compares the frozen repaired producer, the new
Java backend and the new native backend. All use eight workers, a 6 GiB G1 heap,
8 MiB regions, prepared spatial inputs, 384/128 MiB caches and 8x8 traversal.
At least two repetitions reverse execution order. Timings include JVM startup;
CPU seconds and peak RSS are recorded separately. Historical shard log intervals
are reported separately because they are not controlled paired measurements.

Each output must pass SQLite integrity and exact compressed tile-key/payload
comparison with the completed planet shard. The independent 1,060-position pilot
and the full formerly failing Antarctic shard are included. There is no RBT
archive dependency in this protocol. A successful comparison also preserves
compressed output size; physical SQLite packing need not be identical.

Candidate 01 SHA-256:
`6715fa17d9ffd149aa33add21a008c507c664347e2dec7fdb5e5be8961be39ed`.
Its JVM verify passed: 165 tests passed, four optional-data tests skipped.
The first iteration was deliberately stopped after repeated Iceland trials to
combine scan passes and move lowland rejection ahead of sample materialization.
It is retained as incomplete evidence, not a passed full protocol. Iceland wall
times were 47.91/49.96 s baseline, 50.66/48.79 s new Java and 29.10/25.32 s native;
all compared outputs contained the same 9,209 compressed tile payloads.
An active Explorer transfer was subsequently identified at approximately 36 MiB/s
read plus 36 MiB/s write. **These timings are contended diagnostics and are
excluded from clean performance conclusions.** Reports are explicitly marked
`diagnostic-transfer-contention`; the earlier apparent speedup is not accepted
as a controlled measurement.

Candidate 02 SHA-256:
`32d6d4d940b0949d8cc7e213c7defa240c50b05fcf8f8bd0723dab69a3925cef`.
Its verify passed 167 tests with four optional-data skips. A subsequent focused
run passed both elevation-view tests, including the added conservative-bound
test; 33 Python tests also passed. This intermediate JAR was used only for its
diagnostic window; clean trials use candidate 03 below.
Local evidence is under `out/planet-speed-20261019/` and `out/webp-review/`.
Candidate 02's independent pilot matched 800 stored tiles and its complete
Iceland shard matched 9,209 with both backends. The full-shard trial window was
stopped when transfer contention was confirmed; its timings are also excluded.
Peak RSS in that contended Iceland trial was 1,594 MiB baseline, 1,611 MiB Java
and 2,205 MiB native. These diagnostic figures are superseded by the clean
measurements above; a lower wall time does not imply lower peak RSS.

The final artifact additionally records backend selection in resume identity.
Its per-tile generation code is unchanged from candidate 02. Full JVM verify
passed: **169 tests passed, four optional-data tests skipped**, including the
backend-identity test; **36 Python tests passed**. Final candidate 03 SHA-256:
`a63912b42ac455699a0f958abf62a1a7ab337f3424999cbe2761156bcfe455f2`.
The final JAR includes the bundled native dependency notices; a class comparison
found changes from candidate 02 only in `ShardBuildKt` and its generated identity
helpers. The passed final serial evidence is under
`out/planet-speed-clean-20261019-01/`.

The original repaired planet resumed after both diagnostic windows and is
generating normally. `out/webp-review/queue_clean_validation.py` waits for a
30-second quiet Explorer interval while the planet continues. It then pauses
at a checkpoint, requires quiet disks, runs the full serial comparison and
resumes the unchanged supervisor in a finally block. A transfer during a trial
invalidates the interval and queues another attempt. Unexpected errors or
output mismatches stop validation for inspection; they do not adopt the candidate.
If the planet is already merging/verifying, the queue waits for completion.

Operational state: `F:/vectorvector-planet-repaired-20261019/throughput-validation-status.json`.
It records `clean-validation-passed`; the clean report has `checks=passed`.

Distributed generation remains future work. The current eight-core machine is
the only benchmark/execution target.
