#!/usr/bin/env bash
# cloud-sdk-reset-change.sh — Restore DestinationRetrievalStrategyResolver.java from backup.
# Run from inside third-party/cloud-sdk-java/ after cloud-sdk-make-change.sh.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$SCRIPT_DIR/cloudplatform/connectivity-destination-service/src/main/java/com/sap/cloud/sdk/cloudplatform/connectivity/DestinationRetrievalStrategyResolver.java"

if [ ! -f "$TARGET.bak" ]; then
  echo "No backup found — nothing to restore"
  exit 0
fi

mv "$TARGET.bak" "$TARGET"
echo "✓ DestinationRetrievalStrategyResolver.java restored"
