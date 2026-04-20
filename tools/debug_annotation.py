#!/usr/bin/env python3
"""Quick test to check which annotation elements SFM misses and why."""
import subprocess, json, re

# Pick a simple file with annotation elements
test_file = 'java_files/Java8_RepeatingAnnotations.java'
# Check what SourceFileModel produces
result = subprocess.run(
    ['mvn', '-pl', 'test-order-junit', 'exec:java',
     '-Dexec.mainClass=me.bechberger.testorder.changes.SourceFileModel',
     '-Dexec.args=' + test_file],
    capture_output=True, text=True, cwd='.'
)
print("STDOUT:", result.stdout[:500])
print("STDERR:", result.stderr[:500])
