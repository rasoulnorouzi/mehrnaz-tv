#!/usr/bin/env bash
# tools/gw.sh <gradle args>: run Gradle for THIS checkout/worktree with at most
# BOLBOL_GRADLE_SLOTS (default 2) builds running machine-wide, and no lingering daemon.
# Why: up to 9 agents work in parallel on a 16 GB machine; each build needs ~2.5 GB.
# Never use `gradlew --stop` (it kills every agent's build).
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SLOTS="${BOLBOL_GRADLE_SLOTS:-2}"
LOCKDIR="/tmp/bolbol-gradle-locks"
mkdir -p "$LOCKDIR"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
[[ -f "$ROOT/local.properties" ]] || echo "sdk.dir=$ANDROID_HOME" > "$ROOT/local.properties"

waited=0
while true; do
  for i in $(seq 1 "$SLOTS"); do
    exec {fd}>"$LOCKDIR/slot$i.lock"
    if flock -n "$fd"; then
      (( waited > 0 )) && echo "gw.sh: got build slot $i after ${waited}s" >&2
      "$ROOT/gradlew" -p "$ROOT" --no-daemon "$@"
      rc=$?
      flock -u "$fd"
      exit "$rc"
    fi
    exec {fd}>&-
  done
  (( waited % 60 == 0 )) && echo "gw.sh: all $SLOTS build slots busy, waiting..." >&2
  sleep 5; waited=$((waited + 5))
done
