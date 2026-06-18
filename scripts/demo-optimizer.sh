#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────
#  demo · optimizer (≤90s)
#
#  Seed 5 runs (so the optimizer has signal), then watch the
#  genetic-algorithm tune scoring weights.
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

banner "test-order · optimizer"

step "Need at least 5 runs to feed the optimizer."
mvn -q test-order:prepare > /dev/null
for i in 1 2 3 4 5; do
	printf "${G}  ▸ run %d/5${R}\n" "$i"
	mvn -q test-order:auto test > /dev/null 2>&1 || true
	pause 0.3
done
pause 0.6

banner "Run the genetic optimizer."
slow_type "mvn -q test-order:optimize"
pause 0.8

step "Optimized weights are persisted; future runs use them automatically."
type_cmd "mvn -q test-order:show-weights | head -20"
pause 1.5

banner "What it did"
step "Sampled candidate weight vectors. Crossover + mutation."
step "Picked the vector that ranks bug-catchers highest on history."
