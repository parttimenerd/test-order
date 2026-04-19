#!/usr/bin/env bash
set -euo pipefail

# Run all test-order tests: unit, invoker fixtures, and end-to-end integration tests.
#
# Usage:
#   ./run-tests.sh          # run everything
#   ./run-tests.sh unit     # unit tests only
#   ./run-tests.sh it       # invoker fixture tests only
#   ./run-tests.sh e2e      # end-to-end integration tests only

cd "$(dirname "$0")"

run_unit() {
  echo "=== Unit tests ==="
  mvn -B -ntp test
}

run_it() {
  echo "=== Invoker fixture tests ==="
  mvn -B -ntp -DskipTests install
  mvn -B -ntp -Prun-its verify -pl test-order-maven-plugin
}

run_e2e() {
  echo "=== End-to-end integration tests ==="
  mvn -B -ntp -DskipTests install
  mvn -B -ntp verify -Dtestorder.it=true -pl test-order-maven-plugin
}

target="${1:-all}"

case "$target" in
  unit) run_unit ;;
  it)   run_it ;;
  e2e)  run_e2e ;;
  all)
    run_unit
    run_it
    run_e2e
    echo "=== All tests passed ==="
    ;;
  *)
    echo "Unknown target: $target (use unit, it, e2e, or all)" >&2
    exit 1
    ;;
esac
