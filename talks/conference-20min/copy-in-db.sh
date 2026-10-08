#!/usr/bin/env bash
# copy-in-db.sh — Copy the pre-trained test-order index into spring-petclinic.
# Run from inside third-party/spring-petclinic/ after prepare-demo.sh has copied it there.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PETCLINIC="$SCRIPT_DIR"
# Snapshot lives in the talk dir of the parent repo — find it relative to this script's
# known location inside third-party/spring-petclinic/
TALK_DIR="$SCRIPT_DIR/../../talks/conference-20min"
SNAPSHOT="$TALK_DIR/petclinic-index-snapshot"

if [ ! -d "$SNAPSHOT" ]; then
  echo "ERROR: no index snapshot at $SNAPSHOT" >&2
  echo "Re-run prepare-demo.sh or manually: cp -r $PETCLINIC/.test-order $SNAPSHOT" >&2
  exit 1
fi

rm -rf "$PETCLINIC/.test-order"
cp -r "$SNAPSHOT" "$PETCLINIC/.test-order"
echo "✓ test-order index restored from snapshot"
