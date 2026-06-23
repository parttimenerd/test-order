#!/usr/bin/env bash
# Micro-benchmark: measure where the agent's time actually goes.
#
# Runs a test suite with -Dtestorder.timing=true to get internal counters,
# then runs the same suite without the agent as baseline.
#
# Usage:
#   ./scripts/bench_agent_timing.sh [MODULE]
#
# Requires the agent jar to be built first:
#   mvn install -pl test-order-agent -am -DskipTests -Dspotless.check.skip=true

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="${SCRIPT_DIR}/.."
DEMO_DIR="${PROJECT_ROOT}/third-party/cloud-sdk-java"
AGENT_JAR="${PROJECT_ROOT}/test-order-agent/target/test-order-agent.jar"

MODULE="${1:-cloudplatform/caching}"

if [[ ! -f "$AGENT_JAR" ]]; then
    echo "Error: Agent jar not found at $AGENT_JAR"
    echo "Build it: mvn install -pl test-order-agent -am -DskipTests -Dspotless.check.skip=true"
    exit 1
fi

if [[ ! -d "$DEMO_DIR" ]]; then
    echo "Error: Demo project not found at $DEMO_DIR"
    exit 1
fi

cd "$DEMO_DIR"

# Clean test-order state
find . -name '.test-order' -type d -exec rm -rf {} + 2>/dev/null || true

OUTPUT_DIR="$(mktemp -d)/test-order-deps"
INDEX_FILE="$(mktemp -d)/test-deps.lz4"
mkdir -p "$OUTPUT_DIR"

echo "=== Agent Timing Micro-Benchmark ==="
echo "  Demo:    $DEMO_DIR"
echo "  Module:  $MODULE"
echo "  Agent:   $AGENT_JAR"
echo ""

COMMON_FLAGS="-Dspotless.check.skip=true -Dmaven.test.failure.ignore=true"

# The agent argLine — inject timing flag
AGENT_ARGS="--mode=FULL --outputDir=${OUTPUT_DIR} --indexFile=${INDEX_FILE} --autoDetectPackages=true"
AGENT_ARGLINE="-javaagent:${AGENT_JAR}=${AGENT_ARGS} -Dtestorder.timing=true -Dtestorder.learn=true"

echo "── Test 1: Baseline (no agent) ──"
echo ""
time mvn test -pl "$MODULE" $COMMON_FLAGS -DargLine=' ' -q 2>&1 | tail -3
echo ""

echo "── Test 2: With agent + timing (FULL) ──"
echo ""
rm -rf "$OUTPUT_DIR"/* "$INDEX_FILE" 2>/dev/null || true
time mvn test -pl "$MODULE" $COMMON_FLAGS -DargLine="$AGENT_ARGLINE" 2>&1 | grep -E "test-order-timing|Tests run" | head -20
echo ""

echo "── Test 3: With agent + timing (FULL) ──"
echo ""
AGENT_ARGS_FULL="--mode=FULL --outputDir=${OUTPUT_DIR} --indexFile=${INDEX_FILE} --autoDetectPackages=true"
AGENT_ARGLINE_FULL="-javaagent:${AGENT_JAR}=${AGENT_ARGS_FULL} -Dtestorder.timing=true -Dtestorder.learn=true"
rm -rf "$OUTPUT_DIR"/* "$INDEX_FILE" 2>/dev/null || true
time mvn test -pl "$MODULE" $COMMON_FLAGS -DargLine="$AGENT_ARGLINE_FULL" 2>&1 | grep -E "test-order-timing|Tests run" | head -20
echo ""

echo "── Test 4: With agent + timing (MEMBER) ──"
echo ""
AGENT_ARGS_FM="--mode=MEMBER --outputDir=${OUTPUT_DIR} --indexFile=${INDEX_FILE} --autoDetectPackages=true"
AGENT_ARGLINE_FM="-javaagent:${AGENT_JAR}=${AGENT_ARGS_FM} -Dtestorder.timing=true -Dtestorder.learn=true"
rm -rf "$OUTPUT_DIR"/* "$INDEX_FILE" 2>/dev/null || true
time mvn test -pl "$MODULE" $COMMON_FLAGS -DargLine="$AGENT_ARGLINE_FM" 2>&1 | grep -E "test-order-timing|Tests run" | head -20
echo ""

echo "── Cleanup ──"
rm -rf "$OUTPUT_DIR" "$(dirname "$INDEX_FILE")" 2>/dev/null || true
echo "Done."
