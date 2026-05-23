# Multi-Module Projects with test-order Plugin

**Complete Guide to Setting Up Test Ordering in Maven & Gradle Multi-Module Builds**

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Maven Setup](#maven-setup)
4. [Gradle Setup](#gradle-setup)
5. [Parallel Execution](#parallel-execution)
6. [Best Practices](#best-practices)
7. [Troubleshooting](#troubleshooting)

---

## Overview

The test-order plugin brings **intelligent test reordering** to multi-module projects by:

- **Sharing a dependency index** across all modules (at the root project)
- **Maintaining per-module state** to prevent test isolation issues in parallel builds
- **Aggregating test results** for global test optimization
- **Supporting hierarchical module structures** (modules with sub-modules)

### Key Benefits for Multi-Module Projects

✅ **Faster CI/CD**: Run changed-test modules first, defer unaffected modules  
✅ **Parallel Safety**: Each module has isolated state; no test interference  
✅ **Scalability**: Works with 2 modules or 200 modules  
✅ **Dashboard**: Unified visualization of test execution across all modules

---

## Architecture

### File Structure

```
project-root/
├── .test-order/                    ← Shared (root level)
│   ├── test-dependencies.lz4       ← Index (ALL modules' dependencies)
│   ├── state.lz4                   ← Shared state (run history, weights, failures)
│   ├── deps/                       ← Shared deps directory
│   ├── hashes/
│   │   ├── com.app-module-a-hashes.lz4
│   │   ├── com.app-module-a-test-hashes.lz4
│   │   ├── com.app-module-b-hashes.lz4
│   │   └── com.app-module-b-test-hashes.lz4
│   └── (other files...)
│
├── module-a/
│   └── pom.xml (or build.gradle)
│
├── module-b/
│   └── pom.xml (or build.gradle)
│
├── module-c/                       ← Sub-module
│   └── pom.xml (or build.gradle)
│
└── pom.xml (root) or settings.gradle

Legend:
  🔴 RED   = Shared (one copy for all modules)
  🟡 YELLOW = Per-module (each module has its own, stored in hashes/)
```

### Why This Design?

**Shared Index**: All modules contribute `.deps` files during learn mode. After `testOrderAggregate`, you have a **single dependency graph** showing which tests cover which classes across **all modules**. This enables:
- Running only affected test modules
- Detecting inter-module dependencies
- Global test prioritization

**Shared State**: The `state.lz4` file is shared at the reactor root level, providing a unified view of run history, scoring weights, and failure data across all modules.

**Per-Module Hashes**: Source/test file hashes are stored per-module (using `<groupId>-<artifactId>` prefixes) to enable module-specific change detection without interference between modules.

---

## Maven Setup

### 1. Root `pom.xml`

```xml
<project>
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example.app</groupId>
    <artifactId>app-root</artifactId>
    <version>1.0.0</version>
    <packaging>pom</packaging>

    <modules>
        <module>module-a</module>
        <module>module-b</module>
        <module>module-c</module>
    </modules>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <test-order.version>0.0.1-SNAPSHOT</test-order.version>
    </properties>

    <!-- Plugin Management (inherited by all modules) -->
    <pluginManagement>
        <plugins>
            <plugin>
                <groupId>me.bechberger</groupId>
                <artifactId>test-order-maven-plugin</artifactId>
                <version>${test-order.version}</version>
                <configuration>
                    <!-- All modules inherit this config -->
                    <instrumentationMode>CLASS</instrumentationMode>
                    <changeMode>uncommitted</changeMode>
                    <scoreNewTest>100</scoreNewTest>
                </configuration>
            </plugin>
        </plugins>
    </pluginManagement>

    <build>
        <plugins>
            <!-- Apply plugin to all modules automatically -->
            <plugin>
                <groupId>me.bechberger</groupId>
                <artifactId>test-order-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

### 2. Module `pom.xml`

```xml
<project>
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.example.app</groupId>
        <artifactId>app-root</artifactId>
        <version>1.0.0</version>
    </parent>
    <artifactId>module-a</artifactId>
    <name>Module A - Core Logic</name>

    <dependencies>
        <!-- ... your dependencies ... -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- Inherits configuration from root pluginManagement -->
            <plugin>
                <groupId>me.bechberger</groupId>
                <artifactId>test-order-maven-plugin</artifactId>
            </plugin>

            <!-- Standard Maven plugins -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>2.22.2</version>
                <configuration>
                    <includes>
                        <include>**/*Test.java</include>
                        <include>**/*Tests.java</include>
                    </includes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

### 3. Build Commands

```bash
# Learn mode: collect dependencies from all modules
mvn clean install -Dtestorder.mode=learn

# After collecting deps, aggregate into shared index
mvn test-order:aggregate

# Order mode: run tests in optimized order
mvn clean test

# View test execution plan (before running)
mvn test-order:show

# Generate dashboard
mvn test-order:dashboard

# Diagnose setup issues
mvn test-order:diagnose

# Clean all test-order files (for starting over)
mvn test-order:clean
```

### 4. Module-Specific Overrides

Override configuration in individual modules if needed:

```xml
<!-- module-b/pom.xml -->
<plugin>
    <groupId>me.bechberger</groupId>
    <artifactId>test-order-maven-plugin</artifactId>
    <configuration>
        <changeMode>since-last-commit</changeMode>
        <scoreSpeed>150</scoreSpeed>
        <!-- Override from root config for this module only -->
    </configuration>
</plugin>
```

---

## Gradle Setup

### 1. Root `settings.gradle`

```gradle
rootProject.name = 'app-root'

includeBuild 'module-a'
includeBuild 'module-b'
includeBuild 'module-c'
// or: include 'module-a', 'module-b', 'module-c' (if single build)
```

### 2. Root `build.gradle`

```gradle
plugins {
    id 'me.bechberger.test-order' version '0.0.1-SNAPSHOT' apply false
}

// Shared configuration for all subprojects
subprojects {
    apply plugin: 'java'
    apply plugin: 'me.bechberger.test-order'

    group = 'com.example.app'
    version = '1.0.0'

    java {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    repositories {
        mavenLocal()
        mavenCentral()
    }

    dependencies {
        testImplementation 'org.junit.jupiter:junit-jupiter:5.9.2'
    }

    test {
        useJUnitPlatform()
    }

    // Shared test-order configuration
    testOrder {
        mode = 'auto'
        instrumentationMode = 'CLASS'
        changeMode = 'uncommitted'
        scoreNewTest = 100
        autoLearnRunThreshold = 10
        autoLearnDiffThreshold = 0
    }
}
```

### 3. Module `build.gradle`

```gradle
plugins {
    id 'java'
}

dependencies {
    // ... your dependencies ...
    testImplementation 'org.junit.jupiter:junit-jupiter'
}

test {
    useJUnitPlatform()
}

// Optional: override global testOrder config for this module
testOrder {
    changeMode = 'since-last-commit'
    scoreSpeed = 150
}
```

### 4. Build Commands

```bash
# Learn mode: collect from all subprojects
./gradlew clean build -Dtestorder.mode=learn

# Aggregate deps into shared index
./gradlew testOrderAggregate

# Order mode: optimized execution
./gradlew clean test

# Show predicted order without running
./gradlew testOrderShowOrder

# Detailed order breakdown with scores
./gradlew testOrderExplainOrder

# Generate dashboard
./gradlew testOrderDashboard

# Serve dashboard locally (opens in browser)
./gradlew testOrderServe

# Diagnose setup
./gradlew testOrderDiagnose

# Clean test-order artifacts
./gradlew testOrderClean
```

---

## Parallel Execution

### Maven: Parallel Module Builds

```bash
# Run modules in parallel (up to N threads)
mvn clean test -T 1C
# -T 1C = 1 thread per CPU core

# With test-order optimization
mvn clean test -T 1C -DfailIfNoTests=false

# Note: test-order automatically handles state isolation
# (each module locks only its own state.lz4)
```

### Gradle: Parallel Task Execution

```bash
# Enable parallel in gradle.properties
org.gradle.parallel=true
org.gradle.workers.max=<number of cores>

# Or via command line
./gradlew test --parallel --max-workers=4

# Works seamlessly with test-order (per-module isolation)
```

### Important: Hash File Locking

When running in parallel, hash file access is coordinated:

```
Module A: locks .test-order/hashes/com.app-module-a-hashes.lz4
Module B: locks .test-order/hashes/com.app-module-b-hashes.lz4
Module C: locks .test-order/hashes/com.app-module-c-hashes.lz4
         ↓
         No conflicts! (Different hash files per module)
         ↓
Module A succeeds
Module B succeeds
Module C succeeds
```

The shared index (test-dependencies.lz4) is only written during aggregation (after all modules complete).

---

## Best Practices

### 1. **Initial Setup Checklist**

- [ ] Root project has test-order plugin configured
- [ ] All subprojects inherit via `pluginManagement` (Maven) or `subprojects` block (Gradle)
- [ ] First run: Use `learn` mode to collect dependencies
  ```bash
  # Maven
  mvn clean install -Dtestorder.mode=learn
  
  # Gradle
  ./gradlew clean build -Dtestorder.mode=learn
  ```
- [ ] Aggregate dependencies
  ```bash
  # Maven
  mvn test-order:aggregate
  
  # Gradle
  ./gradlew testOrderAggregate
  ```
- [ ] Verify index was created
  ```bash
  ls -lh .test-order/test-dependencies.lz4
  ```

### 2. **Daily Workflow**

```bash
# Option 1: Auto mode (recommended)
mvn clean test          # Maven auto-learns when needed
./gradlew clean test    # Gradle auto-learns when needed

# Option 2: Explicit aggregation (for CI/CD)
mvn clean test -Dtestorder.mode=learn
mvn test-order:aggregate
mvn test-order:prepare -Dtestorder.mode=order

# Option 3: Skip reordering when debugging specific tests
mvn test -Dtest=MyTest -Dtestorder.skip=true
./gradlew test --tests MyTest -Dtestorder.skip=true
```

### 3. **CI/CD Integration**

**GitHub Actions Example**:
```yaml
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
        with:
          fetch-depth: 0  # Full history for change detection
      
      - name: Set up JDK
        uses: actions/setup-java@v3
        with:
          java-version: '17'
      
      - name: Learn (first run or after major changes)
        if: github.event_name == 'push' && github.ref == 'refs/heads/main'
        run: mvn clean install -Dtestorder.mode=learn
      
      - name: Aggregate
        if: github.event_name == 'push' && github.ref == 'refs/heads/main'
        run: mvn test-order:aggregate
      
      - name: Test (order mode)
        run: mvn clean test
      
      - name: Export metrics
        run: ls -lh .test-order-metrics.json && cat .test-order-metrics.json
      
      - name: Upload to artifact
        if: always()
        uses: actions/upload-artifact@v3
        with:
          name: test-metrics
          path: .test-order-metrics.json
```

### 4. **Handling Flaky Tests**

```bash
# Run specific test without ordering (to isolate flakiness)
mvn test -Dtest=FlakyTest -Dtestorder.skip=true

# Or exclude from ordering and run separately
mvn clean test -Dtestorder.skip=true -Dtest=FlakyTest
mvn clean test -DexcludedGroups=flaky

# After fixing, re-learn
mvn clean install -Dtestorder.mode=learn && mvn test-order:aggregate
```

### 5. **Module Dependencies**

If Module B depends on Module A's tests, ensure correct execution order:

```
module-a/pom.xml:
  <module>module-b</module>

module-b/pom.xml:
  <dependency>
    <groupId>com.example</groupId>
    <artifactId>module-a</artifactId>
    <version>${project.version}</version>
  </dependency>
```

Test-order detects this during learn mode and schedules Module A tests first.

---

## Troubleshooting

### Problem: Index is empty / No tests detected

**Symptom**: `test-dependencies.lz4` exists but is very small (< 1 KB)

**Causes**:
1. Tests weren't collected in learn mode
2. Aggregation didn't run
3. Tests aren't named `*Test.java` or `*Tests.java`

**Solution**:
```bash
# Maven
mvn clean install -Dtestorder.mode=learn -X 2>&1 | grep -i "deps\|test"
mvn test-order:aggregate
mvn test-order:diagnose

# Gradle
./gradlew clean build -Dtestorder.mode=learn --debug 2>&1 | grep -i "deps\|test"
./gradlew testOrderAggregate
./gradlew testOrderDiagnose
```

### Problem: Parallel builds deadlock

**Symptom**: Tests hang or timeout during parallel execution

**Causes**:
1. Hash file lock timeout (> 30 seconds)
2. State file corrupted from concurrent access
3. Network filesystem locks are slow

**Solution**:
```bash
# Reduce stale-lock threshold (default 120 minutes); lock files older than this are removed
mvn test -Dtestorder.lock.stale.minutes=5

# For Gradle
./gradlew test -Dtestorder.lock.stale.minutes=5

# Disable parallel execution temporarily
mvn test -T 1  # Maven: serial
./gradlew test --max-workers=1  # Gradle: serial

# Check what's holding the lock
lsof | grep ".test-order"
```

### Problem: Module-specific state not isolated

**Symptom**: Running Module A tests affects Module B test scoring

**Causes**:
1. Both modules point to same state file
2. TestOrderExtension.applyDefaults() not called
3. Custom path configuration overriding defaults

**Solution**:
```bash
# Verify state files are separate
ls -la module-a/.test-order/state.lz4
ls -la module-b/.test-order/state.lz4
# Should be in different directories

# Check extension config
mvn test-order:diagnose  # Shows resolved paths

# Reset to defaults
rm -rf module-a/.test-order
rm -rf module-b/.test-order
mvn clean install -Dtestorder.mode=learn
```

### Problem: Changed detection misses cross-module impacts

**Symptom**: Changed Module A's code but Module B tests still run

**Expected**: Module B tests should run because they depend on Module A

**Solution**:
1. Learn mode must collect full dependency graph (all modules)
2. Index must be aggregated after all modules contribute
3. Use `testOrderDiagnose` to verify coverage

```bash
# Ensure full learn cycle
mvn clean install -Dtestorder.mode=learn
mvn test-order:aggregate
mvn test-order:show  # Verify Module B tests are included

# If still missing, check package structure
# test-order needs source root to auto-detect packages
```

---

## Dashboard & Metrics

### Generate Dashboard

```bash
# Maven
mvn test-order:dashboard
# Opens: target/test-order-dashboard/index.html

# Gradle
./gradlew testOrderDashboard
# Opens: build/test-order-dashboard/index.html
```

### View Metrics

```bash
# Maven
cat target/.test-order-metrics.json | jq '.'

# Gradle
cat build/test-order-metrics.json | jq '.'

# Example output:
{
  "timestamp": "2026-04-29T10:30:00Z",
  "project": "app-root",
  "total_tests": 1240,
  "tests_selected": 340,
  "estimated_savings_seconds": 3200.5,
  "savings_percentage": 82.1,
  "modules": {
    "module-a": { "tests": 400, "selected": 120 },
    "module-b": { "tests": 550, "selected": 180 },
    "module-c": { "tests": 290, "selected": 40 }
  }
}
```

---

## Sample Multi-Module Project

See `samples/sample-multi/` for a complete working example:

```bash
cd samples/sample-multi
mvn clean install -Dtestorder.mode=learn
mvn test-order:aggregate
mvn clean test
mvn test-order:dashboard
```

---

## FAQ

**Q: Can I use test-order with nested modules (modules within modules)?**  
A: Yes! Hierarchy doesn't matter. Each module maintains its own state; shared index lives at root.

**Q: What happens if I delete a module?**  
A: Its hash files stay in `.test-order/hashes/`. Run `testOrderCompact` to clean up.

**Q: Can different modules use different changeMode values?**  
A: Yes, but not recommended. Set once at root; override only if needed.

**Q: How do I know which tests cover which classes?**  
A: `mvn test-order:dump` shows the dependency index in readable text format.

**Q: What if my CI system can't access the shared .test-order directory?**  
A: Use `-Dtestorder.skip=true` to disable reordering; tests will run in default order.

---

## Next Steps

1. Run the [Quick Start](#maven-setup)
2. Read [Architecture](#architecture) for deep understanding
3. Implement [Best Practices](#best-practices) in your CI/CD
4. Use `testOrderDiagnose` / `mvn test-order:diagnose` to validate setup
5. Monitor metrics and adjust scoring weights as needed

For questions or issues: See [Troubleshooting](#troubleshooting) or file a GitHub issue.
