#!/usr/bin/env bash
# reset-demo.sh — Reset all demo state between runs or after a failed demo.
#
# Usage:
#   ./reset-demo.sh           # reset everything
#   ./reset-demo.sh --dry-run # show what would be reset, don't change anything
#
# Resets:
#   D0  reverts commons-collections patch + removes its .test-order index
#   D1  restores commons-collections index from snapshot (re-copy-in-db)
#   D2  restores VisitController.java from backup (if present)
#   D3  restores petclinic index from snapshot
#   D4  nothing to reset — meta-agent.jar stays in .tools/, server exits via Stop button
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
COLLECTIONS="$REPO_ROOT/third-party/commons-collections"
PETCLINIC="$REPO_ROOT/third-party/spring-petclinic"
VISIT="$PETCLINIC/src/main/java/org/springframework/samples/petclinic/owner/VisitController.java"
COLLECTIONS_SNAPSHOT="$SCRIPT_DIR/commons-collections-index-snapshot"
PETCLINIC_SNAPSHOT="$SCRIPT_DIR/petclinic-index-snapshot"

BOLD='\033[1m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'; NC='\033[0m'
ok()   { printf "  ${GREEN}✓${NC}  %s\n" "$*"; }
skip() { printf "  ${YELLOW}–${NC}  %s\n" "$*"; }

dry=false
[ "${1:-}" = "--dry-run" ] && dry=true

$dry && printf "${BOLD}[dry-run] showing what would be reset:${NC}\n\n"

# ── D0: revert commons-collections patch ─────────────────────────────────────
if [ -d "$COLLECTIONS" ]; then
  HAS_CHANGES=$(git -C "$COLLECTIONS" diff --quiet HEAD && echo "no" || echo "yes")
  if [ "$HAS_CHANGES" = "yes" ]; then
    if $dry; then ok "would revert commons-collections working tree (git checkout src/)"
    else git -C "$COLLECTIONS" checkout src/; ok "commons-collections patch reverted"; fi
  else
    skip "commons-collections — already clean"
  fi
else
  skip "commons-collections not found at $COLLECTIONS"
fi

# ── D0: remove commons-collections live index ────────────────────────────────
COLL_INDEX="$COLLECTIONS/.test-order"
if [ -d "$COLL_INDEX" ]; then
  if $dry; then ok "would remove commons-collections .test-order (D0 needs no index)"
  else rm -rf "$COLL_INDEX"; ok "commons-collections .test-order removed"; fi
else
  skip "commons-collections .test-order — already absent"
fi

# ── D1: restore commons-collections index snapshot ───────────────────────────
if [ -d "$COLLECTIONS_SNAPSHOT" ]; then
  if $dry; then ok "would restore commons-collections .test-order from snapshot"
  else
    cp -r "$COLLECTIONS_SNAPSHOT" "$COLL_INDEX"
    ok "commons-collections .test-order restored from snapshot"
  fi
else
  skip "commons-collections snapshot not found at $COLLECTIONS_SNAPSHOT — skipping"
fi

# ── D2: restore VisitController.java ─────────────────────────────────────────
if [ -f "$VISIT.bak" ]; then
  if $dry; then ok "would restore VisitController.java from backup"
  else mv "$VISIT.bak" "$VISIT"; ok "VisitController.java restored"; fi
else
  skip "VisitController.java — no backup (already clean)"
fi

# ── D3: restore petclinic index from snapshot ────────────────────────────────
if [ -d "$PETCLINIC_SNAPSHOT" ]; then
  if $dry; then ok "would restore petclinic .test-order from snapshot"
  else
    rm -rf "$PETCLINIC/.test-order"
    cp -r "$PETCLINIC_SNAPSHOT" "$PETCLINIC/.test-order"
    ok "petclinic .test-order restored from snapshot"
  fi
else
  skip "petclinic index snapshot not found at $PETCLINIC_SNAPSHOT — skipping"
fi

printf "\n${GREEN}${BOLD}Reset complete.${NC} Run ./prepare-demo.sh --check-only to verify.\n"
