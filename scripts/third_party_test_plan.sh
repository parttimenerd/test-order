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

# Portable in-place sed: macOS requires -i '' while GNU sed (Linux) uses -i.
# Use a distinct .sedi-tmp suffix so the temp file never collides with the
# intentional .bak files that inject_gradle_plugin relies on for restoration.
sedi() {
    local args=("$@")
    local file="${args[-1]}"
    sed -i.sedi-tmp "${args[@]}"
    rm -f "${file}.sedi-tmp"
}

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
    "jackson-databind"
    "gson"
    "jsoup"
    "pdfbox"
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
    "cds4j"
    "assertj"
    "awaitility"
    "classgraph"
    "commons-codec"
    "commons-configuration"
    "commons-csv"
    "commons-dbcp"
    "commons-geometry"
    "commons-math"
    "commons-statistics"
    "dubbo"
    "eclipse-collections"
    "guice"
    "httpcomponents-client"
    "jackson-annotations"
    "jackson-core"
    "jimfs"
    "joda-time"
    "logbook"
    "netty"
    "slf4j"
    "vertx-web"
    "commons-email"
    "commons-validator"
    "commons-numbers"
    "shiro"
    "jetty"
    "hazelcast"
    "truth"
    "undertow"
    "commons-lang3-apache"
    "jsoup-alt"
    "logging-log4j-alt"
    "commons-rng"
    "problem"
    "mapstruct"
    "gson-alt"
    "smallrye-mutiny"
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
    "picocli"
    "neonbee"
    "resilience4j"
    "caffeine"
    "quartz"
    "reactor-core"
    "opentelemetry"
    "micrometer"
    "byte-buddy"
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

PLUGIN_VERSION="0.1.0"
PLUGIN_GROUP="me.bechberger"
PLUGIN_ARTIFACT="test-order-maven-plugin"
PREPARE_GOAL="$PLUGIN_GROUP:$PLUGIN_ARTIFACT:prepare"

# On Linux, JDK 21+ dropped --release 8 support.  Many OSS projects still target
# Java 8/11.  If the current JDK is 21+ and a JDK 17 is available via SDKMAN,
# use it as the default for Maven invocations so javac --release 8/11 works.
if [[ "$(uname)" != "Darwin" && -z "${JAVA_HOME_OVERRIDE_SET:-}" ]]; then
    _jdk17="${HOME}/.sdkman/candidates/java/17.0.13-sapmchn"
    if [[ -x "${_jdk17}/bin/javac" ]]; then
        export JAVA_HOME="${_jdk17}"
        export PATH="${_jdk17}/bin:${PATH}"
        export JAVA_HOME_OVERRIDE_SET=1
    fi
    unset _jdk17
fi

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
    idx_file=$(find -L "$dir" ! -path "*precheck*" -name "test-dependencies.lz4" -print -quit 2>/dev/null)
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
    # Check for a per-repo override before running the heuristic.
    # "NONE" means: run the full reactor, do not add -pl at all.
    local override
    override=$(detect_module_override "$repo")
    if [[ -n "$override" ]]; then
        echo "$override"
        return
    fi
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
    # Mocha/chai format: "  N failing" where N != 0
    if grep -qE "^\s+[1-9][0-9]* failing" "$log_file" 2>/dev/null; then
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
    # Mocha/chai format: "N passing" or "N failing" (even 0 passing means tests attempted)
    grep -qE "^\s+[0-9]+ (passing|failing|pending)" "$log_file" 2>/dev/null && return 0
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

    # Per-repo hook: if the repo already ships test-order as a <extensions>true</extensions>
    # plugin in its pom.xml, skip injection to avoid double-loading the lifecycle participant.
    if type detect_plugin_already_configured &>/dev/null; then
        local already
        already=$(detect_plugin_already_configured "$repo" 2>/dev/null || echo "")
        if [[ "$already" == "true" ]]; then
            ok "$repo already has test-order configured in pom.xml — skipping injection"
            return
        fi
    fi

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

    # Skip injection if already injected:
    #   (a) bak file exists — normal case when injection is in place
    #   (b) file already contains the plugin id — leftover from a prior run where cleanup failed
    if [[ -f "$build_file.bak" ]]; then
        ok "Plugin already injected in $repo (bak exists) — skipping"
        return 0
    fi
    if grep -q 'me.bechberger.test-order' "$build_file" 2>/dev/null; then
        warn "Plugin id already present in $build_file (leftover from prior run) — cleaning before re-injection"
        git -C "$dir" checkout -- "$(basename "$build_file")" 2>/dev/null || true
        # If git checkout didn't clean it (not a git repo), strip the injected lines with sed
        if grep -q 'me.bechberger.test-order' "$build_file" 2>/dev/null; then
            warn "git restore failed — stripping injected lines from $build_file with sed"
            sedi "/me[._:]bechberger[._:]test-order/d" "$build_file"
            sedi "/me[._:]bechberger:test-order-gradle-plugin/d" "$build_file"
            sedi "/^subprojects { plugins\.withId/d" "$build_file"
            # Remove trailing orphaned braces left by a prior dependencyResolutionManagement append
            python3 -c "
import sys
path = sys.argv[1]
with open(path) as f: lines = f.readlines()
while lines and lines[-1].strip() in ('', '}'):
    candidate = lines.pop()
    if candidate.strip() == '}':
        # Check preceding context — if last real line looks like a regular statement, it's orphaned
        prev_real = [l for l in lines if l.strip()]
        if not prev_real or prev_real[-1].strip() not in ('}', ''):
            pass  # orphaned, drop it
        else:
            lines.append(candidate); break  # real closing brace, keep it
while lines and lines[-1].strip() == '':
    lines.pop()
lines.append('\n')
with open(path, 'w') as f: f.writelines(lines)
" "$build_file" 2>/dev/null || true
            if grep -q 'me.bechberger.test-order' "$build_file" 2>/dev/null; then
                warn "Cannot clean $build_file — skipping injection to avoid double plugin"
                return 0
            fi
        fi
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
        # 1. pluginManagement: inject mavenLocal() into existing block or prepend new block.
        # Use Python with brace-counting to correctly handle nested blocks (e.g. plugins{} inside
        # pluginManagement{} which would break simple regex like [^}]*).
        # Guard: only inject if mavenLocal is NOT already inside pluginManagement (it might exist
        # in dependencyResolutionManagement.repositories which doesn't help plugin resolution).
        python3 -c "
import re
content = open('$settings_file').read()

def find_block_end(text, start):
    'Return index of the closing } matching the { at text[start-1] (start is after the {).'
    depth = 1
    i = start
    while i < len(text) and depth > 0:
        if text[i] == '{':
            depth += 1
        elif text[i] == '}':
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return len(text)

pm_match = re.search(r'pluginManagement\s*\{', content)
if pm_match:
    pm_body_start = pm_match.end()
    pm_end = find_block_end(content, pm_body_start)
    pm_body = content[pm_body_start:pm_end]
    # Already has mavenLocal inside pluginManagement — nothing to do
    if 'mavenLocal' in pm_body:
        open('$settings_file', 'w').write(content)
        exit(0)
    repo_match = re.search(r'repositories\s*\{', pm_body)
    if repo_match:
        # Inject mavenLocal() right after 'repositories {' inside pluginManagement
        insert_pos = pm_body_start + repo_match.end()
        content = content[:insert_pos] + '\n        mavenLocal()' + content[insert_pos:]
    else:
        # No repositories block inside pluginManagement — add one after the opening {
        insert_pos = pm_body_start
        content = content[:insert_pos] + '\n    repositories {\n        mavenLocal()\n        gradlePluginPortal()\n        mavenCentral()\n    }' + content[insert_pos:]
else:
    # No pluginManagement block — prepend a new one (only if mavenLocal not already there)
    if 'mavenLocal' not in content:
        content = 'pluginManagement {\n    repositories {\n        mavenLocal()\n        gradlePluginPortal()\n        mavenCentral()\n    }\n}\n' + content

open('$settings_file', 'w').write(content)
"
        # 2. dependencyResolutionManagement: add mavenLocal() if not already there,
        # so test-order JARs (agent, core) resolve at runtime/testRuntime scope.
        # NOTE: do NOT check `grep -q mavenLocal` across the whole file — that would
        # skip this step if mavenLocal appears only in pluginManagement.repositories,
        # which does NOT cover dependency resolution.  Check only the DRM block.
        local _drm_has_local
        _drm_has_local=$(python3 -c "
import re, sys
content = open('$settings_file').read()
drm = re.search(r'dependencyResolutionManagement\s*\{', content)
if drm:
    depth, i = 1, drm.end()
    while i < len(content) and depth > 0:
        if content[i] == '{': depth += 1
        elif content[i] == '}': depth -= 1
        i += 1
    print('yes' if 'mavenLocal' in content[drm.start():i] else 'no')
else:
    print('no')
" 2>/dev/null || echo "no")
        if [[ "$_drm_has_local" != "yes" ]]; then
            printf '\ndependencyResolutionManagement { repositories { mavenLocal() } }\n' >> "$settings_file"
        fi
    elif [[ -f "$dir/settings.gradle" ]]; then
        settings_file="$dir/settings.gradle"
        cp "$settings_file" "$settings_file.bak"
        if ! grep -q "mavenLocal" "$settings_file"; then
            if grep -q "pluginManagement" "$settings_file"; then
                if grep -q "repositories" "$settings_file"; then
                    sedi '/repositories {/a\
        mavenLocal()
' "$settings_file"
                else
                    sedi '/pluginManagement {/a\
    repositories {\
        mavenLocal()\
        gradlePluginPortal()\
        mavenCentral()\
    }
' "$settings_file"
                fi
            else
                sedi '1i\
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
        # Kotlin DSL multi-module: use buildscript { classpath } so the plugin jar is on the
        # build classpath for ALL subprojects.  apply(plugin = "...") in the subprojects block
        # looks up the plugin via the buildscript classpath, NOT the plugins {} resolution
        # mechanism, so plugins { id() apply false } would cause "Plugin not found" errors.
        # If the root build file already has buildscript { ... } inject the classpath dep into
        # it; otherwise prepend a fresh buildscript {} block before the first plugins {} line.
        if grep -q "^buildscript {" "$build_file"; then
            python3 /dev/stdin "$build_file" "$PLUGIN_VERSION" << 'PYEOF'
import sys
build_file = sys.argv[1]
plugin_version = sys.argv[2]
lines = open(build_file).read().splitlines(keepends=True)
classpath_line = f'    classpath("me.bechberger:test-order-gradle-plugin:{plugin_version}")\n'
maven_local_line = '    repositories { maven { url = uri("file://${System.getProperty(\"user.home\")}/.m2/repository") } }\n'

in_buildscript = False
depth = 0
found_dep = False
found_repos = False
has_maven_local = False
bs_start = -1
bs_end = -1
dep_insert_after = -1
repos_insert_after = -1

for i, line in enumerate(lines):
    stripped = line.lstrip()
    is_comment = stripped.startswith('//')
    if not in_buildscript:
        if line.rstrip() == 'buildscript {':
            in_buildscript = True
            depth = 1
            bs_start = i
    else:
        if not is_comment:
            depth += line.count('{') - line.count('}')
        if depth <= 0:
            bs_end = i
            break
        if not is_comment:
            if 'repositories {' in line and depth == 2:
                found_repos = True
                repos_insert_after = i
            if 'mavenLocal()' in line or 'maven {' in line:
                has_maven_local = True
            if 'dependencies {' in line and depth == 2:
                found_dep = True
                dep_insert_after = i

out = list(lines)
if dep_insert_after >= 0:
    out.insert(dep_insert_after + 1, classpath_line)
elif bs_end >= 0 and not found_dep:
    out.insert(bs_end, '    dependencies {\n' + classpath_line + '    }\n')
if not found_repos and bs_start >= 0:
    out.insert(bs_start + 1, maven_local_line)
elif found_repos and not has_maven_local and repos_insert_after >= 0:
    out.insert(repos_insert_after + 1, '        mavenLocal()\n')

open(build_file, 'w').write(''.join(out))
PYEOF
            log "  → injected test-order classpath into existing buildscript{} in $build_file"
        else
            # Prepend a buildscript{} block — Kotlin DSL syntax
            local maven_local_kts='buildscript {\n    repositories { mavenLocal() }\n    dependencies { classpath("me.bechberger:test-order-gradle-plugin:'"$PLUGIN_VERSION"'") }\n}\n\n'
            python3 /dev/stdin "$build_file" "$maven_local_kts" << 'PYEOF'
import sys
build_file = sys.argv[1]
prepend = sys.argv[2].replace('\\n', '\n')
content = open(build_file).read()
# Insert before first top-level plugins { or import statement that precedes it
import re
# Find the first plugins { at the start of a line
m = re.search(r'^plugins\s*\{', content, re.MULTILINE)
if m:
    content = content[:m.start()] + prepend + content[m.start():]
else:
    content = prepend + content
open(build_file, 'w').write(content)
PYEOF
            log "  → prepended buildscript{} with test-order classpath in $build_file"
        fi
    elif [[ "$build_file" == *.kts ]]; then
        # Kotlin DSL single-module: root project has Java plugin, apply directly.
        sedi '/^plugins {/a\
    id("me.bechberger.test-order") version "'"$PLUGIN_VERSION"'"
' "$build_file"
    elif [[ "$is_multi_module" == "true" ]]; then
        # Groovy DSL multi-module: inject into existing buildscript{} if present,
        # otherwise prepend a new buildscript{} before the first plugins{} line.
        # Gradle only allows ONE buildscript{} block; a second one causes artifact
        # instrumentation failures (e.g. hibernate-orm which already has buildscript{}).
        if grep -q "^buildscript {" "$build_file"; then
            # Inject classpath dep into the existing buildscript{} block using a
            # line-by-line parser that tracks brace depth (handles commented-out blocks).
            # Gradle only allows ONE buildscript{} block; a second one causes artifact
            # instrumentation failures (e.g. hibernate-orm which already has buildscript{}).
            python3 /dev/stdin "$build_file" "$PLUGIN_VERSION" << 'PYEOF'
import sys
build_file = sys.argv[1]
plugin_version = sys.argv[2]
lines = open(build_file).read().splitlines(keepends=True)
classpath_line = f'\tclasspath "me.bechberger:test-order-gradle-plugin:{plugin_version}"\n'
maven_local_line = '\trepositories { mavenLocal() }\n'

in_buildscript = False
depth = 0
found_dep = False
found_repos = False
has_maven_local = False
bs_start = -1
bs_end = -1
dep_insert_after = -1
repos_insert_after = -1  # first line inside repositories{} block

for i, line in enumerate(lines):
    stripped = line.lstrip()
    is_comment = stripped.startswith('//')
    if not in_buildscript:
        if line.rstrip() == 'buildscript {':
            in_buildscript = True
            depth = 1
            bs_start = i
    else:
        if not is_comment:
            depth += line.count('{') - line.count('}')
        if depth <= 0:
            bs_end = i
            break
        if not is_comment:
            if 'repositories {' in line and depth == 2:
                found_repos = True
                repos_insert_after = i  # insert mavenLocal() after this line
            if 'mavenLocal()' in line and found_repos:
                has_maven_local = True
            if 'dependencies {' in line and depth == 2:
                found_dep = True
                dep_insert_after = i

out = list(lines)
if dep_insert_after >= 0:
    out.insert(dep_insert_after + 1, classpath_line)
elif bs_end >= 0 and not found_dep:
    out.insert(bs_end, '\tdependencies {\n' + classpath_line + '\t}\n')
if not found_repos and bs_start >= 0:
    out.insert(bs_start + 1, maven_local_line)
elif found_repos and not has_maven_local and repos_insert_after >= 0:
    # Add mavenLocal() inside the existing repositories{} block
    out.insert(repos_insert_after + 1, '\t\tmavenLocal()\n')

open(build_file, 'w').write(''.join(out))
PYEOF
            log "  → injected test-order classpath into existing buildscript{} in $build_file"
        else
            # No existing buildscript{}; prepend one before plugins{}
            sedi '/^plugins {/i\
buildscript {\
    repositories { mavenLocal() }\
    dependencies { classpath "me.bechberger:test-order-gradle-plugin:'"$PLUGIN_VERSION"'" }\
}\

' "$build_file"
        fi
    else
        sedi '/^plugins {/a\
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
            # Use pluginManager.withPlugin("java") so the plugin is only applied to subprojects
            # that actually have the Java plugin. Subprojects without Java (e.g. BOM-only,
            # build-logic plugins) have null testClassesDirs and would crash testOrderAffected.
            # Note: plugins.withId("java") { apply(...) } does NOT work in Kotlin DSL — the
            # lambda `this` is Plugin<*>, not Project, so apply() resolves to the wrong overload.
            # pluginManager.withPlugin() correctly scopes to the project context.
            if [[ "$use_settings_repos" == "true" ]]; then
                printf '\nsubprojects { pluginManager.withPlugin("java") { apply(plugin = "me.bechberger.test-order") } }\n' >> "$build_file"
            else
                printf '\nsubprojects { repositories { mavenLocal() }; pluginManager.withPlugin("java") { apply(plugin = "me.bechberger.test-order") } }\n' >> "$build_file"
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
        sedi "/$remove_pattern/d" "$settings_file"
        log "  → removed lines matching '$remove_pattern' from $settings_file"
    fi

    # Override the daemon JVM vendor in gradle/gradle-daemon-jvm.properties if needed.
    local daemon_vendor=""
    type detect_gradle_daemon_jvm_vendor &>/dev/null && daemon_vendor=$(detect_gradle_daemon_jvm_vendor "$repo" 2>/dev/null || echo "")
    if [[ -n "$daemon_vendor" ]]; then
        local daemon_jvm_file="$dir/gradle/gradle-daemon-jvm.properties"
        if [[ -f "$daemon_jvm_file" ]]; then
            cp "$daemon_jvm_file" "$daemon_jvm_file.bak"
            sedi "s/^toolchainVendor=.*/toolchainVendor=$daemon_vendor/" "$daemon_jvm_file"
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

    # Per-repo JAVA_HOME override (e.g. spring-ai needs JDK 21, not the global JDK 17 default)
    local maven_java_home=""
    type detect_maven_java_home &>/dev/null && maven_java_home=$(detect_maven_java_home "$repo")
    if [[ -n "$maven_java_home" ]]; then
        export JAVA_HOME="$maven_java_home"
        export PATH="$maven_java_home/bin:$PATH"
    fi

    local compiler_args
    compiler_args=$(detect_compiler_args "$repo")
    local extra_mvn_args=""
    type detect_extra_mvn_args &>/dev/null && extra_mvn_args=$(detect_extra_mvn_args "$repo")
    local mvn_args=(-B -Denforcer.skip=true -Drat.skip=true -Djacoco.skip=true
                    -Dmaven.build.cache.enabled=false)
    [[ -n "$compiler_args" ]] && mvn_args+=($compiler_args)
    if [[ -n "$extra_mvn_args" ]]; then
        eval "mvn_args+=($extra_mvn_args)"
    fi
    [[ -n "$pkg" ]] && mvn_args+=("-Dtestorder.includePackages=$pkg")
    [[ -n "$module" && "$module" != "NONE" ]] && mvn_args+=(-pl "$module" -am)

    # Pre-learn: install jars first if required by this repo (e.g. cross-module test deps)
    local prelearn_goals=""
    type detect_maven_prelearn_goals &>/dev/null && prelearn_goals=$(detect_maven_prelearn_goals "$repo")
    if [[ -n "$prelearn_goals" ]]; then
        log "Running pre-learn install: mvn $prelearn_goals ${mvn_args[*]}"
        # shellcheck disable=SC2086
        mvn $prelearn_goals "${mvn_args[@]}" \
            2>&1 | tee "$results/prelearn-install.log" | tail -3 || \
            warn "Pre-learn install had failures (continuing)"
    fi

    # Run learn
    log "Running: mvn_learn ${mvn_args[*]}"
    if mvn_learn "${mvn_args[@]}" \
        2>&1 | tee "$results/learn.log" | tail -5; then
        ok "Learn succeeded for $repo"
    else
        warn "Learn had failures (tests may fail, deps still captured)"
    fi

    # Check if index was created
    local idx=$(find -L "$dir" ! -path "*precheck*" -name "test-dependencies.lz4" -print -quit)
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
    local learn_extra_args
    learn_extra_args=$(type detect_gradle_learn_extra_args &>/dev/null \
        && detect_gradle_learn_extra_args "$repo" 2>/dev/null || echo "")
    local override_java_home
    override_java_home=$(detect_gradle_java_home "$repo" 2>/dev/null || echo "")

    log "Running: ./gradlew cleanTest test --no-build-cache --no-configuration-cache -Dtestorder.mode=learn${extra_args:+ $extra_args}${learn_extra_args:+ $learn_extra_args}"
    # shellcheck disable=SC2086
    if JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew cleanTest test -Dtestorder.mode=learn --no-daemon --no-build-cache --no-configuration-cache \
        $extra_args ${learn_extra_args} \
        2>&1 | tee "$results/learn.log" | tail -5; then
        ok "Learn succeeded for $repo"
    else
        warn "Learn had failures for $repo"
    fi

    local idx=$(find -L "$dir" ! -path "*precheck*" -name "test-dependencies.lz4" -print -quit)
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
    idx_file=$(find -L "$dir" ! -path "*precheck*" -name "test-dependencies.lz4" -print -quit 2>/dev/null)
    if [[ -n "$idx_file" ]]; then
        local override_java_home
        override_java_home=$(detect_gradle_java_home "$repo" 2>/dev/null || echo "")
        local extra_args
        extra_args=$(detect_gradle_extra_args "$repo" 2>/dev/null || echo "")
        local from_index
        # shellcheck disable=SC2086
        from_index=$(JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew testOrderDump \
            --no-daemon --no-build-cache --no-configuration-cache \
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

    log "Running: ./gradlew testOrderShow"
    # shellcheck disable=SC2086
    JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew testOrderShow \
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

    log "Running: ./gradlew testOrderAffected -Dtestorder.affected.topN=5"
    # shellcheck disable=SC2086
    JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew testOrderAffected \
        --no-build-cache --no-configuration-cache \
        -Dtestorder.changeMode=explicit \
        -Dtestorder.changed.classes="$src_class" \
        -Dtestorder.affected.topN=5 \
        -Dtestorder.affected.randomM=2 \
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
    idx=$(find -L "$dir" ! -path "*precheck*" -name "test-dependencies.lz4" -print -quit 2>/dev/null)
    if [[ -z "$idx" ]]; then
        log "No index found, running learn first..."
        # shellcheck disable=SC2086
        JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew cleanTest test \
            --no-build-cache --no-configuration-cache \
            -Dtestorder.mode=learn \
            $extra_args \
            2>&1 | tee "$results/bug-learn.log" | tail -3 || true
        idx=$(find -L "$dir" ! -path "*precheck*" -name "test-dependencies.lz4" -print -quit 2>/dev/null)
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
            task_prefix=""   # root-level tasks: cleanTestOrderAffected testOrderAffected
        elif [[ -n "$subproject_prefix_override" ]]; then
            task_prefix="$subproject_prefix_override"
        else
            task_prefix=":${first_segment}:"
        fi
    fi

    local select_task="${task_prefix}testOrderAffected"

    # shellcheck disable=SC2086
    # --rerun forces re-execution of testOrderAffected: after patch the source changes, but
    # Gradle's incremental tracking may consider testOrderAffected UP-TO-DATE when production
    # class files change (class dirs on classpath aren't always tracked).
    JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew "$select_task" \
        --rerun "$select_task" \
        --no-build-cache --no-configuration-cache \
        -Dtestorder.changeMode=explicit \
        -Dtestorder.changed.classes="$classname" \
        -Dtestorder.affected.topN=3 \
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
    find -L "$dir" -name "test-dependencies.lz4" ! -path "*precheck*" -delete 2>/dev/null || true
    find "$dir" -name "test-order-deps" -type d -exec rm -rf {} + 2>/dev/null || true
    ok "Cleaned test-order data"

    # 2. Learn (3 runs)
    local learn_extra_args
    learn_extra_args=$(type detect_gradle_learn_extra_args &>/dev/null \
        && detect_gradle_learn_extra_args "$repo" 2>/dev/null || echo "")
    for i in 1 2 3; do
        log "Step 2.$i: Learn run $i/3"
        # shellcheck disable=SC2086
        JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew cleanTest test \
            --no-build-cache --no-configuration-cache \
            -Dtestorder.mode=learn \
            $extra_args ${learn_extra_args} \
            2>&1 | tee "$results/full-learn-$i.log" | tail -3 || warn "Learn run $i had failures"
    done

    local idx
    idx=$(find -L "$dir" ! -path "*precheck*" -name "test-dependencies.lz4" -print -quit 2>/dev/null)
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
        --no-daemon --no-build-cache --no-configuration-cache \
        $extra_args \
        2>&1 | tee >(head -c 5242880 > "$results/full-dump.log") | tail -20 || warn "Dump failed"

    # 4. Show order (use testOrderShow; testOrderShowOrder is deprecated and doesn't filter excluded/abstract tests)
    log "Step 4: Show order"
    # shellcheck disable=SC2086
    JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew testOrderShow \
        --no-daemon --no-build-cache --no-configuration-cache \
        -Dtestorder.changeMode=explicit \
        -Dtestorder.changed.classes="$src_class" \
        $extra_args \
        2>&1 | tee "$results/full-show-order.log" | tail -20 || warn "Show-order failed"

    # 5. Select
    log "Step 5: Select top-5"
    # shellcheck disable=SC2086
    JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew cleanTest testOrderAffected \
        --no-daemon --no-build-cache --no-configuration-cache \
        -Dtestorder.changeMode=explicit \
        -Dtestorder.changed.classes="$src_class" \
        -Dtestorder.affected.topN=5 \
        $extra_args \
        2>&1 | tee "$results/full-select.log" | tail -10 || warn "Select failed"

    # 6. Run remaining
    log "Step 6: Run remaining"
    # shellcheck disable=SC2086
    JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew testOrderRunRemaining \
        --no-daemon --no-build-cache --no-configuration-cache \
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
            JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew "${bp_task_prefix}testOrderAffected" \
                --no-daemon --no-build-cache --no-configuration-cache \
                -Dtestorder.changeMode=explicit \
                -Dtestorder.changed.classes="$classname" \
                -Dtestorder.affected.topN=3 \
                $extra_args \
                2>&1 | tee "$bug_log" | tail -10 || true
            if log_has_test_failures "$bug_log"; then
                ok "Bug caught in top-3 selected tests! (step 7)"
            elif ! log_has_tests_run "$bug_log"; then
                warn "Bug result unknown — build failed before tests ran (step 7)"
            else
                warn "Bug not caught in top-3 for $classname (step 7)"
            fi
            # Step 7b: SA auto-mode (changeMode=uncommitted) — verify SA detects the
            # change from git diff without an explicit class name.
            if git -C "$dir" rev-parse --git-dir >/dev/null 2>&1; then
                log "Step 7b: SA auto-mode (changeMode=uncommitted)"
                local sa_log="$results/full-bug-sa-select.log"
                # shellcheck disable=SC2086
                JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew "${bp_task_prefix}testOrderAffected" \
                    --no-daemon --no-build-cache --no-configuration-cache \
                    -Dtestorder.changeMode=uncommitted \
                    -Dtestorder.affected.topN=5 \
                    $extra_args \
                    2>&1 | tee "$sa_log" | tail -5 || true
                if log_has_test_failures "$sa_log"; then
                    ok "SA auto-mode caught bug (step 7b)!"
                elif grep -q "No changed classes detected\|no changed classes" "$sa_log" 2>/dev/null; then
                    warn "SA auto-mode: no changed classes detected from git diff (step 7b)"
                elif ! log_has_tests_run "$sa_log"; then
                    warn "SA auto-mode: build failed before tests ran (step 7b)"
                else
                    warn "SA auto-mode: bug NOT caught in top-5 selected tests (step 7b)"
                fi
            fi
            restore_bug_patch "$repo" "$patch_file"
        else
            warn "No applicable patch for $repo — skipping bug injection (add scripts/bugs/$repo/*.patch)"
        fi
    else
        warn "No patch directory for $repo — skipping bug injection (add scripts/bugs/$repo/*.patch)"
    fi

    # 8. Dashboard
    log "Step 8: Generate dashboard"
    local dash_log="$results/full-dashboard.log"
    JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew testOrderDashboard \
        --no-daemon --no-build-cache --no-configuration-cache \
        $extra_args \
        2>&1 | tee "$dash_log" | tail -5 || warn "Dashboard generation failed"
    local dash_html
    dash_html=$(find "$dir" -name "index.html" -path "*test-order-dashboard*" 2>/dev/null | head -1 || true)
    if [[ -n "$dash_html" ]]; then
        local dash_size
        dash_size=$(du -sh "$dash_html" 2>/dev/null | cut -f1 || true)
        ok "Dashboard: $dash_html ($dash_size)"
    else
        warn "Dashboard HTML not found after generation"
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

    # Per-repo JAVA_HOME override
    local maven_java_home=""
    type detect_maven_java_home &>/dev/null && maven_java_home=$(detect_maven_java_home "$repo")
    if [[ -n "$maven_java_home" ]]; then
        export JAVA_HOME="$maven_java_home"
        export PATH="$maven_java_home/bin:$PATH"
    fi

    local mvn_args=(-B -Denforcer.skip=true -Drat.skip=true -Djacoco.skip=true
                    -Dmaven.build.cache.enabled=false)
    [[ -n "$pkg" ]] && mvn_args+=("-Dtestorder.includePackages=$pkg")
    [[ -n "$module" && "$module" != "NONE" ]] && mvn_args+=(-pl "$module" -am)

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

    # Show order (use modern test-order:show goal; deprecated show-order doesn't filter excluded/abstract tests)
    log "Running: mvn me.bechberger:test-order-maven-plugin:show"
    mvn me.bechberger:test-order-maven-plugin:show \
        -Dtestorder.changeMode=explicit \
        -Dtestorder.changed.classes="$src_class" \
        -Dtestorder.show.limit=-1 \
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

    local mvn_args=(-B -Denforcer.skip=true -Drat.skip=true -Djacoco.skip=true
                    -Dmaven.build.cache.enabled=false)
    [[ -n "$pkg" ]] && mvn_args+=("-Dtestorder.includePackages=$pkg")
    [[ -n "$module" && "$module" != "NONE" ]] && mvn_args+=(-pl "$module" -am)

    # Phase 1: Select top-N tests
    log "Running: mvn clean me.bechberger:test-order-maven-plugin:affected test -Dtestorder.affected.topN=5"
    mvn clean me.bechberger:test-order-maven-plugin:affected test \
        -Dtestorder.changeMode=explicit \
        -Dtestorder.changed.classes="$src_class" \
        -Dtestorder.affected.topN=5 \
        -Dtestorder.affected.randomM=2 \
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

    local mvn_args=(-B -Denforcer.skip=true -Drat.skip=true -Djacoco.skip=true
                    -Dmaven.build.cache.enabled=false)
    [[ -n "$pkg" ]] && mvn_args+=("-Dtestorder.includePackages=$pkg")
    [[ -n "$module" && "$module" != "NONE" ]] && mvn_args+=(-pl "$module" -am)

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

    # Per-repo JAVA_HOME override (same as phase_learn_maven)
    local maven_java_home=""
    type detect_maven_java_home &>/dev/null && maven_java_home=$(detect_maven_java_home "$repo")
    if [[ -n "$maven_java_home" ]]; then
        export JAVA_HOME="$maven_java_home"
        export PATH="$maven_java_home/bin:$PATH"
    fi

    local compiler_args
    compiler_args=$(detect_compiler_args "$repo")
    local extra_mvn_args=""
    type detect_extra_mvn_args &>/dev/null && extra_mvn_args=$(detect_extra_mvn_args "$repo")
    local base_args=(-B -Denforcer.skip=true -Drat.skip=true -Djacoco.skip=true
                     -Dmaven.build.cache.enabled=false)
    [[ -n "$compiler_args" ]] && base_args+=($compiler_args)
    if [[ -n "$extra_mvn_args" ]]; then
        eval "base_args+=($extra_mvn_args)"
    fi
    [[ -n "$pkg" ]] && base_args+=("-Dtestorder.includePackages=$pkg")

    local mvn_args=("${base_args[@]}")
    [[ -n "$module" && "$module" != "NONE" ]] && mvn_args+=(-pl "$module" -am)

    # Ensure we have an index (learn first if needed)
    local idx
    idx=$(find -L "$dir" ! -path "*precheck*" -name "test-dependencies.lz4" -print -quit 2>/dev/null)
    if [[ -z "$idx" ]]; then
        log "No index found, running learn first..."
        # Pre-learn: install jars first if required by this repo (e.g. cross-module test deps)
        local prelearn_goals=""
        type detect_maven_prelearn_goals &>/dev/null && prelearn_goals=$(detect_maven_prelearn_goals "$repo")
        if [[ -n "$prelearn_goals" ]]; then
            log "Running pre-learn install: mvn $prelearn_goals"
            # shellcheck disable=SC2086
            mvn $prelearn_goals "${mvn_args[@]}" \
                2>&1 | tee "$results/bug-prelearn-install.log" | tail -3 || \
                warn "Pre-learn install had failures (continuing)"
        fi
        mvn_learn "${mvn_args[@]}" \
            2>&1 | tee "$results/bug-learn.log" | tail -3
        idx=$(find -L "$dir" ! -path "*precheck*" -name "test-dependencies.lz4" -print -quit 2>/dev/null)
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
            -Dtestorder.affected.topN=3 \
            -Dtestorder.mode=skip \
            -Dsurefire.failIfNoSpecifiedTests=false \
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

    # Per-repo JAVA_HOME override (e.g. spring-ai needs JDK 21, not the global JDK 17 default)
    local maven_java_home=""
    type detect_maven_java_home &>/dev/null && maven_java_home=$(detect_maven_java_home "$repo")
    if [[ -n "$maven_java_home" ]]; then
        export JAVA_HOME="$maven_java_home"
        export PATH="$maven_java_home/bin:$PATH"
    fi

    # Base args (no module selector) shared by all goals
    local compiler_args
    compiler_args=$(detect_compiler_args "$repo")
    local extra_mvn_args=""
    type detect_extra_mvn_args &>/dev/null && extra_mvn_args=$(detect_extra_mvn_args "$repo")
    local base_args=(-B --fail-at-end -Denforcer.skip=true -Drat.skip=true -Djacoco.skip=true
                     -Dmaven.build.cache.enabled=false)
    [[ -n "$compiler_args" ]] && base_args+=($compiler_args)
    if [[ -n "$extra_mvn_args" ]]; then
        # Use eval to correctly handle quoted args like -pl '!module-name'
        eval "base_args+=($extra_mvn_args)"
    fi
    [[ -n "$pkg" ]] && base_args+=("-Dtestorder.includePackages=$pkg")

    # Build args for test+compile goals: need -pl $module -am so dependencies compile
    local mvn_args=("${base_args[@]}")
    [[ -n "$module" && "$module" != "NONE" ]] && mvn_args+=(-pl "$module" -am)

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
    local idx=$(find -L "$dir" ! -path "*precheck*" -name "test-dependencies.lz4" -print -quit 2>/dev/null)
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
    mvn me.bechberger:test-order-maven-plugin:dump "${cmd_args[@]}" 2>&1 | tee >(head -c 5242880 > "$results/full-dump.log") | tail -20 || warn "Dump failed"

    # Also dump as TSV for discriminating-power scoring in find_bug_targets (S15)
    local dump_tsv="$results/full-dump.tsv"
    mvn me.bechberger:test-order-maven-plugin:dump "${cmd_args[@]}" \
        -Dtestorder.dump.format=tsv -Dtestorder.dump.output="$dump_tsv" -q 2>/dev/null || true

    # 4. Show order with a specific change (use modern show goal; deprecated show-order doesn't filter excluded/abstract tests)
    log "Step 4: Show order"
    mvn me.bechberger:test-order-maven-plugin:show \
        -Dtestorder.changeMode=explicit \
        -Dtestorder.changed.classes="$src_class" \
        -Dtestorder.show.limit=-1 \
        "${cmd_args[@]}" \
        2>&1 | tee "$results/full-show-order.log" | tail -20 || warn "Show-order failed"

    # 5. Select
    log "Step 5: Select top-5"
    mvn clean me.bechberger:test-order-maven-plugin:affected test \
        -Dtestorder.changeMode=explicit \
        -Dtestorder.changed.classes="$src_class" \
        -Dtestorder.affected.topN=5 \
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
            -Dtestorder.affected.topN=3 \
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
            log "  Top-5 scorers (show):"
            mvn me.bechberger:test-order-maven-plugin:show \
                -Dtestorder.changeMode=explicit \
                -Dtestorder.changed.classes="$classname" \
                "${cmd_args[@]}" -q 2>&1 \
                | grep -E '^\s+[0-9]' | head -5 | sed 's/^/    /' || true
        fi
        # Step 7b: SA auto-mode (changeMode=uncommitted) — verify SA detects the
        # change from the git diff without an explicit class name. Only runs when
        # the third-party repo is a git repository (patch leaves an uncommitted diff).
        if git -C "$dir" rev-parse --git-dir >/dev/null 2>&1; then
            log "Step 7b: SA auto-mode (changeMode=uncommitted)"
            local sa_log="$results/full-bug-sa-select.log"
            mvn clean me.bechberger:test-order-maven-plugin:affected test \
                -Dtestorder.changeMode=uncommitted \
                -Dtestorder.affected.topN=5 \
                -Dtestorder.mode=skip \
                "${test_cmd_args[@]}" \
                2>&1 | tee "$sa_log" | tail -5 || true
            if log_has_test_failures "$sa_log"; then
                ok "SA auto-mode caught bug (step 7b)!"
            elif grep -q "No changed classes detected" "$sa_log" 2>/dev/null || \
                 grep -q "no changed classes" "$sa_log" 2>/dev/null; then
                warn "SA auto-mode: no changed classes detected from git diff (step 7b)"
            elif ! log_has_tests_run "$sa_log"; then
                warn "SA auto-mode: build failed before tests ran (step 7b)"
            else
                warn "SA auto-mode: bug NOT caught in top-5 selected tests (step 7b)"
            fi
            # Also show what SA found
            mvn me.bechberger:test-order-maven-plugin:show-static-analysis \
                "${cmd_args[@]}" -q 2>&1 | grep -E 'changed|seed|expand|uncertain|class' \
                | head -10 | sed 's/^/    /' || true
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

    # 11. Dashboard
    log "Step 11: Generate dashboard"
    local dash_log="$results/full-dashboard.log"
    local cmd_args_dash=("${cmd_args[@]}")
    mvn me.bechberger:test-order-maven-plugin:dashboard "${cmd_args_dash[@]}" \
        2>&1 | tee "$dash_log" | tail -5 || warn "Dashboard generation failed"
    local dash_html
    dash_html=$(find "$dir" -name "index.html" -path "*test-order-dashboard*" 2>/dev/null | head -1 || true)
    if [[ -n "$dash_html" ]]; then
        local dash_size
        dash_size=$(du -sh "$dash_html" 2>/dev/null | cut -f1 || true)
        ok "Dashboard: $dash_html ($dash_size)"
    else
        warn "Dashboard HTML not found after generation"
    fi

    ok "Full workflow completed for $repo"
    remove_maven_plugin "$repo" "$module"
}

# ═══════════════════════════════════════════════════════════════════════════════
# SYNTHETIC HISTORY: Source mutators + validation phase
#
# These helpers mutate a single .java file, run two learn cycles, then assert
# that the change-detection machinery (bytecode hash, git-uncommitted,
# since-last-run) correctly reports the changed class.
#
# Each mutator:
#   1. Saves a .bak copy of the target file.
#   2. Applies a sed/awk transformation directly to the source.
#   3. Returns the FQCN of the mutated class on stdout.
#
# revert_mutation() restores from the .bak copy.
# ═══════════════════════════════════════════════════════════════════════════════

# ── Select a mutation target file ────────────────────────────────────────────
# Pick a non-test .java file from src/main/java that is actually referenced by
# at least one test class name (heuristic: the test is named FooTest → Foo is
# a good target).  Falls back to the first available source file.
#
# Optional third arg: path to hashes directory (.test-order/hashes/).
# When provided, only files in submodule directories that have a corresponding
# hash file are considered. This prevents selecting targets from modules that
# did not run during Learn-A (which would make since-last-run detection fail).
# Returns: full path to the .java file
select_mutation_target() {
    local repo="$1"
    local module="${2:-}"
    local hashes_dir="${3:-}"
    local dir="$THIRD_PARTY/$repo"
    local search_root="$dir"
    # Tentatively narrow to the requested module. We'll widen back to the
    # whole repo below if the hash filter excludes everything inside it
    # (common in multi-module Maven repos where detect_single_module picks
    # a different module than the one learn-A actually generated hashes for).
    if [[ -n "$module" && "$module" != "NONE" && -d "$dir/$module/src/main/java" ]]; then
        search_root="$dir/$module"
    fi

    # If a hashes dir was provided, build an allowlist of subdirectory names
    # that have at least one hash file. Hash file names follow the convention
    # <groupId>-<artifactId>-hashes.lz4 (Maven) or <projectName>-hashes.lz4 (Gradle).
    # We store all hyphen-separated suffixes of the artifact ID portion so that
    # directory names like "log4j-api" (from "...log4j-api-hashes.lz4"), "codec-base"
    # (from "...netty-codec-base-hashes.lz4"), and "handler" (from "...netty-handler-hashes.lz4")
    # are all matched correctly.
    local -A hashed_dirs=()
    if [[ -n "$hashes_dir" && -d "$hashes_dir" ]]; then
        while IFS= read -r hf; do
            local bn
            bn=$(basename "$hf" "-hashes.lz4")
            # Strip the bytecode/method/test suffix variants — only process *-hashes.lz4 directly
            # bn is e.g. "io.netty-netty-codec-base" or "org.apache.logging.log4j-log4j-api"
            # Strip leading dotted groupId segment (everything up to and including first '-' after dots)
            # e.g. "io.netty-netty-codec-base" → strip "io.netty-" → "netty-codec-base"
            local artid="${bn}"
            # Remove leading "x.y.z-" groupId prefix (greedy match of dotted segments + dash)
            artid="${artid#*[.]*-}"    # e.g. io.netty-netty-codec-base → netty-codec-base
            # Store all non-empty hyphen-suffix combinations of the artId
            local suffix="$artid"
            while [[ -n "$suffix" ]]; do
                hashed_dirs["$suffix"]=1
                # Strip leading segment: "netty-codec-base" → "codec-base" → "base" → ""
                [[ "$suffix" == *-* ]] && suffix="${suffix#*-}" || suffix=""
            done
        done < <(find "$hashes_dir" -maxdepth 1 -name "*-hashes.lz4" 2>/dev/null)
    fi

    # Returns true if a source file path is in a module that had a hash snapshot.
    _has_hash() {
        local src="$1"
        if [[ "${#hashed_dirs[@]}" -eq 0 ]]; then
            return 0  # no filter — allow all
        fi
        # Extract the first path component below $dir
        local rel="${src#$dir/}"
        local subdir="${rel%%/*}"
        [[ -n "${hashed_dirs[$subdir]+set}" ]] && return 0
        return 1
    }

    # If the hash filter is active and the requested module is NOT in the
    # hash allowlist, widen search to the whole repo. Otherwise the filter
    # rejects every file under search_root (e.g. detect_single_module picks
    # vertx-web/vertx-web but hashes only cover vertx-web-client).
    if [[ "$search_root" != "$dir" && "${#hashed_dirs[@]}" -gt 0 ]]; then
        local mod_basename
        mod_basename="$(basename "$search_root")"
        if [[ -z "${hashed_dirs[$mod_basename]+set}" ]]; then
            search_root="$dir"
        fi
    fi

    # Build a set of test class basenames (strip 'Test' / 'Tests' suffix)
    local -A test_set=()
    while IFS= read -r f; do
        local base
        base=$(basename "$f" .java)
        base="${base%Test}"; base="${base%Tests}"
        test_set["$base"]=1
    done < <(find "$dir" -path "*/src/test/java/*" -name "*Test*.java" ! -path "*/target/*" ! -path "*/buildSrc/*" 2>/dev/null | head -500)

    # Walk src/main/java looking for a file that:
    #   (a) is in a module with a hash file (if filter active)
    #   (b) has a matching test name, AND
    #   (c) contains injectable source (methods with bodies ≥ 3 lines)
    local best=""
    while IFS= read -r src; do
        _has_hash "$src" || continue
        local name
        name=$(basename "$src" .java)
        if [[ -n "${test_set[$name]+set}" ]]; then
            # Check for a concrete method body: needs at least one { ... } block
            if grep -q "^[[:space:]]*public\|^[[:space:]]*protected\|^[[:space:]]*private" "$src" 2>/dev/null &&
               grep -q "[{}]" "$src" 2>/dev/null; then
                best="$src"
                break
            fi
        fi
    done < <(find "$search_root" -path "*/src/main/java/*" -name "*.java" \
                ! -path "*/target/*" ! -path "*/src/test/*" ! -path "*/buildSrc/*" \
                ! -name "package-info.java" ! -name "module-info.java" \
                ! -name "*Builder*" ! -name "*Generated*" \
                2>/dev/null | sort)

    # Fall back to first available source file (still filtered by hashes)
    if [[ -z "$best" ]]; then
        while IFS= read -r src; do
            _has_hash "$src" || continue
            best="$src"
            break
        done < <(find "$search_root" -path "*/src/main/java/*" -name "*.java" \
                    ! -path "*/target/*" ! -path "*/src/test/*" ! -path "*/buildSrc/*" \
                    ! -name "package-info.java" ! -name "module-info.java" \
                    2>/dev/null | sort)
    fi

    # Last resort: still respect hash filter if active; only bypass if no filter at all
    if [[ -z "$best" ]]; then
        while IFS= read -r src; do
            _has_hash "$src" || continue
            best="$src"
            break
        done < <(find "$search_root" -path "*/src/main/java/*" -name "*.java" \
                    ! -path "*/target/*" ! -path "*/src/test/*" ! -path "*/buildSrc/*" \
                    ! -name "package-info.java" ! -name "module-info.java" \
                    2>/dev/null | sort)
    fi
    # Absolute last resort: no hash filter active at all, pick any file
    if [[ -z "$best" && "${#hashed_dirs[@]}" -eq 0 ]]; then
        best=$(find "$search_root" -path "*/src/main/java/*" -name "*.java" \
                ! -path "*/target/*" ! -path "*/src/test/*" ! -path "*/buildSrc/*" \
                ! -name "package-info.java" ! -name "module-info.java" \
                2>/dev/null | sort | head -1)
    fi
    echo "$best"
}

# Convert a full path under src/main/java to a FQCN
path_to_fqcn() {
    echo "$1" | sed 's|.*/src/main/java/||;s|/|.|g;s|\.java$||'
}

# ── Mutators ─────────────────────────────────────────────────────────────────

# mutate_add_method: appends a no-op static method before the last closing brace
mutate_add_method() {
    local src="$1"
    cp "$src" "$src.bak"
    # Insert before the final closing brace of the class
    awk 'BEGIN{found=0} /^}[[:space:]]*$/ && !found{
        print "  /** inserted by test-order synthetic mutation */";
        print "  public static int __syntheticProbe() { return 42; }";
        found=1
    } {print}' "$src.bak" > "$src"
}

# mutate_body: flip the first `return true` to `return false` (or vice versa),
# or fall back to add_method if no injectable pattern exists.
# Returns 0 if mutation was applied, 1 if fell back to add_method.
mutate_body() {
    local src="$1"
    cp "$src" "$src.bak"
    local mutated=0
    if grep -q "return true;" "$src"; then
        sed -i.tmp 's/return true;/return false; \/\* synthetic mutation \*\//' "$src"
        mutated=1
    elif grep -q "return false;" "$src"; then
        sed -i.tmp 's/return false;/return true; \/\* synthetic mutation \*\//' "$src"
        mutated=1
    else
        # Fall back: flip first `== 0` in a return statement to `== 1`
        if grep -qE "return.*== 0" "$src"; then
            sed -i.tmp 's/\(return.*\)== 0/\1== 1 \/\* synthetic \*\//' "$src"
            mutated=1
        fi
    fi
    rm -f "$src.tmp"

    # If sed made no change, fall back to add_method (always works).
    # IMPORTANT: do NOT call mutate_add_method directly — it would overwrite .bak
    # with the already-mutated (or pristine) file. Instead, save .bak ourselves
    # first, then do the awk insertion inline.
    if [[ "$mutated" -eq 0 ]] || cmp -s "$src.bak" "$src"; then
        # Restore the original and apply add_method inline (preserving .bak = original)
        cp "$src.bak" "$src"
        awk 'BEGIN{found=0} /^}[[:space:]]*$/ && !found{
            print "  /** inserted by test-order synthetic mutation */";
            print "  public static int __syntheticProbe() { return 42; }";
            found=1
        } {print}' "$src.bak" > "$src"
    fi
}

# mutate_add_field: inject a synthetic field after the opening class/interface/enum line.
# For interfaces (and annotations), uses "public static final" since private fields are
# not valid Java there. For classes/enums uses "private static final".
mutate_add_field() {
    local src="$1"
    cp "$src" "$src.bak"
    # Match class or interface/enum/annotation declaration at the top level
    awk '
    /^[[:space:]]*(public|protected|abstract|final|sealed|non-sealed|strictfp)?[[:space:]]*(public|protected|abstract|final|sealed|non-sealed|strictfp)?[[:space:]]*(interface|@interface|enum)[[:space:]][A-Z]/ {
        print;
        print "  public static final int __SYNTHETIC_PROBE = 1;";
        next
    }
    /class [A-Z][A-Za-z0-9_]*/ && /^[[:space:]]*(public|protected|final|abstract|class|@)/ {
        print;
        print "  private static final int __SYNTHETIC_PROBE = 1;";
        next
    } {print}' "$src.bak" > "$src"
    # Fallback: if awk made no change (unusual layout), insert before final }
    # Detect whether source is an interface/annotation to pick the right modifier.
    if cmp -s "$src.bak" "$src"; then
        cp "$src.bak" "$src"
        local field_decl="private static final int __SYNTHETIC_PROBE = 1;"
        if grep -qE '^[[:space:]]*(public[[:space:]]+)?@?interface[[:space:]]|^[[:space:]]*(public[[:space:]]+)?interface[[:space:]]' "$src.bak"; then
            field_decl="public static final int __SYNTHETIC_PROBE = 1;"
        fi
        awk -v decl="  $field_decl" 'BEGIN{found=0} /^}[[:space:]]*$/ && !found{
            print decl;
            found=1
        } {print}' "$src.bak" > "$src"
    fi
}

# mutate_touch_only (negative control): create a dummy NON-source file to ensure
# no source change → detection correctly reports nothing changed.
# Since this doesn't modify any .java file, both source-hash and bytecode-hash
# based detection should report 0 changed classes.
mutate_touch_only() {
    local src="$1"
    # Create a .bak as a sentinel (no actual file modification)
    touch "$src.bak"
    # Write a dummy non-source file adjacent to src to confirm we "did something"
    echo "// synthetic-touch-noop" > "${src%.java}__synthetic_noop__.txt"
}

# Revert any mutation on $src (restore from .bak)
revert_mutation() {
    local src="$1"
    if [[ -f "$src.bak" ]]; then
        # If .bak is empty (touch_only sentinel), just delete it
        if [[ -s "$src.bak" ]]; then
            mv "$src.bak" "$src"
        else
            rm -f "$src.bak"
        fi
    fi
    rm -f "$src.tmp" "${src%.java}__synthetic_noop__.txt"
}

# ── Assert change detection ───────────────────────────────────────────────────
# Run `mvn test-order:show` with the given changeMode and parse "Changed classes:"
# Returns 0 if $fqcn appears in output, 1 otherwise.
# For touch_only, pass expected=0 (inverted logic).
assert_show_detects() {
    local dir="$1"
    local fqcn="$2"
    local change_mode="$3"
    local expected_detected="${4:-1}"   # 1 = expect detected, 0 = expect NOT detected
    shift 4
    local cmd_args=("$@")

    local out
    out=$(mvn me.bechberger:test-order-maven-plugin:show \
        -Dtestorder.changeMode="$change_mode" \
        -Dtestorder.show.limit=-1 \
        "${cmd_args[@]}" \
        2>&1) || true

    local detected=0
    if echo "$out" | grep -q "Changed classes:.*$fqcn\|Changed class:.*$fqcn"; then
        detected=1
    fi

    if [[ "$expected_detected" -eq 1 && "$detected" -eq 1 ]]; then
        ok "  [DETECT OK] mode=$change_mode fqcn=$fqcn"
        return 0
    elif [[ "$expected_detected" -eq 0 && "$detected" -eq 0 ]]; then
        ok "  [NO-DETECT OK] mode=$change_mode fqcn=$fqcn (expected no detection)"
        return 0
    else
        if [[ "$expected_detected" -eq 1 ]]; then
            warn "  [DETECT MISS] mode=$change_mode fqcn=$fqcn — expected detection but got none"
        else
            warn "  [FALSE POS] mode=$change_mode fqcn=$fqcn — unexpected detection (touch_only)"
        fi
        return 1
    fi
}

# ── Write one TSV row to the results dir ─────────────────────────────────────
# Columns: repo, mutator, change_mode, fqcn, expected, detected, runtime_ms
write_synthetic_tsv_row() {
    local tsv_file="$1"
    local repo="$2"
    local mutator="$3"
    local change_mode="$4"
    local fqcn="$5"
    local expected="$6"
    local detected="$7"
    local runtime_ms="${8:-0}"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$repo" "$mutator" "$change_mode" "$fqcn" "$expected" "$detected" "$runtime_ms" \
        >> "$tsv_file"
}

# ── Phase: synthetic-history (Maven) ─────────────────────────────────────────
phase_synthetic_history_maven() {
    local repo="$1"
    local mutator="${2:-mutate_body}"   # which mutator to use
    section "SYNTHETIC HISTORY: $repo (Maven) mutator=$mutator"

    local dir="$THIRD_PARTY/$repo"
    local results
    results=$(result_dir "$repo")
    local synth_dir="$results/synthetic"
    mkdir -p "$synth_dir"
    local tsv_file="$synth_dir/results.tsv"
    # Header (only if file doesn't exist)
    [[ -f "$tsv_file" ]] || printf 'repo\tmutator\tchange_mode\tfqcn\texpected\tdetected\truntime_ms\n' > "$tsv_file"

    local module
    module=$(detect_single_module "$repo")
    local pkg
    pkg=$(detect_test_package "$repo")

    cd "$dir"
    inject_maven_plugin "$repo" "$module"

    local compiler_args
    compiler_args=$(detect_compiler_args "$repo")
    local extra_mvn_args=""
    type detect_extra_mvn_args &>/dev/null && extra_mvn_args=$(detect_extra_mvn_args "$repo")
    local base_args=(-B --fail-at-end -Denforcer.skip=true -Drat.skip=true -Djacoco.skip=true
                     -Dmaven.build.cache.enabled=false)
    [[ -n "$compiler_args" ]] && base_args+=($compiler_args)
    if [[ -n "$extra_mvn_args" ]]; then
        eval "base_args+=($extra_mvn_args)"
    fi
    [[ -n "$pkg" ]] && base_args+=("-Dtestorder.includePackages=$pkg")

    local mvn_args=("${base_args[@]}")
    [[ -n "$module" && "$module" != "NONE" ]] && mvn_args+=(-pl "$module" -am)

    # 1. Clean state
    log "SH Step 1: Clean test-order data"
    rm -rf "$dir/.test-order" 2>/dev/null
    find "$dir" -maxdepth 3 -name ".test-order" -type d -exec rm -rf {} + 2>/dev/null || true
    ok "Cleaned"

    # 2. Learn-A (initial run to build bytecode-hashes baseline)
    log "SH Step 2: Learn-A"
    mvn_learn "${mvn_args[@]}" \
        2>&1 | tee "$synth_dir/learn-A.log" | tail -3 \
        || warn "Learn-A had test failures (index may still be valid)"

    local idx
    idx=$(find -L "$dir" ! -path "*precheck*" -name "test-dependencies.lz4" -print -quit 2>/dev/null)
    if [[ -z "$idx" ]]; then
        warn "No index after Learn-A — project likely uses JUnit 4. Skipping synthetic-history."
        remove_maven_plugin "$repo" "$module"
        write_synthetic_tsv_row "$tsv_file" "$repo" "$mutator" "N/A" "N/A" "skip" "skip" "0"
        return 0
    fi
    ok "Index: $idx"

    # Determine idx_module and cmd_args (same logic as phase_full_maven)
    local idx_dir
    idx_dir=$(dirname "$idx")
    idx_dir=$(dirname "$idx_dir")
    local idx_module=""
    [[ "$idx_dir" != "$dir" ]] && idx_module=$(basename "$idx_dir")
    local cmd_args=("${base_args[@]}")
    [[ -n "$idx_module" ]] && cmd_args+=(-pl "$idx_module")

    # 3. Select mutation target
    local target_src
    target_src=$(select_mutation_target "$repo" "${idx_module:-$module}" "$dir/.test-order/hashes")
    if [[ -z "$target_src" ]]; then
        warn "No mutation target found for $repo — skipping"
        remove_maven_plugin "$repo" "$module"
        write_synthetic_tsv_row "$tsv_file" "$repo" "$mutator" "N/A" "N/A" "skip" "skip-no-target" "0"
        return 0
    fi
    local fqcn
    fqcn=$(path_to_fqcn "$target_src")
    log "SH Step 3: Mutation target: $fqcn ($target_src)"

    # 4. Apply mutation (BEFORE running show — source hash detection requires the
    #    source to differ from the learn-A snapshot; learn-B must NOT run first)
    log "SH Step 4: Apply $mutator to $fqcn"
    local is_touch_only=0
    [[ "$mutator" == "mutate_touch_only" ]] && is_touch_only=1
    "$mutator" "$target_src"

    # For non-touch_only: git-stage so uncommitted mode sees the change
    if [[ "$is_touch_only" -eq 0 ]]; then
        git -C "$dir" add -f "$target_src" 2>/dev/null || true
    fi

    # 5. Assert detection BEFORE learn-B (source hashes differ from learn-A snapshot)
    local expected_detected
    [[ "$is_touch_only" -eq 0 ]] && expected_detected=1 || expected_detected=0

    for mode in "since-last-run" "uncommitted"; do
        local t0=$SECONDS
        local show_log="$synth_dir/show-${mutator}-${mode}.log"
        local detected=0
        # show uses detectReadOnly → does NOT update hashes.lz4
        mvn me.bechberger:test-order-maven-plugin:show \
            -Dtestorder.changeMode="$mode" \
            -Dtestorder.show.limit=-1 \
            "${cmd_args[@]}" \
            > "$show_log" 2>&1 || true

        # "Changed classes:" line lists the changed source classes
        if grep -q "Changed classes:.*$fqcn" "$show_log" 2>/dev/null; then
            detected=1
        fi

        if [[ "$expected_detected" -eq 1 && "$detected" -eq 1 ]]; then
            ok "  [DETECT OK] mode=$mode fqcn=$fqcn"
        elif [[ "$expected_detected" -eq 0 && "$detected" -eq 0 ]]; then
            ok "  [NO-DETECT OK] mode=$mode (no-op mutation, expected no detection)"
        elif [[ "$expected_detected" -eq 1 && "$detected" -eq 0 ]]; then
            warn "  [DETECT MISS] mode=$mode fqcn=$fqcn — expected detection but got none"
        else
            warn "  [FALSE POS] mode=$mode fqcn=$fqcn — unexpected detection"
        fi

        local det_ms=$(( (SECONDS - t0) * 1000 ))
        write_synthetic_tsv_row "$tsv_file" "$repo" "$mutator" "$mode" "$fqcn" \
            "$expected_detected" "$detected" "$det_ms"
    done

    # 6. Learn-B: prove the changed class is detected during learn and boosts tests
    log "SH Step 6: Learn-B (validate detection inside learn pipeline)"
    local t_start=$SECONDS
    mvn_learn "${mvn_args[@]}" \
        2>&1 | tee "$synth_dir/learn-B.log" | tail -5 \
        || warn "Learn-B had test failures"
    local learn_b_ms=$(( (SECONDS - t_start) * 1000 ))
    # Verify learn-B reported the expected changed class
    if [[ "$is_touch_only" -eq 0 ]]; then
        if grep -q "changed source class.*$fqcn\|Detected.*$fqcn\|boosting.*depend on" "$synth_dir/learn-B.log" 2>/dev/null; then
            ok "  Learn-B correctly reported change in $fqcn"
        else
            warn "  Learn-B did not mention $fqcn in change detection"
        fi
    fi

    # 7. Revert mutation
    log "SH Step 7: Revert mutation"
    revert_mutation "$target_src"
    # Use 'git checkout HEAD --' not 'git checkout --': the latter restores from
    # the index, which still has the mutation staged after 'git add -f'. HEAD
    # restores unconditionally from the last commit regardless of index state.
    git -C "$dir" reset HEAD "$target_src" 2>/dev/null || true
    git -C "$dir" checkout -- "$target_src" 2>/dev/null || true

    ok "Synthetic-history complete for $repo (mutator=$mutator)"
    remove_maven_plugin "$repo" "$module"
}

# Run all 4 mutators for a single repo (full synthetic-history sweep)
phase_synthetic_history_all_maven() {
    local repo="$1"
    for mut in mutate_body mutate_add_method mutate_add_field mutate_touch_only; do
        phase_synthetic_history_maven "$repo" "$mut" || true
    done
}

# ── Phase: synthetic-history (Gradle) ─────────────────────────────────────────
phase_synthetic_history_gradle() {
    local repo="$1"
    local mutator="${2:-mutate_body}"
    section "SYNTHETIC HISTORY: $repo (Gradle) mutator=$mutator"

    local dir results extra_args override_java_home
    _gradle_phase_init "$repo"
    local dir="$THIRD_PARTY/$repo"
    local results
    results=$(result_dir "$repo")
    local synth_dir="$results/synthetic"
    mkdir -p "$synth_dir"
    local tsv_file="$synth_dir/results.tsv"
    [[ -f "$tsv_file" ]] || printf 'repo\tmutator\tchange_mode\tfqcn\texpected\tdetected\truntime_ms\n' > "$tsv_file"

    cd "$dir"
    inject_gradle_plugin "$repo"

    local learn_extra_args
    learn_extra_args=$(type detect_gradle_learn_extra_args &>/dev/null \
        && detect_gradle_learn_extra_args "$repo" 2>/dev/null || echo "")

    # 1. Clean state
    log "SH Step 1: Clean test-order data"
    rm -rf "$dir/.test-order" 2>/dev/null
    find "$dir" -maxdepth 5 -name ".test-order" -type d -exec rm -rf {} + 2>/dev/null || true
    ok "Cleaned"

    # 2. Learn-A
    log "SH Step 2: Learn-A"
    # shellcheck disable=SC2086
    JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew cleanTest test \
        -Dtestorder.mode=learn --no-build-cache --no-configuration-cache \
        $extra_args ${learn_extra_args} \
        2>&1 | tee "$synth_dir/learn-A.log" | tail -3 \
        || warn "Learn-A had test failures (index may still be valid)"

    local idx
    idx=$(find -L "$dir" ! -path "*precheck*" -name "test-dependencies.lz4" -print -quit 2>/dev/null)
    if [[ -z "$idx" ]]; then
        warn "No index after Learn-A. Skipping synthetic-history."
        remove_gradle_plugin "$repo"
        write_synthetic_tsv_row "$tsv_file" "$repo" "$mutator" "N/A" "N/A" "skip" "skip-no-index" "0"
        return 0
    fi
    ok "Index: $idx"

    # 3. Select mutation target
    local target_src
    target_src=$(select_mutation_target "$repo" "" "$dir/.test-order/hashes")
    if [[ -z "$target_src" ]]; then
        warn "No mutation target found for $repo — skipping"
        remove_gradle_plugin "$repo"
        write_synthetic_tsv_row "$tsv_file" "$repo" "$mutator" "N/A" "N/A" "skip" "skip-no-target" "0"
        return 0
    fi
    local fqcn
    fqcn=$(path_to_fqcn "$target_src")
    log "SH Step 3: Mutation target: $fqcn ($target_src)"

    # 4. Apply mutation
    log "SH Step 4: Apply $mutator to $fqcn"
    local is_touch_only=0
    [[ "$mutator" == "mutate_touch_only" ]] && is_touch_only=1
    "$mutator" "$target_src"

    if [[ "$is_touch_only" -eq 0 ]]; then
        git -C "$dir" add -f "$target_src" 2>/dev/null || true
    fi

    # 5. Assert detection via testOrderShow
    local expected_detected
    [[ "$is_touch_only" -eq 0 ]] && expected_detected=1 || expected_detected=0

    for mode in "since-last-run" "uncommitted"; do
        local t0=$SECONDS
        local show_log="$synth_dir/show-${mutator}-${mode}.log"
        local detected=0
        # shellcheck disable=SC2086
        JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew testOrderShow \
            --no-build-cache --no-configuration-cache \
            "-Dtestorder.changeMode=$mode" \
            "-Dtestorder.show.limit=-1" \
            $extra_args \
            > "$show_log" 2>&1 || true

        if grep -q "Changed classes:.*$fqcn\|$fqcn" "$show_log" 2>/dev/null; then
            detected=1
        fi

        if [[ "$expected_detected" -eq 1 && "$detected" -eq 1 ]]; then
            ok "  [DETECT OK] mode=$mode fqcn=$fqcn"
        elif [[ "$expected_detected" -eq 0 && "$detected" -eq 0 ]]; then
            ok "  [NO-DETECT OK] mode=$mode (no-op mutation, expected no detection)"
        elif [[ "$expected_detected" -eq 1 && "$detected" -eq 0 ]]; then
            warn "  [DETECT MISS] mode=$mode fqcn=$fqcn — expected detection but got none"
        else
            warn "  [FALSE POS] mode=$mode fqcn=$fqcn — unexpected detection"
        fi

        local det_ms=$(( (SECONDS - t0) * 1000 ))
        write_synthetic_tsv_row "$tsv_file" "$repo" "$mutator" "$mode" "$fqcn" \
            "$expected_detected" "$detected" "$det_ms"
    done

    # 6. Learn-B: prove the changed class is detected during learn
    log "SH Step 6: Learn-B (validate detection inside learn pipeline)"
    # shellcheck disable=SC2086
    JAVA_HOME="${override_java_home:-${JAVA_HOME:-}}" ./gradlew testOrderLearn \
        --no-build-cache --no-configuration-cache \
        $extra_args ${learn_extra_args} \
        2>&1 | tee "$synth_dir/learn-B.log" | tail -5 \
        || warn "Learn-B had test failures"

    if [[ "$is_touch_only" -eq 0 ]]; then
        if grep -q "changed source class.*$fqcn\|Detected.*$fqcn\|boosting.*depend on\|$fqcn" "$synth_dir/learn-B.log" 2>/dev/null; then
            ok "  Learn-B correctly reported change in $fqcn"
        else
            warn "  Learn-B did not mention $fqcn in change detection"
        fi
    fi

    # 7. Revert mutation
    log "SH Step 7: Revert mutation"
    revert_mutation "$target_src"
    git -C "$dir" reset HEAD "$target_src" 2>/dev/null || true
    git -C "$dir" checkout -- "$target_src" 2>/dev/null || true

    ok "Synthetic-history complete for $repo (mutator=$mutator)"
    remove_gradle_plugin "$repo"
}

phase_synthetic_history_all_gradle() {
    local repo="$1"
    for mut in mutate_body mutate_add_method mutate_add_field mutate_touch_only; do
        phase_synthetic_history_gradle "$repo" "$mut" || true
    done
}

# ═══════════════════════════════════════════════════════════════════════════════
# MATRIX PHASE: Run synthetic-history under a cartesian settings grid
#
# Grid (12 cells = 3 instrumentation × 2 bytecodeCD × 2 staticAnalysis.depth):
#   instrumentation.mode:          CLASS | METHOD | MEMBER
#   bytecodeChangeDetectionEnabled: true | false
#   staticAnalysis.depth:           1   | 3
#
# Per cell, run mutate_body + mutate_touch_only (most discriminating pair).
# Also runs two malformed-invocation cells (no-prior-learn, wrong-cwd).
# ═══════════════════════════════════════════════════════════════════════════════

# Tag a repo with shape attributes and write _repo-attrs.tsv row (idempotent)
tag_repo_attrs() {
    local repo="$1"
    local dir="$THIRD_PARTY/$repo"
    local attrs_file="$RESULTS_DIR/_repo-attrs.tsv"
    [[ -f "$attrs_file" ]] || printf 'repo\tmulti_module\tkotlin_sources\tlombok_heavy\tjunit4_only\ttestng\tspock\thuge\ttiny\tmodule_info\tgit_clean\n' > "$attrs_file"

    # Skip if already tagged
    grep -q "^$repo	" "$attrs_file" 2>/dev/null && return 0

    local multi_module=0 kotlin_sources=0 lombok_heavy=0
    local junit4_only=0 testng=0 spock=0 huge=0 tiny=0 module_info=0 git_clean=0

    # multi_module: more than one pom.xml or build.gradle
    local pom_count
    pom_count=$(find "$dir" -maxdepth 3 -name "pom.xml" ! -path "*/target/*" 2>/dev/null | wc -l | tr -d ' ')
    [[ "$pom_count" -gt 1 ]] && multi_module=1

    # kotlin_sources
    find "$dir" -path "*/src/*" -name "*.kt" -print -quit 2>/dev/null | grep -q . && kotlin_sources=1 || true

    # lombok_heavy (≥10 .java files import lombok)
    local lombok_count
    lombok_count=$(grep -rl "import lombok\." "$dir/src" 2>/dev/null | wc -l | tr -d ' ')
    [[ "$lombok_count" -ge 10 ]] && lombok_heavy=1

    # junit4_only: has junit:junit but no junit-jupiter
    if find "$dir" -name "pom.xml" -exec grep -l "junit:junit\|<artifactId>junit</artifactId>" {} \; 2>/dev/null | grep -q .; then
        if ! find "$dir" -name "pom.xml" -exec grep -l "junit-jupiter" {} \; 2>/dev/null | grep -q .; then
            junit4_only=1
        fi
    fi

    # testng
    find "$dir" -name "pom.xml" -exec grep -l "testng" {} \; 2>/dev/null | grep -q . && testng=1 || true
    find "$dir" -name "*.gradle*" -exec grep -l "testng" {} \; 2>/dev/null | grep -q . && testng=1 || true

    # spock
    find "$dir" -name "pom.xml" -exec grep -l "spock-core" {} \; 2>/dev/null | grep -q . && spock=1 || true

    # huge: >5000 .java source files
    local java_count
    java_count=$(find "$dir" -name "*.java" ! -path "*/target/*" 2>/dev/null | wc -l | tr -d ' ')
    [[ "$java_count" -gt 5000 ]] && huge=1

    # tiny: <20 test classes
    local test_count
    test_count=$(find "$dir" -path "*/src/test/*" -name "*Test*.java" 2>/dev/null | wc -l | tr -d ' ')
    [[ "$test_count" -lt 20 ]] && tiny=1

    # module_info
    find "$dir" -name "module-info.java" ! -path "*/target/*" -print -quit 2>/dev/null | grep -q . && module_info=1 || true

    # git_clean (working tree clean)
    git -C "$dir" diff --quiet HEAD 2>/dev/null && git_clean=1 || true

    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$repo" "$multi_module" "$kotlin_sources" "$lombok_heavy" \
        "$junit4_only" "$testng" "$spock" "$huge" "$tiny" "$module_info" "$git_clean" \
        >> "$attrs_file"
    ok "Tagged attrs for $repo (multi=$multi_module kotlin=$kotlin_sources lombok=$lombok_heavy junit4=$junit4_only huge=$huge)"
}

phase_matrix_maven() {
    local repo="$1"
    section "MATRIX: $repo (Maven)"

    local dir="$THIRD_PARTY/$repo"
    local results
    results=$(result_dir "$repo")
    local matrix_dir="$results/matrix"
    mkdir -p "$matrix_dir"
    local tsv_file="$matrix_dir/results.tsv"
    [[ -f "$tsv_file" ]] || printf 'repo\tcell\tinstrumentation\tbytecodeCD\tdepth\tmutator\tchange_mode\tfqcn\texpected\tdetected\truntime_ms\n' > "$tsv_file"

    local module
    module=$(detect_single_module "$repo")
    local pkg
    pkg=$(detect_test_package "$repo")
    local compiler_args
    compiler_args=$(detect_compiler_args "$repo")
    local extra_mvn_args=""
    type detect_extra_mvn_args &>/dev/null && extra_mvn_args=$(detect_extra_mvn_args "$repo")
    local base_args=(-B --fail-at-end -Denforcer.skip=true -Drat.skip=true -Djacoco.skip=true
                     -Dmaven.build.cache.enabled=false)
    [[ -n "$compiler_args" ]] && base_args+=($compiler_args)
    if [[ -n "$extra_mvn_args" ]]; then
        eval "base_args+=($extra_mvn_args)"
    fi
    [[ -n "$pkg" ]] && base_args+=("-Dtestorder.includePackages=$pkg")
    local mvn_args=("${base_args[@]}")
    [[ -n "$module" && "$module" != "NONE" ]] && mvn_args+=(-pl "$module" -am)

    cd "$dir"
    inject_maven_plugin "$repo" "$module"

    # Select mutation target once (reused across all cells)
    local target_src
    target_src=$(select_mutation_target "$repo" "$module" "$dir/.test-order/hashes")
    if [[ -z "$target_src" ]]; then
        warn "No mutation target found — skipping matrix for $repo"
        remove_maven_plugin "$repo" "$module"
        return 0
    fi
    local fqcn
    fqcn=$(path_to_fqcn "$target_src")
    log "Matrix target: $fqcn"

    # ── Malformed-invocation cell 1: no prior learn ───────────────────────
    log "Cell[malformed-no-learn]: show without any prior learn"
    rm -rf "$dir/.test-order" 2>/dev/null || true
    find "$dir" -maxdepth 3 -name ".test-order" -type d -exec rm -rf {} + 2>/dev/null || true
    local ml_out
    ml_out=$(timeout 120 mvn me.bechberger:test-order-maven-plugin:show \
        "${base_args[@]}" 2>&1) || true
    local ml_rc=$?
    if echo "$ml_out" | grep -qi "dependency index\|no index\|please run.*learn\|no data"; then
        ok "  [GOOD ERROR] no-prior-learn cell: got friendly message"
        printf '%s\tmalformed-no-learn\tN/A\tN/A\tN/A\tN/A\tN/A\t%s\t1\t1\t0\n' "$repo" "$fqcn" >> "$tsv_file"
    else
        warn "  [BAD ERROR] no-prior-learn cell: unexpected output (rc=$ml_rc)"
        printf '%s\tmalformed-no-learn\tN/A\tN/A\tN/A\tN/A\tN/A\t%s\t1\t0\t0\n' "$repo" "$fqcn" >> "$tsv_file"
    fi

    # ── Grid cells ───────────────────────────────────────────────────────
    local instr_modes=("CLASS" "METHOD" "MEMBER")
    local bcd_modes=("true" "false")
    local depth_modes=("1" "3")
    local mutators=("mutate_body" "mutate_touch_only")

    for instr in "${instr_modes[@]}"; do
        for bcd in "${bcd_modes[@]}"; do
            for depth in "${depth_modes[@]}"; do
                local cell="${instr}-bcd${bcd}-d${depth}"
                log "Cell[$cell]"

                # Clean and build baseline learn for this cell
                rm -rf "$dir/.test-order" 2>/dev/null || true
                find "$dir" -maxdepth 3 -name ".test-order" -type d -exec rm -rf {} + 2>/dev/null || true
                local cell_learn_args=("${mvn_args[@]}"
                    "-Dtestorder.instrumentation.mode=$instr"
                    "-Dtestorder.bytecodeChangeDetectionEnabled=$bcd"
                    "-Dtestorder.staticAnalysis.depth=$depth")

                timeout 300 mvn_learn "${cell_learn_args[@]}" \
                    > "$matrix_dir/${cell}-learn-A.log" 2>&1 \
                    || warn "  Learn-A failed for cell $cell (may still have index)"

                local idx
                idx=$(find -L "$dir" ! -path "*precheck*" -name "test-dependencies.lz4" -print -quit 2>/dev/null)
                if [[ -z "$idx" ]]; then
                    warn "  No index after learn-A for cell $cell — skipping"
                    for mut in "${mutators[@]}"; do
                        printf '%s\t%s\t%s\t%s\t%s\t%s\thash\t%s\tskip\tskip\t0\n' \
                            "$repo" "$cell" "$instr" "$bcd" "$depth" "$mut" "$fqcn" >> "$tsv_file"
                    done
                    continue
                fi

                # Build cmd_args for show (no -am)
                local idx_dir
                idx_dir=$(dirname "$(dirname "$idx")")
                local idx_module=""
                [[ "$idx_dir" != "$dir" ]] && idx_module=$(basename "$idx_dir")
                local cell_cmd_args=("${base_args[@]}"
                    "-Dtestorder.instrumentation.mode=$instr"
                    "-Dtestorder.bytecodeChangeDetectionEnabled=$bcd"
                    "-Dtestorder.staticAnalysis.depth=$depth")
                [[ -n "$idx_module" ]] && cell_cmd_args+=(-pl "$idx_module")

                for mut in "${mutators[@]}"; do
                    local is_touch=0
                    [[ "$mut" == "mutate_touch_only" ]] && is_touch=1
                    local expected
                    [[ "$is_touch" -eq 0 ]] && expected=1 || expected=0

                    # Apply mutation
                    "$mut" "$target_src"
                    [[ "$is_touch" -eq 0 ]] && git -C "$dir" add -f "$target_src" 2>/dev/null || true

                    # Learn-B
                    timeout 300 mvn_learn "${cell_learn_args[@]}" \
                        > "$matrix_dir/${cell}-${mut}-learn-B.log" 2>&1 \
                        || warn "  Learn-B failed for $cell/$mut"

                    # Assert since-last-run mode (primary for matrix)
                    local t0=$SECONDS
                    local show_log_m="$matrix_dir/${cell}-${mut}-show.log"
                    local detected=0
                    mvn me.bechberger:test-order-maven-plugin:show \
                        -Dtestorder.changeMode=since-last-run \
                        -Dtestorder.show.limit=-1 \
                        "${cell_cmd_args[@]}" \
                        > "$show_log_m" 2>&1 || true
                    if grep -q "Changed classes:.*$fqcn" "$show_log_m" 2>/dev/null; then
                        detected=1
                    fi
                    local dt=$(( (SECONDS - t0) * 1000 ))

                    printf '%s\t%s\t%s\t%s\t%s\t%s\tsince-last-run\t%s\t%s\t%s\t%s\n' \
                        "$repo" "$cell" "$instr" "$bcd" "$depth" "$mut" \
                        "$fqcn" "$expected" "$detected" "$dt" >> "$tsv_file"

                    # Revert
                    revert_mutation "$target_src"
                    git -C "$dir" checkout -- "$target_src" 2>/dev/null || true
                done
            done
        done
    done

    ok "Matrix complete for $repo"
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

    local mvn_args=(-B -Denforcer.skip=true -Drat.skip=true -Djacoco.skip=true
                    -Dmaven.build.cache.enabled=false)
    [[ -n "$pkg" ]] && mvn_args+=("-Dtestorder.includePackages=$pkg")
    [[ -n "$module" && "$module" != "NONE" ]] && mvn_args+=(-pl "$module" -am)

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

    if [[ ! -d "$THIRD_PARTY/$repo" ]]; then
        warn "Skipping $repo — directory not found: $THIRD_PARTY/$repo"
        return 0
    fi

    if is_maven_repo "$repo"; then
        case "$phase" in
            learn)            phase_learn_maven "$repo" ;;
            order)            phase_order_maven "$repo" ;;
            select)           phase_select_maven "$repo" ;;
            tiered)           phase_tiered_maven "$repo" ;;
            bugs)             phase_bugs_maven "$repo" ;;
            auto)             phase_auto_maven "$repo" ;;
            full)             phase_full_maven "$repo" ;;
            synthetic-history)phase_synthetic_history_all_maven "$repo" ;;
            matrix)           tag_repo_attrs "$repo"; phase_matrix_maven "$repo" ;;
            *)                err "Unknown phase: $phase" ;;
        esac
    elif is_gradle_repo "$repo"; then
        case "$phase" in
            learn)   phase_learn_gradle "$repo" ;;
            order)   phase_order_gradle "$repo" ;;
            select)  phase_select_gradle "$repo" ;;
            bugs)    phase_bugs_gradle "$repo" ;;
            full)    phase_full_gradle "$repo" ;;
            synthetic-history) phase_synthetic_history_all_gradle "$repo" ;;
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
        learn|order|select|tiered|bugs|auto|full|synthetic-history|matrix)
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
            echo "  install           - Install test-order to local Maven repo"
            echo "  learn             - Run learn mode on all repos"
            echo "  order             - Run order mode (requires prior learn)"
            echo "  select            - Run select + run-remaining"
            echo "  tiered            - Run 3-tier CI pipeline"
            echo "  bugs              - Inject synthetic bugs and verify detection"
            echo "  auto              - Run auto mode (learn→order transition)"
            echo "  full              - Complete workflow (all of the above)"
            echo "  synthetic-history - Validate change detection: learn → mutate → learn → assert"
            echo "  matrix            - Cartesian settings grid (instrumentation × bcd × depth)"
            echo "  quick             - Full workflow on small repos only"
            echo "  medium            - Full workflow on medium repos"
            echo "  large             - Full workflow on large repos"
            echo "  all               - Everything on every repo"
            echo "  regression        - Full workflow on known-working regression set"
            echo ""
            echo "Repos: ${MAVEN_REPOS[*]} ${GRADLE_REPOS[*]}"
            exit 1
            ;;
    esac

    section "DONE — Results in $RESULTS_DIR"
}

main "$@"
