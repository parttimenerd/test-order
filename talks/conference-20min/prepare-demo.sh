#!/usr/bin/env bash
# prepare-demo.sh — Pre-flight setup for the "You Are Running the Wrong Tests First" talk.
#
# Run this once, ~5 minutes before going on stage. It:
#   1. Checks JDK and Maven are on PATH
#   2. Resets sample-shop to a clean state (no index) — D1/D2 require this
#   3. Verifies spring-petclinic index exists — D0 and D3 require it
#   4. Optionally re-learns the petclinic index from scratch (--learn-petclinic)
#   5. Prints a ready summary with the exact commands for each demo
#
# Usage:
#   ./prepare-demo.sh                   # normal pre-show prep
#   ./prepare-demo.sh --learn-petclinic # force re-run learn pass (~4 min)
#   ./prepare-demo.sh --check-only      # dry-run: just validate, don't change anything
#
# Demos in the talk:
#   D0  spring-petclinic, NO test-order — plain mvn test, tests run A-Z, VisitControllerTests last
#   D1  sample-shop — learn run then order run, auto-detection
#   D2  sample-shop — edit Cart.java, order shifts (builds on D1 index)
#   D3  spring-petclinic, WITH test-order — live APFD, then mvn test-order:serve

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Use git to find the main worktree root so this works from a git worktree as well
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --path-format=absolute --show-toplevel 2>/dev/null || cd "$SCRIPT_DIR/../.." && pwd)"
# If we're in a worktree, find the main working tree
MAIN_WORKTREE="$(git -C "$SCRIPT_DIR" worktree list --porcelain 2>/dev/null | awk 'NR==1{print $2}')"
if [ -n "$MAIN_WORKTREE" ] && [ "$MAIN_WORKTREE" != "$REPO_ROOT" ]; then
  REPO_ROOT="$MAIN_WORKTREE"
fi
SHOP="$REPO_ROOT/samples/sample-shop"
PETCLINIC="$REPO_ROOT/third-party/spring-petclinic"

BOLD='\033[1m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'; RED='\033[0;31m'; CYAN='\033[0;36m'; NC='\033[0m'

say()    { printf "${BOLD}▶ %s${NC}\n" "$*"; }
ok()     { printf "  ${GREEN}✓${NC}  %s\n" "$*"; }
warn()   { printf "  ${YELLOW}⚠${NC}  %s\n" "$*"; }
fail()   { printf "  ${RED}✗${NC}  %s\n" "$*"; FAILURES=$((FAILURES+1)); }
section(){ printf "\n${CYAN}${BOLD}── %s ──${NC}\n" "$*"; }

learn_petclinic=false
check_only=false
FAILURES=0

for arg in "$@"; do
  case "$arg" in
    --learn-petclinic) learn_petclinic=true ;;
    --check-only)      check_only=true ;;
    -h|--help)
      sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "Unknown arg: $arg" >&2; exit 2 ;;
  esac
done

# ── 1. Prerequisites ──────────────────────────────────────────────────────────

section "Prerequisites"

if command -v java >/dev/null 2>&1; then
  JAVA_VER=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)[._].*/\1/;s/.*"([0-9]+)".*/\1/')
  if [ "${JAVA_VER:-0}" -ge 17 ]; then
    ok "Java $JAVA_VER on PATH"
  else
    fail "Java $JAVA_VER found — need 17+. Set JAVA_HOME and retry."
  fi
else
  fail "java not found on PATH"
fi

if command -v mvn >/dev/null 2>&1; then
  MVN_VER=$(mvn -v 2>/dev/null | head -1 | awk '{print $3}')
  ok "Maven $MVN_VER on PATH"
else
  fail "mvn not found on PATH"
fi

if [ $FAILURES -gt 0 ]; then
  printf "\n${RED}${BOLD}%d prerequisite(s) failed — fix before going on stage.${NC}\n" "$FAILURES"
  exit 1
fi

# ── 2. sample-shop: reset to clean state ──────────────────────────────────────

section "D1/D2 — sample-shop (reset)"

if [ ! -d "$SHOP" ]; then
  fail "samples/sample-shop not found at $SHOP"
  exit 1
fi

# Restore Cart.java if a demo edit was left behind
CART="$SHOP/src/main/java/com/example/shop/Cart.java"
if [ -f "$CART.bak" ]; then
  if $check_only; then
    warn "Cart.java.bak exists — would restore on real run"
  else
    mv "$CART.bak" "$CART"
    ok "Cart.java restored from backup"
  fi
else
  ok "Cart.java clean (no backup present)"
fi

# Remove the index so D1 starts fresh (learn run)
SHOP_INDEX="$SHOP/.test-order"
if [ -d "$SHOP_INDEX" ]; then
  if $check_only; then
    warn "sample-shop index exists — would delete on real run (D1 needs a fresh learn pass)"
  else
    rm -rf "$SHOP_INDEX"
    ok "sample-shop index removed — D1 will run learn pass"
  fi
else
  ok "sample-shop index absent — D1 will run learn pass"
fi

# Wipe compiled classes so the learn run rebuilds cleanly
if ! $check_only; then
  (cd "$SHOP" && mvn clean -q 2>/dev/null) && ok "sample-shop cleaned (target/ wiped)"
fi

# ── 3. spring-petclinic: verify index ────────────────────────────────────────

section "D0/D3 — spring-petclinic"

if [ ! -d "$PETCLINIC" ]; then
  fail "third-party/spring-petclinic not found at $PETCLINIC"
else
  ok "spring-petclinic checkout present"
fi

PETCLINIC_INDEX="$PETCLINIC/.test-order/test-dependencies.lz4"

if $learn_petclinic; then
  say "Re-learning petclinic index (--learn-petclinic, ~4 min)…"
  if ! $check_only; then
    rm -rf "$PETCLINIC/.test-order"
    (cd "$PETCLINIC" && mvn test -q) && ok "petclinic learn run complete — index written"
  fi
elif [ -f "$PETCLINIC_INDEX" ]; then
  # stat -f %m is BSD/macOS; stat -c %Y is GNU/Linux
  MOD_TIME=$(stat -f %m "$PETCLINIC_INDEX" 2>/dev/null || stat -c %Y "$PETCLINIC_INDEX" 2>/dev/null || echo 0)
  AGE=$(( ($(date +%s) - MOD_TIME) / 3600 ))
  ok "petclinic index present (${AGE}h old) — D3 will run order pass"
  if [ "$AGE" -gt 168 ]; then
    warn "Index is >7 days old — consider re-running: cd $PETCLINIC && mvn test"
  fi
else
  warn "petclinic index missing — D3 will run a slow learn pass on stage (~4 min)"
  warn "To pre-build: cd $PETCLINIC && mvn test  (then re-run prepare-demo.sh)"
fi

# Verify VisitControllerTests exists for D0 narrative
if find "$PETCLINIC/src/test" -name "VisitControllerTests.java" 2>/dev/null | grep -q .; then
  ok "VisitControllerTests.java present — D0 alphabetical story works"
else
  warn "VisitControllerTests.java not found — check the D0 narrative"
fi

# ── 4. Ready summary ──────────────────────────────────────────────────────────

if [ $FAILURES -gt 0 ]; then
  printf "\n${RED}${BOLD}%d check(s) failed — fix before going on stage.${NC}\n\n" "$FAILURES"
  exit 1
fi

printf "\n${GREEN}${BOLD}Demo ready.${NC}\n\n"
printf "${BOLD}Open 4 terminal tabs, cd into each:${NC}\n\n"

printf "  ${CYAN}Tab 1 — D0${NC} (alphabetical baseline, NO test-order)\n"
printf "    cd %s\n" "$PETCLINIC"
printf "    mvn test -pl . -Dsurefire.failIfNoSpecifiedTests=false\n\n"

printf "  ${CYAN}Tab 2 — D1 + D2${NC} (learn run → order run → edit Cart.java → order shifts)\n"
printf "    cd %s\n" "$SHOP"
printf "    mvn test                                    # D1 learn run\n"
printf "    mvn test                                    # D1 order run\n"
printf "    mvn test-order:show                         # D1 scores + why\n"
printf "    # ── edit Cart.java: add null-check in add() ──\n"
printf "    mvn test                                    # D2 CartTest = #1\n"
printf "    mvn test-order:show                         # D2 score breakdown\n\n"

printf "  ${CYAN}Tab 3 — D3${NC} (real project, APFD live, then dashboard)\n"
printf "    cd %s\n" "$PETCLINIC"
printf "    mvn test                                    # order run (index loaded)\n"
printf "    mvn test-order:serve                        # open dashboard\n\n"

printf "  ${CYAN}Tab 4 — slides${NC} (fallback if live demo breaks)\n"
printf "    cd %s/talks/conference-20min\n" "$REPO_ROOT"
printf "    npx slidev slides.md\n\n"

printf "${BOLD}D2 edit:${NC}\n"
printf "  File: %s\n" "$CART"
printf "  Inside Cart.add(), add:\n"
printf "    if (item == null) throw new IllegalArgumentException();\n\n"
