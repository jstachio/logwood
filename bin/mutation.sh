#!/usr/bin/env bash
# Mutation testing with PIT. Reports are in <module>/target/pit-reports/index.html.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$project_dir"
exec "${project_dir}/mvnw" --batch-mode -f "${project_dir}/pom.xml" \
  -Pmutation -pl '!example,!bench' clean verify "$@"
