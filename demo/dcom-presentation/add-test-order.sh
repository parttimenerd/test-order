#!/usr/bin/env bash
# =============================================================================
# add-test-order.sh — Add test-order plugin to root pom + drop in learn index
# =============================================================================
# On-stage script. Two effects:
#   1. Adds the test-order plugin block to cloud-sdk-java/pom.xml
#      (configured to read/write a single shared index at the reactor root)
#   2. Copies the pre-baked learn index into cloud-sdk-java/.test-order/
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="$SCRIPT_DIR/cloud-sdk-java"
POM="$SDK_DIR/pom.xml"
ROOT_TEST_ORDER="$SDK_DIR/.test-order"
BAKE_DIR="$SCRIPT_DIR/.baked-history/cloud-sdk-java"
MARKER="test-order-plugin-start"

export JAVA_HOME="${JAVA_HOME:-/Users/i560383_1/Library/Java/JavaVirtualMachines/sapmachine-21/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

# ── 1. Add plugin to root pom.xml (applies to all 65 modules) ───────────────

if grep -q "$MARKER" "$POM" 2>/dev/null; then
    echo "  test-order already enabled in pom.xml"
else
    echo ""
    echo "  Adding test-order plugin to root pom.xml (all modules)..."
    perl -i -pe '
        if (/^\t\t<plugins>\s*$/ && !$done) {
            $_ .= "\t\t\t<!-- test-order-plugin-start -->\n"
                 . "\t\t\t<plugin>\n"
                 . "\t\t\t\t<groupId>me.bechberger</groupId>\n"
                 . "\t\t\t\t<artifactId>test-order-maven-plugin</artifactId>\n"
                 . "\t\t\t\t<version>0.0.1-SNAPSHOT</version>\n"
                 . "\t\t\t\t<extensions>true</extensions>\n"
                 . "\t\t\t\t<configuration>\n"
                 . "\t\t\t\t\t<topN>7</topN>\n"
                 . "\t\t\t\t\t<seed>42</seed>\n"
                 . "\t\t\t\t</configuration>\n"
                 . "\t\t\t\t<executions>\n"
                 . "\t\t\t\t\t<execution>\n"
                 . "\t\t\t\t\t\t<goals><goal>prepare</goal></goals>\n"
                 . "\t\t\t\t\t</execution>\n"
                 . "\t\t\t\t</executions>\n"
                 . "\t\t\t</plugin>\n"
                 . "\t\t\t<!-- test-order-plugin-end -->\n";
            $done = 1;
        }
    ' "$POM"
    echo "  ✓ Plugin block added to cloud-sdk-java/pom.xml"
fi

# ── 2. Show pom diff ─────────────────────────────────────────────────────────

echo ""
git -C "$SDK_DIR" --no-pager diff --color pom.xml || true

# ── 3. Drop in the pre-baked learn index (single shared index at root) ─────

echo ""
if [[ -d "$BAKE_DIR" ]]; then
    rm -rf "$ROOT_TEST_ORDER"
    mkdir -p "$ROOT_TEST_ORDER"
    cp -r "$BAKE_DIR/." "$ROOT_TEST_ORDER/"
    echo "  ✓ Learn index restored to cloud-sdk-java/.test-order/"
else
    echo "  ⚠️  No baked index at $BAKE_DIR — run ./prepare.sh first"
fi

echo ""
