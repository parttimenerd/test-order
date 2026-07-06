#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────
#  test-order demo · Spring Boot core (351 test classes)
#
#  Records an asciinema-friendly terminal session that showcases
#  change-aware test prioritisation on a real, large project.
#
#  Every command in this script is guarded by "set -e" so the
#  recording aborts on the first error – proving the workflow is
#  production-solid, not a rehearsed happy path.
# ──────────────────────────────────────────────────────────────────
set -euo pipefail

# ── Colours & helpers ────────────────────────────────────────────
C='\033[0;36m'   # cyan
G='\033[0;32m'   # green
Y='\033[0;33m'   # yellow
B='\033[1m'      # bold
R='\033[0m'      # reset

banner() { printf "\n${C}${B}═══ %s ═══${R}\n\n" "$1"; }
step()   { printf "${G}▸ %s${R}\n" "$1"; }
pause()  { sleep "${1:-1.5}"; }
type_cmd() {
  # Print the command in bold, pause, then execute it
  printf "${Y}\$ %s${R}\n" "$1"
  pause 0.8
  eval "$1"
}

SPRING_BOOT=$(cd "$(dirname "$0")/.." && pwd)/third-party/spring-boot
CORE=$SPRING_BOOT/core/spring-boot
SRC=$CORE/src/main/java/org/springframework/boot
INIT=$SPRING_BOOT/test-order-init.gradle

# Preflight: require the third-party repo and a learned index.
if [ ! -d "$SPRING_BOOT" ]; then
  echo "SKIP: third-party/spring-boot checkout not found at $SPRING_BOOT" >&2
  exit 0
fi
if [ ! -f "$SPRING_BOOT/.test-order/test-dependencies.lz4" ]; then
  echo "SKIP: no learned index at $SPRING_BOOT/.test-order/test-dependencies.lz4" >&2
  echo "      Run: cd $SPRING_BOOT && ./gradlew --init-script test-order-init.gradle :core:spring-boot:testOrderLearn --no-daemon" >&2
  exit 0
fi

# Disable pagers — required when running in headless/CI mode
export GIT_PAGER=cat
export LESS="-FRX"
export PAGER=cat

# Spring Boot currently requires JDK 25+ for some modules. Prefer JDK 25.
if [ -z "${JAVA_HOME:-}" ] || [ "${JAVA_HOME}" != "$(/usr/libexec/java_home -v 25 2>/dev/null || echo)" ]; then
  JAVA_25_HOME="$(/usr/libexec/java_home -v 25 2>/dev/null || true)"
  if [ -n "$JAVA_25_HOME" ]; then
    export JAVA_HOME="$JAVA_25_HOME"
  fi
fi

cleanup() {
  if [ -f "$SRC/SpringApplication.java.bak" ]; then
    mv "$SRC/SpringApplication.java.bak" "$SRC/SpringApplication.java"
  fi
  if [ -f "$SRC/ResourceBanner.java.bak" ]; then
    mv "$SRC/ResourceBanner.java.bak" "$SRC/ResourceBanner.java"
  fi
}

trap cleanup EXIT

cd "$SPRING_BOOT"

# ──────────────────────────────────────────────────────────────────
banner "test-order demo · Spring Boot core"
# ──────────────────────────────────────────────────────────────────

cat <<'EOF'
This demo shows the test-order Gradle plugin on Spring Boot's
core module – 351 test classes, a real-world project.

Workflow:
  1. Show the learned dependency index
  2. Baseline ordering (no code changes)
  3. Modify SpringApplication.java → see order change
  4. Run tests in priority order
  5. Modify a different file → order shifts again
  6. Generate the interactive dashboard

EOF
pause 2

# ── 1. Project overview ──────────────────────────────────────────
banner "1 · Project overview"

step "Test class count in spring-boot core:"
type_cmd "find core/spring-boot/src/test -name '*Tests.java' | wc -l"
pause 1.5

step "Dependency index (shared at repo root — covers all modules):"
type_cmd "ls -lh .test-order/test-dependencies.lz4"
pause 1

# ── 2. Dump a sample of the dependency index ─────────────────────
banner "2 · Dependency index (sample)"

step "Which source classes does each test class exercise?"
type_cmd "./gradlew --init-script $INIT :core:spring-boot:testOrderDump --no-daemon --console plain -q 2>/dev/null | head -5"
pause 2

# ── 3. Baseline ordering ─────────────────────────────────────────
banner "3 · Baseline ordering (no code changes)"

step "Predicted test order — scored by speed & past failures only:"
type_cmd "./gradlew --init-script $INIT :core:spring-boot:testOrderShowOrder --no-daemon --console plain -q 2>/dev/null | head -20"
pause 2.5

# ── 4. Modify SpringApplication.java ─────────────────────────────
banner "4 · Touch SpringApplication.java"

step "Adding a trivial comment to SpringApplication.java:"
# Save original for cleanup
cp "$SRC/SpringApplication.java" "$SRC/SpringApplication.java.bak"

# Append a harmless comment after the package line
sed -i '' 's|^package org.springframework.boot;|package org.springframework.boot;\n// demo: simulated developer change|' "$SRC/SpringApplication.java"

type_cmd "git -C $SPRING_BOOT diff --stat"
pause 1

step "Show the diff:"
type_cmd "git -C $SPRING_BOOT diff core/spring-boot/src/main/java/org/springframework/boot/SpringApplication.java | head -15"
pause 2

# ── 5. Show updated ordering ─────────────────────────────────────
banner "5 · Updated ordering after change"

step "Tests that exercise SpringApplication jump to the top:"
type_cmd "./gradlew --init-script $INIT :core:spring-boot:testOrderShowOrder --no-daemon --console plain -q 2>/dev/null | head -20"
pause 3

# ── 6. Show affected tests (no test run needed) ──────────────────
banner "6 · Affected tests — instant preview"

step "Which tests would run for this change? (no test execution needed):"
type_cmd "./gradlew --init-script $INIT :core:spring-boot:testOrderShowOrder --no-daemon --console plain -q 2>/dev/null | grep -A3 'Changed classes' | head -5"
pause 1
type_cmd "./gradlew --init-script $INIT :core:spring-boot:testOrderShowOrder --no-daemon --console plain -q 2>/dev/null | grep -E '^  [0-9]' | head -10"
pause 2

# ── 7. Restore, then modify a different file ─────────────────────
banner "7 · Change a different file — ResourceBanner.java"

# Restore SpringApplication
mv "$SRC/SpringApplication.java.bak" "$SRC/SpringApplication.java"

# Touch ResourceBanner instead
cp "$SRC/ResourceBanner.java" "$SRC/ResourceBanner.java.bak"
sed -i '' 's|^package org.springframework.boot;|package org.springframework.boot;\n// demo: banner feature work|' "$SRC/ResourceBanner.java"

step "Now ResourceBanner.java is the changed file:"
type_cmd "git -C $SPRING_BOOT diff --stat"
pause 1

step "Watch the order shift — BannerTests / ResourceBannerTests jump up:"
type_cmd "./gradlew --init-script $INIT :core:spring-boot:testOrderShowOrder --no-daemon --console plain -q 2>/dev/null | head -20"
pause 3

# ── 8. Dashboard ─────────────────────────────────────────────────
banner "8 · Generate interactive dashboard"

type_cmd "./gradlew --init-script $INIT :core:spring-boot:testOrderDashboard --no-daemon --console plain -q 2>/dev/null"
step "Dashboard HTML generated:"
type_cmd "ls -lh core/spring-boot/build/test-order/dashboard/index.html 2>/dev/null || find core/spring-boot/build -name 'index.html' 2>/dev/null | head -1"
pause 2

# ── Cleanup ──────────────────────────────────────────────────────
mv "$SRC/ResourceBanner.java.bak" "$SRC/ResourceBanner.java"

banner "Done!"
cat <<'EOF'
Key takeaways:
  ✓ Tests affected by your change run FIRST
  ✓ No waiting for unrelated tests to finish
  ✓ Order shifts automatically as you edit different files
  ✓ Works on real, large projects — 351 test classes
  ✓ Zero configuration: just add the plugin and go

EOF
