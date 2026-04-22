#!/bin/bash

# Create Edge Case Class Names Project
mkdir -p test-project-edge-names/{src/test/java,src/test/java/com/special,target/classes}

cd test-project-edge-names

cat > pom.xml << 'POMEOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>test-project-edge-names</artifactId>
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

# Test class with very long name (500+ chars)
LONGNAME="VeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryTest"

cat > src/test/java/${LONGNAME}.java << 'JAVAEOF'
import org.junit.Test;
import static org.junit.Assert.*;

public class VeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryTest {
    
    @Test
    public void testWithLongClassName() {
        assertTrue(true);
    }
}
JAVAEOF

# Test with special characters in class name (escaped in filename)
mkdir -p "src/test/java/com/special"
cat > "src/test/java/com/special/Test\$DollarSignTest.java" << 'JAVAEOF'
package com.special;

import org.junit.Test;
import static org.junit.Assert.*;

public class Test$DollarSignTest {
    
    @Test
    public void testDollarSign() {
        assertTrue(true);
    }
}
JAVAEOF

# Numeric-only class name test
cat > src/test/java/com/special/Test123.java << 'JAVAEOF'
package com.special;

import org.junit.Test;
import static org.junit.Assert.*;

public class Test123 {
    
    @Test
    public void testNumeric() {
        assertTrue(true);
    }
}
JAVAEOF

# Package with numbers
cat > src/test/java/com/special/Test999Package.java << 'JAVAEOF'
package com.special;

import org.junit.Test;
import static org.junit.Assert.*;

public class Test999Package {
    
    @Test
    public void testPackageNumbers() {
        assertTrue(true);
    }
}
JAVAEOF

# Test in default package
cat > src/test/java/DefaultPackageTest.java << 'JAVAEOF'
import org.junit.Test;
import static org.junit.Assert.*;

public class DefaultPackageTest {
    
    @Test
    public void testInDefaultPackage() {
        assertTrue(true);
    }
}
JAVAEOF

echo "Created test-project-edge-names"
cd ..

