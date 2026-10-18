# Planet memory implementation, 18 October 2026

The [phase plan](memory-implementation-plan.md) applies the five recommendations
from the [code and allocation investigation](memory-footprint-investigation.md).
Development uses the isolated `migration/planet-contours-20261017` worktree.
The original C: checkout and previous frozen producer are preserved.

## Implementation

1. **Prepared spatial partitions.** `PrepareSpatialInputs` computes the existing
   exact buffered z6 glacier masks once, preserving wrapping and holes, and stores
   halo-selected peak records with original IDs/attributes. Runtime retains at
   most two roots, with a 64 MiB estimated-root target (an oversized active root
   remains usable alone). Original DEM fallback and ranking remain in use.
   Partitions have SHA-256 checksums, input provenance and a completion manifest
   published last. Runtime verifies each loaded partition. Preparation has a lock
   and can resume partial work with unchanged inputs.
2. **Byte budgets and locality.** Elevation cache budgets total 384 MiB across
   payload, native and derived caches. Glacier budgets total 128 MiB across the
   two prepared-root child caches. Existing entry limits remain secondary bounds.
   Shared sample arrays are conservatively charged; shard-end telemetry also
   reports unique retained sample bytes by identity. Guava uses one weight segment
   so a large entry that fits the budget is not rejected by a smaller segment.
3. **Lightweight overzoom.** Derived 512-pixel tiles retain parent grids and axis
   tables instead of complete child float arrays. Bulk buffered sample copying
   feeds the original blur math directly. Interpolation operation order and NaN
   behavior are unchanged, including ancestor fallback and world boundaries.
4. **Primitive line processing.** Chaikin and RDP use primitive coordinate buffers
   and a primitive work stack. Final retained coordinates become the original
   line objects before existing glacier splitting, clipping and encoding. The
   contour tracer, saddle decisions, intervals and quality settings are unchanged.
5. **Archive lifetime.** An explicit shard-end hook optionally reopens local
   PMTiles handles after tile work/writers finish. Read/write locks prevent closing
   under positional reads; input size/time changes fail. Decoded caches survive.
   This is not a Windows file-cache cap. Checkpoint worker recycling, memory/disk
   guards and progress/failure limits remain necessary for the unattended run.

All changes preserve the z9–13 output contract. The optional 10 m/z14 extension
remains a separate research proposal and is not part of this production upgrade.
No external RBT archive, test data or style asset is required by the normal build.

## Iteration and rejection evidence

Candidate 02 passed the independent pilot and repeated mixed-terrain comparisons,
but was rejected during its complete dense Tibet shard test. With smaller caches,
the original 128-tile-long strip traversal repeatedly reloaded neighbour elevation
data. A small-window benchmark had hidden that working-set problem.

Candidate 03 visits 8-by-8 spatial blocks, leaving all per-tile math unchanged.
Prepared base masks also bypass child-cache accounting because the partition
already owns them. These changes address repeated data loads and repeated ancestor
overlays instead of merely increasing the heap. Sparse/bounded/full range tests
check that blocking visits exactly the same tile set once.

Candidate 03 JAR SHA-256:
`2639b197bf2c22daa05b2bcca64f8c8fe26cd6cf1dba14dad47993e145a3e383`.
It is frozen under `out/memory-implementation-20261018/candidate-03.jar`.
Raw benchmark and failed-iteration artifacts remain outside Git under the same
evidence directory, including `paired-02` and `paired-03`.

## Verification protocol

The JDK 21 helper's `verify` passed: **157 tests passed, 4 optional-data tests
skipped, zero failures/errors**. New checks include more than two million exact
interpolation samples, randomized open/closed smoothing and simplification,
prepared glacier/peak equivalence with DEM fallback, corruption/truncation,
cache eviction, concurrent archive reads/reopening and tile-block coverage.
Python checks cover raw comparison, merge verification, supervisor policy,
repository-only styles and input preparation.
All **29 Python tests passed** after adding the migration checks.

Paired benchmarks serialize fresh old/new JVMs with eight workers, a 6 GiB G1
heap and 8 MiB regions on both sides. Fixed region size avoids changing the
humongous-allocation threshold while comparing code. Two mixed-workload repeats
reverse old/new order. Processing time is reported separately from startup.
These are warm-system measurements, not artificially purged cold-disk results.

The compatibility protocol requires every key and compressed payload to match,
including missing/extra tiles, plus SQLite integrity checks. It covers the
independent 1,060-position pilot (800 stored tiles), mixed Iceland/Alps/Himalaya/
Greenland/Antarctica windows and two complete old planet shards. The migration
gate also checks peak RSS improvement and both mixed and sustained processing
speed, so startup savings cannot hide slower generation.

## Candidate 03 measured results

| Workload / metric | Previous producer | Candidate 03 |
| --- | ---: | ---: |
| Mixed 640 positions, median wall time including startup | 56.73 s | 11.42 s |
| Mixed median processing time, 1 s log resolution | 27 s | 9.5 s |
| Mixed median peak RSS | 3,945.6 MiB | 2,361.8 MiB |
| Mixed median CPU time | 135.89 CPU-s | 59.74 CPU-s |
| Complete Iceland, candidate wall / peak RSS | — | 39.70 s / 1,769.9 MiB |
| Complete dense Tibet processing | 356 s, preserved planet log | 235 s |
| Complete dense Tibet, candidate wall / peak RSS | — | 235.89 s / 2,981.5 MiB |

Mixed peak RSS fell **40.1%**, processing time **64.8%**, and CPU time **56.0%**.
Including global-index startup, mixed wall time fell **79.9%**. That startup-heavy
speedup is not a prediction of whole-planet throughput. Complete Tibet processing
was **34.0% faster** than the preserved run interval. That sustained comparison
uses an earlier 16 GiB production JVM, whereas the mixed paired comparison controls
both heap and GC region size. No new full old-producer Tibet replay is claimed.

Both mixed repeats matched all 638 stored tiles. Complete Iceland matched all
9,209 stored tiles; complete Tibet matched all 21,134. The independent pilot
matched all 800 stored tiles across 1,060 requested positions. There were no
missing/extra keys or payload differences. Contour/peak compressed data therefore
has exactly the same size. Database page layouts differ with insertion order:
Iceland archives were 24,915,968 versus 24,952,832 bytes (+0.15%); Tibet archives
were 429,084,672 versus 429,203,456 bytes (+0.028%).

The complete Tibet test retained 127 MiB of unique elevation sample arrays at its
final cache report. Its minimum available physical memory was 9,687 MiB and peak
system-cache resident counter was 744 MiB. These bounded results do not prove a
long-running Windows cache plateau. Process RSS, cache counter and available RAM
are distinct measurements; the supervisor protections remain enabled.

## Preservation and adoption

The previous supervisor was stopped first, then its verified worker, at 10:31
Europe/Stockholm. The snapshot preserves **1,698 completed markers**, the original
fingerprint and manifest. Original producer SHA-256:
`7455d9e4c95b6e0ccfc628d79df4256a0780b02d56d5e3a541f8bb558301a4f8`.
The read-only helper independently reproduced its original fingerprint:
`36c560a0f9fb9845788448045b9b10cff99129a887421f21a69faac04c86124c`.

Adoption uses a separate deployment/work directory and a new executable/input/
argument fingerprint. Both deployment locks are held while every completed old
archive is stamp-checked, integrity-checked and SHA-256 verified. Hardlinks avoid
duplicating large archives on the same volume; copy is the fallback. Destination
hashes are checked and an audit is written before publishing the new fingerprint.
Incomplete old shards are rebuilt. The old fingerprint and payloads are never
rewritten. Normal resume checks remain enabled.

Prepared inputs reside at `F:/vectorvector-spatial-20261018-v1`: 4,096 partitions,
453,901,100 payload bytes. Preparation took 282.2 seconds and peaked at 2,379.7 MiB
RSS. Preparation is a one-time global-index step, separate from contour runtime.
