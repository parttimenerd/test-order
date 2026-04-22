#!/usr/bin/env python3

import os
import sys
import json
import time
import subprocess
from pathlib import Path
from datetime import datetime

class Phase5ScaleTests:
    def __init__(self):
        self.results = []
        self.findings = []
        self.repo_root = Path(__file__).parent.parent
        self.test_projects_dir = self.repo_root / "phase5-scale-tests"
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
        
    def create_large_multi_module_project(self, num_modules=20, tests_per_module=200):
        """Create a Maven reactor with many modules"""
        scenario_name = f"maven-reactor-{num_modules}modules-{tests_per_module}tests"
        project_dir = self.test_projects_dir / scenario_name
        project_dir.mkdir(exist_ok=True)
        
        # Create parent POM
        parent_pom = f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>multi-module-reactor</artifactId>
    <version>1.0.0</version>
    <packaging>pom</packaging>
    
    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
    </properties>
    
    <modules>
"""
        
        for m in range(num_modules):
            module_name = f"module-{m:03d}"
            parent_pom += f"        <module>{module_name}</module>\n"
        
        parent_pom += """    </modules>
</project>
"""
        
        with open(project_dir / "pom.xml", "w") as f:
            f.write(parent_pom)
        
        # Create modules
        print(f"Creating {num_modules} Maven modules with {tests_per_module} tests each...")
        for m in range(num_modules):
            module_name = f"module-{m:03d}"
            module_dir = project_dir / module_name
            src_dir = module_dir / "src" / "test" / "java" / "com" / "example" / module_name
            src_dir.mkdir(parents=True, exist_ok=True)
            
            module_pom = f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>{module_name}</artifactId>
    <version>1.0.0</version>
    
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
            with open(module_dir / "pom.xml", "w") as f:
                f.write(module_pom)
            
            # Create test classes
            classes_to_create = min(tests_per_module // 10, 100)
            for t in range(classes_to_create):
                class_name = f"TestClass{t:03d}"
                test_code = f"""package com.example.{module_name};
import org.junit.Test;
public class {class_name} {{
    @Test public void test1() {{ }}
    @Test public void test2() {{ }}
    @Test public void test3() {{ }}
    @Test public void test4() {{ }}
    @Test public void test5() {{ }}
}}
"""
                with open(src_dir / f"{class_name}.java", "w") as f:
                    f.write(test_code)
            
            if (m + 1) % 5 == 0:
                print(f"  Created {m + 1}/{num_modules} modules")
        
        return project_dir, scenario_name
    
    def run_test_project(self, project_dir, scenario_name, command):
        """Run test and measure performance"""
        print(f"\n{'='*60}")
        print(f"Testing: {scenario_name}")
        print(f"Project: {project_dir}")
        print(f"{'='*60}\n")
        
        original_dir = os.getcwd()
        os.chdir(project_dir)
        
        try:
            start_time = time.time()
            
            print(f"Running: {' '.join(command)}")
            result = subprocess.run(
                command,
                capture_output=True,
                text=True,
                timeout=600
            )
            
            elapsed = time.time() - start_time
            
            # Check cache
            cache_size = 0
            cache_dir = Path(".test-order")
            if cache_dir.exists():
                for f in cache_dir.rglob("*"):
                    if f.is_file():
                        cache_size += f.stat().st_size / (1024 * 1024)
            
            # Count tests
            test_count = len(list(Path(".").rglob("*Test*.java")))
            
            result_data = {
                "scenario": scenario_name,
                "elapsed_time": elapsed,
                "cache_size_mb": cache_size,
                "test_count": test_count,
                "success": result.returncode == 0,
                "return_code": result.returncode
            }
            
            self.results.append(result_data)
            
            print(f"✓ Completed in {elapsed:.2f}s")
            print(f"  Cache Size: {cache_size:.2f}MB")
            print(f"  Test Files: {test_count}")
            
            if elapsed > 300:
                self.log_finding(scenario_name, "Slow execution", "medium",
                               f"Took {elapsed:.2f}s for {test_count} tests")
            
            if result.returncode != 0:
                self.log_finding(scenario_name, "Test failure", "high",
                               f"Return code: {result.returncode}")
                if result.stderr:
                    print(f"Error:\n{result.stderr[:300]}")
            
            os.chdir(original_dir)
            return result_data
            
        except subprocess.TimeoutExpired:
            self.log_finding(scenario_name, "Timeout", "critical",
                           f"Command exceeded 10 minute timeout for {test_count} tests")
            os.chdir(original_dir)
            return None
        except Exception as e:
            self.log_finding(scenario_name, "Exception", "critical", str(e))
            os.chdir(original_dir)
            return None
    
    def create_extreme_scale_project(self, num_classes=1000):
        """Create extremely large single module"""
        scenario_name = f"extreme-scale-{num_classes}classes"
        project_dir = self.test_projects_dir / scenario_name
        project_dir.mkdir(exist_ok=True)
        
        src_dir = project_dir / "src" / "test" / "java" / "com" / "example"
        src_dir.mkdir(parents=True, exist_ok=True)
        
        pom = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>extreme-scale</artifactId>
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
            f.write(pom)
        
        print(f"Creating {num_classes} test classes...")
        for i in range(num_classes):
            class_name = f"Test{i:05d}"
            test_code = f"""package com.example;
import org.junit.Test;
public class {class_name} {{
    @Test public void t1() {{ }}
    @Test public void t2() {{ }}
    @Test public void t3() {{ }}
    @Test public void t4() {{ }}
    @Test public void t5() {{ }}
    @Test public void t6() {{ }}
    @Test public void t7() {{ }}
    @Test public void t8() {{ }}
    @Test public void t9() {{ }}
    @Test public void t10() {{ }}
}}
"""
            with open(src_dir / f"{class_name}.java", "w") as f:
                f.write(test_code)
            
            if (i + 1) % 100 == 0:
                print(f"  Created {i + 1}/{num_classes}")
        
        return project_dir, scenario_name
    
    def run_all_tests(self):
        """Run all Phase 5 tests"""
        print("\n" + "="*70)
        print("PHASE 5: LARGE-SCALE BUG HUNT")
        print("="*70)
        
        # Test 1: Small baseline
        print("\n[1/5] Creating baseline project (20 modules, 200 tests)...")
        proj_dir, name = self.create_large_multi_module_project(20, 200)
        self.run_test_project(proj_dir, name, ["mvn", "clean", "-q"])
        
        # Test 2: Medium scale
        print("\n[2/5] Creating medium-scale project (40 modules, 300 tests)...")
        proj_dir, name = self.create_large_multi_module_project(40, 300)
        self.run_test_project(proj_dir, name, ["mvn", "clean", "-q"])
        
        # Test 3: Large scale
        print("\n[3/5] Creating large-scale project (60 modules, 500 tests)...")
        proj_dir, name = self.create_large_multi_module_project(60, 500)
        self.run_test_project(proj_dir, name, ["mvn", "clean", "-q"])
        
        # Test 4: Extreme single module
        print("\n[4/5] Creating extreme single module (1000 classes)...")
        proj_dir, name = self.create_extreme_scale_project(1000)
        self.run_test_project(proj_dir, name, ["mvn", "clean", "-q"])
        
        # Test 5: Very extreme
        print("\n[5/5] Creating very extreme project (2000 classes)...")
        proj_dir, name = self.create_extreme_scale_project(2000)
        self.run_test_project(proj_dir, name, ["mvn", "clean", "-q"])
        
        print("\n" + "="*70)
        print(f"PHASE 5 TESTING COMPLETE")
        print(f"Scenarios tested: {len(self.results)}")
        print(f"Findings: {len(self.findings)}")
        print("="*70)
        
        self.generate_report()
    
    def generate_report(self):
        """Generate comprehensive report"""
        report_file = self.repo_root / "PHASE-5-LARGE-SCALE-REPORT.md"
        
        with open(report_file, "w") as f:
            f.write("# PHASE 5: Large-Scale Bug Hunt Report\n\n")
            f.write(f"Generated: {datetime.now().isoformat()}\n\n")
            
            # Summary
            f.write("## Summary\n\n")
            f.write(f"- Total test scenarios: {len(self.results)}\n")
            critical = len([x for x in self.findings if x['severity'] == 'critical'])
            high = len([x for x in self.findings if x['severity'] == 'high'])
            f.write(f"- Critical findings: {critical}\n")
            f.write(f"- High severity findings: {high}\n\n")
            
            # Results table
            f.write("## Test Results\n\n")
            if self.results:
                f.write("| Scenario | Tests | Time (s) | Cache (MB) | Status |\n")
                f.write("|----------|-------|---------|-----------|--------|\n")
                for r in self.results:
                    status = "✓" if r['success'] else "✗"
                    f.write(f"| {r['scenario'][:40]} | {r['test_count']} | {r['elapsed_time']:.2f} | {r['cache_size_mb']:.2f} | {status} |\n")
            
            # Detailed findings
            if self.findings:
                f.write("\n## Findings\n\n")
                for finding in sorted(self.findings, key=lambda x: {'critical': 0, 'high': 1, 'medium': 2, 'low': 3}.get(x['severity'], 4)):
                    f.write(f"### [{finding['severity'].upper()}] {finding['issue']}\n\n")
                    f.write(f"Scenario: {finding['scenario']}\n\n")
                    f.write(f"Details: {finding['details']}\n\n")
            
            f.write("## Performance Characteristics\n\n")
            if self.results:
                total_time = sum(r['elapsed_time'] for r in self.results if r)
                total_tests = sum(r['test_count'] for r in self.results if r)
                avg_cache = sum(r['cache_size_mb'] for r in self.results if r) / len([r for r in self.results if r])
                f.write(f"- Total execution time: {total_time:.2f}s\n")
                f.write(f"- Total tests processed: {total_tests}\n")
                f.write(f"- Average cache size: {avg_cache:.2f}MB\n")
        
        print(f"\n✓ Report saved: {report_file}")

if __name__ == "__main__":
    runner = Phase5ScaleTests()
    runner.run_all_tests()
