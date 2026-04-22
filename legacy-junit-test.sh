#!/bin/bash

# Phase 5 Legacy JUnit Version Testing
# Tests test-order plugin against various JUnit versions

WORK_DIR="/Users/i560383_1/code/experiments/test-order/phase5-legacy-junit-tests"
LOG_FILE="/Users/i560383_1/code/experiments/test-order/P5-LEGACY-JUNIT-TEST-LOG.txt"
BUG_REPORT="/Users/i560383_1/code/experiments/test-order/LIVE-BUG-REPORT.md"

mkdir -p "$WORK_DIR"

echo "=== Phase 5 Legacy JUnit Version Testing ===" | tee "$LOG_FILE"
echo "Started: $(date)" | tee -a "$LOG_FILE"
echo "" | tee -a "$LOG_FILE"

# Test matrix
declare -a JUNIT4_VERSIONS=("4.0" "4.6" "4.10" "4.12" "4.13")
declare -a JUNIT5_VERSIONS=("5.0.0" "5.5.0" "5.9.0" "5.10.0")

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

create_junit4_project() {
    local version=$1
    local proj_dir="$WORK_DIR/junit4-$version"
    
    mkdir -p "$proj_dir/src/test/java"
    
    cat > "$proj_dir/pom.xml" << EOFPOM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.test</groupId>
    <artifactId>junit4-$version-test</artifactId>
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
                            <goal>order</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
EOFPOM

    # Create test classes
    cat > "$proj_dir/src/test/java/TestBasic.java" << 'EOFTEST'
import org.junit.Test;
import static org.junit.Assert.*;

public class TestBasic {
    @Test
    public void test_01_basic() {
        assertTrue(true);
    }
    
    @Test
    public void test_02_another() {
        assertEquals(1, 1);
    }
}
EOFTEST

    cat > "$proj_dir/src/test/java/TestDependency.java" << 'EOFTEST'
import org.junit.Test;
import static org.junit.Assert.*;

public class TestDependency {
    @Test
    public void test_01_dependent() {
        assertTrue(true);
    }
}
EOFTEST
}

create_junit5_project() {
    local version=$1
    local proj_dir="$WORK_DIR/junit5-$version"
    
    mkdir -p "$proj_dir/src/test/java"
    
    cat > "$proj_dir/pom.xml" << EOFPOM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.test</groupId>
    <artifactId>junit5-$version-test</artifactId>
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
                            <goal>order</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
EOFPOM

    # Create test classes
    cat > "$proj_dir/src/test/java/TestBasic.java" << 'EOFTEST'
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestBasic {
    @Test
    public void test_01_basic() {
        assertTrue(true);
    }
    
    @Test
    public void test_02_another() {
        assertEquals(1, 1);
    }
}
EOFTEST

    cat > "$proj_dir/src/test/java/TestDependency.java" << 'EOFTEST'
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestDependency {
    @Test
    public void test_01_dependent() {
        assertTrue(true);
    }
}
EOFTEST
}

test_version() {
    local version=$1
    local proj_dir=$2
    local test_type=$3
    
    echo "Testing $test_type version $version..." | tee -a "$LOG_FILE"
    cd "$proj_dir"
    
    if mvn clean test -q 2>&1 | grep -q "BUILD SUCCESS"; then
        echo -e "${GREEN}✓ SUCCESS${NC} - $test_type $version" | tee -a "$LOG_FILE"
        echo "PASS:$test_type:$version" >> "$LOG_FILE"
    else
        local error=$(mvn clean test 2>&1 | tail -20)
        echo -e "${RED}✗ FAILED${NC} - $test_type $version" | tee -a "$LOG_FILE"
        echo "ERROR details:" | tee -a "$LOG_FILE"
        echo "$error" | tee -a "$LOG_FILE"
        echo "" | tee -a "$LOG_FILE"
    fi
}

# Test JUnit 4 versions
echo "Testing JUnit 4.x versions..." | tee -a "$LOG_FILE"
for version in "${JUNIT4_VERSIONS[@]}"; do
    create_junit4_project "$version"
    test_version "$version" "$WORK_DIR/junit4-$version" "JUnit4"
done

# Test JUnit 5 versions
echo "" | tee -a "$LOG_FILE"
echo "Testing JUnit 5.x versions..." | tee -a "$LOG_FILE"
for version in "${JUNIT5_VERSIONS[@]}"; do
    create_junit5_project "$version"
    test_version "$version" "$WORK_DIR/junit5-$version" "JUnit5"
done

echo "" | tee -a "$LOG_FILE"
echo "Completed: $(date)" | tee -a "$LOG_FILE"

