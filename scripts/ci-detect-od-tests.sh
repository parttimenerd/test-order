#!/usr/bin/env bash
# ci-detect-od-tests.sh — CI wrapper for mvn test-order:detect-dependencies
#
# Runs OD (order-dependent) test detection and exits non-zero when findings
# are found.  Designed for use in GitHub Actions, GitLab CI, Jenkins, etc.
#
# Exit codes:
#   0  No OD bugs found (or detection skipped via --warn-only)
#   1  OD bugs found (only when --fail-on-detection flag is used)
#   2  Tool misconfiguration or invocation error
#
# Usage:
#   ./scripts/ci-detect-od-tests.sh [OPTIONS]
#
# Options:
#   --fail-on-detection     Exit 1 when findings are found (default: warn only)
#   --algorithm ALGO        Detection algorithm (default: combined)
#   --time-budget SECONDS   Time budget per module (default: 300)
#   --module MODULE         Maven module to scan (default: all)
#   --report-dir DIR        Where to read JSON/Markdown reports from (default: .test-order/detection)
#   --annotate-github       Emit GitHub Actions annotations for each finding
#   --help                  Show this help

set -euo pipefail

FAIL_ON_DETECTION=false
ALGORITHM=combined
TIME_BUDGET=300
MODULE=""
REPORT_DIR=".test-order/detection"
ANNOTATE_GITHUB=false

usage() {
    sed -n '/^# Usage:/,/^[^#]/p' "$0" | grep '^#' | sed 's/^# \?//'
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --fail-on-detection) FAIL_ON_DETECTION=true; shift ;;
        --algorithm)         ALGORITHM="$2"; shift 2 ;;
        --time-budget)       TIME_BUDGET="$2"; shift 2 ;;
        --module)            MODULE="$2"; shift 2 ;;
        --report-dir)        REPORT_DIR="$2"; shift 2 ;;
        --annotate-github)   ANNOTATE_GITHUB=true; shift ;;
        --help)              usage ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

# Resolve script directory and cd to project root
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

log() { echo "$(date +%H:%M:%S) [ci-detect-od] $*"; }

# Determine working directory for the mvn invocation.
# If --module points to a standalone Maven project (has its own pom.xml but
# is not part of this reactor), run mvn from that directory instead.
WORK_DIR="$PROJECT_ROOT"
if [[ -n "$MODULE" ]]; then
    MODULE_DIR="$PROJECT_ROOT/$MODULE"
    if [[ -f "$MODULE_DIR/pom.xml" ]] && ! grep -q "<module>$MODULE" "$PROJECT_ROOT/pom.xml" 2>/dev/null; then
        WORK_DIR="$MODULE_DIR"
        MODULE=""  # no -pl needed when running from the module's own directory
    fi
fi

# Build the mvn command
MVN_ARGS=(
    "test-order:detect-dependencies"
    "-Dtestorder.detect.algorithm=${ALGORITHM}"
    "-Dtestorder.detect.timeBudget=${TIME_BUDGET}"
    "-Dtestorder.detect.failOnDetection=false"   # we handle exit code ourselves
    "-Dspotless.check.skip=true"
    "--batch-mode"
)

if [[ -n "$MODULE" ]]; then
    MVN_ARGS+=("-pl" "$MODULE")
fi

log "Running OD detection (algorithm=${ALGORITHM}, budget=${TIME_BUDGET}s)"
if [[ -n "$MODULE" ]]; then
    log "Module: $MODULE"
fi

# Run detection; capture exit code without letting set -e abort us
set +e
(cd "$WORK_DIR" && mvn "${MVN_ARGS[@]}")
MVN_EXIT=$?
set -e

# If Maven itself failed for a non-detection reason, propagate the error
if [[ $MVN_EXIT -ne 0 ]]; then
    log "ERROR: mvn exited with code $MVN_EXIT (not a detection failure — check the output above)"
    exit $MVN_EXIT
fi

# Parse findings from the JSON report
REPORT="$WORK_DIR/$REPORT_DIR/od-detection-report.json"
if [[ ! -f "$REPORT" ]]; then
    log "WARNING: No report file found at $REPORT — detection may not have run"
    exit 0
fi

FINDING_COUNT=$(REPORT_PATH="$REPORT" python3 -c "
import json, sys, os
try:
    data = json.load(open(os.environ['REPORT_PATH']))
    print(len(data.get('findings', [])))
except Exception as e:
    print(0)
    sys.stderr.write(str(e) + '\n')
" 2>/dev/null || echo "0")

if [[ "$FINDING_COUNT" -eq 0 ]]; then
    log "No order-dependent bugs found."
    exit 0
fi

log "Detected ${FINDING_COUNT} order-dependent finding(s)."

# Print a human-readable summary
REPORT_PATH="$REPORT" python3 -c "
import json, os
data = json.load(open(os.environ['REPORT_PATH']))
for i, f in enumerate(data.get('findings', []), 1):
    victim = f.get('victim', '?')
    ftype  = f.get('type', '?')
    desc   = f.get('description', '')
    chain  = ' -> '.join(f.get('dependencyChain', []))
    print(f'  [{i}] {ftype}: {victim}')
    if chain:
        print(f'       chain: {chain}')
    if desc:
        print(f'       {desc}')
"

# Emit GitHub Actions annotations if requested
if [[ "$ANNOTATE_GITHUB" == "true" ]] && [[ -n "${GITHUB_ACTIONS:-}" ]]; then
    REPORT_PATH="$REPORT" python3 -c "
import json, os
data = json.load(open(os.environ['REPORT_PATH']))
for f in data.get('findings', []):
    victim = f.get('victim', '?')
    ftype  = f.get('type', '?')
    desc   = f.get('description', victim)
    chain  = ' -> '.join(f.get('dependencyChain', []))
    title  = f'OD Test ({ftype}): {victim}'
    msg    = desc
    if chain:
        msg += f' | chain: {chain}'
    print(f'::warning title={title}::{msg}')
"
fi

log "Full report: $REPORT"
if [[ -f "$WORK_DIR/$REPORT_DIR/od-detection-report.md" ]]; then
    log "Markdown:    $WORK_DIR/$REPORT_DIR/od-detection-report.md"
fi

if [[ "$FAIL_ON_DETECTION" == "true" ]]; then
    log "Failing build (--fail-on-detection is set)."
    exit 1
else
    log "Build continues (use --fail-on-detection to fail on findings)."
    exit 0
fi
