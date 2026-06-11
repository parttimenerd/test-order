#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════════
# Run synthetic-history + matrix phases across ALL third-party repos in parallel
# Designed for machines with many cores (thinkstation: 128).
#
# Usage:
#   ./scripts/run_synthetic_parallel.sh [PHASE] [MAX_JOBS]
#
# PHASE defaults to "synthetic-history". Use "matrix" for the full grid.
# MAX_JOBS defaults to 32 (safe for Maven: each job uses 2-4 cores for compile)
# ═══════════════════════════════════════════════════════════════════════════════
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
THIRD_PARTY="$ROOT_DIR/third-party"

PHASE="${1:-synthetic-history}"
MAX_JOBS="${2:-32}"

echo "Running phase=$PHASE on all repos with MAX_JOBS=$MAX_JOBS"
echo "Repos: $(ls "$THIRD_PARTY" | grep -v README | wc -l) total"
echo ""

# Install plugin once before parallel runs
bash "$SCRIPT_DIR/third_party_test_plan.sh" install

run_one() {
    local repo="$1"
    local phase="$2"
    local log_file="$ROOT_DIR/target/third-party-results/parallel-${phase}-${repo}.log"
    echo "[START] $repo" >&2
    bash "$SCRIPT_DIR/third_party_test_plan.sh" "$phase" "$repo" \
        > "$log_file" 2>&1
    local rc=$?
    if [[ $rc -eq 0 ]]; then
        echo "[OK]    $repo" >&2
    else
        echo "[FAIL]  $repo (rc=$rc) — see $log_file" >&2
    fi
}
export -f run_one
export ROOT_DIR SCRIPT_DIR PHASE

mkdir -p "$ROOT_DIR/target/third-party-results"

# Find all repos that are Maven (synthetic-history and matrix only support Maven for now)
REPOS=()
while IFS= read -r repo; do
    [[ "$repo" == "README.md" ]] && continue
    [[ -d "$THIRD_PARTY/$repo" ]] || continue
    [[ -f "$THIRD_PARTY/$repo/pom.xml" ]] || continue  # Maven only
    REPOS+=("$repo")
done < <(ls "$THIRD_PARTY")

echo "Maven repos: ${#REPOS[@]}"
printf '%s\n' "${REPOS[@]}" | xargs -P "$MAX_JOBS" -I{} bash -c 'run_one "$@"' _ {} "$PHASE"

echo ""
echo "All done. Running aggregation..."
bash "$SCRIPT_DIR/aggregate_synthetic_results.sh"
