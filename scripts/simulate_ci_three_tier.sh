#!/usr/bin/env bash
set -euo pipefail

# Simulate a three-tier CI workflow for a Maven project using test-order.
#
# Usage:
#   scripts/simulate_ci_three_tier.sh <project-dir> [changed-classes-csv]
#
# Example:
#   scripts/simulate_ci_three_tier.sh samples/sample-basic com.myapp.service.UserService

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <project-dir> [changed-classes-csv]"
  exit 1
fi

PROJECT_DIR="$1"
CHANGED_CLASSES="${2:-}"

if [[ ! -d "$PROJECT_DIR" ]]; then
  echo "Project directory not found: $PROJECT_DIR"
  exit 1
fi

cd "$PROJECT_DIR"

echo "=== test-order three-tier CI simulation ==="
echo "Project: $PROJECT_DIR"

echo "[0/4] Optional CI warm-start"
if [[ -f .test-order/download-config.yml ]]; then
  mvn -q test-order:download || true
else
  echo "No .test-order/download-config.yml found, skipping download step."
fi

COMMON_ARGS=("-Dtestorder.mode=order")
if [[ -n "$CHANGED_CLASSES" ]]; then
  COMMON_ARGS+=("-Dtestorder.changeMode=explicit" "-Dtestorder.changed.classes=$CHANGED_CLASSES")
fi

echo "[1/4] Tier 1: affected tests"
mvn test-order:tiered-select test "${COMMON_ARGS[@]}"

echo "[2/4] Tier 2: top-scored remaining (runs only if tier 1 passed)"
mvn test-order:run-tier test -Dtestorder.tiered.currentTier=2 "${COMMON_ARGS[@]}"

echo "[3/4] Tier 3: rest (runs only if tier 2 passed)"
mvn test-order:run-tier test -Dtestorder.tiered.currentTier=3 "${COMMON_ARGS[@]}"

echo "[4/4] Done"
echo "Tier files:"
echo "  target/test-order-tier1.txt"
echo "  target/test-order-tier2.txt"
echo "  target/test-order-tier3.txt"
