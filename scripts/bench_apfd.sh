#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════════
# bench_apfd.sh — APFD benchmark for the conference talk
#
# For each repo × each bug patch:
#   1. Run mvn test with no ordering (alphabetical baseline) — record test class
#      execution order from surefire reports, find position of first failure
#   2. Run mvn test with test-order — same
#   3. Compute APFD for both: 1 - (pos_sum / (n * m)) + 1/(2n)
#
# Output:
#   target/bench-apfd/results.tsv   — tab-separated: repo, patch, n, m,
#                                     alpha_pos, order_pos, alpha_apfd, order_apfd
#   target/bench-apfd/summary.txt   — human-readable per-repo averages
#   target/bench-apfd/chart.json    — JSON ready to paste into BenchmarkChart.vue
#
# Usage:
#   ./scripts/bench_apfd.sh [REPO...]   # omit for all default repos
#
# Requirements:
#   - test-order installed: mvn install -DskipTests in repo root
#   - All repos cloned under third-party/
# ═══════════════════════════════════════════════════════════════════════════════
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
THIRD_PARTY="$ROOT_DIR/third-party"
BUGS_DIR="$SCRIPT_DIR/bugs"
RESULTS_DIR="$ROOT_DIR/target/bench-apfd"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

source "$SCRIPT_DIR/third-party-overrides.sh" 2>/dev/null || true

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; NC='\033[0m'
log()     { echo -e "${BLUE}[$(date +%H:%M:%S)]${NC} $*" >&2; }
ok()      { echo -e "${GREEN}  ✓${NC} $*" >&2; }
warn()    { echo -e "${YELLOW}  ⚠${NC} $*" >&2; }
err()     { echo -e "${RED}  ✗${NC} $*" >&2; }
section() { echo -e "\n${CYAN}═══ $* ═══${NC}" >&2; }

# ─── Repos to benchmark ───────────────────────────────────────────────────────
# Full benchmark set — all repos with verified patches in third-party/.
DEFAULT_REPOS=(
    "commons-collections"
    "commons-io"
    "gson"
    "guava"
    "jackson-core"
    "logbook"
    "spring-ai"
)

PLUGIN_VERSION="0.1.0"
PLUGIN_GROUP="me.bechberger"
PLUGIN_ARTIFACT="test-order-maven-plugin"

# ─── Source helpers from main script ─────────────────────────────────────────
# We re-implement only the pieces we need to avoid sourcing the entire 3000-line
# script (which has side-effects). The key helpers we need:
#   inject_maven_plugin, remove_maven_plugin, inject_bug_patch, restore_bug_patch,
#   detect_single_module, detect_test_package, detect_maven_java_home,
#   detect_compiler_args, detect_extra_mvn_args

sedi() {
    local args=("$@")
    local file="${args[-1]}"
    sed -i.sedi-tmp "${args[@]}"
    rm -f "${file}.sedi-tmp"
}

detect_single_module() {
    # Returns the single-module path for a repo, or empty string for root-only
    type detect_module_override &>/dev/null && {
        local m; m=$(detect_module_override "$1" 2>/dev/null || echo "")
        [[ -n "$m" ]] && { echo "$m"; return; }
    }
    echo ""
}

detect_test_package() {
    type detect_package_override &>/dev/null && {
        local p; p=$(detect_package_override "$1" 2>/dev/null || echo "")
        [[ -n "$p" ]] && { echo "$p"; return; }
    }
    echo ""
}

inject_maven_plugin() {
    local repo="$1"
    local dir="$THIRD_PARTY/$repo"
    local mvn_dir="$dir/.mvn"
    local ext_file="$mvn_dir/extensions.xml"

    type detect_plugin_already_configured &>/dev/null && {
        local already; already=$(detect_plugin_already_configured "$repo" 2>/dev/null || echo "")
        [[ "$already" == "true" ]] && { ok "$repo already configured — skipping injection"; return; }
    }

    [[ -f "$ext_file" ]] && grep -q "$PLUGIN_ARTIFACT" "$ext_file" 2>/dev/null && {
        warn "Extension already present in $ext_file"; return
    }

    mkdir -p "$mvn_dir"
    if [[ -f "$ext_file" ]]; then
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
"
    else
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
    fi
}

remove_maven_plugin() {
    local repo="$1"
    local dir="$THIRD_PARTY/$repo"
    local ext_file="$dir/.mvn/extensions.xml"
    if [[ -f "$ext_file.bak" ]]; then
        mv "$ext_file.bak" "$ext_file"
    elif [[ -f "$ext_file" ]] && grep -q "$PLUGIN_ARTIFACT" "$ext_file" 2>/dev/null; then
        rm -f "$ext_file"
        rmdir "$dir/.mvn" 2>/dev/null || true
    fi
}

inject_bug_patch() {
    # Prints "CLASSNAME\tPATCH_FILE" on success, returns 1 if no patch applies
    local repo="$1"
    local patch_file="${2:-}"   # optional: specific patch to apply
    local bugs_dir="$BUGS_DIR/$repo"
    [[ -d "$bugs_dir" ]] || return 1

    local patches=()
    if [[ -n "$patch_file" ]]; then
        patches=("$patch_file")
    elif [[ -f "$bugs_dir/bugs.txt" ]]; then
        while IFS= read -r line; do
            [[ -z "$line" || "$line" == \#* ]] && continue
            patches+=("$bugs_dir/$line")
        done < "$bugs_dir/bugs.txt"
    else
        while IFS= read -r -d '' f; do patches+=("$f"); done \
            < <(find "$bugs_dir" -name "*.patch" -print0 | sort -z)
    fi

    for pf in "${patches[@]}"; do
        [[ -f "$pf" ]] || continue
        if patch -d "$THIRD_PARTY/$repo" -p1 --dry-run --silent < "$pf" 2>/dev/null; then
            patch -d "$THIRD_PARTY/$repo" -p1 --no-backup-if-mismatch < "$pf" >/dev/null
            # Extract changed class from patch: first +++ b/ filename
            local changed
            changed=$(grep '^+++ b/' "$pf" | head -1 | sed 's|+++ b/||' \
                | sed 's|.*/java/||' | sed 's|\.java$||' | tr '/' '.')
            printf '%s\t%s\n' "$changed" "$pf"
            return 0
        fi
    done
    return 1
}

restore_bug_patch() {
    local repo="$1"
    local patch_file="$2"
    [[ -f "$patch_file" ]] || return 0
    patch -d "$THIRD_PARTY/$repo" -p1 -R --no-backup-if-mismatch --forward -s \
        < "$patch_file" >/dev/null 2>&1 || true
    find "$THIRD_PARTY/$repo" -name "*.rej" -delete 2>/dev/null || true
}

# ─── Parse surefire log to get ordered test results with durations ────────────
# Output: one line per test class: "CLASSNAME\tPASS_OR_FAIL\tDURATION_SECS"
# Maven surefire prints:
#   Running com.example.FooTest
#   Tests run: N, Failures: M, Errors: E, Skipped: S, Time elapsed: T s
parse_test_order_from_log() {
    local log_file="$1"
    python3 - "$log_file" <<'PYEOF'
import sys, re

# Line-by-line state machine — no backtracking regex across lines
run_re    = re.compile(r'Running\s+(\S+)')
tests_re  = re.compile(r'Tests run:\s*(\d+),\s*Failures:\s*(\d+),\s*Errors:\s*(\d+)[^\n]*Time elapsed:\s*([\d.]+)\s*s')

current = None
with open(sys.argv[1], errors='replace') as f:
    for line in f:
        m = run_re.search(line)
        if m:
            current = m.group(1)
            continue
        if current:
            m = tests_re.search(line)
            if m:
                failed   = int(m.group(2)) + int(m.group(3))
                duration = float(m.group(4))
                print(f"{current}\t{1 if failed > 0 else 0}\t{duration:.3f}")
                current = None
PYEOF
}

# ─── Run one benchmark trial ──────────────────────────────────────────────────
# Returns: "alpha_apfd\torder_apfd\talpha_first_pos\torder_first_pos\tn_tests"
# or "error" if something went wrong
run_trial() {
    local repo="$1"
    local patch_file="$2"
    local changed_class="$3"
    local trial_dir="$4"
    local mvn_args=("${@:5}")

    local dir="$THIRD_PARTY/$repo"
    mkdir -p "$trial_dir"

    # ── Step 1: verify patch applies, then inject bug ────────────────────────
    log "  [patch] injecting $patch_file"
    if ! patch -d "$dir" -p1 --dry-run --silent --no-backup-if-mismatch < "$patch_file" 2>/dev/null; then
        warn "  Patch does not apply cleanly — skipping trial"
        echo "error"
        return
    fi
    patch -d "$dir" -p1 --no-backup-if-mismatch < "$patch_file" >/dev/null

    # ── Step 2: alphabetical baseline (bug present, no test-order reordering) ─
    log "  [alpha] running baseline..."
    local alpha_log="$trial_dir/alpha.log"
    (cd "$dir" && mvn clean test \
        -Dtestorder.mode=skip \
        "${mvn_args[@]}" \
        > "$alpha_log" 2>&1) || true

    # ── Step 3: learn run (clean state, before any bugs) ─────────────────────
    # Always build the index from clean code so the dep graph is accurate.
    # This is shared across all patches for this repo.
    local idx
    idx=$(find -L "$dir" ! -path "*precheck*" -name "test-dependencies.lz4" -print -quit 2>/dev/null || true)
    if [[ -z "$idx" ]]; then
        log "  [learn] no index found — running learn from clean state"
        local learn_log="$trial_dir/learn.log"
        (cd "$dir" && mvn clean test \
            -Dtestorder.mode=learn \
            "${mvn_args[@]}" \
            > "$learn_log" 2>&1) || true
        idx=$(find -L "$dir" ! -path "*precheck*" -name "test-dependencies.lz4" -print -quit 2>/dev/null || true)
        if [[ -z "$idx" ]]; then
            warn "  Learn run produced no index — order run may fall back to alphabetical"
        fi
    fi

    # ── Step 4: test-order run ────────────────────────────────────────────────
    log "  [order] running with test-order..."
    local order_log="$trial_dir/order.log"
    (cd "$dir" && mvn clean test \
        -Dtestorder.mode=auto \
        "${mvn_args[@]}" \
        > "$order_log" 2>&1) || true

    # ── Step 5: restore bug ───────────────────────────────────────────────────
    patch -d "$dir" -p1 -R --no-backup-if-mismatch --forward -s \
        < "$patch_file" >/dev/null 2>&1 || true
    find "$dir" -name "*.rej" -delete 2>/dev/null || true

    # ── Step 6: parse results ─────────────────────────────────────────────────
    local alpha_results order_results
    alpha_results=$(parse_test_order_from_log "$alpha_log")
    order_results=$(parse_test_order_from_log "$order_log")

    if [[ -z "$alpha_results" || -z "$order_results" ]]; then
        warn "  Could not parse test results for $repo / $patch_file"
        echo "error"
        return
    fi

    # Save parsed results
    echo "$alpha_results" > "$trial_dir/alpha-parsed.tsv"
    echo "$order_results" > "$trial_dir/order-parsed.tsv"

    # Compute all metrics via Python — APFD, first-failure position, time-to-first-failure %
    python3 - "$trial_dir/alpha-parsed.tsv" "$trial_dir/order-parsed.tsv" <<'PYEOF'
import sys

def load(path):
    rows = []
    for line in open(path):
        parts = line.strip().split('\t')
        if len(parts) < 3: continue
        rows.append({'cls': parts[0], 'fail': int(parts[1]), 'dur': float(parts[2])})
    return rows

def metrics(rows):
    n = len(rows)
    m = sum(r['fail'] for r in rows)
    total_time = sum(r['dur'] for r in rows)

    # APFD (position-based)
    if m == 0 or n == 0:
        apfd = float('nan')
    else:
        pos_sum = sum(i+1 for i, r in enumerate(rows) if r['fail'])
        apfd = 1.0 - pos_sum / (n * m) + 1.0 / (2 * n)

    # First failure: position (1-based) and cumulative time percentage
    first_pos = next((i+1 for i, r in enumerate(rows) if r['fail']), None)
    if first_pos is not None and total_time > 0:
        time_to_first = sum(r['dur'] for r in rows[:first_pos])
        time_pct = time_to_first / total_time * 100
    else:
        time_pct = float('nan')

    return apfd, first_pos, time_pct, n, total_time

alpha = load(sys.argv[1])
order = load(sys.argv[2])

a_apfd, a_pos, a_time_pct, n, a_total = metrics(alpha)
o_apfd, o_pos, o_time_pct, _, o_total = metrics(order)

def fmt(v): return f"{v:.4f}" if v == v else "nan"  # nan check
def fmtpct(v): return f"{v:.1f}" if v == v else "nan"
def fmtpos(v): return str(v) if v is not None else "none"

print('\t'.join([
    fmt(a_apfd), fmt(o_apfd),
    fmtpos(a_pos), fmtpos(o_pos),
    fmtpct(a_time_pct), fmtpct(o_time_pct),
    str(n), f"{a_total:.1f}", f"{o_total:.1f}"
]))
PYEOF
}

# ─── Per-repo benchmark ───────────────────────────────────────────────────────
bench_repo() {
    local repo="$1"
    section "BENCH: $repo"

    local dir="$THIRD_PARTY/$repo"
    if [[ ! -d "$dir" ]]; then
        warn "$repo not found in $THIRD_PARTY — skipping"
        return
    fi

    local bugs_dir="$BUGS_DIR/$repo"
    if [[ ! -d "$bugs_dir" ]]; then
        warn "No bug patches for $repo — skipping"
        return
    fi

    # Build maven args
    local module; module=$(detect_single_module "$repo")
    local pkg;    pkg=$(detect_test_package "$repo")
    local compiler_args; compiler_args=""
    type detect_compiler_args &>/dev/null && compiler_args=$(detect_compiler_args "$repo" 2>/dev/null || true)

    local maven_java_home=""
    type detect_maven_java_home &>/dev/null && maven_java_home=$(detect_maven_java_home "$repo" 2>/dev/null || true)
    if [[ -n "$maven_java_home" ]]; then
        export JAVA_HOME="$maven_java_home"
        export PATH="$maven_java_home/bin:$PATH"
    fi

    local extra_bench_args=""
    type detect_bench_extra_args &>/dev/null && extra_bench_args=$(detect_bench_extra_args "$repo" 2>/dev/null || true)
    # Also pick up general per-repo overrides (e.g. guava's -P '!java9', gson's error-prone profile)
    type detect_extra_mvn_args &>/dev/null && {
        local repo_overrides; repo_overrides=$(detect_extra_mvn_args "$repo" 2>/dev/null || true)
        [[ -n "$repo_overrides" ]] && extra_bench_args="$extra_bench_args $repo_overrides"
    }

    local base_args=(-B -Denforcer.skip=true -Drat.skip=true -Djacoco.skip=true
                     -Dmaven.build.cache.enabled=false)
    [[ -n "$compiler_args" ]] && base_args+=($compiler_args)
    [[ -n "$extra_bench_args" ]] && base_args+=($extra_bench_args)
    [[ -n "$pkg" ]] && base_args+=("-Dtestorder.includePackages=$pkg")
    [[ -n "$module" && "$module" != "NONE" ]] && base_args+=(-pl "$module" -am)

    inject_maven_plugin "$repo"

    # Collect patches
    local patches=()
    if [[ -f "$bugs_dir/bugs.txt" ]]; then
        while IFS= read -r line; do
            [[ -z "$line" || "$line" == \#* ]] && continue
            [[ -f "$bugs_dir/$line" ]] && patches+=("$bugs_dir/$line")
        done < "$bugs_dir/bugs.txt"
    else
        while IFS= read -r -d '' f; do patches+=("$f"); done \
            < <(find "$bugs_dir" -name "*.patch" -print0 | sort -z)
    fi

    if [[ ${#patches[@]} -eq 0 ]]; then
        warn "No patches found for $repo"
        remove_maven_plugin "$repo"
        return
    fi

    local repo_results_dir="$RESULTS_DIR/$TIMESTAMP/$repo"
    mkdir -p "$repo_results_dir"

    local patch_idx=0
    for pf in "${patches[@]}"; do
        patch_idx=$((patch_idx + 1))
        local patch_name; patch_name=$(basename "$pf" .patch)
        local trial_dir="$repo_results_dir/patch-$patch_idx-$patch_name"

        log "Patch $patch_idx/${#patches[@]}: $patch_name"

        # Extract changed class from patch
        local changed_class
        changed_class=$(grep '^+++ b/' "$pf" | head -1 | sed 's|+++ b/||' \
            | sed 's|.*/java/||' | sed 's|\.java$||' | tr '/' '.')

        local result
        result=$(run_trial "$repo" "$pf" "$changed_class" "$trial_dir" "${base_args[@]}")

        if [[ "$result" == "error" ]]; then
            echo -e "$repo\t$patch_name\terror\terror\terror\terror\terror\terror\terror\terror\terror" \
                >> "$RESULTS_DIR/$TIMESTAMP/results.tsv"
            continue
        fi

        local alpha_apfd order_apfd alpha_first order_first \
              alpha_time_pct order_time_pct n_tests alpha_total_s order_total_s
        IFS=$'\t' read -r alpha_apfd order_apfd alpha_first order_first \
                          alpha_time_pct order_time_pct n_tests alpha_total_s order_total_s \
            <<< "$result"

        local line
        line=$(printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s' \
            "$repo" "$patch_name" "$n_tests" \
            "$alpha_apfd" "$order_apfd" \
            "$alpha_first" "$order_first" \
            "$alpha_time_pct" "$order_time_pct" \
            "$alpha_total_s" "$order_total_s")
        echo "$line" >> "$RESULTS_DIR/$TIMESTAMP/results.tsv"

        ok "  alpha=${alpha_apfd} (pos=${alpha_first}, time=${alpha_time_pct}%)  →  order=${order_apfd} (pos=${order_first}, time=${order_time_pct}%)  [n=${n_tests}]"
    done

    remove_maven_plugin "$repo"
}

# ─── Summarise results ────────────────────────────────────────────────────────
summarise() {
    local tsv="$RESULTS_DIR/$TIMESTAMP/results.tsv"
    [[ -f "$tsv" ]] || { warn "No results file found"; return; }
    python3 "$SCRIPT_DIR/bench_summarise.py" "$tsv" "$RESULTS_DIR/$TIMESTAMP"
}

# ─── Main ─────────────────────────────────────────────────────────────────────
mkdir -p "$RESULTS_DIR/$TIMESTAMP"

# Header for results TSV
echo -e "repo\tpatch\tn_tests\talpha_apfd\torder_apfd\talpha_first_pos\torder_first_pos\talpha_time_pct\torder_time_pct\talpha_total_s\torder_total_s" \
    > "$RESULTS_DIR/$TIMESTAMP/results.tsv"

if [[ $# -gt 0 ]]; then
    REPOS=("$@")
else
    REPOS=("${DEFAULT_REPOS[@]}")
fi

# Install latest plugin first
section "Installing test-order plugin"
(cd "$ROOT_DIR" && mvn install -DskipTests -q) || {
    err "Plugin install failed — aborting"
    exit 1
}

for repo in "${REPOS[@]}"; do
    bench_repo "$repo"
done

section "RESULTS"
summarise

echo ""
log "Full results: $RESULTS_DIR/$TIMESTAMP/"
log "TSV:          $RESULTS_DIR/$TIMESTAMP/results.tsv"
log "Chart JSON:   $RESULTS_DIR/$TIMESTAMP/chart.json"
