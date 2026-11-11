# Releases and documentation

Logwood follows Rainbow Gum's `bin/vh` process rather than the Maven release
plugin. Build with JDK 27 or newer, including for Java 21 artifacts.

## Versions

The first selected release is `1.0.0`, stored with its reproducible-build epoch
in `version.properties`. The development POM is `1.1.0-SNAPSHOT`, ahead of that
release as required by the helper. `bin/vh release` temporarily changes all
reactor versions and the standalone `bin/pom.xml` to the selected release.

```sh
bin/vh current
bin/vh get pom
bin/vh set current 1.0.0
bin/vh set pom 1.1.0-SNAPSHOT
```

`set current` updates the release timestamp. Commit the chosen
`version.properties` and development POMs before preparing a release. Each new
major/minor release needs a later development snapshot; patch releases can
reuse that snapshot. Documentation examples use the development version until
the first release is published.

## Validate and prepare

```sh
bin/reformat.sh
./mvnw clean verify
bin/analyze.sh
bin/test-tools.sh
bin/doc.sh
```

Inspect the aggregate guide at `target/reports/apidocs/index.html`. Check the
version badge, module links, and downloadable Markdown. Library artifacts keep
their own source and Javadoc JARs; the aggregate covers all three public modules.

When ready, from a clean, committed checkout:

```sh
bin/vh release
```

This command creates an annotated local `v1.0.0` tag on the committed source,
then prepares release POMs using the timestamp in `version.properties`. Do not
commit those temporary POMs. No release tag or publication is created by a
normal build or documentation build.

## Publish

Configure a Central Portal token as Maven server `central`, and a GPG signing
key in the publishing environment. Namespace ownership for `io.jstach.logwood`
must be verified with Sonatype. The release profile generates source/Javadoc
JARs and signatures and uploads a bundle for manual publication in Central:

```sh
./mvnw clean deploy -Ddeploy=release -Duser.timezone=UTC
```

The example, benchmark, and build-tool artifacts are excluded. Check the
validated bundle in Central Portal before publishing it. The publishing
configuration follows [Sonatype's Maven instructions](https://central.sonatype.org/publish/publish-portal-maven/).

After publication, restore only the temporary POM edits, then push the prepared
tag and development branch:

```sh
git restore --source=HEAD -- pom.xml ':(glob)**/pom.xml'
git push --follow-tags origin main
```

## Documentation hosting

The Pages workflow builds snapshots on JDK 27 and publishes the aggregate
directory, including `badge.svg`, `overview.md`, and `llms.txt`, at
`https://jstach.io/logwood/`. Select **GitHub Actions** as the repository's Pages
source once in GitHub settings. The workflow can also be run manually.

For a release, build documentation from the release tag with its release POMs
(`bin/vh set pom`, then `bin/doc.sh`) and copy the resulting aggregate into the
versioned documentation tree in `jstachio.github.io`, following Rainbow Gum's
`doc/<project>/<version>/apidocs` layout. Update that site's `current` link after
the release is published. Snapshot documentation remains separate.

## Reproduce a release

Check out the release tag in an isolated worktree and run:

```sh
bin/rebuild.sh
```

This restores the selected release versions and fixed timestamp before building.
It does not publish or push. Build-tool and badge checks run in temporary files;
their integration test creates tags only in an isolated temporary Git repository.
