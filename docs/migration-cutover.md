# Migration cutover checklist

Updated 20 October 2026. The development source lives on
`migration/planet-contours-20261017`, a branch of the Git repository whose main
checkout is `C:/Users/DarkStar/Documents/GitHub/vectorvector`. Its development
worktree is `F:/vectorvector-migration-20261017`. The original checkout now uses the tested `planet-contours` branch. The F: worktree
remains available for provenance and rollback; no folder overwrite was used.

## Implemented and tested

- Conservative contour rejection checks the complete smoothing footprint and
  preserves independent peak generation.
- Finite-data smoothing and bounded contour-level lookup preserve output bytes.
- Native WebP decoding is optional and participates in the shard fingerprint.
- Controlled regional trials passed: 22 runs and 22 output comparisons, with
  native median wall-time reductions of 41% Iceland, 15% Tibet and 38% sparse
  Antarctica. See [measurements and limits](planet-speed-results.md).
- A fresh Git source export built with JDK 21: 169 tests passed, four optional
  data tests skipped, no failures/errors. All 44 Python tests passed.
- Both packaged decoder examples passed reference-free validation: three tiles,
  76 contour features each, all compressed payloads identical. No external RBT
  dataset was used. The updated example driver was copied into the source export
  after its Java build; application source remained the exported `74c3caa` tree.
- All 238 application classes in the fresh JAR match the benchmarked producer.
  The clean build excludes four obsolete, unreferenced nested classes left in
  the earlier incremental build. CI now uses `clean verify` to prevent stale
  build outputs entering published artifacts.
- CI now specifies Linux and Windows, operational regression checks and explicit
  Java/native packaged examples with raw-payload comparison. Remote execution
  remains pending; local success is not a GitHub CI result.

Evidence stays outside Git in `out/release-readiness-20261019/`: source archive
identity, build/test logs, fixture outputs, class comparison and a hash-checked
backup of the original checkout's 37 modified/untracked paths. Original status,
patches, HEAD, branch, remotes and file hashes were rechecked unchanged.

## Production validation completed

The repaired producer was checkpoint-paused with 3,857/4,096 shards complete.
The separate native deployment is `F:/vectorvector-planet-native-20261019`.
Its guarded continuation imported those shards and completed the remaining 239,
merged all output and passed full verification. Keep the frozen JAR, inputs and
command unchanged. Read
`upgrade-status.json` for handover state and then `status.json` for production
state. Preserve the old deployment as the rollback source.

The completion gate passed on 19 October at 22:54:49 local; see the
[completion report](planet-completion-20261019.md). Acceptance checks were:

1. All 4,096 completion markers, successful merge and full raw-payload verification.
2. Independent pilot comparison and `planet-complete-and-verified` status.
3. Final archive size and memory observations recorded in the completion report.
   Existing representative visual checks remain regional evidence; there is no
   claim of a visual survey of every planet tile.

## Prepare publication

1. Review the branch diff, compatibility notes and upstream attribution. Finish
   the shaded binary's complete dependency-license/notice inventory; the bundled
   native WebP notices are present, but this does not close the broader binary
   notice audit. Source preparation and binary release are separate gates.
2. Build publication artifacts from clean source with JDK 21 and `clean verify`.
   Keep generated archives, datasets, local tools and evidence out of Git.
3. Finalize version and release notes with the actual planet result and known
   limitations. Review the intended GitHub remote and branch before pushing.
4. Push the reviewed feature branch when instructed, inspect both CI platforms,
   and resolve failures before merging or publishing a release.

## Local checkout cutover, 20 October 2026

The original checkout is now on `planet-contours`, based on the validated migration
history. Its older 37-path WIP was committed separately as `9ccca13` on
`preservation/original-checkout-20261020`; it was not merged into the new pipeline.
The compatibility decisions remain in [migration-compatibility.md](migration-compatibility.md).
The local `main` reference remains unchanged at `63767d8`.

Before switching, 115 existing file paths were backed up and hash-verified,
including local files that the new branch tracks. A complete Git bundle was
created and verified. The older ignored `run-wholeworld-sharded.ps1` launcher
was also backed up and moved out of the active checkout; use
`run-planet-sharded.ps1` with its documented new arguments.

Cutover evidence and rollback files remain outside Git under
`F:/vectorvector-migration-20261017/out/migration-cutover-20261020/`:

- `repository-before-cutover.bundle`: verified history/refs backup.
- `original-checkout/`: original HEAD, branch, index, status, patches and hashed files.
- `original-local-only/`: backup of the retired ignored launcher.
- Build, Python and both packaged example validation logs and the final cutover report.

The old implementation can be inspected on its preservation branch or restored
into a separate worktree from the verified bundle. Do not overwrite a dirty
working directory during rollback. Retain the F: migration worktree, frozen
planet deployments and original backups until remote integration is complete.

Validation in the destination checkout passed: JDK 21 `clean verify` reported
169 passed tests and four optional-data skips; all 44 Python tests passed. Both
packaged decoder examples passed reference-free validation (three tiles and 76
contours each), with identical raw payloads. All 238 current application classes
match the producer used in planet validation. No external RBT dataset was used
for these destination smoke tests. The final record is `cutover-report.json` in
the evidence directory.

No push, `main` merge or binary release is part of this local cutover. Remote CI
and the broader shaded dependency-notice review remain publication/release gates.
