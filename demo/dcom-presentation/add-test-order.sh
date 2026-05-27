#!/usr/bin/env bash
# =============================================================================
# add-test-order.sh — Add test-order plugin to pom.xml + restore learn index
# =============================================================================
# Run on stage after the "pain" demo.
# Adds one plugin block to pom.xml, copies the pre-downloaded learn index from
# .baked-history/ (put there by prepare.sh), and shows the git diff.
# No network needed on stage.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
POM="$SCRIPT_DIR/cloud-sdk-java/cloudplatform/connectivity-destination-service/pom.xml"
BAKE_DIR="$SCRIPT_DIR/.baked-history/cloud-sdk-java"
MODULE_DIR="$SCRIPT_DIR/cloud-sdk-java/cloudplatform/connectivity-destination-service"
MARKER="test-order-plugin-start"

# ── 1. Add plugin to pom.xml ────────────────────────────────────────────────

if grep -q "$MARKER" "$POM" 2>/dev/null; then
    echo "  test-order already enabled in pom.xml"
else
    echo ""
    echo "  Adding test-order plugin to pom.xml..."
    perl -i -pe '
        if (/^\s+<plugins>\s*$/ && !$done) {
            $_ .= "                <!-- test-order-plugin-start -->\n"
                 . "                <plugin>\n"
                 . "                        <groupId>me.bechberger</groupId>\n"
                 . "                        <artifactId>test-order-maven-plugin</artifactId>\n"
                 . "                        <version>0.0.1-SNAPSHOT</version>\n"
                 . "                        <configuration>\n"
                 . "                                <topN>7</topN>\n"
                 . "                                <seed>42</seed>\n"
                 . "                        </configuration>\n"
                 . "                        <executions>\n"
                 . "                                <execution>\n"
                 . "                                        <goals><goal>prepare</goal></goals>\n"
                 . "                                </execution>\n"
                 . "                        </executions>\n"
                 . "                </plugin>\n"
                 . "                <!-- test-order-plugin-end -->\n";
            $done = 1;
        }
    ' "$POM"
    echo "  ✓ Plugin block added"
fi

# ── 2. Restore learn index from .baked-history/ ──────────────────────────────

echo ""
echo "  Restoring learn index (downloaded from CI by prepare.sh)..."
if [[ -d "$BAKE_DIR" ]]; then
    rm -rf "$MODULE_DIR/.test-order"
    mkdir -p "$MODULE_DIR/.test-order"
    cp -r "$BAKE_DIR/." "$MODULE_DIR/.test-order/"
    echo "  ✓ Learn data ready"
else
    echo "  ⚠  No baked index found — run prepare.sh first"
fi

# ── 3. Show diff ─────────────────────────────────────────────────────────────

echo ""
cd "$SCRIPT_DIR/cloud-sdk-java"
git --no-pager diff --color cloudplatform/connectivity-destination-service/pom.xml || true

echo ""
echo "  That's it. One plugin block. Zero other changes."
echo ""
