# Runnable example

Build with JDK 21+ and install the reactor dependencies into your local Maven
repository, then run the example directly:

```sh
./mvnw -pl example -am install
java -jar example/target/balsa-example.jar
java -jar example/target/balsa-example.jar Alice
java -Dlogging.format=jsonl -jar example/target/balsa-example.jar Alice
```

`Main` calls both System.Logger and SLF4J. Its BalsaConfig gives `example.*`
loggers DEBUG by default while honoring explicit `logging.level.<logger>` properties. Two
KeyValuesProviders demonstrate ordering: defaults at -10 are overlaid by
application fields at the default order 0. The final fluent log call overlays
`component` again with `event`.

The JAR contains `META-INF/MANIFEST.MF` with Main-Class and Class-Path entries
for the configured local Maven repository, normally `~/.m2/repository`.
Maven expands that location into absolute file URLs when it builds the JAR.
Dependencies are referenced directly; install them before running. Rebuild the
example after changing the repository location or moving to another machine.
Both module-info and the classpath service descriptor register the sample SPI.

The example is part of the normal build and is never deployed by Maven's deploy
phase. The module sets `maven.deploy.skip=true` and hard-codes the deploy plugin's
`skip` configuration to true, including when a release build globally sets
`-Dmaven.deploy.skip=false`. CI checks this against a local file repository;
no example artifact is uploaded.
