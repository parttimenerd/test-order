#!/usr/bin/env python3

import os
import subprocess
import json
from pathlib import Path

test_results = {}

def test_version(framework, version):
    """Test a specific JUnit version"""
    proj_name = f"p5-{framework}-v{version}-test"
    proj_dir = f"/Users/i560383_1/code/experiments/test-order/{proj_name}"
    
    try:
        result = subprocess.run(
            ["mvn", "clean", "test", "-q"],
            cwd=proj_dir,
            capture_output=True,
            timeout=120,
            text=True
        )
        success = result.returncode == 0
        return {
            "status": "PASS" if success else "FAIL",
            "returncode": result.returncode
        }
    except subprocess.TimeoutExpired:
        return {"status": "TIMEOUT", "returncode": -1}
    except Exception as e:
        return {"status": "ERROR", "error": str(e)}

# Test JUnit versions
junit4_versions = ["4.13.2", "4.12", "4.11", "4.10", "4.8.2"]
junit5_versions = ["5.10.0", "5.9.0", "5.5.0", "5.0.0"]

print("=== JUnit 4.x Compatibility ===")
for ver in junit4_versions:
    result = test_version("junit4", ver)
    print(f"JUnit 4 v{ver}: {result['status']}")
    
print("\n=== JUnit 5.x Compatibility ===")
for ver in junit5_versions:
    result = test_version("junit5", ver)
    print(f"JUnit 5 v{ver}: {result['status']}")

