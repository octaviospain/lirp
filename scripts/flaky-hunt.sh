#!/usr/bin/env bash
#
# flaky-hunt.sh — run the full test suite repeatedly to surface intermittent
# failures and hangs (notably the historic cross-thread transaction deadlock)
# before they reach master.
#
# The suite passes in module isolation but a rare locking interleaving could
# hang the full run only under load; a single green run therefore does not prove
# determinism. This script re-runs the suite N times, forcing re-execution each
# time, and captures JVM thread dumps if a run exceeds its wall-clock budget so a
# hang is diagnosable instead of an opaque timeout.
#
# Usage:
#   scripts/flaky-hunt.sh [iterations] [per-run-timeout-seconds]
#
# Defaults: 10 iterations, 900s per run. Override via args or the ITERATIONS /
# RUN_TIMEOUT environment variables. Stress tests run as part of the suite.
#
# Exit status: 0 if every run passed; non-zero on the first failing or hung run.

set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

ITERATIONS="${1:-${ITERATIONS:-10}}"
RUN_TIMEOUT="${2:-${RUN_TIMEOUT:-900}}"
GRADLE="${GRADLE:-./gradlew}"

echo "flaky-hunt: $ITERATIONS run(s) of '$GRADLE test', ${RUN_TIMEOUT}s budget each"

dump_threads() {
    command -v jstack >/dev/null 2>&1 || { echo "jstack unavailable — skipping thread dump"; return; }
    command -v jps >/dev/null 2>&1 || { echo "jps unavailable — skipping thread dump"; return; }
    for pid in $(jps -q); do
        echo "--- jstack $pid ---"
        jstack "$pid" 2>/dev/null || true
    done
}

for i in $(seq 1 "$ITERATIONS"); do
    echo "===== run $i/$ITERATIONS ($(date '+%H:%M:%S')) ====="
    # -k escalates to SIGKILL 30s after the initial SIGTERM so a wedged JVM cannot keep the
    # run alive past its budget; a hang still yields exit 124 and the thread-dump path below.
    timeout -k 30 "$RUN_TIMEOUT" "$GRADLE" test --rerun-tasks
    rc=$?
    if [ "$rc" = "124" ]; then
        echo "!!! run $i exceeded ${RUN_TIMEOUT}s — likely a deadlock. Thread dumps follow."
        dump_threads
        echo "!!! flaky-hunt FAILED: hang on run $i"
        exit 124
    elif [ "$rc" != "0" ]; then
        echo "!!! flaky-hunt FAILED: run $i exited $rc"
        exit "$rc"
    fi
    echo "run $i passed"
done

echo "flaky-hunt: all $ITERATIONS run(s) passed"
