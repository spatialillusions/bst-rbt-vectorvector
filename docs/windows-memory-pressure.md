# Windows memory pressure during the planet build

## Observed stop on 17 October 2026

The first attempt ran from 14:11 to 17:56 Europe/Stockholm. The monitor stopped
its Java child after five consecutive samples below 1,024 MiB available physical
memory. This was an intentional guard stop, not a JVM out-of-memory error.
797 completed shards survived and were reused by attempt two at 20:21.

The first attempt's 13,339 telemetry samples show:

| Point | Java working set GiB | Available physical GiB | Resident system cache GiB |
|---|---:|---:|---:|
| About 10 minutes | 8.87 | 8.88 | 0.49 |
| About 1 hour | 8.82 | 6.84 | 2.09 |
| About 2 hours | 9.15 | 4.56 | 3.91 |
| Just before final expansion | 8.81 | 1.76 | 7.75 |
| Lowest available-memory sample | 10.27 | 0.29 | 7.76 |

Java working set fluctuated, with an overall peak of 12.10 GiB; it did not show
a continuous climb comparable to the cache. Post-GC heap minima after startup
were approximately 2.1?2.6 GiB in successive half-hour windows. These observations
do not establish the absence of a leak, but sustained resident file-cache growth
plus the final JVM expansion explain the observed exhaustion of physical headroom.
Java logged 103.64 GiB of logical reads. Attribution to a particular cached file
was not captured, so the cache growth is strongly consistent with the archive
scan rather than proven to belong exclusively to it.

Windows' resident system cache is different from the standby list. Standby pages
are already included in available memory; the explanation is not merely that
Windows filled otherwise-unused RAM with reclaimable standby data. See
[Microsoft's cache/memory-manager description](https://learn.microsoft.com/en-us/windows-server/administration/performance-tuning/subsystem/cache-memory-management/).

The old telemetry recorded only the Java process and whole-machine counters.
It cannot retrospectively identify other applications at the stop. A new live
snapshot found 50 Code.exe processes with about 5.8 GiB combined working sets.
Those totals include shared pages and are not unique physical usage. This is
present-day evidence of competing desktop demand, not proof of its earlier size.

## Mitigation and its limits

At 22:11 Europe/Stockholm, attempt two's Java PID 15796 was changed from memory
priority 5 (normal) to 2 (low), verified through GetProcessInformation. This is a
reversible per-process retention hint. CPU priority, heap limit, workers, runtime,
inputs and contour parameters were unchanged. The immutable runner was not edited.
The 1 GiB memory guard and disk guard remain active.

[Microsoft documents ProcessMemoryPriority](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-setprocessinformation)
as a hint for trimming lower-priority pages, including background file-scanning
workloads. It is not a cache-size cap, does not immediately evict existing cache
pages, and can cause more paging. Treat the live change as an operational trial:
several minutes without a stop cannot prove a multi-day run is safe or establish
its performance impact across different terrain.

The frozen runtime continues to record its existing counters. A separate read-only
observer now records top processes, application groups, system commit, kernel pools,
modified pages, standby pages and paging activity every 15 seconds. It holds the
original process handle and exits when that process ends, avoiding PID reuse.
It does not terminate applications or modify priorities. Inaccessible system
processes are counted and skipped; grouped working sets are not unique totals.

Local evidence is retained under `F:/vectorvector-planet-20261017/`:

- `monitor/generation-0001/samples.json`, `result.json`, `run.log`: original stop.
- `gc.log.0`: original GC history (retain with operational evidence).
- `memory-investigation/priority-change.json`: before/after priority audit.
- `memory-investigation/live-attribution.jsonl`: ongoing system/process samples.
- `memory-investigation/observer-runtime/`: copied observer scripts.

## Future runs

The development monitor now accepts an opt-in early pressure response:

```powershell
python tools/monitor_job.py --out out/measured-planet --cwd . `
  --min-available-mib 1024 --min-disk-free-gib 20 `
  --low-memory-priority-on-pressure-mib 4096 -- java <your JVM and CLI arguments>
```

After five samples below 4 GiB available, it lowers only its own child to memory
priority 2. The stop floor still applies. The pressure threshold must exceed the
stop floor; default behavior is unchanged unless the option is supplied. Existing
`--low-memory-priority` and marker-based modes still use priority 1. No system-wide
cache settings, standby purges, power settings or unrelated processes are changed.
The active frozen runner does not receive new development flags automatically.

For attribution alongside any existing process:

```powershell
python tools/watch_memory.py --pid <java-pid> --out out/memory.jsonl
```

Use a fresh output file. Keep unused IDE/browser sessions closed when practical.
Before adopting a smaller heap or fewer workers, measure sustained dense-terrain
throughput and peak live memory. A 10?12 GiB heap cap is a candidate for testing,
not an established safe replacement for the frozen 16 GiB configuration. Both
resident file cache and other applications must fit alongside the JVM; heap size
alone is not a whole-machine memory budget.

## Validation of the development tools

- Disposable eight-second child: forced early-pressure threshold applied memory
  priority 2 and exited successfully; no memory-floor stop occurred.
- Disposable child with an intentionally unreachable stop floor: the original
  five-sample stop still fired and reported `memory_floor_stop=true`.
- Read-only observer smoke check recorded the target Java process and system
  memory counters; the ongoing observer writes attributable process groups.
- These checks prove the controls and telemetry work. They do not prove the
  operational mitigation eliminates long-run memory pressure or preserves speed.

## Bounded sessions deployed on 18 October 2026

The user requested a stop, a stronger fix, and a restart. Attempt two was stopped
at 09:52 Europe/Stockholm with 1,646 completed shards preserved. The low-retention
priority trial had reduced the cache growth compared with attempt one, but the
observer still saw only 1.6 GiB available at its overnight minimum. It was not a
hard memory bound. Closing the JVM restored approximately 17.4 GiB available.

A new supervisor now limits the lifetime of generation workers rather than
letting their archive handles and caches accumulate for the full planet:

- After 45 minutes, request recycling and wait for a newly completed shard.
- After five samples below 3 GiB available, request recycling earlier.
- Wait at most two minutes for that checkpoint. On timeout, the unfinished
  shard is discarded/rebuilt by the existing resume logic; published shards stay.
- Stop only the owned JVM, then wait for at least 8 GiB available continuously
  for ten seconds before starting a fresh worker. Recovery waits stop safely
  after 20 minutes if other machine activity prevents adequate headroom.
- The 1 GiB memory stop and 20 GiB disk stop remain. A memory-floor stop may
  recover automatically, but three consecutive floor stops or two sessions
  without new completed shards stop the supervisor for investigation. Unexpected
  failures and disk-floor stops do not auto-retry.
- At 6 GiB available, the monitor can lower retention priority to 2 before
  recycling is necessary. On merging, the existing log marker switches priority
  to 1 even if the earlier pressure response already applied priority 2.
- Once all shards exist at session start, scheduled recycling is disabled for
  merging. Full merge verification and the original independent QA sample remain
  mandatory before reporting completion.

A race may start the next shard between marker publication and the monitor's
next sample. That shard has no completion marker and is regenerated. This is
checkpoint-aware interruption using the existing recovery contract, not a claim
that the JVM receives a graceful per-shard stop command.

The producer JAR, exact Java argv, data identities, shard fingerprint and output
parameters are unchanged. The new operational scripts are isolated under
`F:/vectorvector-planet-20261017/runtime-supervised-v1/`, with SHA-256 identities
in `operations.json`. Original `runtime/` and `manifest.json` remain intact.
Original GC logs and all preserved completion stamps were copied into the new
operational directory before restart. Supervisor locking shares the original
`runner.lock` so old and new runners cannot run concurrently.

The current deployment's restart command is now:

```powershell
python F:/vectorvector-planet-20261017/runtime-supervised-v1/planet_runner.py F:/vectorvector-planet-20261017
```

Use this supervisor for subsequent resumes; the old `runtime/run.py` does not
provide bounded sessions. `--check-only` validates frozen input/runtime and
supervisor identities without starting generation. `--verify-only` is reserved
for completed generation with an interrupted verification stage.

Source is in `tools/planet_runner.py`, `tools/monitor_job.py`, and
`tools/recycle_policy.py`. The supervisor is an operational tool for an already
prepared frozen deployment: it expects the saved `java_command`, `immutable_files`,
`quality_reference` and `quality_tiles` in `manifest.json`, plus the original
`runtime/verify_shard_merge.py`. It does not construct those inputs or download
reference data. `quality_reference` is our independent pilot output, not RBT.

Validation before deploying:

- Five pure policy regressions cover disabled behavior, consecutive-sample
  hysteresis, time requests, checkpoint waiting and timeout fallback.
- With only committed elevation fixtures, a real JAR run was interrupted at a
  checkpoint and resumed. Both preserved stamps were unchanged; all 284 raw
  tile keys/payloads matched an uninterrupted build; SQLite integrity passed.
- A complete miniature frozen deployment used the actual supervisor to recycle,
  resume, merge, check every merged payload against four shards, then compare all
  284 tiles with the independent baseline. It reached
  `planet-complete-and-verified` after two worker sessions.
- Evidence is in `out/recycle-recovery-20261018/` and
  `out/recycle-supervisor-20261018/` in the migration worktree.

This bounds accumulation between worker restarts; it does not impose a global
Windows cache cap. Startup reloads the OSM indexes and may redo a small amount of
unfinished work at each restart. Planet-scale throughput impact and behavior
through multiple real recycling sessions still need observation.
