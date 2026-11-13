# Build tools

Use JDK 27 or newer for Maven, `javac`, `javadoc`, and these scripts. Runtime
artifacts still target Java 21; ScopedKeyValues targets Java 25.

| Command | Purpose |
| --- | --- |
| `bin/doc.sh` | Build aggregate Javadoc from `doc/overview.md`, including the version badge |
| `bin/fast-doc.sh` | The same documentation build with reactor parallelism |
| `bin/reformat.sh` | Apply Spring Java Format to the library and standalone tools |
| `bin/build-fast.sh` | Build and test with reactor parallelism, skipping Javadoc |
| `bin/analyze.sh` | Run required Error Prone/NullAway nullness checks |
| `bin/coverage.sh` | Measure test coverage with JaCoCo and print a summary; the report is in `coverage/target/site/jacoco-aggregate` |
| `bin/mutation.sh` | Run PIT mutation testing locally; reports are in `<module>/target/pit-reports` |
| `bin/vh` | Rainbow Gum's version, timestamp, and release-tag helper, adapted for Logwood |
| `bin/rebuild.sh` | Prepare the selected release version and build from a checked-out tag |

Run `bin/analyze.sh` before completing Java changes, even when tests pass.
See [AGENTS.md](../AGENTS.md) for complete-output test assertions and mutation
testing expectations.

The Java source launchers have no third-party runtime dependencies. `bin/pom.xml`
is outside the reactor and exists for editing and formatting the tools. The
version helper updates its parent version explicitly. These classes are never
included in Logwood's artifacts. Adapted Rainbow Gum tools retain the BSD-3-Clause
notice in [LICENSE](LICENSE); the logging library remains Zero-Clause BSD.

See [release.md](../release.md) for the release process.
