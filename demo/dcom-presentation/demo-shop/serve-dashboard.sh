#!/usr/bin/env bash
# =============================================================================
# serve-dashboard.sh — Launch the test-order dashboard for demo-shop.
# =============================================================================
# Wraps `mvnd test-order:serve` (falls back to mvn); prints the URL to open.
# Ctrl+C to stop.
# =============================================================================
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-/Users/i560383_1/Library/Java/JavaVirtualMachines/sapmachine-21/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

MVN="$(command -v mvnd 2>/dev/null || echo mvn)"

cd "$DIR"
exec $MVN test-order:serve
