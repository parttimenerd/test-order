#!/usr/bin/env python3
"""Validate every parsed JSON file against its source Java file.

The check is structural: it compares the JSON model to the Java source after
stripping comments and string/char literals, then verifies that recorded types
and methods match the corresponding source fragments.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent.parent


def strip_comments_and_literals(text: str) -> str:
    result = list(text)

    def blank(match: re.Match[str]) -> None:
        for index in range(match.start(), match.end()):
            result[index] = " "

    for pattern in (
        re.compile(r"/\*.*?\*/", re.S),
        re.compile(r"//.*?$", re.M),
        re.compile(r'"(?:\\.|[^"\\])*"', re.S),
        re.compile(r"'(?:\\.|[^'\\])+'", re.S),
    ):
        for match in pattern.finditer(text):
            blank(match)

    return "".join(result)


def find_matching_brace(text: str, open_index: int) -> int:
    depth = 0
    for index in range(open_index, len(text)):
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index
    return -1


def source_from_recorded_file(recorded_file: str | None) -> Path | None:
    if not recorded_file:
        return None

    candidate = Path(recorded_file)
    if not candidate.is_absolute():
        candidate = ROOT / candidate

    if candidate.exists():
        return candidate

    return None


def normalized_package(text: str) -> str:
    match = re.search(r"^\s*package\s+([A-Za-z0-9_.]+)\s*;", text, re.M)
    return match.group(1) if match else ""


def locate_block(text: str, needle: str, start_at: int = 0) -> tuple[int, int] | None:
    index = text.find(needle, start_at)
    if index < 0:
        return None
    brace_index = text.find("{", index + len(needle))
    if brace_index < 0:
        return None
    end_index = find_matching_brace(text, brace_index)
    if end_index < 0:
        return None
    return brace_index, end_index


def validate_method(method: dict[str, Any], body: str, errors: list[str], prefix: str) -> None:
    signature = method.get("signature", "")
    if not signature:
        errors.append(f"{prefix}: missing method signature")
        return

    start_index = body.find(signature)
    if start_index < 0:
        errors.append(f"{prefix}: method signature not found: {signature!r}")
        return

    if method.get("body") is not None:
        brace_index = body.find("{", start_index + len(signature) - 1)
        if brace_index < 0:
            errors.append(f"{prefix}: opening brace not found for method {method.get('name')!r}")
            return
        end_index = find_matching_brace(body, brace_index)
        if end_index < 0:
            errors.append(f"{prefix}: unmatched braces in method {method.get('name')!r}")
            return
        source_body = body[brace_index + 1 : end_index]
        if source_body != method.get("body"):
            errors.append(f"{prefix}: method body mismatch for {method.get('name')!r}")


def validate_type(type_obj: dict[str, Any], body: str, errors: list[str], prefix: str) -> None:
    kind = type_obj.get("kind")
    name = type_obj.get("name")
    if not kind or not name:
        errors.append(f"{prefix}: incomplete type record")
        return

    needle = f"{kind} {name}"
    type_location = locate_block(body, needle)
    if not type_location:
        errors.append(f"{prefix}: type declaration not found: {needle!r}")
        return

    brace_index, end_index = type_location
    source_body = body[brace_index + 1 : end_index]
    json_body = type_obj.get("body")
    if source_body != json_body:
        errors.append(f"{prefix}: type body mismatch for {name!r}")

    methods = type_obj.get("methods", [])
    for index, method in enumerate(methods):
        validate_method(method, source_body, errors, f"{prefix}.{name}.methods[{index}]")

    inner_types = type_obj.get("inner_types", [])
    for index, inner in enumerate(inner_types):
        validate_type(inner, source_body, errors, f"{prefix}.{name}.inner_types[{index}]")


def validate_file(json_path: Path) -> list[str]:
    errors: list[str] = []
    payload = json.loads(json_path.read_text(encoding="utf-8"))
    source_path = source_from_recorded_file(payload.get("file"))

    if source_path is None:
        errors.append(f"source file could not be resolved from {payload.get('file')!r}")
        return errors

    if not source_path.exists():
        errors.append(f"source file missing: {source_path}")
        return errors

    source_text = source_path.read_text(encoding="utf-8")
    cleaned = strip_comments_and_literals(source_text)

    recorded_file = payload.get("file")
    if recorded_file:
        recorded_candidate = Path(recorded_file)
        if not recorded_candidate.is_absolute():
            recorded_candidate = ROOT / recorded_candidate
        if recorded_candidate.resolve() != source_path.resolve():
            errors.append(f"file field mismatch: {recorded_file!r} vs {source_path}")

    recorded_package = payload.get("package", "")
    source_package = normalized_package(cleaned)
    if recorded_package != source_package:
        errors.append(
            f"package mismatch: json={recorded_package!r} source={source_package!r}"
        )

    for index, type_obj in enumerate(payload.get("types", [])):
        validate_type(type_obj, cleaned, errors, f"types[{index}]")

    return errors


def main() -> int:
    json_files = sorted((ROOT / "src_parsed").rglob("*.json"))
    total = 0
    failed = 0
    failures: list[tuple[Path, list[str]]] = []

    for json_path in json_files:
        total += 1
        errors = validate_file(json_path)
        if errors:
            failed += 1
            failures.append((json_path, errors))

    print(f"Validated {total} JSON files")
    print(f"Passed: {total - failed}")
    print(f"Failed: {failed}")
    if failures:
        print("\nFirst 20 failures:")
        for json_path, errors in failures[:20]:
            print(f"- {json_path}")
            for error in errors[:5]:
                print(f"  {error}")

    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
