#!/bin/bash

# Create Windows Path Handling Edge Cases Project
mkdir -p test-project-windows-edge/{src/test/java/com/windows,target/classes}

cd test-project-windows-edge

cat > pom.xml << 'POMEOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>test-project-windows-edge</artifactId>
    <version>1.0.0</version>

    <properties>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
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

# Test class with reserved Windows names embedded
cat > src/test/java/com/windows/WindowsReservedNamesTest.java << 'JAVAEOF'
package com.windows;

import org.junit.Test;
import static org.junit.Assert.*;

public class WindowsReservedNamesTest {
    
    @Test
    public void testWithConInPath() {
        // Simulates Windows CON reserved name
        String path = "test/CON/file.txt";
        assertTrue(!path.isEmpty());
    }
    
    @Test
    public void testWithPrnInPath() {
        // Simulates Windows PRN reserved name
        String path = "test/PRN/file.txt";
        assertTrue(!path.isEmpty());
    }
    
    @Test
    public void testWithAuxInPath() {
        // Simulates Windows AUX reserved name
        String path = "test/AUX/file.txt";
        assertTrue(!path.isEmpty());
    }
    
    @Test
    public void testWithCom1InPath() {
        // Simulates Windows COM port name
        String path = "test/COM1/file.txt";
        assertTrue(!path.isEmpty());
    }
}
JAVAEOF

# Test with paths that would be problematic on Windows
cat > src/test/java/com/windows/LongPathTest.java << 'JAVAEOF'
package com.windows;

import org.junit.Test;
import static org.junit.Assert.*;

public class LongPathTest {
    
    @Test
    public void testWithLongPath() {
        // Windows has 260 character limit (MAX_PATH)
        StringBuilder path = new StringBuilder("a");
        for (int i = 0; i < 250; i++) {
            path.append("/b");
        }
        assertTrue(path.length() > 260);
    }
    
    @Test
    public void testPathWithBackslashes() {
        String winPath = "C:\\Users\\test\\path\\to\\file.txt";
        assertTrue(winPath.contains("\\"));
    }
    
    @Test
    public void testUNCPath() {
        String uncPath = "\\\\server\\share\\file.txt";
        assertTrue(uncPath.startsWith("\\\\"));
    }
}
JAVAEOF

# Test that checks case sensitivity issues
cat > src/test/java/com/windows/CaseSensitivityTest.java << 'JAVAEOF'
package com.windows;

import org.junit.Test;
import static org.junit.Assert.*;

public class CaseSensitivityTest {
    
    @Test
    public void testCaseInsensitiveEquals() {
        // On Windows filesystem, "File.txt" == "file.txt"
        // But in Java, they're different strings
        String file1 = "File.txt";
        String file2 = "file.txt";
        assertNotEquals(file1, file2); // Java strings are case-sensitive
    }
    
    @Test
    public void testPathCaseVariations() {
        String path1 = "C:\\TEST\\FILE";
        String path2 = "C:\\test\\file";
        // On macOS/Linux these are different
        // On Windows they refer to the same file
        assertTrue(true);
    }
}
JAVAEOF

echo "Created test-project-windows-edge"
cd ..

