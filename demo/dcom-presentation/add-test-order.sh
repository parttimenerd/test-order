#!/usr/bin/env bash
# =============================================================================
# add-test-order.sh — Add test-order plugin to pom.xml + show diff
# =============================================================================
# Run on stage after the "pain" demo.
# 1. Adds plugin block to pom.xml
# 2. Shows git diff of pom.xml
#
# Speaker then types manually:
#   mvn test-order:download -pl cloudplatform/connectivity-destination-service
#   (wifi fallback: cp -R .prepared-test-order .test-order)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
POM="$SCRIPT_DIR/cloud-sdk-java/cloudplatform/connectivity-destination-service/pom.xml"
MARKER="test-order-plugin-start"

export JAVA_HOME="${JAVA_HOME:-/Users/i560383_1/Library/Java/JavaVirtualMachines/sapmachine-21/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

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

# ── 2. Show diff ─────────────────────────────────────────────────────────────

echo ""
cd "$SCRIPT_DIR/cloud-sdk-java"
git --no-pager diff --color cloudplatform/connectivity-destination-service/pom.xml || true

echo ""
echo "  That's it. One plugin block. Zero other changes."
echo ""
echo "  Now run: mvn test-order:download -pl cloudplatform/connectivity-destination-service"
echo "  (wifi fallback: cp -R .prepared-test-order .test-order)"
echo ""
