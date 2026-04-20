# Benchmarks baselines

This directory stores lightweight JMH baseline captures for the core algorithms:

- `CoreAlgorithmBenchmark.scoreTestClass`
- `CoreAlgorithmBenchmark.loadDependencyMap`
- `CoreAlgorithmBenchmark.computeSetCover`
- `CoreAlgorithmBenchmark.stateRoundTrip`

Refresh with:

```bash
mvn -q -pl test-order-benchmarks -am package -DskipTests
java -jar test-order-benchmarks/target/benchmarks.jar CoreAlgorithmBenchmark -wi 0 -i 1 -f 1
```

Save the output to a dated `*.txt` file in this directory when updating the baselines.
