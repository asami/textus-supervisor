#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=cncf-common.sh
source "$SCRIPT_DIR/cncf-common.sh"

if [[ ! -s "$CNCF_RUNTIME_CLASSPATH_FILE" ]]; then
  echo "Runtime classpath is not prepared." >&2
  echo "Run: $SCRIPT_DIR/update-runtime-classpath.sh" >&2
  exit 1
fi

runtime_classpath="$(
  "$CNCF_LAUNCHER" \
    --dependency "org.goldenport:goldenport-cncf_3:$CNCF_VERSION" \
    --main-class "$CNCF_MAIN_CLASS" \
    --repository "$SIMPLEMODELING_REPOSITORY" \
    --cache "$CNCF_LAUNCHER_CACHE" \
    --resolve-only
)"
sample_classpath="$(cat "$CNCF_RUNTIME_CLASSPATH_FILE")"

exec java \
  -Dcncf.server.port="$CNCF_SERVER_PORT" \
  -Dcncf.http.baseurl="$CNCF_HTTP_BASEURL" \
  -cp "$runtime_classpath:$sample_classpath" \
  "$CNCF_MAIN_CLASS" \
  "${CNCF_COMMON_ARGS[@]}" \
  server
