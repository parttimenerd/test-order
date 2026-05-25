#!/usr/bin/env bash
# Introduces the demo bug: inverts the tenant-check in DestinationRetrievalStrategyResolver
# This simulates a realistic off-by-one / logic inversion bug.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FILE="$SCRIPT_DIR/cloud-sdk-java/cloudplatform/connectivity-destination-service/src/main/java/com/sap/cloud/sdk/cloudplatform/connectivity/DestinationRetrievalStrategyResolver.java"

if grep -q "return Objects.equals(currentTenantId, providerTenantId);" "$FILE"; then
    sed -i '' 's/return Objects.equals(currentTenantId, providerTenantId);/return !Objects.equals(currentTenantId, providerTenantId);/' "$FILE"
    cd "$SCRIPT_DIR/cloud-sdk-java"
    git add "$FILE" && git commit -m "Fix tenant routing" -q
    echo "  ✏️  Bug introduced in DestinationRetrievalStrategyResolver (inverted tenant check)"
elif grep -q "return !Objects.equals(currentTenantId, providerTenantId);" "$FILE"; then
    echo "  ⚠️  Bug already present (already inverted)"
else
    echo "  ✗ ERROR: could not find tenant check — check $FILE"
    exit 1
fi
