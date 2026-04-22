#!/bin/bash

# Create Legacy JUnit 4.0-4.8 Project
mkdir -p test-project-junit4-old/{src/test/java/com/example,target/classes}

cd test-project-junit4-old

cat > pom.xml << 'POMEOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>test-project-junit4-old</artifactId>
    <version>1.0.0</version>

    <properties>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <!-- JUnit 4.0 - early version -->
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <version>4.4</version>
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

# Create early JUnit 4 test (no assumptions, basic @Test)
cat > src/test/java/com/example/EarlyJUnit4Test.java << 'JAVAEOF'
package com.example;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import static org.junit.Assert.*;

public class EarlyJUnit4Test {
    
    private String value;
    
    @BeforeClass
    public static void setUpClass() {
        System.out.println("Early JUnit 4 - before class");
    }
    
    @AfterClass
    public static void tearDownClass() {
        System.out.println("Early JUnit 4 - after class");
    }
    
    @Before
    public void setUp() {
        value = "initialized";
    }
    
    @After
    public void tearDown() {
        value = null;
    }
    
    @Test
    public void testInitialized() {
        assertEquals("initialized", value);
    }
    
    @Test
    public void testNotNull() {
        assertNotNull(value);
    }
    
    @Test
    public void testMultipleAssertions() {
        assertNotNull(value);
        assertEquals("initialized", value);
        assertTrue(value.length() > 0);
    }
}
JAVAEOF

# Create test with no annotations - just test* methods in JUnit 4
cat > src/test/java/com/example/JUnit4NoAnnotationsTest.java << 'JAVAEOF'
package com.example;

public class JUnit4NoAnnotationsTest {
    
    public void testMethodOne() {
        // This looks like a test method but has no @Test annotation
        assert true;
    }
    
    public void testMethodTwo() {
        int result = 1 + 1;
        assert result == 2;
    }
    
    public void notATestMethod() {
        // Should not run as test
    }
}
JAVAEOF

echo "Created test-project-junit4-old"
cd ..

