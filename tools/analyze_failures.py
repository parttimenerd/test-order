#!/usr/bin/env python3
"""Analyze failure patterns from the cross-check test."""
import re

with open('/tmp/failures.txt') as f:
    lines = f.readlines()

# Parse each failure
categories = {
    'sfm_extra': [],    # SFM has methods that tree-sitter doesn't  
    'ts_extra': [],     # tree-sitter has methods that SFM doesn't
    'both': [],         # both have different sets
}

for line in lines:
    # Extract expected and actual
    m = re.search(r'expected: <\[([^\]]*)\]> but was: <\[([^\]]*)\]>', line)
    if not m:
        continue
    expected_str = m.group(1)
    actual_str = m.group(2)
    expected = set(x.strip() for x in expected_str.split(',') if x.strip())
    actual = set(x.strip() for x in actual_str.split(',') if x.strip())
    
    ts_only = expected - actual
    sfm_only = actual - expected
    
    # Extract class name
    cn = re.search(r'mismatch in (\S+)', line)
    cls = cn.group(1) if cn else '?'
    
    if ts_only and sfm_only:
        categories['both'].append((cls, ts_only, sfm_only))
    elif ts_only:
        categories['ts_extra'].append((cls, ts_only))
    elif sfm_only:
        categories['sfm_extra'].append((cls, sfm_only))

print(f"Total failures: {len(lines)}")
print(f"  tree-sitter has extra methods (SFM missing): {len(categories['ts_extra'])}")
print(f"  SFM has extra methods (false positives): {len(categories['sfm_extra'])}")
print(f"  Both differ: {len(categories['both'])}")

print("\n=== SFM has extra methods (SFM false positives) ===")
# Collect all SFM false positive method names
sfm_fps = {}
for cls, extras in categories['sfm_extra']:
    for e in extras:
        sfm_fps.setdefault(e, []).append(cls)
for name, classes in sorted(sfm_fps.items(), key=lambda x: -len(x[1])):
    print(f"  {name}: {len(classes)} classes ({', '.join(classes[:3])}{'...' if len(classes) > 3 else ''})")

print("\n=== Tree-sitter has extra methods (SFM missing) ===")
ts_fps = {}
for cls, extras in categories['ts_extra']:
    for e in extras:
        ts_fps.setdefault(e, []).append(cls)
for name, classes in sorted(ts_fps.items(), key=lambda x: -len(x[1])):
    print(f"  {name}: {len(classes)} classes ({', '.join(classes[:3])}{'...' if len(classes) > 3 else ''})")

print("\n=== Both differ ===")
for cls, ts_only, sfm_only in categories['both'][:10]:
    print(f"  {cls}: ts_extra={ts_only}, sfm_extra={sfm_only}")
