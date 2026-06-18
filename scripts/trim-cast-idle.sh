#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────
#  trim-cast-idle.sh — compress idle gaps in an asciinema cast.
#
#  Auto-detects format:
#    v2: line-1 header has "version": 2, event time = absolute seconds.
#    v3: line-1 header has "version": 3, event time = delta from the
#        previous event.
#
#  In both cases idle = the inter-event gap. We cap that gap at
#  MAX_IDLE seconds. The header's `duration` (if present) is rewritten
#  to the new total.
#
#  Usage:
#    scripts/trim-cast-idle.sh path/to/cast.cast [MAX_IDLE]
#
#  MAX_IDLE defaults to 1.5 seconds. Pass 0.5 for very snappy demos.
#
#  Implementation: pure jq + awk + bash. No Rust, no node.
# ──────────────────────────────────────────────────────────────────
set -euo pipefail

CAST="${1:?usage: trim-cast-idle.sh <cast> [max-idle-seconds]}"
MAX_IDLE="${2:-1.5}"

if [ ! -f "$CAST" ]; then
	echo "ERROR: cast file not found: $CAST" >&2
	exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
	echo "ERROR: jq not installed (brew install jq / apt-get install jq)" >&2
	exit 1
fi

HEADER=$(head -n 1 "$CAST")
VERSION=$(echo "$HEADER" | jq -r '.version // 2')

TMP="$(mktemp -t trim-cast.XXXXXX)"
trap 'rm -f "$TMP" "$TMP.dur"' EXIT

# The awk script outputs the rewritten event lines on stdout and the
# new total duration to /dev/stderr.
awk -v MAX="$MAX_IDLE" -v VERSION="$VERSION" '
	BEGIN {
		prev = 0      # absolute time of the previous event (v2 sees this raw)
		total = 0     # cumulative absolute time after trimming
	}
	{
		line = $0
		# Pull out the leading number — works for both [0.029, "o", ...]
		# and [12.345,"i","x"] etc.
		if (!match(line, /^\[[ \t]*[-0-9.eE+]+/)) next
		tstr = substr(line, RSTART + 1, RLENGTH - 1)
		gsub(/^[ \t]+/, "", tstr)
		t = tstr + 0
		rest = substr(line, RSTART + RLENGTH)

		if (VERSION + 0 == 3) {
			# v3: t is already a delta. Clamp it.
			delta = t
			if (delta > MAX) delta = MAX
			total += delta
			printf "[%.6f%s\n", delta, rest
		} else {
			# v2: t is absolute. delta = t - prev. Clamp the delta and
			# emit absolute = total + delta.
			delta = t - prev
			if (delta > MAX) delta = MAX
			total += delta
			prev = t
			printf "[%.6f%s\n", total, rest
		}
	}
	END {
		printf "DURATION=%.6f\n", total > "/dev/stderr"
	}
' < <(tail -n +2 "$CAST") > "$TMP" 2> "$TMP.dur"

NEW_DUR=$(awk -F= '/^DURATION=/ {print $2}' "$TMP.dur" | tail -1)

# Splice updated duration into the header (if header has the field).
if [ -n "${NEW_DUR:-}" ]; then
	NEW_HEADER=$(echo "$HEADER" | jq -c --argjson d "$NEW_DUR" '
		if has("duration") then .duration = $d else . end
	')
	{
		echo "$NEW_HEADER"
		cat "$TMP"
	} > "$TMP.2"
	mv "$TMP.2" "$CAST"
else
	{
		echo "$HEADER"
		cat "$TMP"
	} > "$TMP.2"
	mv "$TMP.2" "$CAST"
fi

echo "trimmed: $CAST  (v$VERSION, max idle ${MAX_IDLE}s, new duration ${NEW_DUR:-?}s)"
