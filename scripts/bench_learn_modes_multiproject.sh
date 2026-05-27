#!/usr/bin/env bash
# Benchmark learn modes across multiple projects.
#
# Compares FULL, METHOD, MEMBER and baseline across:
#   - cloud-sdk-java (large enterprise, Maven, ~1000 main + ~750 test classes)
#   - jackson-databind (medium, Maven, ~600 main + ~780 test classes)
#   - commons-lang (medium OSS, Maven, ~260 main + ~240 test classes)
#   - javaparser-core (medium, Maven, multi-module)
#   - spring-boot (large, Gradle, multi-module)
#
# Measures:
#   - Wall-clock time (single run per mode per project)
#   - Index file size (test-dependencies.lz4)
#   - Total .test-order directory size
#
# Usage:
#   ./scripts/bench_learn_modes_multiproject.sh [--quick] [--no-gradle] [--repeat N] [--member-only]
#
# Options:
#   --quick        Only run cloud-sdk-java with a single module (fast iteration)
#   --no-gradle    Skip Gradle-based projects (spring-boot)
#   --repeat N     Run each mode N times (default: 1). Reports min/avg/max.
#   --member-only  Compare only baseline vs MEMBER (skip CLASS and METHOD)

set -euo pipefail

# Track injected POMs for cleanup on exit/interrupt
declare -a INJECTED_POMS=()
cleanup() {
    for pom in "${INJECTED_POMS[@]}"; do
        perl -i -ne 'print unless /bench-test-order-plugin/ .. /bench-test-order-plugin/' "$pom" 2>/dev/null || true
    done
}
trap cleanup EXIT INT TERM

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="${SCRIPT_DIR}/.."

# Projects: name|dir|module_flag|build_tool|java_sdk
# java_sdk is the SDKMAN identifier (e.g. "21-sapmchn", "25-sapmchn")
declare -a PROJECTS=()

CLOUD_SDK_DIR="${PROJECT_ROOT}/demo/dcom-presentation/cloud-sdk-java"
JACKSON_DIR="${PROJECT_ROOT}/third-party/jackson-databind"
COMMONS_LANG_DIR="${PROJECT_ROOT}/third-party/commons-lang"
COMMONS_COLLECTIONS_DIR="${PROJECT_ROOT}/third-party/commons-collections"
COMMONS_IO_DIR="${PROJECT_ROOT}/third-party/commons-io"
JAVAPARSER_DIR="${PROJECT_ROOT}/third-party/javaparser"
LOG4J2_DIR="${PROJECT_ROOT}/third-party/logging-log4j2"
NETTY_DIR="${PROJECT_ROOT}/third-party/netty"  # excluded from bench: pom enforces strict XML format check that conflicts with plugin injection
SPRING_BOOT_DIR="${PROJECT_ROOT}/third-party/spring-boot"
CDS_ATTACHMENTS_DIR="${PROJECT_ROOT}/third-party/cds-feature-attachments"
AI_SDK_DIR="${PROJECT_ROOT}/third-party/ai-sdk-java"
NEONBEE_DIR="${PROJECT_ROOT}/third-party/neonbee"

# ── SDKMAN helper ─────────────────────────────────────────────────────────
# Switch Java version via SDKMAN for a project. Expects SDKMAN to be available.
SDKMAN_INIT="${SDKMAN_DIR:-$HOME/.sdkman}/bin/sdkman-init.sh"

sdk_use_java() {
    local java_sdk="$1"
    if [[ -z "$java_sdk" ]]; then
        return 0  # no version specified, keep current
    fi
    if [[ ! -f "$SDKMAN_INIT" ]]; then
        echo "    WARNING: SDKMAN not found at $SDKMAN_INIT — skipping Java switch"
        return 0
    fi
    # Source SDKMAN and switch (disable nounset: sdkman-init.sh uses ZSH_VERSION unguarded)
    set +u
    source "$SDKMAN_INIT"
    sdk use java "$java_sdk" > /dev/null 2>&1 || {
        set -u
        echo "    WARNING: Failed to switch to Java $java_sdk (not installed?)"
        echo "    Install with: sdk install java $java_sdk"
        return 1
    }
    set -u
}

QUICK=false
NO_GRADLE=false
REPEAT=1
MEMBER_ONLY=false
NO_BASELINE=false
declare -a SKIP_PROJECTS=()
declare -a ONLY_PROJECTS=()
usage() {
    cat <<'EOF'
Usage: bench_learn_modes_multiproject.sh [OPTIONS]

Options:
  --quick           Only run cloud-sdk-java with a single module (fast iteration)
  --no-gradle       Skip Gradle-based projects (spring-boot)
  --repeat N        Run each mode N times (default: 1). Reports min/avg/max.
  --member-only     Compare only baseline vs MEMBER (skip CLASS and METHOD)
  --no-baseline     Skip the baseline (no-instrumentation) run
  --skip=<name>     Skip a specific project by name (may be repeated)
  --project=<name>  Run only the named project(s). Substring match on the
                    project label (e.g. "cloud-sdk", "jackson", "cloud-sdk(core)").
                    May be repeated, or comma-separated: --project=jackson,commons-lang
  -h, --help        Show this help message and exit
EOF
}

for arg in "$@"; do
    case "$arg" in
        -h|--help) usage; exit 0 ;;
        --quick) QUICK=true ;;
        --no-gradle) NO_GRADLE=true ;;
        --member-only) MEMBER_ONLY=true ;;
        --no-baseline) NO_BASELINE=true ;;
        --skip=*) SKIP_PROJECTS+=("${arg#--skip=}") ;;
        --project=*)
            # Allow comma-separated values: --project=jackson,commons-lang
            IFS=',' read -ra _projs <<< "${arg#--project=}"
            for p in "${_projs[@]}"; do
                [[ -n "$p" ]] && ONLY_PROJECTS+=("$p")
            done
            ;;
        --repeat=*) REPEAT="${arg#--repeat=}" ;;
        --repeat) ;; # handled below with next arg
        *)
            # Handle "--repeat N" (two separate args)
            if [[ "${prev_arg:-}" == "--repeat" ]]; then
                REPEAT="$arg"
            else
                echo "Unknown arg: $arg"; usage; exit 1
            fi
            ;;
    esac
    prev_arg="$arg"
done

if ! [[ "$REPEAT" =~ ^[0-9]+$ ]] || (( REPEAT < 1 )); then
    echo "Error: --repeat must be a positive integer (got: $REPEAT)"
    exit 1
fi

# Validate projects exist
ALL_DIRS=("$CLOUD_SDK_DIR" "$JACKSON_DIR" "$COMMONS_LANG_DIR" "$COMMONS_COLLECTIONS_DIR" "$COMMONS_IO_DIR" "$JAVAPARSER_DIR" "$LOG4J2_DIR" "$SPRING_BOOT_DIR" "$CDS_ATTACHMENTS_DIR" "$AI_SDK_DIR" "$NEONBEE_DIR")
MISSING=false
for dir in "${ALL_DIRS[@]}"; do
    if [[ ! -d "$dir" ]]; then
        echo "WARNING: $dir not found"
        MISSING=true
    fi
done

if [[ "$MISSING" == "true" ]]; then
    echo ""
    echo "Some projects are missing. Run: bash scripts/setup-example-repos.sh"
    echo "Continuing with available projects..."
    echo ""
fi

# Setup project list
# Format: name|dir|module_flag|build_tool|java_sdk
if [[ "$QUICK" == "true" ]]; then
    if [[ -d "$CLOUD_SDK_DIR" ]]; then
        PROJECTS+=("cloud-sdk(core)|${CLOUD_SDK_DIR}|-pl cloudplatform/cloudplatform-core -am|maven|21-sapmchn")
    fi
else
    if [[ -d "$CLOUD_SDK_DIR" ]]; then
        PROJECTS+=("cloud-sdk(full)|${CLOUD_SDK_DIR}||maven|21-sapmchn")
        PROJECTS+=("cloud-sdk(core)|${CLOUD_SDK_DIR}|-pl cloudplatform/cloudplatform-core -am|maven|21-sapmchn")
    fi
    if [[ -d "$JACKSON_DIR" ]]; then
        PROJECTS+=("jackson-databind|${JACKSON_DIR}||maven|21-sapmchn")
    fi
    if [[ -d "$COMMONS_LANG_DIR" ]]; then
        PROJECTS+=("commons-lang|${COMMONS_LANG_DIR}||maven|21-sapmchn")
    fi
    if [[ -d "$COMMONS_COLLECTIONS_DIR" ]]; then
        PROJECTS+=("commons-collections|${COMMONS_COLLECTIONS_DIR}||maven|21-sapmchn")
    fi
    if [[ -d "$COMMONS_IO_DIR" ]]; then
        PROJECTS+=("commons-io|${COMMONS_IO_DIR}||maven|21-sapmchn")
    fi
    if [[ -d "$JAVAPARSER_DIR" ]]; then
        # javaparser-core has no tests; use javaparser-core-testing which has ~247 test files
        PROJECTS+=("javaparser|${JAVAPARSER_DIR}|-pl javaparser-core-testing -am|maven|17.0.18-sapmchn")
    fi
    if [[ -d "$LOG4J2_DIR" ]]; then
        # log4j2(full) excluded: log4j-core's `generate-plugin-descriptors` annotation processor
        # loads instrumented log4j-api classes from upstream reactor module; processor classpath
        # lacks test-order-agent runtime → NoClassDefFoundError on UsageStore.
        PROJECTS+=("log4j-core-test|${LOG4J2_DIR}|-pl log4j-core-test -am|maven|21-sapmchn")
    fi
    if [[ -d "$NETTY_DIR" ]]; then
        : # excluded: netty's xml-maven-plugin check-format rejects injected plugin indentation
    fi
    if [[ "$NO_GRADLE" != "true" && -d "$SPRING_BOOT_DIR" ]]; then
        PROJECTS+=("spring-boot|${SPRING_BOOT_DIR}||gradle|25-sapmchn")
    fi
    if [[ -d "$CDS_ATTACHMENTS_DIR" ]]; then
        # cds-feature-attachments main module has ~51 test files; skip integration-tests (require CDS runtime)
        PROJECTS+=("cds-attachments|${CDS_ATTACHMENTS_DIR}|-pl cds-feature-attachments -am|maven|21-sapmchn")
    fi
    if [[ -d "$AI_SDK_DIR" ]]; then
        # ai-sdk-java core module has ~14 test files; orchestration has ~13
        PROJECTS+=("ai-sdk(core)|${AI_SDK_DIR}|-pl core -am|maven|21-sapmchn")
    fi
    if [[ "$NO_GRADLE" != "true" && -d "$NEONBEE_DIR" ]]; then
        PROJECTS+=("neonbee|${NEONBEE_DIR}||gradle|21-sapmchn")
    fi
fi

if [[ ${#PROJECTS[@]} -eq 0 ]]; then
    echo "Error: No projects available to benchmark."
    exit 1
fi

MODES=("CLASS" "METHOD" "MEMBER")
if [[ "$MEMBER_ONLY" == "true" ]]; then
    MODES=("MEMBER")
fi
MVN_COMMON="-Dspotless.check.skip=true -Dmaven.test.failure.ignore=true -Dmdep.analyze.skip=true -Denforcer.skip=true -Drat.skip=true -Danimal.sniffer.skip=true -Dsurefire.skipAfterFailureCount=50 -Dexcludes=**/XsuaaSecurityTest.java -q"
GRADLE_COMMON="--no-daemon -q"

RESULTS_DIR="/tmp/bench-learn-multiproject-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS_DIR"

# Always install the latest test-order artifacts so the benchmark uses current code.
echo "Installing test-order artifacts..."
(cd "$PROJECT_ROOT" && mvn install -DskipTests --no-transfer-progress -q) || {
    echo "ERROR: mvn install failed — benchmark may use stale JARs"
}

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║       Learn Modes Benchmark — Multi-Project Comparison      ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "  Results: $RESULTS_DIR"
echo "  Modes:   ${MODES[*]}"
echo "  Repeat:  $REPEAT"
echo "  Projects:"
for entry in "${PROJECTS[@]}"; do
    IFS='|' read -r name dir module_flag build_tool java_sdk <<< "$entry"
    echo "    - $name ($build_tool, java=$java_sdk)"
done
echo ""

# CSV header
CSV="$RESULTS_DIR/results.csv"
echo "project,mode,iteration,time_sec,index_bytes,total_bytes" > "$CSV"

# ── Plugin injection for Maven projects ───────────────────────────
# Ensures test-order-maven-plugin is present with the correct version.
# If already present (from prior setup), fixes the version.
# Otherwise injects a fresh plugin block with a marker for clean removal.

PLUGIN_MARKER="<!-- bench-test-order-plugin -->"
PLUGIN_VERSION="0.0.1-SNAPSHOT"

inject_plugin_maven() {
    local pom="$1"
    if grep -q "$PLUGIN_MARKER" "$pom" 2>/dev/null; then
        return 0  # already injected by us
    fi
    # If plugin already exists (from prior manual setup), fix version and ensure
    # <extensions>true</extensions> is present so the lifecycle participant runs.
    if grep -q "test-order-maven-plugin" "$pom" 2>/dev/null; then
        perl -i -pe "s|<version>[^<]*</version>|<version>$PLUGIN_VERSION</version>| if /test-order-maven-plugin/ .. /<\\/plugin>/ and /<version>/" "$pom"
        # Add <extensions>true</extensions> right after the </version> line if not already present in the plugin block
        perl -i -0777 -pe 's{(<artifactId>test-order-maven-plugin</artifactId>\s*<version>[^<]*</version>)(?!\s*<extensions>)}{$1\n            <extensions>true</extensions>}g' "$pom"
        return 0
    fi

    local plugin_xml
    plugin_xml=$(cat <<XMLEOF
        $PLUGIN_MARKER
        <plugin>
            <groupId>me.bechberger</groupId>
            <artifactId>test-order-maven-plugin</artifactId>
            <version>$PLUGIN_VERSION</version>
            <extensions>true</extensions>
            <executions>
                <execution>
                    <goals><goal>prepare</goal></goals>
                </execution>
            </executions>
        </plugin>
        $PLUGIN_MARKER
XMLEOF
)

    # Case 1: POM has <build><plugins> (NOT inside <pluginManagement>) — inject after it
    # Uses Perl to track whether we're inside <pluginManagement> to avoid injecting there
    if grep -q '<plugins>' "$pom" 2>/dev/null; then
        perl -i -pe "
            \$in_pm++ if /<pluginManagement>/;
            \$in_pm-- if /<\\/pluginManagement>/;
            if (/<plugins>/ && !\$in_pm && !\$done) {
                \$_ .= qq{$plugin_xml\n};
                \$done = 1;
            }
        " "$pom"
        # Check if injection actually happened (might not if all <plugins> were inside pluginManagement)
        if grep -q "$PLUGIN_MARKER" "$pom" 2>/dev/null; then
            return 0
        fi
    fi

    # Case 2: POM has <build> but no direct <plugins> — inject <plugins> block
    # Insert right before </build> (but not </pluginManagement></build>)
    if grep -q '<build>' "$pom" 2>/dev/null; then
        perl -i -pe "
            if (/<\\/build>/ && !\$done) {
                \$_ = \"    <plugins>\n$plugin_xml\n    </plugins>\n\" . \$_;
                \$done = 1;
            }
        " "$pom"
    # Case 3: POM has no <build> at all — inject before </project>
    else
        perl -i -pe "
            if (/<\\/project>/ && !\$done) {
                \$_ = \"    <build>\n        <plugins>\n$plugin_xml\n        </plugins>\n    </build>\n\" . \$_;
                \$done = 1;
            }
        " "$pom"
    fi
}

remove_plugin_maven() {
    local pom="$1"
    if ! grep -q "$PLUGIN_MARKER" "$pom" 2>/dev/null; then
        return 0  # nothing to remove (was pre-existing or version-fixed)
    fi
    perl -i -ne "print unless /$PLUGIN_MARKER/ .. /$PLUGIN_MARKER/" "$pom"
}

# Find the effective POM to inject into for a given project dir + module_flag
find_target_pom() {
    local project_dir="$1"
    local module_flag="$2"

    if [[ -n "$module_flag" && "$module_flag" == *-pl* ]]; then
        # Extract the first module path from -pl flag
        local module_path
        module_path=$(echo "$module_flag" | sed 's/.*-pl *//' | sed 's/ .*//' | cut -d, -f1)
        local candidate="$project_dir/$module_path/pom.xml"
        if [[ -f "$candidate" ]]; then
            echo "$candidate"
            return
        fi
    fi
    echo "$project_dir/pom.xml"
}

# Human-readable size
numfmt_size() {
    local bytes=$1
    if (( bytes >= 1048576 )); then
        printf "%.1fM" "$(echo "$bytes / 1048576" | bc -l)"
    elif (( bytes >= 1024 )); then
        printf "%.1fK" "$(echo "$bytes / 1024" | bc -l)"
    else
        printf "%dB" "$bytes"
    fi
}

# Measure index/total size from .test-order dirs
measure_sizes() {
    local project_dir="$1"
    local index_file
    index_file=$(find "$project_dir" -path '*/.test-order/test-dependencies.lz4' -type f 2>/dev/null | head -1)
    if [[ -n "$index_file" ]]; then
        INDEX_BYTES=$(stat -f%z "$index_file" 2>/dev/null || stat --printf='%s' "$index_file" 2>/dev/null || echo 0)
    else
        INDEX_BYTES=0
    fi
    TOTAL_BYTES=$(find "$project_dir" -path '*/.test-order/*' -type f -exec stat -f%z {} + 2>/dev/null | awk '{s+=$1} END {print s+0}' || echo 0)
}

# ── Maven functions ───────────────────────────────────────────────

run_mode_maven() {
    local project_name="$1" project_dir="$2" module_flag="$3" mode="$4"
    cd "$project_dir"

    local times=()
    for (( iter=1; iter<=REPEAT; iter++ )); do
        find "$project_dir" -name '.test-order' -type d -exec rm -rf {} + 2>/dev/null || true
        # Clean offline instrumentation artifacts so each run starts fresh
        find "$project_dir" -path '*/target/.test-order' -type d -exec rm -rf {} + 2>/dev/null || true

        local start_ns end_ns elapsed_sec
        start_ns=$(python3 -c 'import time; print(time.time_ns())')

        mvn test $module_flag $MVN_COMMON \
            -Dtestorder.mode=learn \
            -Dtestorder.instrumentation.mode="$mode" \
            > "$RESULTS_DIR/log_${project_name}_${mode}_${iter}.txt" 2>&1 || true

        end_ns=$(python3 -c 'import time; print(time.time_ns())')
        elapsed_sec=$(python3 -c "print(f'{($end_ns - $start_ns) / 1e9:.2f}')")
        times+=("$elapsed_sec")

        measure_sizes "$project_dir"
        echo "$project_name,$mode,$iter,$elapsed_sec,$INDEX_BYTES,$TOTAL_BYTES" >> "$CSV"
    done

    if (( REPEAT == 1 )); then
        printf "    %-14s %7ss  index=%s  total=%s\n" "$mode" "${times[0]}" \
            "$(numfmt_size $INDEX_BYTES)" "$(numfmt_size $TOTAL_BYTES)"
    else
        local stats
        stats=$(python3 -c "
import statistics
t = [float(x) for x in '${times[*]}'.split()]
print(f'min={min(t):.2f} avg={statistics.mean(t):.2f} max={max(t):.2f}')
")
        printf "    %-14s %s  (%d runs)  index=%s  total=%s\n" "$mode" "$stats" "$REPEAT" \
            "$(numfmt_size $INDEX_BYTES)" "$(numfmt_size $TOTAL_BYTES)"
    fi
}

run_baseline_maven() {
    local project_name="$1" project_dir="$2" module_flag="$3"
    cd "$project_dir"

    local times=()
    for (( iter=1; iter<=REPEAT; iter++ )); do
        local start_ns end_ns elapsed_sec
        start_ns=$(python3 -c 'import time; print(time.time_ns())')

        mvn test $module_flag $MVN_COMMON \
            -Dtestorder.skip=true -DargLine=' ' \
            > "$RESULTS_DIR/log_${project_name}_baseline_${iter}.txt" 2>&1 || true

        end_ns=$(python3 -c 'import time; print(time.time_ns())')
        elapsed_sec=$(python3 -c "print(f'{($end_ns - $start_ns) / 1e9:.2f}')")
        times+=("$elapsed_sec")

        echo "$project_name,baseline,$iter,$elapsed_sec,0,0" >> "$CSV"
    done

    if (( REPEAT == 1 )); then
        printf "    %-14s %7ss  (no instrumentation)\n" "baseline" "${times[0]}"
    else
        local stats
        stats=$(python3 -c "
import statistics
t = [float(x) for x in '${times[*]}'.split()]
print(f'min={min(t):.2f} avg={statistics.mean(t):.2f} max={max(t):.2f}')
")
        printf "    %-14s %s  (%d runs, no instrumentation)\n" "baseline" "$stats" "$REPEAT"
    fi
}

# ── Gradle functions ──────────────────────────────────────────────

run_mode_gradle() {
    local project_name="$1" project_dir="$2" module_flag="$3" mode="$4"
    cd "$project_dir"

    local init_script="${project_dir}/test-order-init.gradle"
    local canonical="${PROJECT_ROOT}/test-order-gradle-plugin/test-order-init.gradle"
    # Always copy the canonical init script to ensure correct version
    if [[ -f "$canonical" ]]; then
        cp "$canonical" "$init_script"
    elif [[ ! -f "$init_script" ]]; then
        echo "    ERROR: missing $init_script and $canonical"
        for (( iter=1; iter<=REPEAT; iter++ )); do
            echo "$project_name,$mode,$iter,0,0,0" >> "$CSV"
        done
        return
    fi

    # module_flag for gradle is e.g. "-p spring-boot-project:spring-boot"
    local task_arg=""
    if [[ -n "$module_flag" && "$module_flag" == -p* ]]; then
        local subproject="${module_flag#-p }"
        task_arg="${subproject}:test"
    else
        task_arg="test"
    fi

    local times=()
    for (( iter=1; iter<=REPEAT; iter++ )); do
        find "$project_dir" -name '.test-order' -type d -exec rm -rf {} + 2>/dev/null || true
        # Clean offline instrumentation artifacts so each run starts fresh
        find "$project_dir" -path '*/build/.test-order' -type d -exec rm -rf {} + 2>/dev/null || true

        local start_ns end_ns elapsed_sec
        start_ns=$(python3 -c 'import time; print(time.time_ns())')

        ./gradlew $task_arg \
            --init-script "$init_script" \
            -Dtestorder.mode=learn \
            -Dtestorder.instrumentation.mode="$mode" \
            $GRADLE_COMMON --continue \
            > "$RESULTS_DIR/log_${project_name}_${mode}_${iter}.txt" 2>&1 || true

        end_ns=$(python3 -c 'import time; print(time.time_ns())')
        elapsed_sec=$(python3 -c "print(f'{($end_ns - $start_ns) / 1e9:.2f}')")
        times+=("$elapsed_sec")

        measure_sizes "$project_dir"
        echo "$project_name,$mode,$iter,$elapsed_sec,$INDEX_BYTES,$TOTAL_BYTES" >> "$CSV"
    done

    if (( REPEAT == 1 )); then
        printf "    %-14s %7ss  index=%s  total=%s\n" "$mode" "${times[0]}" \
            "$(numfmt_size $INDEX_BYTES)" "$(numfmt_size $TOTAL_BYTES)"
    else
        local stats
        stats=$(python3 -c "
import statistics
t = [float(x) for x in '${times[*]}'.split()]
print(f'min={min(t):.2f} avg={statistics.mean(t):.2f} max={max(t):.2f}')
")
        printf "    %-14s %s  (%d runs)  index=%s  total=%s\n" "$mode" "$stats" "$REPEAT" \
            "$(numfmt_size $INDEX_BYTES)" "$(numfmt_size $TOTAL_BYTES)"
    fi
}

run_baseline_gradle() {
    local project_name="$1" project_dir="$2" module_flag="$3"
    cd "$project_dir"

    local task_arg=""
    if [[ -n "$module_flag" && "$module_flag" == -p* ]]; then
        local subproject="${module_flag#-p }"
        task_arg="${subproject}:test"
    else
        task_arg="test"
    fi

    local times=()
    for (( iter=1; iter<=REPEAT; iter++ )); do
        local start_ns end_ns elapsed_sec
        start_ns=$(python3 -c 'import time; print(time.time_ns())')

        ./gradlew $task_arg $GRADLE_COMMON --continue \
            -Dtestorder.skip=true \
            > "$RESULTS_DIR/log_${project_name}_baseline_${iter}.txt" 2>&1 || true

        end_ns=$(python3 -c 'import time; print(time.time_ns())')
        elapsed_sec=$(python3 -c "print(f'{($end_ns - $start_ns) / 1e9:.2f}')")
        times+=("$elapsed_sec")

        echo "$project_name,baseline,$iter,$elapsed_sec,0,0" >> "$CSV"
    done

    if (( REPEAT == 1 )); then
        printf "    %-14s %7ss  (no instrumentation)\n" "baseline" "${times[0]}"
    else
        local stats
        stats=$(python3 -c "
import statistics
t = [float(x) for x in '${times[*]}'.split()]
print(f'min={min(t):.2f} avg={statistics.mean(t):.2f} max={max(t):.2f}')
")
        printf "    %-14s %s  (%d runs, no instrumentation)\n" "baseline" "$stats" "$REPEAT"
    fi
}

# ── Run benchmarks ────────────────────────────────────────────────

for entry in "${PROJECTS[@]}"; do
    IFS='|' read -r name dir module_flag build_tool java_sdk <<< "$entry"

    # Check if --project filter is set and this one doesn't match
    if [[ ${#ONLY_PROJECTS[@]} -gt 0 ]]; then
        match=false
        for only_name in "${ONLY_PROJECTS[@]}"; do
            if [[ "$name" == *"$only_name"* ]]; then
                match=true
                break
            fi
        done
        if [[ "$match" == "false" ]]; then
            continue
        fi
    fi

    # Check if this project should be skipped
    skip_this=false
    for skip_name in "${SKIP_PROJECTS[@]}"; do
        if [[ "$name" == *"$skip_name"* ]]; then
            skip_this=true
            break
        fi
    done
    if [[ "$skip_this" == "true" ]]; then
        echo "  Skipping $name (--skip=$skip_name)"
        continue
    fi

    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  Project: $name ($build_tool)"
    echo "  Dir:     $dir"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""

    # Switch Java version via SDKMAN
    if [[ -n "$java_sdk" ]]; then
        echo "  Switching to Java $java_sdk..."
        if ! sdk_use_java "$java_sdk"; then
            echo "  SKIPPING $name (Java $java_sdk not available)"
            echo ""
            continue
        fi
    fi

    # Warmup: compile once
    echo "  Warming up (compile)..."
    cd "$dir"
    if [[ "$build_tool" == "gradle" ]]; then
        local_init="${dir}/test-order-init.gradle"
        canonical="${PROJECT_ROOT}/test-order-gradle-plugin/test-order-init.gradle"
        if [[ -f "$canonical" ]]; then
            cp "$canonical" "$local_init"
        fi
        if [[ -f "$local_init" ]]; then
            ./gradlew compileTestJava $GRADLE_COMMON --init-script "$local_init" > /dev/null 2>&1 || true
        else
            ./gradlew compileTestJava $GRADLE_COMMON > /dev/null 2>&1 || true
        fi
    else
        mvn clean test-compile $module_flag $MVN_COMMON > /dev/null 2>&1 || true
    fi
    echo ""

    echo "  Results:"
    if [[ "$build_tool" == "gradle" ]]; then
        [[ "$NO_BASELINE" != "true" ]] && run_baseline_gradle "$name" "$dir" "$module_flag"
        for mode in "${MODES[@]}"; do
            run_mode_gradle "$name" "$dir" "$module_flag" "$mode"
        done
    else
        # Inject test-order plugin into POM
        target_pom=$(find_target_pom "$dir" "$module_flag")
        inject_plugin_maven "$target_pom"
        INJECTED_POMS+=("$target_pom")
        echo "  (plugin injected into $(basename "$(dirname "$target_pom")")/pom.xml)"

        [[ "$NO_BASELINE" != "true" ]] && run_baseline_maven "$name" "$dir" "$module_flag"
        for mode in "${MODES[@]}"; do
            run_mode_maven "$name" "$dir" "$module_flag" "$mode"
        done

        # Remove injected plugin
        remove_plugin_maven "$target_pom"
    fi
    echo ""
done

# ── Summary report ────────────────────────────────────────────────

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                     SUMMARY REPORT                          ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# Print comparison table
if (( REPEAT == 1 )); then
    printf "%-18s %-14s %8s %8s %10s %10s\n" "PROJECT" "MODE" "TIME(s)" "OVERHEAD" "INDEX" "TOTAL"
    printf "%-18s %-14s %8s %8s %10s %10s\n" "──────────────────" "──────────────" "────────" "────────" "──────────" "──────────"
else
    printf "%-18s %-14s %8s %8s %8s %8s %10s %10s\n" "PROJECT" "MODE" "MIN(s)" "AVG(s)" "MAX(s)" "OVERHEAD" "INDEX" "TOTAL"
    printf "%-18s %-14s %8s %8s %8s %8s %10s %10s\n" "──────────────────" "──────────────" "────────" "────────" "────────" "────────" "──────────" "──────────"
fi

current_project=""
baseline_time=0

# Aggregate iterations for summary
python3 - "$CSV" "$REPEAT" << 'PYEOF'
import csv, sys
from collections import defaultdict

repeat = int(sys.argv[2])

# Group rows by (project, mode) → list of times
groups = defaultdict(lambda: {'times': [], 'index_bytes': 0, 'total_bytes': 0})
with open(sys.argv[1]) as f:
    for row in csv.DictReader(f):
        key = (row['project'], row['mode'])
        groups[key]['times'].append(float(row['time_sec']))
        groups[key]['index_bytes'] = int(row['index_bytes'])
        groups[key]['total_bytes'] = int(row['total_bytes'])

# Determine print order from CSV
seen = []
seen_set = set()
with open(sys.argv[1]) as f:
    for row in csv.DictReader(f):
        key = (row['project'], row['mode'])
        if key not in seen_set:
            seen.append(key)
            seen_set.add(key)

baselines = {}
for (project, mode), data in groups.items():
    if mode == 'baseline':
        import statistics
        baselines[project] = statistics.mean(data['times'])

def fmt_size(b):
    if b >= 1048576:
        return f"{b/1048576:.1f}M"
    elif b >= 1024:
        return f"{b/1024:.1f}K"
    else:
        return f"{b}B"

for (project, mode) in seen:
    data = groups[(project, mode)]
    times = data['times']
    import statistics
    t_min = min(times)
    t_avg = statistics.mean(times)
    t_max = max(times)

    bt = baselines.get(project, 0)
    if mode == 'baseline':
        overhead = "—"
        idx_str = "—"
        tot_str = "—"
    else:
        if bt > 0:
            overhead = f"+{((t_avg - bt) / bt * 100):.0f}%"
        else:
            overhead = "N/A"
        idx_str = fmt_size(data['index_bytes'])
        tot_str = fmt_size(data['total_bytes'])

    if repeat == 1:
        print(f"{project:<18} {mode:<14} {t_avg:>8.2f} {overhead:>8} {idx_str:>10} {tot_str:>10}")
    else:
        print(f"{project:<18} {mode:<14} {t_min:>8.2f} {t_avg:>8.2f} {t_max:>8.2f} {overhead:>8} {idx_str:>10} {tot_str:>10}")
PYEOF

# ── Data-driven analysis ──────────────────────────────────────────

echo ""
echo "──────────────────────────────────────────────────────────────"
echo ""

python3 - "$CSV" << 'PYEOF'
import csv, sys, statistics
from collections import defaultdict

# Aggregate iterations: use mean time per (project, mode)
groups = defaultdict(lambda: {'times': [], 'index_bytes': 0, 'total_bytes': 0})
with open(sys.argv[1]) as f:
    for row in csv.DictReader(f):
        key = (row['project'], row['mode'])
        groups[key]['times'].append(float(row['time_sec']))
        groups[key]['index_bytes'] = int(row['index_bytes'])
        groups[key]['total_bytes'] = int(row['total_bytes'])

rows = []
for (project, mode), data in groups.items():
    rows.append({
        'project': project,
        'mode': mode,
        'time_sec': statistics.mean(data['times']),
        'index_bytes': data['index_bytes'],
        'total_bytes': data['total_bytes'],
    })

# Group by project
projects = defaultdict(dict)
for r in rows:
    projects[r['project']][r['mode']] = r

print("  DATA-DRIVEN ANALYSIS:")
print()

# Per-mode overhead averages across projects that produced valid data
mode_overheads = defaultdict(list)
mode_index_sizes = defaultdict(list)

for proj, modes in projects.items():
    baseline = modes.get('baseline')
    if not baseline or baseline['time_sec'] <= 0:
        continue
    bt = baseline['time_sec']
    for mode_name in ['CLASS', 'METHOD', 'MEMBER']:
        m = modes.get(mode_name)
        if m and m['time_sec'] > 0:
            overhead_pct = (m['time_sec'] - bt) / bt * 100
            mode_overheads[mode_name].append((proj, overhead_pct))
            if m['index_bytes'] > 0:
                mode_index_sizes[mode_name].append((proj, m['index_bytes']))

# Print average overhead per mode
print("    Average overhead vs baseline (across projects with valid data):")
for mode_name in ['CLASS', 'METHOD', 'MEMBER']:
    entries = mode_overheads.get(mode_name, [])
    if entries:
        avg = sum(o for _, o in entries) / len(entries)
        worst_proj, worst_pct = max(entries, key=lambda x: x[1])
        best_proj, best_pct = min(entries, key=lambda x: x[1])
        print(f"      {mode_name:14s}  avg={avg:+.0f}%  "
              f"range=[{best_pct:+.0f}% .. {worst_pct:+.0f}%]  "
              f"(worst: {worst_proj})")
print()

# Index size comparison (relative to FULL)
print("    Index size relative to FULL mode:")
full_sizes = {proj: sz for proj, sz in mode_index_sizes.get('CLASS', [])}
for mode_name in ['METHOD', 'MEMBER']:
    entries = mode_index_sizes.get(mode_name, [])
    if entries and full_sizes:
        ratios = []
        for proj, sz in entries:
            if proj in full_sizes and full_sizes[proj] > 0:
                ratios.append(sz / full_sizes[proj])
        if ratios:
            avg_ratio = sum(ratios) / len(ratios)
            print(f"      {mode_name:14s}  avg={avg_ratio:.2f}x FULL")
print()

# Data-driven findings
print("    FINDINGS (from measured data):")

full_entries = mode_overheads.get('CLASS', [])
fm_entries = mode_overheads.get('METHOD', [])
fmem_entries = mode_overheads.get('MEMBER', [])

if full_entries and fm_entries:
    full_avg = sum(o for _, o in full_entries) / len(full_entries)
    fm_avg = sum(o for _, o in fm_entries) / len(fm_entries)
    diff = fm_avg - full_avg
    if abs(diff) < 5:
        print(f"      • METHOD vs FULL: negligible difference ({diff:+.0f}%)")
        print(f"        → METHOD could replace FULL as default (same cost, more data)")
    elif diff < 15:
        print(f"      • METHOD adds {diff:.0f}% over FULL — acceptable for the extra granularity")
    else:
        print(f"      • METHOD adds {diff:.0f}% over FULL — keep as opt-in")

if full_entries and fmem_entries:
    full_avg = sum(o for _, o in full_entries) / len(full_entries)
    fmem_avg = sum(o for _, o in fmem_entries) / len(fmem_entries)
    diff = fmem_avg - full_avg
    if diff > 50:
        print(f"      • MEMBER adds {diff:.0f}% over FULL — too expensive, keep opt-in only")
    elif diff > 20:
        print(f"      • MEMBER adds {diff:.0f}% over FULL — significant; opt-in only")
    else:
        print(f"      • MEMBER adds {diff:.0f}% over FULL — surprisingly cheap")

# Detect projects where instrumentation failed
broken = []
for proj, modes in projects.items():
    for mode_name in ['CLASS', 'METHOD', 'MEMBER']:
        m = modes.get(mode_name)
        if m and m['index_bytes'] == 0 and m['time_sec'] > 5:
            broken.append(f"{proj}/{mode_name}")
if broken:
    print()
    print(f"      ⚠ No index produced (plugin may not be configured):")
    for b in broken:
        print(f"          {b}")

print()
PYEOF

echo "Results CSV: $CSV"
echo "Logs: $RESULTS_DIR/log_*.txt"
