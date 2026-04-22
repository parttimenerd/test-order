#!/bin/bash

# Create Legacy JUnit 3 Project
mkdir -p test-project-junit3/{src/test/java/com/example,target/classes}

cd test-project-junit3

cat > pom.xml << 'POMEOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>test-project-junit3</artifactId>
    <version>1.0.0</version>

    <properties>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <!-- Legacy JUnit 3.x -->
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <version>3.8.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.8.1</version>
                <configuration>
                    <source>11</source>
                    <target>11</target>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>2.22.2</version>
            </plugin>
            <!-- test-order plugin -->
            <plugin>
                <groupId>com.github.test-order</groupId>
                <artifactId>test-order-maven-plugin</artifactId>
                <version>1.0.0-SNAPSHOT</version>
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
POMEOF

# Create JUnit 3 test class - extends TestCase
cat > src/test/java/com/example/LegacyJUnit3Test.java << 'JAVAEOF'
package com.example;

import junit.framework.TestCase;

public class LegacyJUnit3Test extends TestCase {
    
    private String value;
    
    public void setUp() {
        value = "initialized";
    }
    
    public void tearDown() {
        value = null;
    }
    
    // Test method using test* naming convention
    public void testValueInitialized() {
        assertEquals("initialized", value);
    }
    
    public void testValueNotNull() {
        assertNotNull(value);
    }
    
    public void testValueEquals() {
        assertTrue("Value should equal 'initialized'", 
                   value.equals("initialized"));
    }
}
JAVAEOF

# Create another JUnit 3 test with suite() method
cat > src/test/java/com/example/LegacyJUnit3SuiteTest.java << 'JAVAEOF'
package com.example;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

public class LegacyJUnit3SuiteTest extends TestCase {
    
    public void testAlpha() {
        assertTrue(true);
    }
    
    public void testBeta() {
        assertTrue(true);
    }
    
    public void testGamma() {
        assertTrue(true);
    }
    
    public static Test suite() {
        TestSuite suite = new TestSuite(LegacyJUnit3SuiteTest.class);
        return suite;
    }
}
JAVAEOF

echo "Created test-project-junit3"
cd ..

