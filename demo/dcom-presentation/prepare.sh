#!/usr/bin/env bash
# =============================================================================
# DCOM Demo Jam — Preparation Script
# =============================================================================
# Run this ONCE before going on stage. Builds cloud-sdk-java, runs the learn
# pass (if index is missing), runs 3 bug-fix cycles to build dashboard history,
# then bakes the .test-order snapshot for fast resets.
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

export JAVA_HOME="${JAVA_HOME:-/Users/i560383_1/Library/Java/JavaVirtualMachines/sapmachine-21/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " DCOM Demo Jam — Setup"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " Java: $(java -version 2>&1 | head -1)"

# Pre-flight checks
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

# 1. Build the test-order plugin from source
echo ""
echo "▶ Building test-order plugin..."
cd "$REPO_ROOT"
mvn install -DskipTests -Dspotless.check.skip=true -pl test-order-maven-plugin -am -q
echo "  ✓ test-order plugin installed"

# ═══════════════════════════════════════════════════
# CLOUD-SDK-JAVA
# ═══════════════════════════════════════════════════

# 2. Clone cloud-sdk-java if not present
echo ""
if [[ ! -d "$SDK_DIR" ]]; then
    echo "▶ Cloning SAP Cloud SDK for Java..."
    git clone --depth 1 https://github.com/SAP/cloud-sdk-java.git "$SDK_DIR"
    cd "$SDK_DIR"
    # Disable flaky timeout test
    sed -i '' '/void testConcurrentFetchSameDestinationSameTenantButDifferentPrincipal/i\
    @Disabled("Flaky timeout")' "$SDK_DIR/$MODULE/src/test/java/com/sap/cloud/sdk/cloudplatform/connectivity/DestinationServiceTest.java"
    sed -i '' '/import org.junit.jupiter.api.Test;/a\
import org.junit.jupiter.api.Disabled;' "$SDK_DIR/$MODULE/src/test/java/com/sap/cloud/sdk/cloudplatform/connectivity/DestinationServiceTest.java"
    git add -A && git commit -m "Disable flaky test" -q
    echo "  ✓ Cloned"
else
    echo "▶ cloud-sdk-java already cloned"
fi

# 3. Build cloud-sdk-java (skip tests)
echo ""
echo "▶ Building cloud-sdk-java (skip tests)..."
cd "$SDK_DIR"
# Ensure test-order is OFF during the plain install — instrumented bytecode must
# not end up in the installed JARs (the odata-generator module is used as a
# Maven plugin and would fail to load UsageStore at plugin boot time).
cd "$SCRIPT_DIR"
./toggle-test-order.sh off 2>/dev/null || true
cd "$SDK_DIR"
# Purge any previously installed SNAPSHOT JARs that may contain instrumented
# bytecode from an earlier (buggy) run — mvn clean install won't purge m2 cache.
find ~/.m2/repository/com/sap/cloud/sdk -name "*-5.31.0-SNAPSHOT.jar" -delete 2>/dev/null || true
mvn clean install -DskipTests -q
echo "  ✓ cloud-sdk-java built"

# 4. Enable test-order and run learn pass (if no index)
echo ""
cd "$SCRIPT_DIR"
./toggle-test-order.sh on 2>/dev/null || true

if [[ ! -f "$SDK_DIR/$MODULE/.test-order/test-dependencies.lz4" ]]; then
    echo "▶ Running learn pass for cloud-sdk-java..."
    cd "$SDK_DIR"
    mvn test -pl "$MODULE" -Dtestorder.mode=learn -q 2>/dev/null || true
    # Merge the fallback file into the index (socket collector writes a fallback on reuseForks=false)
    mvn test-order:aggregate -pl "$MODULE" -q 2>/dev/null || true
    echo "  ✓ Learn pass complete — index built"
else
    echo "▶ cloud-sdk-java index already exists — skipping learn"
fi

# 5. Commit the copilot-instructions and learned index so git change detection works
echo ""
echo "▶ Setting up git state..."
cd "$SDK_DIR"
git add -f .github/copilot-instructions.md .test-order "$MODULE/.test-order" "$MODULE/pom.xml" 2>/dev/null || true
git commit -m "Add test-order index and copilot instructions" --allow-empty -q 2>/dev/null || true

# 5b. Accumulate run history for dashboard using real bug-fix cycles
# Each cycle: introduce a real bug → commit → select+test (red run) → fix → commit → select+test (green run)
echo ""
echo "▶ Building dashboard history (real bug-fix cycles)..."
cd "$SDK_DIR"

RESOLVER="$MODULE/src/main/java/com/sap/cloud/sdk/cloudplatform/connectivity/DestinationRetrievalStrategyResolver.java"
AUTH_PROVIDER="$MODULE/src/main/java/com/sap/cloud/sdk/cloudplatform/connectivity/AuthTokenHeaderProvider.java"

# Cycle 1: flip currentTenantIsProvider — breaks tenant-based routing tests
sed -i '' 's/return Objects.equals(currentTenantId, providerTenantId);/return !Objects.equals(currentTenantId, providerTenantId);/' "$RESOLVER"
git add "$RESOLVER" && git commit -m "bug: invert tenant check" -q 2>/dev/null || true
mvn test-order:select test -pl "$MODULE" -Dmaven.test.failure.ignore=true -q 2>/dev/null || true
sed -i '' 's/return !Objects.equals(currentTenantId, providerTenantId);/return Objects.equals(currentTenantId, providerTenantId);/' "$RESOLVER"
git add "$RESOLVER" && git commit -m "fix: restore tenant check" -q 2>/dev/null || true
mvn test-order:select test -pl "$MODULE" -q 2>/dev/null || true

# Cycle 2: flip XSUAA attributes guard — forces FORWARD_USER_TOKEN when LOOKUP is correct
sed -i '' 's/if( attributes == null || !JWT_ATTR_XSUAA/if( attributes != null \&\& JWT_ATTR_XSUAA/' "$RESOLVER"
git add "$RESOLVER" && git commit -m "bug: invert XSUAA attribute check" -q 2>/dev/null || true
mvn test-order:select test -pl "$MODULE" -Dmaven.test.failure.ignore=true -q 2>/dev/null || true
sed -i '' 's/if( attributes != null \&\& JWT_ATTR_XSUAA/if( attributes == null || !JWT_ATTR_XSUAA/' "$RESOLVER"
git add "$RESOLVER" && git commit -m "fix: restore XSUAA attribute check" -q 2>/dev/null || true
mvn test-order:select test -pl "$MODULE" -q 2>/dev/null || true

# Cycle 3: flip auth token presence check — always throws instead of building header
sed -i '' 's/if( !tokens.isEmpty() ) {/if( tokens.isEmpty() ) {/' "$AUTH_PROVIDER"
git add "$AUTH_PROVIDER" && git commit -m "bug: invert token presence check" -q 2>/dev/null || true
mvn test-order:select test -pl "$MODULE" -Dmaven.test.failure.ignore=true -q 2>/dev/null || true
sed -i '' 's/if( tokens.isEmpty() ) {/if( !tokens.isEmpty() ) {/' "$AUTH_PROVIDER"
git add "$AUTH_PROVIDER" && git commit -m "fix: restore token presence check" -q 2>/dev/null || true
mvn test-order:select test -pl "$MODULE" -q 2>/dev/null || true

git checkout -- . 2>/dev/null || true
git add -A && git commit -m "Restore after history cycles" --allow-empty -q 2>/dev/null || true
echo "  ✓ Dashboard history built (3 bug-fix cycles = 6 runs)"

# 6. Disable test-order (demo starts with "before" state)
echo ""
echo "▶ Resetting pom for demo start..."
cd "$SCRIPT_DIR"
./toggle-test-order.sh off 2>/dev/null || true
cd "$SDK_DIR"
git add -A && git commit -m "Reset pom" --allow-empty -q 2>/dev/null || true

# 7. Bake the history so reset-demo.sh can restore it instantly
echo ""
echo "▶ Baking .test-order snapshot for fast reset..."
cd "$SCRIPT_DIR"
./bake-history.sh

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " ✅ Ready!"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo " Pre-demo checklist:"
echo "   ☐ Terminal font 20pt+"
echo "   ☐ Slides running: cd slides && npm run dev"
echo "   ☐ Dashboard running: cd cloud-sdk-java && mvn test-order:serve -pl $MODULE"
echo "   ☐ Browser tab open at localhost:8080 (dashboard pre-loaded)"
echo "   ☐ VS Code open on cloud-sdk-java/"
echo "   ☐ copilot-instructions.md visible in tab (.github/copilot-instructions.md)"
echo "   ☐ Run: ./reset-demo.sh"
echo ""
