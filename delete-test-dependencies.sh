#!/usr/bin/env bash
set -euo pipefail
if [ $# -ne 1 ]; then
  echo "Usage: $0 <folder>" >&2
  exit 1
fi
rm -f "$1/test-dependencies.lz4"
