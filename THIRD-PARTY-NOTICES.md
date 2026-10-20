# Third-party notices

vectorvector retains the upstream BSD 3-Clause license in LICENSE. Dependencies
retain their own licenses; this notice does not relicense them.

The executable JAR contains:

- `META-INF/licenses/vectorvector/LICENSE`: the project's upstream license.
- `META-INF/dependency-licenses/<artifact>-<version>-jar/`: verbatim license and
  notice resources extracted from resolved runtime JARs, without name collisions.
- `META-INF/licenses/third-party/`: supplemental upstream license/copyright texts
  for artifacts that do not provide those resources in their JARs.
- `META-INF/licenses/dependencies.json`: all runtime coordinates, declared license
  choices, text provenance and SHA-256 hashes.
- `META-INF/licenses/`: WebP4j, libwebp and giflib notices, including libwebp patents.

## Runtime inventory

| Artifact | License / bundled notices |
|---|---|
| `org.jetbrains.kotlin:kotlin-stdlib:2.1.0` | Apache-2.0 |
| `org.jetbrains:annotations:13.0` | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0` | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0` | Apache-2.0 |
| `info.picocli:picocli:4.7.6` | Apache-2.0 |
| `io.github.oshai:kotlin-logging-jvm:7.0.0` | Apache-2.0 |
| `org.slf4j:slf4j-api:2.0.16` | MIT |
| `org.apache.logging.log4j:log4j-slf4j2-impl:2.24.0` | Apache-2.0 |
| `org.apache.logging.log4j:log4j-api:2.24.0` | Apache-2.0 |
| `org.apache.logging.log4j:log4j-core:2.24.0` | Apache-2.0 |
| `com.twelvemonkeys.imageio:imageio-tiff:3.15.3` | BSD-3-Clause |
| `com.twelvemonkeys.imageio:imageio-core:3.15.3` | BSD-3-Clause |
| `com.twelvemonkeys.imageio:imageio-metadata:3.15.3` | BSD-3-Clause |
| `com.twelvemonkeys.common:common-lang:3.15.3` | BSD-3-Clause |
| `com.twelvemonkeys.common:common-io:3.15.3` | BSD-3-Clause |
| `com.twelvemonkeys.common:common-image:3.15.3` | BSD-3-Clause |
| `com.twelvemonkeys.imageio:imageio-webp:3.15.3` | BSD-3-Clause |
| `dev.matrixlab.webp4j:webp4j-core:2.5.0` | MIT; native libwebp BSD-3-Clause; giflib MIT |
| `com.google.protobuf:protobuf-kotlin:4.29.1` | BSD-3-Clause |
| `com.google.protobuf:protobuf-java:4.29.1` | BSD-3-Clause |
| `com.google.guava:guava:33.4.0-jre` | Apache-2.0 |
| `com.google.guava:failureaccess:1.0.2` | Apache-2.0 |
| `com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava` | Apache-2.0 |
| `com.google.code.findbugs:jsr305:3.0.2` | Apache-2.0 |
| `org.checkerframework:checker-qual:3.43.0` | MIT |
| `com.google.errorprone:error_prone_annotations:2.36.0` | Apache-2.0 |
| `com.google.j2objc:j2objc-annotations:3.0.0` | Apache-2.0 |
| `org.locationtech.jts:jts-core:1.20.0` | EPL-2.0 OR BSD-3-Clause (EDL-1.0) |
| `org.xerial:sqlite-jdbc:3.47.1.0` | Apache-2.0; bundled SQLite public-domain; Zentus BSD |

JTS is dual licensed; the EDL-1.0 BSD-style option is available, and both original
license texts are included. SQLite's Java wrapper includes both the Xerial and
Zentus notices; SQLite itself is public-domain software. The native WebP4j
binaries include libwebp and giflib components whose notices are preserved.

The inventory covers resolved Java runtime artifacts and the bundled native
components reviewed for this build. Development-only Maven plugins, tests and
optional Python environments are not embedded in the executable JAR. Their
licenses remain in their respective distributions.

See [the audit and update instructions](docs/dependency-notices.md). Re-run the
packaged-notice verifier after every dependency or packaging change. Do not
remove attribution or license texts when redistributing the executable.
