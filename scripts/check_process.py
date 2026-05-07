#!/usr/bin/env python3
import re, sys
from pathlib import Path

js_path = str(Path(__file__).resolve().parent.parent / "test-order-dashboard/src/main/resources/dashboard/dist/dashboard.js")

with open(js_path) as f:
    content = f.read()

print(f"FILE_SIZE: {len(content)}")
print(f"LINE_COUNT: {content.count(chr(10))}")

# Find process.env
matches = list(re.finditer(r'process\.env', content))
print(f"PROCESS_ENV_MATCHES: {len(matches)}")
for m in matches:
    s = max(0, m.start()-50)
    e = min(len(content), m.end()+50)
    print(f"  POS {m.start()}: {repr(content[s:e])}")

# Find bare process (not .process and not process( method calls)
bare = list(re.finditer(r'(?<!\.)(?<!\w)process(?![\w(])', content))
print(f"BARE_PROCESS: {len(bare)}")
for m in bare[:20]:
    s = max(0, m.start()-40)
    e = min(len(content), m.end()+40)
    print(f"  POS {m.start()}: {repr(content[s:e])}")

# Check if the Vite define replacement happened (should see "production" literal)
prod_count = content.count('"production"')
print(f"PRODUCTION_LITERAL: {prod_count}")

# Check Vue version
vue_matches = re.findall(r'@vue/\w+ v[\d.]+', content)
print(f"VUE_VERSIONS: {vue_matches}")

# Also check if the JAR has a different version
import subprocess, zipfile, os
jar_path = os.path.expanduser("~/.m2/repository/me/bechberger/test-order-dashboard/0.1.0-SNAPSHOT/test-order-dashboard-0.1.0-SNAPSHOT.jar")
if os.path.exists(jar_path):
    print(f"\nJAR_EXISTS: {jar_path}")
    with zipfile.ZipFile(jar_path) as zf:
        js_entries = [n for n in zf.namelist() if n.endswith("dashboard.js")]
        for entry in js_entries:
            jar_content = zf.read(entry).decode('utf-8', errors='replace')
            jar_matches = list(re.finditer(r'process\.env', jar_content))
            jar_bare = list(re.finditer(r'(?<!\.)(?<!\w)process(?![\w(])', jar_content))
            print(f"  JAR_ENTRY: {entry}")
            print(f"  JAR_SIZE: {len(jar_content)}")
            print(f"  JAR_PROCESS_ENV: {len(jar_matches)}")
            print(f"  JAR_BARE_PROCESS: {len(jar_bare)}")
            for m in jar_matches:
                s = max(0, m.start()-50)
                e = min(len(jar_content), m.end()+50)
                print(f"    POS {m.start()}: {repr(jar_content[s:e])}")
            for m in jar_bare[:10]:
                s = max(0, m.start()-40)
                e = min(len(jar_content), m.end()+40)
                print(f"    BARE POS {m.start()}: {repr(jar_content[s:e])}")
else:
    print(f"\nJAR_NOT_FOUND: {jar_path}")

# Check the maven-plugin JAR too (it might bundle the dashboard)
mvn_jar = os.path.expanduser("~/.m2/repository/me/bechberger/test-order-maven-plugin/0.1.0-SNAPSHOT/test-order-maven-plugin-0.1.0-SNAPSHOT.jar")
if os.path.exists(mvn_jar):
    print(f"\nMVN_PLUGIN_JAR: {mvn_jar}")
    with zipfile.ZipFile(mvn_jar) as zf:
        js_entries = [n for n in zf.namelist() if 'dashboard' in n.lower() and n.endswith('.js')]
        html_entries = [n for n in zf.namelist() if 'dashboard' in n.lower() and n.endswith('.html')]
        print(f"  JS_ENTRIES: {js_entries}")
        print(f"  HTML_ENTRIES: {html_entries}")
