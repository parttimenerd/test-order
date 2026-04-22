#!/usr/bin/env bash
# Test the dashboard workflow as a normal user would
set -e
cd /Users/i560383_1/code/experiments/test-order/test-order-example

echo "═══════════════════════════════════════════════════════════"
echo "  test-order dashboard — normal user workflow test"
echo "═══════════════════════════════════════════════════════════"
echo ""

# 1. Clean state
echo "▶ Step 1: Clean slate — remove old artifacts"
rm -rf .test-order
echo "  ✓ Cleaned"
echo ""

# 2. First learn run
echo "▶ Step 2: First learn run"
mvn test-order:combined test -q 2>&1 | grep -E "test-order|Tests run|BUILD" | head -10
echo ""

# 3. Check artifacts created
echo "▶ Step 3: Check artifacts"
ls -lh .test-order/test-dependencies.lz4 .test-order/state.lz4 .test-order/hashes.lz4 2>/dev/null || echo "  ⚠ Some artifacts missing!"
echo ""

# 4. Second run (should be order mode now)
echo "▶ Step 4: Second run (order mode)"
mvn test-order:combined test -q 2>&1 | grep -E "test-order|Tests run|BUILD" | head -10
echo ""

# 5. Show order
echo "▶ Step 5: Show order"
mvn test-order:show-order 2>&1 | grep -v "^\[INFO\] ---" | grep -v "^\[INFO\] $" | grep -E "Changed|#|Test Class|Score|test-order" | head -20
echo ""

# 6. Dump index
echo "▶ Step 6: Dump dependency index"
mvn test-order:dump 2>&1 | grep -v "^\[INFO\] ---" | grep -v "^\[INFO\] $" | grep -v "WARNING" | tail -20
echo ""

# 7. Generate dashboard
echo "▶ Step 7: Generate dashboard"
mvn test-order:dashboard 2>&1 | grep -E "Dashboard|Generated|test-order" | head -5
echo ""

# 8. Check dashboard file
echo "▶ Step 8: Check dashboard output"
ls -lh target/test-order-dashboard/index.html 2>/dev/null || echo "  ⚠ Dashboard file missing!"
echo ""

# 9. Extract and validate dashboard JSON
echo "▶ Step 9: Validate dashboard JSON data"
python3 -c "
import re, json, sys
html = open('target/test-order-dashboard/index.html').read()
m = re.search(r'<script type=\"application/json\" id=\"dashboard-data\">(.*?)</script>', html, re.DOTALL)
if not m:
    print('  ⚠ No dashboard-data script tag found!')
    sys.exit(1)
data = json.loads(m.group(1))
print(f'  project:        {data.get(\"project\",{}).get(\"name\",\"?\")}')
print(f'  tests:          {len(data.get(\"tests\",[]))}')
print(f'  runs:           {len(data.get(\"runs\",[]))}')
print(f'  changedClasses: {len(data.get(\"changedClasses\",[]))}')
print(f'  weights:        {data.get(\"weights\",{})}')
print(f'  coverage:       {data.get(\"coverage\")}')
print(f'  medianDuration: {data.get(\"medianDuration\")}')
print(f'  weightDefs:     {len(data.get(\"weightDefs\",[]))} entries')

# Check each test entry
for t in data.get('tests',[]):
    issues = []
    if 'name' not in t: issues.append('missing name')
    if 'rank' not in t: issues.append('missing rank')
    if 'score' not in t: issues.append('missing score')
    if 'deps' not in t: issues.append('missing deps')
    if 'duration' not in t: issues.append('missing duration')
    if 'methods' not in t and t.get('methods') is None: pass  # null is ok
    if t.get('depTotal',0) > 0 and not t.get('deps'):
        issues.append(f'depTotal={t[\"depTotal\"]} but deps empty')
    if issues:
        print(f'  ⚠ Test {t.get(\"name\",\"?\")}: {issues}')

# Check each run entry
for i, r in enumerate(data.get('runs',[])):
    issues = []
    if 'timestamp' not in r: issues.append('missing timestamp')
    if 'totalTests' not in r: issues.append('missing totalTests')
    if 'apfd' not in r: issues.append('missing apfd')
    if r.get('totalTests',0) == 0: issues.append('totalTests=0')
    if issues:
        print(f'  ⚠ Run {i}: {issues}')

# Verify coverage field
cov = data.get('coverage')
if cov is not None:
    if 'totalSourceClasses' not in cov: print('  ⚠ coverage missing totalSourceClasses')
    if 'classes' not in cov: print('  ⚠ coverage missing classes')
    else:
        for c in cov['classes']:
            if c.get('testCount',0) > 0 and not c.get('tests'):
                print(f'  ⚠ Coverage class {c.get(\"name\",\"?\")}: testCount={c[\"testCount\"]} but tests empty')

print('  ✓ JSON validation complete')
" 2>&1
echo ""

# 10. Validate HTML structure
echo "▶ Step 10: Validate HTML structure"
python3 -c "
import re
html = open('target/test-order-dashboard/index.html').read()
checks = [
    ('Vue 3 library', 'vue.global'),
    ('Chart.js library', 'chart.js' if 'chart.js' in html.lower() else 'Chart'),
    ('D3.js library', 'd3.'),
    ('dashboard-data JSON', 'dashboard-data'),
    ('dashboard.js inlined', 'createApp'),
    ('body.html inlined', 'id=\"app\"'),
    ('dashboard.css inlined', '--bg-base'),
    ('sidebar (aside)', '<aside'),
    ('tab buttons', 'tab-btn'),
    ('dep graph wrapper', 'dg-wrap'),
    ('coverage treemap wrapper', 'cov-treemap'),
    ('score breakdown canvas', 'bd-main'),
    ('duration chart canvas', 'hd-main'),
    ('score-over-time canvas', 'hs-main'),
    ('run-position canvas', 'hp-main'),
    ('changed classes panel', 'showChangedPanel'),
    ('SIDEBAR_SORT_COLS', 'SIDEBAR_SORT_COLS'),
    ('selectTest function', 'selectTest'),
    ('selectMethod function', 'selectMethod'),
    ('method sub-list in sidebar', 'selectMethod(m)'),
]
for label, needle in checks:
    found = needle in html
    print(f'  {\"✓\" if found else \"⚠ MISSING\"} {label}')
print()
print(f'  Total HTML size: {len(html)} bytes ({len(html)//1024} KB)')
" 2>&1
echo ""

echo "═══════════════════════════════════════════════════════════"
echo "  Workflow test complete"
echo "═══════════════════════════════════════════════════════════"
