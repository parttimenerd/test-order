#!/usr/bin/env bash
# enable-test-order.sh — Add test-order extension to spring-petclinic .mvn/extensions.xml
# Run from inside third-party/spring-petclinic/. Use before D1/D3 demos. Prints diff.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXT="$SCRIPT_DIR/.mvn/extensions.xml"
PLUGIN_VERSION="0.1.1"

if grep -q "test-order-maven-plugin" "$EXT" 2>/dev/null; then
  echo "test-order already enabled in $EXT"
  exit 0
fi

BEFORE=$(cat "$EXT" 2>/dev/null || echo "")

if [ -f "$EXT" ]; then
  python3 - "$EXT" "$PLUGIN_VERSION" <<'EOF'
import sys, xml.etree.ElementTree as ET
path, version = sys.argv[1], sys.argv[2]
ET.register_namespace('', '')
tree = ET.parse(path)
root = tree.getroot()
ext = ET.SubElement(root, 'extension')
ET.SubElement(ext, 'groupId').text = 'me.bechberger'
ET.SubElement(ext, 'artifactId').text = 'test-order-maven-plugin'
ET.SubElement(ext, 'version').text = version
tree.write(path, xml_declaration=True, encoding='UTF-8')
EOF
else
  mkdir -p "$SCRIPT_DIR/.mvn"
  cat > "$EXT" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<extensions>
  <extension>
    <groupId>me.bechberger</groupId>
    <artifactId>test-order-maven-plugin</artifactId>
    <version>$PLUGIN_VERSION</version>
  </extension>
</extensions>
EOF
fi

AFTER=$(cat "$EXT")
echo "✓ test-order enabled. Diff:"
diff <(echo "$BEFORE") <(echo "$AFTER") || true
