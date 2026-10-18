# Memory footprint and contour throughput investigation

Measured on 18 October 2026 against the frozen producer JAR from commit `9866a3b`,
SHA-256 `7455d9e4c95b6e0ccfc628d79df4256a0780b02d56d5e3a541f8bb558301a4f8`.
The planet executable, inputs, parameters and supervisor were unchanged. These
are research recommendations, not changes enabled in the running build.

## What the code retains

`Main.kt` constructs the elevation store once and lazy-loads global peak and
glacier indexes once per JVM. They survive across all shards. The 138 MB filtered
PBF and 67 MB supplemental ice WKB expand into 1,118,202 peak records and 131,001
glacier/ice features containing 20,396,774 coordinate positions. Filtering the
original planet PBF helped considerably, but compressed file size is not retained
model size. Glacier loading still resolves ways/relations, locates nodes and
constructs/repairs geometry.

There are three elevation cache layers: `MapterhornSourceSet.decoded`,
`MapterhornElevationDataStore.nativeCache`, and `tileCache`. Their entry limits
are not a combined byte budget. Some layers share arrays, so counting every cache
entry as another allocation would be incorrect. Overzoomed grids introduce new
arrays. A 512-square float grid has 1 MiB of sample data before object overhead.

`GlacierIndex.lonLatCache` retains up to 1,024 variable-size geometry entries in
this deployment. `PeakIndex.elevations` accumulates resolved records for encountered
missing-elevation peaks, up to the input's 335,171 such peaks. Eight tile workers
can be active and read neighbouring tiles for halo continuity. The processor
channel and output writer queue are bounded; the latter holds 200 queued tasks.
Completed planet contours are not retained in an unbounded output list.

Gaussian scratch buffers are already reused per thread. FastIsolines already
handles all levels in one cell pass. Glacier geometry is already clipped before
unioning, and its splitter already uses prepared disjoint/containment tests.
Those should not be presented as new optimizations.

Source entry points:
[index construction](../src/main/kotlin/com/greensopinion/vectorvector/Main.kt),
[elevation caches](../src/main/kotlin/com/greensopinion/vectorvector/elevation/mapterhorn/MapterhornElevationDataStore.kt),
[glacier index](../src/main/kotlin/com/greensopinion/vectorvector/osm/GlacierIndex.kt),
[peak index](../src/main/kotlin/com/greensopinion/vectorvector/osm/Peaks.kt),
[contour pipeline](../src/main/kotlin/com/greensopinion/vectorvector/sink/contour/ContourPostProcessor.kt),
[point operations](../src/main/kotlin/com/greensopinion/vectorvector/sink/contour/LineOperations.kt).

## Five recommended changes

1. **Prebuild shard-local glacier geometry and compact peak records once.**

   Matching spatial inputs to the z6 output shards would help. Prefer prepared
   geometry/records over merely splitting raw PBFs: otherwise workers still repeat
   relation assembly, node lookup and geometry repair. Preserve IDs, names and
   attributes, and include a halo. Eventually prepare buffered masks for large ice
   sheets rather than duplicating continent-sized features into many shards.

   The real global feature model and indexes used about **1.09 GiB post-GC heap
   delta**. Fresh JVMs loading prepared Iceland/Himalayan partitions used about
   **2.0 MiB/23.0 MiB**. Their files were **0.61 MiB/7.38 MiB** and loaded/indexed
   in **0.115/0.223 seconds**. The capped global probe took 55 seconds; this is
   not a production speedup ratio, since production has different parallelism.
   Avoiding global preparation would reduce both retained inputs and restart work.

   Iceland retained 71 glacier features and 824 peaks; the Himalayan sample
   retained 5,821 and 3,847. **54 sampled masks/peak layers matched** the global
   indexes: ice masks were topologically equal and peak layers byte-identical.
   The same null DEM fallback was used in both peak indexes; real missing-height
   resolution is not covered. The prototype saved whole intersecting geometries,
   not pre-unioned base masks. It used a z6 halo of 1/16 root tile per side,
   matching the base glacier buffer. Final preparation must derive its halo from
   settings and test dateline wrapping, Antarctic holes, seam continuity, ranks,
   names, peak DEM fallback and output payloads before production use.

2. **Give the elevation/glacier caches measured byte budgets.**

   Separate native-data and derived-grid budgets, account for shared arrays,
   and weight glacier entries by estimated retained geometry size. Add hit/miss,
   resample/decode and retained-byte instrumentation before tuning eviction.

   On a real Alpine 24-by-24 z13 neighbour-request trace, 512 entries retained
   **694 MiB of distinct float arrays**, versus **255 MiB at 128 entries**:
   **63% less**. Current-thread CPU was **6.44 versus 6.25 seconds**, similar
   within this small test. At 64 entries, arrays fell to 128 MiB but CPU rose to
   7.45 seconds and allocation roughly doubled because of repeated work.

   An actual CLI regression generated the same **576 tile positions** at 128
   and 512 entries. All **558 stored tiles had identical keys/compressed payloads**,
   absent positions matched, and SQLite integrity passed. Sample peak RSS was
   **953 versus 1,468 MiB**; single-run wall times were 14.6 versus 17.8 seconds.
   The planet was running concurrently: these are not controlled throughput
   claims. OSM enrichment was omitted to isolate elevation caching. Validate
   dense, polar, coastal and sustained workloads before changing planet defaults.

3. **Fuse z13 overzoom resampling into elevation materialization.**

   The global elevation archive ends at native z12. At z13, `grid()` resamples a
   parent quadrant into a new 512-square float array that `tileCache` retains.
   Gaussian preparation then copies samples into a double buffer. Lightweight
   child views and a fused materialization pass can remove this intermediate
   allocation. z13 accounts for about three quarters of z9–13 tile positions.

   A prototype allocated **2,103,360 instead of 3,151,976 bytes per grid**,
   **33% less**, and matched **1,048,576 samples bit for bit** across four
   quadrants, including NaNs and clamping. Isolated median current-thread CPU
   was about **0.59 versus 1.64 ms/grid**. This is not a planet speedup forecast.

   Gaussian halos and cache lifetimes still need integration tests. Views must
   not cause excessive repeated interpolation for neighbours. Keep existing
   float rounding and source selection. Do not scale z12 contour lines to create
   z13: smoothing settings differ. Constant zero-parent grids may offer another
   exact fast path, but the complete halo must be checked before skipping work.

4. **Use primitive coordinate/connectivity buffers through the contour pipeline.**

   FastIsolines constructs DoublePoint objects, coordinate hash maps, small
   IntArray adjacency lists and nested lists. Chaikin creates more point lists
   per pass, followed by RDP, JTS ice-splitting conversion and encoding. On a
   rough synthetic 512-square surface, FastIsolines alone allocated **37.7 MiB**
   and returned 186,266 line points. This is a dense test, not every planet tile.

   A primitive-array Chaikin prototype reduced allocation from **215,968 to
   96,032 bytes** for the tested open line and **228,192 to 96,064 bytes** for
   the closed line: **56–58% less**. All 4,000/4,001 output points matched bit
   for bit. Four alternating warmed trials measured median CPU around **36 to
   16 microseconds/open line** and **47 to 16 microseconds/closed line**.

   Keep buffers primitive into simplification/encoding; repeatedly converting
   back into objects would surrender savings. Reuse worker-local capacity but
   bound unusually large scratch buffers. Preserve saddle topology, point/line
   order, rounding, closure, clipping and glacier boundaries. This reduces GC
   work; the prototype is not a full pipeline replacement or end-to-end speed test.

5. **Test archive-handle rotation at shard boundaries while retaining warm application state.**

   `FileRangeSource` holds a positional FileChannel until the elevation store
   closes at JVM exit. Output writers already close per shard; PBF scanners close
   each pass. The long-lived PMTiles handle is therefore a specific target for
   resident-cache investigation. The original run's resident cache reached about
   7.8 GiB, and whole-worker closure released that pressure.

   Add a quiescent archive-handle reopen step after shard processing finishes,
   retaining decoded grids, PMTiles directory metadata and regional indexes.
   Coordinate all in-flight reads before closing a shared channel. This could
   avoid cache accumulation and repeated JVM/global-index startup, but loss of
   useful disk locality could also reduce speed.

   **This is less verified than the first four.** Existing measurements cover
   whole-worker closure, not isolated handle closure. Run successive real shards
   for several hours, measuring resident cache, standby, available RAM, I/O and
   throughput. Closing a handle is not a contractual global cache cap. Keep
   current guards/recycling until this experiment succeeds. Windows manages
   file caching per file object; see
   [Microsoft's description](https://learn.microsoft.com/en-us/windows-server/administration/performance-tuning/subsystem/cache-memory-management/).

## Limits and rejected shortcuts

Research JVMs used JDK 21, at most two reported processors and 1–3 GiB heap
limits. Probes were serialized while the planet continued. Global-load and CLI
trials had a 3 GiB available-memory guard. CPU microtimings exclude other GC and
compiler threads. Post-GC heap deltas are approximate retained models, not peak
RSS or a complete production budget. No probe forced GC in the planet process.

The producer uses 8 MiB G1 regions. Initial smaller-heap probe defaults made
1 MiB float arrays humongous and inflated used-heap measurements. Quoted cache
trials were corrected to 8 MiB regions, and distinct arrays were counted by
identity. Shrinking the heap without checking region ergonomics can distort
results; this is not evidence that planet growth was fragmentation. See
[Oracle's G1 guide](https://docs.oracle.com/en/java/javase/21/gctuning/garbage-first-g1-garbage-collector1.html).

A blind packed-coordinate-factory switch was not beneficial: the loaded/indexed
model increased from about 1.09 GiB to 1.33 GiB post-GC under matching capped-JVM
settings, with identical feature/coordinate counts. Its retention cause was not
profiled further. Do not adopt this as a presumed saving. The 64-entry cache also
caused recomputation. Forced full collections, lower visual detail and reduced
neighbour buffers are not supported remedies for these findings.

Start with **1 and 2**, then integrate/prove **3 and 4**. Validate **5** before
replacing worker recycling. Benefits overlap, especially the grid optimizations;
do not add their percentages. Application reductions do not cap Windows' cache.

## Reproduction and evidence

Research sources:
[MemoryFootprintProbe.java](../tools/MemoryFootprintProbe.java),
[PrimitiveSmoothingProbe.java](../tools/PrimitiveSmoothingProbe.java),
[FusedResampleProbe.java](../tools/FusedResampleProbe.java).
They use an existing executable JAR, with no RBT data or Python analysis packages.
Select JDK 21 first; use `:` instead of `;` for classpaths on Linux/macOS:

```powershell
New-Item -ItemType Directory -Force out/memory-probe | Out-Null
javac -proc:none -cp target/vectorvector-cli.jar -d out/memory-probe tools/MemoryFootprintProbe.java tools/PrimitiveSmoothingProbe.java tools/FusedResampleProbe.java
java -Xmx2g -XX:ActiveProcessorCount=2 -XX:G1HeapRegionSize=8m -cp 'out/memory-probe;target/vectorvector-cli.jar' FusedResampleProbe
java -Xmx2g -XX:ActiveProcessorCount=2 -XX:G1HeapRegionSize=8m -cp 'out/memory-probe;target/vectorvector-cli.jar' PrimitiveSmoothingProbe
java -Xmx2g -XX:ActiveProcessorCount=2 -XX:G1HeapRegionSize=8m -cp 'out/memory-probe;target/vectorvector-cli.jar' MemoryFootprintProbe grids <your-planet.pmtiles> 128 24
java -Xmx3g -XX:ActiveProcessorCount=2 -XX:G1HeapRegionSize=8m -cp 'out/memory-probe;target/vectorvector-cli.jar' MemoryFootprintProbe osm <filtered-features.osm.pbf> <antarctica-ice.wkb> false 29 16 out/memory-probe
java -Xmx1g -XX:ActiveProcessorCount=2 -XX:G1HeapRegionSize=8m -cp 'out/memory-probe;target/vectorvector-cli.jar' MemoryFootprintProbe reload out/memory-probe/regional-29-16.bin
```

`grids` traces Alpine elevation/neighbour requests. `osm` prepares Iceland and
Himalayan research partitions and checks 27 tiles each. The binary format is
experimental, not a production input format. Synthetic resampling/smoothing
needs only the JAR; global modes require your own elevation/filtered OSM/ice data.
They are not repository-only unit tests.

Raw evidence is in `out/memory-deep-dive-20261018/` in the migration worktree:
`summary.json` records hashes/settings/results; `prepared-spatial/` and reload
logs record regional tests; `grids-*-region8.jsonl` records corrected cache tests;
`smoothing-warm.jsonl` and `fused-resample.jsonl` record alternating trials;
`cli-commands.json`, `cli-cache-comparison.json` and `cli-c*-monitor/` record CLI
checks. Datasets, prepared binary shards, generated classes and results remain
ignored local artifacts. Initial smaller-region tests are retained as superseded
evidence and are not mixed with corrected cache measurements.
