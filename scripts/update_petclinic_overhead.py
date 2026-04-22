#!/usr/bin/env python3
"""Benchmark spring-petclinic instrumentation overhead and update README.md.

Usage:
    python3 scripts/update_petclinic_overhead.py

The script:
1. Installs the local test-order artifacts unless --skip-install is set
2. Precompiles spring-petclinic tests unless --skip-precompile is set
3. Benchmarks a pure surefire baseline plus all instrumentation modes
4. Writes raw results to spring-petclinic/target/petclinic-overhead.json
5. Replaces the README block between the PetClinic overhead markers
"""

from __future__ import annotations

import argparse
import json
import random
import shutil
import statistics
import subprocess
import time
from pathlib import Path

README_BLOCK_START = "<!-- BEGIN SPRING PETCLINIC OVERHEAD -->"
README_BLOCK_END = "<!-- END SPRING PETCLINIC OVERHEAD -->"

MODE_ORDER = ["NONE", "METHOD_ENTRY", "FULL", "FULL_METHOD", "FULL_MEMBER"]
MODE_LABELS = {
    "NONE": "none",
    "METHOD_ENTRY": "`METHOD_ENTRY`",
    "FULL": "`FULL`",
    "FULL_METHOD": "`FULL_METHOD`",
    "FULL_MEMBER": "`FULL_MEMBER`",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Benchmark spring-petclinic instrumentation overhead and update README.md."
    )
    parser.add_argument("--repeats", type=int, default=5, help="Measured runs per mode (default: 5)")
    parser.add_argument("--warmups", type=int, default=1, help="Warm-up runs per mode (default: 1)")
    parser.add_argument("--seed", type=int, default=42, help="Shuffle seed for run order (default: 42)")
    parser.add_argument("--skip-install", action="store_true", help="Skip installing local test-order artifacts")
    parser.add_argument("--skip-precompile", action="store_true", help="Skip spring-petclinic test-compile")
    parser.add_argument("--dry-run", action="store_true", help="Print the generated README block without writing it")
    return parser.parse_args()


def replace_between(text: str, start: str, end: str, replacement: str) -> str:
    start_idx = text.find(start)
    end_idx = text.find(end)
    if start_idx == -1 or end_idx == -1 or end_idx < start_idx:
        raise ValueError(f"README markers not found: {start} / {end}")
    return text[: start_idx + len(start)] + "\n" + replacement + "\n" + text[end_idx:]


def run(command: list[str], cwd: Path) -> None:
    result = subprocess.run(command, cwd=cwd)
    if result.returncode != 0:
        raise SystemExit(f"Command failed ({result.returncode}): {' '.join(command)}")


def cleanup(paths: list[Path]) -> None:
    for path in paths:
        if path.is_dir():
            shutil.rmtree(path, ignore_errors=True)
        elif path.exists():
            path.unlink()


def benchmark(args: argparse.Namespace, repo_root: Path) -> dict[str, dict[str, float | list[float]]]:
    petclinic_dir = repo_root / "spring-petclinic"
    readme_path = repo_root / "README.md"
    output_json = petclinic_dir / "target" / "petclinic-overhead.json"

    if args.skip_install is False:
        print("Installing local test-order artifacts...")
        run(
            [
                "mvn",
                "-q",
                "-pl",
                "test-order-agent,test-order-junit,test-order-maven-plugin",
                "-am",
                "install",
                "-DskipTests",
            ],
            cwd=repo_root,
        )

    if args.skip_precompile is False:
        print("Precompiling spring-petclinic tests...")
        run(
            [
                "./mvnw",
                "-q",
                "test-compile",
                "-Dcheckstyle.skip=true",
                "-Dspring-javaformat.skip=true",
            ],
            cwd=petclinic_dir,
        )

    common = [
        "./mvnw",
        "-q",
        "-Dcheckstyle.skip=true",
        "-Dspring-javaformat.skip=true",
        "-Dtest=!*IntegrationTests,!MySqlIntegrationTests,!PostgresIntegrationTests,!MysqlTestApplication",
    ]
    commands = {
        "NONE": common + ["surefire:test"],
        "METHOD_ENTRY": common
        + ["test-order:prepare", "surefire:test", "-Dtestorder.mode=learn", "-Dtestorder.instrumentationMode=METHOD_ENTRY"],
        "FULL": common
        + ["test-order:prepare", "surefire:test", "-Dtestorder.mode=learn", "-Dtestorder.instrumentationMode=FULL"],
        "FULL_METHOD": common
        + ["test-order:prepare", "surefire:test", "-Dtestorder.mode=learn", "-Dtestorder.instrumentationMode=FULL_METHOD"],
        "FULL_MEMBER": common
        + ["test-order:prepare", "surefire:test", "-Dtestorder.mode=learn", "-Dtestorder.instrumentationMode=FULL_MEMBER"],
    }

    cleanup_paths = [
        petclinic_dir / ".test-order" / "test-dependencies.lz4",
        petclinic_dir / ".test-order" / "hashes.lz4",
        petclinic_dir / ".test-order-test-hashes.lz4",
        petclinic_dir / ".test-order" / "state.lz4",
        petclinic_dir / ".test-order-durations",
        petclinic_dir / ".test-order-failures",
        petclinic_dir / ".test-order-method-durations",
        petclinic_dir / ".test-order-method-failures",
        petclinic_dir / "target" / "test-order-deps",
        petclinic_dir / "target" / "surefire-reports",
    ]

    for _ in range(args.warmups):
        for mode in MODE_ORDER:
            cleanup(cleanup_paths)
            run(commands[mode], cwd=petclinic_dir)
            print(f"warm-up {mode} ok")

    rng = random.Random(args.seed)
    results: dict[str, list[float]] = {mode: [] for mode in MODE_ORDER}
    for round_index in range(args.repeats):
        round_modes = MODE_ORDER[:]
        rng.shuffle(round_modes)
        print(f"round {round_index + 1} order: {', '.join(round_modes)}")
        for mode in round_modes:
            cleanup(cleanup_paths)
            start = time.perf_counter()
            run(commands[mode], cwd=petclinic_dir)
            elapsed = time.perf_counter() - start
            results[mode].append(elapsed)
            print(f"{mode} run {len(results[mode])}: {elapsed:.3f}s")

    summary: dict[str, dict[str, float | list[float]]] = {}
    baseline_avg = statistics.mean(results["NONE"])
    baseline_median = statistics.median(results["NONE"])
    for mode, times in results.items():
        avg = statistics.mean(times)
        median = statistics.median(times)
        stdev = statistics.stdev(times) if len(times) > 1 else 0.0
        summary[mode] = {
            "times": times,
            "avg": avg,
            "median": median,
            "stdev": stdev,
            "overhead_pct_avg": ((avg - baseline_avg) / baseline_avg * 100.0) if baseline_avg else 0.0,
            "overhead_pct_median": ((median - baseline_median) / baseline_median * 100.0) if baseline_median else 0.0,
        }

    output_json.parent.mkdir(parents=True, exist_ok=True)
    output_json.write_text(json.dumps(summary, indent=2) + "\n")
    print(f"Wrote raw results to {output_json.relative_to(repo_root)}")

    block = generate_readme_block(args.repeats, summary)
    if args.dry_run:
        print(block)
    else:
        updated = replace_between(readme_path.read_text(), README_BLOCK_START, README_BLOCK_END, block)
        readme_path.write_text(updated)
        print("Updated README.md")
    return summary


def generate_readme_block(repeats: int, summary: dict[str, dict[str, float | list[float]]]) -> str:
    lines = [
        "Measured learn-run timings on `spring-petclinic` "
        f"({repeats} measured runs per mode, baseline = pure `surefire:test` without `test-order:prepare`, "
        "`-Dcheckstyle.skip=true`, `-Dspring-javaformat.skip=true`, excluding "
        "`*IntegrationTests`, `MySqlIntegrationTests`, `PostgresIntegrationTests`, and `MysqlTestApplication`):",
        "",
        "| Mode | Avg time | Median | Std dev | Overhead vs none |",
        "|---|---:|---:|---:|---:|",
    ]
    for mode in MODE_ORDER:
        data = summary[mode]
        lines.append(
            f"| {MODE_LABELS[mode]} | {data['avg']:.3f} s | {data['median']:.3f} s | "
            f"{data['stdev']:.3f} s | {data['overhead_pct_avg']:.1f}% |"
        )
    lines.extend(
        [
            "",
            "Regenerate this table with `python3 scripts/update_petclinic_overhead.py`.",
        ]
    )
    return "\n".join(lines)


def main() -> int:
    args = parse_args()
    repo_root = Path(__file__).resolve().parent.parent
    benchmark(args, repo_root)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
