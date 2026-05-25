#!/usr/bin/env bash
# Fixes the demo bug: restores the correct tenant-check
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FILE="$SCRIPT_DIR/cloud-sdk-java/cloudplatform/connectivity-destination-service/src/main/java/com/sap/cloud/sdk/cloudplatform/connectivity/DestinationRetrievalStrategyResolver.java"

if grep -q "return !Objects.equals(currentTenantId, providerTenantId);" "$FILE"; then
    sed -i '' 's/return !Objects.equals(currentTenantId, providerTenantId);/return Objects.equals(currentTenantId, providerTenantId);/' "$FILE"
    cd "$SCRIPT_DIR/cloud-sdk-java"
    git add "$FILE" && git commit -m "Fix tenant routing (remove negation)" -q
    echo "  ✓ Bug fixed in DestinationRetrievalStrategyResolver"
elif grep -q "return Objects.equals(currentTenantId, providerTenantId);" "$FILE"; then
    echo "  ✓ Already clean (no bug present)"
else
    echo "  ✗ ERROR: could not find tenant check — check $FILE"
    exit 1
fi
