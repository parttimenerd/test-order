#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────
#  demo · diagnose (≤15s)
#
#  When something looks off, run diagnose first.
# ──────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/_demo_lib.sh"

ROOT="$(repo_root)"
FIXTURE="$ROOT/test-fixtures/fixture-jacoco"

cleanup() {
	rm -rf "$FIXTURE/.test-order" "$FIXTURE/target" 2>/dev/null || true
}
trap cleanup EXIT

cd "$FIXTURE"
cleanup

banner "test-order · diagnose"

step "Run once to populate state."
mvn -q test-order:prepare test > /dev/null 2>&1 || true
pause 0.6

banner "Health report — one command."
slow_type "mvn -q test-order:diagnose"
pause 1.5

step "diagnose flags: missing coverage, stale state, unreachable tests."
step "Use it as the first stop when the order looks wrong."
