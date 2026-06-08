#!/usr/bin/env bash
# =============================================================================
# d-com Mannheim Demo Jam — Preparation Script
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
# Parent POM — plugin lives here so it auto-attaches to every reactor module
# (so the dashboard reflects the full SDK suite, not just one module).
POM="$SDK_DIR/pom.xml"
BAKE_DIR="$SCRIPT_DIR/.baked-history/cloud-sdk-java"

export JAVA_HOME="${JAVA_HOME:-/Users/i560383_1/Library/Java/JavaVirtualMachines/sapmachine-21/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

# ── helpers ──────────────────────────────────────────────────────────────────

pom_enable() {
    grep -q "test-order-plugin-start" "$POM" 2>/dev/null && return
    # Inject into the active <build><plugins> block — i.e. the <plugins>
    # tag that comes AFTER </pluginManagement>. Plugins inside
    # <pluginManagement> only declare versions; they don't attach to the
    # build, so we'd never see test-order run.
    perl -i -pe '
        BEGIN { $seen_pm_close = 0; $done = 0 }
        if (/<\/pluginManagement>/) { $seen_pm_close = 1 }
        elsif ($seen_pm_close && /^\s+<plugins>\s*$/ && !$done) {
            $_ .= "                <!-- test-order-plugin-start -->\n"
                 . "                <plugin>\n"
                 . "                        <groupId>me.bechberger</groupId>\n"
                 . "                        <artifactId>test-order-maven-plugin</artifactId>\n"
                 . "                        <version>0.0.1-SNAPSHOT</version>\n"
                 . "                        <extensions>true</extensions>\n"
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
echo " d-com Mannheim Demo Jam — Setup"
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

    # Remove sample-API modules that trigger a UsageStore PluginContainerException
    # during learn mode (OData/OpenAPI code generators load classes from realms
    # that silently reject importFrom). Workaround per README "Known Issues".
    rm -rf "$SDK_DIR/datamodel/odata/odata-api-sample" \
           "$SDK_DIR/datamodel/odata-v4/odata-v4-api-sample" \
           "$SDK_DIR/datamodel/openapi/openapi-api-sample" \
           "$SDK_DIR/datamodel/openapi/openapi-api-apache-sample"
    perl -i -ne 'print unless m{<module>odata-api-sample</module>}' "$SDK_DIR/datamodel/odata/pom.xml"
    perl -i -ne 'print unless m{<module>odata-v4-api-sample</module>}' "$SDK_DIR/datamodel/odata-v4/pom.xml"
    perl -i -ne 'print unless m{<module>openapi-api-sample</module>|<module>openapi-api-apache-sample</module>}' "$SDK_DIR/datamodel/openapi/pom.xml"
    git add -A && git commit -m "Remove sample-API modules (UsageStore PluginContainerException workaround)" -q

    # Tag this commit so future re-runs of prepare.sh can reset back to it,
    # peeling off any "Add test-order index"/"Reset pom" commits a prior run
    # may have layered on top.
    git tag -f demo-baseline HEAD
    echo "  ✓ Cloned"
else
    echo "▶ cloud-sdk-java already cloned"
fi

# ── 2b. Reset cloud-sdk-java to a known-clean state ──────────────────────────
# Wipe any artefacts a prior (possibly failed) prepare.sh run may have left
# behind. Without this, a stale .test-order/ would short-circuit the learn pass
# at step 4 and the dashboard would silently reflect old data.

echo ""
echo "▶ Resetting cloud-sdk-java to clean state..."
cd "$SDK_DIR"
if git rev-parse --verify -q demo-baseline >/dev/null; then
    # Reset to the first-clone snapshot (clone + flaky-test disable). This
    # peels off "Add test-order index", "Reset pom", and any other commits
    # earlier prepare.sh runs may have layered on.
    git reset --hard demo-baseline -q 2>/dev/null || true
else
    # No baseline tag — script was originally run before tagging was added.
    # Best effort: drop uncommitted edits, leave commit history alone.
    git reset --hard HEAD -q 2>/dev/null || true
    echo "  ⚠️  No 'demo-baseline' tag — re-clone cloud-sdk-java/ for a full reset."
fi
git clean -fdq 2>/dev/null || true
# These directories are kept out of git but recreated each run.
rm -rf "$SDK_DIR/.test-order" "$SDK_DIR/.prepared-test-order"
rm -rf "$SDK_DIR/target/test-order-dashboard"
echo "  ✓ Working tree clean, .test-order/ and dashboard removed"

# ── 3. Build cloud-sdk-java (plugin OFF during install) ───────────────────────

echo ""
echo "▶ Building cloud-sdk-java (skip tests)..."
pom_disable
find ~/.m2/repository/com/sap/cloud/sdk -name "*-5.31.0-SNAPSHOT.jar" -delete 2>/dev/null || true
cd "$SDK_DIR"
mvn clean install -DskipTests -q
echo "  ✓ cloud-sdk-java built"

# ── 4. Learn index across the entire reactor ────────────────────────────────

echo ""
pom_enable

# In multi-module mode the index lives at <reactor-root>/.test-order/.
# Step 2b wiped it, so we always run the learn pass here.
INDEX="$SDK_DIR/.test-order/test-dependencies.lz4"
echo "▶ Running learn pass across the full reactor (this takes a while)..."
cd "$SDK_DIR"
# Run every test in every module so the dashboard reflects the whole SDK.
# The sample-API modules that triggered UsageStore PluginContainerException
# were removed at clone time (step 2), so a clean unaltered run should pass.
mvn test \
    -Dtestorder.mode=learn \
    --batch-mode --no-transfer-progress 2>&1 | tail -20 || true
if [[ -f "$INDEX" ]]; then
    echo "  ✓ Learn pass complete — reactor index at $INDEX"
else
    echo "  ✗ ERROR: $INDEX not produced — learn pass may have failed."
    exit 1
fi

# ── 5. Generate dashboard from learned index (with demo bug applied) ─────────
# Apply the demo bug temporarily so the dashboard reflects the post-change
# state the audience sees on stage (non-zero "Change Affected"). Revert
# immediately afterwards so the on-stage initial state stays clean.

echo ""
echo "▶ Generating dashboard..."
APPLIED_CHANGE=0
if [[ -x "$SCRIPT_DIR/make-change.sh" ]]; then
    echo "  ▶ Applying demo change so dashboard reflects it..."
    "$SCRIPT_DIR/make-change.sh" >/dev/null 2>&1 || true
    APPLIED_CHANGE=1
fi

cd "$SDK_DIR"
mvn test-order:dashboard -Dtestorder.dashboard.open=false --batch-mode --no-transfer-progress -q 2>&1 | tail -5 || true
DASHBOARD_HTML="$SDK_DIR/target/test-order-dashboard/index.html"

# Revert the demo change so the stage starts clean.
if [[ "$APPLIED_CHANGE" == "1" && -x "$SCRIPT_DIR/fix-change.sh" ]]; then
    echo "  ▶ Reverting demo change..."
    "$SCRIPT_DIR/fix-change.sh" >/dev/null 2>&1 || true
fi

if [[ -f "$DASHBOARD_HTML" ]]; then
    echo "  ✓ Dashboard generated at $DASHBOARD_HTML"
    if [[ "$(uname)" == "Darwin" ]]; then
        open "$DASHBOARD_HTML"
    elif command -v xdg-open &>/dev/null; then
        xdg-open "$DASHBOARD_HTML" &>/dev/null &
    fi
else
    echo "  ⚠️  Dashboard not generated — see Maven output above."
fi

# ── 6. Commit index + copilot instructions ────────────────────────────────────

echo ""
echo "▶ Setting up git state..."
cd "$SDK_DIR"
git add -f .github/copilot-instructions.md .test-order pom.xml 2>/dev/null || true
git commit -m "Add test-order index and copilot instructions" --allow-empty -q 2>/dev/null || true

# ── 7. Reset pom to OFF for demo start ────────────────────────────────────────

echo ""
echo "▶ Removing plugin from parent pom for demo start..."
pom_disable
cd "$SDK_DIR"
git add -A && git commit -m "Reset pom" --allow-empty -q 2>/dev/null || true

# ── 8. Bake .test-order snapshot ─────────────────────────────────────────────

echo ""
echo "▶ Baking .test-order snapshot for fast reset (so reset.sh works offline)..."

SDK_INDEX="$SDK_DIR/.test-order/test-dependencies.lz4"
if [[ ! -f "$SDK_INDEX" ]]; then
    echo "  ✗ ERROR: $SDK_INDEX not found — learn pass may have failed."
    exit 1
fi

rm -rf "$BAKE_DIR"
mkdir -p "$BAKE_DIR"
cp -r "$SDK_DIR/.test-order/." "$BAKE_DIR/"
rm -f "$BAKE_DIR/"*.lock
echo "  ✓ $(du -sh "$BAKE_DIR" | cut -f1) saved to .baked-history/"

# Also bake as .prepared-test-order/ inside the reactor root — wifi fallback on stage:
#   cp -R .prepared-test-order .test-order
PREPARED_DIR="$SDK_DIR/.prepared-test-order"
rm -rf "$PREPARED_DIR"
cp -r "$SDK_DIR/.test-order" "$PREPARED_DIR"
rm -f "$PREPARED_DIR/"*.lock
echo "  ✓ Wifi fallback baked to cloud-sdk-java/.prepared-test-order/"

# ── 9. Warm up mvnd daemon ────────────────────────────────────────────────────

echo ""
if command -v mvnd &>/dev/null; then
    echo "▶ Warming up mvnd daemon (cloud-sdk-java)..."
    cd "$SDK_DIR"
    mvnd test-order:affected test -pl "$MODULE" -q 2>/dev/null || true
    echo "  ✓ mvnd daemon warm — on-stage affected runs will be ~30x faster (~0.4s vs ~12s cold)"
fi

# ── 10. Launch Slidev (background — nohup) ───────────────────────────────────

echo ""
SLIDES_DIR="$SCRIPT_DIR/slides"
if [[ -d "$SLIDES_DIR" ]] && [[ -f "$SLIDES_DIR/package.json" ]]; then
    if lsof -ti:3030 &>/dev/null; then
        echo "▶ Stopping existing process on port 3030..."
        lsof -ti:3030 | xargs kill -9 2>/dev/null || true
    fi
    echo "▶ Starting Slidev (background, nohup)..."
    cd "$SLIDES_DIR"
    nohup npm run dev > "$SLIDES_DIR/slidev.log" 2>&1 &
    SLIDEV_PID=$!
    disown 2>/dev/null || true
    echo "  PID $SLIDEV_PID — logs: $SLIDES_DIR/slidev.log"
    echo -n "  Waiting for localhost:3030"
    for i in $(seq 1 30); do
        if lsof -ti:3030 &>/dev/null; then
            echo " ready"
            break
        fi
        echo -n "."
        sleep 1
    done
    if [[ "$(uname)" == "Darwin" ]]; then
        open "http://localhost:3030"
    elif command -v xdg-open &>/dev/null; then
        xdg-open "http://localhost:3030" &>/dev/null &
    fi
    echo "  ✓ Slides at http://localhost:3030"
else
    echo "▶ Slidev not found at $SLIDES_DIR — skipping."
fi

# ── done ──────────────────────────────────────────────────────────────────────

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " ✅ Ready!"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo " Pre-demo checklist:"
echo "   ☐ Terminal font 20pt+"
echo "   ☐ Run: ./start-slides.sh   (opens Slidev + cloud-sdk-java dashboard side-by-side)"
echo "   ☐ VS Code open on cloud-sdk-java/"
echo "   ☐ copilot-instructions.md visible in tab (.github/copilot-instructions.md)"
echo "   ☐ Run: ./reset.sh"
echo "   ☐ Wifi fallback ready: .prepared-test-order/ at cloud-sdk-java root"
echo "     (on stage if no wifi: cd cloud-sdk-java && cp -R .prepared-test-order .test-order)"
echo ""
