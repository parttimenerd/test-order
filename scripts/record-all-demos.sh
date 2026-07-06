#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────
#  record-all-demos.sh — re-record every demo-*.sh into a .cast file.
#
#  Usage:
#    scripts/record-all-demos.sh [out-dir]
#
#  Default out-dir: docs/assets/casts. Each cast is post-processed
#  through trim-cast-idle.sh.
#
#  Requires: asciinema, jq, awk (any), bash.
# ──────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT_DIR="${1:-$ROOT/docs/assets/casts}"

mkdir -p "$OUT_DIR"

if ! command -v asciinema >/dev/null 2>&1; then
	echo "ERROR: asciinema not installed (brew install asciinema / apt-get install asciinema)" >&2
	exit 1
fi

# Order matters: hero demo first so failures abort early.
DEMOS=(
	demo-hello-world
	demo-learn
	demo-tiered
	demo-dashboard
	demo-optimizer
	demo-diagnose
	demo-multi-module
	demo-spring-ai
	demo-spring-boot
)

# Per-demo idle-gap target (seconds). Snappy for short demos, more
# breathing room for long ones.
declare -A IDLE
IDLE[demo-hello-world]=0.6
IDLE[demo-learn]=0.8
IDLE[demo-tiered]=1.0
IDLE[demo-dashboard]=1.0
IDLE[demo-optimizer]=1.5
IDLE[demo-diagnose]=0.8
IDLE[demo-multi-module]=1.5
IDLE[demo-spring-ai]=1.5
IDLE[demo-spring-boot]=2.0

for name in "${DEMOS[@]}"; do
	script="$SCRIPT_DIR/$name.sh"
	out="$OUT_DIR/$name.cast"
	if [ ! -x "$script" ]; then
		echo "[skip] $script not executable"
		continue
	fi
	echo "─── recording $name ───"
	rm -f "$out"
	if asciinema rec \
		--cols 120 --rows 32 \
		--idle-time-limit 2 \
		--overwrite \
		--command "bash $script" \
		"$out" 2>&1; then
		if [ -f "$out" ] && [ -s "$out" ]; then
			"$SCRIPT_DIR/trim-cast-idle.sh" "$out" "${IDLE[$name]:-1.0}"
		else
			echo "[skip] $name produced no cast (preflight check triggered)"
			rm -f "$out"
		fi
	else
		echo "::warning::Demo $name failed to record; skipping cast"
		rm -f "$out"
	fi
done

echo
echo "All demos recorded to $OUT_DIR/"
ls -lh "$OUT_DIR"
