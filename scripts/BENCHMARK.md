# APFD Benchmark

Measures how much earlier test-order's reordering finds failures compared to alphabetical order, using **APFD** (Average Percentage of Faults Detected).

## Quick start

```bash
# Run the full benchmark (all repos, all patches)
python3 scripts/bench_apfd.py

# Run for a single repo only
python3 scripts/bench_apfd.py commons-io

# Skip the mvn install step if artifacts are already built
python3 scripts/bench_apfd.py --no-install

# Summarise results after the run
python3 scripts/bench_summarise.py target/bench-apfd/<timestamp>/results.csv
```

## How it works

For each `repo × bug-patch` pair:

1. **Learn** — if no index exists, run `mvn test -Dtestorder.mode=learn` and record wall time.
2. **Alpha** — run `mvn test -Dtestorder.mode=skip` (alphabetical order) and record per-test pass/fail and timing.
3. **Order** — run `mvn test -Dtestorder.mode=auto` (test-order reordering) and record the same.
4. Compute APFD, first-failure position, and time-to-first-failure percentage for both runs.

Results are written to `target/bench-apfd/<timestamp>/results.csv` **after every individual trial** so no progress is lost if the run is interrupted.

## Output columns (results.csv)

| Column | Meaning |
|--------|---------|
| `repo` | Third-party project name |
| `patch` | Bug patch filename |
| `n_tests` / `n_tests_order` | Test-class count (flags discrepancies between runs) |
| `alpha_apfd` / `order_apfd` | APFD score (0–1, higher = earlier detection) |
| `alpha_first_pos` / `order_first_pos` | 1-based position of first failure |
| `alpha_time_pct` / `order_time_pct` | Wall-clock fraction elapsed at first failure |
| `alpha_total_s` / `order_total_s` | Cumulative test duration (sum of reported times) |
| `learn_s` | Learn run wall time (0 if index was reused) |
| `alpha_wall_s` / `order_wall_s` | Actual wall time per run |
| `status` | `ok`, `error`, `patch_failed`, or `no_failure` |

## Patch naming convention

```
scripts/bugs/<repo>/
  bugs.txt                          # ordered list of patches to run
  synthetic-<name>.patch            # artificially injected condition flips / off-by-ones
  real-<name>.patch                 # patches sourced from actual bugs in project history
```

`bugs.txt` controls which patches are exercised and in what order.  List one filename per line; blank lines and `#` comments are ignored.

## Repos covered

77 repos under `scripts/bugs/`.  The default benchmark set (used when no repo argument is given) is:

```
commons-collections, commons-io, gson, guava, jackson-core,
logbook, spring-ai, spring-petclinic
```

## Summarise / chart output

`bench_summarise.py` accepts the CSV (or the old TSV format) and produces:

- **stdout** — human-readable per-repo summary table
- **`chart.json`** — per-repo aggregates with error bars, ready to feed into `BenchmarkChart.vue`

```bash
python3 scripts/bench_summarise.py results.csv out/
# → out/chart.json
```

## Repo-specific overrides

`scripts/third-party-overrides.sh` contains per-repo Maven/Gradle argument overrides used by the benchmark:

- `detect_learn_extra_args` — extra args for the learn run
- `detect_bench_extra_args` — extra args applied to all three runs (alpha/learn/order)

Add overrides there when a repo has pre-existing test failures or unusual build requirements.
