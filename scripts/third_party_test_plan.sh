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

# Source per-repo overrides (detect_compiler_args, detect_package_override, …)
# shellcheck source=scripts/third-party-overrides.sh
source "$SCRIPT_DIR/third-party-overrides.sh" 2>/dev/null || true

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
    "commons-pool"
    "commons-compress"
    "spring-ai"
    "ai-sdk-java"
    "maven"
    "logging-log4j2"
    "cds-feature-attachments"
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
    "neonbee"
    "resilience4j"
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
    "commons-compress"
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

# Known-working repos for regression sweeps (must produce an index + catch bugs)
REGRESSION_REPOS=(
    # Maven repos
    "jackson-databind"
    "commons-collections"
    "commons-text"
    "commons-io"
    "commons-pool"
    "jsoup"
    "logging-log4j2"
    "javaparser"
    "spring-petclinic"
    "spring-ai"
    # Gradle repos
    "junit5"
    "okhttp"
    "mockito"
    "resilience4j"
    "micronaut-core"
    "hibernate-orm"
)

PLUGIN_VERSION="0.0.1-SNAPSHOT"
PLUGIN_GROUP="me.bechberger"
PLUGIN_ARTIFACT="test-order-maven-plugin"
PREPARE_GOAL="$PLUGIN_GROUP:$PLUGIN_ARTIFACT:prepare"

# ─── Colors ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; NC='\033[0m'

log()  { echo -e "${BLUE}[$(date +%H:%M:%S)]${NC} $*"; }
ok()   { echo -e "${GREEN}  ✓${NC} $*"; }
warn() { echo -e "${YELLOW}  ⚠${NC} $*"; }
err()  { echo -e "${RED}  ✗${NC} $*"; }
section() { echo -e "\n${CYAN}═══ $* ═══${NC}"; }

# ─── Helpers ──────────────────────────────────────────────────────────────────

# Single-invocation learn: prepare goal is auto-bound to process-test-classes by
# CollectorLifecycleParticipant.afterProjectsRead(), so plain `mvn clean test`
# instruments classes between testCompile and test, with no double-lifecycle
# recompilation.
mvn_learn() {
    # $@ = extra mvn args (base_args/mvn_args already expanded by caller)
    mvn clean test -Dtestorder.mode=learn "$@"
}

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
        | sort | uniq -c | sort -rn 2>/dev/null \
        | awk 'NR==1{print $2}' | tr '/' '.'
}

# Detect a source class name (for changed.classes parameter).
# First tries to extract a dependency class from the test index (most reliable:
# guarantees the class is actually reachable from the indexed tests).
# Falls back to scanning source files if no index exists yet.
detect_source_class() {
    local repo="$1"
    local module="${2:-}"  # optional: prefer source files from this module
    local dir="$THIRD_PARTY/$repo"

    # Try extracting a non-test dependency class from the existing index
    local idx_file
    idx_file=$(find "$dir" ! -path "*precheck*" -name "test-dependencies.lz4" -print -quit 2>/dev/null)
    if [[ -n "$idx_file" ]]; then
        # Run dump, parse the dep column, skip test classes, pick first non-inner non-test class
        local from_index
        from_index=$(mvn me.bechberger:test-order-maven-plugin:"$PLUGIN_VERSION":dump \
            -f "$dir/pom.xml" -B -q 2>/dev/null \
            | awk -F'\t' 'NF==2 && $1!=$2 {print $2}' \
            | tr ',' '\n' | awk '!/\$|Test$|Tests$|^#|^D|^$/{print; exit}' 2>/dev/null || true)
        if [[ -n "$from_index" ]]; then
            echo "$from_index"
            return
        fi
    fi

    # Fall back to scanning source files if no index exists yet
    if [[ -n "$module" ]]; then
        local result
        result=$(find "$dir/$module" -path "*/src/main/java/*.java" \
            ! -path "*/target/*" ! -path "*/src/test/*" \
            ! -name "package-info.java" ! -name "module-info.java" \
            -print -quit 2>/dev/null \
            | sed 's|.*/src/main/java/||;s|/|.|g;s|\.java$||')
        if [[ -n "$result" ]]; then
            echo "$result"
            return
        fi
    fi
    # Last resort: search the whole repo, avoiding generated/target dirs.
    find "$dir" -path "*/src/main/java/*.java" \
        ! -path "*/target/*" ! -path "*/src/test/*" \
        ! -path "*generator*" ! -path "*metamodel*" \
        ! -name "package-info.java" ! -name "module-info.java" \
        -print -quit 2>/dev/null \
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
#
# Primary strategy: apply a pre-written .patch file from scripts/bugs/<repo>/.
# Each patch was created by hand against a real source file so it is guaranteed
# to produce compilable Java and target code that is actually exercised by tests.
#
# Patch files live in  scripts/bugs/<repo>/<name>.patch  (unified diff format).
# A "bugs.txt" file in the same directory lists patches in preferred order, one
# per line.  If bugs.txt is absent, all *.patch files are tried in sorted order.
#
# The function emits on stdout the FQCN of the class that was patched so that
# the caller can pass it to -Dtestorder.changed.classes.

BUGS_DIR="$SCRIPT_DIR/bugs"

# Apply the first applicable patch for $repo.
# Returns 0 if a log file (Maven or Gradle format) contains test failures.
log_has_test_failures() {
    local log_file="$1"
    # Maven: "Tests run: X, Failures: N" or "Tests run: X, Errors: N" where N != 0
    if grep -q "Tests run:.*Failures: [^0]\|Tests run:.*Errors: [^0]" "$log_file" 2>/dev/null; then
        return 0
    fi
    # Gradle JUnit 5 verbose: "ClassName > method() FAILED" lines
    if grep -qE "^[A-Za-z].* > .+\(\) FAILED$" "$log_file" 2>/dev/null; then
        return 0
    fi
    # Gradle summary: "N tests completed, M failed" where M != 0
    if grep -qE "^[0-9]+ tests? completed, [0-9]+ failed" "$log_file" 2>/dev/null; then
        return 0
    fi
    return 1
}

# Returns 0 if a log file shows that tests actually ran (Maven or Gradle format).
log_has_tests_run() {
    local log_file="$1"
    # Maven: "Tests run:" summary line
    grep -q "Tests run:" "$log_file" 2>/dev/null && return 0
    # Gradle: any test task result — PASSED, FAILED, or SKIPPED
    grep -qE "^[A-Za-z].* > .+\(\) (PASSED|FAILED|SKIPPED)$" "$log_file" 2>/dev/null && return 0
    # Gradle: "N tests completed" summary
    grep -qE "^[0-9]+ tests? completed" "$log_file" 2>/dev/null && return 0
    # Gradle: "> Task :module:test" ran (not SKIPPED/UP-TO-DATE) means tests attempted
    grep -qE "^> Task :[^:]+:test$" "$log_file" 2>/dev/null && return 0
    return 1
}

# Prints "CLASSNAME\tPATCH_FILE" on success, returns 0.
# Returns 1 if no patch applies.
inject_bug_patch() {
    local repo="$1"
    local dir="$THIRD_PARTY/$repo"
    local bugs_dir="$BUGS_DIR/$repo"
    [[ -d "$bugs_dir" ]] || return 1

    local patches=()
    if [[ -f "$bugs_dir/bugs.txt" ]]; then
        while IFS= read -r line; do
            [[ -z "$line" || "$line" == \#* ]] && continue
            patches+=("$bugs_dir/$line")
        done < "$bugs_dir/bugs.txt"
    else
        while IFS= read -r p; do patches+=("$p"); done \
            < <(find "$bugs_dir" -name "*.patch" | sort)
    fi

    for patch_file in "${patches[@]}"; do
        [[ -f "$patch_file" ]] || continue
        # Dry-run first to check the patch applies cleanly
        if patch -d "$dir" -p1 --dry-run --no-backup-if-mismatch --forward -s < "$patch_file" 2>/dev/null; then
            patch -d "$dir" -p1 --no-backup-if-mismatch --forward -s < "$patch_file"
            # Derive the changed class FQCN from the patch +++ header (strip "b/" prefix from git patches)
            local changed_file
            changed_file=$(grep '^+++ ' "$patch_file" | head -1 | sed 's|^+++ b/||;s|^+++ ||;s|\t.*||')
            local fqcn
            # Handle both Java (src/main/java) and Kotlin (src/main/kotlin) source roots
            fqcn=$(echo "$changed_file" | sed 's|.*src/main/java/||;s|.*src/main/kotlin/||;s|/|.|g;s|\.java$||;s|\.kt$||')
            log "  Applied patch $(basename "$patch_file") → $fqcn" >&2
            printf '%s\t%s\n' "$fqcn" "$patch_file"
            return 0
        fi
    done
    return 1
}

# Reverse the patch applied by inject_bug_patch.
restore_bug_patch() {
    local repo="$1" patch_file="$2"
    local dir="$THIRD_PARTY/$repo"
    patch -d "$dir" -p1 -R --no-backup-if-mismatch --forward -s < "$patch_file" 2>/dev/null || true
    log "  Reversed patch $(basename "$patch_file")" >&2
}

# Restore all injected bugs (legacy .bak fallback + any leftover patches)
restore_bugs() {
    local repo="$1"
    local dir="$THIRD_PARTY/$repo"
    find "$dir" -name "*.bak" -exec sh -c 'mv "$1" "${1%.bak}"' _ {} \; 2>/dev/null
    log "  Restored all files in $repo"
}

# Find good bug injection targets: source files that have corresponding tests.
# Optional second argument: path to a TSV index dump (from mvn test-order:dump).
# When provided, candidates are scored by injectability / (df + 1) so that
# discriminating classes (low document-frequency in the dep index) are preferred
# over near-universal utility classes that provide no test-selection signal.
find_bug_targets() {
    local repo="$1"
    local dump_file="${2:-}"
    local dir="$THIRD_PARTY/$repo"
    # Build a set of test class basenames (without Test.java suffix) for O(1) lookup
    local -A test_set
    while IFS= read -r testfile; do
        local base
        base=$(basename "$testfile" Test.java)
        test_set["$base"]=1
    done < <(find "$dir" -path "*/src/test/java/*" -name "*Test.java" ! -path "*/target/*" 2>/dev/null)

    # Build df map from dump: class -> number of tests that list it as a dep
    local -A df_map
    if [[ -n "$dump_file" && -f "$dump_file" ]]; then
        while IFS=$'\t' read -r _test deps; do
            [[ -z "$deps" ]] && continue
            IFS=',' read -ra dep_arr <<< "$deps"
            for dep in "${dep_arr[@]}"; do
                [[ -z "$dep" ]] && continue
                df_map["$dep"]=$(( ${df_map["$dep"]:-0} + 1 ))
            done
        done < "$dump_file"
    fi

    find "$dir" -path "*/src/main/java/*" -name "*.java" ! -path "*/target/*" \
        | while read -r src; do
            local name
            name=$(basename "$src" .java)
            if [[ -n "${test_set[$name]+set}" ]]; then
                local inject_score
                # Count injectable patterns only on non-comment lines (skip //, /* ... */ blocks).
                # Use tight < pattern: left side must end with ), ] or digit to exclude generics.
                inject_score=$(perl -ne '
                    $in_block = 1 if /\/\*/; $in_block = 0 if /\*\//;
                    print if !$in_block && !/^\s*\/\//;
                ' "$src" 2>/dev/null | grep -cE "return (true|false)|[0-9)]\s*<\s*[0-9a-zA-Z_(]|\.add\(|return 0;" || true)
                if [[ "$inject_score" -gt 0 ]]; then
                    local fqcn
                    fqcn=$(echo "$src" | sed 's|.*/src/main/java/||;s|/|.|g;s|\.java$||')
                    local df=${df_map["$fqcn"]:-0}
                    # Penalise high-df: discriminating classes rank higher
                    local final_score=$(( inject_score * 1000 / (df + 1) ))
                    echo "$final_score $src"
                fi
            fi
        done \
        | sort -rn | awk 'NR<=5{print $2}'  # Pick top-5 most injectable + discriminating
}

# ─── Maven Plugin Injection ──────────────────────────────────────────────────

inject_maven_plugin() {
    local repo="$1"
    local dir="$THIRD_PARTY/$repo"
    # optional module arg (ignored — we no longer inject into pom.xml)
    local module="${2:-}"

    local mvn_dir="$dir/.mvn"
    local ext_file="$mvn_dir/extensions.xml"

    # If the extension is already registered (either via our injection or the repo's own config),
    # nothing to do — CollectorLifecycleParticipant will fire automatically.
    if [[ -f "$ext_file" ]] && grep -q "$PLUGIN_ARTIFACT" "$ext_file" 2>/dev/null; then
        warn "Extension already present in $ext_file"
        return
    fi

    mkdir -p "$mvn_dir"

    if [[ -f "$ext_file" ]]; then
        # .mvn/extensions.xml exists but without our plugin — append our extension
        cp "$ext_file" "$ext_file.bak"
        python3 -c "
import xml.etree.ElementTree as ET
ET.register_namespace('', 'http://maven.apache.org/EXTENSIONS/1.0.0')
tree = ET.parse('$ext_file')
root = tree.getroot()
ns = root.tag.split('}')[0] + '}' if root.tag.startswith('{') else ''
ext = ET.SubElement(root, f'{ns}extension')
ET.SubElement(ext, f'{ns}groupId').text = '$PLUGIN_GROUP'
ET.SubElement(ext, f'{ns}artifactId').text = '$PLUGIN_ARTIFACT'
ET.SubElement(ext, f'{ns}version').text = '$PLUGIN_VERSION'
ET.indent(root, space='  ')
tree.write('$ext_file', xml_declaration=True, encoding='UTF-8')
" 2>&1 || { err "Failed to append extension to $ext_file"; return 1; }
        ok "Appended $PLUGIN_ARTIFACT to existing $ext_file"
    else
        # Create a fresh extensions.xml
        cat > "$ext_file" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<extensions>
  <extension>
    <groupId>$PLUGIN_GROUP</groupId>
    <artifactId>$PLUGIN_ARTIFACT</artifactId>
    <version>$PLUGIN_VERSION</version>
  </extension>
</extensions>
EOF
        ok "Created $ext_file for $repo"
    fi
}

remove_maven_plugin() {
    local repo="$1"
    local dir="$THIRD_PARTY/$repo"
    local ext_file="$dir/.mvn/extensions.xml"

    # Remove our injected extensions.xml (or restore backup if we appended to an existing one)
    if [[ -f "$ext_file.bak" ]]; then
        mv "$ext_file.bak" "$ext_file"
        ok "Restored $ext_file for $repo"
    elif [[ -f "$ext_file" ]] && grep -q "$PLUGIN_ARTIFACT" "$ext_file" 2>/dev/null; then
        rm -f "$ext_file"
        # If .mvn/ is now empty and was created by us, remove it too
        rmdir "$dir/.mvn" 2>/dev/null || true
        ok "Removed $ext_file for $repo"
    fi

    # Legacy cleanup: restore any pom.xml.bak files left by old injection approach
    local restored=0
    while IFS= read -r bak; do
        mv "$bak" "${bak%.bak}"
        (( restored++ ))
    done < <(find "$dir" -name "pom.xml.bak" 2>/dev/null)
    [[ "$restored" -gt 0 ]] && ok "Restored $restored legacy pom.xml backup(s) in $repo"
    return 0
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

    # Inject mavenLocal() into settings files so Gradle can resolve both:
    #   (a) the test-order plugin itself (pluginManagement.repositories)
    #   (b) test-order JARs added to testImplementation/testRuntimeOnly
    #       (dependencyResolutionManagement.repositories)
    #
    # Strategy:
    #   pluginManagement: inject into existing block, or prepend a new one.
    #   dependencyResolutionManagement: always append a new block at end of file
    #     (Gradle merges multiple dependencyResolutionManagement blocks safely).
    local settings_file=""
    if [[ -f "$dir/settings.gradle.kts" ]]; then
        settings_file="$dir/settings.gradle.kts"
        cp "$settings_file" "$settings_file.bak"
        # 1. pluginManagement: inject mavenLocal() into existing block or prepend new block
        if ! grep -q "mavenLocal" "$settings_file"; then
            if grep -q "pluginManagement" "$settings_file"; then
                if grep -q "repositories" "$settings_file"; then
                    # Inject after the FIRST repositories { line that is inside pluginManagement
                    python3 -c "
import re, sys
content = open('$settings_file').read()
# Find pluginManagement block and inject mavenLocal into its repositories
def inject(m):
    return m.group(0).replace('repositories {', 'repositories {\n        mavenLocal()', 1)
content = re.sub(r'pluginManagement\s*\{[^}]*repositories\s*\{', inject, content, count=1, flags=re.DOTALL)
open('$settings_file', 'w').write(content)
"
                else
                    sed -i '' '/pluginManagement {/a\
    repositories {\
        mavenLocal()\
        gradlePluginPortal()\
        mavenCentral()\
    }
' "$settings_file"
                fi
            else
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
        # 2. dependencyResolutionManagement: append a new block so test-order JARs resolve
        printf '\ndependencyResolutionManagement { repositories { mavenLocal() } }\n' >> "$settings_file"
    elif [[ -f "$dir/settings.gradle" ]]; then
        settings_file="$dir/settings.gradle"
        cp "$settings_file" "$settings_file.bak"
        if ! grep -q "mavenLocal" "$settings_file"; then
            if grep -q "pluginManagement" "$settings_file"; then
                if grep -q "repositories" "$settings_file"; then
                    sed -i '' '/repositories {/a\
        mavenLocal()
' "$settings_file"
                else
                    sed -i '' '/pluginManagement {/a\
    repositories {\
        mavenLocal()\
        gradlePluginPortal()\
        mavenCentral()\
    }
' "$settings_file"
                fi
            else
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
        printf '\ndependencyResolutionManagement { repositories { mavenLocal() } }\n' >> "$settings_file"
    fi

    # Detect multi-module early — it affects injection strategy.
    local is_multi_module=false
    if grep -q "^allprojects {" "$build_file"; then
        is_multi_module=true
    elif [[ -n "$settings_file" ]] && grep -qE "include\s*[\('\"']" "$settings_file"; then
        is_multi_module=true
    fi

    # Inject plugin application.
    #
    # For Groovy multi-module: use buildscript{} + subprojects{apply plugin:} instead
    # of plugins{id "..."}.  Injecting into plugins{} applies the plugin to the root
    # project, which often lacks the java plugin and fails with "testRuntimeOnly not
    # found".  buildscript{} only puts the jar on the classpath; subprojects{} then
    # applies it only to subprojects that have the java plugin.
    if [[ "$build_file" == *.kts ]] && [[ "$is_multi_module" == "true" ]]; then
        # Kotlin DSL multi-module: declare with apply=false so the plugin is on the classpath
        # but NOT applied to the root project (which may lack the Java plugin / testRuntimeOnly).
        # The subprojects block below then applies only to subprojects that have the java plugin.
        sed -i '' '/^plugins {/a\
    id("me.bechberger.test-order") version "'"$PLUGIN_VERSION"'" apply false
' "$build_file"
    elif [[ "$build_file" == *.kts ]]; then
        # Kotlin DSL single-module: root project has Java plugin, apply directly.
        sed -i '' '/^plugins {/a\
    id("me.bechberger.test-order") version "'"$PLUGIN_VERSION"'"
' "$build_file"
    elif [[ "$is_multi_module" == "true" ]]; then
        # Groovy DSL multi-module: prepend buildscript{} before the first plugins{} line
        sed -i '' '/^plugins {/i\
buildscript {\
    repositories { mavenLocal() }\
    dependencies { classpath "me.bechberger:test-order-gradle-plugin:'"$PLUGIN_VERSION"'" }\
}\

' "$build_file"
    else
        sed -i '' '/^plugins {/a\
    id "me.bechberger.test-order" version "'"$PLUGIN_VERSION"'"
' "$build_file"
    fi

    # For multi-module projects, also apply the plugin to subprojects so that
    # each subproject's Test task is configured (root plugins{} only covers the
    # root project).
    #
    # Only include repositories { mavenLocal() } in the subprojects block when
    # dependencyResolutionManagement is NOT already in settings (i.e. the project
    # uses per-project repositories).  When dependencyResolutionManagement is
    # present, adding project-level repos shadows the settings-level repos and
    # breaks dependencies like google-java-format (hibernate-orm).
    local use_settings_repos=false
    [[ -n "$settings_file" ]] && grep -q "dependencyResolutionManagement" "$settings_file" && use_settings_repos=true

    if [[ "$is_multi_module" == "true" ]]; then
        if [[ "$build_file" == *.kts ]]; then
            # Use plugins.withId("java") so the plugin is only applied to subprojects
            # that actually have the Java plugin. Subprojects without Java (e.g. BOM-only,
            # build-logic plugins) have null testClassesDirs and would crash testOrderSelect.
            if [[ "$use_settings_repos" == "true" ]]; then
                printf '\nsubprojects { plugins.withId("java") { apply(plugin = "me.bechberger.test-order") } }\n' >> "$build_file"
            else
                printf '\nsubprojects { repositories { mavenLocal() }; plugins.withId("java") { apply(plugin = "me.bechberger.test-order") } }\n' >> "$build_file"
            fi
        else
            if [[ "$use_settings_repos" == "true" ]]; then
                printf '\nsubprojects { apply plugin: "me.bechberger.test-order" }\n' >> "$build_file"
            else
                printf '\nsubprojects { repositories { mavenLocal() }; apply plugin: "me.bechberger.test-order" }\n' >> "$build_file"
            fi
        fi
        log "  → multi-module: added subprojects apply block to $build_file"
    fi

    # Inject any extra gradle.properties lines required by this repo.
    local extra_props=""
    type detect_gradle_properties_extra &>/dev/null && extra_props=$(detect_gradle_properties_extra "$repo" 2>/dev/null || echo "")
    if [[ -n "$extra_props" ]]; then
        local props_file="$dir/gradle.properties"
        if [[ -f "$props_file" ]]; then
            cp "$props_file" "$props_file.bak"
        fi
        printf '\n%s\n' "$extra_props" >> "$props_file"
        log "  → injected extra gradle.properties: $extra_props"
    fi

    # Remove any lines from settings files that must be stripped for the build to work.
    local remove_pattern=""
    type detect_gradle_settings_remove &>/dev/null && remove_pattern=$(detect_gradle_settings_remove "$repo" 2>/dev/null || echo "")
    if [[ -n "$remove_pattern" && -n "$settings_file" ]]; then
        # settings_file already backed up above; just modify in place
        sed -i '' "/$remove_pattern/d" "$settings_file"
        log "  → removed lines matching '$remove_pattern' from $settings_file"
    fi

    # Override the daemon JVM vendor in gradle/gradle-daemon-jvm.properties if needed.
    local daemon_vendor=""
    type detect_gradle_daemon_jvm_vendor &>/dev/null && daemon_vendor=$(detect_gradle_daemon_jvm_vendor "$repo" 2>/dev/null || echo "")
    if [[ -n "$daemon_vendor" ]]; then
        local daemon_jvm_file="$dir/gradle/gradle-daemon-jvm.properties"
        if [[ -f "$daemon_jvm_file" ]]; then
            cp "$daemon_jvm_file" "$daemon_jvm_file.bak"
            sed -i '' "s/^toolchainVendor=.*/toolchainVendor=$daemon_vendor/" "$daemon_jvm_file"
            log "  → patched gradle-daemon-jvm.properties: toolchainVendor=$daemon_vendor"
        fi
    fi

    ok "Injected test-order-gradle-plugin into $build_file"
}

remove_gradle_plugin() {
    local repo="$1"
    local dir="$THIRD_PARTY/$repo"

    for f in build.gradle build.gradle.kts settings.gradle settings.gradle.kts gradle.properties gradle/gradle-daemon-jvm.properties; do
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
        -pl test-order-agent,test-order-core,test-order-annotations,test-order-junit,test-order-maven-plugin -am
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

    local mvn_args=(-B -Denforcer.skip=true -Djacoco.skip=true
                    -Dmaven.build.cache.enabled=false)
    [[ -n "$pkg" ]] && mvn_args+=("-Dtestorder.includePackages=$pkg")
    [[ -n "$module" ]] && mvn_args+=(-pl "$module" -am)

    # Run learn
    log "Running: mvn_learn ${mvn_args[*]}"
    if mvn_learn "${mvn_args[@]}" \
        2>&1 | tee "$results/learn.log" | tail -5; then
        ok "Learn succeeded for $repo"
    else
        warn "Learn had failures (tests may fail, deps still captured)"
    fi

    # Check if index was created
    local idx=$(find "$dir" ! -path "*precheck*" -name "test-dependencies.lz4" -print -quit)
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

    local extra_args
    extra_args=$(detect_gradle_extra_args "$repo" 2>/dev/null || echo "")
    local override_java_home
    override_java_home=$(detect_gradle_java_home "$repo" 2>/dev/null || echo "")

    log "Running: ./gradlew cleanTest test --no-build-cache --no-configuration-cache -Dtestorder.mode=learn${extra_args:+ $extra_args}"
    # shellcheck disable=SC2086
    if JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew cleanTest test -Dtestorder.mode=learn --no-daemon --no-build-cache --no-configuration-cache \
        $extra_args \
        2>&1 | tee "$results/learn.log" | tail -5; then
        ok "Learn succeeded for $repo"
    else
        warn "Learn had failures for $repo"
    fi

    local idx=$(find "$dir" ! -path "*precheck*" -name "test-dependencies.lz4" -print -quit)
    if [[ -n "$idx" ]]; then
        ok "Index created: $idx ($(du -h "$idx" | cut -f1))"
        # Clean up accumulated .deps files — can be GB-scale for large projects.
        # The index captures all the information we need.
        local deps_count
        deps_count=$(find "$dir" -name "test-order-deps" -type d 2>/dev/null | wc -l | tr -d ' ')
        if [[ "$deps_count" -gt 0 ]]; then
            find "$dir" -name "test-order-deps" -type d -exec rm -rf {} + 2>/dev/null || true
            log "  Cleaned $deps_count test-order-deps dir(s)"
        fi
    else
        err "No index created for $repo"
    fi

    remove_gradle_plugin "$repo"
}

# Detect a source class for Gradle repos using the dump task (if index exists)
# or by scanning source files.  Outputs an FQCN suitable for -Dtestorder.changed.classes.
detect_source_class_gradle() {
    local repo="$1"
    local dir="$THIRD_PARTY/$repo"

    # Try reading from the index via testOrderDump
    local idx_file
    idx_file=$(find "$dir" ! -path "*precheck*" -name "test-dependencies.lz4" -print -quit 2>/dev/null)
    if [[ -n "$idx_file" ]]; then
        local override_java_home
        override_java_home=$(detect_gradle_java_home "$repo" 2>/dev/null || echo "")
        local extra_args
        extra_args=$(detect_gradle_extra_args "$repo" 2>/dev/null || echo "")
        local from_index
        # shellcheck disable=SC2086
        from_index=$(JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew testOrderDump \
            --no-build-cache --no-configuration-cache \
            $extra_args \
            2>/dev/null \
            | awk -F'\t' 'NF==2 && $1!=$2 {print $2}' \
            | tr ',' '\n' | awk '!/\$|Test$|Tests$|^#|^D|^$/{print; exit}' 2>/dev/null || true)
        if [[ -n "$from_index" ]]; then
            echo "$from_index"
            return
        fi
    fi

    # Fall back: scan source files
    find "$dir" -path "*/src/main/java/*.java" \
        ! -path "*/build/*" ! -path "*/src/test/*" \
        ! -name "package-info.java" ! -name "module-info.java" \
        -print -quit 2>/dev/null \
        | sed 's|.*/src/main/java/||;s|/|.|g;s|\.java$||'
}

# ─── Gradle phase helpers ─────────────────────────────────────────────────────

# Shared setup for Gradle phases: sets dir, results, extra_args, override_java_home.
# Caller must `cd "$dir"` after.
_gradle_phase_init() {
    local repo="$1"
    dir="$THIRD_PARTY/$repo"
    results=$(result_dir "$repo")
    extra_args=$(detect_gradle_extra_args "$repo" 2>/dev/null || echo "")
    override_java_home=$(detect_gradle_java_home "$repo" 2>/dev/null || echo "")
    local init_script
    init_script=$(detect_gradle_init_script "$repo" 2>/dev/null || echo "")
    if [[ -n "$init_script" ]]; then
        extra_args="${extra_args:+$extra_args }--init-script $init_script"
    fi
}

# ═══════════════════════════════════════════════════════════════════════════════
# PHASE 2: Order — Run tests in dependency-optimized order
# ═══════════════════════════════════════════════════════════════════════════════

phase_order_gradle() {
    local repo="$1"
    section "ORDER: $repo (Gradle)"
    local dir results extra_args override_java_home
    _gradle_phase_init "$repo"

    cd "$dir"
    inject_gradle_plugin "$repo"

    local src_class
    src_class=$(detect_source_class_gradle "$repo")

    log "Running: ./gradlew cleanTest test -Dtestorder.mode=order -Dtestorder.changeMode=explicit -Dtestorder.changed.classes=$src_class"
    # shellcheck disable=SC2086
    if JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew cleanTest test \
        --no-build-cache --no-configuration-cache \
        -Dtestorder.mode=order \
        -Dtestorder.changeMode=explicit \
        -Dtestorder.changed.classes="$src_class" \
        $extra_args \
        2>&1 | tee "$results/order.log" | tail -5; then
        ok "Order mode succeeded for $repo"
    else
        warn "Order mode had failures for $repo"
    fi

    log "Running: ./gradlew testOrderShowOrder"
    # shellcheck disable=SC2086
    JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew testOrderShowOrder \
        --no-build-cache --no-configuration-cache \
        -Dtestorder.changeMode=explicit \
        -Dtestorder.changed.classes="$src_class" \
        $extra_args \
        2>&1 | tee "$results/show-order.log" | tail -20 || warn "Show-order failed"

    remove_gradle_plugin "$repo"
}

phase_select_gradle() {
    local repo="$1"
    section "SELECT + RUN-REMAINING: $repo (Gradle)"
    local dir results extra_args override_java_home
    _gradle_phase_init "$repo"

    cd "$dir"
    inject_gradle_plugin "$repo"

    local src_class
    src_class=$(detect_source_class_gradle "$repo")

    log "Running: ./gradlew testOrderSelect -Dtestorder.select.topN=5"
    # shellcheck disable=SC2086
    JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew testOrderSelect \
        --no-build-cache --no-configuration-cache \
        -Dtestorder.changeMode=explicit \
        -Dtestorder.changed.classes="$src_class" \
        -Dtestorder.select.topN=5 \
        -Dtestorder.select.randomM=2 \
        $extra_args \
        2>&1 | tee "$results/select.log" | tail -10 || warn "Select failed"

    log "Running: ./gradlew testOrderRunRemaining"
    # shellcheck disable=SC2086
    JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew testOrderRunRemaining \
        --no-build-cache --no-configuration-cache \
        $extra_args \
        2>&1 | tee "$results/run-remaining.log" | tail -5 || warn "Run-remaining failed"

    ok "Select + run-remaining completed for $repo"
    remove_gradle_plugin "$repo"
}

phase_bugs_gradle() {
    local repo="$1"
    section "BUG INJECTION: $repo (Gradle)"
    local dir results extra_args override_java_home
    _gradle_phase_init "$repo"

    # Bugs-only extra args: additional exclusions specific to the bug injection phase
    local bugs_extra_args=""
    type detect_gradle_bugs_extra_args &>/dev/null && bugs_extra_args=$(detect_gradle_bugs_extra_args "$repo" 2>/dev/null || echo "")
    [[ -n "$bugs_extra_args" ]] && extra_args="${extra_args:+$extra_args }$bugs_extra_args"

    cd "$dir"
    inject_gradle_plugin "$repo"

    # Ensure we have an index
    local idx
    idx=$(find "$dir" ! -path "*precheck*" -name "test-dependencies.lz4" -print -quit 2>/dev/null)
    if [[ -z "$idx" ]]; then
        log "No index found, running learn first..."
        # shellcheck disable=SC2086
        JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew cleanTest test \
            --no-build-cache --no-configuration-cache \
            -Dtestorder.mode=learn \
            $extra_args \
            2>&1 | tee "$results/bug-learn.log" | tail -3 || true
        idx=$(find "$dir" ! -path "*precheck*" -name "test-dependencies.lz4" -print -quit 2>/dev/null)
    fi

    # Apply pre-written patch if available
    local bugs_dir="$BUGS_DIR/$repo"
    if [[ ! -d "$bugs_dir" ]]; then
        warn "No patch directory for $repo — skipping bug injection (add scripts/bugs/$repo/*.patch)"
        remove_gradle_plugin "$repo"
        return
    fi

    # Safety: reverse any leftover patches and clean up .rej files
    for pf in "$bugs_dir"/*.patch; do
        [[ -f "$pf" ]] || continue
        patch -d "$dir" -p1 -R --no-backup-if-mismatch --forward -s < "$pf" >/dev/null 2>&1 || true
    done
    # Remove any .rej files from failed reverse attempts (they confuse subsequent patch dry-runs)
    find "$dir" -name "*.rej" -delete 2>/dev/null || true

    local patch_result
    patch_result=$(inject_bug_patch "$repo") || true
    if [[ -z "$patch_result" ]]; then
        warn "No applicable patch for $repo"
        remove_gradle_plugin "$repo"
        return
    fi

    local classname patch_file
    classname=$(echo "$patch_result" | cut -f1)
    patch_file=$(echo "$patch_result" | cut -f2)

    log "Running select with bug in $classname"
    local bug_log="$results/bug-select.log"

    # For multi-module builds, scope to the subproject that owns the patched file.
    # This avoids triggering unrelated `test` tasks in downstream subprojects (which
    # become stale when a patched upstream class changes, causing spurious failures).
    local task_prefix=""
    local patch_target
    patch_target=$(grep '^+++ ' "$patch_file" | head -1 | sed 's|^+++ b/||;s|^+++ ||;s|\t.*||')
    local first_segment
    first_segment=$(echo "$patch_target" | cut -d/ -f1)
    # If the first segment contains src/main (single-module) the patch is at the root; no prefix.
    # Otherwise, if the segment is a known subproject directory, use it as the prefix.
    if [[ -n "$first_segment" && -d "$dir/$first_segment" && "$first_segment" != "src" ]]; then
        # Allow per-repo override for repos that rename subprojects (e.g. micronaut-core
        # prepends "micronaut-" via useStandardizedProjectNames), or use "ROOT" to skip
        # subproject scoping when the test catching the bug lives in a different module.
        local subproject_prefix_override=""
        type detect_gradle_subproject_prefix &>/dev/null && \
            subproject_prefix_override=$(detect_gradle_subproject_prefix "$repo" "$first_segment" 2>/dev/null || echo "")
        if [[ "$subproject_prefix_override" == "ROOT" ]]; then
            task_prefix=""   # root-level tasks: cleanTestOrderSelect testOrderSelect
        elif [[ -n "$subproject_prefix_override" ]]; then
            task_prefix="$subproject_prefix_override"
        else
            task_prefix=":${first_segment}:"
        fi
    fi

    local clean_task="${task_prefix}cleanTestOrderSelect"
    local select_task="${task_prefix}testOrderSelect"

    # shellcheck disable=SC2086
    # cleanTestOrderSelect forces re-execution of testOrderSelect: after patch the source
    # changes, but Gradle's incremental tracking may consider testOrderSelect UP-TO-DATE
    # when production class files change (class dirs on classpath aren't always tracked).
    JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew "$clean_task" "$select_task" \
        --no-build-cache --no-configuration-cache \
        -Dtestorder.changeMode=explicit \
        -Dtestorder.changed.classes="$classname" \
        -Dtestorder.select.topN=3 \
        $extra_args \
        2>&1 | tee "$bug_log" | tail -10 || true

    if log_has_test_failures "$bug_log"; then
        ok "Bug caught in top-3 selected tests!"
    elif ! log_has_tests_run "$bug_log"; then
        warn "Bug result unknown — build failed before tests ran"
    else
        warn "Bug NOT caught in top-3 selected tests for $classname"
    fi

    restore_bug_patch "$repo" "$patch_file"
    remove_gradle_plugin "$repo"
}

phase_full_gradle() {
    local repo="$1"
    section "FULL WORKFLOW: $repo (Gradle)"
    local dir results extra_args override_java_home
    _gradle_phase_init "$repo"

    cd "$dir"
    inject_gradle_plugin "$repo"

    # 1. Clean
    log "Step 1: Clean test-order data"
    find "$dir" -name "test-dependencies.lz4" ! -path "*precheck*" -delete 2>/dev/null || true
    find "$dir" -name "test-order-deps" -type d -exec rm -rf {} + 2>/dev/null || true
    ok "Cleaned test-order data"

    # 2. Learn (3 runs)
    for i in 1 2 3; do
        log "Step 2.$i: Learn run $i/3"
        # shellcheck disable=SC2086
        JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew cleanTest test \
            --no-build-cache --no-configuration-cache \
            -Dtestorder.mode=learn \
            $extra_args \
            2>&1 | tee "$results/full-learn-$i.log" | tail -3 || warn "Learn run $i had failures"
    done

    local idx
    idx=$(find "$dir" ! -path "*precheck*" -name "test-dependencies.lz4" -print -quit 2>/dev/null)
    if [[ -z "$idx" ]]; then
        warn "No dependency index produced. Skipping remaining steps."
        remove_gradle_plugin "$repo"
        return 0
    fi
    ok "Index created: $idx ($(du -h "$idx" | cut -f1))"

    # Clean up deps dirs
    local deps_count
    deps_count=$(find "$dir" -name "test-order-deps" -type d 2>/dev/null | wc -l | tr -d ' ')
    [[ "$deps_count" -gt 0 ]] && find "$dir" -name "test-order-deps" -type d -exec rm -rf {} + 2>/dev/null || true

    local src_class
    src_class=$(detect_source_class_gradle "$repo")

    # 3. Dump
    log "Step 3: Dump state"
    # shellcheck disable=SC2086
    JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew testOrderDump \
        --no-build-cache --no-configuration-cache \
        $extra_args \
        2>&1 | tee "$results/full-dump.log" | tail -20 || warn "Dump failed"

    # 4. Show order
    log "Step 4: Show order"
    # shellcheck disable=SC2086
    JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew testOrderShowOrder \
        --no-build-cache --no-configuration-cache \
        -Dtestorder.changeMode=explicit \
        -Dtestorder.changed.classes="$src_class" \
        $extra_args \
        2>&1 | tee "$results/full-show-order.log" | tail -20 || warn "Show-order failed"

    # 5. Select
    log "Step 5: Select top-5"
    # shellcheck disable=SC2086
    JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew cleanTest testOrderSelect \
        --no-build-cache --no-configuration-cache \
        -Dtestorder.changeMode=explicit \
        -Dtestorder.changed.classes="$src_class" \
        -Dtestorder.select.topN=5 \
        $extra_args \
        2>&1 | tee "$results/full-select.log" | tail -10 || warn "Select failed"

    # 6. Run remaining
    log "Step 6: Run remaining"
    # shellcheck disable=SC2086
    JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew testOrderRunRemaining \
        --no-build-cache --no-configuration-cache \
        $extra_args \
        2>&1 | tee "$results/full-remaining.log" | tail -5 || warn "Run-remaining failed"

    # 7. Bug injection
    log "Step 7: Bug injection verification"
    local bugs_dir_7="$BUGS_DIR/$repo"
    if [[ -d "$bugs_dir_7" ]]; then
        for pf in "$bugs_dir_7"/*.patch; do
            [[ -f "$pf" ]] || continue
            patch -d "$dir" -p1 -R --no-backup-if-mismatch --forward -s < "$pf" >/dev/null 2>&1 || true
        done
        find "$dir" -name "*.rej" -delete 2>/dev/null || true
        local patch_result
        patch_result=$(inject_bug_patch "$repo") || true
        if [[ -n "$patch_result" ]]; then
            local classname patch_file
            classname=$(echo "$patch_result" | cut -f1)
            patch_file=$(echo "$patch_result" | cut -f2)
            local bug_log="$results/full-bug-select.log"
            # Scope to the subproject owning the patched file to avoid stale downstream test tasks.
            local bp_target bp_segment bp_task_prefix=""
            bp_target=$(grep '^+++ ' "$patch_file" | head -1 | sed 's|^+++ b/||;s|^+++ ||;s|\t.*||')
            bp_segment=$(echo "$bp_target" | cut -d/ -f1)
            if [[ -n "$bp_segment" && -d "$dir/$bp_segment" && "$bp_segment" != "src" ]]; then
                local bp_prefix_override=""
                type detect_gradle_subproject_prefix &>/dev/null && \
                    bp_prefix_override=$(detect_gradle_subproject_prefix "$repo" "$bp_segment" 2>/dev/null || echo "")
                if [[ "$bp_prefix_override" == "ROOT" ]]; then
                    bp_task_prefix=""
                elif [[ -n "$bp_prefix_override" ]]; then
                    bp_task_prefix="$bp_prefix_override"
                else
                    bp_task_prefix=":${bp_segment}:"
                fi
            fi
            # shellcheck disable=SC2086
            JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew "${bp_task_prefix}testOrderSelect" \
                --no-build-cache --no-configuration-cache \
                -Dtestorder.changeMode=explicit \
                -Dtestorder.changed.classes="$classname" \
                -Dtestorder.select.topN=3 \
                $extra_args \
                2>&1 | tee "$bug_log" | tail -10 || true
            if log_has_test_failures "$bug_log"; then
                ok "Bug caught in top-3 selected tests! (step 7)"
            elif ! log_has_tests_run "$bug_log"; then
                warn "Bug result unknown — build failed before tests ran (step 7)"
            else
                warn "Bug not caught in top-3 for $classname (step 7)"
            fi
            restore_bug_patch "$repo" "$patch_file"
        else
            warn "No applicable patch for $repo — skipping bug injection (add scripts/bugs/$repo/*.patch)"
        fi
    else
        warn "No patch directory for $repo — skipping bug injection (add scripts/bugs/$repo/*.patch)"
    fi

    ok "Full workflow completed for $repo"
    remove_gradle_plugin "$repo"
}

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

    local mvn_args=(-B -Denforcer.skip=true -Djacoco.skip=true
                    -Dmaven.build.cache.enabled=false)
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

    local mvn_args=(-B -Denforcer.skip=true -Djacoco.skip=true
                    -Dmaven.build.cache.enabled=false)
    [[ -n "$pkg" ]] && mvn_args+=("-Dtestorder.includePackages=$pkg")
    [[ -n "$module" ]] && mvn_args+=(-pl "$module" -am)

    # Phase 1: Select top-N tests
    log "Running: mvn clean me.bechberger:test-order-maven-plugin:affected test -Dtestorder.select.topN=5"
    mvn clean me.bechberger:test-order-maven-plugin:affected test \
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

    local mvn_args=(-B -Denforcer.skip=true -Djacoco.skip=true
                    -Dmaven.build.cache.enabled=false)
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

    local compiler_args
    compiler_args=$(detect_compiler_args "$repo")
    local extra_mvn_args=""
    type detect_extra_mvn_args &>/dev/null && extra_mvn_args=$(detect_extra_mvn_args "$repo")
    local base_args=(-B -Denforcer.skip=true -Djacoco.skip=true
                     -Dmaven.build.cache.enabled=false)
    [[ -n "$compiler_args" ]] && base_args+=($compiler_args)
    if [[ -n "$extra_mvn_args" ]]; then
        eval "base_args+=($extra_mvn_args)"
    fi
    [[ -n "$pkg" ]] && base_args+=("-Dtestorder.includePackages=$pkg")

    local mvn_args=("${base_args[@]}")
    [[ -n "$module" ]] && mvn_args+=(-pl "$module" -am)

    # Ensure we have an index (learn first if needed)
    local idx
    idx=$(find "$dir" ! -path "*precheck*" -name "test-dependencies.lz4" -print -quit 2>/dev/null)
    if [[ -z "$idx" ]]; then
        log "No index found, running learn first..."
        mvn_learn "${mvn_args[@]}" \
            2>&1 | tee "$results/bug-learn.log" | tail -3
        idx=$(find "$dir" ! -path "*precheck*" -name "test-dependencies.lz4" -print -quit 2>/dev/null)
        if [[ -z "$idx" ]]; then
            warn "No index produced — cannot run bug injection"
            remove_maven_plugin "$repo" "$module"
            return 1
        fi
    fi

    # Determine which module owns the index for command-only goals
    local idx_dir
    idx_dir=$(dirname "$idx")        # .../repo/<mod>/.test-order OR .../repo/.test-order
    idx_dir=$(dirname "$idx_dir")    # .../repo/<mod>             OR .../repo
    local idx_module=""
    [[ "$idx_dir" != "$dir" ]] && idx_module=$(basename "$idx_dir")

    local cmd_args=("${base_args[@]}")
    [[ -n "$idx_module" ]] && cmd_args+=(-pl "$idx_module")

    local test_cmd_args=("${cmd_args[@]}")
    [[ -n "$idx_module" ]] && test_cmd_args+=(-am)

    # Reverse any stale patches before applying fresh one
    local bugs_dir="$BUGS_DIR/$repo"
    if [[ -d "$bugs_dir" ]]; then
        for pf in "$bugs_dir"/*.patch; do
            [[ -f "$pf" ]] || continue
            patch -d "$dir" -p1 -R --no-backup-if-mismatch --forward -s < "$pf" >/dev/null 2>&1 || true
        done
        find "$dir" -name "*.rej" -delete 2>/dev/null || true
    fi

    local patch_result
    patch_result=$(inject_bug_patch "$repo") || true
    if [[ -n "$patch_result" ]]; then
        local classname patch_file
        classname=$(echo "$patch_result" | cut -f1)
        patch_file=$(echo "$patch_result" | cut -f2)
        log "Running select with bug in $classname"
        local bug_log="$results/bug-select.log"
        # shellcheck disable=SC2086
        mvn clean me.bechberger:test-order-maven-plugin:affected test \
            -Dtestorder.changeMode=explicit \
            -Dtestorder.changed.classes="$classname" \
            -Dtestorder.select.topN=3 \
            -Dtestorder.mode=skip \
            "${test_cmd_args[@]}" \
            2>&1 | tee "$bug_log" | tail -10 || true
        if log_has_test_failures "$bug_log"; then
            ok "Bug caught in top-3 selected tests!"
        elif grep -q "None of the explicitly specified changed classes" "$bug_log" 2>/dev/null; then
            warn "Bug class $classname not in dependency index"
        elif ! log_has_tests_run "$bug_log"; then
            warn "Bug result unknown — build failed before tests ran"
        else
            warn "Bug NOT caught in top-3 selected tests for $classname"
        fi
        restore_bug_patch "$repo" "$patch_file"
    else
        warn "No patch available for $repo — add scripts/bugs/$repo/*.patch"
    fi

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

    cd "$dir"
    inject_maven_plugin "$repo" "$module"

    # Base args (no module selector) shared by all goals
    local compiler_args
    compiler_args=$(detect_compiler_args "$repo")
    local extra_mvn_args=""
    type detect_extra_mvn_args &>/dev/null && extra_mvn_args=$(detect_extra_mvn_args "$repo")
    local base_args=(-B --fail-at-end -Denforcer.skip=true -Djacoco.skip=true
                     -Dmaven.build.cache.enabled=false)
    [[ -n "$compiler_args" ]] && base_args+=($compiler_args)
    if [[ -n "$extra_mvn_args" ]]; then
        # Use eval to correctly handle quoted args like -pl '!module-name'
        eval "base_args+=($extra_mvn_args)"
    fi
    [[ -n "$pkg" ]] && base_args+=("-Dtestorder.includePackages=$pkg")

    # Build args for test+compile goals: need -pl $module -am so dependencies compile
    local mvn_args=("${base_args[@]}")
    [[ -n "$module" ]] && mvn_args+=(-pl "$module" -am)

    # 1. Clean
    log "Step 1: Clean test-order data"
    rm -rf "$dir/.test-order" "$dir/target/test-order-deps" 2>/dev/null
    find "$dir" -maxdepth 2 -name ".test-order.precheck-*" -type d -exec rm -rf {} + 2>/dev/null || true
    [[ -n "$module" ]] && rm -rf "$dir/$module/.test-order" "$dir/$module/target/test-order-deps" 2>/dev/null
    ok "Cleaned test-order data"

    # 2. Learn (3 runs to build history)
    for i in 1 2 3; do
        log "Step 2.$i: Learn run $i/3"
        mvn_learn "${mvn_args[@]}" \
            2>&1 | tee "$results/full-learn-$i.log" | tail -3 || warn "Learn run $i had test failures (deps may still be captured)"
    done

    # Check if index was actually produced
    local idx=$(find "$dir" ! -path "*precheck*" -name "test-dependencies.lz4" -print -quit 2>/dev/null)
    if [[ -z "$idx" ]]; then
        warn "No dependency index produced (project may use JUnit 4). Skipping remaining steps."
        remove_maven_plugin "$repo" "$module"
        return 0
    fi
    ok "Index created: $idx ($(du -h "$idx" | cut -f1))"

    # Determine which module actually owns the index.  Command-only goals (dump,
    # show-order, select, etc.) must run on that exact module so that
    # ${project.basedir} resolves to the directory containing .test-order/.
    # We do NOT pass -am here because these goals don't compile anything.
    local idx_dir
    idx_dir=$(dirname "$idx")        # .../repo/<mod>/.test-order  OR  .../repo/.test-order
    idx_dir=$(dirname "$idx_dir")    # .../repo/<mod>              OR  .../repo
    local idx_module=""
    if [[ "$idx_dir" != "$dir" ]]; then
        idx_module=$(basename "$idx_dir")
    fi

    # Args for command-only goals (no -am, uses idx_module not $module)
    local cmd_args=("${base_args[@]}")
    [[ -n "$idx_module" ]] && cmd_args+=(-pl "$idx_module")

    # Args for goals that compile+run tests: add -am so dependencies are built
    local test_cmd_args=("${cmd_args[@]}")
    [[ -n "$idx_module" ]] && test_cmd_args+=(-am)

    # Prefer source files from the instrumented module for changed.classes hints
    local src_class
    src_class=$(detect_source_class "$repo" "${idx_module:-$module}")

    # 3. Dump state
    log "Step 3: Dump state"
    mvn me.bechberger:test-order-maven-plugin:dump "${cmd_args[@]}" 2>&1 | tee "$results/full-dump.log" | tail -20 || warn "Dump failed"

    # Also dump as TSV for discriminating-power scoring in find_bug_targets (S15)
    local dump_tsv="$results/full-dump.tsv"
    mvn me.bechberger:test-order-maven-plugin:dump "${cmd_args[@]}" \
        -Dtestorder.dump.format=tsv -Dtestorder.dump.output="$dump_tsv" -q 2>/dev/null || true

    # 4. Show order with a specific change
    log "Step 4: Show order"
    mvn me.bechberger:test-order-maven-plugin:show-order \
        -Dtestorder.changeMode=explicit \
        -Dtestorder.changed.classes="$src_class" \
        "${cmd_args[@]}" \
        2>&1 | tee "$results/full-show-order.log" | tail -20 || warn "Show-order failed"

    # 5. Select
    log "Step 5: Select top-5"
    mvn clean me.bechberger:test-order-maven-plugin:affected test \
        -Dtestorder.changeMode=explicit \
        -Dtestorder.changed.classes="$src_class" \
        -Dtestorder.select.topN=5 \
        -Dtestorder.mode=skip \
        "${test_cmd_args[@]}" \
        2>&1 | tee "$results/full-select.log" | tail -10 || warn "Select failed"

    # 6. Run remaining
    log "Step 6: Run remaining"
    mvn me.bechberger:test-order-maven-plugin:run-remaining test \
        "${test_cmd_args[@]}" \
        2>&1 | tee "$results/full-remaining.log" | tail -5 || warn "Run-remaining failed"

    # 7. Inject bug and verify detection
    log "Step 7: Bug injection verification"
    # Safety: if a prior run crashed with a patch applied, undo it first
    local bugs_dir_7="$BUGS_DIR/$repo"
    if [[ -d "$bugs_dir_7" ]]; then
        for pf in "$bugs_dir_7"/*.patch; do
            [[ -f "$pf" ]] || continue
            patch -d "$dir" -p1 -R --no-backup-if-mismatch --forward -s < "$pf" >/dev/null 2>&1 || true
        done
        # Remove any .rej files left by failed reverse-patch attempts
        find "$dir" -name "*.rej" -delete 2>/dev/null || true
    fi
    local bug_injected=false
    local patch_result
    patch_result=$(inject_bug_patch "$repo") || true
    if [[ -n "$patch_result" ]]; then
        local classname patch_file
        classname=$(echo "$patch_result" | cut -f1)
        patch_file=$(echo "$patch_result" | cut -f2)
        # If the repo uses the Maven Build Cache Extension, purge all local cache
        # entries so the patched source is actually recompiled.  Without this,
        # the cache restores pre-patch bytecode and the bug goes undetected.
        if [[ -f "$dir/.mvn/maven-build-cache-config.xml" && -d "$HOME/.m2/build-cache" ]]; then
            local root_pom="$dir/pom.xml"
            # Extract groupId from root pom (falls back to empty → skip purge)
            local groupId
            groupId=$(grep -m1 '<groupId>' "$root_pom" 2>/dev/null | sed 's|.*<groupId>||;s|</groupId>.*||;s|[[:space:]]||g' || echo "")
            if [[ -n "$groupId" ]]; then
                find "$HOME/.m2/build-cache" -path "*/${groupId}/*" -delete 2>/dev/null || true
                log "  Purged local build cache for groupId=$groupId (step 7 bug injection)"
            fi
        fi
        local bug_out
        bug_out=$(mvn clean me.bechberger:test-order-maven-plugin:affected test \
            -Dtestorder.changeMode=explicit \
            -Dtestorder.changed.classes="$classname" \
            -Dtestorder.select.topN=3 \
            -Dtestorder.mode=skip \
            "${test_cmd_args[@]}" \
            2>&1 | tee "$results/full-bug-select.log") || true
        echo "$bug_out" | tail -10
        # NOTE: grep on the file rather than $bug_out — $bug_out may be missing
        # some bytes due to bash command-substitution buffering in multi-module builds.
        local bug_log="$results/full-bug-select.log"
        if log_has_test_failures "$bug_log"; then
            ok "Bug caught in top-3 selected tests! (step 7)"
            bug_injected=true
        elif grep -q "None of the explicitly specified changed classes" "$bug_log" 2>/dev/null; then
            warn "Bug class $classname not in dependency index"
            local n_tests
            n_tests=$(grep -c $'\t' "$results/full-dump.log" 2>/dev/null || echo "?")
            log "    Index has ~$n_tests test entries"
        elif ! log_has_tests_run "$bug_log"; then
            warn "Bug result unknown for $classname — build failed before tests ran (step 7)"
            bug_injected=true
        else
            warn "Bug not caught in top-3 selected tests for $classname (step 7)"
            bug_injected=true
            # S6: diagnosis
            log "  Step 7 diagnosis for $classname:"
            if [[ -f "$results/full-bug-select.log" ]]; then
                local selected_tests
                selected_tests=$(grep -oE '[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+Test[a-zA-Z0-9_]*' \
                    "$results/full-bug-select.log" 2>/dev/null | sort -u | head -10 || true)
                [[ -n "$selected_tests" ]] && log "  Selected tests:" && echo "$selected_tests" | sed 's/^/    /'
            fi
            log "  Top-5 scorers (show-order):"
            mvn me.bechberger:test-order-maven-plugin:show-order \
                -Dtestorder.changeMode=explicit \
                -Dtestorder.changed.classes="$classname" \
                "${cmd_args[@]}" -q 2>&1 \
                | grep -E '^\s+[0-9]' | head -5 | sed 's/^/    /' || true
        fi
        restore_bug_patch "$repo" "$patch_file"
    else
        warn "No patch available for $repo — skipping bug injection (add scripts/bugs/$repo/*.patch)"
    fi
    # 8. Compact state
    log "Step 8: Compact"
    mvn me.bechberger:test-order-maven-plugin:compact "${cmd_args[@]}" 2>&1 | tail -3 || warn "Compact failed"

    # 9. Export JSON
    log "Step 9: Export JSON"
    mvn me.bechberger:test-order-maven-plugin:export-json "${cmd_args[@]}" 2>&1 | tee "$results/full-export.log" | tail -5 || warn "Export failed"

    # 10. Diagnose
    log "Step 10: Diagnose"
    mvn me.bechberger:test-order-maven-plugin:diagnose "${cmd_args[@]}" 2>&1 | tee "$results/full-diagnose.log" | tail -20 || warn "Diagnose failed"

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

    local mvn_args=(-B -Denforcer.skip=true -Djacoco.skip=true
                    -Dmaven.build.cache.enabled=false)
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
            order)   phase_order_gradle "$repo" ;;
            select)  phase_select_gradle "$repo" ;;
            bugs)    phase_bugs_gradle "$repo" ;;
            full)    phase_full_gradle "$repo" ;;
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
        regression)
            phase_install
            local reg_pass=0 reg_fail=0
            for repo in "${REGRESSION_REPOS[@]}"; do
                if [[ ! -d "$THIRD_PARTY/$repo" ]]; then
                    warn "regression: $repo not cloned — skipping"
                    continue
                fi
                if run_for_repo "$repo" "full"; then
                    ok "PASS: $repo"
                    (( reg_pass++ )) || true
                else
                    warn "FAIL: $repo"
                    (( reg_fail++ )) || true
                fi
            done
            section "REGRESSION: $reg_pass passed, $reg_fail failed"
            [[ "$reg_fail" -eq 0 ]]
            ;;
        *)
            echo "Usage: $0 [PHASE] [REPO]"
            echo ""
            echo "Phases:"
            echo "  install    - Install test-order to local Maven repo"
            echo "  learn      - Run learn mode on all repos"
            echo "  order      - Run order mode (requires prior learn)"
            echo "  select     - Run select + run-remaining"
            echo "  tiered     - Run 3-tier CI pipeline"
            echo "  bugs       - Inject synthetic bugs and verify detection"
            echo "  auto       - Run auto mode (learn→order transition)"
            echo "  full       - Complete workflow (all of the above)"
            echo "  quick      - Full workflow on small repos only"
            echo "  medium     - Full workflow on medium repos"
            echo "  large      - Full workflow on large repos"
            echo "  all        - Everything on every repo"
            echo "  regression - Full workflow on known-working regression set"
            echo ""
            echo "Repos: ${MAVEN_REPOS[*]} ${GRADLE_REPOS[*]}"
            exit 1
            ;;
    esac

    section "DONE — Results in $RESULTS_DIR"
}

main "$@"
