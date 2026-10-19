# Migration execution record

**19 October recovery:** the [glacier topology repair report](glacier-topology-recovery.md)
records the reproduced Antarctic failure, Planetiler comparison, validated fix
and guarded continuation to `F:/vectorvector-planet-repaired-20261019`. Its
`upgrade-status.json` tracks adoption; `status.json` tracks generation after
the successful import. Earlier worker-specific log links are historical.

17 October 2026. The complete phased plan is in [migration-plan.md](migration-plan.md);
the feature/CLI review is in [migration-compatibility.md](migration-compatibility.md).

**18 October continuation:** the [memory implementation and validation](memory-implementation-results.md)
records the newer isolated producer, byte-identical old/new comparisons, rejected
cache/traversal iteration, audited preservation of 1,698 shards and the new active
deployment at `F:/vectorvector-planet-optimized-20261018`. Use its frozen
`operations/planet_runner.py`, not the superseded old resume commands below.
Full-planet generation, final verification and final integration remain pending.

## Preserved work

- Original Git history base: `63767d8f6200606984bbbb9d8f724473f228e884`.
- Older working implementation: local branch `preservation/older-planet-20261017`, commit `dab55fa`.
  Includes its uncommitted source/tests and ignored build guidance/launchers. It was not pushed.
- Exact pre-migration file bytes, index, staged/working patches, refs bundle and WIP bundle:
  `F:/bst-rbt-vectorvector/out/migration-20261017/`.
- A separate restoration rehearsal verified every saved file and reproduced the original
  working-tree/index status. Datasets and outputs remain at their existing locations.

An isolation check after worktree creation found the original checkout at clean upstream HEAD
instead of its initial WIP state; the cause was not established. Its `main` commit had not moved.
Only after checking that this was a clean unchanged HEAD, the exact saved files/deletions and
index were restored. All saved file hashes, working status and index bytes then matched the
initial snapshot. This event and restoration checks are retained in `original-restoration.json`.
Subsequent phases recheck the original checkout rather than assuming isolation.

## Isolated import

- Worktree: `F:/vectorvector-migration-20261017`.
- Branch: `migration/planet-contours-20261017`, initially based on `63767d8`.
- Imported 209 inventoried files; original tracked IDE code styles and Maven JVM settings were
  retained. No planet data, toolchains, generated styles or benchmark output was copied.
- Added a portable JDK 21 build helper and checked-in build guidance. Corrected its native-stderr
  handling after a harmless Mockito notice was treated as a PowerShell error; Maven exit status
  determines success.
- Added explicit preservation of legacy `test-data/` and `test-output/` ignore rules, so eventual
  local cutover cannot accidentally expose those existing datasets as new source files.
- Repository-local attributes keep text LF and small binary fixtures binary; global Git settings
  are not changed.

## Validation gates

The source-only import build completed through the corrected helper with exit 0: 143 tests
passed, four skipped, zero failures/errors. All 258 rebuilt project classes match corresponding
F: frozen pilot classes byte for byte. The older build directory contained 19 additional stale
classes; the fresh artifact does not carry them forward. Import commit: `52a92b2`.

The [saddle-cell correction](saddle-cell-correction.md) is separate commit `9866a3b`. Four focused
regressions failed before the fix. The complete corrected suite reports **152 tests: 148 passed,
four skipped**, zero failures/errors; all five focused tests and four upstream terrain goldens pass.

Python syntax, tracked-file inventory, whitespace and local links pass. Ignored generated-output
links were converted to plain local paths for GitHub. Eleven optional Python regressions pass,
including five tests for the bounded full merge verifier. Linux/GitHub CI remains a remote gate.

## Migrated executable pilot: passed

Frozen JAR SHA-256:
`7455d9e4c95b6e0ccfc628d79df4256a0780b02d56d5e3a541f8bb558301a4f8`.
Real-data evidence is under `out/migration-20261017/validation/` in the original F: workspace.

- Global-index quality generation completed in 60.66 seconds with 4,686.8 MiB peak RSS.
- **229,956 contour features and 8,243 buffered peak occurrences** pass schema checks. Peak
  layer bytes are unchanged in all 1,060 selected tile positions.
- The saddle correction changes 166 of those tile payloads and joins formerly fragmented paths.
  It is not presented as byte-identical arbitrary-terrain output. The real-style Iceland and
  Greenland images are pixel-identical to the earlier candidate; Alps and Matterhorn differ in
  eight pixels each out of 491,520. No visible degradation was found in the inspected renders.
- All 188 flagged boundary edges were reviewed geometrically: zero elevation-matched strip
  length falls outside the neighbor's one-pixel buffered geometry. The previous small ice-split
  discrepancy remains 71.56 extent units in aggregate; it did not worsen.
- Forced interruption/resume reused an unchanged completed shard. All 160 resulting raw tile
  payloads match the quality build. The new full copy-order verifier also passed on those two
  completed shard databases and their merged archive.
- The same 11,400-position global storage sample estimates **51.92 GiB** for the archive and
  **140.68 GiB** working capacity including margin/scratch. Keep the rounded 141 GiB budget.
  Merge bytecode is unchanged from the imported implementation; the prior 40 GiB test remains
  applicable. The new estimate remains smaller than the sampled RBT contour-only archive.

## Long-run controls and prepared deployment

The portable environment helpers now tolerate a missing `.tools` directory and retain an
external JDK/Maven setup. Windows launcher dry run and Git Bash JDK 21 checks pass. Launcher
rebuilds use the checked-in build helper.

The monitor can stop only its own child after five low-disk samples, in addition to the existing
RAM guard. A normal child exits successfully; an intentionally impossible disk reserve stops
the disposable test child and records `disk_floor_stop=true`. No user process is stopped.

The full copy-order verifier opens at most two databases at once, checks destination integrity
and its unique index, validates every completion stamp, and compares **every raw key/payload**
against the frozen merger's y/x-shard and rowid copy order. Five tests cover correct copying,
missing markers, changed payloads, extra rows and unexpected shard counts. Verification never
deletes an archive. This avoids opening thousands of simultaneous shard connections.

An independent deployment is prepared at `F:/vectorvector-planet-20261017`, with copied and
hashed runtime files, filtered OSM/WKB and pilot-reference inputs. The 663 GB Mapterhorn archive
stays read-only on D:. No build artifacts or test data are committed to Git. Its immutable-input
check passes. Production RAM/disk guards are 1,024 MiB available and 20 GiB free on output/temp
volumes; the frozen runner records commands/status and supports exact resume and verification-only
retry. It automatically verifies the complete merge and compares the 1,060 pilot positions after
generation. Full-planet completion is still required before release.

## Phase 6: full planet running

Started **17 October 2026 at 14:11 Europe/Stockholm** with frozen runtime SHA above. The runner
is a hidden independent process, so closing this chat does not stop it. It uses eight workers,
16 GiB G1 and 64 by 64 z6 partitions for RBT z9-13. No 10 m/z14 output is enabled.

Local operational files:

- Live stage: `F:/vectorvector-planet-20261017/status.json`.
- Telemetry: `F:/vectorvector-planet-20261017/monitor/generation-0001/latest.json`.
- Generation log: `F:/vectorvector-planet-20261017/monitor/generation-0001/run.log`.
- Frozen command and identities: `F:/vectorvector-planet-20261017/manifest.json`.
- Output/shards: `F:/vectorvector-planet-20261017/tiles/`.

Resume after a recorded stop with the same Python and runner:

```powershell
& 'C:\Users\DarkStar\AppData\Local\Programs\Python\Python314\python.exe' F:\vectorvector-planet-20261017\runtime\run.py
```

The runner rejects a concurrent invocation and changed runtime/input identities. Use
`--verify-only` only after generation has completed and a later verification stage stopped.
Do not use the development launcher or rebuild the frozen runtime for a retry. Final status
`planet-complete-and-verified` requires the complete raw-payload merge check and all 1,060 pilot
tile comparisons; `planet-generation-running` is not a success result. No planet ETA is inferred.

## Remaining gates

- Finish and review the full planet production run and its automatic integrity/payload/sample checks.
- Before binary publication, review third-party notices: the inherited shade filters exclude generic
  dependency `META-INF/LICENSE`/`NOTICE` files, and the current JAR contains only two SQLite-specific
  license resources. The root upstream license is preserved; binary notice packaging needs separate
  completion. Do not claim a release packaging review has passed.
- Run GitHub/Linux CI and finalize version/release notes after an authorized remote push.
- Review and explicitly authorize merge/publication and eventual local workspace cutover.

No remote branch, PR, release or `main` merge has been published. The original checkout's WIP
remains preserved and verified. Production corrections now live in the isolated migration worktree;
the original F: source is retained as earlier evidence, not copied over the migration branch again.

## Repository-only build, example and verification

The default example now uses only the committed PMTiles fixture, without OSM or
external RBT inputs. `tools/generate_example.py` creates a fresh output directory;
`tools/verify_contours.py` opens MBTiles read-only and streams structural/schema
checks independently of a reference archive. Its small optional dependency list
is separate from the analysis requirements. CI now generates and verifies this
example after Maven verification.

The original checked-in `styles/contours/style.json` and default TileServer config
use only generated contours/optional peaks, with no basemap, fonts, sprites or
remote style assets. RBT research comparisons remain optional; extraction and
rendering require explicitly supplied reference paths, and the comparison-style
generator no longer downloads an upstream style automatically. Historical RBT
reports are retained as provenance, not setup prerequisites.

Local verification on 17 October 2026:

- JDK 21 `verify` passed in the isolated worktree with no regional datasets:
  152 tests reported, 148 passed, four optional local-data/manual tests skipped.
- Eight self-contained Python regression tests passed, including invalid keys,
  duplicate keys, invalid index/interval/ice flag, empty output and style isolation.
- The example ran from outside the checkout using its committed input: three
  tiles, 76 contour features, and reference-free validation passed.
- TileServer GL 5.6.0 served the default config and rendered a 768?640 PNG using
  only the generated archive. The image was visually inspected; no RBT assets
  were configured or loaded. Evidence is in `out/standalone-style-check/`.

These changes affect development tools/docs/styles only. The running full-planet
job continues using its frozen runtime and independent verification inputs.
Linux/GitHub CI execution remains pending publication of the branch.

## Planet memory-pressure follow-up

Attempt one stopped on its 1 GiB physical-memory guard at 17:56 on 17 October,
with 797 completed shards. It resumed at 20:21 after frozen identity checks.
The [memory-pressure investigation](windows-memory-pressure.md) records sustained
resident Windows cache growth and the final JVM expansion. At 22:11, attempt two's
Java process received a reversible memory-priority change (5 to 2); its CPU
priority/runtime/inputs are unchanged. This is a measured mitigation trial, not a
claim of resolved long-run pressure. A separate read-only observer records other
processes and additional system counters. Development monitoring now offers an
opt-in early-pressure response, smoke-tested with disposable children. The frozen
runner and its safety floors remain unchanged.

## Bounded-worker recovery on 18 October

At the user's request, attempt two was stopped at 09:52 Europe/Stockholm with
1,646 completed shards. A checkpoint-aware recycling supervisor was implemented
and validated before deployment. It keeps the producer JAR/argv/input/shard
identities unchanged, limits generation sessions to 45 minutes, recycles early
below 3 GiB available and waits for stable 8 GiB headroom before resuming. Existing
memory/disk stop floors and full verification remain. Unexpected failures stop
rather than triggering blind retries. Two no-progress sessions or three consecutive
memory-floor stops also stop for investigation.

A real interrupted/resumed fixture build matched all 284 baseline payloads and
preserved both completed stamps. An end-to-end miniature deployment then recycled
and resumed automatically, merged four shards, verified every raw payload, matched
all 284 independent sample tiles and reported complete. Five pure policy tests passed.

The current deployment now uses the isolated SHA-checked
`F:/vectorvector-planet-20261017/runtime-supervised-v1/planet_runner.py` with the
planet directory as its argument. It shares the existing runner lock. This
supersedes the earlier operational resume command; original runtime and manifests
remain preserved. See [full details](windows-memory-pressure.md) for recovery limits
and the remaining need to observe real-session memory and throughput behavior.
