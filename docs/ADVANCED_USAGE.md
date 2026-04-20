# Advanced Usage Guide

Real-world scenarios, recipes, and best practices for test-order in complex environments.

## Table of Contents
1. [CI/CD Integration](#cicd-integration)
2. [Multi-Module Projects](#multi-module-projects)
3. [Custom Workflows](#custom-workflows)
4. [Troubleshooting](#troubleshooting)
5. [Performance Tuning](#performance-tuning)

---

## CI/CD Integration

### GitHub Actions

```yaml
name: Selective Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    
    steps:
      - uses: actions/checkout@v3
        with:
          fetch-depth: 0  # Full history for git diff
      
      - uses: actions/setup-java@v3
        with:
          java-version: '17'
      
      - name: Run selective tests
        run: |
          mvn test-order:combined test \
            -Dtestorder.change-mode=since-last-commit \
            -Dtestorder.select-top-n=20 \
            -Dtestorder.select-random-m=10
      
      - name: Run full tests on failure
        if: failure()
        run: mvn test
```

### GitLab CI

```yaml
test:selective:
  script:
    - mvn test-order:combined test
      -Dtestorder.change-mode=since-last-commit
      -Dtestorder.git-base-commit=origin/main
  only:
    - merge_requests
    - branches

test:full:
  script:
    - mvn test
  only:
    - tags
    - main
```

### Jenkins Pipeline

```groovy
pipeline {
    agent any
    
    stages {
        stage('Selective Tests') {
            steps {
                script {
                    def baseCommit = env.CHANGE_TARGET ?: 'main'
                    sh """
                        mvn test-order:combined test \
                          -Dtestorder.change-mode=since-last-commit \
                          -Dtestorder.git-base-commit=${baseCommit} \
                          -Dtestorder.select-top-n=30
                    """
                }
            }
        }
        
        stage('Full Tests on Main') {
            when { branch 'main' }
            steps {
                sh 'mvn test'
            }
        }
    }
    
    post {
        failure {
            emailext(
                subject: 'Build failed',
                body: 'Selective tests detected failures. Running full suite...'
            )
            sh 'mvn test'
        }
    }
}
```

### CircleCI

```yaml
version: 2.1

workflows:
  test:
    jobs:
      - selective-test
      - full-test:
          requires: [selective-test]
          filters:
            branches:
              only: main

jobs:
  selective-test:
    docker:
      - image: cimg/openjdk:17.0
    steps:
      - checkout
      - run:
          name: Selective test run
          command: |
            mvn test-order:combined test \
              -Dtestorder.change-mode=auto \
              -Dtestorder.select-top-n=25
  
  full-test:
    docker:
      - image: cimg/openjdk:17.0
    steps:
      - checkout
      - run:
          name: Full test suite
          command: mvn test
```

---

## Multi-Module Projects

### Monorepo with Selective Modules

**Project Structure**:
```
monorepo/
├── core/
├── api/
├── web/
├── admin/
└── pom.xml (aggregator)
```

**pom.xml (root)**:
```xml
<project>
    <groupId>com.example</groupId>
    <artifactId>monorepo-parent</artifactId>
    <packaging>pom</packaging>
    
    <modules>
        <module>core</module>
        <module>api</module>
        <module>web</module>
        <module>admin</module>
    </modules>
    
    <pluginManagement>
        <plugins>
            <plugin>
                <groupId>me.bechberger</groupId>
                <artifactId>test-order-maven-plugin</artifactId>
                <version>0.1.0-SNAPSHOT</version>
                <configuration>
                    <changeMode>auto</changeMode>
                    <selectTopN>20</selectTopN>
                    <selectRandomM>10</selectRandomM>
                </configuration>
            </plugin>
        </plugins>
    </pluginManagement>
</project>
```

**Run selective tests for affected modules**:
```bash
# Run test-order on entire project (auto-detects changes)
mvn clean test-order:combined test

# Run test-order only for specific modules
mvn -pl core,api test-order:combined test

# Run test-order with custom threshold
mvn clean test-order:combined test \
  -Dtestorder.select-top-n=30 \
  -Dtestorder.select-random-m=15
```

### Gradle Multi-Module

**settings.gradle**:
```groovy
rootProject.name = 'monorepo'
include 'core', 'api', 'web'
```

**root build.gradle**:
```groovy
plugins {
    id 'me.bechberger.test-order' version '0.1.0' apply false
}

subprojects {
    apply plugin: 'java'
    apply plugin: 'me.bechberger.test-order'
    
    testOrder {
        changeMode = 'auto'
        selectTopN = 20
        selectRandomM = 10
    }
}
```

**Run tests**:
```bash
# Selective tests for all modules
gradle test

# Selective tests for specific module
gradle :api:test

# Full test suite (override configuration)
gradle test --no-test-order
```

---

## Custom Workflows

### TDD Workflow (Rapid Iteration)

```bash
# 1. Create test
# 2. Run test-order (only new test runs)
mvn test-order:combined test -Dtestorder.change-mode=uncommitted

# 3. If fail:
#    - Fix code
#    - Rerun (fast feedback)
# 4. If pass:
#    - Commit and move to next feature
git add . && git commit -m "Feature complete"
```

### Pre-Commit Hook

**.git/hooks/pre-commit**:
```bash
#!/bin/bash
set -e

echo "Running selective tests..."
mvn test-order:combined test \
  -Dtestorder.change-mode=uncommitted \
  -Dtestorder.select-top-n=10

if [ $? -ne 0 ]; then
    echo "Tests failed! Fix before committing."
    exit 1
fi

echo "✓ Tests passed, proceeding with commit"
```

### Release Workflow

```bash
# Checkout release branch
git checkout -b release/v1.2.0

# Run tests for changed code
mvn test-order:combined test \
  -Dtestorder.change-mode=since-last-commit \
  -Dtestorder.git-base-commit=main

# If release-critical changes:
mvn test-order:combined test \
  -Dtestorder.change-mode=explicit \
  -Dtestorder.changed-classes=com.example.ReleaseService,com.example.PaymentProcessor

# Tag and push
git tag v1.2.0
git push origin release/v1.2.0 v1.2.0
```

### Nightly Full Test with Reporting

```bash
# cron job: 2 AM daily
0 2 * * * cd /path/to/project && \
  mvn clean test \
  -Dtestorder.coverage-mojo:coverage \
  -Dtestorder.threshold-percent=75 \
  -Dtestorder.output-file=coverage-report.md && \
  git add coverage-report.md && \
  git commit -m "Nightly coverage report" && \
  git push origin main
```

---

## Troubleshooting

### Tests Not Running

**Problem**: "No tests selected"

**Solutions**:
```bash
# 1. Check change detection
mvn test-order:prepare test

# 2. Force change detection
mvn test-order:combined test -Dtestorder.select-top-n=1

# 3. Rebuild index
rm test-dependencies.lz4 .test-order-state
mvn test-order:combined test

# 4. Use explicit mode
mvn test-order:combined test \
  -Dtestorder.change-mode=explicit \
  -Dtestorder.changed-classes=com.example.Service
```

### Wrong Tests Selected

**Problem**: "Expected TestX but got TestY"

**Solutions**:
```bash
# 1. Debug change detection
mvn test-order:prepare test -X 2>&1 | grep -i "changed"

# 2. Check dependency index
java -cp test-order-cli.jar \
  me.bechberger.testorder.cli.IndexDumper \
  test-dependencies.lz4 | head -50

# 3. Verify git state
git status
git diff --name-only

# 4. Reset and rebuild
rm test-dependencies.lz4 .test-order-state .test-order-hashes.lz4
mvn clean test-order:combined test
```

### Performance Issues

**Problem**: "Build is slower than before"

**Causes & Solutions**:
```
1. Index size too large (>10MB)
   → Trim test suite or run locally only
   → Use explicit mode in CI

2. Hash computation slow
   → Use git-based detection: since-last-commit
   → Or use uncommitted mode

3. Test execution slow
   → Increase selectTopN (20 → 50)
   → Decrease selectRandomM (10 → 5)
   → Or run full suite on main branch only

4. Agent overhead
   → Use SMART instrumentation mode
   → Filter packages: -Dinstrumentation.include-packages=com.example
```

---

## Performance Tuning

### Optimization Levels

#### Level 1: Development (Fastest)
```bash
mvn test-order:combined test \
  -Dtestorder.change-mode=uncommitted \
  -Dtestorder.select-top-n=5 \
  -Dtestorder.select-random-m=0
  
# Expected: ~10-20% of full suite time
```

#### Level 2: Feature Branch (Fast)
```bash
mvn test-order:combined test \
  -Dtestorder.change-mode=since-last-commit \
  -Dtestorder.select-top-n=20 \
  -Dtestorder.select-random-m=5
  
# Expected: ~30-50% of full suite time
```

#### Level 3: Main Branch (Thorough)
```bash
mvn test-order:combined test \
  -Dtestorder.select-top-n=50 \
  -Dtestorder.select-random-m=20
  
# Expected: ~60-80% of full suite time
```

#### Level 4: Release (Complete)
```bash
mvn test

# Expected: 100% of full suite time
```

### Configuration Strategy

**pom.xml** (shared defaults):
```xml
<plugin>
    <groupId>me.bechberger</groupId>
    <artifactId>test-order-maven-plugin</artifactId>
    <configuration>
        <changeMode>auto</changeMode>
        <selectTopN>20</selectTopN>
        <selectRandomM>10</selectRandomM>
    </configuration>
</plugin>
```

**Maven profiles** (environment-specific):
```xml
<!-- Development -->
<profile>
    <id>dev</id>
    <activation><property><name>env</name><value>dev</value></property></activation>
    <properties>
        <testorder.select-top-n>5</testorder.select-top-n>
    </properties>
</profile>

<!-- CI -->
<profile>
    <id>ci</id>
    <activation><property><name>env</name><value>ci</value></property></activation>
    <properties>
        <testorder.change-mode>since-last-commit</testorder.change-mode>
        <testorder.select-top-n>40</testorder.select-top-n>
    </properties>
</profile>

<!-- Release -->
<profile>
    <id>release</id>
    <activation><property><name>env</name><value>release</value></property></activation>
    <properties>
        <testorder.select-top-n>999</testorder.select-top-n>
    </properties>
</profile>
```

**Usage**:
```bash
# Development
mvn test -Denv=dev

# CI
mvn test -Denv=ci

# Release
mvn test -Denv=release
```

### Cache Management

**Warming up the cache**:
```bash
# First run: build full index (slow)
mvn clean test-order:snapshot test

# Subsequent runs: fast (only changes detected)
mvn test-order:combined test

# Cache size check
du -h test-dependencies.lz4 .test-order-*

# Clear cache if needed
rm test-dependencies.lz4 .test-order-*
```

### Large Project Optimizations

**For 10,000+ tests**:
```bash
# 1. Use explicit mode (skip index loading)
mvn test-order:combined test \
  -Dtestorder.change-mode=explicit \
  -Dtestorder.changed-classes=com.example.Service

# 2. Use SMART instrumentation (lighter bytecode)
mvn test-order:combined test \
  -Dtestorder.instrumentation-mode=SMART

# 3. Filter packages (reduce agent overhead)
mvn test-order:combined test \
  -Dinstrumentation.include-packages=com.example

# 4. Disable random selection (reduce variance)
mvn test-order:combined test \
  -Dtestorder.select-random-m=0 \
  -Dtestorder.select-top-n=100
```

---

## Summary

Advanced usage patterns:
- **CI/CD**: Integrate with GitHub Actions, GitLab CI, Jenkins, CircleCI
- **Multi-Module**: Leverage test-order across monorepos
- **Custom Workflows**: TDD, pre-commit hooks, release processes
- **Troubleshooting**: Clear diagnosis and recovery strategies
- **Performance**: Tune for your environment (dev, CI, release)

test-order scales from rapid TDD iteration to complex monorepo testing.
