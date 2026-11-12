#!/usr/bin/env bash
# Code coverage with JaCoCo. The report is in coverage/target/site/jacoco-aggregate/index.html.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$project_dir"
"${project_dir}/mvnw" --batch-mode -f "${project_dir}/pom.xml" \
  -Dcodecover=true -pl '!example,!bench' clean verify "$@"
exec java "${project_dir}/bin/src/main/java/io/jstach/script/CoverageReport.java" \
  "${project_dir}/coverage/target/site/jacoco-aggregate/jacoco.xml"
