#!/usr/bin/env bash
# make-change-commons.sh — Apply the EnumerationIterator bug patch.
# Copied to third-party/commons-collections/ by prepare-demo.sh.
# Patch lives at: talks/conference-20min/../scripts/bugs/commons-collections/...
#   i.e. ../../scripts/bugs/commons-collections/ relative to third-party/commons-collections/
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCH="$SCRIPT_DIR/../../scripts/bugs/commons-collections/real-enumerationiterator-remove-no-reset.patch"

if [ ! -f "$PATCH" ]; then
  echo "ERROR: patch not found at $PATCH" >&2
  exit 1
fi

cd "$SCRIPT_DIR"

if git diff --quiet HEAD; then
  git apply "$PATCH"
  echo "✓ Patch applied — EnumerationIterator.remove() missing last=null"
else
  echo "⚠  Working tree already has changes — patch may already be applied"
  git diff --stat HEAD
fi
