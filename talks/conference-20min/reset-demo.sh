#!/usr/bin/env bash
# reset-demo.sh — Reset all demo state between runs or after a failed demo.
#
# Usage:
#   ./reset-demo.sh           # reset everything
#   ./reset-demo.sh --dry-run # show what would be reset, don't change anything
#
# Resets:
#   D0  nothing (petclinic VisitController restored here too)
#   D1  removes root index so next mvn test triggers a learn run
#   D2  restores Cart.java from backup (if present)
#   D3  restores VisitController.java from backup (if present)
#       restores petclinic index from snapshot
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
SHOP="$REPO_ROOT/samples/sample-shop"
PETCLINIC="$REPO_ROOT/third-party/spring-petclinic"
CART="$SHOP/src/main/java/com/example/shop/Cart.java"
VISIT="$PETCLINIC/src/main/java/org/springframework/samples/petclinic/owner/VisitController.java"
SNAPSHOT="$SCRIPT_DIR/petclinic-index-snapshot"

BOLD='\033[1m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'; NC='\033[0m'
ok()   { printf "  ${GREEN}✓${NC}  %s\n" "$*"; }
skip() { printf "  ${YELLOW}–${NC}  %s\n" "$*"; }

dry=false
[ "${1:-}" = "--dry-run" ] && dry=true

$dry && printf "${BOLD}[dry-run] showing what would be reset:${NC}\n\n"

# ── D2: restore Cart.java ─────────────────────────────────────────────────────
if [ -f "$CART.bak" ]; then
  if $dry; then ok "would restore Cart.java from backup"
  else mv "$CART.bak" "$CART"; ok "Cart.java restored"; fi
else
  skip "Cart.java — no backup (already clean)"
fi

# ── D3: restore VisitController.java ─────────────────────────────────────────
if [ -f "$VISIT.bak" ]; then
  if $dry; then ok "would restore VisitController.java from backup"
  else mv "$VISIT.bak" "$VISIT"; ok "VisitController.java restored"; fi
else
  skip "VisitController.java — no backup (already clean)"
fi

# ── D3: restore petclinic index from snapshot ────────────────────────────────
if [ -d "$SNAPSHOT" ]; then
  if $dry; then ok "would restore petclinic .test-order from snapshot"
  else
    rm -rf "$PETCLINIC/.test-order"
    cp -r "$SNAPSHOT" "$PETCLINIC/.test-order"
    ok "petclinic .test-order restored from snapshot"
  fi
else
  skip "petclinic index snapshot not found at $SNAPSHOT — skipping"
fi

# ── D1: remove sample-shop root index ────────────────────────────────────────
ROOT_INDEX="$REPO_ROOT/.test-order"
if [ -d "$ROOT_INDEX" ]; then
  if $dry; then ok "would remove root .test-order (D1 needs fresh learn pass)"
  else rm -rf "$ROOT_INDEX"; ok "root .test-order removed — D1 will learn on next run"; fi
else
  skip "root .test-order — already absent"
fi

printf "\n${GREEN}${BOLD}Reset complete.${NC} Run ./prepare-demo.sh to verify.\n"
