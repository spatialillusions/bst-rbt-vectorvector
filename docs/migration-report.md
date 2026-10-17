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

The first source-only build reported 147 tests, zero failures/errors and four data-dependent or
diagnostic skips. All 258 rebuilt project classes match the corresponding F: frozen pilot classes
byte for byte. The older build directory contained 19 additional stale classes; the fresh build
  does not carry those into the migrated artifact. The helper's corrected invocation completed
  `verify` with exit 0: 143 tests passed and four skipped. Python syntax, tracked-file inventory,
  whitespace and local Markdown links pass; generated-evidence links were converted to plain
  local paths so they do not become broken GitHub links. Six Python regressions are checked
  separately from Maven. Linux/GitHub CI remains a later remote-review gate.

The older saddle-cell regressions exposed a correctness gap in both contour generators. This
is a **planet gate**, not a deferred compatibility flag. It will be handled in a separate commit,
with focused failing/passing tests and representative output validation. Until that gate is
satisfied, do not freeze a planet executable or start the full production pilot.

No remote branch, PR, release or `main` merge has been published. Full planet execution and
local workspace cutover remain later gated phases of the migration plan.
