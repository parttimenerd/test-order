#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────
#  demo · hello world (petclinic, ≤30s)
#
#  Shows the full lifecycle on a petclinic-flavoured fixture:
#   1. Fresh project — learn dependencies on first run
#   2. Introduce a bug in Pet.java → PetTest and AppointmentSchedulerTest fail
#   3. Fix the bug — test-order surfaces the previously-failing tests first
# ──────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/_demo_lib.sh"

ROOT="$(repo_root)"
FIXTURE="$ROOT/test-fixtures/fixture-petclinic"
PET_SRC="$FIXTURE/src/main/java/org/example/petclinic/Pet.java"

cleanup() {
	# Restore Pet.java from the backup we made at script start
	[ -f "$PET_SRC.orig" ] && cp "$PET_SRC.orig" "$PET_SRC" && rm -f "$PET_SRC.orig"
	rm -rf "$FIXTURE/.test-order" "$FIXTURE/target" 2>/dev/null || true
}
trap cleanup EXIT
cleanup   # start clean

cd "$FIXTURE"
cp "$PET_SRC" "$PET_SRC.orig"   # save original so cleanup can restore it

# ── Step 1: learn run ─────────────────────────────────────────────
banner "test-order · petclinic demo"
step "Five test classes covering the petclinic domain. No state yet."
type_cmd "ls src/test/java/org/example/petclinic/"
pause 0.8

step "First run — test-order learns which classes each test exercises."
slow_type "mvn -q test"
pause 0.8

# ── Step 2: introduce a bug ───────────────────────────────────────
banner "Introduce a bug in Pet.java"
step "getAge() is changed to always return -1 — breaks PetTest."
pause 0.6

# One-liner patch: unique enough string for a clean sed match
python3 -c "
import pathlib
p = pathlib.Path('$PET_SRC')
p.write_text(p.read_text().replace(
    'return (int) java.time.temporal.ChronoUnit.YEARS.between(birthDate, java.time.LocalDate.now());',
    'return -1; // BUG'
))
"
type_cmd "grep 'BUG' src/main/java/org/example/petclinic/Pet.java"
pause 0.6

# ── Step 3: run with the bug ─────────────────────────────────────
banner "Run tests — failures detected"
slow_type "mvn -q test-order:auto test || true"
mvn -q test-order:auto test 2>&1 \
  | grep -E "^\[ERROR\] (Tests run|org\.example\.|  Pet)" \
  | grep -v "Please refer\|full stack\|re-run Maven\|information about\|Help 1\|MojoFailure" \
  || true
pause 1.0

# ── Step 4: fix the bug ───────────────────────────────────────────
banner "Fix the bug"
cp "$PET_SRC.orig" "$PET_SRC"
step "Pet.java restored."
pause 0.6

# ── Step 5: rerun — failing tests go first ───────────────────────
banner "Re-run: test-order puts the bug-catchers first"
slow_type "mvn -q test-order:auto test"
pause 0.8

# ── Step 6: show the ranking ─────────────────────────────────────
banner "Prioritised order"
slow_type "mvn -q test-order:show"
pause 1.5
