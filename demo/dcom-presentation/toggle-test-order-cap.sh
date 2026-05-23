#!/usr/bin/env bash
# =============================================================================
# toggle-test-order-cap.sh — Add or remove test-order from cap-sflight
# =============================================================================
# Usage:
#   ./toggle-test-order-cap.sh on    # Add test-order plugin
#   ./toggle-test-order-cap.sh off   # Remove test-order plugin
#   ./toggle-test-order-cap.sh       # Toggle
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
POM="$SCRIPT_DIR/cap-sflight/srv/pom.xml"

MARKER="test-order-plugin-start"

is_enabled() {
    grep -q "$MARKER" "$POM" 2>/dev/null
}

enable() {
    if is_enabled; then
        echo "test-order already enabled."
        return
    fi

    perl -i -pe '
        if (/^\s+<plugins>\s*$/ && !$done) {
            $_ .= "      <!-- test-order-plugin-start -->\n"
                 . "      <plugin>\n"
                 . "        <groupId>me.bechberger</groupId>\n"
                 . "        <artifactId>test-order-maven-plugin</artifactId>\n"
                 . "        <version>0.0.1-SNAPSHOT</version>\n"
                 . "        <executions>\n"
                 . "          <execution>\n"
                 . "            <goals><goal>prepare</goal></goals>\n"
                 . "          </execution>\n"
                 . "        </executions>\n"
                 . "      </plugin>\n"
                 . "      <!-- test-order-plugin-end -->\n";
            $done = 1;
        }
    ' "$POM"

    echo ""
    echo "  ✅ test-order plugin ADDED to cap-sflight"
    echo ""
    show_diff
}

disable() {
    if ! is_enabled; then
        echo "test-order already disabled."
        return
    fi
    perl -i -ne 'print unless /test-order-plugin-start/ .. /test-order-plugin-end/' "$POM"
    echo ""
    echo "  ❌ test-order plugin REMOVED from cap-sflight"
    echo ""
    show_diff
}

show_diff() {
    cd "$SCRIPT_DIR/cap-sflight"
    git --no-pager diff --color srv/pom.xml || true
}

case "${1:-toggle}" in
    on|enable|add)
        enable
        ;;
    off|disable|remove)
        disable
        ;;
    toggle)
        if is_enabled; then
            disable
        else
            enable
        fi
        ;;
    *)
        echo "Usage: $0 [on|off|toggle]"
        exit 1
        ;;
esac
