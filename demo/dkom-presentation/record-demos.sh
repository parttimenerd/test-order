#!/usr/bin/env bash
# =============================================================================
# Record d-kom demos using asciinema for reliable presentation playback
# =============================================================================
# Usage: ./record-demos.sh
# Output: legacy-demo.cast, vibe-demo.cast
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if ! command -v asciinema &>/dev/null; then
    echo "asciinema not found. Install with: brew install asciinema"
    exit 1
fi

echo "╔══════════════════════════════════════════════════╗"
echo "║  Recording d-kom Demos                           ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""
echo "Two recordings will be made:"
echo "  1. legacy-demo.cast  (Act I + III: Olingo)"
echo "  2. vibe-demo.cast    (Act IV: sflight)"
echo ""
echo "Tips:"
echo "  - Type at a natural pace for the audience"
echo "  - Pause briefly after key output appears"
echo "  - The recording auto-stops when the script exits"
echo ""

read -p "Press Enter to start recording LEGACY demo..."
echo ""

asciinema rec "$SCRIPT_DIR/legacy-demo.cast" \
    --title "test-order: Legacy CVE Fix (Apache Olingo)" \
    --cols 120 --rows 35 \
    --command "$SCRIPT_DIR/run-legacy-demo.sh"

echo ""
echo "✓ Legacy demo recorded: $SCRIPT_DIR/legacy-demo.cast"
echo ""

read -p "Press Enter to start recording VIBE demo..."
echo ""

asciinema rec "$SCRIPT_DIR/vibe-demo.cast" \
    --title "test-order: Vibe Coding (SAP CAP sflight)" \
    --cols 120 --rows 35 \
    --command "$SCRIPT_DIR/run-vibe-demo.sh"

echo ""
echo "✓ Vibe demo recorded: $SCRIPT_DIR/vibe-demo.cast"
echo ""
echo "Play back with:"
echo "  asciinema play legacy-demo.cast"
echo "  asciinema play vibe-demo.cast"
echo ""
echo "Or upload: asciinema upload legacy-demo.cast"
