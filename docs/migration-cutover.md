# Migration cutover checklist

Updated 19 October 2026. The tested source already lives on
`migration/planet-contours-20261017`, a branch of the Git repository whose main
checkout is `C:/Users/DarkStar/Documents/GitHub/vectorvector`. Its development
worktree is `F:/vectorvector-migration-20261017`. Copying the source over the
original checkout would discard work and is unnecessary for publication.

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

## Finish production validation

The repaired producer was checkpoint-paused with 3,857/4,096 shards complete.
The separate native deployment is `F:/vectorvector-planet-native-20261019`.
Its guarded continuation audits/imports completed shards before launching native
generation. Keep the frozen JAR, inputs and command unchanged. Read
`upgrade-status.json` for handover state and then `status.json` for production
state. Preserve the old deployment as the rollback source.

Required before declaring the planet successful:

1. All 4,096 completion markers, successful merge and full raw-payload verification.
2. Independent pilot comparison and `planet-complete-and-verified` status.
3. Final archive size, representative visual review and sustained memory/throughput
   observations recorded in the migration report.

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

## Change the normal development checkout

The original checkout's uncommitted older implementation remains preserved.
Reconcile it explicitly with the compatibility decisions before changing its
branch. Keep a recoverable branch/backup of that work and verify the backup;
never reset, clean or overwrite it as a shortcut. Until reconciliation, use the
tested F: worktree for development and for a feature-branch push. Git publication
does not require moving the physical working directory first.

After reconciliation and production validation, switch the original checkout to
the reviewed branch, reopen it in the IDE, and rerun the repository-only example
there. Retain the migration worktree and old deployment until cutover is verified.
