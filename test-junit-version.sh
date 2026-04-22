#!/bin/bash

# Create a test project for specific JUnit version
VERSION=$1
PROJ_NAME=$2
ARTIFACT_ID=$3
JUNIT_DEPENDENCY=$4
TEST_CLASS_IMPORT=$5

WORK_DIR="/Users/i560383_1/code/experiments/test-order/phase5-legacy-junit-tests"
PROJ_DIR="$WORK_DIR/$PROJ_NAME"

mkdir -p "$PROJ_DIR/src/test/java"

# Create POM
cat > "$PROJ_DIR/pom.xml" << EOFPOM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.test</groupId>
    <artifactId>$ARTIFACT_ID</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>
    
    <properties>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
    
    <dependencies>
        $JUNIT_DEPENDENCY
    </dependencies>
    
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.11.0</version>
                <configuration>
                    <source>11</source>
                    <target>11</target>
                </configuration>
            </plugin>
            <plugin>
                <groupId>me.bechberger</groupId>
                <artifactId>test-order-maven-plugin</artifactId>
                <version>0.1.0-SNAPSHOT</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>order</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
EOFPOM

# Create test class
cat > "$PROJ_DIR/src/test/java/BasicTest.java" << EOFTEST
$TEST_CLASS_IMPORT
import static org.junit.jupiter.api.Assertions.*;

public class BasicTest {
    @Test
    public void test_basic() {
        assertTrue(true);
    }
    
    @Test
    public void test_equality() {
        assertEquals(1, 1);
    }
}
EOFTEST

echo "Created test project: $PROJ_NAME"
echo "Location: $PROJ_DIR"
