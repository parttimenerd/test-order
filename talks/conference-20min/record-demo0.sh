#!/usr/bin/env bash
# record-demo0.sh — Record the D0 asciinema cast for the 20min talk.
# D0 shows plain `mvn test` on commons-collections with test-order skipped (alphabetical).
# The EnumerationIterator patch must already be applied before recording.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
COLLECTIONS="$REPO_ROOT/third-party/commons-collections"
CAST_OUT="$SCRIPT_DIR/public/demo0.cast"

if [ ! -d "$COLLECTIONS" ]; then
  echo "ERROR: commons-collections not found at $COLLECTIONS" >&2
  exit 1
fi

# Remove any live index — D0 must run alphabetically with mode=skip
rm -rf "$COLLECTIONS/.test-order"

echo "Applying EnumerationIterator patch for recording..."
"$SCRIPT_DIR/make-change-commons.sh"

echo "Recording D0 cast to $CAST_OUT ..."

asciinema rec \
  --cols 100 --rows 30 \
  --idle-time-limit 2 \
  --overwrite \
  --command "bash -c 'cd $COLLECTIONS && echo \"\$ mvn test -Dtestorder.mode=skip\" && mvn test -Dtestorder.mode=skip 2>&1'" \
  "$CAST_OUT"

echo "Done: $CAST_OUT"
echo "NOTE: remember to revert the patch: git -C $COLLECTIONS checkout src/"
