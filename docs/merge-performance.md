# Bulk MBTiles merge — 16 October 2026

The merge now disables journaling for its disposable working database and builds the tile index
once at the end. On the local machine, the median time for a 32-shard, roughly 760 MiB merge fell
from **22.27 to 20.06 seconds**, a **10.0% reduction**. All tile keys and compressed payloads match.
These are bounded tests, not a full-planet merge or an estimate of planet completion time.

## Why this is appropriate here

The previous implementation used `journal_mode=DELETE`, `synchronous=NORMAL`, and an index updated
throughout the copy. A rollback journal preserves original versions of modified pages; it does
not necessarily duplicate every appended tile blob. See [SQLite's atomic commit explanation](https://www.sqlite.org/atomiccommit.html).

Our `.merging` database is rebuilt from immutable completed shards on every retry. It has no
readers and is never a checkpoint. Consequently, SQLite transaction recovery inside this
temporary file is unnecessary. The previous final archive remains separate until publication.

The implementation now:

1. Attaches one source shard at a time in read-only mode and copies its compressed blobs through SQLite.
2. Uses `journal_mode=OFF` and `synchronous=OFF` on the temporary destination, with a 16 MiB page cache.
3. Creates the unique tile index after all copies, rejecting duplicate keys before publication.
   Large index sorts may spill to disk (`temp_store=FILE`).
4. Closes the database, explicitly flushes it with `FileChannel.force(true)`, then atomically replaces
   the final archive on the same filesystem.
5. Discards the temporary database on any ordinary error. An interrupted process can leave a
   partial file; the next attempt discards it and starts the merge again from completed shards.

This exception applies only to the disposable merge. Shard generation keeps its existing
transactions. With journaling disabled, failed statements can leave corrupt partial structures,
including a failed unique-index build; no rollback is attempted and the failed file is never
published. This follows the limitations in [SQLite's journal-mode documentation](https://www.sqlite.org/pragma.html#pragma_journal_mode).
Process-error recovery is covered by tests; machine power-loss behavior was not experimentally tested.

## Actual Java/JDBC measurements

The test invokes the production Kotlin merge function from `tools/MergeBenchmark.java`, against
the original and rebuilt executable. Both use the same SQLite JDBC 3.47.1.0, JDK 21, and
`-Xms128m -Xmx1g`. All runs were serialized after compilation and tests finished.

The input is **32 synthetic shards**, each repeating the real 9,209-tile Iceland shard payload
distribution at disjoint synthetic z13 keys: **294,688 tiles**, **799,916,032 input bytes**.
These are valid disjoint database keys, but the repeated payloads do not represent distinct terrain.
Sources and outputs are on F: (Kingston USB), on the previously documented Core Ultra 7 / 31.49 GiB
machine. Inputs can benefit from the Windows filesystem cache; this was not a controlled cold-cache test.

| Sequence | Implementation | Merge + final flush | Process CPU time |
|---|---|---:|---:|
| 0 | Original | 37.00 s | 7.22 s |
| 1 | Bulk | 20.06 s | 7.08 s |
| 2 | Bulk | 20.06 s | 6.78 s |
| 3 | Original | 22.27 s | 7.09 s |
| 4 | Original | 22.12 s | 6.84 s |
| 5 | Bulk | 19.82 s | 6.45 s |

The first original run was much slower than its repeats. The **10% median reduction**, rather
than the first pair's 46% reduction, is the useful summary. Original output is 760.91 MiB;
bulk output is 760.49 MiB. The small size difference is index packing, not omitted tiles.

Every output passed `PRAGMA integrity_check`. A streaming digest of ordered tile keys, payload
lengths and raw compressed payloads is identical in all six runs:
`19cfa100dbab9d03d43258244ad513556538910c15878fd206b73c48ac7b02c7`.
Verification and JVM startup are excluded from the measured time; final file flushing is included
for both implementations. Early exploratory Python SQL runs were not used in this table.

Raw logs and `out/merge-benchmark/java-results/results.json` are under
`out/merge-benchmark/java-results/`. The original executable is
`out/performance/optimized-memory-cli.jar` (SHA-256 `580309f693a1d74f23bfdc11b26aa6df58e830ce70bb51fbf7877884e87a9abf`);
the measured bulk executable is copied to `out/merge-benchmark/bulk-cli.jar`
(SHA-256 `5997cf086a3c1863018b64b3f536b77fc9d51e32201f9015e4844f31d8f52cec`).

## Later page-layout and large-file validation

The [17 October readiness pilots](planet-pilot-results.md) extend this initial study with
alternating 4/16/64 KiB page-size measurements, random tile reads and a 40 GiB input workload.
The production default now uses 64 KiB pages, direct source scans and bounded read-only source
mapping. The earlier timings below describe the earlier executable; retain their identities
when comparing results. Compressed payloads remain unchanged.

## Verification and reproduction

`mvn package` passed: **130 tests passed, three existing diagnostic/benchmark tests skipped**.
Coverage includes interleaved shard keys, encoded filenames, final index uniqueness and integrity,
duplicate rejection, cleanup after partial-copy failure, preservation of previous output, stale
journal cleanup, and reuse of completed shards. The contour golden tests still pass.

Prepare a fresh benchmark directory with real payloads:

```powershell
python tools/benchmark_merge.py <completed-contour-shard.mbtiles> --out out/merge-new --prepare-only
. .\env.ps1
java -Xms128m -Xmx1g -cp out/performance/optimized-memory-cli.jar `
  tools/MergeBenchmark.java out/merge-new out/merge-new/original.mbtiles
java -Xms128m -Xmx1g -cp out/merge-benchmark/bulk-cli.jar `
  tools/MergeBenchmark.java out/merge-new out/merge-new/bulk.mbtiles
```

Repeat in alternating order. The Java helper prints `MERGE_RESULT` JSON, validates integrity,
computes the payload digest, and removes its benchmark output after verification. Input shards
are retained. Without `--prepare-only`, the Python tool separately compares three SQL strategies
using Python's SQLite; those results should not be mixed with the Java measurements.

Planet-scale merges still require reading and writing all tile payloads and enough temporary
space for index sorting. This change does not implement globally ordered multiway copying,
merge checkpoints, or serving directly from shards. As with any executable change, existing
resumable runs have a different build fingerprint; the runbook's compatibility rules still apply.
