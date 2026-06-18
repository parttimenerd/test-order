#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────
#  demo · learn mode (≤20s)
#
#  Run twice. After the first run, scores exist. Show how they shift
#  the order on the second run.
# ──────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/_demo_lib.sh"

ROOT="$(repo_root)"
FIXTURE="$ROOT/test-fixtures/fixture-parameterized-tests"

cleanup() {
	rm -rf "$FIXTURE/.test-order" "$FIXTURE/target" 2>/dev/null || true
}
trap cleanup EXIT

cd "$FIXTURE"
cleanup

banner "test-order · learn mode"

step "First run — no history yet, alphabetical baseline."
slow_type "mvn -q test-order:prepare test"
pause 0.8

step "Now look at the learned scores:"
type_cmd "mvn -q test-order:show | head -15"
pause 1.5

step "Second run — scores drive the order."
slow_type "mvn -q test-order:auto test"
pause 0.8

banner "What just happened?"
step "Tests that previously caught failures or ran fast moved up."
step "Each future run refines the model."
