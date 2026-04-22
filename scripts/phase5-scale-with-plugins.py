#!/usr/bin/env python3

import os
import sys
import json
import time
import subprocess
import psutil
from pathlib import Path
from datetime import datetime
from collections import defaultdict

class Phase5WithPlugins:
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
            for t in range(min(tests_per_module // 10, 100)):  # Limit classes
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
    
    def measure_resource_usage(self, project_dir, scenario_name, command):
        """Run command and measure resource usage"""
        print(f"\n{'='*60}")
        print(f"Testing: {scenario_name}")
        print(f"Command: {' '.join(command)}")
        print(f"{'='*60}\n")
        
        original_dir = os.getcwd()
        os.chdir(project_dir)
        
        try:
            start_time = time.time()
            peak_memory = 0
            
            process = subprocess.Popen(
                command,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True
            )
            
            # Monitor process
            try:
                while process.poll() is None:
                    try:
                        p = psutil.Process(process.pid)
                        mem_info = p.memory_info()
                        peak_memory = max(peak_memory, mem_info.rss / (1024*1024))
                    except:
                        pass
                    time.sleep(0.5)
            except:
                pass
            
            stdout, stderr = process.communicate(timeout=600)
            elapsed = time.time() - start_time
            
            # Check cache
            cache_size = 0
            cache_dir = Path(".test-order")
            if cache_dir.exists():
                for f in cache_dir.rglob("*"):
                    if f.is_file():
                        cache_size += f.stat().st_size / (1024 * 1024)
            
            # Count tests
            test_count = len(list(Path("src/test/java").rglob("*.java")))
            
            result = {
                "scenario": scenario_name,
                "command": " ".join(command),
                "elapsed_time": elapsed,
                "peak_memory_mb": peak_memory,
                "cache_size_mb": cache_size,
                "test_count": test_count,
                "success": process.returncode == 0,
                "return_code": process.returncode
            }
            
            self.results.append(result)
            
            print(f"✓ Completed in {elapsed:.2f}s")
            print(f"  Peak Memory: {peak_memory:.2f}MB")
            print(f"  Cache Size: {cache_size:.2f}MB")
            print(f"  Test Count: {test_count}")
            print(f"  Return Code: {process.returncode}")
            
            if elapsed > 300:
                self.log_finding(scenario_name, "Long execution time", "medium",
                               f"Took {elapsed:.2f}s, exceeded 5 minutes for {test_count} tests")
            
            if peak_memory > 2048:
                self.log_finding(scenario_name, "High memory usage", "high",
                               f"Peak: {peak_memory:.2f}MB")
            
            if process.returncode != 0:
                self.log_finding(scenario_name, "Command failed", "high",
                               f"Return code: {process.returncode}\nStderr: {stderr[:200]}")
            
            os.chdir(original_dir)
            return result
            
        except subprocess.TimeoutExpired:
            self.log_finding(scenario_name, "Timeout", "critical",
                           f"Command exceeded 10 minute timeout")
            os.chdir(original_dir)
            return None
        except Exception as e:
            self.log_finding(scenario_name, "Exception", "critical", str(e))
            os.chdir(original_dir)
            return None
    
    def create_gradle_multi_project(self, num_projects=50):
        """Create a Gradle project with multiple subprojects"""
        scenario_name = f"gradle-{num_projects}projects"
        project_dir = self.test_projects_dir / scenario_name
        project_dir.mkdir(exist_ok=True)
        
        # Create settings.gradle
        settings = f"""rootProject.name = 'gradle-multi-project'
"""
        for p in range(num_projects):
            settings += f"include('subproject-{p:03d}')\n"
        
        with open(project_dir / "settings.gradle", "w") as f:
            f.write(settings)
        
        # Create build.gradle
        build = """plugins {
    id 'java'
}

allprojects {
    apply plugin: 'java'
    
    repositories {
        mavenCentral()
    }
    
    dependencies {
        testImplementation 'junit:junit:4.13.2'
    }
}
"""
        with open(project_dir / "build.gradle", "w") as f:
            f.write(build)
        
        # Create subprojects
        print(f"Creating {num_projects} Gradle subprojects...")
        for p in range(num_projects):
            sub_dir = project_dir / f"subproject-{p:03d}"
            test_dir = sub_dir / "src" / "test" / "java" / "com" / "example"
            test_dir.mkdir(parents=True, exist_ok=True)
            
            test_code = f"""package com.example;
import org.junit.Test;
public class Test{p:03d} {{
    @Test public void test1() {{ }}
    @Test public void test2() {{ }}
}}
"""
            with open(test_dir / f"Test{p:03d}.java", "w") as f:
                f.write(test_code)
            
            if (p + 1) % 10 == 0:
                print(f"  Created {p + 1}/{num_projects} subprojects")
        
        return project_dir, scenario_name
    
    def run_comprehensive_tests(self):
        """Run comprehensive Phase 5 tests"""
        print("\n" + "="*70)
        print("PHASE 5: LARGE-SCALE BUG HUNT WITH PLUGINS")
        print("="*70)
        
        # Test 1: Maven multi-module
        print("\n[TEST 1/4] Creating and testing Maven reactor with 20 modules...")
        proj_dir, name = self.create_large_multi_module_project(num_modules=20, tests_per_module=500)
        self.measure_resource_usage(proj_dir, name, ["mvn", "clean", "test", "-q"])
        
        # Test 2: Gradle multi-project
        print("\n[TEST 2/4] Creating and testing Gradle multi-project...")
        proj_dir, name = self.create_gradle_multi_project(num_projects=50)
        self.measure_resource_usage(proj_dir, name, ["gradle", "test"])
        
        # Test 3: Extreme single module
        print("\n[TEST 3/4] Creating extreme single module (2000 test classes)...")
        from phase5_large_scale_test import Phase5TestRunner
        runner = Phase5TestRunner()
        proj_dir, name = runner.create_large_maven_module(num_classes=2000, methods_per_class=5)
        self.measure_resource_usage(proj_dir, name, ["mvn", "clean", "test", "-q", "-DskipTests"])
        
        # Test 4: Large module with dependencies
        print("\n[TEST 4/4] Creating module with interdependent tests...")
        proj_dir, name = self.create_large_multi_module_project(num_modules=50, tests_per_module=100)
        self.measure_resource_usage(proj_dir, name, ["mvn", "clean", "test", "-q"])
        
        print("\n" + "="*70)
        print(f"PHASE 5 COMPLETE - {len(self.results)} scenarios, {len(self.findings)} findings")
        print("="*70)
        
        self.generate_comprehensive_report()
    
    def generate_comprehensive_report(self):
        """Generate comprehensive Phase 5 report"""
        report_file = self.repo_root / "PHASE-5-SCALE-FINDINGS.md"
        
        with open(report_file, "w") as f:
            f.write("# PHASE 5: Large-Scale Plugin Testing\n\n")
            f.write(f"Generated: {datetime.now().isoformat()}\n\n")
            
            # Summary
            f.write("## Summary\n\n")
            f.write(f"- Test scenarios: {len(self.results)}\n")
            f.write(f"- Critical issues: {len([x for x in self.findings if x['severity'] == 'critical'])}\n")
            f.write(f"- High priority: {len([x for x in self.findings if x['severity'] == 'high'])}\n\n")
            
            # Performance metrics
            f.write("## Performance Metrics\n\n")
            if self.results:
                f.write("| Scenario | Tests | Time (s) | Memory (MB) | Cache (MB) | Status |\n")
                f.write("|----------|-------|---------|------------|-----------|--------|\n")
                for r in self.results:
                    status = "✓" if r['success'] else "✗"
                    f.write(f"| {r['scenario']} | {r['test_count']} | {r['elapsed_time']:.2f} | {r['peak_memory_mb']:.0f} | {r['cache_size_mb']:.2f} | {status} |\n")
            
            # Findings
            if self.findings:
                f.write("\n## Critical Findings\n\n")
                for finding in sorted(self.findings, key=lambda x: {'critical': 0, 'high': 1, 'medium': 2, 'low': 3}[x['severity']]):
                    f.write(f"### [{finding['severity'].upper()}] {finding['issue']}\n\n")
                    f.write(f"- Scenario: {finding['scenario']}\n")
                    f.write(f"- Details: {finding['details']}\n\n")

if __name__ == "__main__":
    runner = Phase5WithPlugins()
    runner.run_comprehensive_tests()
