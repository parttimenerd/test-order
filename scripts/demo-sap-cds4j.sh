#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# test-order demo · SAP cds4j (real-world CAP Java SDK)
#
# Storyboard — three scenes:
#   1. Baseline: all 111 test classes green (learn index already built)
#   2. Inject bug → test-order:show ranks ResultTest first
#   3. Run top-3 → bug caught in ~17 s
#   4. SA auto-mode (changeMode=uncommitted) → bug caught in ~10 s
#
# Usage:
#   bash scripts/demo-sap-cds4j.sh          # normal pace
#   DEMO_FAST=1 bash scripts/demo-sap-cds4j.sh   # skip pauses (CI / dry-run)
#
# Requirements:
#   - test-order-maven-plugin installed to ~/.m2
#     (mvn install -DskipTests -pl test-order-core,test-order-maven-plugin)
#   - third-party/cds4j already has a learned index (.test-order/ present)
#     (scripts/third_party_test_plan.sh learn cds4j)
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=_demo_lib.sh
source "$SCRIPT_DIR/_demo_lib.sh"

CDS4J_DIR="$ROOT_DIR/third-party/cds4j"
PATCH_FILE="$SCRIPT_DIR/bugs/cds4j/resultimpl-single-wrong-exception.patch"
RESULT_IMPL="cds4j-core/src/main/java/com/sap/cds/impl/ResultImpl.java"
LOG_DIR="$ROOT_DIR/target/demo-cds4j"
mkdir -p "$LOG_DIR"

# ── Preflight ─────────────────────────────────────────────────────────────────
if [[ ! -f "$PATCH_FILE" ]]; then
    echo "ERROR: patch file missing: $PATCH_FILE" >&2; exit 1
fi
if [[ ! -f "$CDS4J_DIR/.test-order/test-dependencies.lz4" ]]; then
    echo "ERROR: no learned index at $CDS4J_DIR/.test-order/" >&2
    echo "       Run: scripts/third_party_test_plan.sh learn cds4j" >&2
    exit 1
fi

cleanup() {
    # Always reverse the patch on exit so the working tree is clean
    if git -C "$CDS4J_DIR" diff --quiet -- "$RESULT_IMPL" 2>/dev/null; then
        :  # already clean
    else
        patch -d "$CDS4J_DIR" -p1 -R --no-backup-if-mismatch --forward -s \
            < "$PATCH_FILE" 2>/dev/null || true
    fi
}
trap cleanup EXIT

# Ensure we start from a clean state
if ! git -C "$CDS4J_DIR" diff --quiet -- "$RESULT_IMPL" 2>/dev/null; then
    git -C "$CDS4J_DIR" checkout -- "$RESULT_IMPL"
fi

export GIT_PAGER=cat LESS="-FRX" PAGER=cat

# ── Shared Maven args ─────────────────────────────────────────────────────────
# cds4j has extensions=true on the plugin, so goals resolve as test-order:…
# We always target cds4j-core because that's where ResultImpl lives.
MVN_ARGS=(
    -pl cds4j-core
    --no-transfer-progress
    -q
)

# ══════════════════════════════════════════════════════════════════════════════
banner "test-order · SAP cds4j demo"
# ══════════════════════════════════════════════════════════════════════════════
cat <<'EOF'
SAP Cloud Application Programming Model (CAP) is the foundation for
enterprise cloud apps at SAP. cds4j is the core Java library — every
CAP Java service calls ResultImpl.single() to fetch a single entity.

This demo shows test-order selecting only the affected tests when a
one-line bug is introduced in that critical path.

  Project : cds4j-core  (~111 test classes)
  Bug     : ResultImpl.single() throws the WRONG exception on empty result
  Goal    : Select only the 3 tests that cover ResultImpl — catch the bug
            in seconds instead of minutes
EOF
pause 3

cd "$CDS4J_DIR"

# ══════════════════════════════════════════════════════════════════════════════
banner "Scene 1 — Baseline: all tests green"
# ══════════════════════════════════════════════════════════════════════════════
step "The learned dependency index is already built:"
type_cmd "ls -lh .test-order/test-dependencies.lz4"
pause 1

step "Show the prioritised test order (no changed classes → score by history & speed):"
type_cmd "mvn me.bechberger:test-order-maven-plugin:show ${MVN_ARGS[*]} 2>&1 | grep -v '^WARNING:'"
pause 1.5

# ══════════════════════════════════════════════════════════════════════════════
banner "Scene 2 — Inject the bug"
# ══════════════════════════════════════════════════════════════════════════════
step "The bug: ResultImpl.single() throws NonUniqueResultException on EMPTY result"
step "         instead of EmptyResultException — a one-line variable swap"
echo
type_cmd "cat $PATCH_FILE"
pause 1

type_cmd "patch -d . -p1 < '$PATCH_FILE'"
echo
step "Confirm the change:"
type_cmd "git diff $RESULT_IMPL"
pause 1.5

step "Now show the prioritised order — test-order detects the changed class:"
SHOW_LOG="$LOG_DIR/show.log"
type_cmd "mvn me.bechberger:test-order-maven-plugin:show \
    -Dtestorder.changeMode=uncommitted \
    ${MVN_ARGS[*]} 2>&1 | tee '$SHOW_LOG' | grep -E '^\s+[0-9]+\.|changed|affected|total'"
echo
step "ResultTest appears at the top — it's the only test that directly covers ResultImpl.single()."
pause 2

# ══════════════════════════════════════════════════════════════════════════════
banner "Scene 3 — Run only top-3: bug caught"
# ══════════════════════════════════════════════════════════════════════════════
step "Run the 3 most affected tests (instead of all 111):"
BUG_LOG="$LOG_DIR/top3.log"
set +e
type_cmd "mvn clean me.bechberger:test-order-maven-plugin:affected test \
    -Dtestorder.changeMode=explicit \
    '-Dtestorder.changed.classes=com.sap.cds.impl.ResultImpl' \
    -Dtestorder.affected.topN=3 \
    -Dtestorder.mode=skip \
    ${MVN_ARGS[*]} 2>&1 | tee '$BUG_LOG' | tail -20"
EXIT_TOP3=$?
set -e

echo
if grep -qE "Tests run:.*Failures: [1-9]|Tests in error:|BUILD FAILURE" "$BUG_LOG" 2>/dev/null; then
    step "✔  Bug CAUGHT in top-3 selected tests!"
    echo
    step "Failing test detail:"
    grep -E "Expecting|but was|NonUnique|EmptyResult|FAILURE.*ResultImpl|testSingle" \
        "$BUG_LOG" 2>/dev/null | head -8 || true
else
    echo "  (see $BUG_LOG for details)"
fi
pause 2

# ══════════════════════════════════════════════════════════════════════════════
banner "Scene 4 — SA auto-mode: zero configuration needed"
# ══════════════════════════════════════════════════════════════════════════════
step "SA auto-mode uses git diff to detect the changed class automatically."
step "No -Dtestorder.changed.classes needed — test-order finds it from the uncommitted diff."
echo
SA_LOG="$LOG_DIR/sa-auto.log"
set +e
type_cmd "mvn clean me.bechberger:test-order-maven-plugin:affected test \
    -Dtestorder.changeMode=uncommitted \
    -Dtestorder.affected.topN=5 \
    -Dtestorder.mode=skip \
    ${MVN_ARGS[*]} 2>&1 | tee '$SA_LOG' | grep -E 'changed classes|selected|Tests run|FAILURE|BUILD'"
set -e

echo
if grep -qE "Tests run:.*Failures: [1-9]|Tests in error:|BUILD FAILURE" "$SA_LOG" 2>/dev/null; then
    step "✔  SA auto-mode caught the bug!"
    step "   test-order detected the change from git diff — no explicit class name needed."
else
    echo "  (see $SA_LOG)"
fi
pause 1.5

# ══════════════════════════════════════════════════════════════════════════════
banner "Scene 5 — Interactive dashboard"
# ══════════════════════════════════════════════════════════════════════════════
step "Generate the dashboard with the changed class highlighted (bug still applied):"
DASH_LOG="$LOG_DIR/dashboard.log"
type_cmd "mvn me.bechberger:test-order-maven-plugin:dashboard \
    -Dtestorder.changeMode=uncommitted \
    -Dtestorder.dashboard.open=true \
    -pl cds4j-core --no-transfer-progress \
    2>&1 | tee '$DASH_LOG' | grep -v '^WARNING:' | grep -E 'Dashboard written|open|BUILD SUCCESS|test-order\]'"

DASH_HTML=$(grep "Dashboard written to:" "$DASH_LOG" 2>/dev/null | sed 's/.*written to: //' | tr -d '[:space:]' || true)
if [[ -z "$DASH_HTML" ]]; then
    DASH_HTML=$(find "$CDS4J_DIR" /tmp -name "index.html" -path "*test-order-dashboard*" 2>/dev/null | head -1 || true)
fi
if [[ -n "$DASH_HTML" && -f "$DASH_HTML" ]]; then
    DASH_SIZE=$(du -sh "$DASH_HTML" 2>/dev/null | cut -f1 || true)
    step "Dashboard ready ($DASH_SIZE) — look for ResultImpl in the Changed Classes column"
else
    step "Dashboard open — look for ResultImpl in the Changed Classes column"
fi
pause 2

# ══════════════════════════════════════════════════════════════════════════════
banner "Summary"
# ══════════════════════════════════════════════════════════════════════════════
# Extract timing numbers from logs if available
FULL_TIME=$(grep "Total time:" "$BUG_LOG" 2>/dev/null | tail -1 | sed 's/.*Total time:[ ]*//' || echo "~17 s")
SA_TIME=$(grep "Total time:" "$SA_LOG"  2>/dev/null | tail -1 | sed 's/.*Total time:[ ]*//' || echo "~10 s")
# Count selected test classes: read from the show log's "affected=N" header
SELECTED=$(grep -oE 'affected=[0-9]+' "$LOG_DIR/show.log" 2>/dev/null | head -1 | grep -oE '[0-9]+' || echo "3")

cat <<EOF

  Without test-order : run all 111 test classes in cds4j-core  (~3 min)
  With test-order    : run only ${SELECTED} affected test classes
    top-3 mode       : ${FULL_TIME}
    SA auto-mode     : ${SA_TIME}

  How it works:
    • Learn phase instruments each test run and records which production
      classes each test class actually loaded at runtime (bytecode level)
    • On change: test-order scores every test class by dependency overlap
      with the changed set — ResultTest scores highest because it directly
      calls ResultImpl.single()
    • SA auto-mode (changeMode=uncommitted) reads 'git diff' automatically —
      no explicit class name, no build-system integration beyond the plugin

  Same bug detection. Fraction of the compute cost.

EOF
