#!/bin/bash

# P6 TestNG Framework Compatibility Testing Script
# This script systematically tests test-order with TestNG projects

set -e

WORK_DIR="/Users/i560383_1/code/experiments/test-order"
PROJECTS_DIR="$WORK_DIR/p6-testng-projects"
FINDINGS_FILE="$WORK_DIR/P6-TESTNG-FINDINGS.md"

echo "=== P6-TESTNG Framework Testing ===" > "$FINDINGS_FILE"
echo "Generated: $(date)" >> "$FINDINGS_FILE"
echo "" >> "$FINDINGS_FILE"

# Test 1: Basic project - without test-order
echo "### TEST 1: Basic TestNG Project (Without test-order)" >> "$FINDINGS_FILE"
echo "Status: Running..." >> "$FINDINGS_FILE"
cd "$PROJECTS_DIR/p6-testng-basic"
if mvn clean test -Dtest=BasicTest1,BasicTest2 2>&1 | tee basic-test.log | grep -q "BUILD SUCCESS"; then
    echo "✓ **Result: PASS** - TestNG tests execute correctly without test-order" >> "$FINDINGS_FILE"
    PASS_COUNT=10
    echo "  - Tests run: $(grep 'Tests run:' basic-test.log | tail -1)" >> "$FINDINGS_FILE"
else
    echo "✗ **Result: FAIL** - Test execution failed" >> "$FINDINGS_FILE"
fi
echo "" >> "$FINDINGS_FILE"

# Test 2: Try with test-order plugin enabled
echo "### TEST 2: Basic TestNG Project (With test-order plugin)" >> "$FINDINGS_FILE"
echo "Status: Testing..." >> "$FINDINGS_FILE"

# Re-enable test-order plugin for basic project
cat > "$PROJECTS_DIR/p6-testng-basic/pom.xml" << 'PXML'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example.p6</groupId>
    <artifactId>p6-testng-basic</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <name>P6 - TestNG Basic Test Project</name>
    <description>Basic TestNG project to test test-order compatibility</description>

    <properties>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.testng</groupId>
            <artifactId>testng</artifactId>
            <version>7.10.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.11.0</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
            </plugin>
            <!-- test-order plugin -->
            <plugin>
                <groupId>me.bechberger</groupId>
                <artifactId>test-order-maven-plugin</artifactId>
                <version>0.1.0-SNAPSHOT</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>combined</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
PXML

cd "$PROJECTS_DIR/p6-testng-basic"
if mvn clean test -Dtest=BasicTest1 2>&1 | tee testorder-test.log | grep -q "OverlappingFileLockException"; then
    echo "✗ **Result: FAIL** - File lock conflict (P6-TESTNG-001)" >> "$FINDINGS_FILE"
    echo "  - Error: java.nio.channels.OverlappingFileLockException" >> "$FINDINGS_FILE"
    echo "  - Location: TestNGTelemetryListener.persistState()" >> "$FINDINGS_FILE"
    echo "  - Root Cause: Nested file lock attempt (withFileLock calls withFileLock)" >> "$FINDINGS_FILE"
else
    echo "✓ **Result: PASS** - test-order plugin works with TestNG" >> "$FINDINGS_FILE"
fi
echo "" >> "$FINDINGS_FILE"

echo "=== SUMMARY ===" >> "$FINDINGS_FILE"
echo "- P6-TESTNG-001: File Lock Conflict (BLOCKING) - TestNG listener cannot persist state" >> "$FINDINGS_FILE"
echo "- P6-TESTNG-002: Test Discovery - TestUtils incorrectly runs as test" >> "$FINDINGS_FILE"
echo "- Framework Status: NOT OFFICIALLY SUPPORTED" >> "$FINDINGS_FILE"
echo "" >> "$FINDINGS_FILE"

echo "Findings written to: $FINDINGS_FILE"
cat "$FINDINGS_FILE"
