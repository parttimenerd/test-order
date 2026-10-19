#!/usr/bin/env bash
# prepare-demo.sh — Pre-flight setup for the "You Are Running the Wrong Tests First" talk.
#
# Run this once, ~10 minutes before going on stage. It:
#   1. Checks JDK and Maven are on PATH
#   2. Verifies commons-collections checkout, reverts any leftover patch
#   3. Removes live commons-collections index (D0 needs no index)
#   4. Verifies/builds commons-collections index snapshot (needed by copy-in-db.sh for D1)
#   5. Verifies spring-petclinic checkout and pom.xml has test-order
#   6. Verifies/builds petclinic index snapshot (needed by copy-in-db.sh for D2/D3 dashboard)
#   7. Copies make-change-commons.sh + copy-in-db.sh into each project dir
#   8. Downloads meta-agent.jar to spring-petclinic/.tools/ (for D4 — live bytecode diff)
#   9. Prints a ready summary with exact demo commands
#
# Usage:
#   ./prepare-demo.sh                    # normal pre-show prep
#   ./prepare-demo.sh --rebuild-indexes  # force-rebuild both index snapshots (~8 min)
#   ./prepare-demo.sh --check-only       # dry-run: just validate, don't change anything

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
COLLECTIONS="$REPO_ROOT/third-party/commons-collections"
PETCLINIC="$REPO_ROOT/third-party/spring-petclinic"
COLLECTIONS_SNAPSHOT="$SCRIPT_DIR/commons-collections-index-snapshot"
PETCLINIC_SNAPSHOT="$SCRIPT_DIR/petclinic-index-snapshot"
PATCH="$REPO_ROOT/scripts/bugs/commons-collections/real-enumerationiterator-remove-no-reset.patch"

BOLD='\033[1m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'; RED='\033[0;31m'; CYAN='\033[0;36m'; NC='\033[0m'
say()    { printf "${BOLD}▶ %s${NC}\n" "$*"; }
ok()     { printf "  ${GREEN}✓${NC}  %s\n" "$*"; }
warn()   { printf "  ${YELLOW}⚠${NC}  %s\n" "$*"; }
fail()   { printf "  ${RED}✗${NC}  %s\n" "$*"; FAILURES=$((FAILURES+1)); }
section(){ printf "\n${CYAN}${BOLD}── %s ──${NC}\n" "$*"; }

rebuild_indexes=false
check_only=false
FAILURES=0

for arg in "$@"; do
  case "$arg" in
    --rebuild-indexes) rebuild_indexes=true ;;
    --check-only)      check_only=true ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
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

[ $FAILURES -gt 0 ] && { printf "\n${RED}${BOLD}%d prerequisite(s) failed.${NC}\n" "$FAILURES"; exit 1; }

# ── 2. commons-collections: D0 baseline setup ────────────────────────────────

section "D0/D1 — commons-collections"

if [ ! -d "$COLLECTIONS" ]; then
  fail "third-party/commons-collections not found"
  fail "Clone: git clone --depth=1 https://github.com/apache/commons-collections.git $COLLECTIONS"
else
  ok "commons-collections checkout present"

  # Revert any leftover patch
  if ! git -C "$COLLECTIONS" diff --quiet HEAD; then
    if $check_only; then
      warn "commons-collections has uncommitted changes — would revert on real run"
    else
      git -C "$COLLECTIONS" checkout src/
      ok "commons-collections patch reverted"
    fi
  else
    ok "commons-collections source clean"
  fi

  # Remove live index (D0 must run alphabetically)
  if [ -d "$COLLECTIONS/.test-order" ]; then
    if $check_only; then
      warn "commons-collections .test-order exists — would remove (D0 needs no index)"
    else
      rm -rf "$COLLECTIONS/.test-order"
      ok "commons-collections .test-order removed — D0 will run alphabetically"
    fi
  else
    ok "commons-collections .test-order absent — D0 will run alphabetically"
  fi

  # Copy demo scripts into commons-collections
  for script in make-change-commons.sh copy-in-db.sh setup-test-order.sh; do
    src="$SCRIPT_DIR/$script"
    dst="$COLLECTIONS/$script"
    if [ -f "$src" ]; then
      if $check_only; then ok "would copy $script → third-party/commons-collections/"
      else cp "$src" "$dst"; chmod +x "$dst"; ok "copied $script → commons-collections/"; fi
    else
      fail "$src not found"
    fi
  done

  # D0 needs NO test-order — remove .mvn/extensions.xml if it has the plugin
  # (setup-test-order.sh adds it live on stage between D0 and D2)
  EXT_COLL="$COLLECTIONS/.mvn/extensions.xml"
  if grep -q "test-order-maven-plugin" "$EXT_COLL" 2>/dev/null; then
    if $check_only; then
      warn "commons-collections .mvn/extensions.xml has test-order — would remove (D0 needs no plugin)"
    else
      python3 - "$EXT_COLL" <<'PYEOF'
import sys, xml.etree.ElementTree as ET
path = sys.argv[1]
tree = ET.parse(path)
root = tree.getroot()
to_remove = [e for e in root.findall('extension') if e.findtext('artifactId') == 'test-order-maven-plugin']
for e in to_remove: root.remove(e)
if not root.findall('extension'):
    import os; os.remove(path)
else:
    tree.write(path, xml_declaration=True, encoding='UTF-8')
PYEOF
      ok "commons-collections .mvn/extensions.xml: test-order removed (D0 will run without plugin)"
    fi
  elif [ ! -f "$EXT_COLL" ]; then
    ok "commons-collections .mvn/extensions.xml absent — D0 will run without plugin"
  else
    ok "commons-collections .mvn/extensions.xml has no test-order — D0 ready"
  fi

  # Remove stale junit-platform.properties generated by a previous learn run.
  # Without this, D0's plain 'mvn test' logs a ClassNotFoundException for PriorityClassOrderer.
  JUNIT_PROPS="$COLLECTIONS/target/test-classes/junit-platform.properties"
  if [ -f "$JUNIT_PROPS" ]; then
    if $check_only; then
      warn "stale $JUNIT_PROPS exists — would remove (D0 needs no test-order classpath)"
    else
      rm -f "$JUNIT_PROPS"
      ok "removed stale junit-platform.properties from target/test-classes"
    fi
  else
    ok "no stale junit-platform.properties — D0 clean"
  fi

  # Verify patch file
  if [ -f "$PATCH" ]; then
    ok "EnumerationIterator patch present"
  else
    fail "patch not found at $PATCH"
  fi

  # Build commons-collections index snapshot (needed for setup-test-order.sh in D2)
  if $rebuild_indexes || [ ! -d "$COLLECTIONS_SNAPSHOT" ]; then
    if $check_only; then
      warn "commons-collections snapshot missing — run without --check-only to build (~2 min)"
    else
      say "Building commons-collections index snapshot (~2 min)…"
      # Temporarily inject extension for the learn run, then remove it again
      mkdir -p "$COLLECTIONS/.mvn"
      cat > "$EXT_COLL" <<'XMLEOF'
<?xml version="1.0" encoding="UTF-8"?>
<extensions>
  <extension>
    <groupId>me.bechberger</groupId>
    <artifactId>test-order-maven-plugin</artifactId>
    <version>0.1.1</version>
  </extension>
</extensions>
XMLEOF
      (cd "$COLLECTIONS" && mvn clean test -q 2>&1 | tail -5) && ok "learn run complete"
      rm -f "$EXT_COLL"
      rmdir "$COLLECTIONS/.mvn" 2>/dev/null || true
      rm -rf "$COLLECTIONS_SNAPSHOT"
      cp -r "$COLLECTIONS/.test-order" "$COLLECTIONS_SNAPSHOT"
      ok "snapshot saved → $COLLECTIONS_SNAPSHOT"
      rm -rf "$COLLECTIONS/.test-order"
      ok "live index removed — D0 will run without plugin"
    fi
  else
    SNAP_FILES=$(find "$COLLECTIONS_SNAPSHOT" -type f | wc -l | tr -d ' ')
    ok "commons-collections snapshot present ($SNAP_FILES files) — setup-test-order.sh ready"
  fi
fi

# ── 3. spring-petclinic: D2/D3 setup ─────────────────────────────────────────

section "D2/D3 — spring-petclinic"

if [ ! -d "$PETCLINIC" ]; then
  fail "third-party/spring-petclinic not found"
  fail "Clone: git clone --depth=1 https://github.com/spring-projects/spring-petclinic.git $PETCLINIC"
else
  ok "spring-petclinic checkout present"

  # Copy demo scripts
  for script in copy-in-db.sh petclinic-make-change.sh; do
    src="$SCRIPT_DIR/$script"
    dst="$PETCLINIC/$script"
    if [ -f "$src" ]; then
      if $check_only; then ok "would copy $script → third-party/spring-petclinic/"
      else cp "$src" "$dst"; chmod +x "$dst"; ok "copied $script → spring-petclinic/"; fi
    else
      fail "$src not found"
    fi
  done

  # Verify VisitController is clean
  VISIT="$PETCLINIC/src/main/java/org/springframework/samples/petclinic/owner/VisitController.java"
  if [ -f "$VISIT.bak" ]; then
    if $check_only; then warn "VisitController.java.bak exists — would restore"
    else mv "$VISIT.bak" "$VISIT"; ok "VisitController.java restored from backup"; fi
  else
    ok "VisitController.java clean"
  fi

  # Ensure .mvn/extensions.xml has test-order (plugin is wired via extension, not pom)
  EXT_PETCLINIC="$PETCLINIC/.mvn/extensions.xml"
  if grep -q "test-order-maven-plugin" "$EXT_PETCLINIC" 2>/dev/null; then
    ok "spring-petclinic .mvn/extensions.xml has test-order"
  elif grep -q "test-order-maven-plugin" "$PETCLINIC/pom.xml" 2>/dev/null; then
    ok "spring-petclinic pom.xml has test-order"
  else
    if $check_only; then
      warn "spring-petclinic has no test-order — would inject .mvn/extensions.xml"
    else
      mkdir -p "$PETCLINIC/.mvn"
      cat > "$EXT_PETCLINIC" <<'XMLEOF'
<?xml version="1.0" encoding="UTF-8"?>
<extensions>
  <extension>
    <groupId>me.bechberger</groupId>
    <artifactId>test-order-maven-plugin</artifactId>
    <version>0.1.1</version>
  </extension>
</extensions>
XMLEOF
      ok "created spring-petclinic .mvn/extensions.xml with test-order 0.1.1"
    fi
  fi

  # Build petclinic index snapshot
  if $rebuild_indexes || [ ! -d "$PETCLINIC_SNAPSHOT" ]; then
    if $check_only; then
      warn "petclinic snapshot missing — run without --check-only to build (~3 min)"
    else
      say "Building petclinic index snapshot (~3 min)…"
      # Run twice so dashboard has run history for D3
      (cd "$PETCLINIC" && mvn clean test -q 2>&1 | tail -5) && ok "petclinic learn run 1 complete"
      (cd "$PETCLINIC" && mvn clean test -q 2>&1 | tail -5) && ok "petclinic order run 2 complete"
      rm -rf "$PETCLINIC_SNAPSHOT"
      cp -r "$PETCLINIC/.test-order" "$PETCLINIC_SNAPSHOT"
      ok "snapshot saved → $PETCLINIC_SNAPSHOT"
    fi
  else
    SNAP_FILES=$(find "$PETCLINIC_SNAPSHOT" -type f | wc -l | tr -d ' ')
    ok "petclinic snapshot present ($SNAP_FILES files) — dashboard will have history"
  fi
fi

# ── 4. D4 — meta-agent setup ─────────────────────────────────────────────────

section "D4 — meta-agent (live bytecode diff)"

META_AGENT_JAR="$PETCLINIC/.tools/meta-agent.jar"
META_AGENT_URL="https://github.com/parttimenerd/meta-agent/releases/download/0.0.10/meta-agent.jar"

if [ -f "$META_AGENT_JAR" ]; then
  ok "meta-agent.jar present at $META_AGENT_JAR"
else
  if $check_only; then
    warn "meta-agent.jar missing — would download from GitHub releases"
  else
    say "Downloading meta-agent.jar (~5 MB)…"
    mkdir -p "$PETCLINIC/.tools"
    curl -fsSL "$META_AGENT_URL" -o "$META_AGENT_JAR"
    ok "meta-agent.jar saved to $META_AGENT_JAR"
  fi
fi

if [ -f "$PETCLINIC/learn-with-meta-agent.sh" ]; then
  ok "learn-with-meta-agent.sh present in spring-petclinic"
else
  if $check_only; then
    warn "learn-with-meta-agent.sh missing from spring-petclinic — D4 will not work"
  else
    fail "learn-with-meta-agent.sh not found at $PETCLINIC/learn-with-meta-agent.sh"
  fi
fi

# ── 5. Ready summary ──────────────────────────────────────────────────────────

if [ $FAILURES -gt 0 ]; then
  printf "\n${RED}${BOLD}%d check(s) failed — fix before going on stage.${NC}\n\n" "$FAILURES"
  exit 1
fi

printf "\n${GREEN}${BOLD}Demo ready.${NC} Open 5 terminal tabs:\n\n"

printf "  ${CYAN}D0${NC} (alphabetical baseline, ~5 min to failure at test #237):\n"
printf "    cd %s\n" "$COLLECTIONS"
printf "    ./make-change-commons.sh   # apply the patch\n"
printf "    mvn test   # wait for EnumerationIteratorTest at #237\n\n"

printf "  ${CYAN}D1${NC} (petclinic — cold learn run, live edit, VisitControllerTests → #1):\n"
printf "    cd %s\n" "$PETCLINIC"
printf "    mvn test                    # learn run — first time\n"
printf "    ./petclinic-make-change.sh\n"
printf "    mvn test-order:show\n"
printf "    mvn test   # Ctrl+C after first red (~2 seconds)\n\n"

printf "  ${CYAN}D2${NC} (commons-collections — test-order active, same bug → test #1, Ctrl+C after red):\n"
printf "    cd %s\n" "$COLLECTIONS"
printf "    ./setup-test-order.sh   # adds .mvn/extensions.xml + restores index\n"
printf "    mvn test-order:show\n"
printf "    mvn test   # Ctrl+C after first red (~10 seconds)\n\n"

printf "  ${CYAN}D2.5${NC} (petclinic — @AlwaysRun annotation live, Feature Catalogue section):\n"
printf "    cd %s\n" "$PETCLINIC"
printf "    mvn test-order:show             # before: see current ranking\n"
printf "    # add @AlwaysRun to VisitControllerTests.java (one line)\n"
printf "    mvn test-order:show             # after: VisitControllerTests → #1\n\n"

printf "  ${CYAN}D2.6${NC} (petclinic — TDD enforcement, Feature Catalogue section — optional):\n"
printf "    cd %s\n" "$PETCLINIC"
printf "    mvn test -Dtestorder.tdd=true   # watch for TDD VIOLATION\n\n"

printf "  ${CYAN}D3${NC} (petclinic dashboard):\n"
printf "    cd %s\n" "$PETCLINIC"
printf "    mvn test-order:dashboard && open target/test-order-dashboard/index.html\n\n"

printf "  ${CYAN}D4${NC} (meta-agent — live bytecode diff, Act 5 Under the Hood):\n"
printf "    cd %s\n" "$PETCLINIC"
printf "    ./learn-with-meta-agent.sh --meta-agent\n"
printf "    # browser opens → http://localhost:7071/classes\n"
printf "    # click any class to see before/after bytecode diff\n"
printf "    # click Stop in the nav bar when done\n\n"

printf "  ${CYAN}Reset between runs:${NC}\n"
printf "    cd %s && ./reset-demo.sh\n\n" "$SCRIPT_DIR"
