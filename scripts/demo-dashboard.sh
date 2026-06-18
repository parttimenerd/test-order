#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────
#  demo · dashboard (≤30s)
#
#  Run, then open the dashboard. Show what's in it.
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

banner "test-order · dashboard"

step "Run the suite once to populate the dashboard data."
slow_type "mvn -q test-order:prepare test"
pause 0.8

banner "Generate the HTML report."
slow_type "mvn -q test-order:dashboard"
pause 0.8

step "Static, self-contained HTML — no server, no daemon."
type_cmd "ls -lh target/test-order-dashboard/index.html"
pause 1.0

step "Open it in your browser:"
printf "${M}  open target/test-order-dashboard/index.html${R}\n"
pause 1.5

banner "What's inside"
step "Overview · KPIs, history, top tests"
step "Tests    · per-class detail and scores"
step "Analytics· flakiness, coverage, weight curves"
step "Weights  · genetic-optimizer evolution"
