# Planet sharding validation — 16 October 2026

## Result

The resumable build path passes automated checks and a small real-data recovery test. A forced-stop
and resumed build produced exactly the same 84 compressed tile payloads as the pre-change packaged
CLI. This establishes correctness for that window, not full-planet memory or throughput capacity.

## Real-data comparison

Inputs:

- Elevation: `D:\Mapterhorn\mapterhorn.pmtiles`, the local planet archive, native z0–12.
- OSM: `D:\OpenStreetMap\iceland-260630.osm.pbf` for glacier polygons and peaks.
- Profile: existing RBT defaults, 512-pixel elevation grids, gzip output.
- Range: z11–13, minZ coordinates x928–929, y545–546: four adjacent partition roots in Iceland.
- New build: 2 × 2 shards, three tile workers, `-Xmx2g`.
- Baseline: the existing `target/vectorvector-cli.jar` from before these changes, copied to
  `out/planet-validation/baseline-cli.jar` before packaging the new code.

| Check | Result |
| --- | --- |
| z11 payloads | 4 identical |
| z12 payloads | 16 identical |
| z13 payloads | 64 identical |
| Missing / additional / different payloads | 0 / 0 / 0 |
| Raw compressed blobs after resume | Byte-identical to baseline |
| SQLite `integrity_check`, baseline / clean sharded / resumed | `ok` / `ok` / `ok` |

The comparison includes contour geometry, attributes, glacier splits, peaks, and buffered edges
between the four shards. z13 uses the existing ancestor overzoom behavior.

For the forced-stop test, a separate JVM was terminated after the first shard completed and the
next shard was actively processing without a completion marker. Restarting the identical
command reused the first archive without changing its modification time, rebuilt the incomplete
shard, finished the other two, and merged successfully. A subsequent run reused all four shards
without loading OSM indexes. Changing `--parallelism` on the same work directory was rejected
with exit code 1 before OSM loading or shard modification.

Reproduction inputs and logs remain under `out/planet-validation/`:

- `baseline.log`, `sharded.log`
- `interrupted-final.log`, `resumed-final.log`, `reuse-all.log`, `changed-settings.log`
- `crash-args.xml`: exact JVM/CLI arguments for the interrupted run
- `release-build.log`, `verification-tests.log`

Compare the archives using:

```powershell
python tools\compare_tiles.py out\planet-validation\baseline\contours.mbtiles `
    out\planet-validation\interrupted-final\contours.mbtiles
```

## Automated checks

JDK 21 and the project-local Maven toolchain were used. `mvn -q package` passed, including the
existing default contour golden hashes and the RBT, PMTiles, glacier, and peak tests. A final
`mvn -q test` also covers:

- Exact shard coverage with bbox and explicit-tile filters, and no overlapping ownership.
- Long planet counts through z16 and sparse tile-list counts through z25 without enumeration.
- Worker concurrency limits and prompt failure propagation with a full producer queue.
- Incomplete-shard rebuilds, completed-shard reuse, and run-fingerprint rejection.
- Duplicate-key merge rejection with preservation of the previous final archive.
- Lazy peak elevations, with cached DEM lookup before ranking.
- Decoded payload sharing across tile coordinates, with distinct archive identities.
- SQLite full-transaction and partial-transaction flushing, writer-error propagation, and rebuild.
- Validation of worker, shard, work-directory, output-name, and local-input requirements.

Final suite result: **128 tests, 0 failures, 0 errors, 3 skipped** (125 executed successfully).
The three diagnostic/benchmark tests normally skipped by the existing suite remain skipped.
The launcher was checked with `-DryRun`; its default planet command was not executed.

## Remaining pilot work

This section records the initial validation scope. The subsequent
[17 October readiness pilots](planet-pilot-results.md) now cover global feature preparation,
index memory, eight regional shards, forced recovery, a larger-than-RAM merge and global quality
windows. Read that report for the current status; the measurements above remain historical.

No full planet build was started. In particular, the complete OSM planet was not loaded into the
glacier and peak indexes during this validation. Selected features still reside in RAM. Measure
startup, sustained heap, throughput, output size, and merge space with the complete selected feature
set and multiple terrain regions before committing to a multi-day production run. Native z13 data,
Antarctica, Greenland, and antimeridian quality need their own checks. Input datasets were read only.
