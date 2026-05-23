#!/usr/bin/env bash
# Backup script: adds a comment to DestinationService.java (simulates a fix)
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FILE="$SCRIPT_DIR/cloud-sdk-java/cloudplatform/connectivity-destination-service/src/main/java/com/sap/cloud/sdk/cloudplatform/connectivity/DestinationService.java"

sed -i '' '1s/^/\/\/ fixed token refresh handling\n/' "$FILE"
echo "  ✏️  Added fix to DestinationService.java"
