#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$project_dir"
exec "${project_dir}/mvnw" --batch-mode --no-transfer-progress \
  -f "${project_dir}/pom.xml" -Pdoc clean install -DskipTests=true "$@"
