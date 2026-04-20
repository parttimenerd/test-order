# Integration Tests for test-order-maven-plugin

This directory contains Maven Invoker fixture projects used by the `run-its` profile.

Fixtures:
- `basic-learn-mode`: verifies that learn mode produces `.deps` files and LZ4 hash snapshots for the JUnit 5 line.
- `order-mode`: verifies that order mode writes `junit-platform.properties` with PriorityClassOrderer and tests still pass with a pre-built index for the JUnit 5 line.
- `select-mode`: verifies that select mode runs only the prioritized subset, writes selected/remaining files, and preserves deterministic filtering for the JUnit 5 line.
- `run-remaining-mode`: verifies that `run-remaining` consumes a pre-written remaining-tests file and executes only the deferred JUnit 5 tests.
- `basic-learn-mode-junit6`: mirrors learn-mode coverage on the JUnit 6 line.
- `order-mode-junit6`: mirrors order-mode coverage on the JUnit 6 line.
- `select-mode-junit6`: mirrors select-mode coverage on the JUnit 6 line.
- `run-remaining-mode-junit6`: mirrors run-remaining coverage on the JUnit 6 line.
- `aggregate-deps`: verifies that the `aggregate` goal merges `.deps` files into `test-dependencies.lz4`.
- `reactor-learn-mode`: verifies that multi-module learn mode writes shared reactor-local `.test-order/deps` output.

Run from the project root:

```bash
mvn clean install -DskipTests && mvn -Prun-its verify -pl test-order-maven-plugin
```
