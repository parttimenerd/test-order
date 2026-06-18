#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────
#  demo · tiered selection (≤45s)
#
#  Three-tier output: must-run, should-run, can-skip. Useful for CI
#  pipelines that can run a fast PR check + a full nightly.
# ──────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/_demo_lib.sh"

ROOT="$(repo_root)"
FIXTURE="$ROOT/test-fixtures/fixture-spring-boot-slices"

cleanup() {
	rm -rf "$FIXTURE/.test-order" "$FIXTURE/target" 2>/dev/null || true
}
trap cleanup EXIT

cd "$FIXTURE"
cleanup

banner "test-order · tiered selection"

step "Seed history with one full run."
slow_type "mvn -q test-order:prepare test"
pause 0.8

banner "Now ask for the tiered split."

slow_type "mvn -q test-order:tiered-select"
pause 1.5

step "On a CI fast-path you'd run only the must-run tier."
type_cmd "mvn -q test-order:tiered-select -Dtestorder.tier=must -Dtestorder.show=true | head -20"
pause 1.5

banner "Why three tiers?"
step "Must = caught a bug recently. Run on every PR."
step "Should = touches changed code. Run on PR + main."
step "Skip = unrelated to this change. Defer to nightly."
