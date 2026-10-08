#!/usr/bin/env bash
# make-change.sh (petclinic) — Apply (or undo) the D3 demo edit to VisitController.java.
#
# Usage:
#   ./make-change.sh          # change flash message in processNewVisitForm
#   ./make-change.sh --undo   # restore from backup
#
# The edit touches VisitController so that VisitControllerTests jumps to #1
# in the ranked order — demonstrating test-order's dep-overlap scoring.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$SCRIPT_DIR/src/main/java/org/springframework/samples/petclinic/owner/VisitController.java"

if [ "${1:-}" = "--undo" ]; then
  if [ -f "$TARGET.bak" ]; then
    mv "$TARGET.bak" "$TARGET"
    echo "✓ VisitController.java restored"
  else
    echo "No backup found — already clean"
  fi
  exit 0
fi

if [ -f "$TARGET.bak" ]; then
  echo "VisitController.java.bak exists — change already applied (run --undo first)"
  exit 1
fi

cp "$TARGET" "$TARGET.bak"

python3 - "$TARGET" <<'EOF'
import sys
path = sys.argv[1]
text = open(path).read()
old = 'redirectAttributes.addFlashAttribute("message", "Your visit has been booked");'
new = 'redirectAttributes.addFlashAttribute("message", "Your visit has been booked successfully");'
if old not in text:
    print(f"ERROR: expected string not found in {path}", file=sys.stderr)
    sys.exit(1)
open(path, 'w').write(text.replace(old, new, 1))
print("✓ VisitController.java updated (flash message)")
EOF

echo ""
echo "Next: mvn test   →  VisitControllerTests should rank #1"
