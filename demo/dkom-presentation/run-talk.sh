#!/usr/bin/env bash
# =============================================================================
# d-kom: Full Talk Flow — Run Both Demos Back-to-Back
# =============================================================================
# One script for the entire 6-minute lightning talk.
# Runs Demo 1 (Legacy) → pause → Demo 2 (Vibe) seamlessly.
#
# Usage:
#   ./run-talk.sh           # Live execution
#   ./run-talk.sh --replay  # Replay from saved output (fallback)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MODE="${1:-}"

# ── ANSI colors ───────────────────────────────────────────────────────
BOLD='\033[1m'
GREEN='\033[1;32m'
CYAN='\033[1;36m'
DIM='\033[2m'
RESET='\033[0m'

clear
printf "\n"
printf "  ${BOLD}╔══════════════════════════════════════════════════════╗${RESET}\n"
printf "  ${BOLD}║  Smarter CI: From Legacy Maintenance to Vibe Coding ║${RESET}\n"
printf "  ${BOLD}║  SAP d-kom 2026 — Johannes Bechberger               ║${RESET}\n"
printf "  ${BOLD}╚══════════════════════════════════════════════════════╝${RESET}\n"
printf "\n"
printf "  ${DIM}Demo 1: Legacy CVE Fix (Apache Olingo)${RESET}\n"
printf "  ${DIM}Demo 2: Vibe Coding Gone Wrong (SAP CAP sflight)${RESET}\n"
printf "\n"

read -p "  ▶ Press Enter to start Demo 1 (Legacy CVE Fix)..."

# ── Demo 1 ────────────────────────────────────────────────────────────
"$SCRIPT_DIR/run-legacy-demo.sh" $MODE

printf "\n"
printf "  ${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}\n"
printf "  ${CYAN}  Demo 1 complete. Switching to Demo 2...             ${RESET}\n"
printf "  ${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}\n"
printf "\n"

read -p "  ▶ Press Enter to start Demo 2 (Vibe Coding)..."

# ── Demo 2 ────────────────────────────────────────────────────────────
"$SCRIPT_DIR/run-vibe-demo.sh" $MODE

printf "\n"
printf "  ${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}\n"
printf "  ${GREEN}  ✓ Both demos complete!                              ${RESET}\n"
printf "  ${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}\n"
printf "\n"
