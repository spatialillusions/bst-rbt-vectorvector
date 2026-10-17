# Build guidance

Use `./build-jdk21.ps1` for local Windows builds. Pass Maven goals through `-MavenArgs`
(for example, `./build-jdk21.ps1 -MavenArgs verify`). Use `-JavaHome` or `JDK21_HOME` when needed.
The helper checks Java 21 before invoking Maven. Do not run Maven directly unless the selected
environment has already been confirmed as JDK 21. CI selects JDK 21 explicitly.

Keep datasets, generated styles, outputs and local toolchains out of Git. Preserve uncommitted
work and use an isolated branch/worktree for migration. See `docs/migration-plan.md` and
`docs/migration-compatibility.md` for migration gates and changed CLI behavior.
