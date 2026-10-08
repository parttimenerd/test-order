#!/usr/bin/env bash
# prepare-demo.sh — Pre-flight setup for the "You Are Running the Wrong Tests First" talk.
#
# Run this once, ~5 minutes before going on stage. It:
#   1. Checks JDK and Maven are on PATH
#   2. Verifies cloud-sdk-java checkout and enables test-order — D0 and D1 require it
#   3. Ensures DestinationRetrievalStrategyResolver.java is clean (no leftover bug)
#   4. Removes any live .test-order index (D0 needs no index)
#   5. Verifies the pre-trained index snapshot — D1 needs it; auto-builds if missing
#   6. Prints a ready summary with the exact commands for each demo
#
# Usage:
#   ./prepare-demo.sh                   # normal pre-show prep
#   ./prepare-demo.sh --learn-cloud-sdk # force re-run learn pass (~6 min, builds snapshot)
#   ./prepare-demo.sh --check-only      # dry-run: just validate, don't change anything
#
# Demos in the talk:
#   D0  cloud-sdk-java, NO test-order — plain mvn test, DestinationRetrievalStrategyResolverTest runs alphabetically
#   D1  cloud-sdk-java, WITH test-order — same bug, DestinationRetrievalStrategyResolverTest jumps to #1

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Use git to find the main worktree root so this works from a git worktree as well
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --path-format=absolute --show-toplevel 2>/dev/null || cd "$SCRIPT_DIR/../.." && pwd)"
# If we're in a worktree, find the main working tree
MAIN_WORKTREE="$(git -C "$SCRIPT_DIR" worktree list --porcelain 2>/dev/null | awk 'NR==1{print $2}')"
if [ -n "$MAIN_WORKTREE" ] && [ "$MAIN_WORKTREE" != "$REPO_ROOT" ]; then
  REPO_ROOT="$MAIN_WORKTREE"
fi
CLOUD_SDK="$REPO_ROOT/third-party/cloud-sdk-java"

BOLD='\033[1m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'; RED='\033[0;31m'; CYAN='\033[0;36m'; NC='\033[0m'

say()    { printf "${BOLD}▶ %s${NC}\n" "$*"; }
ok()     { printf "  ${GREEN}✓${NC}  %s\n" "$*"; }
warn()   { printf "  ${YELLOW}⚠${NC}  %s\n" "$*"; }
fail()   { printf "  ${RED}✗${NC}  %s\n" "$*"; FAILURES=$((FAILURES+1)); }
section(){ printf "\n${CYAN}${BOLD}── %s ──${NC}\n" "$*"; }

learn_cloud_sdk=false
check_only=false
FAILURES=0

for arg in "$@"; do
  case "$arg" in
    --learn-cloud-sdk) learn_cloud_sdk=true ;;
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

# ── 2. cloud-sdk-java: D0 and D1 setup ───────────────────────────────────────

section "D0/D1 — cloud-sdk-java"

if [ ! -d "$CLOUD_SDK" ]; then
  fail "third-party/cloud-sdk-java not found at $CLOUD_SDK"
  fail "Clone it: git clone --depth=1 https://github.com/SAP/cloud-sdk-java.git $CLOUD_SDK"
else
  ok "cloud-sdk-java checkout present"
fi

# Copy demo scripts into cloud-sdk-java so they run from that dir
for script in cloud-sdk-make-change.sh cloud-sdk-reset-change.sh cloud-sdk-copy-in-db.sh; do
  src="$SCRIPT_DIR/$script"
  dst_name="${script#cloud-sdk-}"   # strip "cloud-sdk-" prefix → make-change.sh
  dst="$CLOUD_SDK/$dst_name"
  if [ -f "$src" ]; then
    if $check_only; then
      ok "would copy $script → $dst"
    else
      cp "$src" "$dst"
      chmod +x "$dst"
      ok "copied $script → third-party/cloud-sdk-java/$dst_name"
    fi
  else
    fail "$src not found"
  fi
done

# Ensure .mvn/extensions.xml has test-order (for D1)
EXT="$CLOUD_SDK/.mvn/extensions.xml"
if [ ! -f "$EXT" ]; then
  if $check_only; then
    warn "$EXT missing — would create on real run"
  else
    mkdir -p "$CLOUD_SDK/.mvn"
    cat > "$EXT" <<'XMLEOF'
<?xml version="1.0" encoding="UTF-8"?>
<extensions>
  <extension>
    <groupId>me.bechberger</groupId>
    <artifactId>test-order-maven-plugin</artifactId>
    <version>0.1.0</version>
  </extension>
</extensions>
XMLEOF
    ok "created $EXT with test-order 0.1.0"
  fi
elif grep -q "test-order-maven-plugin" "$EXT"; then
  ok "cloud-sdk-java .mvn/extensions.xml has test-order"
else
  fail "$EXT exists but test-order is not in it — check manually"
fi

# Restore DestinationRetrievalStrategyResolver.java if a demo edit was left behind
DEMO_TARGET="$CLOUD_SDK/cloudplatform/connectivity-destination-service/src/main/java/com/sap/cloud/sdk/cloudplatform/connectivity/DestinationRetrievalStrategyResolver.java"
if [ -f "$DEMO_TARGET.bak" ]; then
  if $check_only; then
    warn "DestinationRetrievalStrategyResolver.java.bak exists — would restore on real run"
  else
    mv "$DEMO_TARGET.bak" "$DEMO_TARGET"
    ok "DestinationRetrievalStrategyResolver.java restored from backup"
  fi
elif grep -q '!Objects.equals(currentTenantId, providerTenantId)' "$DEMO_TARGET" 2>/dev/null; then
  if $check_only; then
    warn "Bug already injected in DestinationRetrievalStrategyResolver.java — would revert on real run"
  else
    sed -i '' 's/return !Objects.equals(currentTenantId, providerTenantId);/return Objects.equals(currentTenantId, providerTenantId);/' "$DEMO_TARGET"
    ok "DestinationRetrievalStrategyResolver.java reverted (! removed)"
  fi
else
  ok "DestinationRetrievalStrategyResolver.java clean"
fi

# Remove cloud-sdk live .test-order index so D0 runs without any ordering
CLOUD_SDK_INDEX="$CLOUD_SDK/.test-order"
if [ -d "$CLOUD_SDK_INDEX" ]; then
  if $check_only; then
    warn "cloud-sdk-java index exists — would delete on real run (D0 needs no index)"
  else
    rm -rf "$CLOUD_SDK_INDEX"
    ok "cloud-sdk-java index removed — D0 will run alphabetically"
  fi
else
  ok "cloud-sdk-java index absent — D0 will run alphabetically"
fi

# D1 needs a pre-trained snapshot
CLOUD_SDK_SNAPSHOT="$SCRIPT_DIR/cloud-sdk-index-snapshot"

if $learn_cloud_sdk; then
  say "Building cloud-sdk-java index snapshot (--learn-cloud-sdk, ~6 min)…"
  say "Step 1/2: installing reactor (mvn install -DskipTests) — needed for SNAPSHOT deps…"
  if ! $check_only; then
    (cd "$CLOUD_SDK" && mvn install -DskipTests -q 2>&1 | tail -5) \
      && ok "reactor installed"
    say "Step 2/2: learn run (mvn test) — building test-order index…"
    (cd "$CLOUD_SDK" && mvn test -q 2>&1 | tail -5) \
      && ok "learn run complete — index written"
    rm -rf "$CLOUD_SDK_SNAPSHOT"
    cp -r "$CLOUD_SDK_INDEX" "$CLOUD_SDK_SNAPSHOT"
    ok "snapshot saved → $CLOUD_SDK_SNAPSHOT"
    # Remove the live index so D0 runs alphabetically
    rm -rf "$CLOUD_SDK_INDEX"
    ok "live index removed — D0 will run alphabetically"
  fi
elif [ -d "$CLOUD_SDK_SNAPSHOT" ]; then
  SNAP_FILES=$(find "$CLOUD_SDK_SNAPSHOT" -type f | wc -l | tr -d ' ')
  ok "cloud-sdk-java index snapshot present ($SNAP_FILES files) — D1 copy-in-db.sh will work"
else
  if $check_only; then
    warn "cloud-sdk-java index snapshot missing at $CLOUD_SDK_SNAPSHOT"
    warn "Run: ./prepare-demo.sh --learn-cloud-sdk  (~6 min, builds and saves snapshot)"
  else
    say "cloud-sdk-java index snapshot missing — building it now (~6 min)…"
    say "Step 1/2: installing reactor (mvn install -DskipTests)…"
    (cd "$CLOUD_SDK" && mvn install -DskipTests -q 2>&1 | tail -5) \
      && ok "reactor installed"
    say "Step 2/2: learn run (mvn test)…"
    (cd "$CLOUD_SDK" && mvn test -q 2>&1 | tail -5) \
      && ok "learn run complete — index written"
    rm -rf "$CLOUD_SDK_SNAPSHOT"
    cp -r "$CLOUD_SDK_INDEX" "$CLOUD_SDK_SNAPSHOT"
    ok "snapshot saved → $CLOUD_SDK_SNAPSHOT"
    rm -rf "$CLOUD_SDK_INDEX"
    ok "live index removed — D0 will run alphabetically"
  fi
fi

# ── 3. Ready summary ──────────────────────────────────────────────────────────

if [ $FAILURES -gt 0 ]; then
  printf "\n${RED}${BOLD}%d check(s) failed — fix before going on stage.${NC}\n\n" "$FAILURES"
  exit 1
fi

printf "\n${GREEN}${BOLD}Demo ready.${NC}\n\n"
printf "${BOLD}Open 2 terminal tabs, cd into each:${NC}\n\n"

printf "  ${CYAN}Tab 1 — D0${NC} (alphabetical baseline, NO test-order)\n"
printf "    cd %s\n" "$CLOUD_SDK"
printf "    ./make-change.sh                # inject ! bug into currentTenantIsProvider()\n"
printf "    mvn test\n"
printf "    # DestinationRetrievalStrategyResolverTest fails ~4th alphabetically\n\n"

printf "  ${CYAN}Tab 2 — D1${NC} (test-order active, same bug → test jumps to #1)\n"
printf "    cd %s\n" "$CLOUD_SDK"
printf "    ./copy-in-db.sh                 # restore pre-trained index\n"
printf "    ./make-change.sh                # inject ! bug\n"
printf "    mvn test-order:show             # DestinationRetrievalStrategyResolverTest = #1\n"
printf "    mvn test\n"
printf "    # DestinationRetrievalStrategyResolverTest runs first — failure surfaces immediately\n\n"

printf "${BOLD}Reset between runs:${NC}\n"
printf "    cd %s\n" "$CLOUD_SDK"
printf "    ./reset-change.sh               # restore DestinationRetrievalStrategyResolver.java\n\n"

printf "${BOLD}Mutation details:${NC}\n"
printf "  File: cloudplatform/connectivity-destination-service/src/.../DestinationRetrievalStrategyResolver.java\n"
printf "  Change: return Objects.equals(...)  →  return !Objects.equals(...)\n"
printf "  Scripts: ./make-change.sh  |  ./reset-change.sh\n\n"
