#!/usr/bin/env python3

import os
import sys
import json
import time
import subprocess
from pathlib import Path
from datetime import datetime

class Phase5PluginLoadTest:
    def __init__(self):
        self.results = []
        self.findings = []
        self.repo_root = Path(__file__).parent.parent
        self.test_projects_dir = self.repo_root / "phase5-plugin-tests"
        self.test_projects_dir.mkdir(exist_ok=True)
        
    def log_finding(self, scenario, issue, severity, details):
        """Log a finding"""
        finding = {
            "timestamp": datetime.now().isoformat(),
            "scenario": scenario,
            "issue": issue,
            "severity": severity,
            "details": details
        }
        self.findings.append(finding)
        print(f"\n⚠️  [{severity.upper()}] {issue}")
        print(f"   Scenario: {scenario}")
        print(f"   Details: {details}\n")
    
    def create_plugin_test_project(self, num_classes=500):
        """Create project configured with test-order Maven plugin"""
        scenario_name = f"plugin-test-{num_classes}classes"
        project_dir = self.test_projects_dir / scenario_name
        project_dir.mkdir(exist_ok=True)
        
        src_dir = project_dir / "src" / "test" / "java" / "com" / "example"
        src_dir.mkdir(parents=True, exist_ok=True)
        
        pom = f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>plugin-load-test</artifactId>
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
    
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>2.22.2</version>
                <dependencies>
                    <dependency>
                        <groupId>me.bechberger</groupId>
                        <artifactId>test-order-junit</artifactId>
                        <version>0.1.0-SNAPSHOT</version>
                    </dependency>
                </dependencies>
                <configuration>
                    <properties>
                        <property>
                            <name>listener</name>
                            <value>me.bechberger.TestOrderListener</value>
                        </property>
                    </properties>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
"""
        with open(project_dir / "pom.xml", "w") as f:
            f.write(pom)
        
        print(f"Creating {num_classes} test classes...")
        for i in range(num_classes):
            class_name = f"Test{i:04d}"
            test_code = f"""package com.example;
import org.junit.Test;
public class {class_name} {{
    @Test public void test1() {{ org.junit.Assert.assertTrue(true); }}
    @Test public void test2() {{ org.junit.Assert.assertTrue(true); }}
    @Test public void test3() {{ org.junit.Assert.assertTrue(true); }}
}}
"""
            with open(src_dir / f"{class_name}.java", "w") as f:
                f.write(test_code)
            
            if (i + 1) % 100 == 0:
                print(f"  Created {i + 1}/{num_classes}")
        
        return project_dir, scenario_name
    
    def run_plugin_test(self, project_dir, scenario_name):
        """Run test with plugin enabled"""
        print(f"\n{'='*60}")
        print(f"Testing with plugin: {scenario_name}")
        print(f"Project: {project_dir}")
        print(f"{'='*60}\n")
        
        original_dir = os.getcwd()
        os.chdir(project_dir)
        
        try:
            # Clean first
            print("Cleaning...")
            subprocess.run(["mvn", "clean", "-q"], timeout=60)
            
            # Run with plugin
            print("Running tests with test-order plugin (learn mode)...")
            start_time = time.time()
            
            result = subprocess.run(
                ["mvn", "test", "-q"],
                capture_output=True,
                text=True,
                timeout=600,
                env={**os.environ, "TEST_ORDER_MODE": "learn"}
            )
            
            elapsed = time.time() - start_time
            
            # Check for test-order cache and data
            cache_dir = Path(".test-order")
            cache_size = 0
            cache_exists = cache_dir.exists()
            
            if cache_exists:
                for f in cache_dir.rglob("*"):
                    if f.is_file():
                        cache_size += f.stat().st_size / (1024 * 1024)
            
            # Count tests
            test_count = len(list(Path(".").rglob("*Test*.java")))
            
            result_data = {
                "scenario": scenario_name,
                "elapsed_time": elapsed,
                "cache_size_mb": cache_size,
                "cache_exists": cache_exists,
                "test_count": test_count,
                "success": result.returncode == 0,
                "return_code": result.returncode,
                "stdout_lines": len(result.stdout.split('\n'))
            }
            
            self.results.append(result_data)
            
            print(f"✓ Completed in {elapsed:.2f}s")
            print(f"  Cache size: {cache_size:.2f}MB")
            print(f"  Cache exists: {cache_exists}")
            print(f"  Test count: {test_count}")
            print(f"  Return code: {result.returncode}")
            
            if result.returncode != 0:
                self.log_finding(scenario_name, "Plugin test failed", "high",
                               f"Return code: {result.returncode}")
                if result.stderr:
                    print(f"Error:\n{result.stderr[:500]}")
            
            if elapsed > 300:
                self.log_finding(scenario_name, "Slow plugin execution", "medium",
                               f"Took {elapsed:.2f}s with plugin for {test_count} tests")
            
            os.chdir(original_dir)
            return result_data
            
        except subprocess.TimeoutExpired:
            self.log_finding(scenario_name, "Plugin timeout", "critical",
                           f"Plugin test exceeded 10 minute timeout")
            os.chdir(original_dir)
            return None
        except Exception as e:
            self.log_finding(scenario_name, "Plugin exception", "critical", str(e))
            os.chdir(original_dir)
            return None
    
    def run_stress_tests(self):
        """Run stress tests with plugins"""
        print("\n" + "="*70)
        print("PHASE 5: PLUGIN LOAD & STRESS TESTS")
        print("="*70)
        
        # Build parent project first
        print("\nBuilding test-order parent project...")
        try:
            result = subprocess.run(
                ["mvn", "clean", "install", "-q", "-DskipTests"],
                cwd=str(self.repo_root),
                capture_output=True,
                text=True,
                timeout=300
            )
            if result.returncode != 0:
                print(f"⚠️  Parent build failed: {result.stderr[:200]}")
                self.log_finding("setup", "Parent build failed", "critical", 
                               f"Cannot build parent: {result.returncode}")
        except Exception as e:
            print(f"⚠️  Parent build error: {e}")
            self.log_finding("setup", "Parent build exception", "critical", str(e))
        
        # Test different scales
        scales = [100, 250, 500]
        
        for scale in scales:
            print(f"\n[TEST] Creating and testing {scale} class project...")
            proj_dir, name = self.create_plugin_test_project(num_classes=scale)
            self.run_plugin_test(proj_dir, name)
        
        print("\n" + "="*70)
        print(f"PHASE 5 PLUGIN TESTING COMPLETE")
        print(f"Tests run: {len(self.results)}")
        print(f"Findings: {len(self.findings)}")
        print("="*70)
        
        self.generate_report()
    
    def generate_report(self):
        """Generate report"""
        report_file = self.repo_root / "PHASE-5-PLUGIN-LOAD-TEST-REPORT.md"
        
        with open(report_file, "w") as f:
            f.write("# PHASE 5: Plugin Load & Stress Test Report\n\n")
            f.write(f"Generated: {datetime.now().isoformat()}\n\n")
            
            # Summary
            f.write("## Summary\n\n")
            f.write(f"- Plugin tests run: {len(self.results)}\n")
            critical = len([x for x in self.findings if x['severity'] == 'critical'])
            high = len([x for x in self.findings if x['severity'] == 'high'])
            f.write(f"- Critical findings: {critical}\n")
            f.write(f"- High severity findings: {high}\n\n")
            
            # Results table
            f.write("## Test Results\n\n")
            if self.results:
                f.write("| Scenario | Tests | Time (s) | Cache (MB) | Exists | Status |\n")
                f.write("|----------|-------|---------|-----------|--------|--------|\n")
                for r in self.results:
                    status = "✓" if r['success'] else "✗"
                    cache_flag = "✓" if r['cache_exists'] else "✗"
                    f.write(f"| {r['scenario']} | {r['test_count']} | {r['elapsed_time']:.2f} | {r['cache_size_mb']:.2f} | {cache_flag} | {status} |\n")
            
            # Findings
            if self.findings:
                f.write("\n## Critical Findings\n\n")
                for finding in sorted(self.findings, key=lambda x: {'critical': 0, 'high': 1, 'medium': 2, 'low': 3}.get(x['severity'], 4)):
                    f.write(f"### [{finding['severity'].upper()}] {finding['issue']}\n\n")
                    f.write(f"- Scenario: {finding['scenario']}\n")
                    f.write(f"- Details: {finding['details']}\n\n")

if __name__ == "__main__":
    runner = Phase5PluginLoadTest()
    runner.run_stress_tests()
