#!/usr/bin/env bash
# record-demo0.sh — Record the D0 asciinema cast for the 20min talk.
# D0 shows plain `mvn test` on spring-petclinic WITHOUT test-order.
# Temporarily disables the extensions.xml, records, then restores it.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
PETCLINIC="$REPO_ROOT/third-party/spring-petclinic"
EXT="$PETCLINIC/.mvn/extensions.xml"
CAST_OUT="$SCRIPT_DIR/public/demo.cast"

if [ ! -d "$PETCLINIC" ]; then
  echo "ERROR: spring-petclinic not found at $PETCLINIC" >&2
  exit 1
fi

# Disable test-order for the recording
if [ -f "$EXT" ]; then
  mv "$EXT" "$EXT.bak"
  trap 'mv "$EXT.bak" "$EXT" 2>/dev/null; true' EXIT
fi

echo "Recording D0 cast to $CAST_OUT ..."

asciinema rec \
  --cols 100 --rows 28 \
  --idle-time-limit 2 \
  --overwrite \
  --command "bash -c 'cd $PETCLINIC && echo \"\$ cd third-party/spring-petclinic\" && echo \"\$ mvn test\" && mvn test 2>&1 | tail -40'" \
  "$CAST_OUT"

echo "Done: $CAST_OUT"
