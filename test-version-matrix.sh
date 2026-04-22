#!/bin/bash

# Test different JUnit 4.x versions
test_junit4_version() {
    local version=$1
    local proj_dir="/Users/i560383_1/code/experiments/test-order/p5-junit4-v$version-test"
    
    mkdir -p "$proj_dir/src/test/java"
    
    cat > "$proj_dir/pom.xml" << EOFPOM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.test</groupId>
    <artifactId>junit4-v$version-test</artifactId>
    <version>1.0.0</version>
    <properties>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
    </properties>
    <dependencies>
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <version>$version</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>me.bechberger</groupId>
                <artifactId>test-order-maven-plugin</artifactId>
                <version>0.1.0-SNAPSHOT</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>prepare</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
EOFPOM

    cat > "$proj_dir/src/test/java/Test.java" << 'EOFTEST'
import org.junit.Test;
import static org.junit.Assert.*;

public class Test {
    @Test
    public void test() {
        assertTrue(true);
    }
}
EOFTEST

    cd "$proj_dir"
    echo -n "JUnit 4.$version: "
    if mvn clean test -q 2>&1 | grep -q "BUILD SUCCESS"; then
        echo "✓ PASS"
        return 0
    else
        echo "✗ FAIL"
        return 1
    fi
}

# Test JUnit 5.x versions
test_junit5_version() {
    local version=$1
    local proj_dir="/Users/i560383_1/code/experiments/test-order/p5-junit5-v$version-test"
    
    mkdir -p "$proj_dir/src/test/java"
    
    cat > "$proj_dir/pom.xml" << EOFPOM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.test</groupId>
    <artifactId>junit5-v$version-test</artifactId>
    <version>1.0.0</version>
    <properties>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>$version</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>me.bechberger</groupId>
                <artifactId>test-order-maven-plugin</artifactId>
                <version>0.1.0-SNAPSHOT</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>prepare</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
EOFPOM

    cat > "$proj_dir/src/test/java/Test.java" << 'EOFTEST'
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class Test {
    @Test
    public void test() {
        assertTrue(true);
    }
}
EOFTEST

    cd "$proj_dir"
    echo -n "JUnit 5.$version: "
    if mvn clean test -q 2>&1 | grep -q "BUILD SUCCESS"; then
        echo "✓ PASS"
        return 0
    else
        echo "✗ FAIL"
        return 1
    fi
}

echo "=== JUnit 4.x Version Compatibility ==="
test_junit4_version "4.13.2"
test_junit4_version "4.12"
test_junit4_version "4.11"
test_junit4_version "4.10"

echo ""
echo "=== JUnit 5.x Version Compatibility ==="
test_junit5_version "5.10.0"
test_junit5_version "5.9.0"
test_junit5_version "5.5.0"
test_junit5_version "5.0.0"

