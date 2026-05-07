#!/usr/bin/env python3
"""
Update scoring weight defaults in documentation files from the single source of truth:
  test-order-core/src/main/resources/default-scoring-weights.toml

Usage:
  python scripts/update_scoring_docs.py          # update in place
  python scripts/update_scoring_docs.py --check  # exit 1 if docs are outdated
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOML_PATH = ROOT / "test-order-core" / "src" / "main" / "resources" / "default-scoring-weights.toml"

# Mapping from TOML section name to display metadata
WEIGHT_META = {
    "newTest": {
        "component": "New test bonus",
        "property": "testorder.score.newTest",
        "mojo_param": "scoreNewTest",
        "description": "Bonus for new test classes not in the dependency index",
        "short_desc": "Bonus for new test classes",
    },
    "changedTest": {
        "component": "Changed test bonus",
        "property": "testorder.score.changedTest",
        "mojo_param": "scoreChangedTest",
        "description": "Bonus for changed test sources",
        "short_desc": "Bonus for changed test sources",
    },
    "maxFailure": {
        "component": "Failure bonus",
        "property": "testorder.score.maxFailure",
        "mojo_param": "scoreMaxFailure",
        "description": "Cap on failure-based bonus",
        "short_desc": "Cap on failure-based bonus",
        "default_fmt": "1\u2013{value}",  # "1–5" style
    },
    "speed": {
        "component": "Speed bonus",
        "property": "testorder.score.speed",
        "mojo_param": "scoreSpeed",
        "description": "Bonus for fast tests (logarithmic scale: full bonus at 1/8\u00d7 median, zero at median)",
        "short_desc": "Bonus for fast tests",
    },
    "speedPenalty": {
        "component": "Speed penalty",
        "property": "testorder.score.speedPenalty",
        "mojo_param": "scoreSpeedPenalty",
        "description": "Penalty for slow tests (logarithmic scale: full penalty at 8\u00d7 median, zero at median)",
        "short_desc": "Penalty for slow tests",
    },
    "depOverlap": {
        "component": "Dependency overlap",
        "property": "testorder.score.depOverlap",
        "mojo_param": "scoreDepOverlap",
        "description": "Max score from dependency overlap (sqrt-normalized: overlap/\u221atotalDeps \u00d7 weight)",
        "short_desc": "Max score from dependency overlap",
        "default_fmt": "{value} (max)",
    },
    "changeComplexity": {
        "component": "Change complexity",
        "property": "testorder.score.changeComplexity",
        "mojo_param": "scoreChangeComplexity",
        "description": "Complexity-weighted overlap using Deflate-compressed file size as information-density proxy",
        "short_desc": "Complexity-weighted overlap",
        "default_fmt": "{value} (max)",
    },
    "staticFieldBonus": {
        "component": "Static field bonus",
        "property": "testorder.score.staticFieldBonus",
        "mojo_param": "scoreStaticFieldBonus",
        "description": "Bonus for changed static field overlap",
        "short_desc": "Bonus for changed static field overlap",
    },
    "coverageBonus": {
        "component": "Coverage bonus",
        "property": "testorder.score.coverageBonus",
        "mojo_param": "scoreCoverageBonus",
        "description": "Greedy set-cover bonus (replaces depOverlap + changeComplexity when > 0)",
        "short_desc": "Greedy set-cover bonus",
    },
}

# Weights shown in the main weights table (README.md, docs/SCORING.md)
MAIN_TABLE_WEIGHTS = [
    "newTest", "changedTest", "maxFailure", "speed", "speedPenalty", "depOverlap", "changeComplexity"
]

# All weights shown in CLI_REFERENCE and MAVEN_PLUGIN
ALL_WEIGHTS = [
    "newTest", "changedTest", "maxFailure", "speed", "speedPenalty",
    "depOverlap", "changeComplexity", "staticFieldBonus", "coverageBonus"
]


def parse_toml_weights(toml_path: Path) -> dict[str, int]:
    """Parse weight values from the TOML file (simple parser, no dependencies)."""
    weights = {}
    current_section = None
    content = toml_path.read_text()

    for line in content.splitlines():
        line = line.strip()
        # Section header like [newTest] or [method-scores.failureRecency]
        m = re.match(r"^\[([a-zA-Z][a-zA-Z0-9_.-]*)\]$", line)
        if m:
            current_section = m.group(1)
            continue
        # value = <number>
        if current_section and not current_section.startswith("method-scores") and current_section != "config":
            m = re.match(r"^value\s*=\s*(\d+)", line)
            if m:
                weights[current_section] = int(m.group(1))
    return weights


def fmt_default(name: str, value: int) -> str:
    """Format the default value for display."""
    meta = WEIGHT_META.get(name, {})
    fmt = meta.get("default_fmt")
    if fmt:
        return fmt.format(value=value)
    return str(value)


def update_readme_scoring_table(content: str, weights: dict[str, int]) -> str:
    """Update the weights table between BEGIN/END WEIGHTS TABLE markers."""
    begin = "<!-- BEGIN WEIGHTS TABLE -->"
    end = "<!-- END WEIGHTS TABLE -->"
    if begin not in content:
        return content

    lines = []
    lines.append("| Component | Default | Config property | Description |")
    lines.append("|---|---|---|---|")
    for name in MAIN_TABLE_WEIGHTS:
        meta = WEIGHT_META[name]
        default_str = fmt_default(name, weights[name])
        lines.append(
            f"| **{meta['component']}** | {default_str} | "
            f"`{meta['property']}` | {meta['description']} |"
        )

    table = "\n".join(lines)
    pattern = re.compile(
        re.escape(begin) + r"\n.*?\n" + re.escape(end),
        re.DOTALL,
    )
    return pattern.sub(f"{begin}\n{table}\n{end}", content)


def update_cli_reference(content: str, weights: dict[str, int]) -> str:
    """Update the scoring overrides table in CLI_REFERENCE.md."""
    # Find the "### Scoring Overrides" section and replace its table
    header = "### Scoring Overrides"
    if header not in content:
        return content

    lines = content.splitlines()
    start_idx = None
    table_start = None
    table_end = None

    for i, line in enumerate(lines):
        if header in line:
            start_idx = i
        elif start_idx is not None and table_start is None and line.startswith("| Property"):
            table_start = i
        elif table_start is not None and table_end is None:
            if not line.startswith("|"):
                table_end = i
                break

    if table_start is None:
        return content
    if table_end is None:
        table_end = len(lines)

    new_table = []
    new_table.append("| Property | Default |")
    new_table.append("|---|---|")
    for name in ALL_WEIGHTS:
        meta = WEIGHT_META[name]
        new_table.append(f"| `{meta['property']}` | `{weights[name]}` |")
    new_table.append("| `testorder.weights.file` | unset |")

    result = lines[:table_start] + new_table + lines[table_end:]
    joined = "\n".join(result)
    if content.endswith("\n"):
        joined += "\n"
    return joined


def update_maven_plugin_scores(content: str, weights: dict[str, int]) -> str:
    """Update scoring rows in the MAVEN_PLUGIN.md configuration table."""
    for name in ALL_WEIGHTS:
        meta = WEIGHT_META[name]
        # Match: | `scoreXxx` | `testorder.score.xxx` | `<old>` | ... |
        pattern = re.compile(
            r"(\| `" + re.escape(meta["mojo_param"]) + r"` \| `"
            + re.escape(meta["property"]) + r"` \| `)(\d+)(`)"
        )
        content = pattern.sub(rf"\g<1>{weights[name]}\g<3>", content)
    return content


def update_file(path: Path, updater, weights: dict[str, int], check_only: bool) -> bool:
    """Update a single file. Returns True if content changed."""
    if not path.exists():
        return False
    content = path.read_text()
    updated = updater(content, weights)
    if content == updated:
        return False
    if not check_only:
        path.write_text(updated)
        print(f"  Updated: {path.relative_to(ROOT)}")
    else:
        print(f"  Outdated: {path.relative_to(ROOT)}")
    return True


def main():
    check_only = "--check" in sys.argv

    if not TOML_PATH.exists():
        print(f"ERROR: TOML file not found: {TOML_PATH}", file=sys.stderr)
        sys.exit(1)

    weights = parse_toml_weights(TOML_PATH)
    print(f"Parsed weights from {TOML_PATH.relative_to(ROOT)}:")
    for name in ALL_WEIGHTS:
        print(f"  {name} = {weights[name]}")

    print()
    changed = False

    # README.md
    changed |= update_file(
        ROOT / "README.md", update_readme_scoring_table, weights, check_only
    )
    # docs/SCORING.md
    changed |= update_file(
        ROOT / "docs" / "SCORING.md", update_readme_scoring_table, weights, check_only
    )
    # docs/CLI_REFERENCE.md
    changed |= update_file(
        ROOT / "docs" / "CLI_REFERENCE.md", update_cli_reference, weights, check_only
    )
    # docs/MAVEN_PLUGIN.md
    changed |= update_file(
        ROOT / "docs" / "MAVEN_PLUGIN.md", update_maven_plugin_scores, weights, check_only
    )

    if check_only and changed:
        print("\nDocs are outdated! Run: python scripts/update_scoring_docs.py")
        sys.exit(1)
    elif not changed:
        print("All docs are up to date.")


if __name__ == "__main__":
    main()
