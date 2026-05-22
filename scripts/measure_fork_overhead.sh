#!/usr/bin/env bash
# Measure ACTUAL per-fork overhead in a surefire-like scenario.
# Runs many short-lived JVMs (simulating reuseForks=false) and compares total time.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="${SCRIPT_DIR}/.."
AGENT_JAR="${PROJECT_ROOT}/test-order-agent/target/test-order-agent.jar"

WORK_DIR="/tmp/fork-overhead"
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR/src/com/example" "$WORK_DIR/classes"

# Generate 100 classes
for i in $(seq 1 100); do
    NEXT=$(( (i % 100) + 1 ))
    cat > "$WORK_DIR/src/com/example/Svc${i}.java" << EOF
package com.example;
import java.util.*;
public class Svc${i} {
    private int state = ${i};
    private List<String> data = new ArrayList<>();
    public int process(int input) {
        state += input;
        data.add("item" + input);
        if (data.size() > 10) data.clear();
        return compute(input) + helper(input);
    }
    private int compute(int x) { return x * state; }
    private int helper(int x) { return x + ${i}; }
    public int chain(int x) { return new Svc${NEXT}().process(x); }
}
EOF
done

# Test class that exercises all 100 classes (simulating one test in a fork)
cat > "$WORK_DIR/src/com/example/SimulatedTest.java" << 'EOF'
package com.example;
public class SimulatedTest {
    public static void main(String[] args) {
        long t0 = System.nanoTime();
        
        // Simulate: framework loads all classes
        Svc1 s1=new Svc1(); Svc2 s2=new Svc2(); Svc3 s3=new Svc3();
        Svc4 s4=new Svc4(); Svc5 s5=new Svc5(); Svc6 s6=new Svc6();
        Svc7 s7=new Svc7(); Svc8 s8=new Svc8(); Svc9 s9=new Svc9();
        Svc10 s10=new Svc10(); Svc11 s11=new Svc11(); Svc12 s12=new Svc12();
        Svc13 s13=new Svc13(); Svc14 s14=new Svc14(); Svc15 s15=new Svc15();
        Svc16 s16=new Svc16(); Svc17 s17=new Svc17(); Svc18 s18=new Svc18();
        Svc19 s19=new Svc19(); Svc20 s20=new Svc20();
        Svc21 s21=new Svc21(); Svc22 s22=new Svc22(); Svc23 s23=new Svc23();
        Svc24 s24=new Svc24(); Svc25 s25=new Svc25(); Svc26 s26=new Svc26();
        Svc27 s27=new Svc27(); Svc28 s28=new Svc28(); Svc29 s29=new Svc29();
        Svc30 s30=new Svc30(); Svc31 s31=new Svc31(); Svc32 s32=new Svc32();
        Svc33 s33=new Svc33(); Svc34 s34=new Svc34(); Svc35 s35=new Svc35();
        Svc36 s36=new Svc36(); Svc37 s37=new Svc37(); Svc38 s38=new Svc38();
        Svc39 s39=new Svc39(); Svc40 s40=new Svc40();
        
        long t1 = System.nanoTime();
        
        // Simulate: test runs with 50K method calls
        int sum = 0;
        for (int i = 0; i < 50_000; i++) {
            sum += s1.process(i) + s2.process(i) + s3.process(i) + s4.process(i);
            sum += s5.process(i) + s10.process(i) + s20.process(i) + s30.process(i);
        }
        // Some delegation
        for (int i = 0; i < 5_000; i++) {
            sum += s1.chain(i);
        }
        long t2 = System.nanoTime();
        
        System.out.printf("RESULT load=%.0f work=%.0f total=%.0f sum=%d%n",
            (t1-t0)/1e6, (t2-t1)/1e6, (t2-t0)/1e6, sum);
    }
}
EOF

echo "Compiling 100 service classes + test driver..."
javac -d "$WORK_DIR/classes" "$WORK_DIR/src/com/example/"*.java 2>/dev/null

# Pre-extract runtime jar (simulates what the Maven plugin does once before forks)
RUNTIME_JAR="$WORK_DIR/test-order-runtime.jar"
unzip -o -q "$AGENT_JAR" test-order-runtime.jar -d "$WORK_DIR" 2>/dev/null || true

AGENT_ARGS="--mode=FULL,--outputDir=/tmp/fork-deps,--indexFile=/tmp/fork.lz4,--autoDetectPackages=false,--includePackages=com.example"
if [[ -f "$RUNTIME_JAR" ]]; then
    AGENT_ARGS="${AGENT_ARGS},--runtimeJarPath=${RUNTIME_JAR}"
fi
mkdir -p /tmp/fork-deps

NUM_FORKS=50

echo ""
echo "=== Simulating $NUM_FORKS Surefire Forks ==="
echo "    Each fork: loads 40 classes, runs 50K iterations"
echo ""

# Baseline: run N forks without agent
echo "── Baseline: $NUM_FORKS forks without agent ──"
START=$(python3 -c "import time; print(int(time.time_ns()))")
for i in $(seq 1 $NUM_FORKS); do
    java -cp "$WORK_DIR/classes" com.example.SimulatedTest > /dev/null
done
END=$(python3 -c "import time; print(int(time.time_ns()))")
BASE_MS=$(( (END - START) / 1000000 ))
echo "  Total: ${BASE_MS}ms (avg $(( BASE_MS / NUM_FORKS ))ms per fork)"
echo ""

# With agent: run N forks
echo "── With agent: $NUM_FORKS forks with FULL ──"
START=$(python3 -c "import time; print(int(time.time_ns()))")
for i in $(seq 1 $NUM_FORKS); do
    java "-javaagent:${AGENT_JAR}=${AGENT_ARGS}" -cp "$WORK_DIR/classes" com.example.SimulatedTest > /dev/null
done
END=$(python3 -c "import time; print(int(time.time_ns()))")
AGENT_MS=$(( (END - START) / 1000000 ))
echo "  Total: ${AGENT_MS}ms (avg $(( AGENT_MS / NUM_FORKS ))ms per fork)"
echo ""

OVERHEAD_MS=$(( AGENT_MS - BASE_MS ))
OVERHEAD_PCT=$(( OVERHEAD_MS * 100 / BASE_MS ))
echo "── Results ──"
echo "  Baseline total:  ${BASE_MS}ms"
echo "  Agent total:     ${AGENT_MS}ms"
echo "  Overhead:        ${OVERHEAD_MS}ms (+${OVERHEAD_PCT}%)"
echo "  Per-fork extra:  $(( OVERHEAD_MS / NUM_FORKS ))ms"
echo ""

# Now try with 4 parallel forks (simulating forkCount=4)
echo "── Parallel: 4 concurrent forks, $NUM_FORKS total ──"

echo "  Baseline (4 parallel):"
START=$(python3 -c "import time; print(int(time.time_ns()))")
for i in $(seq 1 $NUM_FORKS); do
    java -cp "$WORK_DIR/classes" com.example.SimulatedTest > /dev/null &
    if (( i % 4 == 0 )); then wait; fi
done
wait
END=$(python3 -c "import time; print(int(time.time_ns()))")
BASE_PAR_MS=$(( (END - START) / 1000000 ))
echo "    Total: ${BASE_PAR_MS}ms"

echo "  With agent (4 parallel):"
START=$(python3 -c "import time; print(int(time.time_ns()))")
for i in $(seq 1 $NUM_FORKS); do
    java "-javaagent:${AGENT_JAR}=${AGENT_ARGS}" -cp "$WORK_DIR/classes" com.example.SimulatedTest > /dev/null &
    if (( i % 4 == 0 )); then wait; fi
done
wait
END=$(python3 -c "import time; print(int(time.time_ns()))")
AGENT_PAR_MS=$(( (END - START) / 1000000 ))
echo "    Total: ${AGENT_PAR_MS}ms"

PAR_OVERHEAD_MS=$(( AGENT_PAR_MS - BASE_PAR_MS ))
PAR_OVERHEAD_PCT=$(( PAR_OVERHEAD_MS * 100 / BASE_PAR_MS ))
echo "  Parallel overhead: ${PAR_OVERHEAD_MS}ms (+${PAR_OVERHEAD_PCT}%)"
echo ""

echo "── Extrapolating to 411 forks (forkCount=4) ──"
EXTRAP_BASE=$(( BASE_PAR_MS * 411 / NUM_FORKS ))
EXTRAP_AGENT=$(( AGENT_PAR_MS * 411 / NUM_FORKS ))
echo "  Estimated baseline: $((EXTRAP_BASE / 1000))s"
echo "  Estimated with agent: $((EXTRAP_AGENT / 1000))s"
echo "  Estimated overhead: $(( (EXTRAP_AGENT - EXTRAP_BASE) / 1000 ))s"
echo ""

# Breakdown: premain vs. runtime
echo "── Breakdown: What costs $(( OVERHEAD_MS / NUM_FORKS ))ms per fork? ──"
echo ""

# Measure just premain (java -version with agent)
PREMAIN_TOTAL=0
for i in $(seq 1 10); do
    S=$(python3 -c "import time; print(int(time.time_ns()))")
    java "-javaagent:${AGENT_JAR}=${AGENT_ARGS}" -version 2>/dev/null
    E=$(python3 -c "import time; print(int(time.time_ns()))")
    PREMAIN_TOTAL=$(( PREMAIN_TOTAL + (E - S) / 1000000 ))
done
PREMAIN_AVG=$(( PREMAIN_TOTAL / 10 ))

BASE_JVM_TOTAL=0
for i in $(seq 1 10); do
    S=$(python3 -c "import time; print(int(time.time_ns()))")
    java -version 2>/dev/null
    E=$(python3 -c "import time; print(int(time.time_ns()))")
    BASE_JVM_TOTAL=$(( BASE_JVM_TOTAL + (E - S) / 1000000 ))
done
BASE_JVM_AVG=$(( BASE_JVM_TOTAL / 10 ))

PREMAIN_COST=$(( PREMAIN_AVG - BASE_JVM_AVG ))
PER_FORK_OVERHEAD=$(( OVERHEAD_MS / NUM_FORKS ))
RUNTIME_COST=$(( PER_FORK_OVERHEAD - PREMAIN_COST ))

echo "  premain() cost:       ${PREMAIN_COST}ms (jar extract + reflection + transformer registration)"
echo "  Runtime cost:         ${RUNTIME_COST}ms (class loading with hook + transform + recording + verification)"
echo "  Total per fork:       ${PER_FORK_OVERHEAD}ms"
echo "  Split: premain=$(( PREMAIN_COST * 100 / PER_FORK_OVERHEAD ))%, runtime=$(( RUNTIME_COST * 100 / PER_FORK_OVERHEAD ))%"
echo ""

rm -rf "$WORK_DIR" /tmp/fork-deps /tmp/fork.lz4
echo "Done."
