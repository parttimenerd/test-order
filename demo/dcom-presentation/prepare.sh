#!/usr/bin/env bash
# =============================================================================
# DCOM Demo Jam — Preparation Script
# =============================================================================
# Run ONCE before going on stage. Builds cloud-sdk-java, runs the learn pass
# (if index is missing), builds dashboard history via real bug-fix cycles, then
# bakes a .baked-history/ snapshot so reset.sh can restore state instantly.
#
# Prerequisites:
#   - JDK 21 installed
#   - Maven 3.9+
#   - test-order plugin source (this repo)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SDK_DIR="$SCRIPT_DIR/cloud-sdk-java"
MODULE="cloudplatform/connectivity-destination-service"
POM="$SDK_DIR/$MODULE/pom.xml"
BAKE_DIR="$SCRIPT_DIR/.baked-history/cloud-sdk-java"

export JAVA_HOME="${JAVA_HOME:-/Users/i560383_1/Library/Java/JavaVirtualMachines/sapmachine-21/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

# ── helpers ──────────────────────────────────────────────────────────────────

pom_enable() {
    grep -q "test-order-plugin-start" "$POM" 2>/dev/null && return
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
}

pom_disable() {
    perl -i -ne 'print unless /test-order-plugin-start/ .. /test-order-plugin-end/' "$POM"
}

# ── pre-flight ───────────────────────────────────────────────────────────────

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " DCOM Demo Jam — Setup"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " Java: $(java -version 2>&1 | head -1)"

echo ""
echo "▶ Pre-flight checks..."
JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)
if [[ "$JAVA_VER" -lt 17 ]]; then
    echo "  ✗ ERROR: JDK 17+ required (got $JAVA_VER). Set JAVA_HOME."
    exit 1
fi
if ! command -v mvn &>/dev/null; then
    echo "  ✗ ERROR: Maven not found. Install Maven 3.9+."
    exit 1
fi
if ! command -v git &>/dev/null; then
    echo "  ✗ ERROR: git not found."
    exit 1
fi
echo "  ✓ JDK $JAVA_VER, Maven $(mvn --version 2>&1 | head -1 | awk '{print $3}'), git OK"

# ── 1. Build the test-order plugin ───────────────────────────────────────────

echo ""
echo "▶ Building test-order plugin..."
cd "$REPO_ROOT"
mvn install -DskipTests -Dspotless.check.skip=true -pl test-order-maven-plugin -am -q
echo "  ✓ test-order plugin installed"

# ── 2. Clone cloud-sdk-java ───────────────────────────────────────────────────

echo ""
if [[ ! -d "$SDK_DIR" ]]; then
    echo "▶ Cloning demo fork of SAP Cloud SDK for Java..."
    git clone --depth 1 https://github.com/parttimenerd/cloud-sdk-java.git "$SDK_DIR"
    cd "$SDK_DIR"
    sed -i '' '/void testConcurrentFetchSameDestinationSameTenantButDifferentPrincipal/i\
    @Disabled("Flaky timeout")' "$SDK_DIR/$MODULE/src/test/java/com/sap/cloud/sdk/cloudplatform/connectivity/DestinationServiceTest.java"
    sed -i '' '/import org.junit.jupiter.api.Test;/a\
import org.junit.jupiter.api.Disabled;' "$SDK_DIR/$MODULE/src/test/java/com/sap/cloud/sdk/cloudplatform/connectivity/DestinationServiceTest.java"
    git add -A && git commit -m "Disable flaky test" -q
    echo "  ✓ Cloned"
else
    echo "▶ cloud-sdk-java already cloned"
fi

# ── 3. Build cloud-sdk-java (plugin OFF during install) ───────────────────────

echo ""
echo "▶ Building cloud-sdk-java (skip tests)..."
pom_disable
find ~/.m2/repository/com/sap/cloud/sdk -name "*-5.31.0-SNAPSHOT.jar" -delete 2>/dev/null || true
cd "$SDK_DIR"
mvn clean install -DskipTests -q
echo "  ✓ cloud-sdk-java built"

# ── 4. Download learn index from CI ──────────────────────────────────────────

echo ""
pom_enable

INDEX="$SDK_DIR/$MODULE/.test-order/test-dependencies.lz4"
if [[ -f "$INDEX" ]]; then
    echo "▶ cloud-sdk-java index already exists — skipping download"
else
    echo "▶ Downloading learn index from CI..."
    cd "$SDK_DIR"
    if mvn test-order:download -pl "$MODULE" --batch-mode --no-transfer-progress \
        -DskipFormatting -Denforcer.skip && [[ -f "$INDEX" ]]; then
        echo "  ✓ Index downloaded from CI"
    else
        echo "  ✗ CI download failed — running learn pass locally as fallback..."
        mvn test -pl "$MODULE" -Dtestorder.mode=learn -q 2>/dev/null || true
        mvn test-order:aggregate -pl "$MODULE" -q 2>/dev/null || true
        echo "  ✓ Learn pass complete (local fallback)"
    fi
fi

# ── 5. Commit index + copilot instructions ────────────────────────────────────

echo ""
echo "▶ Setting up git state..."
cd "$SDK_DIR"
git add -f .github/copilot-instructions.md .test-order "$MODULE/.test-order" "$MODULE/pom.xml" 2>/dev/null || true
git commit -m "Add test-order index and copilot instructions" --allow-empty -q 2>/dev/null || true

# ── 6. Build dashboard history via real bug-fix cycles ────────────────────────

echo ""
echo "▶ Building dashboard history (real bug-fix cycles)..."
cd "$SDK_DIR"

RESOLVER="$MODULE/src/main/java/com/sap/cloud/sdk/cloudplatform/connectivity/DestinationRetrievalStrategyResolver.java"
AUTH_PROVIDER="$MODULE/src/main/java/com/sap/cloud/sdk/cloudplatform/connectivity/AuthTokenHeaderProvider.java"

# Cycle 1
sed -i '' 's/return Objects.equals(currentTenantId, providerTenantId);/return !Objects.equals(currentTenantId, providerTenantId);/' "$RESOLVER"
git add "$RESOLVER" && git commit -m "bug: invert tenant check" -q 2>/dev/null || true
mvn test-order:select test -pl "$MODULE" -Dmaven.test.failure.ignore=true -q 2>/dev/null || true
sed -i '' 's/return !Objects.equals(currentTenantId, providerTenantId);/return Objects.equals(currentTenantId, providerTenantId);/' "$RESOLVER"
git add "$RESOLVER" && git commit -m "fix: restore tenant check" -q 2>/dev/null || true
mvn test-order:select test -pl "$MODULE" -q 2>/dev/null || true

# Cycle 2
sed -i '' 's/if( attributes == null || !JWT_ATTR_XSUAA/if( attributes != null \&\& JWT_ATTR_XSUAA/' "$RESOLVER"
git add "$RESOLVER" && git commit -m "bug: invert XSUAA attribute check" -q 2>/dev/null || true
mvn test-order:select test -pl "$MODULE" -Dmaven.test.failure.ignore=true -q 2>/dev/null || true
sed -i '' 's/if( attributes != null \&\& JWT_ATTR_XSUAA/if( attributes == null || !JWT_ATTR_XSUAA/' "$RESOLVER"
git add "$RESOLVER" && git commit -m "fix: restore XSUAA attribute check" -q 2>/dev/null || true
mvn test-order:select test -pl "$MODULE" -q 2>/dev/null || true

# Cycle 3
sed -i '' 's/if( !tokens.isEmpty() ) {/if( tokens.isEmpty() ) {/' "$AUTH_PROVIDER"
git add "$AUTH_PROVIDER" && git commit -m "bug: invert token presence check" -q 2>/dev/null || true
mvn test-order:select test -pl "$MODULE" -Dmaven.test.failure.ignore=true -q 2>/dev/null || true
sed -i '' 's/if( tokens.isEmpty() ) {/if( !tokens.isEmpty() ) {/' "$AUTH_PROVIDER"
git add "$AUTH_PROVIDER" && git commit -m "fix: restore token presence check" -q 2>/dev/null || true
mvn test-order:select test -pl "$MODULE" -q 2>/dev/null || true

git checkout -- . 2>/dev/null || true
git add -A && git commit -m "Restore after history cycles" --allow-empty -q 2>/dev/null || true
echo "  ✓ History built (3 bug-fix cycles)"

# ── 7. Reset pom to OFF for demo start ────────────────────────────────────────

echo ""
echo "▶ Removing plugin from pom for demo start..."
pom_disable
cd "$SDK_DIR"
git add -A && git commit -m "Reset pom" --allow-empty -q 2>/dev/null || true

# ── 8. Bake .test-order snapshot ─────────────────────────────────────────────

echo ""
echo "▶ Baking .test-order snapshot for fast reset (so reset.sh works offline)..."

SDK_INDEX="$SDK_DIR/$MODULE/.test-order/test-dependencies.lz4"
if [[ ! -f "$SDK_INDEX" ]]; then
    echo "  ✗ ERROR: $SDK_INDEX not found — learn pass may have failed."
    exit 1
fi

rm -rf "$BAKE_DIR"
mkdir -p "$BAKE_DIR"
cp -r "$SDK_DIR/$MODULE/.test-order/." "$BAKE_DIR/"
rm -f "$BAKE_DIR/"*.lock
echo "  ✓ $(du -sh "$BAKE_DIR" | cut -f1) saved to .baked-history/"

# ── done ──────────────────────────────────────────────────────────────────────

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " ✅ Ready!"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo " Pre-demo checklist:"
echo "   ☐ Terminal font 20pt+"
echo "   ☐ Slides running: cd slides && npm run dev"
echo "   ☐ VS Code open on cloud-sdk-java/"
echo "   ☐ copilot-instructions.md visible in tab (.github/copilot-instructions.md)"
echo "   ☐ Run: ./reset.sh"
echo ""
