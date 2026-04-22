#!/bin/bash

# Test 1: Custom Gradle Task That Depends on test-order Plugin
echo "=== Test 1: Custom Task Depending on test-order ==="

TESTDIR="/tmp/test-order-custom-task-$$"
mkdir -p "$TESTDIR"
cd "$TESTDIR"

# Create test project
mkdir -p src/test/java/com/example
cat > build.gradle.kts << 'EOF'
plugins {
    java
    id("me.bechberger.test-order") version "0.1.0-SNAPSHOT"
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.register("customTestTask") {
    dependsOn("test")
    doLast {
        println("Custom task executed after test")
    }
}
EOF

cat > src/test/java/com/example/TestA.java << 'EOF'
package com.example;
import org.junit.Test;
public class TestA {
    @Test
    public void test1() {
        System.out.println("TestA.test1");
    }
}
EOF

# Try to build
echo "Running: gradle customTestTask"
gradle customTestTask 2>&1 | tee "$TESTDIR/output.txt"
RESULT=$?

if [ $RESULT -eq 0 ]; then
    echo "✓ PASS: Custom task executed successfully"
else
    echo "✗ FAIL: Custom task failed with exit code $RESULT"
fi

# Cleanup
cd /
rm -rf "$TESTDIR"
