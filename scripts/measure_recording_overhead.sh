#!/usr/bin/env bash
# Measure recording overhead: how much do the injected recordUsageIdFast calls cost
# when methods are called millions of times in a cold JVM (no JIT warmup)?
#
# This creates a Java program with many classes, instruments them, and measures
# the overhead of the recording calls during execution.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="${SCRIPT_DIR}/.."
AGENT_JAR="${PROJECT_ROOT}/test-order-agent/target/test-order-agent.jar"

if [[ ! -f "$AGENT_JAR" ]]; then
    echo "Error: Build agent first: mvn install -pl test-order-agent -am -DskipTests -Dspotless.check.skip=true"
    exit 1
fi

WORK_DIR="/tmp/overhead-test"
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR/src/com/example" "$WORK_DIR/classes"

echo "=== Recording Overhead Measurement ==="
echo ""
echo "Creating a workload with many instrumented classes + deep call chains..."
echo ""

# Generate 50 classes with methods that call each other (simulating real framework code)
for i in $(seq 1 50); do
    NEXT=$(( (i % 50) + 1 ))
    cat > "$WORK_DIR/src/com/example/Service${i}.java" << EOF
package com.example;

public class Service${i} {
    private int state = 0;
    
    public int process(int input) {
        state += input;
        return compute(input);
    }
    
    private int compute(int x) {
        return x * 2 + state;
    }
    
    public int delegate(int input) {
        return new Service${NEXT}().process(input);
    }
    
    public static int staticWork(int x) {
        return x + 1;
    }
}
EOF
done

# Main test driver with configurable iteration count
cat > "$WORK_DIR/src/com/example/WorkloadDriver.java" << 'EOF'
package com.example;

public class WorkloadDriver {
    public static void main(String[] args) {
        int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 1_000_000;
        
        // Phase 1: Class loading (create all service instances)
        long t0 = System.nanoTime();
        Service1 s1 = new Service1();
        Service2 s2 = new Service2();
        Service3 s3 = new Service3();
        Service4 s4 = new Service4();
        Service5 s5 = new Service5();
        Service6 s6 = new Service6();
        Service7 s7 = new Service7();
        Service8 s8 = new Service8();
        Service9 s9 = new Service9();
        Service10 s10 = new Service10();
        Service11 s11 = new Service11();
        Service12 s12 = new Service12();
        Service13 s13 = new Service13();
        Service14 s14 = new Service14();
        Service15 s15 = new Service15();
        Service16 s16 = new Service16();
        Service17 s17 = new Service17();
        Service18 s18 = new Service18();
        Service19 s19 = new Service19();
        Service20 s20 = new Service20();
        long t1 = System.nanoTime();
        
        // Phase 2: Hot loop — many method calls on instrumented classes
        long sum = 0;
        for (int i = 0; i < iterations; i++) {
            sum += s1.process(i);
            sum += s2.process(i);
            sum += s3.process(i);
            sum += s4.compute2(i);
            sum += s5.process(i);
            sum += Service6.staticWork(i);
            sum += s7.process(i);
            sum += s8.process(i);
            sum += s9.process(i);
            sum += s10.process(i);
        }
        long t2 = System.nanoTime();
        
        // Phase 3: Deep call chain (delegation across classes)
        for (int i = 0; i < iterations / 10; i++) {
            sum += s1.delegate(i);
        }
        long t3 = System.nanoTime();
        
        System.out.printf("LOAD_MS=%.1f%n", (t1 - t0) / 1e6);
        System.out.printf("HOTLOOP_MS=%.1f%n", (t2 - t1) / 1e6);
        System.out.printf("DELEGATION_MS=%.1f%n", (t3 - t2) / 1e6);
        System.out.printf("TOTAL_MS=%.1f%n", (t3 - t0) / 1e6);
        System.out.printf("ITERATIONS=%d%n", iterations);
        System.out.printf("SUM=%d%n", sum);
    }
}
EOF

# Add compute2 to Service4
cat > "$WORK_DIR/src/com/example/Service4.java" << 'EOF'
package com.example;

public class Service4 {
    private int state = 0;
    
    public int process(int input) {
        state += input;
        return compute(input);
    }
    
    private int compute(int x) {
        return x * 2 + state;
    }
    
    public int compute2(int x) {
        return process(x) + compute(x);
    }
    
    public int delegate(int input) {
        return new Service5().process(input);
    }
    
    public static int staticWork(int x) {
        return x + 1;
    }
}
EOF

# Compile
echo "Compiling..."
javac -d "$WORK_DIR/classes" "$WORK_DIR/src/com/example/"*.java 2>&1 | head -5

AGENT_ARGS="--mode=FULL,--outputDir=/tmp/overhead-deps,--indexFile=/tmp/overhead.lz4,--autoDetectPackages=false,--includePackages=com.example"
mkdir -p /tmp/overhead-deps

ITERATIONS=2000000

echo ""
echo "── Running with $ITERATIONS iterations ──"
echo ""

echo "Without agent (5 runs):"
for i in $(seq 1 5); do
    java -cp "$WORK_DIR/classes" com.example.WorkloadDriver $ITERATIONS
done | tee /tmp/overhead-noagent.txt
echo ""

echo "With agent FULL (5 runs):"
for i in $(seq 1 5); do
    java "-javaagent:${AGENT_JAR}=${AGENT_ARGS}" -cp "$WORK_DIR/classes" com.example.WorkloadDriver $ITERATIONS
done | tee /tmp/overhead-agent-me.txt
echo ""

AGENT_ARGS_FULL="--mode=FULL,--outputDir=/tmp/overhead-deps,--indexFile=/tmp/overhead.lz4,--autoDetectPackages=false,--includePackages=com.example"
echo "With agent FULL (5 runs):"
for i in $(seq 1 5); do
    java "-javaagent:${AGENT_JAR}=${AGENT_ARGS_FULL}" -cp "$WORK_DIR/classes" com.example.WorkloadDriver $ITERATIONS
done | tee /tmp/overhead-agent-full.txt
echo ""

AGENT_ARGS_FM="--mode=MEMBER,--outputDir=/tmp/overhead-deps,--indexFile=/tmp/overhead.lz4,--autoDetectPackages=false,--includePackages=com.example"
echo "With agent MEMBER (5 runs):"
for i in $(seq 1 5); do
    java "-javaagent:${AGENT_JAR}=${AGENT_ARGS_FM}" -cp "$WORK_DIR/classes" com.example.WorkloadDriver $ITERATIONS
done | tee /tmp/overhead-agent-fm.txt
echo ""

echo "═══════════════════════════════════════════"
echo "  Summary"
echo "═══════════════════════════════════════════"
echo ""
echo "Hot-loop averages (${ITERATIONS} iterations, 10 method calls each = $(( ITERATIONS * 10 )) recording calls):"
echo ""
echo "No agent:"
grep "HOTLOOP_MS" /tmp/overhead-noagent.txt | awk -F= '{sum+=$2;n++} END {printf "  avg: %.1fms\n", sum/n}'
echo "FULL:"
grep "HOTLOOP_MS" /tmp/overhead-agent-me.txt | awk -F= '{sum+=$2;n++} END {printf "  avg: %.1fms\n", sum/n}'
echo "FULL:"
grep "HOTLOOP_MS" /tmp/overhead-agent-full.txt | awk -F= '{sum+=$2;n++} END {printf "  avg: %.1fms\n", sum/n}'
echo "MEMBER:"
grep "HOTLOOP_MS" /tmp/overhead-agent-fm.txt | awk -F= '{sum+=$2;n++} END {printf "  avg: %.1fms\n", sum/n}'
echo ""
echo "Delegation chain averages ($(( ITERATIONS / 10 )) iterations, ~50 hops each):"
echo ""
echo "No agent:"
grep "DELEGATION_MS" /tmp/overhead-noagent.txt | awk -F= '{sum+=$2;n++} END {printf "  avg: %.1fms\n", sum/n}'
echo "FULL:"
grep "DELEGATION_MS" /tmp/overhead-agent-me.txt | awk -F= '{sum+=$2;n++} END {printf "  avg: %.1fms\n", sum/n}'
echo "FULL:"
grep "DELEGATION_MS" /tmp/overhead-agent-full.txt | awk -F= '{sum+=$2;n++} END {printf "  avg: %.1fms\n", sum/n}'
echo "MEMBER:"
grep "DELEGATION_MS" /tmp/overhead-agent-fm.txt | awk -F= '{sum+=$2;n++} END {printf "  avg: %.1fms\n", sum/n}'
echo ""

# Calculate recording overhead per call
echo "Estimated recording overhead:"
NO_AGENT=$(grep "HOTLOOP_MS" /tmp/overhead-noagent.txt | awk -F= '{sum+=$2;n++} END {print sum/n}')
ME_AGENT=$(grep "HOTLOOP_MS" /tmp/overhead-agent-me.txt | awk -F= '{sum+=$2;n++} END {print sum/n}')
CALLS=$(( ITERATIONS * 10 ))
echo "  Total calls in hot loop: $CALLS"
echo "$NO_AGENT $ME_AGENT $CALLS" | awk '{printf "  Overhead: %.1fms / %d calls = %.1f ns/call\n", ($2-$1), $3, ($2-$1)*1000000/$3}'
echo ""

rm -rf "$WORK_DIR" /tmp/overhead-deps /tmp/overhead.lz4 /tmp/overhead-*.txt
echo "Done."
