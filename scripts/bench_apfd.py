#!/usr/bin/env python3
"""
bench_apfd.py — APFD benchmark for the test-order conference talk.

For each repo × each bug patch:
  1. Ensure a learn index exists (build clean if missing, time it).
  2. Run mvn test with testorder.mode=skip (alphabetical baseline).
  3. Run mvn test with testorder.mode=auto (test-order reordering).
  4. Compute APFD, first-failure position, time-to-first-failure% for both.

Results are written to target/bench-apfd/<timestamp>/results.csv after
every individual trial so progress is never lost.

Usage:
    python3 scripts/bench_apfd.py [REPO...]     # omit for all default repos
    python3 scripts/bench_apfd.py --no-install  # skip mvn install step
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from typing import Optional

# ─── Paths ───────────────────────────────────────────────────────────────────

SCRIPT_DIR  = Path(__file__).parent.resolve()
ROOT_DIR    = SCRIPT_DIR.parent
THIRD_PARTY = ROOT_DIR / "third-party"
BUGS_DIR    = SCRIPT_DIR / "bugs"
RESULTS_DIR = ROOT_DIR / "target" / "bench-apfd"
OVERRIDES   = SCRIPT_DIR / "third-party-overrides.sh"

PLUGIN_VERSION  = "0.1.0"
PLUGIN_GROUP    = "me.bechberger"
PLUGIN_ARTIFACT = "test-order-maven-plugin"

DEFAULT_REPOS = [
    "commons-collections",
    "commons-io",
    # gson uses JUnit 4 only — test-order does not support JUnit 4, always 0 gain
    "guava",
    "jackson-core",
    "logbook",
    "spring-ai",
    "spring-petclinic",
]

# ─── CSV schema ──────────────────────────────────────────────────────────────

FIELDNAMES = [
    "repo", "patch",
    "n_tests",           # test classes seen in alpha run
    "n_tests_order",     # test classes seen in order run (flags discrepancies)
    "alpha_apfd", "order_apfd",
    "alpha_first_pos", "order_first_pos",
    "alpha_time_pct", "order_time_pct",
    "alpha_total_s", "order_total_s",
    "learn_s",           # wall time of the learn run (0 if index was reused)
    "alpha_wall_s",      # wall time of the alpha run
    "order_wall_s",      # wall time of the order run
    "status",            # ok | error | patch_failed | no_failure
]

# ─── Colour logging ──────────────────────────────────────────────────────────

BLUE   = "\033[0;34m"
GREEN  = "\033[0;32m"
YELLOW = "\033[1;33m"
RED    = "\033[0;31m"
CYAN   = "\033[0;36m"
NC     = "\033[0m"

def _ts() -> str:
    return datetime.now().strftime("%H:%M:%S")

def log(msg: str)     -> None: print(f"{BLUE}[{_ts()}]{NC} {msg}", file=sys.stderr)
def ok(msg: str)      -> None: print(f"{GREEN}  ✓{NC} {msg}", file=sys.stderr)
def warn(msg: str)    -> None: print(f"{YELLOW}  ⚠{NC} {msg}", file=sys.stderr)
def err(msg: str)     -> None: print(f"{RED}  ✗{NC} {msg}", file=sys.stderr)
def section(msg: str) -> None: print(f"\n{CYAN}═══ {msg} ═══{NC}", file=sys.stderr)

# ─── Bash overrides bridge ───────────────────────────────────────────────────

def _bash_fn(fn: str, repo: str) -> str:
    """Call a function from third-party-overrides.sh and return its stdout."""
    if not OVERRIDES.exists():
        return ""
    script = f'source {shlex.quote(str(OVERRIDES))} 2>/dev/null && {fn} {shlex.quote(repo)}'
    try:
        r = subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=10)
        return r.stdout.strip()
    except Exception:
        return ""

def repo_extra_args(repo: str) -> list[str]:
    """Return extra maven args for a repo (bench-specific + general overrides)."""
    parts: list[str] = []
    for fn in ("detect_bench_extra_args", "detect_extra_mvn_args", "detect_compiler_args"):
        out = _bash_fn(fn, repo)
        if out:
            parts.append(out)
    return shlex.split(" ".join(parts)) if parts else []

def repo_module(repo: str) -> str:
    return _bash_fn("detect_module_override", repo)

def repo_package(repo: str) -> str:
    return _bash_fn("detect_package_override", repo)

def repo_java_home(repo: str) -> str:
    return _bash_fn("detect_maven_java_home", repo)

def repo_already_configured(repo: str) -> bool:
    return _bash_fn("detect_plugin_already_configured", repo) == "true"

# ─── extensions.xml injection ────────────────────────────────────────────────

EXT_XML = """\
<?xml version="1.0" encoding="UTF-8"?>
<extensions>
  <extension>
    <groupId>{group}</groupId>
    <artifactId>{artifact}</artifactId>
    <version>{version}</version>
  </extension>
</extensions>
"""

def inject_plugin(repo: str) -> bool:
    """Inject .mvn/extensions.xml. Returns True if we created/modified something."""
    if repo_already_configured(repo):
        ok(f"{repo}: plugin already configured in pom.xml — skipping injection")
        return False
    mvn_dir = THIRD_PARTY / repo / ".mvn"
    ext_file = mvn_dir / "extensions.xml"
    if ext_file.exists() and PLUGIN_ARTIFACT in ext_file.read_text():
        warn(f"extensions.xml already contains {PLUGIN_ARTIFACT}")
        return False
    mvn_dir.mkdir(exist_ok=True)
    if ext_file.exists():
        # Append into existing file via Python xml
        import xml.etree.ElementTree as ET
        bak = ext_file.with_suffix(".xml.bak")
        bak.write_bytes(ext_file.read_bytes())
        ET.register_namespace("", "http://maven.apache.org/EXTENSIONS/1.0.0")
        tree = ET.parse(str(ext_file))
        root = tree.getroot()
        ns = (root.tag.split("}")[0] + "}") if root.tag.startswith("{") else ""
        ext = ET.SubElement(root, f"{ns}extension")
        ET.SubElement(ext, f"{ns}groupId").text   = PLUGIN_GROUP
        ET.SubElement(ext, f"{ns}artifactId").text = PLUGIN_ARTIFACT
        ET.SubElement(ext, f"{ns}version").text    = PLUGIN_VERSION
        ET.indent(root, space="  ")
        tree.write(str(ext_file), xml_declaration=True, encoding="UTF-8")
    else:
        ext_file.write_text(EXT_XML.format(
            group=PLUGIN_GROUP, artifact=PLUGIN_ARTIFACT, version=PLUGIN_VERSION))
    return True

def remove_plugin(repo: str) -> None:
    mvn_dir = THIRD_PARTY / repo / ".mvn"
    ext_file = mvn_dir / "extensions.xml"
    bak = ext_file.with_suffix(".xml.bak")
    if bak.exists():
        bak.rename(ext_file)
    elif ext_file.exists() and PLUGIN_ARTIFACT in ext_file.read_text():
        ext_file.unlink()
        try:
            mvn_dir.rmdir()
        except OSError:
            pass

# ─── Patch helpers ───────────────────────────────────────────────────────────

def patch_applies(repo: str, patch_file: Path) -> bool:
    r = subprocess.run(
        ["patch", "-d", str(THIRD_PARTY / repo), "-p1",
         "--dry-run", "--silent", "--no-backup-if-mismatch"],
        input=patch_file.read_bytes(), capture_output=True,
    )
    return r.returncode == 0

def apply_patch(repo: str, patch_file: Path) -> None:
    subprocess.run(
        ["patch", "-d", str(THIRD_PARTY / repo), "-p1", "--no-backup-if-mismatch"],
        input=patch_file.read_bytes(), capture_output=True, check=True,
    )

def revert_patch(repo: str, patch_file: Path) -> None:
    subprocess.run(
        ["patch", "-d", str(THIRD_PARTY / repo), "-p1",
         "-R", "--no-backup-if-mismatch", "--forward", "-s"],
        input=patch_file.read_bytes(), capture_output=True,
    )
    for rej in (THIRD_PARTY / repo).rglob("*.rej"):
        rej.unlink(missing_ok=True)

def changed_class(patch_file: Path) -> str:
    """Extract the Java FQCN of the changed file from the patch."""
    for line in patch_file.read_text(errors="replace").splitlines():
        if line.startswith("+++ b/"):
            path = line[6:]
            # strip everything up to and including .../java/
            if "/java/" in path:
                path = path.split("/java/", 1)[1]
            return path.removesuffix(".java").replace("/", ".")
    return ""

def find_index(repo: str) -> Optional[Path]:
    """Return path of existing test-dependencies.lz4 index, excluding precheck dirs."""
    for p in (THIRD_PARTY / repo).rglob("test-dependencies.lz4"):
        if "precheck" not in str(p):
            return p
    return None

# ─── Maven runner ────────────────────────────────────────────────────────────

_TRANSIENT_PATTERNS = [
    re.compile(r"Could not transfer artifact"),
    re.compile(r"Connection reset"),
    re.compile(r"UnknownHostException"),
    re.compile(r"SocketTimeoutException"),
    re.compile(r"Could not resolve dependencies"),
]
_MVN_RETRIES = 2  # retry up to this many times on transient failures
_MVN_TIMEOUT = 30 * 60  # default per-run timeout in seconds (30 min)

def _looks_transient(log_file: Path) -> bool:
    """Return True if the maven log suggests a transient network failure."""
    try:
        text = log_file.read_text(errors="replace")
        return any(p.search(text) for p in _TRANSIENT_PATTERNS)
    except FileNotFoundError:
        return False

def run_mvn(
    repo: str,
    extra_args: list,
    log_file: Path,
    env: Optional[dict] = None,
    timeout: int = _MVN_TIMEOUT,
) -> tuple:
    """
    Run the maven command in the repo dir, retrying on transient network errors.
    Returns (returncode, wall_seconds).
    stdout goes to log_file; wall time covers all attempts combined.
    Raises subprocess.TimeoutExpired if the run exceeds `timeout` seconds.
    """
    cmd = ["mvn"] + extra_args
    log_file.parent.mkdir(parents=True, exist_ok=True)
    proc_env = os.environ.copy()
    if env:
        proc_env.update(env)
    t0 = time.monotonic()
    for attempt in range(1, _MVN_RETRIES + 2):
        with open(log_file, "w") as lf:
            proc = subprocess.run(
                cmd,
                cwd=str(THIRD_PARTY / repo),
                stdout=lf,
                stderr=subprocess.PIPE,
                env=proc_env,
                timeout=timeout,
            )
        if proc.returncode == 0 or not _looks_transient(log_file):
            break
        if attempt <= _MVN_RETRIES:
            warn(f"  Transient failure detected — retrying (attempt {attempt+1}/{_MVN_RETRIES+1})")
    wall = time.monotonic() - t0
    return proc.returncode, wall

# ─── Surefire log parser ─────────────────────────────────────────────────────

_RUN_RE   = re.compile(r"Running\s+(\S+)")
_TESTS_RE = re.compile(
    r"Tests run:\s*(\d+),\s*Failures:\s*(\d+),\s*Errors:\s*(\d+)"
    r"[^\n]*Time elapsed:\s*([\d.]+)\s*s(?:\s*(?:<<< FAILURE!)?\s*--\s*in\s+(\S+))?"
)

def parse_surefire_log(log_file: Path) -> list[dict]:
    """
    Return list of {cls, fail, dur} dicts in execution order, one entry per
    outer class.  Inner/nested classes (Outer$Inner) are folded into their
    outer class: fail is OR'd, durations are summed, and the outer class
    keeps the position of its first appearance.

    JUnit 5 @Nested classes are run under their outer class context: Surefire
    prints "Running OuterTest" then "Running AbstractBase$Nested" then
    "Tests run: N ... -- in AbstractBase$Nested" (possibly with failures) and
    finally "Tests run: 0 ... -- in OuterTest".  We attribute nested-class
    results to the outermost active outer class context, not to the abstract
    base that defines the nested class.
    """
    raw = []
    outer_ctx: Optional[str] = None  # last seen non-nested Running class
    try:
        with open(log_file, errors="replace") as f:
            for line in f:
                m = _RUN_RE.search(line)
                if m:
                    cls_name = m.group(1)
                    if "$" not in cls_name:
                        outer_ctx = cls_name
                    continue
                m = _TESTS_RE.search(line)
                if m:
                    failed   = int(m.group(2)) + int(m.group(3))
                    duration = float(m.group(4))
                    in_cls   = m.group(5)  # class name from "-- in ..." suffix
                    if in_cls:
                        outer = in_cls.split("$")[0]
                        # If the nested class belongs to a different base than
                        # the current outer_ctx (e.g. AbstractBase$Nested running
                        # under OuterTest context), attribute to outer_ctx.
                        if outer_ctx and outer != outer_ctx and "$" in in_cls:
                            outer = outer_ctx
                    else:
                        outer = outer_ctx
                    if outer:
                        raw.append({"cls": outer, "fail": int(failed > 0), "dur": duration})
    except FileNotFoundError:
        pass

    # Collapse any remaining Outer$Inner refs into their outer class, preserving
    # first-appearance order.
    seen: dict = {}
    for r in raw:
        outer = r["cls"].split("$")[0]
        if outer not in seen:
            seen[outer] = {"cls": outer, "fail": 0, "dur": 0.0}
        seen[outer]["fail"] |= r["fail"]
        seen[outer]["dur"]  += r["dur"]
    return list(seen.values())

# ─── Metrics ─────────────────────────────────────────────────────────────────

def _nan(v) -> str:
    return f"{v:.4f}" if v == v else "nan"

def _nanpct(v) -> str:
    return f"{v:.1f}" if v == v else "nan"

def compute_metrics(rows: list[dict]) -> dict:
    n = len(rows)
    m = sum(r["fail"] for r in rows)
    total_time = sum(r["dur"] for r in rows)

    if m == 0 or n == 0:
        apfd = float("nan")
    else:
        pos_sum = sum(i + 1 for i, r in enumerate(rows) if r["fail"])
        apfd = 1.0 - pos_sum / (n * m) + 1.0 / (2 * n)

    first_pos = next((i + 1 for i, r in enumerate(rows) if r["fail"]), None)
    if first_pos is not None and total_time > 0:
        time_to_first = sum(r["dur"] for r in rows[:first_pos])
        time_pct = time_to_first / total_time * 100
    else:
        time_pct = float("nan")

    return {
        "apfd":      apfd,
        "first_pos": first_pos,
        "time_pct":  time_pct,
        "n":         n,
        "total_s":   total_time,
    }

# ─── CSV writer (continuous) ─────────────────────────────────────────────────

class CsvWriter:
    def __init__(self, path: Path) -> None:
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        append = path.exists() and path.stat().st_size > 0
        self._f = open(path, "a" if append else "w", newline="", buffering=1)
        self._w = csv.DictWriter(self._f, fieldnames=FIELDNAMES)
        if not append:
            self._w.writeheader()
        self._f.flush()

    def write(self, row: dict) -> None:
        # Fill missing keys with empty string
        full = {k: row.get(k, "") for k in FIELDNAMES}
        self._w.writerow(full)
        self._f.flush()

    def close(self) -> None:
        self._f.close()

# ─── Per-repo setup ──────────────────────────────────────────────────────────

def build_base_args(repo: str) -> tuple[list[str], dict]:
    """Return (maven_args, env_updates)."""
    module  = repo_module(repo)
    package = repo_package(repo)
    extra   = repo_extra_args(repo)
    java_home = repo_java_home(repo)

    base = [
        "-B",
        "-Denforcer.skip=true",
        "-Drat.skip=true",
        "-Djacoco.skip=true",
        "-Dmaven.build.cache.enabled=false",
    ]
    base.extend(extra)
    if package:
        base.append(f"-Dtestorder.includePackages={package}")
    if module and module != "NONE":
        base.extend(["-pl", module, "-am"])

    env: dict = {}
    if java_home:
        env["JAVA_HOME"] = java_home
        env["PATH"]      = java_home + "/bin:" + os.environ.get("PATH", "")

    return base, env

# ─── Learn step (once per repo) ──────────────────────────────────────────────

def ensure_learn_index(
    repo: str,
    base_args: list[str],
    env: dict,
    trial_dir: Path,
    timeout: int = _MVN_TIMEOUT,
) -> float:
    """
    Make sure a learn index exists. Returns learn wall-time (0.0 if reused).
    """
    if find_index(repo):
        ok(f"  [learn] existing index reused for {repo}")
        return 0.0

    log(f"  [learn] no index — building from clean state")
    learn_log = trial_dir / "learn.log"
    try:
        _, wall = run_mvn(
            repo,
            ["clean", "test", "-Dtestorder.mode=learn"] + base_args,
            learn_log,
            env=env,
            timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        warn(f"  [learn] timed out after {timeout}s — order runs will fall back to alphabetical")
        return 0.0
    if find_index(repo):
        ok(f"  [learn] index built in {wall:.1f}s")
    else:
        warn(f"  [learn] finished in {wall:.1f}s but no index found — order run may fall back")
    return wall

# ─── Single trial ────────────────────────────────────────────────────────────

def run_trial(
    repo: str,
    patch_file: Path,
    base_args: list[str],
    env: dict,
    trial_dir: Path,
    learn_s: float,
    writer: CsvWriter,
    patch_name: str,
    timeout: int = _MVN_TIMEOUT,
) -> None:
    """Run one patch trial and write a CSV row immediately on completion."""
    trial_dir.mkdir(parents=True, exist_ok=True)

    def error_row(status: str) -> None:
        writer.write({"repo": repo, "patch": patch_name, "status": status})

    # ── 1. Verify + apply patch ───────────────────────────────────────────────
    if not patch_applies(repo, patch_file):
        warn(f"  Patch {patch_name} does not apply cleanly — skipping")
        error_row("patch_failed")
        return

    log(f"  [patch] applying {patch_name}")
    apply_patch(repo, patch_file)

    def pre_clean(repo: str) -> None:
        """Force-delete target/ before mvn clean to avoid macOS file-lock races
        where the previous build's JVM briefly holds compiled classes or surefire
        reports open, causing maven-clean-plugin to fail."""
        target = THIRD_PARTY / repo / "target"
        for attempt in range(5):
            if not target.is_dir():
                break
            shutil.rmtree(target, ignore_errors=True)
            if not target.is_dir():
                break
            time.sleep(0.5 * (attempt + 1))

    try:
        # ── 2. Alphabetical baseline ──────────────────────────────────────────
        pre_clean(repo)
        log(f"  [alpha] running baseline...")
        alpha_log = trial_dir / "alpha.log"
        try:
            _, alpha_wall = run_mvn(
                repo,
                ["clean", "test", "-Dtestorder.mode=skip"] + base_args,
                alpha_log,
                env=env,
                timeout=timeout,
            )
        except subprocess.TimeoutExpired:
            warn(f"  [alpha] timed out after {timeout}s — skipping patch")
            error_row("timeout")
            return

        # ── 3. test-order run ─────────────────────────────────────────────────
        pre_clean(repo)
        log(f"  [order] running with test-order...")
        order_log = trial_dir / "order.log"
        try:
            _, order_wall = run_mvn(
                repo,
                ["clean", "test", "-Dtestorder.mode=auto"] + base_args,
                order_log,
                env=env,
                timeout=timeout,
            )
        except subprocess.TimeoutExpired:
            warn(f"  [order] timed out after {timeout}s — recording partial")
            error_row("timeout")
            return
    finally:
        # ── 4. Restore bug ────────────────────────────────────────────────────
        revert_patch(repo, patch_file)

    # ── 5. Parse and compute ──────────────────────────────────────────────────
    alpha_rows = parse_surefire_log(alpha_log)
    order_rows = parse_surefire_log(order_log)

    if not alpha_rows or not order_rows:
        warn(f"  No parseable test results for {repo}/{patch_name}")
        error_row("error")
        return

    # Save parsed results for manual inspection
    _save_parsed(alpha_rows, trial_dir / "alpha-parsed.tsv")
    _save_parsed(order_rows, trial_dir / "order-parsed.tsv")

    a = compute_metrics(alpha_rows)
    o = compute_metrics(order_rows)

    if a["n"] != o["n"]:
        warn(f"  n_tests mismatch: alpha={a['n']} order={o['n']} — recorded in n_tests_order")

    if a["first_pos"] is None:
        warn(f"  Alpha run had NO failures — patch may not trigger a test; recording as no_failure")
        writer.write({"repo": repo, "patch": patch_name,
                      "n_tests": a["n"], "n_tests_order": o["n"],
                      "alpha_total_s": f"{a['total_s']:.1f}",
                      "order_total_s": f"{o['total_s']:.1f}",
                      "learn_s": f"{learn_s:.1f}",
                      "alpha_wall_s": f"{alpha_wall:.1f}",
                      "order_wall_s": f"{order_wall:.1f}",
                      "status": "no_failure"})
        return

    row = {
        "repo":           repo,
        "patch":          patch_name,
        "n_tests":        a["n"],
        "n_tests_order":  o["n"],
        "alpha_apfd":     _nan(a["apfd"]),
        "order_apfd":     _nan(o["apfd"]),
        "alpha_first_pos": a["first_pos"] if a["first_pos"] is not None else "none",
        "order_first_pos": o["first_pos"] if o["first_pos"] is not None else "none",
        "alpha_time_pct": _nanpct(a["time_pct"]),
        "order_time_pct": _nanpct(o["time_pct"]),
        "alpha_total_s":  f"{a['total_s']:.1f}",
        "order_total_s":  f"{o['total_s']:.1f}",
        "learn_s":        f"{learn_s:.1f}",
        "alpha_wall_s":   f"{alpha_wall:.1f}",
        "order_wall_s":   f"{order_wall:.1f}",
        "status":         "ok",
    }
    writer.write(row)

    ok(
        f"  alpha={a['apfd']:.3f} (pos={a['first_pos']}, time={_nanpct(a['time_pct'])}%)"
        f"  →  order={o['apfd']:.3f} (pos={o['first_pos']}, time={_nanpct(o['time_pct'])}%)"
        f"  learn={learn_s:.0f}s  [n={a['n']}]"
    )

def _save_parsed(rows: list[dict], path: Path) -> None:
    with open(path, "w") as f:
        for r in rows:
            f.write(f"{r['cls']}\t{r['fail']}\t{r['dur']:.3f}\n")

# ─── Per-repo orchestration ──────────────────────────────────────────────────

def bench_repo(repo: str, writer: CsvWriter, out_dir: Path, only_patch: Optional[str] = None, timeout: int = _MVN_TIMEOUT, done_patches: set[str] | None = None) -> None:
    section(f"BENCH: {repo}")

    repo_dir = THIRD_PARTY / repo
    if not repo_dir.is_dir():
        warn(f"{repo} not found in {THIRD_PARTY} — skipping")
        return

    bugs_dir = BUGS_DIR / repo
    if not bugs_dir.is_dir():
        warn(f"No bug patches for {repo} — skipping")
        return

    # Collect patches in order
    patches: list[Path] = []
    bugs_txt = bugs_dir / "bugs.txt"
    if bugs_txt.exists():
        for line in bugs_txt.read_text().splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            pf = bugs_dir / line
            if pf.exists():
                patches.append(pf)
    else:
        patches = sorted(bugs_dir.glob("*.patch"))

    if only_patch:
        patches = [p for p in patches if only_patch in p.stem]
        if not patches:
            warn(f"No patches matching '{only_patch}' for {repo}")
            return

    if not patches:
        warn(f"No patches found for {repo}")
        return

    base_args, env = build_base_args(repo)
    inject_plugin(repo)

    try:
        run_dir = out_dir / repo
        run_dir.mkdir(parents=True, exist_ok=True)

        # Build learn index once for the whole repo (first trial's dir is good enough)
        first_trial_dir = run_dir / f"patch-0-learn"
        learn_s = ensure_learn_index(repo, base_args, env, first_trial_dir, timeout=timeout)

        total = len(patches)
        for idx, pf in enumerate(patches, 1):
            patch_name = pf.stem
            if done_patches and patch_name in done_patches:
                log(f"Patch {idx}/{total}: {patch_name} — already done, skipping")
                continue
            trial_dir  = run_dir / f"patch-{idx:02d}-{patch_name}"
            log(f"Patch {idx}/{total}: {patch_name}")
            run_trial(repo, pf, base_args, env, trial_dir, learn_s, writer, patch_name, timeout=timeout)
            # After first trial the index is already built; mark learn_s=0 for subsequent rows
            learn_s = 0.0
    finally:
        remove_plugin(repo)

# ─── Summary ─────────────────────────────────────────────────────────────────

def _f_float(s) -> float | None:
    try:
        v = float(s)
        return None if math.isnan(v) else v
    except (TypeError, ValueError):
        return None

def mean(xs):
    return sum(xs) / len(xs) if xs else None

def stderr_m(xs):
    if len(xs) < 2:
        return 0.0
    m = mean(xs)
    sd = math.sqrt(sum((x - m) ** 2 for x in xs) / (len(xs) - 1))
    return sd / math.sqrt(len(xs))

def print_summary(csv_path: Path) -> None:
    rows = []
    with open(csv_path, newline="") as f:
        for r in csv.DictReader(f):
            rows.append(r)

    valid = [r for r in rows if r.get("status") == "ok"]
    if not valid:
        warn("No successful trials to summarise")
        return

    by_repo = defaultdict(list)
    for r in valid:
        by_repo[r["repo"]].append(r)

    W = 120
    print("\n" + "─" * W)
    print(
        f"{'Repo':<26} {'#':>3}  "
        f"{'Alpha time%':>11} {'±':>5}  "
        f"{'Order time%':>11} {'±':>5}  "
        f"{'Alpha APFD':>10}  "
        f"{'Order APFD':>10}  "
        f"{'Learn(s)':>8}  "
        f"{'Suite(s)':>8}"
    )
    print("─" * W)

    all_at, all_ot, all_oa = [], [], []
    for repo in sorted(by_repo):
        rrows = by_repo[repo]
        at = [v for r in rrows if (v := _f_float(r["alpha_time_pct"])) is not None]
        ot = [v for r in rrows if (v := _f_float(r["order_time_pct"])) is not None]
        aa = [v for r in rrows if (v := _f_float(r["alpha_apfd"])) is not None]
        oa = [v for r in rrows if (v := _f_float(r["order_apfd"])) is not None]
        ls = [v for r in rrows if (v := _f_float(r["learn_s"])) is not None and v > 0]
        ts = [v for r in rrows if (v := _f_float(r["alpha_total_s"])) is not None]
        all_at.extend(at); all_ot.extend(ot); all_oa.extend(oa)

        at_s  = f"{mean(at):>10.1f}%" if at  else f"{'n/a':>11}"
        at_se = f"±{stderr_m(at):.1f}"  if at  else ""
        ot_s  = f"{mean(ot):>10.1f}%" if ot  else f"{'n/a':>11}"
        ot_se = f"±{stderr_m(ot):.1f}"  if ot  else ""
        aa_s  = f"{mean(aa)*100:>9.1f}%" if aa else f"{'n/a':>10}"
        oa_s  = f"{mean(oa)*100:>9.1f}%" if oa else f"{'n/a':>10}"
        ls_s  = f"{mean(ls):>7.0f}s"   if ls  else f"{'reused':>8}"
        ts_s  = f"{mean(ts):>7.1f}s"   if ts  else f"{'n/a':>8}"

        print(
            f"{repo:<26} {len(rrows):>3}  "
            f"{at_s} {at_se:>5}  "
            f"{ot_s} {ot_se:>5}  "
            f"{aa_s}  {oa_s}  {ls_s}  {ts_s}"
        )

    print("─" * W)
    n_v = len(valid)
    if all_ot:
        at_s = f"{mean(all_at):>10.1f}%" if all_at else f"{'n/a':>11}"
        oa_s = f"{mean(all_oa)*100:>9.1f}%" if all_oa else f"{'n/a':>10}"
        print(
            f"{'OVERALL':<26} {n_v:>3}  "
            f"{at_s} {'':>5}  "
            f"{mean(all_ot):>10.1f}% {'':>5}  "
            f"{'':>10}  {oa_s}"
        )

def write_chart_json(csv_path: Path, out_dir: Path) -> Path:
    rows = []
    with open(csv_path, newline="") as f:
        for r in csv.DictReader(f):
            rows.append(r)

    valid = [r for r in rows if r.get("status") == "ok"]
    by_repo = defaultdict(list)
    for r in valid:
        by_repo[r["repo"]].append(r)

    chart = []
    for repo in sorted(by_repo):
        rrows = by_repo[repo]
        at = [v for r in rrows if (v := _f_float(r["alpha_time_pct"])) is not None]
        ot = [v for r in rrows if (v := _f_float(r["order_time_pct"])) is not None]
        aa = [v for r in rrows if (v := _f_float(r["alpha_apfd"])) is not None]
        oa = [v for r in rrows if (v := _f_float(r["order_apfd"])) is not None]
        ls = [v for r in rrows if (v := _f_float(r["learn_s"])) is not None and v > 0]
        ts = [v for r in rrows if (v := _f_float(r["alpha_total_s"])) is not None]
        chart.append({
            "repo":                repo,
            "n_tests":             _f_float(rrows[0].get("n_tests")) if rrows else None,
            "n_patches":           len(rrows),
            "alpha_time_pct_mean": round(mean(at), 2)      if at else None,
            "alpha_time_pct_se":   round(stderr_m(at), 2)  if at else None,
            "order_time_pct_mean": round(mean(ot), 2)      if ot else None,
            "order_time_pct_se":   round(stderr_m(ot), 2)  if ot else None,
            "alpha_apfd_mean":     round(mean(aa) * 100, 2) if aa else None,
            "alpha_apfd_se":       round(stderr_m(aa) * 100, 2) if aa else None,
            "order_apfd_mean":     round(mean(oa) * 100, 2) if oa else None,
            "order_apfd_se":       round(stderr_m(oa) * 100, 2) if oa else None,
            "learn_s_mean":        round(mean(ls), 1)       if ls else None,
            "alpha_total_s_mean":  round(mean(ts), 1)       if ts else None,
        })

    path = out_dir / "chart.json"
    path.write_text(json.dumps(chart, indent=2))
    return path

# ─── Main ─────────────────────────────────────────────────────────────────────

def main() -> None:
    ap = argparse.ArgumentParser(description="APFD benchmark for test-order")
    ap.add_argument("repos", nargs="*", help="Repos to benchmark (default: all)")
    ap.add_argument("--no-install", action="store_true",
                    help="Skip 'mvn install -DskipTests' of the plugin")
    ap.add_argument("--patch", metavar="SUBSTRING",
                    help="Only run patches whose name contains SUBSTRING")
    ap.add_argument("--append", metavar="RUN_DIR",
                    help="Append results to an existing run directory (e.g. 20261008_223724)")
    ap.add_argument("--timeout", metavar="SECONDS", type=int, default=_MVN_TIMEOUT,
                    help=f"Per-run Maven timeout in seconds (default: {_MVN_TIMEOUT}). "
                         "Timed-out trials are recorded with status=timeout.")
    args = ap.parse_args()

    repos = args.repos if args.repos else DEFAULT_REPOS
    if args.append:
        out_dir = RESULTS_DIR / args.append
        if not out_dir.is_dir():
            err(f"Run directory not found: {out_dir}")
            sys.exit(1)
    else:
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        out_dir = RESULTS_DIR / timestamp
        out_dir.mkdir(parents=True, exist_ok=True)

    csv_path = out_dir / "results.csv"
    writer = CsvWriter(csv_path)

    # When appending, collect already-completed (repo, patch) pairs to skip
    done_patches_by_repo: dict[str, set[str]] = defaultdict(set)
    if args.append and csv_path.exists():
        with open(csv_path, newline="") as f:
            for row in csv.DictReader(f):
                if row.get("status") in ("ok", "no_failure", "patch_failed"):
                    done_patches_by_repo[row["repo"]].add(row["patch"])

    # Install latest plugin build
    if not args.no_install:
        section("Installing test-order plugin")
        r = subprocess.run(
            ["mvn", "install", "-DskipTests", "-q"],
            cwd=str(ROOT_DIR),
        )
        if r.returncode != 0:
            err("Plugin install failed — aborting")
            sys.exit(1)

    t_total = time.monotonic()
    for repo in repos:
        bench_repo(repo, writer, out_dir, only_patch=args.patch, timeout=args.timeout,
                   done_patches=done_patches_by_repo.get(repo))
    elapsed = time.monotonic() - t_total

    writer.close()

    section("RESULTS")
    print_summary(csv_path)
    chart_path = write_chart_json(csv_path, out_dir)

    print()
    log(f"Finished in {elapsed/60:.1f} min")
    log(f"Results CSV: {csv_path}")
    log(f"Chart JSON:  {chart_path}")

if __name__ == "__main__":
    main()
