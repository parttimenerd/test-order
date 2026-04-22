#!/bin/bash

# Create Mixed JUnit 3 and 4 Project
mkdir -p test-project-mixed-junit/{src/test/java/com/example,target/classes}

cd test-project-mixed-junit

cat > pom.xml << 'POMEOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>test-project-mixed-junit</artifactId>
    <version>1.0.0</version>

    <properties>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <!-- Both JUnit 3 and 4 -->
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <version>4.13.2</version>
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

# Mixed test - extends TestCase but also has @Test annotations
cat > src/test/java/com/example/MixedJUnit3and4Test.java << 'JAVAEOF'
package com.example;

import junit.framework.TestCase;
import org.junit.Test;
import static org.junit.Assert.*;

public class MixedJUnit3and4Test extends TestCase {
    
    // JUnit 3 style - setUp/tearDown
    public void setUp() {
        System.out.println("Setup called");
    }
    
    public void tearDown() {
        System.out.println("Teardown called");
    }
    
    // JUnit 3 style - test* method
    public void testJUnit3Style() {
        assertTrue(true);
    }
    
    // JUnit 4 style - @Test annotation
    @Test
    public void testJUnit4Style() {
        assertTrue(true);
    }
    
    // Another JUnit 4 style
    @Test
    public void anotherTestWithAnnotation() {
        assertEquals(1, 1);
    }
}
JAVAEOF

# Pure JUnit 3 test
cat > src/test/java/com/example/PureJUnit3Test.java << 'JAVAEOF'
package com.example;

import junit.framework.TestCase;

public class PureJUnit3Test extends TestCase {
    
    public void testOne() {
        assertTrue(true);
    }
    
    public void testTwo() {
        assertTrue(true);
    }
}
JAVAEOF

# Pure JUnit 4 test
cat > src/test/java/com/example/PureJUnit4Test.java << 'JAVAEOF'
package com.example;

import org.junit.Test;
import static org.junit.Assert.*;

public class PureJUnit4Test {
    
    @Test
    public void testOne() {
        assertTrue(true);
    }
    
    @Test
    public void testTwo() {
        assertTrue(true);
    }
}
JAVAEOF

echo "Created test-project-mixed-junit"
cd ..

