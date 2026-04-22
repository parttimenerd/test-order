#!/usr/bin/env python3

import os
import subprocess
from pathlib import Path

def create_junit4_project(version):
    """Create a test project for a JUnit 4 version"""
    proj_name = f"p5-junit4-v{version}-test"
    proj_dir = f"/Users/i560383_1/code/experiments/test-order/{proj_name}"
    
    # Create directories
    Path(f"{proj_dir}/src/test/java").mkdir(parents=True, exist_ok=True)
    
    # Create POM
    pom_content = f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.test</groupId>
    <artifactId>junit4-v{version}-test</artifactId>
    <version>1.0.0</version>
    <properties>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
    </properties>
    <dependencies>
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <version>{version}</version>
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
</project>"""
    
    with open(f"{proj_dir}/pom.xml", "w") as f:
        f.write(pom_content)
    
    # Create test class
    test_content = """import org.junit.Test;
import static org.junit.Assert.*;

public class BasicTest {
    @Test
    public void testBasic() {
        assertTrue(true);
    }
}"""
    
    with open(f"{proj_dir}/src/test/java/BasicTest.java", "w") as f:
        f.write(test_content)
    
    print(f"Created JUnit 4 v{version} project")

def create_junit5_project(version):
    """Create a test project for a JUnit 5 version"""
    proj_name = f"p5-junit5-v{version}-test"
    proj_dir = f"/Users/i560383_1/code/experiments/test-order/{proj_name}"
    
    # Create directories
    Path(f"{proj_dir}/src/test/java").mkdir(parents=True, exist_ok=True)
    
    # Create POM
    pom_content = f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.test</groupId>
    <artifactId>junit5-v{version}-test</artifactId>
    <version>1.0.0</version>
    <properties>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>{version}</version>
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
</project>"""
    
    with open(f"{proj_dir}/pom.xml", "w") as f:
        f.write(pom_content)
    
    # Create test class
    test_content = """import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class BasicTest {
    @Test
    public void testBasic() {
        assertTrue(true);
    }
}"""
    
    with open(f"{proj_dir}/src/test/java/BasicTest.java", "w") as f:
        f.write(test_content)
    
    print(f"Created JUnit 5 v{version} project")

# Create projects
junit4_versions = ["4.13.2", "4.12", "4.11", "4.10", "4.8.2"]
junit5_versions = ["5.10.0", "5.9.0", "5.5.0", "5.0.0"]

print("=== Generating JUnit 4.x projects ===")
for ver in junit4_versions:
    create_junit4_project(ver)

print("\n=== Generating JUnit 5.x projects ===")
for ver in junit5_versions:
    create_junit5_project(ver)

print("\nAll projects created!")

