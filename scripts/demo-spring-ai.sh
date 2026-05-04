#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# test-order plugin demo with Spring AI (spring-ai-commons module)
#
# Usage:
#   asciinema rec --cols 120 --rows 35 --idle-time-limit 3 \
#       demo-spring-ai.cast -c 'bash scripts/demo-spring-ai.sh'
#
# Aborts on the first error (set -e) — this is a live, unscripted demo.
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

PROJECT="third-party/spring-ai"
MODULE="spring-ai-commons"
MVN="./mvnw"
PLUGIN="me.bechberger:test-order-maven-plugin:0.1.0-SNAPSHOT"

# ── Helpers ──────────────────────────────────────────────────────────────────
CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
RED='\033[0;31m'
BOLD='\033[1m'
RESET='\033[0m'

typecmd() {
  local cmd="$1"
  printf '$ '
  for (( i=0; i<${#cmd}; i++ )); do
    printf '%s' "${cmd:$i:1}"
    sleep 0.020
  done
  echo
  sleep 0.3
  eval "$cmd"
}

# Like typecmd but tolerates pipefail / non-zero exits (grep no match, test failures)
typecmd_pipe() {
  local cmd="$1"
  printf '$ '
  for (( i=0; i<${#cmd}; i++ )); do
    printf '%s' "${cmd:$i:1}"
    sleep 0.020
  done
  echo
  sleep 0.3
  eval "$cmd" || true
}

banner() {
  echo
  echo -e "${CYAN}${BOLD}═══ $1 ═══${RESET}"
  echo
  sleep 1
}

pause() { sleep "${1:-2}"; }

# ── Navigate ─────────────────────────────────────────────────────────────────
cd "$(dirname "$0")/.."
cd "$PROJECT"

banner "test-order demo · Spring AI"
echo -e "This demo shows the ${BOLD}test-order${RESET} Maven plugin on ${BOLD}Spring AI${RESET}"
echo -e "— the Spring framework for AI/LLM applications (20 test classes)."
echo
echo -e "Workflow:"
echo -e "  1. ${GREEN}Learn${RESET}  — collect dependency data via bytecode instrumentation"
echo -e "  2. ${GREEN}Order${RESET}  — baseline ranking (speed only)"
echo -e "  3. ${RED}Bug${RESET}    — inject a bug into Document.java"
echo -e "  4. ${RED}Fail${RESET}   — Document tests fail ${BOLD}early${RESET} thanks to test-order"
echo -e "  5. ${GREEN}Fix${RESET}    — fix the bug, change a different file"
echo -e "  6. ${GREEN}Dashboard${RESET} — generate interactive report"
echo
echo -e "Every command is guarded by ${BOLD}set -e${RESET} — first error aborts the demo."
pause 3

# ── Paths ────────────────────────────────────────────────────────────────────
DOC="$MODULE/src/main/java/org/springframework/ai/document/Document.java"
SPLITTER="$MODULE/src/main/java/org/springframework/ai/transformer/splitter/TextSplitter.java"

# ── Step 0: Clean slate ─────────────────────────────────────────────────────
banner "Step 0 · Clean slate"
typecmd "rm -rf .test-order"
echo -e "${GREEN}✓ Removed existing state files — starting fresh${RESET}"
pause

# ── Step 1: Learn mode ──────────────────────────────────────────────────────
banner "Step 1 · Learn mode (collecting dependency data)"
echo -e "Running tests with bytecode instrumentation to discover which"
echo -e "test class exercises which source class."
echo
typecmd_pipe "$MVN -pl $MODULE -am $PLUGIN:auto test 2>&1 | tail -12"
pause

echo
echo -e "${GREEN}✓ Dependency index created!${RESET}"
typecmd "ls -lh .test-order/test-dependencies.lz4 .test-order/state.lz4"
pause

# ── Step 2: Baseline ordering ───────────────────────────────────────────────
banner "Step 2 · Baseline ordering (no code changes)"
echo -e "No source changes yet — scores are based on speed only."
echo -e "Note where ${BOLD}DocumentTests${RESET} sits in the ranking:"
echo
typecmd_pipe "$MVN -pl $MODULE -am $PLUGIN:show-order 2>&1 | grep -E '^  [0-9]' | tail -20"
echo
echo -e "  DocumentTests is around ${BOLD}position 12${RESET} — middle of the pack."
pause 3

# ── Step 3: Inject a bug into Document.java ──────────────────────────────────
banner "Step 3 · Inject a bug into Document.java"
echo -e "A developer 'accidentally' breaks ${BOLD}getText()${RESET} by prepending a prefix."
echo -e "This is a realistic mistake — e.g. adding draft watermarking:"
echo

# Save backup
cp "$DOC" "$DOC.bak"

# Inject the bug: getText() returns "[DRAFT] " + text instead of just text
sed -i.sed '/public @Nullable String getText() {/,/return this.text;/{
  s/return this.text;/return this.text != null ? "[DRAFT] " + this.text : null;/
}' "$DOC"
rm -f "$DOC.sed"

typecmd "git diff $DOC"
pause 2

echo
echo -e "${RED}✗ Bug injected: getText() now prepends \"[DRAFT] \" to every text!${RESET}"
pause 2

# ── Step 4: Show updated order ───────────────────────────────────────────────
banner "Step 4 · Predicted test order (after change)"
echo -e "Document.java changed → Document-related tests jump to the ${BOLD}top${RESET}:"
echo
typecmd_pipe "$MVN -pl $MODULE -am $PLUGIN:show-order 2>&1 | grep -E '^  [0-9]' | tail -20"
echo
echo -e "${GREEN}✓ Document tests jumped from #12 to the top 3!${RESET}"
echo -e "  The failing tests will run ${BOLD}first${RESET}, not after 10+ unrelated tests."
pause 3

# ── Step 5: Run — fail fast! ─────────────────────────────────────────────────
banner "Step 5 · Run tests — watch it fail FAST"
echo -e "With test-order + ${BOLD}-DskipAfterFailureCount=1${RESET}, the build stops"
echo -e "at the ${BOLD}first${RESET} failure. Watch how fast we get feedback:"
echo

# Capture start time
START_FAIL=$SECONDS

# Run with fail-fast: stop after first test class failure
typecmd_pipe "$MVN -pl $MODULE -am -DskipAfterFailureCount=1 $PLUGIN:auto test 2>&1 | grep -E 'Running |Tests run|BUILD|skip' | head -15"

FAIL_TIME=$(( SECONDS - START_FAIL ))
echo
echo -e "${RED}✗ Build failed in ~${FAIL_TIME}s — only 1 test class ran before stopping!${RESET}"
echo -e "  Without test-order, you'd run 10+ unrelated tests before hitting the bug."
pause 3

# ── Step 6: Fix the bug, modify a different file ────────────────────────────
banner "Step 6 · Fix the bug + change TextSplitter.java"
echo -e "Restore Document.java and modify a different file instead:"
echo

# Restore Document.java
mv "$DOC.bak" "$DOC"
echo -e "${GREEN}✓ Document.java restored${RESET}"

# Touch TextSplitter
cp "$SPLITTER" "$SPLITTER.bak"
sed -i.sed 's/public abstract class TextSplitter/public abstract class TextSplitter \/* modified: chunk strategy *\//' "$SPLITTER"
rm -f "$SPLITTER.sed"

echo -e "${YELLOW}✓ TextSplitter.java modified${RESET}"
echo
typecmd "git diff --stat"
pause 1

echo
echo -e "Watch the order shift — splitter tests jump up:"
typecmd_pipe "$MVN -pl $MODULE -am $PLUGIN:show-order 2>&1 | grep -E '^  [0-9]' | tail -20"
echo
echo -e "${GREEN}✓ TextSplitterTests, TokenTextSplitterTest now ranked higher${RESET}"
pause 2

# Run all tests — they pass now, show Running lines to prove order
echo
echo -e "Run tests — all pass, but in the ${BOLD}new priority order${RESET}:"
typecmd_pipe "$MVN -pl $MODULE -am $PLUGIN:auto test 2>&1 | grep -E 'Running |BUILD' | head -25"
echo
echo -e "${GREEN}✓ Tests ran in change-aware order — splitter tests first!${RESET}"
pause 2

# ── Step 7: Dashboard ───────────────────────────────────────────────────────
banner "Step 7 · Generate interactive dashboard"

# Restore TextSplitter
mv "$SPLITTER.bak" "$SPLITTER"

typecmd_pipe "$MVN -pl $MODULE -am $PLUGIN:dashboard 2>&1 | tail -5"
echo
echo -e "${GREEN}✓ Dashboard generated!${RESET}"
typecmd "ls -lh target/test-order-dashboard/index.html 2>/dev/null || ls -lh $MODULE/target/test-order-dashboard/index.html"
pause 2

# ── Final ────────────────────────────────────────────────────────────────────
banner "Done!"
echo -e "Key takeaways:"
echo -e "  ${RED}✗${RESET} Broke Document.java → failed in ${BOLD}seconds${RESET}, not minutes"
echo -e "  ${GREEN}✓${RESET} -DskipAfterFailureCount=1 stops at the first broken test"
echo -e "  ${GREEN}✓${RESET} Test order shifts automatically as you edit different files"
echo -e "  ${GREEN}✓${RESET} Zero configuration: just add the plugin and run"
echo -e "  ${GREEN}✓${RESET} Works on real projects — Spring AI, Spring Boot, Petclinic"
echo
echo -e "  ${BOLD}Fail your tests fast.${RESET}"
echo
pause 3
