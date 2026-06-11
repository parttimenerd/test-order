#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════════
# Aggregate synthetic-history and matrix validation results
#
# Reads:  target/third-party-results/<repo>/<ts>/synthetic/results.tsv
#         target/third-party-results/<repo>/<ts>/matrix/results.tsv
# Writes: target/third-party-results/_summary.tsv
#         target/third-party-results/_summary.md
#         target/third-party-results/_issues.md  (append-only)
# ═══════════════════════════════════════════════════════════════════════════════
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RESULTS_DIR="$ROOT_DIR/target/third-party-results"
SUMMARY_TSV="$RESULTS_DIR/_summary.tsv"
SUMMARY_MD="$RESULTS_DIR/_summary.md"
ISSUES_MD="$RESULTS_DIR/_issues.md"

mkdir -p "$RESULTS_DIR"

# ─── Collect all TSV rows ────────────────────────────────────────────────────

COMBINED=$(mktemp)
trap 'rm -f "$COMBINED"' EXIT

# synthetic/results.tsv header: repo mutator change_mode fqcn expected detected runtime_ms
# matrix/results.tsv header:   repo cell instrumentation bytecodeCD depth mutator change_mode fqcn expected detected runtime_ms
# We normalise to a 7-col format: repo phase mutator change_mode fqcn expected detected

printf 'repo\tphase\tmutator\tchange_mode\tfqcn\texpected\tdetected\n' > "$COMBINED"

while IFS= read -r tsv; do
    local_phase="synthetic"
    if [[ "$tsv" == */matrix/* ]]; then
        local_phase="matrix"
    fi
    # Skip header line
    tail -n +2 "$tsv" | while IFS=$'\t' read -r cols; do
        IFS=$'\t' read -ra f <<< "$cols"
        if [[ "$local_phase" == "synthetic" ]]; then
            # cols: repo mutator change_mode fqcn expected detected runtime_ms
            printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
                "${f[0]}" "synthetic" "${f[1]}" "${f[2]}" "${f[3]}" "${f[4]}" "${f[5]}"
        else
            # cols: repo cell instrumentation bytecodeCD depth mutator change_mode fqcn expected detected runtime_ms
            printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
                "${f[0]}" "matrix:${f[1]}" "${f[5]}" "${f[6]}" "${f[7]}" "${f[8]}" "${f[9]}"
        fi
    done
done < <(find "$RESULTS_DIR" -name "results.tsv" ! -name "_*" 2>/dev/null) >> "$COMBINED"

total=$(( $(wc -l < "$COMBINED") - 1 ))

if [[ "$total" -le 0 ]]; then
    echo "No synthetic/matrix results found under $RESULTS_DIR"
    exit 0
fi

echo "Aggregating $total rows..."

# ─── Detection rate by (mutator × change_mode) ───────────────────────────────

awk -F'\t' '
NR==1{next}
$6=="skip"{next}
{
    key=$3 SUBSEP $4
    total[key]++
    if($7==$6) ok[key]++
    if($3=="mutate_touch_only") touch_total[$1]++
    if($3=="mutate_touch_only" && $7=="1") touch_fp[$1]++
    if($3!="mutate_touch_only" && $7!=$6) miss[$1]++
}
END{
    print "## Detection rates by (mutator × change_mode)"
    print ""
    print "| Mutator | Mode | Detected | Total | Rate |"
    print "|---|---|---|---|---|"
    for(k in total){
        split(k, p, SUBSEP)
        pct = (ok[k]+0) * 100 / total[k]
        printf "| %s | %s | %d | %d | %.0f%% |\n", p[1], p[2], ok[k]+0, total[k], pct
    }
    print ""
    print "## Repos with missed detections (expected detected=1 but got 0)"
    print ""
    n=0
    for(r in miss){if(miss[r]>0){print "- " r " (" miss[r] " misses)"; n++}}
    if(n==0) print "None — all detections correct!"
    print ""
    print "## False positives on touch_only (expected detected=0 but got 1)"
    print ""
    n=0
    for(r in touch_fp){if(touch_fp[r]>0){print "- " r " (" touch_fp[r] " false positives)"; n++}}
    if(n==0) print "None — no false positives!"
}
' "$COMBINED"

# ─── Write summary TSV ───────────────────────────────────────────────────────

cp "$COMBINED" "$SUMMARY_TSV"
echo ""
echo "Summary TSV: $SUMMARY_TSV"

# ─── Write/update summary markdown ──────────────────────────────────────────

{
    echo "# Synthetic Validation Summary"
    echo ""
    echo "Generated: $(date -u '+%Y-%m-%d %H:%M UTC')"
    echo ""
    echo "Total rows: $total"
    echo ""
    awk -F'\t' '
    NR==1{next}
    $6=="skip"{next}
    {
        key=$3 SUBSEP $4
        total[key]++
        if($7==$6) ok[key]++
        if($3=="mutate_touch_only" && $7=="1") touch_fp[$1]++
        if($3!="mutate_touch_only" && $7!=$6) miss[$1]++
    }
    END{
        print "## Detection rates by (mutator × change_mode)"
        print ""
        print "| Mutator | Mode | Detected | Total | Rate |"
        print "|---|---|---|---|---|"
        for(k in total){
            split(k, p, SUBSEP)
            pct = (ok[k]+0) * 100 / total[k]
            printf "| %s | %s | %d | %d | %.0f%% |\n", p[1], p[2], ok[k]+0, total[k], pct
        }
        print ""
        print "## Repos with missed detections"
        print ""
        n=0
        for(r in miss){if(miss[r]>0){print "- " r " (" miss[r] " misses)"; n++}}
        if(n==0) print "None"
        print ""
        print "## Repos with false positives (touch_only)"
        print ""
        n=0
        for(r in touch_fp){if(touch_fp[r]>0){print "- " r " (" touch_fp[r] " false positives)"; n++}}
        if(n==0) print "None"
    }
    ' "$COMBINED"
} > "$SUMMARY_MD"

echo "Summary MD: $SUMMARY_MD"

# ─── Auto-append new issues to _issues.md ────────────────────────────────────

if [[ ! -f "$ISSUES_MD" ]]; then
    cat > "$ISSUES_MD" << 'HEADER'
# Test-Order Issue Backlog

Auto-generated by aggregate_synthetic_results.sh. Manual entries welcome.

| ID | Severity | Repo | Phase | Mode/Config | Symptom | Fix-commit | Status |
|---|---|---|---|---|---|---|---|
HEADER
    # Seed with known issues from prior sessions
    cat >> "$ISSUES_MD" << 'KNOWN'
| 001 | usability | mockito (gradle) | full | multi-module | PREAMBLE_PRINTED static dedup broken — score legend prints once per subproject | — | open |
| 002 | compat | gson | full | N/A | JUnit 4 only — no index produced (expected, not a bug) | — | wontfix |
| 003 | compat | guava | full | N/A | JUnit 4 only — no index produced (expected) | — | wontfix |
| 004 | bug | dagger/google-auto/jaxb/lombok/jdk-tests | full | N/A | Unknown build system (Bazel/Ant) — script errors | — | wontfix |
KNOWN
fi

# Find new issues: missed detections not already in _issues.md
NEXT_ID=$(grep -c '^|' "$ISSUES_MD" 2>/dev/null || echo 4)
NEXT_ID=$(( NEXT_ID + 1 ))   # rough next ID

awk -F'\t' -v issues="$ISSUES_MD" -v next_id="$NEXT_ID" '
NR==1{next}
$6=="skip"{next}
$3!="mutate_touch_only" && $7!=$6 && $6!="skip" {
    repo=$1; phase=$2; mut=$3; mode=$4; fqcn=$5
    key=repo ":" mut ":" mode
    if(!(key in seen)){
        seen[key]=1
        printf "| %03d | bug | %s | %s | %s + %s | Expected detection of %s missed | — | open |\n",
            next_id++, repo, phase, mut, mode, fqcn >> issues
    }
}
$3=="mutate_touch_only" && $7=="1" {
    repo=$1; phase=$2; mode=$4; fqcn=$5
    key=repo ":touch_fp:" mode
    if(!(key in seen)){
        seen[key]=1
        printf "| %03d | bug | %s | %s | touch_only + %s | False positive detection of %s | — | open |\n",
            next_id++, repo, phase, mode, fqcn >> issues
    }
}
' "$COMBINED"

echo "Issues: $ISSUES_MD"
