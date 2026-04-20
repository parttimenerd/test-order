#!/usr/bin/env python3
"""
Comprehensive test harness for test-order plugin.
Tests all documented workflows without looking at source code.
"""

import subprocess
import os
import sys
import tempfile
import shutil
import json
import re
from pathlib import Path
from dataclasses import dataclass
from typing import List, Optional, Tuple
from enum import Enum

ROOT_DIR = Path("/Users/i560383_1/code/experiments/test-order")


class TestStatus(Enum):
    PASS = "✓"
    FAIL = "✗"
    WARN = "⚠"


@dataclass
class TestResult:
    name: str
    status: TestStatus
    message: str
    details: Optional[str] = None

    def __str__(self):
        return f"{self.status.value} {self.name}: {self.message}"


class TestOrderTestHarness:
    """Test harness for test-order plugin workflows"""

    def __init__(self, test_project_dir: Path):
        self.test_dir = test_project_dir
        self.results: List[TestResult] = []

    def run_command(self, cmd: str) -> Tuple[int, str, str]:
        """Run a shell command and return exit code, stdout, stderr"""
        result = subprocess.run(
            cmd,
            shell=True,
            cwd=str(self.test_dir),
            capture_output=True,
            text=True,
        )
        return result.returncode, result.stdout, result.stderr

    def assert_file_exists(self, path: str, msg: str = "") -> bool:
        """Check if file exists"""
        exists = (self.test_dir / path).exists()
        if not exists:
            self.results.append(
                TestResult("file_exists", TestStatus.FAIL, f"{path} not found. {msg}")
            )
        return exists

    def assert_git_detects_changes(self) -> bool:
        """Verify git detects uncommitted changes"""
        exit_code, stdout, _ = self.run_command("git diff src/main/java/com/example/Calculator.java")
        has_diff = len(stdout.strip()) > 0
        if not has_diff:
            self.results.append(
                TestResult(
                    "git_change_detection",
                    TestStatus.FAIL,
                    "Git diff returned no output despite changes",
                    stdout,
                )
            )
        return has_diff

    def assert_tests_failed(self) -> bool:
        """Verify tests actually failed as expected"""
        exit_code, stdout, _ = self.run_command("mvn test 2>&1 | grep -c 'Failures: [1-9]'")
        failed = exit_code == 0  # grep returns 0 if match found
        if not failed:
            self.results.append(
                TestResult(
                    "test_failures",
                    TestStatus.FAIL,
                    "Expected tests to fail but they all passed",
                )
            )
        return failed

    def assert_state_file_records_failures(self) -> bool:
        """Verify .test-order-state file records test failures"""
        state_file = self.test_dir / ".test-order-state"
        if not state_file.exists():
            self.results.append(
                TestResult(
                    "state_file_exists",
                    TestStatus.FAIL,
                    "State file not found after test run",
                )
            )
            return False
        
        try:
            with open(state_file) as f:
                content = f.read()
                # Look for "failed": true entries
                has_failures = '"failed": true' in content
                if not has_failures:
                    self.results.append(
                        TestResult(
                            "state_file_failures",
                            TestStatus.FAIL,
                            'No "failed": true entries in state file despite test failures',
                            f"State file size: {len(content)} bytes",
                        )
                    )
                return has_failures
        except Exception as e:
            self.results.append(
                TestResult(
                    "state_file_parse",
                    TestStatus.FAIL,
                    f"Error reading state file: {e}",
                )
            )
            return False

    def assert_test_order_changed(self, prev_order: List[str], curr_order: List[str]) -> bool:
        """Verify test order actually changed"""
        if prev_order == curr_order:
            self.results.append(
                TestResult(
                    "test_order_changed",
                    TestStatus.FAIL,
                    f"Test order did not change despite changed code",
                    f"Both: {prev_order}",
                )
            )
            return False
        return True

    def extract_test_order(self, mvn_output: str) -> List[str]:
        """Extract test class names from mvn output in execution order"""
        pattern = r"\[INFO\] Running (com\.example\.\w+)"
        matches = re.findall(pattern, mvn_output)
        return matches

    def test_learn_mode(self):
        """Test 1: Learn mode creates dependency index"""
        print("\n=== TEST 1: Learn Mode ===")
        
        # Clean
        self.run_command("rm -rf target .test-order-* test-dependencies.lz4")
        
        # Run learn mode
        exit_code, stdout, stderr = self.run_command(
            "mvn test -Dtestorder.mode=learn > /tmp/test_learn.log 2>&1 && echo SUCCESS"
        )
        
        if "SUCCESS" not in stdout:
            self.results.append(
                TestResult(
                    "learn_mode_success",
                    TestStatus.FAIL,
                    "Learn mode failed to complete",
                    stderr,
                )
            )
            return
        
        # Check that index was created
        if self.assert_file_exists("test-dependencies.lz4", "Learn mode should create index"):
            self.results.append(
                TestResult(
                    "learn_mode_creates_index",
                    TestStatus.PASS,
                    "Index file created successfully",
                )
            )
        
        # Check that deps directory was populated
        deps_dir = self.test_dir / "target/test-order-deps"
        if deps_dir.exists():
            deps_files = list(deps_dir.glob("*.deps"))
            if deps_files:
                self.results.append(
                    TestResult(
                        "learn_mode_deps_collected",
                        TestStatus.PASS,
                        f"Collected {len(deps_files)} .deps files",
                    )
                )
            else:
                self.results.append(
                    TestResult(
                        "learn_mode_deps_files",
                        TestStatus.WARN,
                        "target/test-order-deps exists but no .deps files found",
                    )
                )

    def test_show_order(self):
        """Test 2: Show order displays test list"""
        print("\n=== TEST 2: Show Order ===")
        
        exit_code, stdout, stderr = self.run_command(
            "mvn test-order:show-order 2>&1"
        )
        
        if exit_code != 0:
            self.results.append(
                TestResult(
                    "show_order_success",
                    TestStatus.FAIL,
                    "show-order command failed",
                    stderr,
                )
            )
            return
        
        # Check for test class names in output
        if "CalculatorAddTest" in stdout or "CalculatorErrorTest" in stdout:
            self.results.append(
                TestResult(
                    "show_order_lists_tests",
                    TestStatus.PASS,
                    "show-order displays test classes",
                )
            )
        else:
            self.results.append(
                TestResult(
                    "show_order_lists_tests",
                    TestStatus.FAIL,
                    "show-order output doesn't contain expected test classes",
                    stdout[:500],
                )
            )

    def test_dump_index(self):
        """Test 3: Dump command shows index contents"""
        print("\n=== TEST 3: Dump Index ===")
        
        exit_code, stdout, stderr = self.run_command("mvn test-order:dump 2>&1")
        
        if exit_code != 0:
            self.results.append(
                TestResult(
                    "dump_success",
                    TestStatus.FAIL,
                    "dump command failed",
                    stderr,
                )
            )
            return
        
        # Check for calculator references in dump
        if "Calculator" in stdout and "test" in stdout.lower():
            self.results.append(
                TestResult(
                    "dump_shows_content",
                    TestStatus.PASS,
                    "dump displays index content",
                )
            )
        else:
            self.results.append(
                TestResult(
                    "dump_shows_content",
                    TestStatus.FAIL,
                    "dump output doesn't show expected index content",
                    stdout[:300],
                )
            )

    def test_order_mode_with_changes(self):
        """Test 4: Order mode reorders tests based on changes"""
        print("\n=== TEST 4: Order Mode with Changes ===")
        
        # Introduce a change to Calculator
        calc_file = self.test_dir / "src/main/java/com/example/Calculator.java"
        original_content = calc_file.read_text()
        
        modified_content = original_content.replace(
            "if (n == 2) return true;",
            "if (n == 2) return true;  // TEST CHANGE",
        )
        calc_file.write_text(modified_content)
        
        try:
            # Run tests with git-based change detection
            exit_code, stdout, stderr = self.run_command(
                "mvn test -Dtestorder.changeMode=since-last-commit > /tmp/order_test.log 2>&1 && echo SUCCESS"
            )
            
            if "SUCCESS" not in stdout:
                self.results.append(
                    TestResult(
                        "order_mode_success",
                        TestStatus.FAIL,
                        "Order mode test run failed",
                    )
                )
                return
            
            # Check that change was detected
            log_output, _, _ = self.run_command("cat /tmp/order_test.log | grep -i 'changed classes'")
            if "Calculator" in log_output:
                self.results.append(
                    TestResult(
                        "order_mode_detects_changes",
                        TestStatus.PASS,
                        "Changed classes detected correctly",
                    )
                )
            else:
                self.results.append(
                    TestResult(
                        "order_mode_detects_changes",
                        TestStatus.FAIL,
                        "No changed classes detected",
                        log_output,
                    )
                )
            
            # Check that tests ran (order mode should inject ClassOrderer)
            if "Running" in log_output or "CalculatorAddTest" in log_output:
                self.results.append(
                    TestResult(
                        "order_mode_runs_tests",
                        TestStatus.PASS,
                        "Tests executed in order mode",
                    )
                )
            else:
                self.results.append(
                    TestResult(
                        "order_mode_runs_tests",
                        TestStatus.FAIL,
                        "Tests did not run or no output detected",
                    )
                )
        finally:
            # Restore original file
            calc_file.write_text(original_content)

    def test_explicit_change_mode(self):
        """Test 5: Explicit change mode works"""
        print("\n=== TEST 5: Explicit Change Mode ===")
        
        exit_code, stdout, stderr = self.run_command(
            "mvn test -Dtestorder.changeMode=explicit "
            "-Dtestorder.changed.classes=com.example.Calculator > /tmp/explicit_test.log 2>&1 && echo SUCCESS"
        )
        
        if "SUCCESS" not in stdout:
            self.results.append(
                TestResult(
                    "explicit_mode_success",
                    TestStatus.FAIL,
                    "Explicit change mode failed",
                )
            )
            return
        
        # Check for changed classes in log
        log_output, _, _ = self.run_command("cat /tmp/explicit_test.log | grep -i 'changed classes'")
        if "Calculator" in log_output:
            self.results.append(
                TestResult(
                    "explicit_mode_accepts_classes",
                    TestStatus.PASS,
                    "Explicit mode correctly accepts specified classes",
                )
            )
        else:
            self.results.append(
                TestResult(
                    "explicit_mode_accepts_classes",
                    TestStatus.FAIL,
                    "Explicit mode did not register specified classes",
                    log_output,
                )
            )

    def test_select_mode(self):
        """Test 6: Select mode generates test subsets"""
        print("\n=== TEST 6: Select Mode ===")
        
        exit_code, stdout, stderr = self.run_command(
            "mvn test-order:select test > /tmp/select_test.log 2>&1 && echo SUCCESS"
        )
        
        if "SUCCESS" not in stdout:
            self.results.append(
                TestResult(
                    "select_mode_success",
                    TestStatus.FAIL,
                    "Select mode failed",
                )
            )
            return
        
        # Check if selected tests file was created
        if self.assert_file_exists("target/test-order-selected.txt"):
            self.results.append(
                TestResult(
                    "select_mode_creates_selected",
                    TestStatus.PASS,
                    "Selected tests file created",
                )
            )
        
        # Check if remaining tests file was created
        if self.assert_file_exists("target/test-order-remaining.txt"):
            remaining_content = (self.test_dir / "target/test-order-remaining.txt").read_text()
            if remaining_content.strip():
                self.results.append(
                    TestResult(
                        "select_mode_creates_remaining",
                        TestStatus.WARN,
                        f"Remaining tests file created ({len(remaining_content.strip().split())} tests)",
                    )
                )
            else:
                self.results.append(
                    TestResult(
                        "select_mode_no_remaining",
                        TestStatus.PASS,
                        "All tests were selected (no remaining)",
                    )
                )

    def test_combined_mode(self):
        """Test 7: Combined mode handles full workflow"""
        print("\n=== TEST 7: Combined Mode ===")
        
        exit_code, stdout, stderr = self.run_command(
            "mvn test-order:combined test > /tmp/combined_test.log 2>&1 && echo SUCCESS"
        )
        
        if "SUCCESS" not in stdout:
            self.results.append(
                TestResult(
                    "combined_mode_success",
                    TestStatus.FAIL,
                    "Combined mode failed",
                )
            )
            return
        
        log_output, _, _ = self.run_command("cat /tmp/combined_test.log")
        
        if "selected" in log_output.lower():
            self.results.append(
                TestResult(
                    "combined_mode_selects",
                    TestStatus.PASS,
                    "Combined mode selected tests",
                )
            )
        else:
            self.results.append(
                TestResult(
                    "combined_mode_selects",
                    TestStatus.WARN,
                    "Combined mode may not have selected tests properly",
                )
            )

    def test_failure_detection_and_state(self):
        """Test 8: Failure detection and state recording"""
        print("\n=== TEST 8: Failure Detection and State Recording ===")
        
        # Clean state
        self.run_command("rm -f .test-order-state")
        
        # Introduce a bug
        calc_file = self.test_dir / "src/main/java/com/example/Calculator.java"
        original_content = calc_file.read_text()
        buggy_content = original_content.replace(
            "return a + b;",
            "return a + b + 999;  // BUG",
        )
        
        try:
            calc_file.write_text(buggy_content)
            
            # Run tests multiple times
            for i in range(3):
                self.run_command("mvn test -q 2>&1 > /dev/null")
            
            # Check state file
            state_file = self.test_dir / ".test-order-state"
            if state_file.exists():
                self.results.append(
                    TestResult(
                        "state_file_created",
                        TestStatus.PASS,
                        "State file created after test runs",
                    )
                )
                
                # Check if failures are recorded
                content = state_file.read_text()
                has_failures = '"failed": true' in content
                
                if has_failures:
                    self.results.append(
                        TestResult(
                            "state_file_records_failures",
                            TestStatus.PASS,
                            "Test failures recorded in state file",
                        )
                    )
                else:
                    self.results.append(
                        TestResult(
                            "state_file_records_failures",
                            TestStatus.FAIL,
                            "Test failures NOT recorded in state file despite tests failing",
                            "Bug: State file shows 'failed': false for CalculatorAddTest",
                        )
                    )
            else:
                self.results.append(
                    TestResult(
                        "state_file_created",
                        TestStatus.FAIL,
                        "State file not created",
                    )
                )
        finally:
            calc_file.write_text(original_content)

    def test_optimize_command(self):
        """Test 9: Optimize command for weight tuning"""
        print("\n=== TEST 9: Optimize Command ===")
        
        exit_code, stdout, stderr = self.run_command("mvn test-order:optimize 2>&1")
        
        if exit_code != 0:
            self.results.append(
                TestResult(
                    "optimize_success",
                    TestStatus.FAIL,
                    "Optimize command failed",
                    stderr,
                )
            )
            return
        
        if "need" in stdout.lower() and "failure" in stdout.lower():
            self.results.append(
                TestResult(
                    "optimize_insufficient_data",
                    TestStatus.WARN,
                    "Optimize command running but insufficient failure data",
                )
            )
        else:
            self.results.append(
                TestResult(
                    "optimize_attempted",
                    TestStatus.PASS,
                    "Optimize command executed",
                )
            )

    def test_weights_file_configuration(self):
        """Test 10: Custom weights file configuration"""
        print("\n=== TEST 10: Custom Weights File ===")
        
        # Create a custom weights file
        weights_file = self.test_dir / "custom-weights.txt"
        weights_content = """# Custom weights
newTest = 20
changedTest = 10
maxFailure = 4
speed = 2
speedPenalty = 1
depOverlap = 6
"""
        weights_file.write_text(weights_content)
        
        try:
            exit_code, stdout, stderr = self.run_command(
                "mvn test-order:show-order -Dtestorder.weights.file=custom-weights.txt 2>&1"
            )
            
            if exit_code == 0:
                self.results.append(
                    TestResult(
                        "custom_weights_accepted",
                        TestStatus.PASS,
                        "Custom weights file accepted by show-order",
                    )
                )
            else:
                self.results.append(
                    TestResult(
                        "custom_weights_accepted",
                        TestStatus.FAIL,
                        "Custom weights file rejected",
                        stderr,
                    )
                )
        finally:
            weights_file.unlink(missing_ok=True)

    def run_all_tests(self):
        """Run all tests"""
        print("=" * 60)
        print("TEST-ORDER PLUGIN COMPREHENSIVE TEST HARNESS")
        print("=" * 60)
        
        self.test_learn_mode()
        self.test_show_order()
        self.test_dump_index()
        self.test_order_mode_with_changes()
        self.test_explicit_change_mode()
        self.test_select_mode()
        self.test_combined_mode()
        self.test_failure_detection_and_state()
        self.test_optimize_command()
        self.test_weights_file_configuration()
        
        # Print results
        print("\n" + "=" * 60)
        print("TEST RESULTS")
        print("=" * 60)
        
        passed = sum(1 for r in self.results if r.status == TestStatus.PASS)
        failed = sum(1 for r in self.results if r.status == TestStatus.FAIL)
        warned = sum(1 for r in self.results if r.status == TestStatus.WARN)
        
        for result in self.results:
            print(str(result))
            if result.details:
                print(f"    Details: {result.details[:100]}")
        
        print("\n" + "-" * 60)
        print(f"SUMMARY: {passed} passed, {failed} failed, {warned} warnings")
        print("-" * 60)
        
        return len(self.results), failed, warned


def main():
    # Use test-project-001
    test_project = ROOT_DIR / "test-project-001"
    if not test_project.exists():
        print(f"ERROR: Test project not found at {test_project}")
        sys.exit(1)

    harness = TestOrderTestHarness(test_project)
    total, failed, warned = harness.run_all_tests()
    if failed > 0:
        print(f"\n⚠️  {failed} test(s) failed - see details above")
        sys.exit(1)
    print("\n✓ All tests passed!")
    sys.exit(0)


if __name__ == "__main__":
    main()
