# Publishing and merging the contour branch

Current checkout: `C:/Users/DarkStar/Documents/GitHub/vectorvector`, branch
`planet-contours`. This is a Git worktree with the upstream history retained.
The old no-Git workspace notes are [historical evidence](history/check-in-20261017.md).
The original WIP remains on `preservation/original-checkout-20261020` and in a
verified local backup; it is not part of this publication branch.

## Local acceptance

- Destination JDK 21 clean build passed: 169 tests passed, four optional-data skips.
- Java and native packaged examples generate the same raw stored tiles from the
  committed fixture; both pass reference-free validation without RBT inputs.
- The planet completed: 4,096 shards, 23,455,012 tiles, SQLite integrity OK and
  all 1,060 independent pilot positions matched. See [completion](planet-completion-20261019.md).
- Runtime license/notice inventory covers 29 artifacts and bundled native notices.
  The [packaging check](dependency-notices.md) checks resolved coordinates and
  the actual shaded JAR, not only source files.

Build from JDK 21 (Windows users use `build-jdk21.ps1`):

```sh
mvn clean verify
python -m pip install -r tools/requirements-verify.txt
python tools/verify_dependency_notices.py
python tools/generate_example.py
python tools/generate_example.py --decoder native --out out/example-native
python tools/verify_contours.py out/example-contours/contours.mbtiles
python tools/verify_contours.py out/example-native/contours.mbtiles
python tools/compare_tiles.py out/example-contours/contours.mbtiles out/example-native/contours.mbtiles --raw
```

Use fresh example output directories; do not delete existing output to repeat a
check. The complete optional Python regression suite additionally needs the
preparation/analysis dependencies documented under `tools/`.

## Review and publish

1. Review `git diff main...planet-contours`, including [CLI compatibility](migration-compatibility.md).
   `-o` is a directory; RBT uses `ele/index/is_ice`, and the original default
   GeoTIFF profile is retained. Generic Terrarium MBTiles and old custom intervals
   are not implied compatibility promises. The 10 m/z14 extension remains research.
2. Verify that only source, fixtures, documentation and small assets are tracked.
   Keep planet data, generated databases, tools, caches and local evidence ignored.
   Never push preservation branches or all refs as a publication shortcut.
3. Push only `planet-contours` to `origin` and open a PR targeting `main`.
   The configured fork is `spatialillusions/bst-rbt-vectorvector`; `upstream`
   remains `greensopinion/vectorvector` and is not the publication destination.
4. Wait for both Linux and Windows CI on the exact PR head commit. CI includes
   JDK 21 clean verification, notice checking, operational regressions and both
   decoder examples with raw output comparison. Local Windows success does not
   establish Linux success.
5. Resolve failed checks and inspect the resulting diff before merging. Retain
   the preservation branch, local evidence and frozen planet output after merge.

No second planet build is required for documentation or notice-only changes.
A producer or geometry change needs appropriate output regression and fresh run
identity; never overwrite the completed planet's frozen executable.

## Release boundaries

A source merge is not a binary release. Keep upstream attribution and bundled
third-party texts. Choose a fork release version and record known quality limits
before publishing compiled artifacts; the current artifact is `1.0-SNAPSHOT`.
Optional Python dependencies and external data have their own licenses and are
not included in the executable's runtime inventory. The legacy `src/it/` GeoTIFF
suite is retained for manual use and is not counted as automated Maven coverage.
