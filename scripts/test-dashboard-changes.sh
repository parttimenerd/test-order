#!/usr/bin/env bash
# Test change detection + dashboard with modifications
set -e
cd /Users/i560383_1/code/experiments/test-order/test-order-example

echo "═══════════════════════════════════════════════════════════"
echo "  test-order dashboard — change detection test"
echo "═══════════════════════════════════════════════════════════"
echo ""

# Make a source change to trigger change detection
echo "▶ Step 1: Modify Calculator.java"
cp src/main/java/com/example/app/Calculator.java /tmp/Calculator.java.bak
# Add a trivial comment to trigger change
echo "// modified for test" >> src/main/java/com/example/app/Calculator.java
echo "  ✓ Modified"
echo ""

# Run in order mode with changes
echo "▶ Step 2: Run with changes (should detect changed class)"
mvn test-order:combined test 2>&1 | grep -E "test-order|change|Changed|Tests run|BUILD" | head -15
echo ""

# Show order — should now show changed class
echo "▶ Step 3: Show order with changes"
mvn test-order:show-order 2>&1 | grep -E "#|Score|Changed|Dep" | head -10
echo ""

# Generate dashboard
echo "▶ Step 4: Generate dashboard with changes"
mvn test-order:dashboard 2>&1 | grep -E "Dashboard|test-order" | head -5
echo ""

# Validate JSON has changed classes
echo "▶ Step 5: Validate dashboard data reflects changes"
python3 -c "
import re, json
html = open('target/test-order-dashboard/index.html').read()
m = re.search(r'<script type=\"application/json\" id=\"dashboard-data\">(.*?)</script>', html, re.DOTALL)
data = json.loads(m.group(1))
print(f'  changedClasses: {data.get(\"changedClasses\",[])}')
print(f'  changedTestClasses: {data.get(\"changedTestClasses\",[])}')
print(f'  runs: {len(data.get(\"runs\",[]))}')
for t in data.get('tests',[]):
    print(f'  test: {t[\"name\"]}: rank={t[\"rank\"]} score={t[\"score\"]} depOverlap={t[\"depOverlap\"]}/{t[\"depTotal\"]} changed={t[\"isChanged\"]} duration={t[\"duration\"]}ms')
    if t.get('deps'):
        print(f'    deps: {t[\"deps\"]}')
    if t.get('methods'):
        print(f'    methods: {[m[\"name\"] for m in t[\"methods\"]]}')
    else:
        print(f'    methods: null (expected without FULL_METHOD mode)')

# Check coverage data
cov = data.get('coverage')
if cov:
    print(f'  coverage: {cov[\"totalSourceClasses\"]} source classes')
    for c in cov.get('classes',[]):
        print(f'    {c[\"name\"]}: {c[\"testCount\"]} tests -> {c[\"tests\"]}')
else:
    print(f'  ⚠ coverage is null!')
" 2>&1
echo ""

# Restore
echo "▶ Step 6: Restore original file"
cp /tmp/Calculator.java.bak src/main/java/com/example/app/Calculator.java
echo "  ✓ Restored"
echo ""

echo "═══════════════════════════════════════════════════════════"
echo "  Change detection test complete"
echo "═══════════════════════════════════════════════════════════"
