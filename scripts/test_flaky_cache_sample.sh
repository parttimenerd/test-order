#!/usr/bin/env bash
#
# Smoke test for the three Develocity-parity features against
# samples/sample-flaky-cache:
#   1. testorder.flaky.retries     — auto-retry FLAKY tests
#   2. testorder.flaky.quarantine  — downgrade FLAKY final failures to aborted
#   3. testorder.cache.skipUnchanged — skip tests whose deps are unchanged
#
# Installs the plugin first, then runs three scenarios and asserts on the
# expected log lines. Exit non-zero on any failure.
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$SCRIPT_DIR/.."
SAMPLE="$ROOT/samples/sample-flaky-cache"

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; NC='\033[0m'
info() { echo -e "${CYAN}▸ $*${NC}"; }
ok()   { echo -e "${GREEN}✓ $*${NC}"; }
fail() { echo -e "${RED}✗ $*${NC}"; exit 1; }

info "Installing test-order plugin from source..."
(cd "$ROOT" && mvn -q -pl test-order-core,test-order-annotations,test-order-junit,test-order-maven-plugin \
    -am install -DskipTests -Dspotless.check.skip=true) \
    || fail "plugin install failed"
ok "Plugin installed"

cd "$SAMPLE"

info "Seeding ML report (classifies FlakyServiceTest as FLAKY)..."
mkdir -p .test-order
cp seed/ml-report.txt .test-order/ml-report.txt
ok "Seeded"

# --- 1. Auto-retry --------------------------------------------------------
info "Scenario 1: auto-retry recovers a flaky failure"
rm -f target/flaky-counter .test-order/state.lz4
LOG=/tmp/sample-flaky-cache-1.log
mvn -q test -Dsample.flaky.failUntil=1 -Dtestorder.flaky.retries=2 > "$LOG" 2>&1
status=$?
grep -q "retry succeeded for com.example.FlakyServiceTest" "$LOG" \
    || { tail -50 "$LOG"; fail "retry log line missing"; }
[ $status -eq 0 ] || { tail -50 "$LOG"; fail "build red despite retry"; }
ok "auto-retry succeeded"

# --- 2. Quarantine --------------------------------------------------------
info "Scenario 2: quarantine downgrades final flaky failure to aborted"
rm -f target/flaky-counter
LOG=/tmp/sample-flaky-cache-2.log
mvn test -Dsample.flaky.failUntil=99 -Dtestorder.flaky.retries=2 \
    -Dtestorder.flaky.quarantine=true > "$LOG" 2>&1
status=$?
# Quarantined test appears as Skipped in surefire output
grep -qE "Tests run: 1, Failures: 0, Errors: 0, Skipped: 1.*FlakyServiceTest" "$LOG" \
    || { tail -50 "$LOG"; fail "quarantined test not reported as Skipped"; }
[ $status -eq 0 ] || { tail -50 "$LOG"; fail "build red despite quarantine"; }
ok "quarantine downgraded failure"

# --- 3. Skip-if-unchanged cache -------------------------------------------
info "Scenario 3: build pass-streak then cache should skip unchanged tests"
rm -f .test-order/state.lz4 target/flaky-counter
for i in 1 2 3 4; do mvn -q test > /dev/null 2>&1 || true; done
LOG=/tmp/sample-flaky-cache-3.log
mvn test -Dtestorder.cache.skipUnchanged=true -Dtestorder.cache.minPassStreak=3 \
    -Dtestorder.auto.runRemaining=false > "$LOG" 2>&1
grep -qE "Cache: skipped [0-9]+ unchanged test" "$LOG" \
    || { tail -50 "$LOG"; fail "cache log line missing"; }
ok "cache skipped unchanged tests"

ok "All scenarios passed"
