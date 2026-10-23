# Build guidance

Use `./build-jdk25.ps1` for local Windows builds. Pass Maven goals through `-MavenArgs`
(for example, `./build-jdk25.ps1 -MavenArgs verify`). Use `-JavaHome` or `JDK25_HOME` when needed.
The helper checks Temurin 25.0.4.1+1, matching bst-rbt-planetiler, before invoking Maven.
Do not run Maven directly unless the selected environment has already been confirmed as this JDK.
CI selects the same Temurin version explicitly. `build-jdk21.ps1` forwards to the new helper.
Both local repositories use the shared account-wide installation at
`%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-25.0.4.1+1`; do not depend on a sibling checkout's JDK.

Keep datasets, generated styles, outputs and local toolchains out of Git. Preserve uncommitted
work and use an isolated branch/worktree for migration. See `docs/migration-plan.md` and
`docs/migration-compatibility.md` for migration gates and changed CLI behavior.
