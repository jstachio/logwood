# Documentation

`overview.md` is the source of the user guide and the overview page of the
aggregate Javadoc. JDK 27 renders its Markdown directly. The README introduces
Logwood and links to the guide; detailed configuration and SPI documentation
belong in the overview and Java source comments.

```sh
bin/doc.sh
```

Open `target/reports/apidocs/index.html`. The output contains the public APIs of
the core, SLF4J registration, and scoped-values modules. Example, benchmark, and
build-tool modules are excluded. The optional scoped-values module requires
Java 25 at runtime, while the core remains Java 21 compatible.

The build also copies `overview.md` and `llms.txt`, generates `badge.svg` with the
actual Maven version, and adds local navigation without third-party scripts.
`_VERSION_` is replaced in both the rendered guide and the downloadable source.
Keep heading names stable: the generated anchors are documentation links.

Link Java types with Javadoc links outside code fences. Source-file links should
use GitHub URLs because the published site contains documentation, not the
repository checkout. The Pages workflow publishes this directory; select
**GitHub Actions** as the repository's Pages source once in GitHub settings.
See [release.md](../release.md) for versioned release documentation.
