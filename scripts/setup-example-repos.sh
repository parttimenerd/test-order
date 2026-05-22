#!/usr/bin/env bash
# setup-example-repos.sh
#
# Clone the external real-world projects used for demos, benchmarks, and
# integration testing into the third-party/ folder.
#
# Safe to re-run — already-cloned repos are skipped (with an optional pull).
#
# Usage:
#   bash scripts/setup-example-repos.sh          # clone only
#   bash scripts/setup-example-repos.sh --pull   # clone or pull latest

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="$REPO_ROOT/third-party"

PULL=false
if [[ "${1:-}" == "--pull" ]]; then
  PULL=true
fi

# ── colour helpers ─────────────────────────────────────────────────────────
G='\033[0;32m'; Y='\033[0;33m'; C='\033[0;36m'; R='\033[0m'
ok()   { printf "${G}✔ %s${R}\n" "$*"; }
info() { printf "${C}▸ %s${R}\n" "$*"; }
warn() { printf "${Y}⚠ %s${R}\n" "$*"; }

mkdir -p "$TARGET"

clone_or_pull() {
  local name="$1"
  local url="$2"
  local dest="$TARGET/$name"

  if [[ -d "$dest/.git" ]]; then
    if $PULL; then
      info "Pulling latest $name …"
      git -C "$dest" pull --ff-only
      ok "$name up-to-date"
    else
      warn "$name already exists — skipping (use --pull to update)"
    fi
  else
    info "Cloning $name …"
    git clone --depth 1 "$url" "$dest"
    ok "$name cloned"
  fi
}

# ── Repos ──────────────────────────────────────────────────────────────────
clone_or_pull spring-ai        https://github.com/spring-projects/spring-ai
clone_or_pull spring-boot      https://github.com/spring-projects/spring-boot
clone_or_pull spring-petclinic https://github.com/spring-projects/spring-petclinic

# ── Benchmark repos ───────────────────────────────────────────────────────
clone_or_pull guava            https://github.com/google/guava
clone_or_pull logging-log4j2   https://github.com/apache/logging-log4j2
clone_or_pull netty            https://github.com/netty/netty

echo
ok "All repos ready in $TARGET"
echo
echo "Next steps:"
echo "  Demo (Maven / Spring AI):  bash scripts/demo-spring-ai.sh"
echo "  Demo (Gradle / Spring Boot): bash scripts/demo-spring-boot.sh"
echo "  Smoke test (PetClinic):    bash scripts/check_and_run.sh"
