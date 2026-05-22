#!/usr/bin/env bash
# Diagnose the 50% overhead: isolate where time is spent.
#
# Strategy: Compare test run time under 4 conditions:
#   A) Baseline — no agent
#   B) Agent attached, NO-OP transformer (return classfileBuffer immediately for ALL classes)
#   C) Agent attached, filter-only (call shouldInstrument but never transform)
#   D) Agent attached, full transform (normal learn mode)
#
# This tells us:
#   B-A = JVM instrumentation infrastructure overhead (byte-copying, lock contention)
#   C-B = Filter evaluation cost
#   D-C = ASM transformation + bytecode injection cost
#   Runtime recording overhead is D - (A + transform cost)
#
# Since we can't easily build variant agents, we instead:
#   1) Run with timing diagnostics (-Dtestorder.timing=true) 
#   2) Use JFR to see where time goes
#   3) Measure per-fork JVM startup with/without agent

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="${SCRIPT_DIR}/.."
AGENT_JAR="${PROJECT_ROOT}/test-order-agent/target/test-order-agent.jar"

echo "=== 50% Overhead Root Cause Analysis ==="
echo ""

# ─── Test 1: Measure agent premain() time ───
# The agent's premain extracts a jar, reflects into UsageStore, registers transformer
echo "── Test 1: Agent premain() cost ──"
echo "   Measures: jar extraction + UsageStore config + transformer registration"
echo ""

AGENT_ARGS="--mode=FULL,--outputDir=/tmp/to-diag-deps,--indexFile=/tmp/to-diag.lz4,--autoDetectPackages=true,--projectRoot=/tmp"
mkdir -p /tmp/to-diag-deps

# Run java -version with and without agent to measure premain cost
echo "  Without agent:"
time java -version 2>/dev/null
echo ""

echo "  With agent (premain runs):"
time java "-javaagent:${AGENT_JAR}=${AGENT_ARGS}" -version 2>/dev/null
echo ""

echo "  With agent (10 runs, averaged):"
TOTAL=0
for i in $(seq 1 10); do
    START=$(python3 -c "import time; print(int(time.time_ns()))")
    java "-javaagent:${AGENT_JAR}=${AGENT_ARGS}" -version 2>/dev/null
    END=$(python3 -c "import time; print(int(time.time_ns()))")
    ELAPSED=$(( (END - START) / 1000000 ))
    TOTAL=$((TOTAL + ELAPSED))
done
AVG=$((TOTAL / 10))
echo "  Average with agent: ${AVG}ms"
echo ""

TOTAL_BASE=0
for i in $(seq 1 10); do
    START=$(python3 -c "import time; print(int(time.time_ns()))")
    java -version 2>/dev/null
    END=$(python3 -c "import time; print(int(time.time_ns()))")
    ELAPSED=$(( (END - START) / 1000000 ))
    TOTAL_BASE=$((TOTAL_BASE + ELAPSED))
done
AVG_BASE=$((TOTAL_BASE / 10))
echo "  Average without agent: ${AVG_BASE}ms"
echo "  → premain overhead: $((AVG - AVG_BASE))ms per fork"
echo ""

# ─── Test 2: Measure class loading with transform hook ───
echo "── Test 2: Class-loading with registered transformer ──"
echo "   Using a simple program that loads many classes"
echo ""

# Create a test program that loads lots of classes
TEST_PROG="/tmp/LoadClassesTest.java"
cat > "$TEST_PROG" << 'EOF'
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;

public class LoadClassesTest {
    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();
        
        // Force-load many classes (simulating Spring/OData framework init)
        List<Class<?>> loaded = new ArrayList<>();
        String[] classes = {
            "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap",
            "java.util.ArrayList", "java.util.LinkedList", "java.util.ArrayDeque",
            "java.util.HashSet", "java.util.TreeSet", "java.util.LinkedHashSet",
            "java.util.concurrent.ConcurrentHashMap", "java.util.concurrent.CopyOnWriteArrayList",
            "java.util.concurrent.atomic.AtomicInteger", "java.util.concurrent.atomic.AtomicLong",
            "java.util.concurrent.locks.ReentrantLock", "java.util.concurrent.locks.ReentrantReadWriteLock",
            "java.util.stream.Collectors", "java.util.stream.StreamSupport",
            "java.util.regex.Pattern", "java.util.regex.Matcher",
            "java.io.BufferedReader", "java.io.BufferedWriter", "java.io.StringWriter",
            "java.nio.file.Files", "java.nio.ByteBuffer", "java.nio.charset.StandardCharsets",
            "java.security.MessageDigest", "java.security.SecureRandom",
            "java.net.URL", "java.net.URI", "java.net.HttpURLConnection",
            "java.lang.reflect.Method", "java.lang.reflect.Field", "java.lang.reflect.Proxy",
        };
        
        for (String cls : classes) {
            loaded.add(Class.forName(cls));
        }
        
        long classLoadEnd = System.nanoTime();
        
        // Now do some work (simulating actual test execution with method calls)
        Map<String, List<Integer>> map = new HashMap<>();
        for (int i = 0; i < 100_000; i++) {
            String key = "key" + (i % 100);
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }
        
        // Some stream operations  
        long sum = map.values().stream()
            .flatMap(Collection::stream)
            .mapToLong(Integer::longValue)
            .sum();
        
        long workEnd = System.nanoTime();
        
        System.out.printf("CLASSLOAD_MS=%.1f%n", (classLoadEnd - start) / 1_000_000.0);
        System.out.printf("WORK_MS=%.1f%n", (workEnd - classLoadEnd) / 1_000_000.0);
        System.out.printf("TOTAL_MS=%.1f%n", (workEnd - start) / 1_000_000.0);
        System.out.printf("SUM=%d%n", sum); // prevent dead-code elimination
    }
}
EOF

# Compile the test program
javac "$TEST_PROG" -d /tmp 2>/dev/null

echo "  Without agent (5 runs):"
for i in $(seq 1 5); do
    java -cp /tmp LoadClassesTest
done | grep "TOTAL_MS" | awk -F= '{sum+=$2; n++} END {printf "  → Average total: %.1fms\n", sum/n}'
echo ""

echo "  With agent (5 runs):"
for i in $(seq 1 5); do
    java "-javaagent:${AGENT_JAR}=${AGENT_ARGS}" -cp /tmp LoadClassesTest
done | grep "TOTAL_MS" | awk -F= '{sum+=$2; n++} END {printf "  → Average total: %.1fms\n", sum/n}'
echo ""

echo "  Class-load time comparison:"
echo "  Without agent:"
for i in $(seq 1 5); do
    java -cp /tmp LoadClassesTest
done | grep "CLASSLOAD_MS" | awk -F= '{sum+=$2; n++} END {printf "    avg classload: %.1fms\n", sum/n}'

echo "  With agent:"
for i in $(seq 1 5); do
    java "-javaagent:${AGENT_JAR}=${AGENT_ARGS}" -cp /tmp LoadClassesTest
done | grep "CLASSLOAD_MS" | awk -F= '{sum+=$2; n++} END {printf "    avg classload: %.1fms\n", sum/n}'
echo ""

echo "  Work time comparison (method call overhead):"
echo "  Without agent:"
for i in $(seq 1 5); do
    java -cp /tmp LoadClassesTest
done | grep "WORK_MS" | awk -F= '{sum+=$2; n++} END {printf "    avg work: %.1fms\n", sum/n}'

echo "  With agent:"
for i in $(seq 1 5); do
    java "-javaagent:${AGENT_JAR}=${AGENT_ARGS}" -cp /tmp LoadClassesTest
done | grep "WORK_MS" | awk -F= '{sum+=$2; n++} END {printf "    avg work: %.1fms\n", sum/n}'
echo ""

# ─── Test 3: JFR profile of a real test run ───
echo "── Test 3: Breakdown estimate ──"
echo ""
echo "Given:"
echo "  - 411 test classes with reuseForks=false, forkCount=4"
echo "  - Each fork loads ~5000 classes (5287 filter calls observed)"
echo "  - Baseline: 248s, With agent: 377s → Overhead: 129s"
echo ""
echo "  Per-fork overhead: 129s × 4 (parallelism) / 411 forks ≈ 1.25s"
echo ""
echo "  Breakdown hypothesis:"
echo "    premain (jar extract + reflect): ${AVG:- ~100}ms - ${AVG_BASE:- ~50}ms = ~50ms"
echo "    filter calls (5287 × ~7ns):     ~0.04ms"
echo "    transform (300 classes × 71µs): ~21ms"  
echo "    → Infrastructure + recording:   ~1200ms (96% of per-fork overhead)"
echo ""
echo "  The 1200ms must be either:"
echo "    a) JVM class-loading serialization with transformer registered"
echo "    b) Recording calls on method entry (cold JIT)"
echo "    c) Bytecode verification of transformed classes"
echo "    d) Memory/GC pressure from larger bytecodes"
echo ""

# ─── Test 4: Compare with -XX:+PrintCompilation ───
echo "── Test 4: JIT compilation pressure ──"
echo "   Counting JIT compilations with and without agent"
echo ""

echo "  Without agent:"
java -XX:+PrintCompilation -cp /tmp LoadClassesTest 2>&1 | grep -c "%" || echo "0"
echo "  compilations"

echo "  With agent:"
java -XX:+PrintCompilation "-javaagent:${AGENT_JAR}=${AGENT_ARGS}" -cp /tmp LoadClassesTest 2>&1 | grep -c "%" || echo "0"
echo "  compilations"
echo ""

# Cleanup
rm -rf /tmp/to-diag-deps /tmp/to-diag.lz4 /tmp/LoadClassesTest.java /tmp/LoadClassesTest.class 2>/dev/null
echo "Done."
