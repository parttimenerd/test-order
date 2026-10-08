#!/usr/bin/env bash
# make-change.sh — Apply (or undo) the D2 demo edit to Cart.java.
#
# Usage:
#   ./make-change.sh          # apply the null-check to Cart.add()
#   ./make-change.sh --undo   # restore Cart.java from backup
#
# The edit adds a null-check inside Cart.add() so that InvoiceTest and
# CartTest jump to the top of the ranked order (they both touch Cart).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
CART="$REPO_ROOT/samples/sample-shop/src/main/java/com/example/shop/Cart.java"

if [ "${1:-}" = "--undo" ]; then
  if [ -f "$CART.bak" ]; then
    mv "$CART.bak" "$CART"
    echo "✓ Cart.java restored"
  else
    echo "No backup found — Cart.java may already be clean"
  fi
  exit 0
fi

if [ -f "$CART.bak" ]; then
  echo "Cart.java.bak already exists — change already applied (run --undo first)"
  exit 1
fi

cp "$CART" "$CART.bak"

# Insert null-check after `public void add(Product product) {`
# Uses Palantir format (no same-line braces) as required by spotless
python3 - "$CART" <<'EOF'
import sys, re

path = sys.argv[1]
text = open(path).read()

old = "\tpublic void add(Product product) {\n\t\titems.add(product);\n\t}"
new = "\tpublic void add(Product product) {\n\t\tif (product == null)\n\t\t\tthrow new IllegalArgumentException(\"product must not be null\");\n\t\titems.add(product);\n\t}"

if old not in text:
    print(f"ERROR: expected pattern not found in {path}", file=sys.stderr)
    sys.exit(1)

open(path, 'w').write(text.replace(old, new, 1))
print("✓ null-check added to Cart.add()")
EOF

echo ""
echo "Next steps:"
echo "  mvn spotless:apply   # required — project enforces Palantir formatting"
echo "  mvn test             # InvoiceTest=#1 (score 8), CartTest=#2 (score 6)"
