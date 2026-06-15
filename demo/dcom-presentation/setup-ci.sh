#!/usr/bin/env bash
# Clone the demo repositories needed for CI (mutation testing workflow).
# Clones at a pinned commit so CI builds are reproducible.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

CLOUD_SDK_DIR="$SCRIPT_DIR/cloud-sdk-java"
CLOUD_SDK_URL="https://github.com/SAP/cloud-sdk-java.git"
CLOUD_SDK_COMMIT="432db950ec364b7591b286942a1140617ff5ddcd"

CAP_SFLIGHT_DIR="$SCRIPT_DIR/cap-sflight"
CAP_SFLIGHT_URL="https://github.com/SAP-samples/cap-sflight.git"
CAP_SFLIGHT_COMMIT="9724a2865dbc669073a2f60d59b0970ba882dae7"

clone_at_commit() {
    local dir="$1" url="$2" commit="$3"
    if [[ -d "$dir/.git" ]]; then
        echo "  already present: $dir"
        return
    fi
    echo "  cloning $url → $dir"
    git clone --no-checkout "$url" "$dir"
    git -C "$dir" checkout "$commit"
}

echo "▶ Setting up demo repositories..."
clone_at_commit "$CLOUD_SDK_DIR" "$CLOUD_SDK_URL" "$CLOUD_SDK_COMMIT"
clone_at_commit "$CAP_SFLIGHT_DIR" "$CAP_SFLIGHT_URL" "$CAP_SFLIGHT_COMMIT"
echo "✓ Done"
