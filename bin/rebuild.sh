#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$project_dir"
# Run from a release tag or an isolated worktree: this temporarily changes POMs.
bin/vh set pom
exec ./mvnw --batch-mode clean package -Duser.timezone=UTC -Dgpg.skip=true "$@"
