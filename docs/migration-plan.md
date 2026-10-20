# Migration to the maintained vectorvector fork

Status: local code cutover completed 20 October 2026; remote publication pending.
See [the cutover record](migration-cutover.md) and
[completed planet validation](planet-completion-20261019.md).

Original plan, 17 October 2026. This plan migrates the validated source in
`F:/bst-rbt-vectorvector` into the Git history currently at
`C:/Users/DarkStar/Documents/GitHub/vectorvector`. It preserves the original project and the
older planet implementation before preparing a replacement branch. A full planet build is
the production-readiness gate; it does not need to precede creating a migration branch.

## Scope and operating rules

- Preserve the existing destination working tree, index, branch and remotes. It has uncommitted
  tracked and untracked work; never reset, clean, overwrite or stash that work to make migration easier.
- Keep the current F: source and its frozen pilot executable/data intact. Do not initialize Git
  there. Use a separate Git worktree for the migration branch.
- Do not push, change `main`, publish a release or replace the original directory during
  preparation. Those are separate final actions after the concrete result has been reviewed.
- Copy only inventoried source, configuration, documentation and small fixtures. Do not copy
  toolchains, Maven outputs, planet data, rendered styles, secrets, benchmark databases or caches.
- Build the destination through its documented JDK 21 helper. Record commands, exit codes,
  test counts, input identities and executable hashes. Serialize heavy validation and benchmarks.
- Preserve the upstream license/copyright and history. Clearly describe this as a substantially
  modified fork. Keep data attribution separate from code licensing.
- A new executable or changed application/input fingerprint always gets a fresh shard directory.
  Existing completed shards from a different executable are not mixed into a new run.

## Phases, gates and recovery

| Phase | Work | Exit evidence | Recovery |
|---|---|---|---|
| 1. Preserve and inventory | Save refs as a Git bundle; save tracked patch, staged patch, status, index and selected working-tree files including ignored build helpers; hash every saved file. Inventory F: migration candidates. | Bundle verifies; copied files match hashes; original checkout status/index/content remain unchanged. | Restore into a separate recovery directory from bundle plus saved files/patches; never overwrite the only copy. |
| 2. Review differences | Review older-only classes, CLI behavior, launchers and tests; document retain/replace/defer decisions and compatibility breaks. | Feature matrix accounts for every older-only source/test file and important option. | Consult the preserved older source; port individual features later with tests if required. |
| 3. Prepare isolated branch | Create `migration/planet-contours-20261017` from the recorded upstream-history commit in an F: worktree; import the candidate tree and build guidance; review removals and ignored files. | Source manifest matches import; original checkout is unchanged; branch diff contains only intended files. | Remove/abandon only the isolated branch/worktree after preserving its evidence; original checkout remains usable. |
| 4. Validate clean source | Build/test via JDK 21 helper; exercise CLI/launcher; verify Python tools; check whitespace, links, attribution and packaging; test a source-only Git checkout without local datasets/toolchains. | Unit/fixture and optional Python regressions pass; clean build succeeds; required fixtures are tracked; artifacts/data excluded. | Fix on migration branch and repeat affected checks. |
| 5. Validate migrated executable | Freeze the actual migrated JAR. Generate bounded tiles with global indexes and Antarctic WKB, compare to the existing quality output, force recovery, merge and check integrity. Prepare full-planet command/storage monitoring. | Matching expected payloads, recovery reuse, integrity, metadata and native-style output; independent input/run identities. | Retain failed logs; use a fresh run directory after code/input changes. |
| 6. Full planet production pilot | Run the frozen migrated executable, global OSM/WKB and local Mapterhorn data with bounded workers/caches and 16 GiB G1. Monitor RAM, GC, I/O and free space; resume the same run if needed. | All shards complete; merge succeeds; final archive passes integrity/count/payload sample checks; actual size and representative global quality are recorded. | Completed shards remain available; incomplete shard is rebuilt; failed merge leaves previous output intact. No publishing on failure. |
| 7. Review and publish | Final source/dependency/notice review; document version/CLI changes, known limitations and planet results. Prepare the final merge/PR/release description for review. | Reviewable commit history, passing CI on GitHub, successful planet evidence and release checklist. | Branch and old snapshot remain available. Merge/push/release require the user's instruction; do not infer publication authorization from migration preparation. |
| 8. Cut over local workspace | After validation/review, reconcile or preserve the older checkout's WIP and make the tested branch the normal development workspace. Update IDE/run paths deliberately. | Desired branch checked out without losing local work; documented new commands and rollback location. | Reopen the preserved older checkout or isolated worktree. |

## Compatibility decisions to settle during phase 2

- The migrated CLI retains the original default GeoTIFF profile while adding the RBT profile;
  old fork-specific options are not automatically compatibility promises.
- `-o` is an output **directory**, not the older wrapper's MBTiles filename. The new launcher
  is `run-planet-sharded.ps1`; the older wrapper must not silently invoke incompatible options.
- Elevation support in this migration is Mapterhorn Terrarium WEBP PMTiles plus legacy
  AW3D30/SRTM. General Terrarium PNG/MBTiles input in the older fork needs an explicit decision.
- The RBT mask comes from reference-complete OSM PBF plus optional Antarctic WKB. The older
  vector-PMTiles mask reader/classifier is a separate feature, not an equivalent replacement.
- Topology diagnostics are evaluated separately from geometry generation; do not change tested
  contour geometry simply to make the older diagnostic pass.
- Configurable arbitrary intervals, nth-line styling and the research 10 m/z14 extension are
  distinguished from the implemented RBT z9–13 profile. No untested feature is presented as shipped.
- Existing Java package/Maven coordinates can remain compatible during source migration. A new
  release version/artifact identity and GitHub release naming are final review decisions, not
  permission to publish under the upstream author's identity.

## Full planet acceptance criteria

1. Use the migrated branch's frozen JAR, not a JAR left over from the F: development workspace.
2. Record its SHA-256 and the exact command. Keep PMTiles/PBF/WKB immutable for the entire run.
3. Use a fresh output/work directory and at least the documented approximately 141 GiB working
   capacity, plus any older final archive and inputs. Recheck actual C:/F: space before starting.
4. All selected shards receive valid completion markers; no failed tiles are silently skipped.
5. Verify merged SQLite integrity, unique tile keys, expected shard/output counts and metadata;
   use bounded streaming checks and low-priority verification where Windows cache pressure warrants it.
6. Compare actual final size with the [sampled RBT comparison](contour-output-size.md). A material
   excess over the planning estimate triggers investigation rather than discarding contour detail.
7. Inspect representative lowlands, mountains, glaciers, coastlines, populated dateline areas and
   Mercator limits with the real style. Keep Greenland/Dutch source differences explicit.
8. Record sustained memory, GC, throughput and interruptions. A completed build does not by itself
   establish source accuracy or resolve documented data differences.

## Publication and attribution review

Retain `LICENSE` and the upstream project link. Explain the changed contour pipeline, Mapterhorn
support, OSM glacier/peak inputs and resumable execution in the README and release notes. Preserve
dependency license/notice texts in distributed binaries where required; audit the shaded JAR rather
than relying only on the root license. Do not put data archives or their credentials into Git.
The migration does not include map datasets, generated RBT styles or reference basemap assets.

## Progress log

- Phase 1 complete: backup bundles, exact files/index/patches and successful restore rehearsal.
  An unexpected original-checkout reversion was restored and verified; see the execution report.
- Phase 2 complete: older-only features and incompatible options are documented. Saddle handling
  was identified as a correctness gate before the planet run.
- Phases 3-4 complete: inventoried import committed as `52a92b2`; isolated source-only build,
  optional Python checks, fixtures, links and packaging inventory pass locally. Remote CI is pending.
- Phase 5 complete: saddle correction committed as `9866a3b`; full suite, real-data quality,
  native rendering, recovery and storage pilot pass. Independent full-planet deployment prepared;
  phase 6 started at 14:11 Europe/Stockholm after runtime/input identity and capacity checks.
  The full planet is running with RAM/disk guards and automatic post-build verification.
- Detailed execution evidence is kept outside Git under `out/migration-20261017/`; a concise
  migration report will record final paths, gates, compatibility decisions and remaining work.

Current progress and any deviations are in [migration-report.md](migration-report.md).

## Repository-only baseline

Building, tests, structural verification and example styling must work without
external RBT data/assets. The README's committed-fixture example and
`tools/verify_contours.py` are the default reproducible path; CI exercises both.
The checked-in standalone style depends only on generated contours. External RBT
comparisons remain optional research evidence, never a fresh-checkout prerequisite.
Production regional/planet generation still requires user-supplied elevation data;
OSM glacier/peak enrichment is optional.
