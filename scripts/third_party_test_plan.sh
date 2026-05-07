#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════════
# Third-Party Repo Test Plan for test-order
# ═══════════════════════════════════════════════════════════════════════════════
#
# Tests all test-order workflows against real OSS projects with synthetic bugs.
#
# Usage:
#   ./scripts/third_party_test_plan.sh [PHASE] [REPO]
#
# Phases: install, learn, order, select, tiered, bugs, full
# Repo:   guava, netty, jackson-databind, commons-lang, ... (optional filter)
#
# Requires: test-order installed to local repo (mvn install -DskipTests)
# ═══════════════════════════════════════════════════════════════════════════════
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
THIRD_PARTY="$ROOT_DIR/third-party"
RESULTS_DIR="$ROOT_DIR/target/third-party-results"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# ─── Configuration ────────────────────────────────────────────────────────────

# Maven repos (use test-order-maven-plugin)
MAVEN_REPOS=(
    "guava"
    "netty"
    "jackson-databind"
    "gson"
    "jsoup"
    "javaparser"
    "commons-collections"
    "commons-io"
    "commons-lang"
    "commons-text"
    "spring-ai"
    "maven"
    "logging-log4j2"
)

# Gradle repos (use test-order-gradle-plugin)
GRADLE_REPOS=(
    "hibernate-orm"
    "okhttp"
    "junit5"
    "kafka"
    "micronaut-core"
    "mockito"
    "spring-boot"
    "spring-petclinic"
)

# Small/fast repos for quick validation
QUICK_REPOS=(
    "jsoup"
    "gson"
    "commons-text"
    "commons-lang"
    "spring-petclinic"
)

# Medium repos for standard testing
MEDIUM_REPOS=(
    "jackson-databind"
    "javaparser"
    "commons-collections"
    "commons-io"
    "okhttp"
    "mockito"
)

# Large repos for stress testing
LARGE_REPOS=(
    "guava"
    "netty"
    "hibernate-orm"
    "kafka"
    "spring-boot"
    "maven"
    "logging-log4j2"
)

PLUGIN_VERSION="0.1.0-SNAPSHOT"
PLUGIN_GROUP="me.bechberger"
PLUGIN_ARTIFACT="test-order-maven-plugin"

# ─── Colors ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; NC='\033[0m'

log()  { echo -e "${BLUE}[$(date +%H:%M:%S)]${NC} $*"; }
ok()   { echo -e "${GREEN}  ✓${NC} $*"; }
warn() { echo -e "${YELLOW}  ⚠${NC} $*"; }
err()  { echo -e "${RED}  ✗${NC} $*"; }
section() { echo -e "\n${CYAN}═══ $* ═══${NC}"; }

# ─── Helpers ──────────────────────────────────────────────────────────────────

is_maven_repo() {
    [[ -f "$THIRD_PARTY/$1/pom.xml" ]]
}

is_gradle_repo() {
    [[ -f "$THIRD_PARTY/$1/build.gradle" || -f "$THIRD_PARTY/$1/build.gradle.kts" ]]
}

repo_dir() {
    echo "$THIRD_PARTY/$1"
}

result_dir() {
    local dir="$RESULTS_DIR/$1/$TIMESTAMP"
    mkdir -p "$dir"
    echo "$dir"
}

# Detect the main test source root for a Maven project
detect_test_package() {
    local repo="$1"
    local dir="$THIRD_PARTY/$repo"
    # Find the most common top-level package in test sources
    find "$dir" -path "*/src/test/java/*" -name "*.java" 2>/dev/null \
        | sed 's|.*/src/test/java/||' \
        | cut -d'/' -f1-3 \
        | sort | uniq -c | sort -rn \
        | head -1 | awk '{print $2}' | tr '/' '.'
}

# Detect a source class name (for changed.classes parameter)
detect_source_class() {
    local repo="$1"
    local dir="$THIRD_PARTY/$repo"
    # Pick the first non-internal, non-package-info source class
    find "$dir" -path "*/src/main/java/*" -name "*.java" \
        ! -name "package-info.java" ! -name "module-info.java" \
        2>/dev/null \
        | head -1 \
        | sed 's|.*/src/main/java/||;s|/|.|g;s|\.java$||'
}

# Detect a single test module for multi-module projects
detect_single_module() {
    local repo="$1"
    local dir="$THIRD_PARTY/$repo"
    # Pick the module with the most test sources
    if [[ -f "$dir/pom.xml" ]] && grep -q "<modules>" "$dir/pom.xml"; then
        local best_mod="" best_count=0
        for mod in $(find "$dir" -maxdepth 2 -name "pom.xml" -not -path "$dir/pom.xml"); do
            local moddir=$(dirname "$mod")
            if [[ -d "$moddir/src/test/java" ]]; then
                local count=$(find "$moddir/src/test/java" -name "*.java" 2>/dev/null | wc -l | tr -d ' ')
                if [[ "$count" -gt "$best_count" ]]; then
                    best_count="$count"
                    best_mod=$(basename "$moddir")
                fi
            fi
        done
        [[ -n "$best_mod" ]] && echo "$best_mod" && return
    fi
    echo ""  # single-module project
}

# ─── Synthetic Bug Injection ─────────────────────────────────────────────────

# Strategy: We inject bugs that cause test failures in predictable ways.
# This lets us verify that test-order correctly prioritizes affected tests.

inject_bug_off_by_one() {
    local repo="$1" file="$2"
    local full="$THIRD_PARTY/$repo/$file"
    if [[ -f "$full" ]]; then
        # Replace "return 0" with "return 1" or "== 0" with "== 1"
        sed -i.bak 's/return 0;/return 1; \/\/ BUG_INJECTED/g' "$full" 2>/dev/null || true
        sed -i.bak 's/== 0/== 1 \/\* BUG_INJECTED \*\//g' "$full" 2>/dev/null || true
        log "  Injected off-by-one bug in $file"
    fi
}

inject_bug_null_return() {
    local repo="$1" file="$2"
    local full="$THIRD_PARTY/$repo/$file"
    if [[ -f "$full" ]]; then
        # Replace first non-void return with null
        sed -i.bak '0,/return [^;]*;/{s/return [^;]*;/return null; \/\/ BUG_INJECTED/}' "$full" 2>/dev/null || true
        log "  Injected null-return bug in $file"
    fi
}

inject_bug_flip_comparison() {
    local repo="$1" file="$2"
    local full="$THIRD_PARTY/$repo/$file"
    if [[ -f "$full" ]]; then
        # Flip < to > in the first comparison found
        sed -i.bak '0,/< /{s/< /> \/\* BUG_INJECTED \*\//}' "$full" 2>/dev/null || true
        log "  Injected flipped-comparison bug in $file"
    fi
}

inject_bug_remove_add() {
    local repo="$1" file="$2"
    local full="$THIRD_PARTY/$repo/$file"
    if [[ -f "$full" ]]; then
        # Comment out the first .add( call
        sed -i.bak '0,/\.add(/{s/\.add(/; \/\/ BUG_INJECTED \/\/ .add(/}' "$full" 2>/dev/null || true
        log "  Injected remove-add bug in $file"
    fi
}

# Restore all injected bugs
restore_bugs() {
    local repo="$1"
    local dir="$THIRD_PARTY/$repo"
    find "$dir" -name "*.bak" -exec sh -c 'mv "$1" "${1%.bak}"' _ {} \; 2>/dev/null
    log "  Restored all files in $repo"
}

# Find good bug injection targets: source files that have corresponding tests
find_bug_targets() {
    local repo="$1"
    local dir="$THIRD_PARTY/$repo"
    # Find source files whose name appears in a test file (likely tested directly)
    find "$dir" -path "*/src/main/java/*" -name "*.java" \
        | while read -r src; do
            local name=$(basename "$src" .java)
            if find "$dir" -path "*/src/test/java/*" -name "${name}Test.java" | grep -q .; then
                echo "$src"
            fi
        done \
        | shuf | head -5  # Pick up to 5 random targets
}

# ─── Maven Plugin Injection ──────────────────────────────────────────────────

inject_maven_plugin() {
    local repo="$1"
    local pom="$THIRD_PARTY/$repo/pom.xml"
    local module="$2"  # optional: specific module

    if [[ -n "$module" ]]; then
        pom="$THIRD_PARTY/$repo/$module/pom.xml"
    fi

    if grep -q "test-order-maven-plugin" "$pom" 2>/dev/null; then
        warn "Plugin already present in $pom"
        return
    fi

    # Backup
    cp "$pom" "$pom.bak"

    # Use Python for reliable XML-aware injection (sed is too fragile for multi-</plugins> poms)
    python3 -c "
import xml.etree.ElementTree as ET
import sys

ns = ''
ET.register_namespace('', 'http://maven.apache.org/POM/4.0.0')
ET.register_namespace('xsi', 'http://www.w3.org/2001/XMLSchema-instance')

tree = ET.parse('$pom')
root = tree.getroot()

# Handle namespace
if root.tag.startswith('{'):
    ns = root.tag.split('}')[0] + '}'

build = root.find(f'{ns}build')
if build is None:
    build = ET.SubElement(root, f'{ns}build')

plugins = build.find(f'{ns}plugins')
if plugins is None:
    plugins = ET.SubElement(build, f'{ns}plugins')

# Add test-order-maven-plugin
plugin = ET.SubElement(plugins, f'{ns}plugin')
ET.SubElement(plugin, f'{ns}groupId').text = '$PLUGIN_GROUP'
ET.SubElement(plugin, f'{ns}artifactId').text = '$PLUGIN_ARTIFACT'
ET.SubElement(plugin, f'{ns}version').text = '$PLUGIN_VERSION'
executions = ET.SubElement(plugin, f'{ns}executions')
execution = ET.SubElement(executions, f'{ns}execution')
goals = ET.SubElement(execution, f'{ns}goals')
ET.SubElement(goals, f'{ns}goal').text = 'prepare'

tree.write('$pom', xml_declaration=True, encoding='UTF-8')
" 2>&1 || { err "Failed to inject plugin into $pom"; return 1; }

    ok "Injected test-order-maven-plugin into $pom"
}

remove_maven_plugin() {
    local repo="$1"
    local module="$2"
    local pom="$THIRD_PARTY/$repo/pom.xml"
    [[ -n "$module" ]] && pom="$THIRD_PARTY/$repo/$module/pom.xml"

    if [[ -f "$pom.bak" ]]; then
        mv "$pom.bak" "$pom"
        ok "Restored original $pom"
    fi
}

# ─── Gradle Plugin Injection ─────────────────────────────────────────────────

inject_gradle_plugin() {
    local repo="$1"
    local dir="$THIRD_PARTY/$repo"
    local build_file=""

    if [[ -f "$dir/build.gradle.kts" ]]; then
        build_file="$dir/build.gradle.kts"
    elif [[ -f "$dir/build.gradle" ]]; then
        build_file="$dir/build.gradle"
    fi

    if [[ -z "$build_file" ]]; then
        err "No build.gradle found in $dir"
        return 1
    fi

    cp "$build_file" "$build_file.bak"

    # Also inject into settings.gradle for plugin resolution
    local settings_file=""
    if [[ -f "$dir/settings.gradle.kts" ]]; then
        settings_file="$dir/settings.gradle.kts"
        cp "$settings_file" "$settings_file.bak"
        # Add mavenLocal() to plugin repositories
        if ! grep -q "mavenLocal" "$settings_file"; then
            sed -i '' '1i\
pluginManagement {\
    repositories {\
        mavenLocal()\
        gradlePluginPortal()\
        mavenCentral()\
    }\
}\
' "$settings_file"
        fi
    elif [[ -f "$dir/settings.gradle" ]]; then
        settings_file="$dir/settings.gradle"
        cp "$settings_file" "$settings_file.bak"
        if ! grep -q "mavenLocal" "$settings_file"; then
            sed -i '' '1i\
pluginManagement {\
    repositories {\
        mavenLocal()\
        gradlePluginPortal()\
        mavenCentral()\
    }\
}\
' "$settings_file"
        fi
    fi

    # Inject plugin application
    if [[ "$build_file" == *.kts ]]; then
        sed -i '' '/^plugins {/a\
    id("me.bechberger.test-order") version "'"$PLUGIN_VERSION"'"
' "$build_file"
    else
        sed -i '' '/^plugins {/a\
    id "me.bechberger.test-order" version "'"$PLUGIN_VERSION"'"
' "$build_file"
    fi

    ok "Injected test-order-gradle-plugin into $build_file"
}

remove_gradle_plugin() {
    local repo="$1"
    local dir="$THIRD_PARTY/$repo"

    for f in build.gradle build.gradle.kts settings.gradle settings.gradle.kts; do
        if [[ -f "$dir/$f.bak" ]]; then
            mv "$dir/$f.bak" "$dir/$f"
        fi
    done
    ok "Restored original build files in $repo"
}

# ═══════════════════════════════════════════════════════════════════════════════
# PHASE 0: Install test-order to local Maven repo
# ═══════════════════════════════════════════════════════════════════════════════

phase_install() {
    section "PHASE 0: Installing test-order to local repo"
    cd "$ROOT_DIR"
    mvn install -DskipTests -Dspotless.check.skip=true -q \
        -pl test-order-agent,test-order-core,test-order-annotations,test-order-junit,test-order-maven-plugin,test-order-gradle-plugin -am
    ok "test-order $PLUGIN_VERSION installed to ~/.m2/repository"
}

# ═══════════════════════════════════════════════════════════════════════════════
# PHASE 1: Learn — Build dependency index for each repo
# ═══════════════════════════════════════════════════════════════════════════════

phase_learn_maven() {
    local repo="$1"
    section "LEARN: $repo (Maven)"
    local dir="$THIRD_PARTY/$repo"
    local results=$(result_dir "$repo")
    local module=$(detect_single_module "$repo")
    local pkg=$(detect_test_package "$repo")

    cd "$dir"

    # Inject plugin
    inject_maven_plugin "$repo" "$module"

    local mvn_args=(-B -Dspotless.check.skip=true -Dcheckstyle.skip=true
                    -Denforcer.skip=true -Dpmd.skip=true -Drat.skip=true
                    -Dlicense.skip=true -Danimal.sniffer.skip=true)
    [[ -n "$pkg" ]] && mvn_args+=("-Dtestorder.includePackages=$pkg")
    [[ -n "$module" ]] && mvn_args+=(-pl "$module" -am)

    # Run learn
    log "Running: mvn clean test -Dtestorder.mode=learn ${mvn_args[*]}"
    if mvn clean test -Dtestorder.mode=learn "${mvn_args[@]}" \
        2>&1 | tee "$results/learn.log" | tail -5; then
        ok "Learn succeeded for $repo"
    else
        warn "Learn had failures (tests may fail, deps still captured)"
    fi

    # Check if index was created
    local idx=$(find "$dir" -name "test-dependencies.lz4" | head -1)
    if [[ -n "$idx" ]]; then
        ok "Index created: $idx ($(du -h "$idx" | cut -f1))"
    else
        err "No index created for $repo"
    fi

    remove_maven_plugin "$repo" "$module"
}

phase_learn_gradle() {
    local repo="$1"
    section "LEARN: $repo (Gradle)"
    local dir="$THIRD_PARTY/$repo"
    local results=$(result_dir "$repo")

    cd "$dir"
    inject_gradle_plugin "$repo"

    log "Running: ./gradlew test -PtestOrder.mode=learn"
    if ./gradlew test -PtestOrder.mode=learn --no-daemon \
        -x checkstyleMain -x checkstyleTest -x spotbugsMain \
        2>&1 | tee "$results/learn.log" | tail -5; then
        ok "Learn succeeded for $repo"
    else
        warn "Learn had failures for $repo"
    fi

    local idx=$(find "$dir" -name "test-dependencies.lz4" | head -1)
    if [[ -n "$idx" ]]; then
        ok "Index created: $idx ($(du -h "$idx" | cut -f1))"
    else
        err "No index created for $repo"
    fi

    remove_gradle_plugin "$repo"
}

# ═══════════════════════════════════════════════════════════════════════════════
# PHASE 2: Order — Run tests in dependency-optimized order
# ═══════════════════════════════════════════════════════════════════════════════

phase_order_maven() {
    local repo="$1"
    section "ORDER: $repo (Maven)"
    local dir="$THIRD_PARTY/$repo"
    local results=$(result_dir "$repo")
    local module=$(detect_single_module "$repo")
    local pkg=$(detect_test_package "$repo")
    local src_class=$(detect_source_class "$repo")

    cd "$dir"
    inject_maven_plugin "$repo" "$module"

    local mvn_args=(-B -Dspotless.check.skip=true -Dcheckstyle.skip=true
                    -Denforcer.skip=true -Dpmd.skip=true -Drat.skip=true
                    -Dlicense.skip=true -Danimal.sniffer.skip=true)
    [[ -n "$pkg" ]] && mvn_args+=("-Dtestorder.includePackages=$pkg")
    [[ -n "$module" ]] && mvn_args+=(-pl "$module" -am)

    # Run order mode (requires existing index from learn phase)
    log "Running: mvn clean test -Dtestorder.mode=order"
    if mvn clean test -Dtestorder.mode=order \
        -Dtestorder.changeMode=explicit \
        -Dtestorder.changed.classes="$src_class" \
        "${mvn_args[@]}" \
        2>&1 | tee "$results/order.log" | tail -5; then
        ok "Order mode succeeded for $repo"
    else
        warn "Order mode had failures for $repo"
    fi

    # Show order
    log "Running: mvn me.bechberger:test-order-maven-plugin:show-order"
    mvn me.bechberger:test-order-maven-plugin:show-order \
        -Dtestorder.changeMode=explicit \
        -Dtestorder.changed.classes="$src_class" \
        "${mvn_args[@]}" \
        2>&1 | tee "$results/show-order.log" | tail -20

    remove_maven_plugin "$repo" "$module"
}

# ═══════════════════════════════════════════════════════════════════════════════
# PHASE 3: Select + Run-Remaining — Two-phase test execution
# ═══════════════════════════════════════════════════════════════════════════════

phase_select_maven() {
    local repo="$1"
    section "SELECT + RUN-REMAINING: $repo (Maven)"
    local dir="$THIRD_PARTY/$repo"
    local results=$(result_dir "$repo")
    local module=$(detect_single_module "$repo")
    local pkg=$(detect_test_package "$repo")
    local src_class=$(detect_source_class "$repo")

    cd "$dir"
    inject_maven_plugin "$repo" "$module"

    local mvn_args=(-B -Dspotless.check.skip=true -Dcheckstyle.skip=true
                    -Denforcer.skip=true -Dpmd.skip=true -Drat.skip=true
                    -Dlicense.skip=true -Danimal.sniffer.skip=true)
    [[ -n "$pkg" ]] && mvn_args+=("-Dtestorder.includePackages=$pkg")
    [[ -n "$module" ]] && mvn_args+=(-pl "$module" -am)

    # Phase 1: Select top-N tests
    log "Running: mvn clean me.bechberger:test-order-maven-plugin:select test -Dtestorder.select.topN=5"
    mvn clean me.bechberger:test-order-maven-plugin:select test \
        -Dtestorder.changeMode=explicit \
        -Dtestorder.changed.classes="$src_class" \
        -Dtestorder.select.topN=5 \
        -Dtestorder.select.randomM=2 \
        -Dtestorder.mode=skip \
        "${mvn_args[@]}" \
        2>&1 | tee "$results/select.log" | tail -10

    # Phase 2: Run remaining
    log "Running: mvn me.bechberger:test-order-maven-plugin:run-remaining test"
    mvn me.bechberger:test-order-maven-plugin:run-remaining test \
        "${mvn_args[@]}" \
        2>&1 | tee "$results/run-remaining.log" | tail -10

    ok "Select + run-remaining completed for $repo"
    remove_maven_plugin "$repo" "$module"
}

# ═══════════════════════════════════════════════════════════════════════════════
# PHASE 4: Tiered Select — Three-tier CI pipeline
# ═══════════════════════════════════════════════════════════════════════════════

phase_tiered_maven() {
    local repo="$1"
    section "TIERED SELECT: $repo (Maven)"
    local dir="$THIRD_PARTY/$repo"
    local results=$(result_dir "$repo")
    local module=$(detect_single_module "$repo")
    local pkg=$(detect_test_package "$repo")
    local src_class=$(detect_source_class "$repo")

    cd "$dir"
    inject_maven_plugin "$repo" "$module"

    local mvn_args=(-B -Dspotless.check.skip=true -Dcheckstyle.skip=true
                    -Denforcer.skip=true -Dpmd.skip=true -Drat.skip=true
                    -Dlicense.skip=true -Danimal.sniffer.skip=true)
    [[ -n "$pkg" ]] && mvn_args+=("-Dtestorder.includePackages=$pkg")
    [[ -n "$module" ]] && mvn_args+=(-pl "$module" -am)

    # Tier 1: Smoke tests (top 3)
    log "Tier 1: Smoke (top 3 tests)"
    mvn clean me.bechberger:test-order-maven-plugin:tiered-select test \
        -Dtestorder.changeMode=explicit \
        -Dtestorder.changed.classes="$src_class" \
        -Dtestorder.tier=1 \
        -Dtestorder.tier1.topN=3 \
        -Dtestorder.mode=skip \
        "${mvn_args[@]}" \
        2>&1 | tee "$results/tier1.log" | tail -5

    # Tier 2: Extended (top 10)
    log "Tier 2: Extended (top 10 tests)"
    mvn clean me.bechberger:test-order-maven-plugin:tiered-select test \
        -Dtestorder.changeMode=explicit \
        -Dtestorder.changed.classes="$src_class" \
        -Dtestorder.tier=2 \
        -Dtestorder.tier2.topN=10 \
        -Dtestorder.mode=skip \
        "${mvn_args[@]}" \
        2>&1 | tee "$results/tier2.log" | tail -5

    # Tier 3: Full (all remaining)
    log "Tier 3: Full suite"
    mvn clean me.bechberger:test-order-maven-plugin:run-remaining test \
        "${mvn_args[@]}" \
        2>&1 | tee "$results/tier3.log" | tail -5

    ok "Tiered pipeline completed for $repo"
    remove_maven_plugin "$repo" "$module"
}

# ═══════════════════════════════════════════════════════════════════════════════
# PHASE 5: Synthetic Bug Injection — Verify prioritization catches bugs fast
# ═══════════════════════════════════════════════════════════════════════════════

phase_bugs_maven() {
    local repo="$1"
    section "BUG INJECTION: $repo (Maven)"
    local dir="$THIRD_PARTY/$repo"
    local results=$(result_dir "$repo")
    local module=$(detect_single_module "$repo")
    local pkg=$(detect_test_package "$repo")

    cd "$dir"
    inject_maven_plugin "$repo" "$module"

    local mvn_args=(-B -Dspotless.check.skip=true -Dcheckstyle.skip=true
                    -Denforcer.skip=true -Dpmd.skip=true -Drat.skip=true
                    -Dlicense.skip=true -Danimal.sniffer.skip=true)
    [[ -n "$pkg" ]] && mvn_args+=("-Dtestorder.includePackages=$pkg")
    [[ -n "$module" ]] && mvn_args+=(-pl "$module" -am)

    # Step 1: Ensure we have a clean index (learn first if needed)
    local idx=$(find "$dir" -name "test-dependencies.lz4" | head -1)
    if [[ -z "$idx" ]]; then
        log "No index found, running learn first..."
        mvn clean test -Dtestorder.mode=learn "${mvn_args[@]}" \
            2>&1 | tee "$results/bug-learn.log" | tail -3
    fi

    # Step 2: Find targets and inject bugs
    local targets=($(find_bug_targets "$repo"))
    if [[ ${#targets[@]} -eq 0 ]]; then
        warn "No suitable bug targets found in $repo"
        remove_maven_plugin "$repo" "$module"
        return
    fi

    local bug_types=("off_by_one" "null_return" "flip_comparison" "remove_add")
    local total_bugs=0
    local bugs_caught_early=0

    for target in "${targets[@]}"; do
        local relative="${target#$THIRD_PARTY/$repo/}"
        local classname=$(echo "$relative" | sed 's|src/main/java/||;s|/|.|g;s|\.java$||')

        for bug_type in "${bug_types[@]}"; do
            total_bugs=$((total_bugs + 1))
            log "Bug #$total_bugs: $bug_type in $classname"

            # Inject bug
            case "$bug_type" in
                off_by_one)     inject_bug_off_by_one "$repo" "$relative" ;;
                null_return)    inject_bug_null_return "$repo" "$relative" ;;
                flip_comparison) inject_bug_flip_comparison "$repo" "$relative" ;;
                remove_add)     inject_bug_remove_add "$repo" "$relative" ;;
            esac

            # Run select with the changed class → should pick the right test
            log "  Running select with changed=$classname"
            local select_output
            select_output=$(mvn clean me.bechberger:test-order-maven-plugin:select test \
                -Dtestorder.changeMode=explicit \
                -Dtestorder.changed.classes="$classname" \
                -Dtestorder.select.topN=3 \
                -Dtestorder.mode=skip \
                "${mvn_args[@]}" 2>&1) || true

            # Check if bug was caught in the selected tests
            if echo "$select_output" | grep -q "FAILURE\|BUILD FAILURE"; then
                bugs_caught_early=$((bugs_caught_early + 1))
                ok "  Bug caught in top-3 selected tests!"
            else
                warn "  Bug NOT caught in top-3 (may need full suite)"
            fi

            echo "$select_output" > "$results/bug-${total_bugs}-${bug_type}.log"

            # Restore
            restore_bugs "$repo"
        done
    done

    log "Bug injection results: $bugs_caught_early / $total_bugs caught in top-3"
    echo "$bugs_caught_early / $total_bugs" > "$results/bug-summary.txt"

    remove_maven_plugin "$repo" "$module"
}

# ═══════════════════════════════════════════════════════════════════════════════
# PHASE 6: Full Workflow — End-to-end pipeline simulation
# ═══════════════════════════════════════════════════════════════════════════════

phase_full_maven() {
    local repo="$1"
    section "FULL WORKFLOW: $repo (Maven)"
    local dir="$THIRD_PARTY/$repo"
    local results=$(result_dir "$repo")
    local module=$(detect_single_module "$repo")
    local pkg=$(detect_test_package "$repo")
    local src_class=$(detect_source_class "$repo")

    cd "$dir"
    inject_maven_plugin "$repo" "$module"

    local mvn_args=(-B -Dspotless.check.skip=true -Dcheckstyle.skip=true
                    -Denforcer.skip=true -Dpmd.skip=true -Drat.skip=true
                    -Dlicense.skip=true -Danimal.sniffer.skip=true
                    -Djacoco.skip=true)
    [[ -n "$pkg" ]] && mvn_args+=("-Dtestorder.includePackages=$pkg")
    [[ -n "$module" ]] && mvn_args+=(-pl "$module" -am)

    # 1. Clean
    log "Step 1: Clean test-order data"
    rm -rf "$dir/.test-order" "$dir/target/test-order-deps" 2>/dev/null
    [[ -n "$module" ]] && rm -rf "$dir/$module/.test-order" "$dir/$module/target/test-order-deps" 2>/dev/null
    ok "Cleaned test-order data"

    # 2. Learn (3 runs to build history)
    for i in 1 2 3; do
        log "Step 2.$i: Learn run $i/3"
        mvn clean test -Dtestorder.mode=learn "${mvn_args[@]}" \
            2>&1 | tee "$results/full-learn-$i.log" | tail -3 || warn "Learn run $i had test failures (deps may still be captured)"
    done

    # Check if index was actually produced
    local idx=$(find "$dir" -name "test-dependencies.lz4" 2>/dev/null | head -1)
    if [[ -z "$idx" ]]; then
        warn "No dependency index produced (project may use JUnit 4). Skipping remaining steps."
        remove_maven_plugin "$repo" "$module"
        return 0
    fi
    ok "Index created: $idx ($(du -h "$idx" | cut -f1))"

    # 3. Dump state
    log "Step 3: Dump state"
    mvn me.bechberger:test-order-maven-plugin:dump "${mvn_args[@]}" 2>&1 | tee "$results/full-dump.log" | tail -20 || warn "Dump failed"

    # 4. Show order with a specific change
    log "Step 4: Show order"
    mvn me.bechberger:test-order-maven-plugin:show-order \
        -Dtestorder.changeMode=explicit \
        -Dtestorder.changed.classes="$src_class" \
        "${mvn_args[@]}" \
        2>&1 | tee "$results/full-show-order.log" | tail -20 || warn "Show-order failed"

    # 5. Select
    log "Step 5: Select top-5"
    mvn clean me.bechberger:test-order-maven-plugin:select test \
        -Dtestorder.changeMode=explicit \
        -Dtestorder.changed.classes="$src_class" \
        -Dtestorder.select.topN=5 \
        -Dtestorder.mode=skip \
        "${mvn_args[@]}" \
        2>&1 | tee "$results/full-select.log" | tail -10 || warn "Select failed"

    # 6. Run remaining
    log "Step 6: Run remaining"
    mvn me.bechberger:test-order-maven-plugin:run-remaining test \
        "${mvn_args[@]}" \
        2>&1 | tee "$results/full-remaining.log" | tail -5 || warn "Run-remaining failed"

    # 7. Inject bug and verify detection
    log "Step 7: Bug injection verification"
    local targets=($(find_bug_targets "$repo"))
    if [[ ${#targets[@]} -gt 0 ]]; then
        local target="${targets[0]}"
        local relative="${target#$THIRD_PARTY/$repo/}"
        local classname=$(echo "$relative" | sed 's|src/main/java/||;s|/|.|g;s|\.java$||')
        inject_bug_flip_comparison "$repo" "$relative"

        mvn clean me.bechberger:test-order-maven-plugin:select test \
            -Dtestorder.changeMode=explicit \
            -Dtestorder.changed.classes="$classname" \
            -Dtestorder.select.topN=3 \
            -Dtestorder.mode=skip \
            "${mvn_args[@]}" \
            2>&1 | tee "$results/full-bug-select.log" | tail -10 || warn "Bug-select failed"

        restore_bugs "$repo"
    else
        warn "No suitable bug injection targets found"
    fi

    # 8. Compact state
    log "Step 8: Compact"
    mvn me.bechberger:test-order-maven-plugin:compact "${mvn_args[@]}" 2>&1 | tail -3 || warn "Compact failed"

    # 9. Export JSON
    log "Step 9: Export JSON"
    mvn me.bechberger:test-order-maven-plugin:export-json "${mvn_args[@]}" 2>&1 | tee "$results/full-export.log" | tail -5 || warn "Export failed"

    # 10. Diagnose
    log "Step 10: Diagnose"
    mvn me.bechberger:test-order-maven-plugin:diagnose "${mvn_args[@]}" 2>&1 | tee "$results/full-diagnose.log" | tail -20 || warn "Diagnose failed"

    ok "Full workflow completed for $repo"
    remove_maven_plugin "$repo" "$module"
}

# ═══════════════════════════════════════════════════════════════════════════════
# PHASE 7: Auto mode — Hands-off test ordering
# ═══════════════════════════════════════════════════════════════════════════════

phase_auto_maven() {
    local repo="$1"
    section "AUTO MODE: $repo (Maven)"
    local dir="$THIRD_PARTY/$repo"
    local results=$(result_dir "$repo")
    local module=$(detect_single_module "$repo")
    local pkg=$(detect_test_package "$repo")
    local src_class=$(detect_source_class "$repo")

    cd "$dir"
    inject_maven_plugin "$repo" "$module"

    local mvn_args=(-B -Dspotless.check.skip=true -Dcheckstyle.skip=true
                    -Denforcer.skip=true -Dpmd.skip=true -Drat.skip=true
                    -Dlicense.skip=true -Danimal.sniffer.skip=true)
    [[ -n "$pkg" ]] && mvn_args+=("-Dtestorder.includePackages=$pkg")
    [[ -n "$module" ]] && mvn_args+=(-pl "$module" -am)

    # Auto mode: first run learns, subsequent runs order
    for i in 1 2 3 4; do
        log "Auto run $i/4"
        mvn clean test -Dtestorder.mode=auto \
            -Dtestorder.changeMode=explicit \
            -Dtestorder.changed.classes="$src_class" \
            "${mvn_args[@]}" \
            2>&1 | tee "$results/auto-$i.log" | tail -5
    done

    ok "Auto mode completed for $repo (4 runs)"
    remove_maven_plugin "$repo" "$module"
}

# ═══════════════════════════════════════════════════════════════════════════════
# ORCHESTRATOR
# ═══════════════════════════════════════════════════════════════════════════════

run_for_repo() {
    local repo="$1"
    local phase="${2:-full}"

    if is_maven_repo "$repo"; then
        case "$phase" in
            learn)   phase_learn_maven "$repo" ;;
            order)   phase_order_maven "$repo" ;;
            select)  phase_select_maven "$repo" ;;
            tiered)  phase_tiered_maven "$repo" ;;
            bugs)    phase_bugs_maven "$repo" ;;
            auto)    phase_auto_maven "$repo" ;;
            full)    phase_full_maven "$repo" ;;
            *)       err "Unknown phase: $phase" ;;
        esac
    elif is_gradle_repo "$repo"; then
        case "$phase" in
            learn)   phase_learn_gradle "$repo" ;;
            *)       warn "Gradle phase '$phase' not yet implemented for $repo" ;;
        esac
    else
        err "Unknown build system for $repo"
    fi
}

# ─── Main ─────────────────────────────────────────────────────────────────────

main() {
    local phase="${1:-}"
    local repo_filter="${2:-}"

    mkdir -p "$RESULTS_DIR"

    if [[ "$phase" == "install" ]]; then
        phase_install
        exit 0
    fi

    # If no phase specified, run full pipeline on quick repos
    if [[ -z "$phase" ]]; then
        section "Running full pipeline on quick repos"
        phase_install
        for repo in "${QUICK_REPOS[@]}"; do
            if [[ -d "$THIRD_PARTY/$repo" ]]; then
                run_for_repo "$repo" "full"
            fi
        done
        exit 0
    fi

    # If repo specified, run that phase on that repo
    if [[ -n "$repo_filter" ]]; then
        run_for_repo "$repo_filter" "$phase"
        exit 0
    fi

    # Otherwise run phase on all applicable repos
    case "$phase" in
        learn|order|select|tiered|bugs|auto|full)
            for repo in "${MAVEN_REPOS[@]}"; do
                if [[ -d "$THIRD_PARTY/$repo" ]]; then
                    run_for_repo "$repo" "$phase"
                fi
            done
            # Gradle repos only support learn for now
            if [[ "$phase" == "learn" ]]; then
                for repo in "${GRADLE_REPOS[@]}"; do
                    if [[ -d "$THIRD_PARTY/$repo" ]]; then
                        run_for_repo "$repo" "$phase"
                    fi
                done
            fi
            ;;
        quick)
            phase_install
            for repo in "${QUICK_REPOS[@]}"; do
                [[ -d "$THIRD_PARTY/$repo" ]] && run_for_repo "$repo" "full"
            done
            ;;
        medium)
            phase_install
            for repo in "${MEDIUM_REPOS[@]}"; do
                [[ -d "$THIRD_PARTY/$repo" ]] && run_for_repo "$repo" "full"
            done
            ;;
        large)
            phase_install
            for repo in "${LARGE_REPOS[@]}"; do
                [[ -d "$THIRD_PARTY/$repo" ]] && run_for_repo "$repo" "full"
            done
            ;;
        all)
            phase_install
            for repo in "${MAVEN_REPOS[@]}"; do
                [[ -d "$THIRD_PARTY/$repo" ]] && run_for_repo "$repo" "full"
            done
            for repo in "${GRADLE_REPOS[@]}"; do
                [[ -d "$THIRD_PARTY/$repo" ]] && run_for_repo "$repo" "learn"
            done
            ;;
        *)
            echo "Usage: $0 [PHASE] [REPO]"
            echo ""
            echo "Phases:"
            echo "  install   - Install test-order to local Maven repo"
            echo "  learn     - Run learn mode on all repos"
            echo "  order     - Run order mode (requires prior learn)"
            echo "  select    - Run select + run-remaining"
            echo "  tiered    - Run 3-tier CI pipeline"
            echo "  bugs      - Inject synthetic bugs and verify detection"
            echo "  auto      - Run auto mode (learn→order transition)"
            echo "  full      - Complete workflow (all of the above)"
            echo "  quick     - Full workflow on small repos only"
            echo "  medium    - Full workflow on medium repos"
            echo "  large     - Full workflow on large repos"
            echo "  all       - Everything on every repo"
            echo ""
            echo "Repos: ${MAVEN_REPOS[*]} ${GRADLE_REPOS[*]}"
            exit 1
            ;;
    esac

    section "DONE — Results in $RESULTS_DIR"
}

main "$@"
