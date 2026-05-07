#!/usr/bin/env python3
"""Delete .test files from a directory tree.

Usage:
    python scripts/delete_dot_test_files.py <folder>
    python scripts/delete_dot_test_files.py <folder> --dry-run
"""

import argparse
import sys
from pathlib import Path


def find_dot_test_files(folder: Path):
    return sorted(folder.rglob("*.test"))


def main():
    parser = argparse.ArgumentParser(description="Delete .test files from a directory tree")
    parser.add_argument("folder", type=Path, help="Root folder to search")
    parser.add_argument("--dry-run", action="store_true", help="Preview files without deleting")
    args = parser.parse_args()

    if not args.folder.is_dir():
        print(f"Error: '{args.folder}' is not a directory", file=sys.stderr)
        sys.exit(1)

    files = find_dot_test_files(args.folder)

    if not files:
        print("No .test files found.")
        return

    for f in files:
        if args.dry_run:
            print(f"Would delete: {f}")
        else:
            f.unlink()
            print(f"Deleted: {f}")

    if args.dry_run:
        print(f"\n{len(files)} file(s) would be deleted. Run without --dry-run to delete.")
    else:
        print(f"\n{len(files)} file(s) deleted.")


if __name__ == "__main__":
    main()
