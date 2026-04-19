# Integration Tests for test-order-maven-plugin

This directory contains Maven Invoker fixture projects used by the `run-its` profile.

Fixtures:
- `basic-learn-mode`: verifies that learn mode produces `.deps` files and LZ4 hash snapshots.
- `order-mode`: verifies that order mode writes `junit-platform.properties` with PriorityClassOrderer and tests still pass with a pre-built index.
- `aggregate-deps`: verifies that the `aggregate` goal merges `.deps` files into `test-dependencies.idx`.

Run from the project root:

```bash
mvn clean install -DskipTests && mvn -Prun-its verify -pl test-order-maven-plugin
```
