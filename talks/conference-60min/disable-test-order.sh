#!/usr/bin/env bash
# disable-test-order.sh — Remove test-order extension from spring-petclinic .mvn/extensions.xml
# Run from inside third-party/spring-petclinic/. Use before D0 demo. Prints diff.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXT="$SCRIPT_DIR/.mvn/extensions.xml"

if [ ! -f "$EXT" ]; then
  echo "No $EXT found — nothing to disable"
  exit 0
fi

if ! grep -q "test-order-maven-plugin" "$EXT" 2>/dev/null; then
  echo "test-order already absent from $EXT"
  exit 0
fi

BEFORE=$(cat "$EXT")

python3 - "$EXT" <<'EOF'
import sys, xml.etree.ElementTree as ET
path = sys.argv[1]
ET.register_namespace('', '')
tree = ET.parse(path)
root = tree.getroot()
to_remove = [
    ext for ext in root.findall('extension')
    if ext.findtext('artifactId') == 'test-order-maven-plugin'
]
for ext in to_remove:
    root.remove(ext)
tree.write(path, xml_declaration=True, encoding='UTF-8')
EOF

AFTER=$(cat "$EXT")
echo "✓ test-order disabled. Diff:"
diff <(echo "$BEFORE") <(echo "$AFTER") || true
