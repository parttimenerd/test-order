#!/usr/bin/env bash
# cloud-sdk-copy-in-db.sh — Copy the pre-trained test-order index into cloud-sdk-java.
# Run from inside third-party/cloud-sdk-java/ before D1 demo.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLOUD_SDK="$SCRIPT_DIR"
TALK_DIR="$SCRIPT_DIR/../../talks/conference-20min"
SNAPSHOT="$TALK_DIR/cloud-sdk-index-snapshot"

if [ ! -d "$SNAPSHOT" ]; then
  echo "ERROR: no index snapshot at $SNAPSHOT" >&2
  echo "Build it first: cd $CLOUD_SDK && mvn test" >&2
  echo "Then save: cp -r $CLOUD_SDK/.test-order $SNAPSHOT" >&2
  exit 1
fi

rm -rf "$CLOUD_SDK/.test-order"
cp -r "$SNAPSHOT" "$CLOUD_SDK/.test-order"
echo "✓ test-order index restored from snapshot"
