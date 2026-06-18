#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────
#  demo · multi-module (≤60s)
#
#  Reactor-aware reordering: -pl filter + cross-module aggregation.
# ──────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/_demo_lib.sh"

ROOT="$(repo_root)"
EXAMPLE="$ROOT/test-order-example"

cleanup() {
	find "$EXAMPLE" -type d -name '.test-order' -exec rm -rf {} + 2>/dev/null || true
	find "$EXAMPLE" -type d -name target -exec rm -rf {} + 2>/dev/null || true
}
trap cleanup EXIT

cd "$EXAMPLE"
cleanup

banner "test-order · multi-module reactor"

step "A multi-module project — list its modules."
type_cmd "mvn -q help:evaluate -Dexpression=project.modules -DforceStdout 2>/dev/null | grep -oE '<string>[^<]+' | head -5"
pause 1.0

banner "Run only one module — but the reactor still aggregates state."
slow_type "mvn -q -pl test-order-example-app -am test-order:prepare test"
pause 0.8

step "State is rolled up to the reactor root:"
type_cmd "ls .test-order/"
pause 1.0

banner "Now reorder the whole reactor."
slow_type "mvn -q test-order:auto test"
pause 0.8

step "Modules and tests inside them are both reordered."
step "Per-module .test-order/ stays in sync via the aggregated root."
