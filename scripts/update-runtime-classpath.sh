#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=cncf-common.sh
source "$SCRIPT_DIR/cncf-common.sh"

mkdir -p "$(dirname "$CNCF_RUNTIME_CLASSPATH_FILE")"
classpath="$(
  cd "$PROJECT_ROOT"
  sbt --batch 'export Runtime / fullClasspath' | awk '/^\// { print; exit }'
)"

if [[ -z "$classpath" ]]; then
  echo "Failed to resolve Runtime / fullClasspath." >&2
  exit 1
fi

printf '%s\n' "$classpath" > "$CNCF_RUNTIME_CLASSPATH_FILE"
printf 'Wrote %s\n' "$CNCF_RUNTIME_CLASSPATH_FILE"
