#!/usr/bin/env bash
# Detailed benchmark of learn modes: timing + index file sizes.
#
# Compares instrumentation modes by running learn, then measuring:
#   - Wall-clock time (mvn test in learn mode)
#   - Index file size (.test-order/test-dependencies.lz4)
#   - Number of tracked dependencies (via test-order:show)
#
# Modes:
#   FULL          - class-level + static field access tracking (default)
#   METHOD   - + per-test-method granularity
#   MEMBER   - + member-level (class#method, class#field) tracking
#
# Usage:
#   ./scripts/bench_learn_modes_detailed.sh [--no-warmup] [MODULE] [RUNS]
#
# Examples:
#   ./scripts/bench_learn_modes_detailed.sh                    # full reactor, 3 runs
#   ./scripts/bench_learn_modes_detailed.sh --no-warmup 1      # quick single run
#   ./scripts/bench_learn_modes_detailed.sh cloudplatform 5

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

RESULTS_DIR="/tmp/bench-learn-modes-detailed"
mkdir -p "$RESULTS_DIR"

echo "=== Detailed Learn Modes Benchmark ==="
echo "  Project: $DEMO_DIR"
echo "  Module:  ${MODULE:-<full reactor>}"
echo "  Runs:    $RUNS"
echo "  Results: $RESULTS_DIR"
echo ""

# --- Phase 1: Timing benchmark (same as bench_learn_modes.sh) ---
echo "═══════════════════════════════════════════"
echo "  Phase 1: Timing (hyperfine)"
echo "═══════════════════════════════════════════"
echo ""

SETUP_CLEAN="find '$DEMO_DIR' -name '.test-order' -type d -exec rm -rf {} + 2>/dev/null; true"

CMD_LEARN_FULL="mvn test $MODULE_FLAG $COMMON_FLAGS -Dtestorder.mode=learn -Dtestorder.instrumentation.mode=FULL"
CMD_LEARN_FM="mvn test $MODULE_FLAG $COMMON_FLAGS -Dtestorder.mode=learn -Dtestorder.instrumentation.mode=METHOD"
CMD_LEARN_FMEM="mvn test $MODULE_FLAG $COMMON_FLAGS -Dtestorder.mode=learn -Dtestorder.instrumentation.mode=MEMBER"
CMD_BASELINE="mvn test $MODULE_FLAG $COMMON_FLAGS -Dtestorder.skip=true -DargLine=' '"

WARMUP_FLAG="--warmup=1"
if [[ "$NO_WARMUP" == "true" ]]; then
    WARMUP_FLAG="--warmup=0"
fi

hyperfine \
    "$WARMUP_FLAG" \
    --runs "$RUNS" \
    --export-markdown "$RESULTS_DIR/timing.md" \
    --export-json "$RESULTS_DIR/timing.json" \
    --prepare "$SETUP_CLEAN" -n "learn FULL" "$CMD_LEARN_FULL" \
    --prepare "$SETUP_CLEAN" -n "learn METHOD" "$CMD_LEARN_FM" \
    --prepare "$SETUP_CLEAN" -n "learn MEMBER" "$CMD_LEARN_FMEM" \
    --prepare "true" -n "baseline (no test-order)" "$CMD_BASELINE"

echo ""

# --- Phase 2: Index file size comparison ---
echo "═══════════════════════════════════════════"
echo "  Phase 2: Index File Size Comparison"
echo "═══════════════════════════════════════════"
echo ""

MODES=("CLASS" "METHOD" "MEMBER")

# Header for CSV
echo "mode,index_size_bytes,index_size_human,num_index_files,total_size_bytes" > "$RESULTS_DIR/sizes.csv"

printf "%-16s %12s %10s %s\n" "MODE" "INDEX SIZE" "FILES" "TOTAL .test-order"
printf "%-16s %12s %10s %s\n" "────────────────" "────────────" "──────────" "─────────────────"

for mode in "${MODES[@]}"; do
    # Clean and run learn
    eval "$SETUP_CLEAN"
    echo -n "  Running learn $mode ... "

    mvn test $MODULE_FLAG $COMMON_FLAGS \
        -Dtestorder.mode=learn \
        -Dtestorder.instrumentation.mode="$mode" \
        > /dev/null 2>&1 || true

    echo "done."

    # Measure index file sizes
    INDEX_FILE=$(find "$DEMO_DIR" -path '*/.test-order/test-dependencies.lz4' -type f 2>/dev/null | head -1)
    if [[ -n "$INDEX_FILE" ]]; then
        INDEX_SIZE=$(stat -f%z "$INDEX_FILE" 2>/dev/null || stat --printf='%s' "$INDEX_FILE" 2>/dev/null || echo 0)
        INDEX_HUMAN=$(du -h "$INDEX_FILE" | cut -f1)
    else
        INDEX_SIZE=0
        INDEX_HUMAN="N/A"
    fi

    # Count all .test-order files and total size
    NUM_FILES=$(find "$DEMO_DIR" -path '*/.test-order/*' -type f 2>/dev/null | wc -l | tr -d ' ')
    TOTAL_SIZE=$(find "$DEMO_DIR" -path '*/.test-order/*' -type f -exec stat -f%z {} + 2>/dev/null | awk '{s+=$1} END {print s+0}' || echo 0)
    TOTAL_HUMAN=$(echo "$TOTAL_SIZE" | awk '{
        if ($1 >= 1048576) printf "%.1fM", $1/1048576;
        else if ($1 >= 1024) printf "%.1fK", $1/1024;
        else printf "%dB", $1
    }')

    printf "%-16s %12s %10s %s (%s)\n" "$mode" "$INDEX_HUMAN" "$NUM_FILES" "$TOTAL_HUMAN" "$TOTAL_SIZE bytes"

    echo "$mode,$INDEX_SIZE,$INDEX_HUMAN,$NUM_FILES,$TOTAL_SIZE" >> "$RESULTS_DIR/sizes.csv"

    # Save detailed file listing
    find "$DEMO_DIR" -path '*/.test-order/*' -type f -exec ls -lh {} \; > "$RESULTS_DIR/files_${mode}.txt" 2>/dev/null || true
done

echo ""

# --- Phase 3: Summary Report ---
echo "═══════════════════════════════════════════"
echo "  Summary"
echo "═══════════════════════════════════════════"
echo ""

# Extract timing from JSON
if command -v jq &>/dev/null && [[ -f "$RESULTS_DIR/timing.json" ]]; then
    echo "Timing (mean ± stddev):"
    jq -r '.results[] | "  \(.command): \(.mean | . * 1000 | round / 1000)s ± \(.stddev | . * 1000 | round / 1000)s"' "$RESULTS_DIR/timing.json"
    echo ""

    BASELINE_TIME=$(jq -r '.results[] | select(.command == "baseline (no test-order)") | .mean' "$RESULTS_DIR/timing.json")
    if [[ -n "$BASELINE_TIME" && "$BASELINE_TIME" != "null" ]]; then
        echo "Overhead vs baseline:"
        jq -r --argjson base "$BASELINE_TIME" '.results[] | select(.command != "baseline (no test-order)") |
            "  \(.command): +\( ((.mean - $base) / $base * 100) | . * 10 | round / 10 )%"' "$RESULTS_DIR/timing.json"
        echo ""
    fi
fi

echo "Index sizes:"
if [[ -f "$RESULTS_DIR/sizes.csv" ]]; then
    tail -n +2 "$RESULTS_DIR/sizes.csv" | while IFS=',' read -r mode size human files total; do
        printf "  %-16s %s (total .test-order: %s bytes in %s files)\n" "$mode" "$human" "$total" "$files"
    done
fi

echo ""
echo "─────────────────────────────────────────────────────────────"
echo "Mode explanation:"
echo "  FULL          → class-level + static field accesses (default, best balance)"
echo "  METHOD   → + per-test-method granularity (finer selection)"
echo "  MEMBER   → + class#method & class#field tracking (highest precision)"
echo ""
echo "Results saved to: $RESULTS_DIR/"
echo "  timing.md, timing.json  — hyperfine results"
echo "  sizes.csv               — index sizes per mode"
echo "  files_<MODE>.txt        — detailed file listings"
