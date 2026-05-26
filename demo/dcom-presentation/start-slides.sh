#!/usr/bin/env bash
# Start Slidev in the background and open in browser.
# Logs to slides/slidev.log. Run from anywhere in the demo tree.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SLIDES_DIR="$SCRIPT_DIR/slides"
LOG="$SLIDES_DIR/slidev.log"

# Kill any existing slidev on port 3030
if lsof -ti:3030 &>/dev/null; then
    echo "▶ Stopping existing process on port 3030..."
    lsof -ti:3030 | xargs kill -9 2>/dev/null || true
fi

echo "▶ Starting Slidev..."
cd "$SLIDES_DIR"
nohup npm run dev > "$LOG" 2>&1 &
SLIDEV_PID=$!
echo "  PID $SLIDEV_PID — logs: $LOG"

# Wait for port to be ready (up to 30s)
echo -n "  Waiting for localhost:3030"
for i in $(seq 1 30); do
    if lsof -ti:3030 &>/dev/null; then
        echo " ready"
        break
    fi
    echo -n "."
    sleep 1
done

open "http://localhost:3030"
echo "  ✅ Slides open at http://localhost:3030"
