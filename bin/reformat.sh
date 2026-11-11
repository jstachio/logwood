#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$project_dir"
"${project_dir}/mvnw" --batch-mode -f "${project_dir}/pom.xml" spring-javaformat:apply "$@"
"${project_dir}/mvnw" --batch-mode -f "${project_dir}/bin/pom.xml" spring-javaformat:apply "$@"
