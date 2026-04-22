#!/usr/bin/env python3
"""
Generate large-scale test projects with test-order plugin enabled.
"""

import os
import sys
from pathlib import Path

def create_test_class(class_num, methods_per_class=5):
    """Generate a single test class with multiple test methods."""
    class_name = f"Test{class_num:05d}"
    methods = []
    
    for method_num in range(methods_per_class):
        method_name = f"test{method_num:03d}"
        methods.append(f"""
    @org.junit.Test
    public void {method_name}() {{
        org.junit.Assert.assertTrue(true);
    }}""")
    
    return f"""package com.example.tests;

public class {class_name} {{
{chr(10).join(methods)}
}}
"""

def create_pom_xml_with_plugin(num_classes, num_methods):
    """Generate a Maven pom.xml with test-order plugin."""
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>large-scale-test-with-plugin</artifactId>
    <version>1.0.0</version>
    
    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
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
                <artifactId>maven-surefire-plugin</artifactId>
                <version>2.22.2</version>
                <configuration>
                    <includes>
                        <include>**/*Test.java</include>
                    </includes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
"""

def create_large_test_project(output_dir, num_classes, methods_per_class=5):
    """Create a complete large test project."""
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)
    
    # Create source structure
    src_test_dir = output_path / "src" / "test" / "java" / "com" / "example" / "tests"
    src_test_dir.mkdir(parents=True, exist_ok=True)
    
    # Create test classes
    print(f"Generating {num_classes} test classes with {methods_per_class} methods each...")
    for class_num in range(num_classes):
        if class_num % 100 == 0:
            print(f"  Progress: {class_num}/{num_classes}")
        
        test_code = create_test_class(class_num, methods_per_class)
        class_file = src_test_dir / f"Test{class_num:05d}.java"
        class_file.write_text(test_code)
    
    # Create pom.xml
    pom_path = output_path / "pom.xml"
    pom_path.write_text(create_pom_xml_with_plugin(num_classes, methods_per_class))
    
    total_tests = num_classes * methods_per_class
    print(f"Created project with {num_classes} test classes, {methods_per_class} methods each = {total_tests} tests")
    print(f"Project location: {output_path}")

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: generate_large_test_project_with_plugin.py <output_dir> <num_classes> [methods_per_class]")
        sys.exit(1)
    
    output_dir = sys.argv[1]
    num_classes = int(sys.argv[2])
    methods_per_class = int(sys.argv[3]) if len(sys.argv) > 3 else 5
    
    create_large_test_project(output_dir, num_classes, methods_per_class)
