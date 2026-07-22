#!/usr/bin/env bash
# BUG-161 regression sweep: run the detection campaign for every indexed Maven
# third-party repo × patch, recording CAUGHT / MISSED / SKIP. Reuses the exact
# campaign command from third_party_test_plan.sh. Reverts every patch afterward.
#
# Usage: scripts/bug161_sweep.sh [repo ...]   (default: all indexed Maven repos)
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TP="$ROOT/third-party"
RESULTS="/tmp/bug161_sweep_results.tsv"
: > "$RESULTS"

# Derive FQCN from a patch's target file path (first +++ b/... java/kotlin line).
fqcn_of() {
    grep -m1 '^+++ b/' "$1" \
      | sed 's|^+++ b/||; s|.*src/main/java/||; s|.*src/main/kotlin/||; s|/|.|g; s|\.java$||; s|\.kt$||'
}

repos=("$@")
if [ ${#repos[@]} -eq 0 ]; then
    mapfile -t repos < <(for r in "$TP"/*/; do
        r=$(basename "$r")
        [ -f "$TP/$r/.test-order/state.lz4" ] && [ -f "$TP/$r/pom.xml" ] \
          && ls "$ROOT/scripts/bugs/$r"/*.patch >/dev/null 2>&1 && echo "$r"
    done)
fi

for repo in "${repos[@]}"; do
    dir="$TP/$repo"
    [ -f "$dir/pom.xml" ] || { echo "SKIP $repo (not maven)"; continue; }
    for patch in "$ROOT/scripts/bugs/$repo"/*.patch; do
        [ -f "$patch" ] || continue
        pname=$(basename "$patch")
        fqcn=$(fqcn_of "$patch")
        [ -n "$fqcn" ] || { echo -e "$repo\t$pname\t-\tSKIP_NO_FQCN" | tee -a "$RESULTS"; continue; }
        target="$dir/$(grep -m1 '^+++ b/' "$patch" | sed 's|^+++ b/||')"
        # Apply
        if ! ( cd "$dir" && git apply "$patch" 2>/dev/null ); then
            echo -e "$repo\t$pname\t$fqcn\tSKIP_PATCH_FAILED" | tee -a "$RESULTS"
            continue
        fi
        log="/tmp/bug161_sweep_${repo}_${pname}.log"
        ( cd "$dir" && mvn clean me.bechberger:test-order-maven-plugin:affected test \
            -Dtestorder.changeMode=explicit \
            -Dtestorder.changed.classes="$fqcn" \
            -Dtestorder.affected.topN=3 \
            -Dtestorder.mode=skip \
            -Dsurefire.failIfNoSpecifiedTests=false \
            >"$log" 2>&1 )
        # Classify
        sel=$(grep -oE 'Selected [0-9]+ tests' "$log" | head -1)
        # CAUGHT when a selected test reports a non-zero Failures OR Errors count.
        # (Spring-context load failures surface as Errors, not Failures — omitting
        # Errors mis-reports those campaigns as MISSED.)
        if grep -qE 'Tests run: [0-9]+, Failures: [1-9]' "$log" \
           || grep -qE 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [1-9]' "$log"; then
            verdict="CAUGHT"
        elif grep -q "None of the explicitly specified changed classes" "$log"; then
            verdict="NOT_IN_INDEX"
        elif grep -qE 'Tests run: [1-9]' "$log"; then
            verdict="MISSED"
        else
            verdict="UNKNOWN_BUILD_FAIL"
        fi
        echo -e "$repo\t$pname\t$fqcn\t$verdict\t${sel:-noselect}" | tee -a "$RESULTS"
        # Revert
        ( cd "$dir" && git checkout -- "${target#"$dir/"}" 2>/dev/null || git checkout -- . 2>/dev/null )
    done
done

echo "=== SUMMARY ==="
sort "$RESULTS" | awk -F'\t' '{c[$4]++} END{for(k in c) printf "%-20s %d\n", k, c[k]}'
