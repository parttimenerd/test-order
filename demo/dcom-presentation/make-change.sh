#!/usr/bin/env bash
# Introduces the demo bug: inverts the tenant-check in DestinationRetrievalStrategyResolver
# Leaves the change uncommitted so 'uncommitted' change-detection mode picks it up.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FILE="$SCRIPT_DIR/cloud-sdk-java/cloudplatform/connectivity-destination-service/src/main/java/com/sap/cloud/sdk/cloudplatform/connectivity/DestinationRetrievalStrategyResolver.java"

if grep -q "return Objects.equals(currentTenantId, providerTenantId);" "$FILE"; then
    sed -i '' 's/return Objects.equals(currentTenantId, providerTenantId);/return !Objects.equals(currentTenantId, providerTenantId);/' "$FILE"
    echo "  ✏️  Bug introduced in DestinationRetrievalStrategyResolver (inverted tenant check)"
    echo "  ℹ️  Change left uncommitted — test-order will detect it via 'uncommitted' mode"
    echo ""
    git -C "$SCRIPT_DIR/cloud-sdk-java" --no-pager diff -- "$FILE"
elif grep -q "return !Objects.equals(currentTenantId, providerTenantId);" "$FILE"; then
    echo "  ⚠️  Bug already present (already inverted)"
    echo ""
    git -C "$SCRIPT_DIR/cloud-sdk-java" --no-pager diff -- "$FILE"
else
    echo "  ✗ ERROR: could not find tenant check — check $FILE"
    exit 1
fi
