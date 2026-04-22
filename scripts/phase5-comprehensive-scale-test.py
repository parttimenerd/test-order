#!/usr/bin/env python3

import os
import sys
import json
import time
import subprocess
from pathlib import Path
from datetime import datetime

class Phase5ComprehensiveTest:
    def __init__(self):
        self.results = []
        self.findings = []
        self.repo_root = Path(__file__).parent.parent
        self.test_projects_dir = self.repo_root / "phase5-comprehensive-tests"
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
        
    def create_large_test_project(self, num_classes=500, test_methods_per_class=5):
        """Create large test project with test-order plugin configured"""
        scenario_name = f"large-{num_classes}classes-{test_methods_per_class}methods"
        project_dir = self.test_projects_dir / scenario_name
        project_dir.mkdir(exist_ok=True)
        
        src_dir = project_dir / "src" / "test" / "java" / "com" / "example"
        src_dir.mkdir(parents=True, exist_ok=True)
        
        # Create POM with test-order Maven plugin
        pom = f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    
    <groupId>com.example.largescale</groupId>
    <artifactId>large-scale-test</artifactId>
    <version>1.0.0</version>
    
    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
    
    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter-api</artifactId>
            <version>5.9.3</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter-engine</artifactId>
            <version>5.9.3</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
    
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
            </plugin>
            <plugin>
                <groupId>me.bechberger</groupId>
                <artifactId>test-order-maven-plugin</artifactId>
                <version>0.1.0-SNAPSHOT</version>
                <configuration>
                    <mode>learn</mode>
                </configuration>
                <executions>
                    <execution>
                        <goals>
                            <goal>learn</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
"""
        with open(project_dir / "pom.xml", "w") as f:
            f.write(pom)
        
        print(f"Creating {num_classes} test classes with {test_methods_per_class} methods each...")
        for i in range(num_classes):
            class_name = f"Test{i:05d}"
            methods = "".join([f"    @org.junit.jupiter.api.Test\n    public void test{j}() {{}}\n"
                              for j in range(test_methods_per_class)])
            test_code = f"""package com.example;
import org.junit.jupiter.api.Test;

public class {class_name} {{
{methods}}}
"""
            with open(src_dir / f"{class_name}.java", "w") as f:
                f.write(test_code)
            
            if (i + 1) % 100 == 0:
                print(f"  Created {i + 1}/{num_classes}")
        
        return project_dir, scenario_name
    
    def run_comprehensive_test(self, project_dir, scenario_name, mode="learn"):
        """Run comprehensive test and collect metrics"""
        print(f"\n{'='*70}")
        print(f"Testing: {scenario_name} (mode={mode})")
        print(f"Project: {project_dir.name}")
        print(f"{'='*70}")
        
        original_dir = os.getcwd()
        os.chdir(project_dir)
        
        try:
            # Clean
            print("Cleaning...")
            subprocess.run(["mvn", "clean", "-q"], timeout=120, capture_output=True)
            
            # Run test-order plugin
            print(f"Running test-order plugin in {mode} mode...")
            start_time = time.time()
            
            result = subprocess.run(
                ["mvn", "test-order:combined", "-q"],
                capture_output=True,
                text=True,
                timeout=600
            )
            
            elapsed = time.time() - start_time
            
            # Analyze cache
            cache_dir = Path(".test-order")
            cache_size = 0
            cache_files = 0
            
            if cache_dir.exists():
                for f in cache_dir.rglob("*"):
                    if f.is_file():
                        cache_size += f.stat().st_size / (1024 * 1024)
                        cache_files += 1
            
            # Count test files
            test_files = len(list(Path("src/test/java").rglob("*.java")))
            test_methods = sum(1 for f in Path("src/test/java").rglob("*.java")
                             for line in open(f).readlines()
                             if "@Test" in line or "@org.junit.jupiter.api.Test" in line)
            
            result_data = {
                "scenario": scenario_name,
                "elapsed_time": elapsed,
                "cache_size_mb": cache_size,
                "cache_files": cache_files,
                "test_files": test_files,
                "test_methods": test_methods,
                "success": result.returncode == 0,
                "return_code": result.returncode
            }
            
            self.results.append(result_data)
            
            print(f"✓ Completed in {elapsed:.2f}s")
            print(f"  Test files: {test_files}")
            print(f"  Test methods: {test_methods}")
            print(f"  Cache size: {cache_size:.2f}MB ({cache_files} files)")
            
            if elapsed > 600:
                self.log_finding(scenario_name, "Very slow execution", "high",
                               f"{elapsed:.2f}s for {test_files} classes")
            
            if cache_size > 500:
                self.log_finding(scenario_name, "Large cache size", "medium",
                               f"Cache: {cache_size:.2f}MB for {test_files} classes")
            
            if result.returncode != 0:
                self.log_finding(scenario_name, "Plugin execution failed", "high",
                               f"Return code: {result.returncode}")
                print(f"Stderr: {result.stderr[:300]}")
            
            os.chdir(original_dir)
            return result_data
            
        except subprocess.TimeoutExpired:
            self.log_finding(scenario_name, "Timeout", "critical",
                           f"Plugin exceeded 10 minute timeout")
            os.chdir(original_dir)
            return None
        except Exception as e:
            self.log_finding(scenario_name, "Exception", "critical", str(e))
            os.chdir(original_dir)
            return None
    
    def run_all_tests(self):
        """Run comprehensive Phase 5 tests"""
        print("\n" + "="*70)
        print("PHASE 5: COMPREHENSIVE LARGE-SCALE TESTING")
        print("="*70)
        
        # Ensure parent is built
        print("\nEnsuring parent project is built...")
        try:
            result = subprocess.run(
                ["mvn", "clean", "install", "-q", "-DskipTests"],
                cwd=str(self.repo_root),
                capture_output=True,
                text=True,
                timeout=300
            )
            if result.returncode == 0:
                print("✓ Parent project built")
            else:
                print(f"⚠️  Parent build returned code {result.returncode}")
        except Exception as e:
            print(f"⚠️  Parent build error: {e}")
        
        # Test progressively larger scales
        test_cases = [
            (100, 10),    # 100 classes, 10 methods = 1000 tests
            (250, 10),    # 250 classes, 10 methods = 2500 tests
            (500, 10),    # 500 classes, 10 methods = 5000 tests
            (750, 5),     # 750 classes, 5 methods = 3750 tests
            (1000, 5),    # 1000 classes, 5 methods = 5000 tests
        ]
        
        for idx, (num_classes, methods) in enumerate(test_cases, 1):
            total_tests = num_classes * methods
            print(f"\n[{idx}/{len(test_cases)}] Creating project: {num_classes} classes, {methods} methods ({total_tests} tests)...")
            
            proj_dir, name = self.create_large_test_project(num_classes, methods)
            self.run_comprehensive_test(proj_dir, name)
        
        print("\n" + "="*70)
        print("PHASE 5 COMPREHENSIVE TESTING COMPLETE")
        print(f"Scenarios tested: {len(self.results)}")
        print(f"Findings discovered: {len(self.findings)}")
        print("="*70)
        
        self.generate_final_report()
    
    def generate_final_report(self):
        """Generate final comprehensive report"""
        report_file = self.repo_root / "PHASE-5-COMPREHENSIVE-FINDINGS.md"
        
        with open(report_file, "w") as f:
            f.write("# PHASE 5: Comprehensive Large-Scale Bug Hunt\n\n")
            f.write(f"**Generated:** {datetime.now().isoformat()}\n\n")
            
            # Executive Summary
            f.write("## Executive Summary\n\n")
            f.write(f"- **Test Scenarios:** {len(self.results)}\n")
            f.write(f"- **Total Findings:** {len(self.findings)}\n")
            
            critical = len([x for x in self.findings if x['severity'] == 'critical'])
            high = len([x for x in self.findings if x['severity'] == 'high'])
            f.write(f"- **Critical Issues:** {critical}\n")
            f.write(f"- **High Priority Issues:** {high}\n\n")
            
            # Performance Analysis
            f.write("## Performance Analysis\n\n")
            if self.results:
                f.write("| Scenario | Classes | Methods | Time (s) | Cache (MB) | Status |\n")
                f.write("|----------|---------|---------|---------|-----------|--------|\n")
                for r in self.results:
                    status = "✓ PASS" if r['success'] else "✗ FAIL"
                    f.write(f"| {r['scenario'][:35]} | {r['test_files']} | {r['test_methods']} | {r['elapsed_time']:.2f} | {r['cache_size_mb']:.2f} | {status} |\n")
                
                # Calculate statistics
                times = [r['elapsed_time'] for r in self.results if r]
                caches = [r['cache_size_mb'] for r in self.results if r]
                total_tests = sum(r['test_methods'] for r in self.results if r)
                
                f.write(f"\n**Statistics:**\n")
                f.write(f"- Total test methods processed: {total_tests}\n")
                f.write(f"- Average execution time: {sum(times)/len(times):.2f}s\n")
                f.write(f"- Average cache size: {sum(caches)/len(caches):.2f}MB\n")
                f.write(f"- Fastest: {min(times):.2f}s\n")
                f.write(f"- Slowest: {max(times):.2f}s\n\n")
            
            # Findings
            if self.findings:
                f.write("## Issues Found\n\n")
                for finding in sorted(self.findings, key=lambda x: {'critical': 0, 'high': 1, 'medium': 2, 'low': 3}.get(x['severity'], 4)):
                    f.write(f"### [{finding['severity'].upper()}] {finding['issue']}\n\n")
                    f.write(f"- **Scenario:** {finding['scenario']}\n")
                    f.write(f"- **Details:** {finding['details']}\n")
                    f.write(f"- **Timestamp:** {finding['timestamp']}\n\n")
            else:
                f.write("## Issues Found\n\n")
                f.write("✓ No critical issues found during large-scale testing!\n\n")
            
            # Recommendations
            f.write("## Recommendations\n\n")
            if critical > 0:
                f.write("### Critical Issues\n")
                f.write("- Immediate investigation and fixes required\n")
                f.write("- These affect production readiness\n\n")
            
            if high > 0:
                f.write("### High Priority Issues\n")
                f.write("- Should be fixed before production use\n")
                f.write("- May affect user experience at scale\n\n")
            
            f.write("### General Recommendations\n")
            f.write("- Monitor cache growth for very large projects (>5000 tests)\n")
            f.write("- Implement cache size limits if needed\n")
            f.write("- Consider parallel test discovery for >1000 classes\n")
            f.write("- Add progress reporting for long operations\n\n")
            
            # Conclusion
            f.write("## Conclusion\n\n")
            if len(self.findings) == 0:
                f.write("The test-order plugins handle large-scale projects well!\n")
                f.write("No critical issues were found during comprehensive testing with up to 1000 test classes.\n")
            elif critical == 0:
                f.write("The test-order plugins are generally stable at large scales.\n")
                f.write("Some minor issues were found but none are blocking.\n")
            else:
                f.write("Critical issues were found that need immediate attention.\n")
        
        print(f"\n✓ Final report saved: {report_file}")

if __name__ == "__main__":
    runner = Phase5ComprehensiveTest()
    runner.run_all_tests()
