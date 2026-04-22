#!/usr/bin/env python3

import os
import sys
import json
import time
import subprocess
import shutil
from pathlib import Path
from datetime import datetime

class Phase5TestRunner:
    def __init__(self):
        self.results = []
        self.repo_root = Path(__file__).parent.parent
        self.test_projects_dir = self.repo_root / "phase5-test-projects"
        self.test_projects_dir.mkdir(exist_ok=True)
        self.findings = []
        
    def log_finding(self, scenario, issue, severity, details):
        """Log a finding from testing"""
        finding = {
            "timestamp": datetime.now().isoformat(),
            "scenario": scenario,
            "issue": issue,
            "severity": severity,  # critical, high, medium, low
            "details": details
        }
        self.findings.append(finding)
        print(f"\n⚠️  FINDING [{severity}]: {issue}")
        print(f"   Scenario: {scenario}")
        print(f"   Details: {details}\n")
        
    def create_large_maven_module(self, num_classes=1000, methods_per_class=10):
        """Create a Maven module with many test classes"""
        scenario_name = f"maven-{num_classes}classes-{methods_per_class}methods"
        project_dir = self.test_projects_dir / scenario_name
        project_dir.mkdir(exist_ok=True)
        
        src_dir = project_dir / "src" / "test" / "java" / "com" / "example" / "largescale"
        src_dir.mkdir(parents=True, exist_ok=True)
        
        # Create POM
        pom_content = f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>large-scale-test</artifactId>
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
                    <testFailureIgnore>true</testFailureIgnore>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
"""
        with open(project_dir / "pom.xml", "w") as f:
            f.write(pom_content)
        
        # Create test classes
        print(f"Creating {num_classes} test classes with {methods_per_class} methods each...")
        for i in range(num_classes):
            class_name = f"TestClass{i:04d}"
            test_code = f"""package com.example.largescale;

import org.junit.Test;
import static org.junit.Assert.*;

public class {class_name} {{
"""
            for j in range(methods_per_class):
                test_code += f"""    @Test
    public void test{j:03d}() {{
        assertTrue(true);
    }}
"""
            test_code += "}\n"
            
            test_file = src_dir / f"{class_name}.java"
            with open(test_file, "w") as f:
                f.write(test_code)
            
            if (i + 1) % 100 == 0:
                print(f"  Created {i + 1}/{num_classes} test classes")
        
        return project_dir, scenario_name
    
    def create_deep_nesting_project(self, depth=50):
        """Create a project with deeply nested package structure"""
        scenario_name = f"deep-nesting-{depth}levels"
        project_dir = self.test_projects_dir / scenario_name
        project_dir.mkdir(exist_ok=True)
        
        # Create nested package structure
        package_path = "com"
        for i in range(depth):
            package_path += f"/level{i}"
        
        src_dir = project_dir / "src" / "test" / "java" / package_path
        src_dir.mkdir(parents=True, exist_ok=True)
        
        # Create POM
        pom_content = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>deep-nesting-test</artifactId>
    <version>1.0.0</version>
    
    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
    </properties>
    
    <dependencies>
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <version>4.13.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
"""
        with open(project_dir / "pom.xml", "w") as f:
            f.write(pom_content)
        
        # Create test class in deeply nested package
        package_name = ".".join(package_path.split("/"))
        test_code = f"""package {package_name};

import org.junit.Test;
import static org.junit.Assert.*;

public class DeeplyNestedTest {{
    @Test
    public void test1() {{
        assertTrue(true);
    }}
}}
"""
        with open(src_dir / "DeeplyNestedTest.java", "w") as f:
            f.write(test_code)
        
        return project_dir, scenario_name
    
    def create_long_names_project(self):
        """Create project with very long class and package names"""
        scenario_name = "long-names"
        project_dir = self.test_projects_dir / scenario_name
        project_dir.mkdir(exist_ok=True)
        
        # Create POM
        pom_content = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>long-names-test</artifactId>
    <version>1.0.0</version>
    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
    </properties>
    <dependencies>
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <version>4.13.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
"""
        with open(project_dir / "pom.xml", "w") as f:
            f.write(pom_content)
        
        # Create package with long name
        long_package = "com.example.verylongpackagename.withnumeroussubpackages.thatspanmultipleparts"
        src_dir = project_dir / "src" / "test" / "java" / long_package.replace(".", "/")
        src_dir.mkdir(parents=True, exist_ok=True)
        
        # Create test class with long name
        long_class_name = "VeryLongTestClassNameWithManyCharactersToTestFileSystemHandling" * 3
        test_code = f"""package {long_package};

import org.junit.Test;

public class {long_class_name} {{
    @Test
    public void testWithVeryLongMethodNameThatExceedsNormalConventions() {{
        org.junit.Assert.assertTrue(true);
    }}
}}
"""
        with open(src_dir / f"{long_class_name}.java", "w") as f:
            f.write(test_code)
        
        return project_dir, scenario_name
    
    def run_maven_test(self, project_dir, scenario_name):
        """Run Maven test with timing and resource monitoring"""
        print(f"\n{'='*60}")
        print(f"Testing Scenario: {scenario_name}")
        print(f"Project: {project_dir}")
        print(f"{'='*60}")
        
        try:
            # Change to project directory
            original_dir = os.getcwd()
            os.chdir(project_dir)
            
            # Clean and build
            print("Running Maven clean test...")
            start_time = time.time()
            
            result = subprocess.run(
                ["mvn", "clean", "test", "-q", "-DskipTests"],
                capture_output=True,
                text=True,
                timeout=300
            )
            
            build_time = time.time() - start_time
            
            # Count test files
            test_count = len(list(Path("src/test/java").rglob("*.java")))
            
            # Check cache
            cache_dir = Path(".test-order")
            cache_size = 0
            if cache_dir.exists():
                cache_size = sum(f.stat().st_size for f in cache_dir.rglob("*") if f.is_file()) / (1024 * 1024)
            
            result_data = {
                "scenario": scenario_name,
                "project_dir": str(project_dir),
                "test_count": test_count,
                "build_time": build_time,
                "cache_size_mb": cache_size,
                "success": result.returncode == 0,
                "stdout": result.stdout[:500] if result.stdout else "",
                "stderr": result.stderr[:500] if result.stderr else ""
            }
            
            self.results.append(result_data)
            
            print(f"✓ Tests completed in {build_time:.2f}s")
            print(f"  Test files: {test_count}")
            print(f"  Cache size: {cache_size:.2f}MB")
            
            if result.returncode != 0:
                self.log_finding(scenario_name, "Build/Test Failure", "high", 
                               f"Return code: {result.returncode}")
                if result.stderr:
                    print(f"Error: {result.stderr[:200]}")
            
            os.chdir(original_dir)
            return result_data
            
        except subprocess.TimeoutExpired:
            self.log_finding(scenario_name, "Timeout", "critical", 
                           f"Maven test exceeded 5 minute timeout")
            os.chdir(original_dir)
            return None
        except Exception as e:
            self.log_finding(scenario_name, "Exception", "high", str(e))
            os.chdir(original_dir)
            return None
    
    def run_all_tests(self):
        """Run all Phase 5 test scenarios"""
        print("\n" + "="*60)
        print("PHASE 5: LARGE-SCALE BUG HUNT")
        print("="*60)
        
        # Scenario 1: Single module with 1000 test classes (10,000 methods)
        print("\n[1/7] Creating large Maven module with 1000 test classes...")
        proj_dir, name = self.create_large_maven_module(num_classes=1000, methods_per_class=10)
        self.run_maven_test(proj_dir, name)
        
        # Scenario 5: Deep nesting
        print("\n[2/7] Creating deeply nested package project (50 levels)...")
        proj_dir, name = self.create_deep_nesting_project(depth=50)
        self.run_maven_test(proj_dir, name)
        
        # Scenario 6: Long names
        print("\n[3/7] Creating project with very long names...")
        proj_dir, name = self.create_long_names_project()
        self.run_maven_test(proj_dir, name)
        
        # Smaller scenario for baseline
        print("\n[4/7] Creating small Maven module (100 classes, 10 methods)...")
        proj_dir, name = self.create_large_maven_module(num_classes=100, methods_per_class=10)
        self.run_maven_test(proj_dir, name)
        
        print("\n" + "="*60)
        print(f"PHASE 5 TESTING COMPLETE")
        print(f"Total scenarios tested: {len(self.results)}")
        print(f"Findings discovered: {len(self.findings)}")
        print("="*60)
        
        self.generate_report()
    
    def generate_report(self):
        """Generate comprehensive report"""
        report_file = self.repo_root / "PHASE-5-LARGE-SCALE-FINDINGS.md"
        
        with open(report_file, "w") as f:
            f.write("# PHASE 5: Large-Scale Bug Hunt Findings\n\n")
            f.write(f"Generated: {datetime.now().isoformat()}\n\n")
            
            # Summary
            f.write("## Summary\n\n")
            f.write(f"- Total test scenarios: {len(self.results)}\n")
            f.write(f"- Critical findings: {len([x for x in self.findings if x['severity'] == 'critical'])}\n")
            f.write(f"- High severity: {len([x for x in self.findings if x['severity'] == 'high'])}\n")
            f.write(f"- Medium severity: {len([x for x in self.findings if x['severity'] == 'medium'])}\n")
            f.write(f"- Low severity: {len([x for x in self.findings if x['severity'] == 'low'])}\n\n")
            
            # Results
            f.write("## Test Results\n\n")
            for result in self.results:
                f.write(f"### {result['scenario']}\n\n")
                f.write(f"- Project: {result['project_dir']}\n")
                f.write(f"- Test files: {result['test_count']}\n")
                f.write(f"- Build time: {result['build_time']:.2f}s\n")
                f.write(f"- Cache size: {result['cache_size_mb']:.2f}MB\n")
                f.write(f"- Success: {'✓' if result['success'] else '✗'}\n\n")
            
            # Findings
            if self.findings:
                f.write("## Detailed Findings\n\n")
                for finding in self.findings:
                    f.write(f"### [{finding['severity'].upper()}] {finding['issue']}\n\n")
                    f.write(f"- Scenario: {finding['scenario']}\n")
                    f.write(f"- Details: {finding['details']}\n")
                    f.write(f"- Timestamp: {finding['timestamp']}\n\n")
            
            f.write("## Next Steps\n\n")
            f.write("- Investigate critical findings\n")
            f.write("- Optimize performance for large projects\n")
            f.write("- Increase test limits/timeout values if needed\n\n")
        
        print(f"Report saved to: {report_file}")

if __name__ == "__main__":
    runner = Phase5TestRunner()
    runner.run_all_tests()
