#!/usr/bin/env bash
# copy-in-db.sh — Restore a pre-trained test-order index into the current project.
# Run from inside the target project directory (spring-petclinic or commons-collections).
# Lives at: third-party/<project>/copy-in-db.sh  (copied there by prepare-demo.sh)
# Snapshot at: talks/conference-20min/<project-name>-index-snapshot
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR"
PROJECT_NAME="$(basename "$PROJECT_DIR")"
# third-party/<project>/ → ../../talks/conference-20min/
TALK_DIR="$SCRIPT_DIR/../../talks/conference-20min"
SNAPSHOT="$TALK_DIR/${PROJECT_NAME}-index-snapshot"

if [ ! -d "$SNAPSHOT" ]; then
  echo "ERROR: no index snapshot at $SNAPSHOT" >&2
  echo "Re-run prepare-demo.sh to build the snapshot." >&2
  exit 1
fi

rm -rf "$PROJECT_DIR/.test-order"
cp -r "$SNAPSHOT" "$PROJECT_DIR/.test-order"
echo "✓ test-order index restored from ${PROJECT_NAME}-index-snapshot"
