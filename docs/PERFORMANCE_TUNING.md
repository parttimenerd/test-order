# Performance Tuning

Keep tuning simple: measure first, then change one lever at a time.

## Baseline Measurements

```bash
# Full suite baseline
mvn test

# Learn-mode cost
mvn test -Dtestorder.mode=learn

# Prioritization view without execution
mvn test-order:show-order
```

Track:

- suite wall-clock time
- time to first failure
- selected subset size
- `.test-order/` data growth

## Main Runtime Levers

### 1) Change Detection Mode

- Local coding loop: `uncommitted`
- Branch/CI checks: `since-last-commit`
- Deterministic pipelines: `explicit`
- Mixed/no-special-case setup: `auto`

### 2) Selection Size

- `testorder.select.topN`: deterministic priority coverage
- `testorder.select.randomM`: diversity sampling

Start conservative and scale up only if needed.

### 3) Instrumentation Mode (Learn Runs)

Available modes:

- `METHOD_ENTRY` (lowest overhead)
- `FULL` (balanced default)
- `FULL_METHOD`
- `FULL_MEMBER` (highest detail and overhead)

Prefer the least expensive mode that still provides useful prioritization signal.

### 4) Instrumentation Scope

Use package filtering to reduce noise and overhead:

```bash
mvn test -Dtestorder.mode=learn \
  -Dtestorder.includePackages=com.example.service,com.example.web
```

## Practical Profiles

### Fast local loop

```bash
mvn test-order:combined test \
  -Dtestorder.changeMode=uncommitted \
  -Dtestorder.select.topN=5 \
  -Dtestorder.select.randomM=0
```

### Normal branch validation

```bash
mvn test-order:combined test \
  -Dtestorder.changeMode=since-last-commit \
  -Dtestorder.select.topN=20 \
  -Dtestorder.select.randomM=5
```

### Conservative CI subset

```bash
mvn test-order:combined test \
  -Dtestorder.changeMode=since-last-commit \
  -Dtestorder.select.topN=50 \
  -Dtestorder.select.randomM=20
```

## Troubleshooting Slowdowns

1. Compare baseline (`mvn test`) vs learn-mode (`mvn test -Dtestorder.mode=learn`).
2. Reduce `testorder.select.randomM` before increasing `topN`.
3. Narrow `testorder.includePackages` when index growth is large.
4. Relearn periodically; avoid stale dependency data.
5. Use dashboard/show-order to confirm that prioritization is helping.

## Benchmarking the hot path

The repository includes JMH benchmarks for instrumentation hot-path operations in [test-order-benchmarks/src/main/java/me/bechberger/testorder/benchmarks/HotPathBenchmark.java](../test-order-benchmarks/src/main/java/me/bechberger/testorder/benchmarks/HotPathBenchmark.java).

Build and run:

```bash
mvn -B clean package -pl test-order-benchmarks -DskipTests
java -jar test-order-benchmarks/target/benchmarks.jar -f 1 -wi 2 -i 3 -t 1
```

Measured benchmark groups:

- `benchmarkClassIdMapLookup` — class ID lookup throughput
- `benchmarkMemberIdLookup` — member ID lookup throughput
- `benchmarkBitsetRecording` — atomic bitset write throughput
- `benchmarkCombinedHotPath` — lookup + record end-to-end path
- `benchmarkBitsetConversion` — conversion of bitsets to names at flush time

When comparing implementation variants (for example AtomicInteger vs VarHandle counters), run the same JMH settings and JVM across variants before deciding to switch.

## Related Docs

- [CLI_REFERENCE.md](CLI_REFERENCE.md)
- [ARCHITECTURE.md](ARCHITECTURE.md)
