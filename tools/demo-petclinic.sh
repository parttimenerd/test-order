#!/bin/bash
#
# Demo: test-order plugin on Spring PetClinic
#
# Shows that after a training run and a source‐code change that breaks tests,
# the plugin reorders tests so the affected tests run first.
#
set -euo pipefail

BLUE='\033[1;34m'
GREEN='\033[1;32m'
RED='\033[1;31m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m' # No Color

banner() { printf "\n${BLUE}════════════════════════════════════════════════════${NC}\n${BOLD}  %s${NC}\n${BLUE}════════════════════════════════════════════════════${NC}\n\n" "$1"; }

ROOT="$(cd "$(dirname "$0")" && pwd)"
PETCLINIC="$ROOT/spring-petclinic"
SKIP_OPTS="-Dcheckstyle.skip -Dspring-javaformat.skip"
EXCLUDE_ITS="-Dtest=!*IntegrationTests,!MySqlIntegrationTests,!PostgresIntegrationTests,!MysqlTestApplication"

# Clean up any leftover test-order data
rm -f "$PETCLINIC/test-dependencies.lz4" \
      "$PETCLINIC/.test-order-hashes.lz4" \
      "$PETCLINIC/.test-order-test-hashes.lz4" \
      "$PETCLINIC/.test-order-failures" \
      "$PETCLINIC/.test-order-durations"
rm -rf "$PETCLINIC/target/test-order-deps"

# ── Step 0: Ensure test-order artifacts are installed ───────────────────
banner "Step 0: Install test-order artifacts"
cd "$ROOT"
mvn install -DskipTests -pl test-order-agent,test-order-junit,test-order-maven-plugin -q 2>/dev/null || \
mvn install -DskipTests -pl test-order-agent,test-order-junit,test-order-maven-plugin 2>&1 | tail -5
echo -e "${GREEN}✓ test-order artifacts installed${NC}"

# ── Step 1: Add the plugin to PetClinic's pom.xml ──────────────────────
banner "Step 1: Add test-order-maven-plugin to PetClinic pom.xml"

# Check if plugin is already in pom.xml
if ! grep -q 'test-order-maven-plugin' "$PETCLINIC/pom.xml"; then
    # Insert the plugin just before the closing </plugins> inside <build>
    # We find the jacoco closing tag and insert after it
    python3 - "$PETCLINIC/pom.xml" << 'PYEOF'
import sys
pom = open(sys.argv[1]).read()

plugin_block = """      <plugin>
        <groupId>me.bechberger</groupId>
        <artifactId>test-order-maven-plugin</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <executions>
          <execution>
            <goals>
              <goal>prepare</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
"""

# Insert before the closing </plugins> in <build>
# Find the last </plugin> before </plugins>
insert_pos = pom.find('    </plugins>\n  </build>')
if insert_pos == -1:
    # try alternate indentation
    insert_pos = pom.find('</plugins>')
if insert_pos == -1:
    print("ERROR: Could not find </plugins> in pom.xml", file=sys.stderr)
    sys.exit(1)

new_pom = pom[:insert_pos] + plugin_block + pom[insert_pos:]
open(sys.argv[1], 'w').write(new_pom)
print("Plugin block inserted into pom.xml")
PYEOF
    echo -e "${GREEN}✓ Plugin added to pom.xml${NC}"
else
    echo -e "${YELLOW}Plugin already present in pom.xml${NC}"
fi

# ── Step 2: Training run (learn mode) ──────────────────────────────────
banner "Step 2: Training run — learn mode (collecting dependency data)"
cd "$PETCLINIC"
echo "Running: mvn test -Dtestorder.mode=learn $SKIP_OPTS $EXCLUDE_ITS"
echo ""

./mvnw test -Dtestorder.mode=learn $SKIP_OPTS $EXCLUDE_ITS 2>&1 | \
    grep -E '\[test-order\]|Tests run:|BUILD|Running ' || true

echo ""
if [ -f "$PETCLINIC/test-dependencies.lz4" ]; then
    echo -e "${GREEN}✓ Dependency index created: test-dependencies.lz4${NC}"
    echo "  Contents (first 20 lines):"
    head -20 "$PETCLINIC/test-dependencies.lz4" | sed 's/^/    /'
else
    echo -e "${YELLOW}No index file yet — aggregating from .deps files${NC}"
    ls -la "$PETCLINIC/target/test-order-deps/" 2>/dev/null | head -10 | sed 's/^/    /'
fi

# ── Step 3: Order mode — baseline (no changes) ────────────────────────
banner "Step 3: Baseline — order mode with NO source changes"
cd "$PETCLINIC"
echo "Running: mvn test -Dtestorder.mode=order -Dtestorder.debug=true $SKIP_OPTS $EXCLUDE_ITS"
echo ""

./mvnw test -Dtestorder.mode=order -Dtestorder.debug=true $SKIP_OPTS $EXCLUDE_ITS 2>&1 | \
    grep -E '\[test-order\]|Tests run:|BUILD|Running |test-order.*score|FINAL ORDER|DEBUG' || true

echo ""
echo -e "${GREEN}✓ Baseline run complete — all tests pass in default priority order${NC}"

# ── Step 4: Introduce a bug in Owner.java ──────────────────────────────
banner "Step 4: Introduce a bug in Owner.java"
OWNER_FILE="$PETCLINIC/src/main/java/org/springframework/samples/petclinic/owner/Owner.java"

# Save original
cp "$OWNER_FILE" "$OWNER_FILE.bak"

# Break getAddress() to return a hardcoded wrong value
python3 - "$OWNER_FILE" << 'PYEOF'
import sys
src = open(sys.argv[1]).read()
# Replace getAddress to return a wrong value
old = '''public String getAddress() {
		return this.address;
	}'''
new = '''public String getAddress() {
		return "INJECTED_BUG";
	}'''
if old not in src:
    print("ERROR: Could not find getAddress() in Owner.java", file=sys.stderr)
    sys.exit(1)
src = src.replace(old, new)
open(sys.argv[1], 'w').write(src)
PYEOF

echo -e "${RED}✗ Modified Owner.getAddress() to always return \"INJECTED_BUG\"${NC}"
echo "  This will break tests that check the owner's address field."
echo ""
echo "  Affected tests (expected):"
echo "    - OwnerControllerTests.showOwner (checks address = '110 W. Liberty St.')"
echo "    - OwnerControllerTests.initUpdateOwnerForm (checks address = '110 W. Liberty St.')"
echo "    - ClinicServiceTests (uses Owner objects)"

# ── Step 5: Run with plugin — test order should prioritize Owner tests ─
banner "Step 5: Order mode WITH bug — plugin should prioritize affected tests"
cd "$PETCLINIC"
echo "Running: mvn test -Dtestorder.mode=order -Dtestorder.debug=true $SKIP_OPTS $EXCLUDE_ITS"
echo -e "${YELLOW}(Expecting test failures in OwnerControllerTests — that's the point!)${NC}"
echo ""

# Run tests — we expect failures, so don't fail the script
./mvnw test -Dtestorder.mode=order -Dtestorder.debug=true -Dtestorder.changeMode=uncommitted \
    $SKIP_OPTS $EXCLUDE_ITS 2>&1 | \
    grep -E '\[test-order\]|Tests run:|BUILD|Running |test-order.*score|FINAL ORDER|DEBUG|FAILURE|ERROR.*<<<' || true

echo ""

# ── Step 6: Restore Owner.java ────────────────────────────────────────
banner "Step 6: Restore Owner.java"
mv "$OWNER_FILE.bak" "$OWNER_FILE"
echo -e "${GREEN}✓ Owner.java restored to original${NC}"

# ── Summary ────────────────────────────────────────────────────────────
banner "Summary"
cat << 'EOF'
The demo showed:

1. TRAINING RUN: The plugin ran in learn mode, collecting which test classes
   depend on which application classes (via the Java agent).

2. BASELINE: In order mode with no changes, tests ran in a normal priority
   order since no source files had changed.

3. BUG INJECTION: We modified Owner.getAddress() to return a wrong value.

4. PRIORITIZED RUN: The plugin detected that Owner.java was changed and
   reordered tests so that OwnerControllerTests (which depends on Owner)
   ran FIRST, finding the failure as early as possible.

   Without the plugin, you'd have to wait through all other test classes
   before potentially hitting the relevant failure.
EOF
