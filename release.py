#!/usr/bin/env python3
"""release.py — version bump and release helper for test-order.

Usage:
  python3 release.py              # bump minor (default), prompt before push
  python3 release.py --patch      # bump patch
  python3 release.py --major      # bump major
  python3 release.py --version 1.2.3   # set explicit version
  python3 release.py --dry-run    # show what would change, write nothing
  python3 release.py --no-push    # commit + tag locally, don't push
  python3 release.py --skip-tests # skip mvn test before committing
  python3 release.py bump         # update files + changelog only, no git
  python3 release.py changelog    # preview the CHANGELOG update

Releasing pushes main + the vX.Y.Z tag, which triggers release.yml → Maven
Central publish. The script never deploys directly.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from datetime import date
from pathlib import Path


ROOT = Path(__file__).resolve().parent

# ---------------------------------------------------------------------------
# Version helpers
# ---------------------------------------------------------------------------

def _current_version() -> str:
    text = (ROOT / "pom.xml").read_text()
    m = re.search(r"<version>(\d+\.\d+\.\d+)</version>", text)
    if not m:
        sys.exit("ERROR: cannot parse release version from pom.xml")
    return m.group(1)

def _bump(v: str, kind: str) -> str:
    major, minor, patch = (int(x) for x in v.split("."))
    if kind == "major": return f"{major + 1}.0.0"
    if kind == "minor": return f"{major}.{minor + 1}.0"
    return f"{major}.{minor}.{patch + 1}"

def _validate_semver(v: str):
    if not re.fullmatch(r"\d+\.\d+\.\d+", v):
        sys.exit(f"ERROR: '{v}' is not a valid X.Y.Z version")


# ---------------------------------------------------------------------------
# File scanning
# ---------------------------------------------------------------------------

_IGNORE_DIRS = {"third-party", "target", "build", ".git", "node_modules"}

# Directories that should never be bumped even if they contain matching version strings
_SKIP_DIRS = _IGNORE_DIRS | {"femtojar"}

def _pom_files() -> list[Path]:
    return [
        p for p in ROOT.rglob("pom.xml")
        if not any(part in _SKIP_DIRS for part in p.parts)
    ]

def _gradle_files() -> list[Path]:
    return [
        p for p in ROOT.rglob("*")
        if p.suffix in (".gradle", ".kts")
        and not any(part in _SKIP_DIRS for part in p.parts)
    ]

def _doc_files() -> list[Path]:
    return [
        p for p in ROOT.rglob("*")
        if p.suffix in (".md", ".mdx", ".sh", ".jsx", ".tsx", ".java")
        and not any(part in _SKIP_DIRS for part in p.parts)
    ]


# ---------------------------------------------------------------------------
# Bump logic
# ---------------------------------------------------------------------------

# Anchors that must appear near a version string for it to be a test-order ref.
# Used for POMs to avoid bumping unrelated <version> tags that happen to match.
_POM_ANCHORS = re.compile(
    r"me\.bechberger|test-order|testorder",
    re.IGNORECASE,
)

def _pom_wants_bump(text: str, old: str) -> bool:
    """True if the POM file should have `old` replaced.

    A POM should be bumped if:
    - It is a test-order module POM (contains test-order-parent as parent), OR
    - It references a me.bechberger/test-order artifact near the version string.
    """
    return bool(_POM_ANCHORS.search(text))

def bump_files(old: str, new: str, dry: bool = False) -> list[str]:
    """Replace version strings in all tracked files.

    For POMs, only bumps files that reference test-order artifacts.
    For Gradle/doc files, does a plain string replace (version is unique enough).
    Returns sorted list of relative paths of changed files.
    """
    changed: list[str] = []

    for p in _pom_files():
        text = p.read_text()
        if old not in text:
            continue
        if not _pom_wants_bump(text, old):
            continue
        rel = str(p.relative_to(ROOT))
        if not dry:
            p.write_text(text.replace(old, new))
        changed.append(rel)

    for p in _gradle_files() + _doc_files():
        text = p.read_text()
        if old not in text:
            continue
        rel = str(p.relative_to(ROOT))
        if not dry:
            p.write_text(text.replace(old, new))
        changed.append(rel)

    return sorted(set(changed))


# ---------------------------------------------------------------------------
# CHANGELOG
# ---------------------------------------------------------------------------

_EMPTY_HEADINGS = {
    "### Breaking", "### Added", "### Changed", "### Deprecated",
    "### Removed", "### Fixed", "### Security",
}

def _unreleased_body() -> str:
    text = (ROOT / "CHANGELOG.md").read_text()
    m = re.search(r"## \[Unreleased\]\s*\n(.*?)(?=\n## \[|\Z)", text, re.DOTALL)
    return m.group(1).strip() if m else ""

def _has_content(body: str) -> bool:
    return any(
        l.strip() and l.strip() not in _EMPTY_HEADINGS
        for l in body.splitlines()
    )

def validate_changelog() -> bool:
    body = _unreleased_body()
    if not _has_content(body):
        print("ERROR: CHANGELOG.md [Unreleased] has no content.")
        print("Add your changes there before releasing.")
        return False
    return True

_NEW_UNRELEASED = (
    "## [Unreleased]\n\n"
    "### Breaking\n### Added\n### Changed\n"
    "### Deprecated\n### Removed\n### Fixed\n### Security\n"
)

def update_changelog(new_version: str, dry: bool = False) -> str:
    today = date.today().isoformat()
    p = ROOT / "CHANGELOG.md"
    text = p.read_text()

    text = re.sub(
        r"## \[Unreleased\]",
        f"{_NEW_UNRELEASED}\n## [{new_version}] - {today}",
        text, count=1,
    )

    # Rewrite comparison links
    m = re.search(
        r"\[Unreleased\]: (https://[^\s]+)/compare/v([\d.]+)\.\.\.HEAD",
        text,
    )
    if m:
        base_url, prev = m.group(1), m.group(2)
        new_links = (
            f"[Unreleased]: {base_url}/compare/v{new_version}...HEAD\n"
            f"[{new_version}]: {base_url}/compare/v{prev}...v{new_version}"
        )
        text = re.sub(
            r"\[Unreleased\]: .+\n\[" + re.escape(prev) + r"\]: .+",
            new_links, text,
        )

    if not dry:
        p.write_text(text)
    return text


# ---------------------------------------------------------------------------
# Git helpers
# ---------------------------------------------------------------------------

def _run(cmd: list, *, check: bool = True) -> subprocess.CompletedProcess:
    print(f"  $ {' '.join(str(c) for c in cmd)}")
    return subprocess.run(cmd, cwd=ROOT, check=check, text=True)

def _git_clean() -> bool:
    return subprocess.run(["git", "diff", "--quiet", "HEAD"], cwd=ROOT).returncode == 0

def _git_branch() -> str:
    return subprocess.check_output(
        ["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=ROOT
    ).decode().strip()

def git_commit(version: str):
    print("\n→ Staging all changes")
    _run(["git", "add", "-A"])
    _run(["git", "commit", "-m", f"chore: release {version}"])

def git_tag(version: str):
    print(f"\n→ Creating annotated tag v{version}")
    _run(["git", "tag", "-a", f"v{version}", "-m", f"Release {version}"])

def git_push(version: str):
    branch = _git_branch()
    print(f"\n→ Pushing {branch} and tag v{version}")
    _run(["git", "push", "origin", branch])
    _run(["git", "push", "origin", f"v{version}"])


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

def run_tests():
    print("\n→ Running mvn test ...")
    _run(["mvn", "-B", "-ntp", "test"])


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="test-order release helper",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--patch",       action="store_true", help="Bump patch (0.0.x)")
    parser.add_argument("--minor",       action="store_true", help="Bump minor (0.x.0) [default]")
    parser.add_argument("--major",       action="store_true", help="Bump major (x.0.0)")
    parser.add_argument("--version",     metavar="X.Y.Z",     help="Set explicit target version")
    parser.add_argument("--no-push",     action="store_true", help="Commit + tag, but don't push")
    parser.add_argument("--skip-tests",  action="store_true", help="Skip mvn test")
    parser.add_argument("--dry-run",     action="store_true", help="Show changes, write nothing")

    sub = parser.add_subparsers(dest="cmd")
    sub.add_parser("bump",      help="Bump files + changelog only (no git ops)")
    sub.add_parser("changelog", help="Preview the CHANGELOG update")

    args = parser.parse_args()
    old = _current_version()

    # Resolve target version
    if args.version:
        _validate_semver(args.version)
        new = args.version
        kind = "explicit"
    elif args.major:
        new = _bump(old, "major"); kind = "major"
    elif args.patch:
        new = _bump(old, "patch"); kind = "patch"
    else:
        new = _bump(old, "minor"); kind = "minor"

    # ── changelog preview ─────────────────────────────────────────────────
    if args.cmd == "changelog":
        print(f"CHANGELOG preview: {old} → {new}\n")
        snippet = update_changelog(new, dry=True)
        print("\n".join(snippet.splitlines()[:30]))
        return

    # ── dry-run ───────────────────────────────────────────────────────────
    if args.dry_run:
        print(f"DRY RUN  {old} → {new}  ({kind})")
        changed = bump_files(old, new, dry=True)
        print(f"\n{len(changed)} file(s) would be updated:")
        for f in changed:
            print(f"  {f}")
        if validate_changelog():
            print("\nCHANGELOG (first 20 lines of updated file):")
            snippet = update_changelog(new, dry=True)
            print("\n".join(snippet.splitlines()[:20]))
        print("\n(no files written)")
        return

    # ── validate ──────────────────────────────────────────────────────────
    print(f"Releasing  {old} → {new}  ({kind})")

    if not validate_changelog():
        sys.exit(1)

    if not _git_clean():
        print("ERROR: working tree has uncommitted changes. Commit or stash first.")
        sys.exit(1)

    branch = _git_branch()
    if branch != "main":
        print(f"WARNING: on branch '{branch}', not 'main'.")

    # Confirm plan
    print(f"\nPlan:")
    print(f"  1. Bump all version strings  {old} → {new}")
    print(f"  2. Update CHANGELOG.md")
    if not args.skip_tests:
        print(f"  3. Run mvn test")
    print(f"  {'4' if not args.skip_tests else '3'}. git commit + tag v{new}")
    if not args.no_push:
        print(f"  {'5' if not args.skip_tests else '4'}. git push origin {branch} + v{new}")
        print(f"       → triggers release.yml → Maven Central publish")
    else:
        print(f"  (--no-push: tag stays local)")

    resp = input("\nContinue? [y/N] ").strip().lower()
    if resp not in ("y", "yes"):
        print("Aborted.")
        sys.exit(0)

    # ── bump files ────────────────────────────────────────────────────────
    print(f"\n→ Bumping version strings")
    changed = bump_files(old, new)
    print(f"  Updated {len(changed)} file(s)")

    # ── update CHANGELOG ──────────────────────────────────────────────────
    print(f"\n→ Updating CHANGELOG.md")
    update_changelog(new)

    # ── bump subcommand: stop here ────────────────────────────────────────
    if args.cmd == "bump":
        print(f"\n✓ Files updated to {new}. No git operations performed.")
        return

    # ── local tests ───────────────────────────────────────────────────────
    if not args.skip_tests:
        run_tests()

    # ── git ───────────────────────────────────────────────────────────────
    git_commit(new)
    git_tag(new)

    if not args.no_push:
        git_push(new)
        print(f"\n✓ Released {new}.")
        print(f"  CI:            https://github.com/parttimenerd/test-order/actions")
        print(f"  Maven Central: https://central.sonatype.com/artifact/me.bechberger/test-order-maven-plugin/{new}")
    else:
        print(f"\n✓ Committed and tagged v{new} locally (--no-push).")
        print(f"  When ready:")
        print(f"    git push origin {branch}")
        print(f"    git push origin v{new}")


if __name__ == "__main__":
    main()
