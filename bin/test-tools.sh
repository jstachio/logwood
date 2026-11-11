#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$project_dir"
tool_jdk="${JAVA_HOME:?Set JAVA_HOME to JDK 27 or newer}"
mkdir -p target/build-tool-tests
"${tool_jdk}/bin/javac" -d target/build-tool-tests \
  bin/src/main/java/io/jstach/script/*.java \
  bin/src/test/java/io/jstach/script/*.java
exec "${tool_jdk}/bin/java" -cp target/build-tool-tests io.jstach.script.BuildToolsTest
