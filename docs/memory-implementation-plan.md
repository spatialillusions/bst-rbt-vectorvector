# Memory and throughput implementation plan

Authorized on 18 October 2026. Implement the five recommendations from
`memory-footprint-investigation.md`, verify output against the previous producer,
iterate where necessary, then deploy and continue planet generation.

## Preservation and acceptance rules

- Stop the old supervisor before its worker so it cannot restart during testing.
- Preserve the old frozen runtime, inputs, manifest, fingerprint and completed
  shards. The initial snapshot contains 1,698 completed shards. Never rewrite
  the old fingerprint to make a different executable appear compatible.
- Develop in the isolated migration worktree. Datasets and generated output stay
  outside Git. Do not modify the original C: checkout or publish remotely.
- Keep contour intervals, smoothing, saddle decisions, float arithmetic, clipping,
  buffers, glacier semantics, peak ranking and gzip settings unchanged.
- Synthetic tests require only repository fixtures. Real-data validation uses our
  own frozen pilot/planet output, not external RBT data or assets.
- Reject optimizations that change output or materially regress sustained speed.
  Measure memory, allocation, CPU, wall time and file-cache pressure separately.

## Phase 1: byte budgets and lightweight overzoom tiles

Add explicit native-elevation, derived-tile and glacier cache byte budgets,
retaining existing entry limits as secondary bounds. Account for shared native
arrays conservatively without claiming duplicated references are extra arrays.
Replace retained resampled child arrays with lightweight views of native parents,
preserving float interpolation and ancestor fallback. Materialization must preserve
NaNs and buffered neighbour access. Test random/edge values bit-for-bit, cache
eviction, overzoom and old tile payloads; compare several cache budgets.

## Phase 2: primitive contour processing

Carry primitive coordinate buffers through Chaikin and RDP, converting only where
the existing clipping/ice/encoding interfaces require objects. Preserve open/ring
endpoints, operation order, degenerate lines and exact coordinates. Keep the
existing tracing topology initially; broaden primitive tracing only if measured
benefits justify the extra correctness risk. Compare randomized line results and
default/RBT golden outputs, then profile real dense terrain.

## Phase 3: prepared spatial inputs

Prepare a z6 store once from the filtered global PBF and supplemental ice WKB.
Persist the exact buffered base masks produced by the existing glacier index,
plus halo-selected peak records with their original attributes and IDs. This
avoids both global runtime indexes and repeated relation/geometry preparation.
Handle antimeridian wrapping and Antarctic holes using existing spatial logic.
Record provenance and per-partition checksums; publish a completion manifest only
when every required partition is complete. Runtime loads a bounded number of
partitions and continues using the original peak DEM lookup/ranking and recursive
glacier clipping below z6. Validate corruption/missing partition failures and
compare peak payloads plus masks with global indexes, including DEM fallback.

## Phase 4: archive-handle lifetimes

Add an explicit quiescent shard-end hook that reopens local PMTiles handles while
retaining decoded grids and directory metadata. Never close channels under active
reads. Test positional reads, EOF, concurrency and lifecycle errors. Measure
resident Windows cache through repeated real shards. Keep bounded worker sessions
and memory/disk guards until longer evidence supports removing them. If handle
rotation regresses speed or fails to control cache, retain it as an explicit option
and document the measured choice rather than declaring it a proven replacement.

## Phase 5: correctness and performance gates

Run JDK 21 verification via the repository build helper and relevant Python tests.
Freeze a candidate JAR. Run paired old/new pilots on identical inputs and tile
lists covering polar ice, dense terrain, coast, negative elevations, dateline and
missing-height peaks. Compare all keys/raw payloads and inspect any differences.
Compare candidate output with the old independent 1,060-tile pilot and at least
two representative completed planet shards. Use fresh destinations throughout.
Run repeated sustained workloads with identical worker counts, recording startup,
throughput, peak RSS/heap, allocation/GC and system cache. Iterate/reject changes
until correctness passes and memory improves without a material speed regression.

## Phase 6: audited adoption and deployment

Create a separate new deployment/work directory with its own frozen JAR, command,
input identities, supervisor and fingerprint. Preserve all old artifacts. Import
only completed old shards through an explicit audited compatibility migration:
verify old fingerprint, completion stamps and archive integrity/hashes, record
producer identities and validation evidence, then link/copy to the new directory.
Compute the new fingerprint from the exact new executable/arguments/inputs;
never disable identity checks or relabel the old work directory in place.

The new supervisor must continue automatically through recovery, merge, full
raw-payload verification against every source shard, and the independent sample
comparison before reporting completion. Confirm real progress after launch and
record current operational paths/restart command. Full-planet completion remains
pending until those checks actually finish; starting the job is not completion.

## Evidence and reporting

Initial preservation: `out/memory-implementation-20261018/baseline-snapshot.json`.
Record implementation decisions, failed experiments, test totals, paired results,
adopted budgets, compatibility-import evidence and deployment details in a linked
implementation report. Commit source/docs locally on the migration branch.
