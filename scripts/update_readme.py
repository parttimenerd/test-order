#!/usr/bin/env python3
"""Embeds the default-scoring-weights.toml resource into README.md.

Reads the TOML resource file and generates a Markdown table and formula,
replacing the content between marker comments in the README.

Usage:
    python3 update_readme.py

Requires: pip install tomli  (Python < 3.11) or uses built-in tomllib (3.11+)
"""

import re
from pathlib import Path

try:
    import tomllib
except ModuleNotFoundError:
    import tomli as tomllib

RESOURCE = Path("test-order-junit/src/main/resources/default-scoring-weights.toml")
README = Path("README.md")

WEIGHTS_TABLE_START = "<!-- BEGIN WEIGHTS TABLE -->"
WEIGHTS_TABLE_END = "<!-- END WEIGHTS TABLE -->"
WEIGHTS_FORMULA_START = "<!-- BEGIN WEIGHTS FORMULA -->"
WEIGHTS_FORMULA_END = "<!-- END WEIGHTS FORMULA -->"

def parse_weights(path: Path) -> list[dict]:
    """Parse the default-scoring-weights.toml resource file."""
    with open(path, "rb") as f:
        data = tomllib.load(f)
    weights = []
    # read TOML comments for descriptions
    lines = path.read_text().splitlines()
    comment_for: dict[str, str] = {}
    pending_comment = []
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("#"):
            text = stripped.lstrip("# ").strip()
            if text and not text.startswith("Copy this") and not text.startswith("Or run:") \
                    and not text.startswith("Each weight") and not text.startswith("test-order"):
                pending_comment.append(text)
        elif stripped.startswith("[") and stripped.endswith("]"):
            name = stripped[1:-1]
            if pending_comment:
                comment_for[name] = pending_comment[0]
                pending_comment = []
        else:
            if not stripped:
                pass  # blank line
            else:
                pending_comment = []

    for name, table in data.items():
        if isinstance(table, dict):
            weights.append({
                "name": name,
                "default": table["value"],
                "min": table.get("min", 0),
                "max": table.get("max", 50),
                "description": comment_for.get(name, ""),
            })
    return weights

FORMULA_PARTS = {
    "newTest": ("isNew ? newTestBonus : 0", "+"),
    "changedTest": ("isChanged ? changedTestBonus : 0", "+"),
    "maxFailure": ("min(ceil(recencyWeightedFailures), maxFailureBonus)", "+"),
    "speed": ("isFastTest ? speedBonus : 0", "+"),
    "speedPenalty": ("isSlowTest ? speedPenalty : 0", "-"),
    "depOverlap": ("depOverlap × count(dependencies ∩ changedClasses)", "+"),
}

def generate_table(weights: list[dict]) -> str:
    """Generate a Markdown table from weight definitions."""
    lines = [
        "| Component | Default | Config property | Description |",
        "|---|---|---|---|",
    ]
    display_names = {
        "newTest": "New test bonus",
        "changedTest": "Changed test bonus",
        "maxFailure": "Failure bonus",
        "speed": "Speed bonus",
        "speedPenalty": "Speed penalty",
        "depOverlap": "Dependency overlap",
    }
    for w in weights:
        name = display_names.get(w["name"], w["name"])
        default = w["default"]
        if w["name"] == "maxFailure":
            default = f'1\u2013{w["default"]}'
        elif w["name"] == "depOverlap":
            default = f'\u00d7{w["default"]} each'
        prop = f'`testorder.score.{w["name"]}`'
        lines.append(f'| **{name}** | {default} | {prop} | {w["description"]} |')
    return "\n".join(lines)

def generate_formula(weights: list[dict]) -> str:
    """Generate the scoring formula from weight definitions."""
    lines = ["```"]
    first = True
    for w in weights:
        name = w["name"]
        if name in FORMULA_PARTS:
            expr, sign = FORMULA_PARTS[name]
            if first:
                lines.append(f"score = ({expr})")
                first = False
            else:
                lines.append(f"      {sign} ({expr})")
    lines.append("```")
    return "\n".join(lines)

def replace_between(text: str, start: str, end: str, replacement: str) -> str:
    """Replace content between start and end markers (inclusive of markers)."""
    pattern = re.escape(start) + r".*?" + re.escape(end)
    new_block = f"{start}\n{replacement}\n{end}"
    result, count = re.subn(pattern, new_block, text, flags=re.DOTALL)
    if count == 0:
        print(f"WARNING: markers {start} / {end} not found in README")
    return result

def main():
    if not RESOURCE.exists():
        print(f"ERROR: Resource file not found: {RESOURCE}")
        return 1
    if not README.exists():
        print(f"ERROR: README not found: {README}")
        return 1

    weights = parse_weights(RESOURCE)
    if not weights:
        print("ERROR: No weights parsed from resource file")
        return 1

    print(f"Parsed {len(weights)} weights: {[w['name'] for w in weights]}")

    readme = README.read_text()
    readme = replace_between(readme, WEIGHTS_TABLE_START, WEIGHTS_TABLE_END, generate_table(weights))
    readme = replace_between(readme, WEIGHTS_FORMULA_START, WEIGHTS_FORMULA_END, generate_formula(weights))
    README.write_text(readme)
    print("README.md updated successfully")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
