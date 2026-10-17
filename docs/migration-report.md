# Migration execution record

17 October 2026. The complete phased plan is in [migration-plan.md](migration-plan.md);
the feature/CLI review is in [migration-compatibility.md](migration-compatibility.md).

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
