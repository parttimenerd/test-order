#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# test-order plugin demo with Spring Petclinic
#
# Usage:  asciinema rec --window-size 120x35 --idle-time-limit 3 demo.cast -c 'bash scripts/demo-petclinic.sh'
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

PETCLINIC="spring-petclinic"
MVN_SKIP="-Dcheckstyle.skip -Dspring-javaformat.skip"
# Exclude Docker-dependent integration tests
EXCLUDE='-Dtest=!MySqlIntegrationTests,!PostgresIntegrationTests,!CrashControllerIntegrationTests,!PetClinicIntegrationTests'

# ── Helpers ──────────────────────────────────────────────────────────────────
CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
RED='\033[0;31m'
BOLD='\033[1m'
RESET='\033[0m'

# Simulate typing at ~40 chars/sec then run
typecmd() {
  local cmd="$1"
  printf '$ '
  for (( i=0; i<${#cmd}; i++ )); do
    printf '%s' "${cmd:$i:1}"
    sleep 0.025
  done
  echo
  sleep 0.3
  eval "$cmd"
}

banner() {
  echo
  echo -e "${CYAN}${BOLD}═══ $1 ═══${RESET}"
  echo
  sleep 1
}

pause() { sleep "${1:-2}"; }

# ── Setup ────────────────────────────────────────────────────────────────────
cd "$(dirname "$0")/.."

banner "test-order demo · Spring Petclinic"
echo -e "This demo shows the ${BOLD}test-order${RESET} Maven plugin workflow:"
echo -e "  1. ${GREEN}Learn${RESET} — initial run to collect dependency data"
echo -e "  2. ${YELLOW}Modify${RESET} source code"
echo -e "  3. ${GREEN}Show order${RESET} — see change-aware prioritization"
echo -e "  4. ${GREEN}Run${RESET} — tests reordered based on changes"
echo -e "  5. ${GREEN}Dashboard${RESET} — visualize results"
echo
pause 3

# ── Step 0: Clean slate ─────────────────────────────────────────────────────
banner "Step 0 · Clean slate"
typecmd "cd $PETCLINIC"
typecmd "rm -rf .test-order"
echo -e "${GREEN}✓ Removed existing state files — starting fresh${RESET}"
pause

# ── Step 1: Learn ────────────────────────────────────────────────────────────
banner "Step 1 · Learn mode (first run — collecting dependency data)"
echo -e "Running tests for the first time. The plugin instruments bytecode to"
echo -e "discover which test depends on which source class."
echo
typecmd "mvn test $MVN_SKIP $EXCLUDE 2>&1 | tail -8"
pause

echo
echo -e "${GREEN}✓ Dependency data collected and stored in test-dependencies.lz4${RESET}"
typecmd "ls -lh .test-order/test-dependencies.lz4 .test-order/state.lz4"
pause

# ── Step 1b: Show initial order ─────────────────────────────────────────────
banner "Step 1b · Show initial test order"
echo -e "No source changes yet, so scores are based on speed only:"
echo
typecmd "mvn test-order:show-order $MVN_SKIP 2>&1 | grep -E '^\[INFO\]   [0-9]'"
pause 3

# ── Step 2: Modify OwnerController ──────────────────────────────────────────
banner "Step 2 · Modify source code"
echo -e "Let's make a change to ${BOLD}OwnerController.java${RESET}:"
echo

OWNER_CTRL="src/main/java/org/springframework/samples/petclinic/owner/OwnerController.java"

# Add a comment to make it a "changed" file
typecmd "sed -i.bak 's/class OwnerController {/class OwnerController { \/\/ modified: add logging/' $OWNER_CTRL"
echo
echo -e "${YELLOW}✓ OwnerController.java modified${RESET}"
typecmd "diff $OWNER_CTRL.bak $OWNER_CTRL || true"
pause 2

# ── Step 3: Show change-aware order ─────────────────────────────────────────
banner "Step 3 · Show predicted test order (before running)"
echo -e "show-order detects OwnerController changed and boosts related tests:"
echo
typecmd "mvn test-order:show-order $MVN_SKIP 2>&1 | grep -E '^\[INFO\]   [0-9]'"
echo
echo -e "${GREEN}✓ OwnerControllerTests jumped to the top (dep overlap score)!${RESET}"
echo -e "  VisitControllerTests, ClinicServiceTests also boosted (shared deps)"
pause 3

# ── Step 4: Run with prioritization ─────────────────────────────────────────
banner "Step 4 · Run tests in prioritized order"
echo -e "Now mvn test runs them in the change-aware order:"
echo
typecmd "mvn test $MVN_SKIP $EXCLUDE 2>&1 | tail -8"
echo
echo -e "${GREEN}✓ Tests ran — Owner-related tests executed first${RESET}"
pause 2

# ── Step 5: Modify VetController + show ─────────────────────────────────────
banner "Step 5 · Change VetController + show new order"
VET_CTRL="src/main/java/org/springframework/samples/petclinic/vet/VetController.java"
typecmd "sed -i.bak 's/class VetController {/class VetController { \/\/ modified: add caching/' $VET_CTRL"
echo -e "${YELLOW}✓ VetController.java modified${RESET}"
echo

# Restore OwnerController
typecmd "mv $OWNER_CTRL.bak $OWNER_CTRL"
echo -e "(restored OwnerController.java)"
echo
typecmd "mvn test-order:show-order $MVN_SKIP 2>&1 | grep -E '^\[INFO\]   [0-9]'"
echo
echo -e "${GREEN}✓ VetControllerTests now ranked higher — Owner tests dropped back${RESET}"
pause 2

typecmd "mvn test $MVN_SKIP $EXCLUDE 2>&1 | tail -5"
pause 2

# ── Step 6: More history runs ───────────────────────────────────────────────
banner "Step 6 · Build up run history"
echo -e "Running a few more times (with different changes) to accumulate history..."
echo

# Run 4: modify Pet model
PET="src/main/java/org/springframework/samples/petclinic/owner/Pet.java"
sed -i.bak 's/public class Pet /public class Pet \/* modified *\/ /' "$PET"
mv "$VET_CTRL.bak" "$VET_CTRL"
echo -e "  → Changed ${BOLD}Pet.java${RESET}"
typecmd "mvn test $MVN_SKIP $EXCLUDE 2>&1 | grep -E 'Tests run:|BUILD' | tail -2"
pause

# Run 5: modify PetValidator
mv "$PET.bak" "$PET"
VALIDATOR="src/main/java/org/springframework/samples/petclinic/owner/PetValidator.java"
sed -i.bak 's/public class PetValidator/public class PetValidator \/* modified *\//' "$VALIDATOR"
echo -e "  → Changed ${BOLD}PetValidator.java${RESET}"
typecmd "mvn test $MVN_SKIP $EXCLUDE 2>&1 | grep -E 'Tests run:|BUILD' | tail -2"
pause

# Run 6: clean (no changes)
mv "$VALIDATOR.bak" "$VALIDATOR"
echo -e "  → No changes (baseline run)"
typecmd "mvn test $MVN_SKIP $EXCLUDE 2>&1 | grep -E 'Tests run:|BUILD' | tail -2"

echo
echo -e "${GREEN}✓ 6 runs completed — history accumulated${RESET}"
pause

# ── Step 7: Dashboard ────────────────────────────────────────────────────────
banner "Step 7 · Generate the interactive dashboard"
typecmd "mvn test-order:dashboard $MVN_SKIP 2>&1 | tail -5"
echo
echo -e "${GREEN}✓ Dashboard generated at target/test-order-dashboard/index.html${RESET}"
typecmd "ls -lh target/test-order-dashboard/index.html"
pause

# ── Final ────────────────────────────────────────────────────────────────────
banner "Done!"
echo -e "The dashboard is ready to open in your browser:"
echo -e "  ${BOLD}open target/test-order-dashboard/index.html${RESET}"
echo
echo -e "Key features shown:"
echo -e "  • ${GREEN}Automatic dependency learning${RESET} from bytecode instrumentation"
echo -e "  • ${YELLOW}Change-aware prioritization${RESET} — modified classes push related tests first"
echo -e "  • ${CYAN}Run history tracking${RESET} — APFD, failure positions, score evolution"
echo -e "  • ${BOLD}Interactive dashboard${RESET} — explore, compare, tune weights"
echo
pause 3
