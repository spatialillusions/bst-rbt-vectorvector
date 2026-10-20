# Runtime dependency notice audit

Reviewed 20 October 2026 for the current Maven runtime dependency set. The source
project retains the upstream BSD 3-Clause license. The executable embeds the
project license and [third-party index](../THIRD-PARTY-NOTICES.md).

## Packaging

The Maven Dependency Plugin copies runtime JAR license, notice, copyright and
patent resources into a separate directory per artifact under
`META-INF/dependency-licenses/`. This avoids loss from shade's duplicate generic
`META-INF/LICENSE` names. Verbatim supplemental texts are committed under
`src/main/resources/META-INF/licenses/third-party/` for dependencies whose JARs
omit license resources. Each text's upstream URL and SHA-256 are recorded in
`META-INF/licenses/dependencies.json`, which also maps all 29 runtime coordinates.
The upstream project license is embedded at
`META-INF/licenses/vectorvector/LICENSE`.

The [Maven plugin's unpack-dependencies goal](https://maven.apache.org/plugins/maven-dependency-plugin/unpack-dependencies-mojo.html)
includes runtime/compile transitive dependencies and supports separate artifact
directories. Packaging uses no Python and downloads no extra license texts.

Both original JTS license options are retained: Eclipse Distribution License
1.0 (BSD-style) and Eclipse Public License 2.0. The BSD-style option is available;
see [JTS licensing](https://github.com/locationtech/jts/blob/1.20.0/FAQ-LICENSING.md).
The native WebP4j/libwebp/giflib notices, libwebp patent grant and SQLite wrapper's
Xerial/Zentus notices are retained separately. Runtime JAR-provided resources are
copied verbatim; supplemental source URLs are in the inventory. The annotations
13.0 POM declares Apache-2.0; its supplemental Apache license was obtained from
the upstream repository's license file rather than a nonexistent 13.0 Git tag.

## Verification and updates

After `clean verify`, run:

```sh
python tools/verify_dependency_notices.py
python -m unittest discover -s tools -p test_verify_dependency_notices.py -v
```

The standard-library verifier compares the resolved Maven runtime list with the
reviewed inventory, requires notice evidence for every artifact, verifies hashes
inside the executable JAR and checks the embedded project license. CI runs this
on both Linux and Windows. Changed dependency coordinates, missing/changed texts,
and changed project licenses fail the check. Update the inventory deliberately
when upgrading dependencies; a new dependency is not reviewed merely because it
has a POM license URL.

For a dependency update, resolve the exact runtime set, inspect its original
JAR/POM and upstream license/notice sources, retain applicable supplemental texts,
and update the source URLs, hashes and third-party index. Verify a clean package
and review any native transitive components. The check is a reproducible
packaging/inventory safeguard, not automated legal advice or a guarantee about
unreviewed future dependency versions. Test/build-only tools and optional Python
environments are not embedded in this binary and remain separate distributions.
