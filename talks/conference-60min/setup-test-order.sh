#!/usr/bin/env bash
# setup-test-order.sh — Wire test-order into this project and restore the pre-trained index.
# Run from inside third-party/commons-collections/ (copied there by prepare-demo.sh).
# Shows a diff so the audience sees exactly what changes.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_NAME="$(basename "$SCRIPT_DIR")"
TALK_DIR="$SCRIPT_DIR/../../talks/conference-60min"
SNAPSHOT="$TALK_DIR/${PROJECT_NAME}-index-snapshot"
MVN_DIR="$SCRIPT_DIR/.mvn"
EXT_FILE="$MVN_DIR/extensions.xml"
PLUGIN_VERSION="0.1.1"

BOLD='\033[1m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; NC='\033[0m'

# ── 1. Add test-order to .mvn/extensions.xml ──────────────────────────────────

EXTENSION_XML='<?xml version="1.0" encoding="UTF-8"?>
<extensions>
  <extension>
    <groupId>me.bechberger</groupId>
    <artifactId>test-order-maven-plugin</artifactId>
    <version>'"$PLUGIN_VERSION"'</version>
  </extension>
</extensions>'

if grep -q "test-order-maven-plugin" "$EXT_FILE" 2>/dev/null; then
  printf "${CYAN}test-order already present in .mvn/extensions.xml${NC}\n"
else
  mkdir -p "$MVN_DIR"
  BEFORE=$(cat "$EXT_FILE" 2>/dev/null || echo "")
  echo "$EXTENSION_XML" > "$EXT_FILE"
  AFTER=$(cat "$EXT_FILE")

  printf "${BOLD}Added test-order to .mvn/extensions.xml:${NC}\n"
  diff <(echo "$BEFORE") <(echo "$AFTER") || true
  printf "\n"
fi

# ── 2. Restore pre-trained index ───────────────────────────────────────────────

if [ ! -d "$SNAPSHOT" ]; then
  printf "ERROR: no index snapshot at %s\n" "$SNAPSHOT" >&2
  printf "Re-run prepare-demo.sh to build the snapshot.\n" >&2
  exit 1
fi

rm -rf "$SCRIPT_DIR/.test-order"
cp -r "$SNAPSHOT" "$SCRIPT_DIR/.test-order"
printf "${GREEN}✓${NC} test-order index restored from ${PROJECT_NAME}-index-snapshot\n"
printf "${GREEN}✓${NC} Ready — run: ./make-change-commons.sh && mvn test\n"
