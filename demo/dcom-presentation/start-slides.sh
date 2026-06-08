#!/usr/bin/env bash
# Start Slidev + the cloud-sdk-java dashboard side-by-side, then open both
# in the browser. Logs to slides/slidev.log and slides/dashboard.log.
# Run from anywhere in the demo tree.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SLIDES_DIR="$SCRIPT_DIR/slides"
SDK_DIR="$SCRIPT_DIR/cloud-sdk-java"
SLIDEV_LOG="$SLIDES_DIR/slidev.log"
DASHBOARD_LOG="$SLIDES_DIR/dashboard.log"

export JAVA_HOME="${JAVA_HOME:-/Users/i560383_1/Library/Java/JavaVirtualMachines/sapmachine-21/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

# ── Slidev ───────────────────────────────────────────────────────────────────

if lsof -ti:3030 &>/dev/null; then
    echo "▶ Stopping existing process on port 3030..."
    lsof -ti:3030 | xargs kill -9 2>/dev/null || true
fi

echo "▶ Starting Slidev..."
cd "$SLIDES_DIR"
nohup npm run dev > "$SLIDEV_LOG" 2>&1 &
SLIDEV_PID=$!
echo "  PID $SLIDEV_PID — logs: $SLIDEV_LOG"

echo -n "  Waiting for localhost:3030"
for i in $(seq 1 30); do
    if lsof -ti:3030 &>/dev/null; then
        echo " ready"
        break
    fi
    echo -n "."
    sleep 1
done

# ── cloud-sdk-java dashboard (static HTML from learned index) ────────────────

if [[ -d "$SDK_DIR" ]]; then
    echo "▶ Generating cloud-sdk-java dashboard from learned index..."

    # Invoke from the reactor root with the fully-qualified plugin coords so we
    # don't need the plugin block in the POM (which is disabled in the demo's
    # initial state). The mojo is aggregator=true and ReactorContext walks up to
    # find the learned index at <reactor-root>/.test-order/.
    #
    # The demo's initial state has NO .test-order/ at the reactor root (reset.sh
    # cleared it). We temporarily seed it from the baked snapshot, generate the
    # dashboard, then move it back to the stash so the on-stage "no index" state
    # is preserved exactly as reset.sh leaves it.
    PLUGIN_GAV="me.bechberger:test-order-maven-plugin:0.0.1-SNAPSHOT"
    DASHBOARD_HTML="$SDK_DIR/target/test-order-dashboard/index.html"
    LIVE_DIR="$SDK_DIR/.test-order"
    BAKED_SRC="$SCRIPT_DIR/.baked-history/cloud-sdk-java"
    STASH_DIR="$SCRIPT_DIR/.dashboard-stash/cloud-sdk-java"

    SEEDED=0
    if [[ ! -f "$LIVE_DIR/test-dependencies.lz4" ]]; then
        if [[ -f "$BAKED_SRC/test-dependencies.lz4" ]]; then
            echo "  ▶ Seeding $LIVE_DIR/ from baked snapshot..."
            mkdir -p "$LIVE_DIR"
            cp -R "$BAKED_SRC/." "$LIVE_DIR/"
            SEEDED=1
        else
            echo "  ⚠️  No learned index and no baked snapshot at $BAKED_SRC"
            echo "      Run ./prepare.sh first. Skipping dashboard."
            SDK_DIR=""
        fi
    fi

    if [[ -n "${SDK_DIR}" ]]; then
        cd "$SDK_DIR"

        # Apply the demo bug so the dashboard reflects the post-change state
        # the audience will see on stage. Reverted at the end so the on-stage
        # initial state stays clean.
        APPLIED_CHANGE=0
        if [[ -x "$SCRIPT_DIR/make-change.sh" ]]; then
            echo "  ▶ Applying demo change so dashboard reflects it..."
            "$SCRIPT_DIR/make-change.sh" >> "$DASHBOARD_LOG" 2>&1 || true
            APPLIED_CHANGE=1
        fi

        if mvn "${PLUGIN_GAV}:dashboard" \
            -Dtestorder.dashboard.open=false \
            --batch-mode --no-transfer-progress \
            >> "$DASHBOARD_LOG" 2>&1; then
            echo "  ✓ Dashboard generated: $DASHBOARD_HTML"
        else
            echo "  ✗ Dashboard generation failed — see $DASHBOARD_LOG"
        fi

        # Revert the demo change so the stage starts clean.
        if [[ "$APPLIED_CHANGE" == "1" && -x "$SCRIPT_DIR/fix-change.sh" ]]; then
            echo "  ▶ Reverting demo change..."
            "$SCRIPT_DIR/fix-change.sh" >> "$DASHBOARD_LOG" 2>&1 || true
        fi

        # Move seeded index back to the stash so the demo state is unchanged.
        if [[ "$SEEDED" == "1" ]]; then
            echo "  ▶ Stashing seeded .test-order/ → $STASH_DIR/"
            rm -rf "$STASH_DIR"
            mkdir -p "$(dirname "$STASH_DIR")"
            mv "$LIVE_DIR" "$STASH_DIR"
        fi
    fi
else
    echo "  ⚠️  $SDK_DIR not found — skipping dashboard. Run ./prepare.sh first."
fi

# ── open both tabs ───────────────────────────────────────────────────────────

open "http://localhost:3030"
if [[ -f "$DASHBOARD_HTML" ]]; then
    open "$DASHBOARD_HTML"
    echo "  ✅ Slides:    http://localhost:3030"
    echo "  ✅ Dashboard: $DASHBOARD_HTML"
else
    echo "  ✅ Slides open at http://localhost:3030"
fi
