#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=cncf-common.sh
source "$SCRIPT_DIR/cncf-common.sh"

DEBUG_PORT="${DEBUG_PORT:-5005}"

cd "$PROJECT_ROOT"
exec sbt \
  -J-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:"$DEBUG_PORT" \
  "runMain $CNCF_MAIN_CLASS ${CNCF_COMMON_ARGS[*]} server"
