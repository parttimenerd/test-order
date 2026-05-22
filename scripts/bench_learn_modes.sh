#!/usr/bin/env bash
# Benchmark test-order overhead across learn modes and baseline.
#
# Compares:
#   1) learn FULL (class-level tracking)
#   2) learn METHOD (method-level tracking)
#   3) learn MEMBER (field+method tracking, heaviest)
#   4) baseline (no test-order, plain mvn test)
#
# Usage:
#   ./scripts/bench_learn_modes.sh [--no-warmup] [MODULE] [RUNS]
#
# Examples:
#   ./scripts/bench_learn_modes.sh                          # full reactor, 3 runs
#   ./scripts/bench_learn_modes.sh cloudplatform 5          # single module, 5 runs
#   ./scripts/bench_learn_modes.sh cloudplatform/cloudplatform-core 10
#   ./scripts/bench_learn_modes.sh --no-warmup              # skip warmup runs
#   ./scripts/bench_learn_modes.sh --no-warmup cloudplatform 5

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="${SCRIPT_DIR}/.."
DEMO_DIR="${PROJECT_ROOT}/demo/dcom-presentation/cloud-sdk-java"

# Parse arguments
NO_WARMUP=false
MODULE=""
RUNS="3"

for arg in "$@"; do
    case "$arg" in
        --no-warmup)
            NO_WARMUP=true
            ;;
        *)
            # Check if arg is a number (treat as RUNS)
            if [[ "$arg" =~ ^[0-9]+$ ]]; then
                RUNS="$arg"
            elif [[ -z "$MODULE" ]]; then
                MODULE="$arg"
            else
                RUNS="$arg"
            fi
            ;;
    esac
done

if [[ ! -d "$DEMO_DIR" ]]; then
    echo "Error: demo project not found at $DEMO_DIR"
    echo "Run: git clone ... into demo/dcom-presentation/cloud-sdk-java"
    exit 1
fi

cd "$DEMO_DIR"

# Common Maven flags
COMMON_FLAGS="-Dspotless.check.skip=true -Dmaven.test.failure.ignore=true -q"

if [[ -n "$MODULE" ]]; then
    MODULE_FLAG="-pl $MODULE -am"
else
    MODULE_FLAG=""
fi

# Cleanup function: remove .test-order dirs between runs for learn modes
SETUP_CLEAN="find '$DEMO_DIR' -name '.test-order' -type d -exec rm -rf {} + 2>/dev/null; true"

echo "=== Benchmark Configuration ==="
echo "  Project: $DEMO_DIR"
echo "  Module:  ${MODULE:-<full reactor>}"
echo "  Runs:    $RUNS"
echo "  Common:  $COMMON_FLAGS"
echo ""

# Build the commands
CMD_LEARN_FULL="mvn test $MODULE_FLAG $COMMON_FLAGS -Dtestorder.mode=learn -Dtestorder.instrumentation.mode=FULL"
CMD_LEARN_FM="mvn test $MODULE_FLAG $COMMON_FLAGS -Dtestorder.mode=learn -Dtestorder.instrumentation.mode=METHOD"
CMD_LEARN_FMEM="mvn test $MODULE_FLAG $COMMON_FLAGS -Dtestorder.mode=learn -Dtestorder.instrumentation.mode=MEMBER"
CMD_BASELINE="mvn test $MODULE_FLAG $COMMON_FLAGS -Dtestorder.skip=true -DargLine=' '"

echo "Commands:"
echo "  [learn FULL]          $CMD_LEARN_FULL"
echo "  [learn METHOD]   $CMD_LEARN_FM"
echo "  [learn MEMBER]   $CMD_LEARN_FMEM"
echo "  [baseline]            $CMD_BASELINE"
echo ""

# Set warmup flag
WARMUP_FLAG="--warmup=1"
if [[ "$NO_WARMUP" == "true" ]]; then
    WARMUP_FLAG="--warmup=0"
fi

hyperfine \
    "$WARMUP_FLAG" \
    --runs "$RUNS" \
    --export-markdown "/tmp/bench-learn-modes.md" \
    --export-json "/tmp/bench-learn-modes.json" \
    --prepare "$SETUP_CLEAN" -n "learn FULL" "$CMD_LEARN_FULL" \
    --prepare "$SETUP_CLEAN" -n "learn METHOD" "$CMD_LEARN_FM" \
    --prepare "$SETUP_CLEAN" -n "learn MEMBER" "$CMD_LEARN_FMEM" \
    --prepare "true" -n "baseline (no test-order)" "$CMD_BASELINE"

echo ""
echo "Results saved to:"
echo "  /tmp/bench-learn-modes.md"
echo "  /tmp/bench-learn-modes.json"
