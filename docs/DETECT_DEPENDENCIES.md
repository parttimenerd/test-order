# Detect-Dependencies: Order-Dependent Test Detection

The `detect-dependencies` goal finds **order-dependent (OD) tests** — tests whose pass/fail outcome changes depending on which other tests run before them. It uses smart test reordering strategies informed by academic research to expose these bugs, then pinpoints the exact dependency via binary search.

## Usage

```bash
mvn test-order:detect-dependencies
```

> **Prerequisite:** For full detection capability (order-control algorithms like
> reverse, random, tuscan, etc.), add `test-order-junit` as an explicit test
> dependency. Without it, only the exclusion-probe strategy is available.
>
> ```xml
> <dependency>
>     <groupId>me.bechberger</groupId>
>     <artifactId>test-order-junit</artifactId>
>     <version>0.0.1-SNAPSHOT</version>
>     <scope>test</scope>
> </dependency>
> ```

---

## Configuration

All parameters can be set via `-D` on the command line or in `<configuration>` in the POM.

<details>
<summary><strong>Maven Plugin Configuration (pom.xml)</strong></summary>

```xml
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>test-order-maven-plugin</artifactId>
  <version>0.0.1-SNAPSHOT</version>
  <configuration>
    <!-- Detection algorithm (default: combined) -->
    <algorithm>combined</algorithm>
    <!-- Time budget in seconds; 0 = unlimited (default: 300) -->
    <timeBudget>300</timeBudget>
    <!-- Stop after finding the first OD pair (default: false) -->
    <stopOnFirst>false</stopOnFirst>
    <!-- Random seed for reproducibility (default: 42) -->
    <randomSeed>42</randomSeed>
    <!-- Fail the build if OD bugs are detected (default: false) -->
    <failOnDetection>false</failOnDetection>
  </configuration>
</plugin>
```

</details>

<details>
<summary><strong>Command-Line Properties</strong></summary>

| Property | Default | Description |
|----------|---------|-------------|
| `testorder.detect.algorithm` | `combined` | Detection algorithm to use |
| `testorder.detect.timeBudget` | `300` | Time budget in seconds (0 = unlimited) |
| `testorder.detect.stopOnFirst` | `false` | Stop after finding the first OD pair |
| `testorder.detect.seed` | `42` | Random seed for reproducibility |
| `testorder.detect.failOnDetection` | `false` | Fail the build if OD bugs are found |

**Example**:
```bash
mvn test-order:detect-dependencies \
  -Dtestorder.detect.algorithm=reverse \
  -Dtestorder.detect.timeBudget=60 \
  -Dtestorder.detect.failOnDetection=true
```

</details>

<details>
<summary><strong>Available Algorithms</strong></summary>

| Algorithm | Aliases | Description | Runs (N classes) |
|-----------|---------|-------------|-----------------|
| `combined` | `combined-adaptive` | **Recommended.** Adaptive multi-strategy: reverse, exclusion probes, random shuffling, edge-targeted probes. Highest detection rate. | ~2N–4N |
| `reverse` | `reverse-order` | Reverses the reference order. Fast single-pass detection of victims. Based on [iDFlakies \[1\]](#references). | 1 |
| `pfast` | `pfast-exclusion` | Exclusion-based: runs with each test removed to find brittles. | N |
| `random` | `random-reordering` | Random permutations within time budget. Broad coverage. Based on [iDFlakies \[1\]](#references). | time-bounded |
| `tuscan` | `tuscan-systematic` | Systematic pair coverage via Tuscan squares. Guarantees all class-pair orderings covered. Based on [Li et al. \[3\]](#references). | N or N+1 |
| `iterative` | `iterative-refinement` | Iterative binary-search refinement after initial detection. | log₂(N) per finding |
| `bounded` | `dependence-aware-bounded` | Uses conflict graph edges to bound the search space. Requires dependency index. | varies |
| `history` | `history-mining` | Mines historical test run data for order-sensitive patterns. Requires state file. | 0 (analysis only) |

**Algorithm Selection Guide**:

- **First time / general use**: `combined` — best detection rate, handles all OD types
- **Quick check in CI**: `reverse` — single pass, finds most victims in seconds
- **Comprehensive scan**: `tuscan` — mathematical guarantee of pair coverage
- **Brittle-focused**: `pfast` — specifically finds tests that need a setter
- **Existing dependency data**: `bounded` — leverages conflict graph for targeted probing

</details>

<details>
<summary><strong>Output Location</strong></summary>

Reports are written to:
```
<project>/.test-order/detection/
├── od-detection-report.json    # Machine-readable findings
└── od-detection-report.md      # Human-readable summary
```

</details>

<details>
<summary><strong>Prerequisites</strong></summary>

The target project must have `test-order-junit` on the test classpath for order control to work:

```xml
<dependency>
  <groupId>me.bechberger</groupId>
  <artifactId>test-order-junit</artifactId>
  <version>0.0.1-SNAPSHOT</version>
  <scope>test</scope>
</dependency>
```

Without this dependency, detection still works via the exclusion-probe strategy, but order-sensitive algorithms (reverse, random, tuscan) cannot enforce precise execution order.

</details>

---

## Output Formats

<details>
<summary><strong>JSON Report</strong> (<code>od-detection-report.json</code>)</summary>

Machine-readable, suitable for CI integration, dashboards, and automated processing.

**Structure**:
```json
{
  "metadata": {
    "module": "<Maven artifact ID>",
    "algorithm": "<algorithm name>",
    "testClassCount": 8,
    "passingTestCount": 6,
    "runsExecuted": 28,
    "durationMs": 46000,
    "durationFormatted": "46s",
    "conflictEdges": 12,
    "randomSeed": 42,
    "timeBudgetSeconds": 120,
    "timestamp": "2026-05-30T21:00:00Z"
  },
  "summary": {
    "totalFindings": 2,
    "victims": 1,
    "brittles": 1,
    "methodLevelFindings": 0
  },
  "findings": [
    {
      "victim": "<fully-qualified class name of the affected test>",
      "type": "VICTIM | BRITTLE",
      "confidence": 0.0-1.0,
      "description": "<human-readable explanation>",
      "dependencyChain": ["<test A>", "<test B>"]
    }
  ],
  "constraints": [
    {
      "testA": "<test class>",
      "testB": "<test class>",
      "type": "MUST_PRECEDE | MUST_NOT_PRECEDE",
      "reason": "<explanation>"
    }
  ]
}
```

**Field descriptions**:

| Field | Description |
|-------|-------------|
| `metadata.module` | Maven artifact ID of the scanned project |
| `metadata.algorithm` | Algorithm used for this run |
| `metadata.runsExecuted` | Number of subprocess test executions |
| `metadata.conflictEdges` | Edges in the conflict graph (test pairs sharing static fields) |
| `metadata.timeBudgetSeconds` | Time budget configured for this run |
| `summary.totalFindings` | Total OD findings (class + method level) |
| `summary.victims` | Count of VICTIM findings |
| `summary.brittles` | Count of BRITTLE findings |
| `findings[].victim` | The order-dependent test class |
| `findings[].type` | `VICTIM` = fails when polluter runs before it; `BRITTLE` = fails without its setter |
| `findings[].confidence` | Detection confidence (0.0–1.0). Higher = more certain. 0.99 = confirmed via isolation. 0.7 = detected but polluter not fully isolated. |
| `findings[].description` | Human-readable summary of the finding |
| `findings[].dependencyChain` | Ordered list: `[setter/polluter, victim]` |
| `constraints[].testA` | First test in the ordering constraint |
| `constraints[].testB` | Second test in the ordering constraint |
| `constraints[].type` | `MUST_PRECEDE` = testA must run before testB (brittle needs setter). `MUST_NOT_PRECEDE` = testA must NOT run before testB (polluter must not precede victim). |
| `constraints[].reason` | Explanation of why the constraint exists |

**Real example** (from sample-od-bugs with 8 test classes):
```json
{
  "metadata": {
    "module": "sample-od-bugs",
    "algorithm": "combined-adaptive",
    "testClassCount": 8,
    "passingTestCount": 6,
    "runsExecuted": 28,
    "durationMs": 46909,
    "durationFormatted": "46s",
    "conflictEdges": 0,
    "randomSeed": 42,
    "timeBudgetSeconds": 120
  },
  "summary": {
    "totalFindings": 2,
    "victims": 0,
    "brittles": 2,
    "methodLevelFindings": 0
  },
  "findings": [
    {
      "victim": "com.example.od.VictimTest",
      "type": "BRITTLE",
      "confidence": 0.99,
      "description": "Confirmed brittle: com.example.od.VictimTest needs com.example.od.SetupTest",
      "dependencyChain": ["com.example.od.SetupTest", "com.example.od.VictimTest"]
    },
    {
      "victim": "com.example.od.CacheConsumerTest",
      "type": "BRITTLE",
      "confidence": 0.99,
      "description": "Confirmed brittle: com.example.od.CacheConsumerTest needs com.example.od.CacheWarmupTest",
      "dependencyChain": ["com.example.od.CacheWarmupTest", "com.example.od.CacheConsumerTest"]
    }
  ],
  "constraints": [
    {
      "testA": "com.example.od.SetupTest",
      "testB": "com.example.od.VictimTest",
      "type": "MUST_PRECEDE",
      "reason": "Brittle: com.example.od.VictimTest needs com.example.od.SetupTest"
    },
    {
      "testA": "com.example.od.CacheWarmupTest",
      "testB": "com.example.od.CacheConsumerTest",
      "type": "MUST_PRECEDE",
      "reason": "Brittle: com.example.od.CacheConsumerTest needs com.example.od.CacheWarmupTest"
    }
  ]
}
```

</details>

<details>
<summary><strong>Markdown Report</strong> (<code>od-detection-report.md</code>)</summary>

Designed for human review — in pull requests, wiki pages, or local inspection.

**Real example**:
```markdown
# Order-Dependent Test Detection Report

## Summary

- **Total findings**: 2
- **Victims** (polluted by another test): 1
- **Brittles** (depend on a setter test): 1

## Findings

| # | Victim | Type | Confidence | Chain |
|---|--------|------|------------|-------|
| 1 | `com.example.od.VictimTest` | BRITTLE | 99% | `SetupTest` → `VictimTest` |
| 2 | `com.example.od.CacheWarmupTest` | VICTIM | 95% | `IntegrationFlowTest` → `CacheWarmupTest` |

## Ordering Constraints

- **MUST_PRECEDE**: `SetupTest` → `VictimTest`
  (Brittle: VictimTest needs SetupTest)
- **MUST_NOT_PRECEDE**: `IntegrationFlowTest` → `CacheWarmupTest`
  (Victim: IntegrationFlowTest pollutes CacheWarmupTest)
```

</details>

<details>
<summary><strong>Console Output</strong></summary>

During execution, the plugin logs progress and results:

```
[INFO] --- test-order:0.0.1-SNAPSHOT:detect-dependencies (default-cli) @ sample-od-bugs ---
[INFO] Loaded dependency map: 8 test classes
[INFO] Loaded state: 3 historical runs
[INFO] No prior data — running discovery test run...
[INFO] Discovered 8 test classes via initial run
[INFO] Detecting OD bugs among 8 test classes
[WARNING] 3 tests fail in reference order (ignored for detection)
[INFO] Conflict graph: 2 edges
[INFO] Running algorithm: combined-adaptive (estimated 28 runs)
[INFO] Detection complete: 2 findings (1 victims, 1 brittles)
[INFO] Report written to: .test-order/detection/od-detection-report.json
[WARNING] [test-order] Detected 2 order-dependent test(s): 1 victims, 1 brittles
```

</details>

<details>
<summary><strong>CI Integration</strong></summary>

### Quick Start (fail on detection)

```bash
mvn test-order:detect-dependencies \
  -Dtestorder.detect.algorithm=reverse \
  -Dtestorder.detect.timeBudget=120 \
  -Dtestorder.detect.failOnDetection=true \
  -Dspotless.check.skip=true \
  --batch-mode
```

Use `--batch-mode` in every CI invocation — it disables ANSI escapes and interactive prompts.

### Dedicated CI Script

`scripts/ci-detect-od-tests.sh` wraps the Maven goal with structured output, GitHub Actions annotations, and clean exit codes:

```bash
# Warn but don't fail (default — good for initial adoption)
./scripts/ci-detect-od-tests.sh --time-budget 120

# Fail the build on findings (for enforcement)
./scripts/ci-detect-od-tests.sh --fail-on-detection --time-budget 120

# Target a specific module in a multi-module build
./scripts/ci-detect-od-tests.sh --module my-service --time-budget 180

# Fast check using reverse-order algorithm
./scripts/ci-detect-od-tests.sh --algorithm reverse --time-budget 60

# Full options
./scripts/ci-detect-od-tests.sh \
  --algorithm combined \
  --time-budget 300 \
  --fail-on-detection \
  --annotate-github
```

**Exit codes from the script:**
| Code | Meaning |
|------|---------|
| `0` | No OD bugs found, or `--fail-on-detection` not set |
| `1` | OD bugs found with `--fail-on-detection` |
| `2` | Invocation error (bad arguments) |

### GitHub Actions

**Nightly scan (recommended starting point):**

```yaml
name: OD Test Detection

on:
  schedule:
    - cron: '0 2 * * *'   # 2am UTC nightly
  workflow_dispatch:

jobs:
  detect-od-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Cache Maven repository
        uses: actions/cache@v4
        with:
          path: ~/.m2/repository
          key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}
          restore-keys: ${{ runner.os }}-maven-

      - name: Detect OD tests
        run: |
          ./scripts/ci-detect-od-tests.sh \
            --algorithm combined \
            --time-budget 300 \
            --fail-on-detection \
            --annotate-github

      - name: Upload OD detection report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: od-detection-report
          path: .test-order/detection/
          retention-days: 30
```

**On every PR (fast reverse-order check):**

```yaml
      - name: Quick OD check (reverse order)
        run: |
          ./scripts/ci-detect-od-tests.sh \
            --algorithm reverse \
            --time-budget 120 \
            --annotate-github
```

The reverse algorithm runs in a single pass and finds the majority of victims — ideal for PR gates where speed matters. Use `combined` for the nightly comprehensive scan.

**Parse findings with `jq` (alternative to the script):**

```yaml
      - name: Detect OD tests
        run: |
          mvn test-order:detect-dependencies \
            -Dtestorder.detect.algorithm=combined \
            -Dtestorder.detect.timeBudget=300 \
            -Dspotless.check.skip=true \
            --batch-mode

      - name: Parse findings
        if: always()
        run: |
          REPORT=.test-order/detection/od-detection-report.json
          if [[ ! -f "$REPORT" ]]; then exit 0; fi
          COUNT=$(jq '.findings | length' "$REPORT")
          echo "OD findings: $COUNT"
          if [[ "$COUNT" -gt 0 ]]; then
            jq -r '.findings[] | "::warning title=OD Test (\(.type)): \(.victim)::\(.description)"' "$REPORT"
          fi
```

### GitLab CI

```yaml
detect-od-tests:
  stage: test
  rules:
    - when: scheduled
  script:
    - ./scripts/ci-detect-od-tests.sh --algorithm combined --time-budget 300 --fail-on-detection
  artifacts:
    when: always
    paths:
      - .test-order/detection/
    expire_in: 30 days
```

### Choosing a Time Budget

| Test suite size | Recommended budget | Notes |
|---|---|---|
| < 50 classes | 60–120s | Full coverage likely in one pass |
| 50–200 classes | 120–300s | Combined algorithm covers most permutations |
| 200–500 classes | 300–600s | Set `--algorithm reverse` for PR gates |
| 500+ classes | 600s+ per module | Consider scanning changed modules only |

The tool tells you how much budget was actually used and how many runs fit in the window:

```
[INFO] Each detection run takes ~7s. For full coverage (500 runs), set testorder.detect.timeBudget=3611
```

Use this output to calibrate your time budget over time.

### Incremental Mode

After the first complete scan, subsequent runs in the same `.test-order/` directory are incremental — previously confirmed findings are carried forward automatically and don't need to be rediscovered. This makes PR-gate scans faster over time.

The report tracks metadata for each run:

```json
{
  "metadata": {
    "runsExecuted": 15,
    "durationMs": 24800,
    "conflictEdges": 143
  }
}
```

### CI Best Practices

- **Start with `--algorithm reverse`** for fast initial adoption — it's a single pass and catches most victims.
- **Graduate to `combined`** once reverse passes cleanly — it has the highest detection rate.
- **Don't set `failOnDetection=true` immediately** — first run it in warn mode to see what already exists.
- **Archive the report artifact** — the JSON report accumulates knowledge across runs (incremental mode).
- **Use `--annotate-github`** (or the `jq` snippet) to surface findings as GitHub check annotations on the PR.
- **Commit `.test-order/detection/`** if you want to track findings in version control; otherwise add it to `.gitignore`.

</details>

---

## Terminology

### Order-Dependent (OD) Test

A test whose outcome (pass or fail) depends on the execution order of the test suite. OD tests are a subset of "flaky" tests — they pass in one order and fail in another.

### Victim

An OD test that **passes in isolation** but **fails** when a specific other test (the *polluter*) runs before it. The polluter modifies shared state that the victim depends on being clean.

**Example**: `VictimTest` passes alone but fails if `PollutorTest` runs first and leaves stale data in a static cache.

### Brittle

An OD test that **fails in isolation** but **passes** when a specific other test (the *state-setter*) runs before it. The brittle test depends on state that another test happens to set up.

**Example**: `BrittleTest` fails alone because it expects a registry to be initialized, but passes when `SetupTest` runs first and initializes it.

### Polluter

A test that **writes shared mutable state** (typically static fields) without cleaning up after itself. Polluters cause *victims* to fail when they run before them.

### State-Setter

A test that **initializes shared state** that a *brittle* test depends on. Unlike polluters, state-setters enable downstream tests to pass.

### Cleaner

A test that **resets shared state**, potentially masking OD bugs. If a cleaner runs between a polluter and its victim, the bug is hidden.

### Reference Order

The baseline execution order in which all "passing" tests are identified. Tests that fail in the reference order are excluded from OD detection (they have pre-existing bugs unrelated to ordering).

### Conflict Graph

A directed graph where nodes are test classes and edges represent potential ordering conflicts — tests that share static fields or other mutable state. Edge weights indicate the likelihood of an OD relationship based on shared field count, field type, and access patterns.

---

## How It Works

The detection algorithm operates in phases:

1. **Discovery** — Run all tests to identify the test suite and establish a reference passing set.
2. **Reference Run** — Execute tests in a deterministic (alphabetical) order to identify which tests pass consistently. Tests failing here are excluded.
3. **Detection** — Apply ordering strategies to surface failures caused by execution order changes.
4. **Pinpointing** — When a failure is found, use binary-search-style isolation to identify the minimal polluter/victim pair.

### The Combined-Adaptive Algorithm

The default `combined` algorithm uses multiple strategies with adaptive prioritization:

- **Reverse Order**: Reverses the reference order — a proven baseline from iDFlakies research [1].
- **Exclusion Probes**: Runs subsets excluding one test at a time to detect brittles (tests that need a setter).
- **Random Shuffling**: Generates random permutations to explore the order space.
- **Edge-Targeted Probes**: Uses the conflict graph to prioritize test pairs that share static fields.

---

## Academic Foundations

The detect-dependencies implementation draws on the following research:

### Core Techniques

**iDFlakies** — Lam, W., Oei, R., Shi, A., Marinov, D., & Xie, T. (2019). "A Framework for Detecting and Partially Classifying Flaky Tests." *ICST '19*. [DOI: 10.1109/ICST.2019.00038](https://doi.org/10.1109/ICST.2019.00038) · [IEEE](https://ieeexplore.ieee.org/abstract/document/8730188/)

The foundation for OD test detection. Introduced the three-phase approach (Setup → Run → Check), the victim/brittle/NOD classification, and demonstrated that reversing the test order is an effective baseline strategy. The reverse-order strategy in our combined algorithm directly implements this approach.

**iFixFlakies** — Shi, A., Lam, W., Oei, R., Xie, T., & Marinov, D. (2019). "iFixFlakies: A Framework for Automatically Fixing Order-Dependent Flaky Tests." *ESEC/FSE '19*. [DOI: 10.1145/3338906.3338925](https://doi.org/10.1145/3338906.3338925)

Established the polluter/victim/cleaner taxonomy and demonstrated that static field dependencies account for 80–85% of OD bugs in Java. Our conflict graph construction and the focus on static field sharing as the primary signal are based on this finding.

**Tuscan Intra-Class** — Li, C., Khosravi, M.M., Lam, W., & Shi, A. (2023). "Systematically Producing Test Orders to Detect Order-Dependent Flaky Tests." *ISSTA '23*. [DOI: 10.1145/3597926.3598083](https://doi.org/10.1145/3597926.3598083) · [ACM](https://dl.acm.org/doi/abs/10.1145/3597926.3598083)

Demonstrated that systematic pair coverage via Tuscan squares achieves 97.2% OD detection with ~104.7 test orders, and that only ~3.3 "minimal essential" orders are needed on average. This insight motivates our adaptive strategy that prioritizes high-yield orderings early.

**PRADET** — Gambi, A., Bell, J., & Zeller, A. (2018). "Practical Test Dependency Detection." *ICST '18*. [DOI: 10.1109/ICST.2018.00041](https://doi.org/10.1109/ICST.2018.00041) · [IEEE](https://ieeexplore.ieee.org/abstract/document/8367031/)

Validated the two-phase architecture: (1) approximate data dependencies cheaply, then (2) confirm with targeted reruns. Our approach mirrors this — the conflict graph (built from the DependencyMap static index) serves as the cheap approximation phase, and binary-search pinpointing serves as the targeted confirmation phase. PRADET demonstrated 2.3×–130.5× speedup over exhaustive approaches.

### Supplementary Research

**PRAW Dependencies** — Biagiola, M., Stocco, A., Mesbah, A., Ricca, F., & Tonella, P. (2019). "Web Test Dependency Detection." *ESEC/FSE '19*.

Introduced read-after-write dependency patterns and NLP-based filtering that reduces validation cost by 72%. The concept of filtering unlikely conflict pairs before expensive validation informs our conflict graph edge weighting.

**FlaKat** — Lin, S. (2023). "A Machine Learning-Based Categorization Framework for Flaky Tests." *MASc Thesis, University of Waterloo*.

Demonstrated that ML classifiers on test source code achieve F1=0.90 for OD test categorization without re-execution. This validates that static signals (like our DependencyMap field analysis) are strong predictors of OD behavior.

**Flakinator** — Malik, R. (2025). "Taming Test Flakiness." *Atlassian Engineering Blog*.

Industry validation at scale (350M+ test executions/day). Their Bayesian inference scoring and quarantine lifecycle management inform the confidence scores in our detection reports.

---

## Design Rationale

### Why Static Field Focus?

80–85% of OD bugs in Java involve static field state (Li et al., 2023; Shi et al., 2020). The existing DependencyMap already tracks static field access at bytecode level, making this the highest-value signal available without runtime instrumentation.

### Why Binary Search for Pinpointing?

Given a failing test order, the polluter could be any test that ran before the victim. Binary search narrows this down in O(log K) iterations rather than O(K) brute-force attempts. For a module with 10 candidate polluters, this means ~4 targeted runs instead of 10.

### Why Multiple Strategies?

No single strategy dominates across all OD bug types:
- Reverse order excels at finding victims (tests that need something to NOT run before them)
- Exclusion probes excel at finding brittles (tests that need something TO run before them)
- Random shuffling provides broad coverage for edge cases

The combined algorithm adaptively allocates its run budget across strategies based on early results.

### Why Order Control via FixedOrderClassOrderer?

Maven Surefire's `-Dtest=A,B,C` controls *which* tests run but not their *execution order*. The `FixedOrderClassOrderer` (a JUnit Jupiter `ClassOrderer`) is injected at runtime to enforce exact class ordering — essential for both the detection and pinpointing phases.

---

## References

1. Lam, W., Oei, R., Shi, A., Marinov, D., & Xie, T. (2019). "iDFlakies: A Framework for Detecting and Partially Classifying Flaky Tests." *Proc. ICST '19*. [DOI: 10.1109/ICST.2019.00038](https://doi.org/10.1109/ICST.2019.00038) · [IEEE](https://ieeexplore.ieee.org/abstract/document/8730188/)
2. Shi, A., Lam, W., Oei, R., Xie, T., & Marinov, D. (2019). "iFixFlakies: A Framework for Automatically Fixing Order-Dependent Flaky Tests." *Proc. ESEC/FSE '19*. [DOI: 10.1145/3338906.3338925](https://doi.org/10.1145/3338906.3338925) · [ACM](https://dl.acm.org/doi/abs/10.1145/3338906.3338925)
3. Li, C., Khosravi, M.M., Lam, W., & Shi, A. (2023). "Systematically Producing Test Orders to Detect Order-Dependent Flaky Tests." *Proc. ISSTA '23*. [DOI: 10.1145/3597926.3598083](https://doi.org/10.1145/3597926.3598083) · [ACM](https://dl.acm.org/doi/abs/10.1145/3597926.3598083)
4. Gambi, A., Bell, J., & Zeller, A. (2018). "Practical Test Dependency Detection." *Proc. ICST '18*. [DOI: 10.1109/ICST.2018.00041](https://doi.org/10.1109/ICST.2018.00041) · [IEEE](https://ieeexplore.ieee.org/abstract/document/8367031/)
5. Biagiola, M., Stocco, A., Mesbah, A., Ricca, F., & Tonella, P. (2019). "Web Test Dependency Detection." *Proc. ESEC/FSE '19*. [DOI: 10.1145/3338906.3338948](https://doi.org/10.1145/3338906.3338948) · [ACM](https://dl.acm.org/doi/abs/10.1145/3338906.3338948)
6. Lin, S. (2023). "FlaKat: A Machine Learning-Based Categorization Framework for Flaky Tests." *MASc Thesis, University of Waterloo*. [UWSpace](https://uwspace.uwaterloo.ca/handle/10012/19362)
7. Malik, R. et al. (2025). "Taming Test Flakiness: How We Built a Scalable Tool to Detect and Manage Flaky Tests." *Atlassian Engineering Blog*. [Blog post](https://www.atlassian.com/blog/atlassian-engineering/taming-test-flakiness-how-we-built-a-scalable-tool-to-detect-and-manage-flaky-tests)
