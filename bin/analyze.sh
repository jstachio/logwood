#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$project_dir"
exec "${project_dir}/mvnw" --batch-mode -f "${project_dir}/pom.xml" \
  -Pnullaway -pl '!example,!bench' clean compile "$@"
