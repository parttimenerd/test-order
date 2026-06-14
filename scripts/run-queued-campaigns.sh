#!/bin/bash
# Script to run queued campaigns when thinkstation comes back online
# Run this after thinkstation connectivity is restored

set -euo pipefail

echo "Starting queued third-party campaigns..."
echo "========================================"

# Commons-codec re-test with new patch
echo "[1/4] Commons-codec with charsequenceutils-regionmatches-flip patch"
bash scripts/third_party_test_plan.sh full commons-codec 2>&1 | tee /tmp/campaign-commons-codec-2.log &

# Kafka (new repo)
echo "[2/4] Kafka with utils-isblank-negate patch"
sleep 5
bash scripts/third_party_test_plan.sh full kafka 2>&1 | tee /tmp/campaign-kafka.log &

# Maven (new repo)
echo "[3/4] Maven with pathselector-needrelativize-flip patch"
sleep 5
bash scripts/third_party_test_plan.sh full maven 2>&1 | tee /tmp/campaign-maven.log &

# AI-SDK-Java (new repo with bugs.txt)
echo "[4/4] AI-SDK-Java with flip-path-endswith patch"
sleep 5
bash scripts/third_party_test_plan.sh full ai-sdk-java 2>&1 | tee /tmp/campaign-ai-sdk-java.log &

echo ""
echo "All campaigns queued. Monitoring progress..."
echo "Check logs with: tail -f /tmp/campaign-*.log"
echo ""
echo "Expected timeline:"
echo "  - commons-codec: ~5-10 minutes (Maven, medium size)"
echo "  - kafka: ~15-20 minutes (Maven, large)"
echo "  - maven: ~20-30 minutes (Maven self-hosting, large)"
echo "  - ai-sdk-java: ~10-15 minutes (Gradle, small)"
echo ""
wait
echo "All campaigns complete!"
