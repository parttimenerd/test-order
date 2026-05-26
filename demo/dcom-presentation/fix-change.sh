#!/usr/bin/env bash
# Fixes the demo bug: restores the correct tenant-check (manual fallback)
# Leaves the fix uncommitted so test-order detects the clean state.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FILE="$SCRIPT_DIR/cloud-sdk-java/cloudplatform/connectivity-destination-service/src/main/java/com/sap/cloud/sdk/cloudplatform/connectivity/DestinationRetrievalStrategyResolver.java"

if grep -q "return !Objects.equals(currentTenantId, providerTenantId);" "$FILE"; then
    sed -i '' 's/return !Objects.equals(currentTenantId, providerTenantId);/return Objects.equals(currentTenantId, providerTenantId);/' "$FILE"
    echo "  ✓ Bug fixed in DestinationRetrievalStrategyResolver"
    echo "  ℹ️  Change left uncommitted"
elif grep -q "return Objects.equals(currentTenantId, providerTenantId);" "$FILE"; then
    echo "  ✓ Already clean (no bug present)"
else
    echo "  ✗ ERROR: could not find tenant check — check $FILE"
    exit 1
fi
