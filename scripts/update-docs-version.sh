#!/usr/bin/env bash
# Usage: ./scripts/update-docs-version.sh <new-version>
# Replaces every hardcoded plugin version in docs, README, and website source.
# Run this once per release before tagging.

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <new-version>   e.g.  $0 1.0.0" >&2
  exit 1
fi

NEW="$1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Files that contain the version literal
FILES=(
  "$ROOT/README.md"
  "$ROOT/docs/GETTING_STARTED.md"
  "$ROOT/docs/CHEAT_SHEET.md"
  "$ROOT/docs/MAVEN_PLUGIN.md"
  "$ROOT/docs/MULTI_MODULE_SETUP.md"
  "$ROOT/docs/DETECT_DEPENDENCIES.md"
  "$ROOT/docs/DEVELOPMENT.md"
  "$ROOT/website/src/components/HomepageInstall.jsx"
)

OLD_PATTERN='0\.0\.1-SNAPSHOT'

for f in "${FILES[@]}"; do
  if [[ -f "$f" ]]; then
    sed -i '' "s/${OLD_PATTERN}/${NEW}/g" "$f"
    echo "Updated: $f"
  else
    echo "WARNING: not found — $f" >&2
  fi
done

echo "Done. Version is now ${NEW}."
echo "Remember to update pom.xml versions separately with: mvn versions:set -DnewVersion=${NEW}"
