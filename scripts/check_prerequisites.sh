#!/usr/bin/env bash
# Quick environment check for test-order prerequisites.
# Usage: bash scripts/check_prerequisites.sh

set -uo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m' # No Color

ok=0
warn=0
fail=0

echo "=== test-order prerequisite check ==="
echo ""

# Java
if command -v java &>/dev/null; then
  java_ver=$(java -version 2>&1 | head -1)
  java_major=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')
  if [ "$java_major" -ge 17 ] 2>/dev/null; then
    printf "${GREEN}✓ %-12s %s${NC}\n" "Java" "$java_ver"
    ok=$((ok + 1))
  else
    printf "${YELLOW}⚠ %-12s %s (17+ required)${NC}\n" "Java" "$java_ver"
    warn=$((warn + 1))
  fi
else
  printf "${RED}✗ %-12s not found (17+ required)${NC}\n" "Java"
  fail=$((fail + 1))
fi

# Maven
if command -v mvn &>/dev/null; then
  mvn_ver=$(mvn --version 2>/dev/null | grep "^Apache Maven" | head -1)
  [ -z "$mvn_ver" ] && mvn_ver=$(mvn --version 2>&1 | head -1)
  printf "${GREEN}✓ %-12s %s${NC}\n" "Maven" "$mvn_ver"
  ok=$((ok + 1))
else
  printf "${YELLOW}⚠ %-12s not found (needed for Maven projects)${NC}\n" "Maven"
  warn=$((warn + 1))
fi

# Gradle
if command -v gradle &>/dev/null || [ -f ./gradlew ]; then
  if [ -f ./gradlew ]; then
    gradle_ver=$(./gradlew --version 2>&1 | grep "^Gradle" | head -1)
    printf "${GREEN}✓ %-12s %s (wrapper)${NC}\n" "Gradle" "$gradle_ver"
  else
    gradle_ver=$(gradle --version 2>&1 | grep "^Gradle" | head -1)
    printf "${GREEN}✓ %-12s %s${NC}\n" "Gradle" "$gradle_ver"
  fi
  ok=$((ok + 1))
else
  printf "${YELLOW}⚠ %-12s not found (needed for Gradle projects)${NC}\n" "Gradle"
  warn=$((warn + 1))
fi

# Git
if command -v git &>/dev/null; then
  git_ver=$(git --version)
  printf "${GREEN}✓ %-12s %s${NC}\n" "Git" "$git_ver"
  ok=$((ok + 1))
  # Check if we're in a git repo
  if git rev-parse --is-inside-work-tree &>/dev/null; then
    printf "${GREEN}✓ %-12s yes${NC}\n" "Git repo"
    ok=$((ok + 1))
  else
    printf "${YELLOW}⚠ %-12s not in a git repository (test-order needs git for change detection)${NC}\n" "Git repo"
    warn=$((warn + 1))
  fi
else
  printf "${RED}✗ %-12s not found${NC}\n" "Git"
  fail=$((fail + 1))
fi

echo ""
echo "---"
printf "Results: ${GREEN}%d passed${NC}" "$ok"
[ "$warn" -gt 0 ] && printf ", ${YELLOW}%d warnings${NC}" "$warn"
[ "$fail" -gt 0 ] && printf ", ${RED}%d failed${NC}" "$fail"
echo ""

if [ "$fail" -gt 0 ]; then
  echo ""
  echo "Fix the failed checks above before using test-order."
  exit 1
elif [ "$warn" -gt 0 ]; then
  echo ""
  echo "Warnings won't block usage but may limit functionality."
fi
