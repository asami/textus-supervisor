#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

CNCF_MAIN_CLASS="${CNCF_MAIN_CLASS:-org.goldenport.cncf.CncfMain}"
CNCF_SAMPLES_ROOT="${CNCF_SAMPLES_ROOT:-}"
if [[ -z "$CNCF_SAMPLES_ROOT" ]]; then
  if [[ -n "${CNCF_BIN:-}" ]]; then
    CNCF_SAMPLES_ROOT="$(cd "$(dirname "$CNCF_BIN")/.." && pwd)"
  elif [[ -d "/Users/asami/src/dev2026/cncf-samples/versions" ]]; then
    CNCF_SAMPLES_ROOT="/Users/asami/src/dev2026/cncf-samples"
  fi
fi
CNCF_VERSION_FILE="${CNCF_VERSION_FILE:-${CNCF_SAMPLES_ROOT:+$CNCF_SAMPLES_ROOT/versions/cncf-version.conf}}"
if [[ -z "${CNCF_VERSION:-}" ]]; then
  if [[ -n "$CNCF_VERSION_FILE" && -f "$CNCF_VERSION_FILE" ]]; then
    CNCF_VERSION="$(tr -d '[:space:]' < "$CNCF_VERSION_FILE")"
  else
    CNCF_VERSION="0.5.0"
  fi
fi
export CNCF_SAMPLES_ROOT
export CNCF_VERSION
CNCF_SERVER_PORT="${CNCF_SERVER_PORT:-19532}"
CNCF_HTTP_BASEURL="${CNCF_HTTP_BASEURL:-http://127.0.0.1:$CNCF_SERVER_PORT}"
CNCF_LAUNCHER="${CNCF_LAUNCHER:-$PROJECT_ROOT/bin/launcher}"
CNCF_LAUNCHER_CACHE="${CNCF_LAUNCHER_CACHE:-$PROJECT_ROOT/.cache/coursier}"
CNCF_RUNTIME_CLASSPATH_FILE="${CNCF_RUNTIME_CLASSPATH_FILE:-$PROJECT_ROOT/target/cncf.d/runtime-classpath.txt}"
SIMPLEMODELING_REPOSITORY="${SIMPLEMODELING_REPOSITORY:-https://www.simplemodeling.org/repository/maven}"

CNCF_COMMON_ARGS=(--discover=classes)
