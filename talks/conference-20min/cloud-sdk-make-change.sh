#!/usr/bin/env bash
# cloud-sdk-make-change.sh — Inject a ! bug into DestinationRetrievalStrategyResolver.
# Inverts currentTenantIsProvider(): Objects.equals → !Objects.equals
# causing DestinationRetrievalStrategyResolverTest to fail.
# Run from inside third-party/cloud-sdk-java/. Use before D0 and D1 demos.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$SCRIPT_DIR/cloudplatform/connectivity-destination-service/src/main/java/com/sap/cloud/sdk/cloudplatform/connectivity/DestinationRetrievalStrategyResolver.java"

if [ ! -f "$TARGET" ]; then
  echo "ERROR: $TARGET not found" >&2
  exit 1
fi

if grep -q '!Objects.equals(currentTenantId, providerTenantId)' "$TARGET"; then
  echo "change already applied — nothing to do"
  exit 0
fi

cp "$TARGET" "$TARGET.bak"
sed -i '' 's/return Objects.equals(currentTenantId, providerTenantId);/return !Objects.equals(currentTenantId, providerTenantId);/' "$TARGET"
echo "✓ Bug injected: currentTenantIsProvider() now returns inverted result"
echo "  File: cloudplatform/connectivity-destination-service/src/.../DestinationRetrievalStrategyResolver.java"
echo "  Change: Objects.equals(...) → !Objects.equals(...)"
