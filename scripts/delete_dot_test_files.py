#!/usr/bin/env python3
"""Delete files ending with .test under a given folder.

Usage:
  python scripts/delete_dot_test_files.py <folder>
  python scripts/delete_dot_test_files.py <folder> --dry-run
"""

from __future__ import annotations

import argparse
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Delete files ending with .test under a folder recursively."
    )
    parser.add_argument("folder", help="Root folder to scan")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Only print files that would be deleted",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = Path(args.folder).expanduser().resolve()

    if not root.exists() or not root.is_dir():
        print(f"Error: folder does not exist or is not a directory: {root}")
        return 1

    matches = sorted(p for p in root.rglob("*.test") if p.is_file())

    if not matches:
        print(f"No .test files found in {root}")
        return 0

    action = "Would delete" if args.dry_run else "Deleting"
    print(f"{action} {len(matches)} file(s):")
    for path in matches:
        print(path)

    if args.dry_run:
        return 0

    deleted = 0
    for path in matches:
        path.unlink(missing_ok=True)
        deleted += 1

    print(f"Deleted {deleted} file(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
