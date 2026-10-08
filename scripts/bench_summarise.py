#!/usr/bin/env python3
"""
bench_summarise.py  —  post-process bench_apfd results

Accepts both the new CSV format (bench_apfd.py) and the old TSV format
(bench_apfd.sh).  Auto-detects by file extension and delimiter.

Usage:
    python3 bench_summarise.py <results.csv|results.tsv> [out_dir]

Writes:
    <out_dir>/chart.json   — per-repo aggregates + error bars for BenchmarkChart.vue
    prints human-readable summary table to stdout
"""
import sys, csv, json, math, collections
from pathlib import Path


# ── helpers ───────────────────────────────────────────────────────────────────

def _f(s):
    try:
        v = float(s)
        return None if math.isnan(v) else v
    except (TypeError, ValueError):
        return None


def mean(xs):
    return sum(xs) / len(xs) if xs else None


def stderr(xs):
    if len(xs) < 2:
        return 0.0
    m = mean(xs)
    sd = math.sqrt(sum((x - m) ** 2 for x in xs) / (len(xs) - 1))
    return sd / math.sqrt(len(xs))


# ── load ──────────────────────────────────────────────────────────────────────

def load(path: Path) -> list:
    # Auto-detect delimiter
    delim = '\t' if path.suffix == '.tsv' else ','
    rows = []
    with open(path, newline='') as f:
        for r in csv.DictReader(f, delimiter=delim):
            if r.get('status', 'ok') not in ('ok', ''):
                continue
            try:
                rows.append({
                    'repo':            r['repo'],
                    'patch':           r['patch'],
                    'n_tests':         int(r['n_tests']) if r.get('n_tests', '').isdigit() else None,
                    'alpha_apfd':      _f(r.get('alpha_apfd')),
                    'order_apfd':      _f(r.get('order_apfd')),
                    'alpha_first_pos': int(r['alpha_first_pos']) if r.get('alpha_first_pos', '').isdigit() else None,
                    'order_first_pos': int(r['order_first_pos']) if r.get('order_first_pos', '').isdigit() else None,
                    'alpha_time_pct':  _f(r.get('alpha_time_pct')),
                    'order_time_pct':  _f(r.get('order_time_pct')),
                    'alpha_total_s':   _f(r.get('alpha_total_s')),
                    'order_total_s':   _f(r.get('order_total_s')),
                    'learn_s':         _f(r.get('learn_s')),
                    'alpha_wall_s':    _f(r.get('alpha_wall_s')),
                    'order_wall_s':    _f(r.get('order_wall_s')),
                })
            except (ValueError, KeyError):
                continue
    return rows


# ── aggregate ─────────────────────────────────────────────────────────────────

def aggregate(rows):
    by_repo = collections.defaultdict(list)
    for r in rows:
        by_repo[r['repo']].append(r)

    agg = {}
    for repo, rrows in sorted(by_repo.items()):
        valid = [r for r in rrows if r['order_time_pct'] is not None]
        if not valid:
            continue

        at = [r['alpha_time_pct'] for r in valid if r['alpha_time_pct'] is not None]
        ot = [r['order_time_pct'] for r in valid]
        aa = [r['alpha_apfd']     for r in valid if r['alpha_apfd']     is not None]
        oa = [r['order_apfd']     for r in valid if r['order_apfd']     is not None]
        ts = [r['alpha_total_s']  for r in valid if r['alpha_total_s']  is not None]
        ls = [r['learn_s']        for r in valid if r['learn_s'] is not None and r['learn_s'] > 0]

        agg[repo] = {
            'repo':                repo,
            'n_tests':             valid[0]['n_tests'],
            'n_patches':           len(valid),
            'alpha_time_pct_mean': round(mean(at), 2)       if at else None,
            'alpha_time_pct_se':   round(stderr(at), 2)     if at else None,
            'order_time_pct_mean': round(mean(ot), 2),
            'order_time_pct_se':   round(stderr(ot), 2),
            'alpha_apfd_mean':     round(mean(aa) * 100, 2) if aa else None,
            'alpha_apfd_se':       round(stderr(aa) * 100, 2) if aa else None,
            'order_apfd_mean':     round(mean(oa) * 100, 2) if oa else None,
            'order_apfd_se':       round(stderr(oa) * 100, 2) if oa else None,
            'learn_s_mean':        round(mean(ls), 1)        if ls else None,
            'alpha_total_s_mean':  round(mean(ts), 1)        if ts else None,
        }
    return agg


# ── print summary ─────────────────────────────────────────────────────────────

def print_summary(agg, rows):
    W = 120
    print("\n" + "─" * W)
    print(f"{'Repo':<26} {'#':>3}  "
          f"{'Alpha time%':>11} {'±':>5}  "
          f"{'Order time%':>11} {'±':>5}  "
          f"{'Alpha APFD':>10}  "
          f"{'Order APFD':>10}  "
          f"{'Learn(s)':>8}  "
          f"{'Suite(s)':>8}")
    print("─" * W)
    for repo, d in sorted(agg.items()):
        at_s  = f"{d['alpha_time_pct_mean']:>10.1f}%" if d['alpha_time_pct_mean'] is not None else f"{'n/a':>11}"
        at_se = f"±{d['alpha_time_pct_se']:.1f}"      if d['alpha_time_pct_se']  is not None else ""
        ot_s  = f"{d['order_time_pct_mean']:>10.1f}%"
        ot_se = f"±{d['order_time_pct_se']:.1f}"
        aa_s  = f"{d['alpha_apfd_mean']:>9.1f}%"      if d['alpha_apfd_mean']    is not None else f"{'n/a':>10}"
        oa_s  = f"{d['order_apfd_mean']:>9.1f}%"      if d['order_apfd_mean']    is not None else f"{'n/a':>10}"
        ls_s  = f"{d['learn_s_mean']:>7.0f}s"         if d['learn_s_mean']       is not None else f"{'reused':>8}"
        ts_s  = f"{d['alpha_total_s_mean']:>7.1f}s"   if d['alpha_total_s_mean'] is not None else f"{'n/a':>8}"
        print(f"{repo:<26} {d['n_patches']:>3}  "
              f"{at_s} {at_se:>5}  "
              f"{ot_s} {ot_se:>5}  "
              f"{aa_s}  {oa_s}  {ls_s}  {ts_s}")
    print("─" * W)

    all_at = [r['alpha_time_pct'] for r in rows if r['alpha_time_pct'] is not None]
    all_ot = [r['order_time_pct'] for r in rows if r['order_time_pct'] is not None]
    all_oa = [r['order_apfd']     for r in rows if r['order_apfd']     is not None]
    n_v    = len(all_ot)
    if all_ot:
        at_s = f"{mean(all_at):>10.1f}%" if all_at else f"{'n/a':>11}"
        oa_s = f"{mean(all_oa)*100:>9.1f}%" if all_oa else f"{'n/a':>10}"
        print(f"{'OVERALL':<26} {n_v:>3}  {at_s} {'':>5}  {mean(all_ot):>10.1f}% {'':>5}  "
              f"{'':>10}  {oa_s}")


# ── write chart.json ──────────────────────────────────────────────────────────

def write_chart_json(agg, out_dir: Path) -> Path:
    path = out_dir / 'chart.json'
    path.write_text(json.dumps(list(agg.values()), indent=2))
    return path


# ── main ──────────────────────────────────────────────────────────────────────

def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <results.csv|results.tsv> [out_dir]", file=sys.stderr)
        sys.exit(1)

    in_path  = Path(sys.argv[1])
    out_dir  = Path(sys.argv[2]) if len(sys.argv) > 2 else in_path.parent
    out_dir.mkdir(parents=True, exist_ok=True)

    rows = load(in_path)
    if not rows:
        print("No valid rows found.", file=sys.stderr)
        sys.exit(1)

    agg        = aggregate(rows)
    chart_path = write_chart_json(agg, out_dir)

    print_summary(agg, rows)
    print(f"\nChart JSON → {chart_path}")


if __name__ == '__main__':
    main()
