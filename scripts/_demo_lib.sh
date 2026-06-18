#!/usr/bin/env bash
# Shared helpers for demo-*.sh scripts.
#
# Source it from each demo:
#   source "$(dirname "$0")/_demo_lib.sh"
#
# Provides: banner, step, pause, type_cmd, slow_type, header_box,
# repo_root, ensure_jdk17_or_higher.

# ── Colours ───────────────────────────────────────────────────────
C='\033[0;36m'   # cyan
G='\033[0;32m'   # green
Y='\033[0;33m'   # yellow
M='\033[0;35m'   # magenta
B='\033[1m'      # bold
R='\033[0m'      # reset
export C G Y M B R

# ── Pacing ────────────────────────────────────────────────────────
# Override DEMO_FAST=1 to skip pauses (handy when iterating locally).
pause() {
	[ "${DEMO_FAST:-0}" = "1" ] && return 0
	sleep "${1:-1.0}"
}

banner() {
	printf "\n${C}${B}═══ %s ═══${R}\n\n" "$1"
}

step() {
	printf "${G}▸ %s${R}\n" "$1"
}

# Prints a bold $-prefixed command, pauses briefly, then executes it.
type_cmd() {
	printf "${Y}\$ %s${R}\n" "$1"
	pause 0.6
	eval "$1"
}

# Simulates human typing one character at a time. Used for the showpiece
# command in each demo. asciinema --idle-time-limit + trim-cast-idle.sh
# squash any leftover gaps in post.
slow_type() {
	local cmd="$1"
	local delay="${2:-0.025}"
	printf "${Y}\$ ${R}"
	for ((i=0; i<${#cmd}; i++)); do
		printf "%s" "${cmd:$i:1}"
		[ "${DEMO_FAST:-0}" = "1" ] || sleep "$delay"
	done
	printf "\n"
	pause 0.4
	eval "$cmd"
}

repo_root() {
	cd "$(dirname "$0")/.." && pwd
}

ensure_jdk17_or_higher() {
	local v
	v=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)\..*/\1/;s/.*"([0-9]+)".*/\1/')
	if [ -n "$v" ] && [ "$v" -lt 17 ]; then
		echo "ERROR: this demo needs JDK 17 or higher (found $v). Set JAVA_HOME and retry." >&2
		exit 1
	fi
}
