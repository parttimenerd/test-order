# Kotlin and Kotest Support in test-order

This document provides detailed guidance for using `test-order` in Kotlin projects, particularly those using the Kotest testing framework.

## Overview

`test-order` has **limited but well-tested** support for Kotlin projects. The key constraint is that Kotlin source files are compiled to JVM bytecode before instrumentation; therefore, no language-specific handling is required. This makes Kotlin projects "just work" with test-order in most scenarios.

## What Works

### ✅ Fully Supported

- **Kotlin language features**: Extensions, data classes, coroutines, etc. — all compile to bytecode that works with test-order
- **Kotlin source paths**: `src/main/kotlin` and `src/test/kotlin` are automatically detected
- **Dependency tracking**: Agent instrumentation works identically on Kotlin-compiled bytecode as on Java-compiled bytecode
- **Change detection**: Git diff works on `.kt` files; file modification timestamps are tracked
- **Learn mode**: Builds complete dependency indices for Kotlin source packages
- **Class-level ordering**: Kotest specs are treated as test classes; `@Test` annotations and JUnit Platform descriptors work as expected
- **Kotest via JUnit Platform**: Kotest with `kotest-runner-junit5-jvm` is fully compatible

### Tested Configurations

| Language | Framework | JVM | Version | Status |
|----------|-----------|-----|---------|--------|
| Kotlin | JUnit Jupiter | 17+ | 2.1.x | ✅ Verified |
| Kotlin | Kotest (JUnit5 runner) | 17+ | 5.9.1 | ✅ Verified |
| Kotlin (mixed) | JUnit Jupiter | 17+ | 2.1.x | ✅ Verified |

### Package Detection

`test-order` uses **intelligent Kotlin source parsing** to accurately extract package names from `.kt` files. The `KotlinSourceAnalyzer` component:

- **Parses package declarations** directly from source code (doesn't rely on directory structure)
- **Extracts top-level class/object names** by analyzing the syntax tree
- **Correctly handles:
  - String and character literals (avoids false matches)
  - Line and block comments
  - Triple-quoted strings
  - Nested and inner classes (correctly excludes them)
  - All Kotlin class variants (data, sealed, abstract, interfaces, objects)

This means even non-standard directory layouts work correctly, and packages can be explicitly declared independent of folder structure (as allowed in Kotlin):

```kotlin
// ✅ Works: package declared differently from directory structure
package com.example.services    // file in src/main/kotlin/foo/bar/MyService.kt
class MyService { }
```

The parser gracefully falls back to directory-based heuristics if parsing fails, ensuring backward compatibility.

## What Doesn't Work As Expected

### 🔶 Method-Level Ordering

Kotest's DSL-based test definitions (e.g., `StringSpec`, `FunSpec`) do not map to individual JUnit test methods. Instead, all test cases in a spec are treated as a single test class. Therefore:

- **Method-level test ordering is not supported** for Kotest specs
- Methods inside the spec class are not reordered
- This is a **no-op limitation** — tests still run and pass; ordering is simply applied at the class level

Workaround: If you need method-level control, use Kotest with `@TestFactory` / `@Nested` / `@ParameterizedTest` patterns that create distinct JUnit method descriptors, or switch to JUnit Jupiter's `@Test` annotation style with Kotlin.

### 🔶 Kotest Without JUnit Platform Runner

If Kotest is configured to run **without** the JUnit Platform runner:

```kotlin
// ❌ This will NOT work with test-order
testImplementation("io.kotest.runner:kotest-runner-core")
```

The JUnit Platform runner is **required**:

```kotlin
// ✅ This works with test-order
testImplementation("io.kotest:kotest-runner-junit5-jvm:5.9.1")
```

## Setup

### Maven

Add Kotest and the agent dependencies:

```xml
<properties>
    <kotlin.version>2.1.20</kotlin.version>
    <kotest.version>5.9.1</kotest.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.jetbrains.kotlin</groupId>
        <artifactId>kotlin-stdlib</artifactId>
        <version>${kotlin.version}</version>
    </dependency>
    <dependency>
        <groupId>io.kotest</groupId>
        <artifactId>kotest-runner-junit5-jvm</artifactId>
        <version>${kotest.version}</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>io.kotest</groupId>
        <artifactId>kotest-assertions-core-jvm</artifactId>
        <version>${kotest.version}</version>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <!-- Kotlin compiler plugin -->
        <plugin>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-maven-plugin</artifactId>
            <version>${kotlin.version}</version>
            <executions>
                <execution>
                    <goals>
                        <goal>compile</goal>
                        <goal>test-compile</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
        
        <!-- test-order plugin -->
        <plugin>
            <groupId>me.bechberger</groupId>
            <artifactId>test-order-maven-plugin</artifactId>
        </plugin>
    </plugins>
</build>
```

### Gradle (Kotlin DSL)

```kotlin
plugins {
    kotlin("jvm") version "2.1.20"
    id("me.bechberger.test-order") version "0.1.0"
}

dependencies {
    testImplementation("io.kotest:kotest-runner-junit5-jvm:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core-jvm:5.9.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
}
```

## Usage Examples

### Basic Kotest StringSpec

```kotlin
package com.example.tests

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class CalculatorTest : StringSpec({
    "adding 2 and 3 should return 5" {
        (2 + 3) shouldBe 5
    }
    
    "multiplying 2 and 3 should return 6" {
        (2 * 3) shouldBe 6
    }
})
```

### Learn Mode

Collect dependency data:
```bash
# Maven
mvn clean test-order:prepare test

# Gradle
./gradlew clean test --args="-Dtestorder.mode=learn"
```

### Order Mode

Reorder tests based on dependencies:
```bash
# Maven
mvn clean test-order:combined test

# Gradle
./gradlew clean test
```

## Troubleshooting

### Problem: Kotest tests not discovered
**Cause**: Missing JUnit Platform runner  
**Solution**: Add `kotest-runner-junit5-jvm` to dependencies (not just `kotest-runner-core`)

### Problem: Tests run but not reordered
**Cause**: No dependency index exists  
**Solution**: Run in learn mode first to build the index:
```bash
mvn clean test-order:prepare test
```

Then run again in order mode (automatic if index exists):
```bash
mvn clean test
```

### Problem: Method-level test filtering not working
**Cause**: Kotest specs don't expose method granularity to JUnit Platform  
**Solution**: Use `--tests` pattern with spec class names instead:
```bash
mvn test --tests="com.example.tests.CalculatorTest"
```

## Performance Considerations

### Instrumentation Overhead

Kotlin bytecode is instrumented the same way as Java bytecode. No extra overhead. However:

- **First run (learn mode)**: Adds ~10-30% overhead for dependency recording
- **Subsequent runs (order mode)**: Minimal overhead (~1-2%)

### Bytecode Size

Kotlin-compiled classes tend to be slightly larger than hand-written Java equivalents due to functional programming features. This has no impact on instrumentation.

### Method Counting

Kotest specs often have implicit test methods generated by the framework. The agent sees the compiled bytecode, so counts may not match the DSL structure. This is expected and does not affect correctness.

## Known Limitations

1. **Method-level ordering**: As noted above, Kotest specs are class-level entities
2. **Dynamic test generation**: Tests generated dynamically at runtime may not be instrumented
3. **Custom Kotest runners**: Only the JUnit Platform runner (`kotest-runner-junit5-jvm`) is tested
4. **Metadata loss**: Kotlin's source-level metadata (annotations, inline functions) is compiled away before instrumentation; the agent sees only JVM bytecode

## See Also

- [test-order-example-kotlin](../test-order-example-kotlin) — Maven example with JUnit Jupiter
- [fixture-kotest](../test-order-junit/test-fixtures/fixture-kotest) — Kotest fixture with full test-order integration
- [KotestFrameworkIT](../test-order-junit/src/test/java/me/bechberger/testorder/it/KotestFrameworkIT.java) — Integration tests verifying Kotest support
- [Main README](./README.md) — General test-order documentation

## Support & Issues

For issues with Kotlin/Kotest support, please refer to:
- [GitHub Issues](https://github.com/bechberger/test-order/issues)
- [Discussion Forum](https://github.com/bechberger/test-order/discussions)

Include your pom.xml/build.gradle.kts and the output of `mvn test -Dtestorder.debug=true` for faster diagnosis.
