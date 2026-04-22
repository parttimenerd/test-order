#!/bin/bash
set -e

BASE="/Users/i560383_1/code/experiments/test-order"
mkdir -p "$BASE/p6-detailed-testing"

# P6-004: Separate test classes - JUnit4 and JUnit5 
mkdir -p "$BASE/p6-mixed-junit4-junit5-separate/{src/test/java,target}"
cat > "$BASE/p6-mixed-junit4-junit5-separate/pom.xml" << 'POMEOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>p6-mixed-junit4-junit5-separate</artifactId>
    <version>1.0.0</version>
    <properties>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
    </properties>
    <dependencies>
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <version>4.13.2</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.9.2</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.vintage</groupId>
            <artifactId>junit-vintage-engine</artifactId>
            <version>5.9.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.0.0-M9</version>
            </plugin>
            <plugin>
                <groupId>com.github.hanratty</groupId>
                <artifactId>test-order-maven-plugin</artifactId>
                <version>0.1.0-SNAPSHOT</version>
            </plugin>
        </plugins>
    </build>
</project>
POMEOF

cat > "$BASE/p6-mixed-junit4-junit5-separate/src/test/java/JUnit4A.java" << 'JAVAEOF'
import org.junit.Test;
import static org.junit.Assert.*;

public class JUnit4A {
    @Test public void a_test() { System.out.println("JUnit4A.a_test"); assertTrue(true); }
    @Test public void b_test() { System.out.println("JUnit4A.b_test"); assertTrue(true); }
    @Test public void c_test() { System.out.println("JUnit4A.c_test"); assertTrue(true); }
}
JAVAEOF

cat > "$BASE/p6-mixed-junit4-junit5-separate/src/test/java/JUnit4B.java" << 'JAVAEOF'
import org.junit.Test;
import static org.junit.Assert.*;

public class JUnit4B {
    @Test public void a_test() { System.out.println("JUnit4B.a_test"); assertTrue(true); }
    @Test public void b_test() { System.out.println("JUnit4B.b_test"); assertTrue(true); }
}
JAVAEOF

cat > "$BASE/p6-mixed-junit4-junit5-separate/src/test/java/JUnit5A.java" << 'JAVAEOF'
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JUnit5A {
    @Test void a_test() { System.out.println("JUnit5A.a_test"); assertTrue(true); }
    @Test void b_test() { System.out.println("JUnit5A.b_test"); assertTrue(true); }
}
JAVAEOF

echo "✓ Created p6-mixed-junit4-junit5-separate with multiple test classes"

# P6-005: TestNG + JUnit with multiple tests
mkdir -p "$BASE/p6-mixed-testng-junit/{src/test/java,target}"
cat > "$BASE/p6-mixed-testng-junit/pom.xml" << 'POMEOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>p6-mixed-testng-junit</artifactId>
    <version>1.0.0</version>
    <properties>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
    </properties>
    <dependencies>
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <version>4.13.2</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testng</groupId>
            <artifactId>testng</artifactId>
            <version>7.7.1</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.0.0-M9</version>
            </plugin>
            <plugin>
                <groupId>com.github.hanratty</groupId>
                <artifactId>test-order-maven-plugin</artifactId>
                <version>0.1.0-SNAPSHOT</version>
            </plugin>
        </plugins>
    </build>
</project>
POMEOF

cat > "$BASE/p6-mixed-testng-junit/src/test/java/JUnitTests.java" << 'JAVAEOF'
import org.junit.Test;
import static org.junit.Assert.*;

public class JUnitTests {
    @Test public void junit_a() { System.out.println("JUnitTests.junit_a"); assertTrue(true); }
    @Test public void junit_b() { System.out.println("JUnitTests.junit_b"); assertTrue(true); }
    @Test public void junit_c() { System.out.println("JUnitTests.junit_c"); assertTrue(true); }
}
JAVAEOF

cat > "$BASE/p6-mixed-testng-junit/src/test/java/TestNGTests.java" << 'JAVAEOF'
import org.testng.annotations.Test;

public class TestNGTests {
    @Test public void testng_a() { System.out.println("TestNGTests.testng_a"); }
    @Test public void testng_b() { System.out.println("TestNGTests.testng_b"); }
}
JAVAEOF

echo "✓ Created p6-mixed-testng-junit with multiple test classes"

# P6-006: Gradle with mixed frameworks
mkdir -p "$BASE/p6-gradle-mixed-frameworks/{src/test/java,target}"
cat > "$BASE/p6-gradle-mixed-frameworks/build.gradle" << 'GRADLEEOF'
plugins {
    id 'java'
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation 'junit:junit:4.13.2'
    testImplementation 'org.junit.jupiter:junit-jupiter:5.9.2'
    testImplementation 'org.testng:testng:7.7.1'
}

test {
    useJUnitPlatform()
}
GRADLEEOF

cat > "$BASE/p6-gradle-mixed-frameworks/src/test/java/GradleJUnit4Test.java" << 'JAVAEOF'
import org.junit.Test;
import static org.junit.Assert.*;

public class GradleJUnit4Test {
    @Test public void test_gradle_j4() { System.out.println("GradleJUnit4Test"); assertTrue(true); }
}
JAVAEOF

echo "✓ Created p6-gradle-mixed-frameworks"

echo "✓ Advanced P6 test projects created!"
