#!/usr/bin/env python3
import re, sys
from pathlib import Path

path = str(Path(__file__).resolve().parent.parent / "test-order-dashboard/src/main/resources/dashboard/dist/dashboard.js")
with open(path, "r") as f:
    content = f.read()

matches = sorted(set(re.findall(r'process\.[a-zA-Z_.]+', content)))
print(f"FOUND: {len(matches)}")
for m in matches:
    print(m)

# Also show context around each match
for m in matches:
    idx = content.find(m)
    if idx >= 0:
        start = max(0, idx - 40)
        end = min(len(content), idx + len(m) + 40)
        print(f"\nCONTEXT for '{m}':")
        print(repr(content[start:end]))
