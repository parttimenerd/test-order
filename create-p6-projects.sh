#!/bin/bash
set -e

BASE_DIR="/Users/i560383_1/code/experiments/test-order"
cd "$BASE_DIR"

# Project 1: JUnit 4 + JUnit 5
mkdir -p p6-mixed-junit4-junit5/{src/test/java,target}
cat > p6-mixed-junit4-junit5/pom.xml << 'POMEOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>p6-mixed-junit4-junit5</artifactId>
    <version>1.0.0</version>

    <properties>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
    </properties>

    <dependencies>
        <!-- JUnit 4 -->
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <version>4.13.2</version>
            <scope>test</scope>
        </dependency>
        <!-- JUnit 5 -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
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

# JUnit 4 tests
cat > p6-mixed-junit4-junit5/src/test/java/JUnit4TestA.java << 'JAVAEOF'
import org.junit.Test;
import static org.junit.Assert.*;

public class JUnit4TestA {
    @Test
    public void testOne() {
        System.out.println("JUnit4TestA.testOne");
        assertTrue(true);
    }
    @Test
    public void testTwo() {
        System.out.println("JUnit4TestA.testTwo");
        assertTrue(true);
    }
}
JAVAEOF

# JUnit 5 tests
cat > p6-mixed-junit4-junit5/src/test/java/JUnit5TestB.java << 'JAVAEOF'
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JUnit5TestB {
    @Test
    void testOne() {
        System.out.println("JUnit5TestB.testOne");
        assertTrue(true);
    }
    @Test
    void testTwo() {
        System.out.println("JUnit5TestB.testTwo");
        assertTrue(true);
    }
}
JAVAEOF

echo "✓ Created p6-mixed-junit4-junit5"

# Project 2: JUnit + Kotest
mkdir -p p6-mixed-junit-kotest/{src/test/kotlin,src/test/java,target}
cat > p6-mixed-junit-kotest/pom.xml << 'POMEOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>p6-mixed-junit-kotest</artifactId>
    <version>1.0.0</version>

    <properties>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
        <kotlin.version>1.8.10</kotlin.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <version>4.13.2</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.kotest</groupId>
            <artifactId>kotest-runner-junit5</artifactId>
            <version>5.5.5</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.kotest</groupId>
            <artifactId>kotest-assertions-core</artifactId>
            <version>5.5.5</version>
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

cat > p6-mixed-junit-kotest/src/test/java/JUnitTestForKotest.java << 'JAVAEOF'
import org.junit.Test;
import static org.junit.Assert.*;

public class JUnitTestForKotest {
    @Test
    public void testJunit() {
        System.out.println("JUnitTestForKotest.testJunit");
        assertTrue(true);
    }
}
JAVAEOF

echo "✓ Created p6-mixed-junit-kotest"

# Project 3: JUnit + TestNG (unsupported)
mkdir -p p6-mixed-junit-testng/{src/test/java,target}
cat > p6-mixed-junit-testng/pom.xml << 'POMEOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>p6-mixed-junit-testng</artifactId>
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

cat > p6-mixed-junit-testng/src/test/java/JUnitTestNG.java << 'JAVAEOF'
import org.junit.Test;
import static org.junit.Assert.*;

public class JUnitTestNG {
    @Test
    public void testJunit() {
        System.out.println("JUnitTestNG.testJunit");
        assertTrue(true);
    }
}
JAVAEOF

cat > p6-mixed-junit-testng/src/test/java/TestNGTest.java << 'JAVAEOF'
import org.testng.annotations.Test;
import org.testng.Assert;

public class TestNGTest {
    @Test
    public void testTestNG() {
        System.out.println("TestNGTest.testTestNG");
        Assert.assertTrue(true);
    }
}
JAVAEOF

echo "✓ Created p6-mixed-junit-testng"

# Project 4: All three frameworks
mkdir -p p6-mixed-all-three/{src/test/java,target}
cat > p6-mixed-all-three/pom.xml << 'POMEOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>p6-mixed-all-three</artifactId>
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
            <groupId>io.kotest</groupId>
            <artifactId>kotest-runner-junit5</artifactId>
            <version>5.5.5</version>
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

cat > p6-mixed-all-three/src/test/java/AllFrameworksTest.java << 'JAVAEOF'
import org.junit.Test;
import org.junit.jupiter.api.Test;
import org.testng.annotations.Test;
import static org.junit.Assert.*;

public class AllFrameworksTest {
    @org.junit.Test
    public void testJunit4() {
        System.out.println("AllFrameworksTest.testJunit4");
        assertTrue(true);
    }
    
    @org.junit.jupiter.api.Test
    void testJunit5() {
        System.out.println("AllFrameworksTest.testJunit5");
        assertTrue(true);
    }
    
    @org.testng.annotations.Test
    public void testTestNG() {
        System.out.println("AllFrameworksTest.testTestNG");
        assertTrue(true);
    }
}
JAVAEOF

echo "✓ Created p6-mixed-all-three"

echo "✓ All mixed framework projects created!"
