#!/bin/bash
set -euo pipefail

cd "$(dirname "$0")/.."

echo "=== Checking if plugin is installed ==="
if [ -f ~/.m2/repository/me/bechberger/test-order-maven-plugin/0.1.0-SNAPSHOT/test-order-maven-plugin-0.1.0-SNAPSHOT.jar ]; then
  echo "Plugin JAR found"
else
  echo "Plugin JAR NOT found, installing..."
  mvn -DskipTests install -T4 2>&1 | tail -5
fi
echo "=== Plugin check done ==="

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo ""
echo "=== Running spring-petclinic with test-order (auto mode) ==="
cd "$REPO_ROOT/third-party/spring-petclinic"
# Skip checkstyle/format checks for speed, skip docker/testcontainers tests
set +e
mvn test \
  -Dcheckstyle.skip=true \
  -Dspring-javaformat.skip=true \
  -DexcludedGroups=testcontainers \
  -Denforcer.skip=true \
  2>&1 | tee /tmp/petclinic-test.txt | tail -60
MVN_EXIT=${PIPESTATUS[0]}
set -e

echo ""
echo "=== RESULT ==="
BUILD_LINE=$(grep -E "\[INFO\] BUILD (SUCCESS|FAILURE)" /tmp/petclinic-test.txt | tail -1 || true)
TEST_SUMMARY=$(grep -E "\[INFO\] Tests run: .*" /tmp/petclinic-test.txt | tail -1 || true)

if [ -n "$BUILD_LINE" ]; then
  echo "$BUILD_LINE"
fi
if [ -n "$TEST_SUMMARY" ]; then
  echo "$TEST_SUMMARY"
fi

if [ "$MVN_EXIT" -ne 0 ]; then
  echo "Maven exited with code $MVN_EXIT"
  echo "Top errors:"
  grep -E "\[ERROR\]|BUILD FAILURE" /tmp/petclinic-test.txt | head -20 || true
  echo "=== DONE (FAIL) ==="
  exit "$MVN_EXIT"
fi

echo "=== DONE (SUCCESS) ==="
