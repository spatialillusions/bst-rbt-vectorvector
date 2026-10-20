# Planet throughput work, 19 October 2026

The running repaired planet deployment remains frozen. Develop and validate in
the isolated migration worktree; retain the completed planet shards as reference.

1. Add conservative early contour rejection over the complete smoothing footprint,
   including a metadata-only constant-zero path. Keep peak generation independent.
2. Investigate maintained native WebP alternatives for Java 21 and benchmark unchanged
   source bytes. Do not select an obsolete bundled native library merely for speed.
3. Optimize finite-data smoothing without additional full-tile caches or changed
   floating-point accumulation order. Preserve the NaN-aware path.
4. Replace per-cell binary contour-level searches with a bounded integer lookup,
   retaining a binary-search fallback for unusually wide ranges.
5. Test synthetic edge cases, full JVM/Python checks, then compare exact raw tile
   payloads and memory/time on repeated old/new runs and complete regional shards.
   Serialize performance trials with planet generation; resume the frozen planet
   after the benchmark window. Record historical times separately from paired trials.

Distributed generation is future work. No changed producer will adopt the running
deployment's fingerprint or overwrite its archives.

## Progress

Implementation and JVM/Python checks pass. Java/native candidate outputs match
the independent pilot and complete Iceland shard exactly. Explorer transfer
contention invalidated the initial timing windows; the planet was resumed.
After the transfer finished, the complete clean protocol passed: 22 monitored
runs and 22 exact output comparisons. Native median wall-time reductions were
41% Iceland, 15% Tibet and 38% sparse Antarctica, at a higher bounded process RSS.
The independent pilot and full repaired glacier shard also matched. The post-test
controller restart error was corrected and the unchanged frozen supervisor was
restarted explicitly. Native remains opt-in; distributed execution remains future
work. See [results](planet-speed-results.md) for timings, memory and limitations.

## Controlled native handover

The user subsequently authorized using more RAM within the existing physical
headroom guards and switching the unfinished planet after validation. The native
handover uses a new frozen deployment at `F:/vectorvector-planet-native-20261019`;
the repaired deployment remains the unchanged rollback source.

`native_speed_validation.py` admits only the complete controlled 22-run report,
matching actual commands/monitor results, producer hashes and equal raw payload
digests. Native must improve median wall time by at least 10% in each paired
region, remain below 6 GiB RSS and retain at least 4 GiB available system RAM in
the trials. Archive classes must be unchanged to inherit the prior handle-lifetime
validation. The decoder JVM property is forwarded into fingerprint calculation.

After a completed-shard checkpoint, stop the owned supervisor/worker, preserve
all marker contents plus the old manifest/fingerprint, freeze the new producer,
and audit/copy completed archives under both deployment locks. Publish the new
fingerprint only after every imported archive passes integrity and hash checks.
The guarded continuation driver performs identity preflight before launching
native generation. The 6 GiB heap, 45-minute sessions, 3 GiB checkpoint recycle,
8 GiB resume threshold and RAM/disk stop floors remain unchanged.

All 44 Python tests pass, including native admission rejection for incomplete,
contended, slower, mismatched and memory-unsafe evidence. Operational import and
generation progress will be recorded in the new deployment's status files.
