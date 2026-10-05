# You Are Running the Wrong Tests First — 20-minute talk

Slidev presentation for JavaZone / Devoxx / similar 20-minute conference slots.

---

## Prerequisites

| Tool | Minimum | Notes |
|------|---------|-------|
| Java | 17 | JDK (not JRE); must be on `PATH` |
| Maven | 3.9+ | `mvn` on `PATH` |
| Node.js | 18+ | For Slidev dev server |
| npm | 9+ | Bundled with Node |

Verify with:
```bash
java -version    # 17+
mvn -v           # 3.9+
node --version   # 18+
```

---

## One-time setup (first clone only)

### 1. Build the plugin locally

The order-run path requires `test-order-core-0.1.0-all.jar` (shaded, includes lz4).
It is not published to Maven Central — build it from source:

```bash
# From the repo root
mvn install -pl test-order-core -am -DskipTests -q
```

Verify:
```bash
ls ~/.m2/repository/me/bechberger/test-order-core/0.1.0/test-order-core-0.1.0-all.jar
```

### 2. Clone spring-petclinic

`third-party/` is gitignored (external checkout). Clone it once:

```bash
# From the repo root
git clone https://github.com/spring-projects/spring-petclinic \
    third-party/spring-petclinic
```

### 3. Install Slidev dependencies

```bash
cd talks/conference-20min
npm install
```

### 4. Pre-build the petclinic index (~4 min, one time)

The learn run instruments and indexes petclinic's classes. Do this once so D3 starts
in order-mode on stage:

```bash
cd third-party/spring-petclinic
# Skip slow checks that aren't relevant to the demo
mvn test \
  -Dcheckstyle.skip=true \
  -Dspring-javaformat.skip=true \
  -DexcludedGroups=testcontainers \
  -Denforcer.skip=true
```

The index is written to `third-party/spring-petclinic/.test-order/`.

---

## Pre-show prep (run ~5 minutes before going on stage)

```bash
cd talks/conference-20min
./prepare-demo.sh
```

This script:
1. Checks Java 17+ and Maven are on `PATH`
2. Restores `Cart.java` if a D2 edit was left behind from a previous run
3. Deletes the reactor-root index (`<repo>/.test-order/`) so D1 starts with a fresh
   learn pass — petclinic's own index (`third-party/spring-petclinic/.test-order/`) is
   unaffected
4. Writes `third-party/spring-petclinic/.mvn/extensions.xml` to activate test-order in
   petclinic (the file is not tracked in git because `third-party/` is gitignored)
5. Verifies the petclinic index exists and prints its age
6. Prints the exact command sequence for each demo

Options:
```
./prepare-demo.sh                   # normal pre-show run
./prepare-demo.sh --learn-petclinic # force re-learn petclinic index (~4 min)
./prepare-demo.sh --check-only      # dry-run: validate only, change nothing
```

---

## Running the slides

```bash
cd talks/conference-20min
npx slidev slides.md
```

Open <http://localhost:3030> for the audience view.  
Press `p` to open presenter mode (notes + timer) in a second window or second monitor.

Export a PDF backup:
```bash
npx slidev export slides.md --output conference-20min-backup.pdf
```

---

## Demo overview

The talk has four live demos. Open four terminal tabs before going on stage.

### D0 — petclinic: no test-order (Tab 1)

Show what CI does today: tests run in JVM discovery order, not by relevance.
`VisitControllerTests` runs 9th out of 18 — you wait through 8 unrelated tests
before finding the failure.

```bash
cd third-party/spring-petclinic
mvn test -pl . -Dsurefire.failIfNoSpecifiedTests=false
```

**What to show:** the test class names scrolling by in the terminal.
Key line to point at: `Running …VisitControllerTests` appearing late in the list.

> Note: order is JVM classfile discovery order, not A-Z by short name.
> `VetControllerTests` runs first; `VisitControllerTests` is 9th.

---

### D1 — sample-shop: learn run → order run (Tab 2)

Shows auto-detection: first `mvn test` with no index → learn run; second `mvn test`
with index present → order run with ranking printed.

```bash
cd samples/sample-shop
mvn test           # Run 1: learn run
mvn test           # Run 2: order run (test order changes)
mvn test-order:show  # Ranked table with Score and Why columns
```

**Run 1 key output:**
```
[test-order] No dependency index found — switching to learn mode automatically.
[test-order] Auto-instrumenting classes for offline learn mode: …/target/classes
[test-order] Instrumented 3 classes (skipped 0)
[test-order] Index written: 1 KB (3 tests)
```

**Run 2 key output:**
```
[test-order] Order mode: injecting PriorityClassOrderer
Running com.example.shop.ProductTest    ← scored highest (FAST + 1 dep)
Running com.example.shop.InvoiceTest
Running com.example.shop.CartTest
```

**`mvn test-order:show` output:**
```
#  Test Class            Score  Deps  Why
1. c.e.shop.ProductTest      1     1  speed +1
2. c.e.shop.InvoiceTest      0     3
3. c.e.shop.CartTest         0     2
```
> No code change → no change-bonus, so scores are low. The interesting ranking
> happens in D2 after editing Cart.java.

---

### D2 — sample-shop: edit Cart.java, affected tests rise (Tab 2, continued)

Shows that a one-line change to a production class immediately re-ranks the tests
that depend on it — no retraining needed.

**Edit** `samples/sample-shop/src/main/java/com/example/shop/Cart.java`,
inside `add()`, add:
```java
if (product == null)
    throw new IllegalArgumentException();
```

> **Important:** this project enforces Palantir Java formatting via Spotless.
> Run `mvn spotless:apply` before `mvn test` or the build will fail with a
> format-violation error.

```bash
# After editing Cart.java:
mvn spotless:apply     # fix Palantir format (required)
mvn test               # order run detects changed class
mvn test-order:show    # show why each test scored what it did
```

**Expected result:**
```
Changed classes: com.example.shop.Cart
→ boosting 2 tests that depend on them

1. c.e.shop.InvoiceTest  score 8   (2/3 deps overlap: Cart + Invoice)
2. c.e.shop.CartTest     score 6   (1/2 deps overlap: Cart)
3. c.e.shop.ProductTest  score 3   (0 deps overlap, FAST bonus)
```

`InvoiceTest` ranks #1 (not `CartTest`) because InvoiceTest depends on both `Cart`
and `Invoice` — higher dep-overlap ratio. Both are boosted; InvoiceTest wins on
breadth.

Key talking point: "No ML, no retraining, no history needed — a set intersection."

After D2, **restore Cart.java** (or `prepare-demo.sh` will do it before the next run):
```bash
# Manual restore if needed:
git checkout -- samples/sample-shop/src/main/java/com/example/shop/Cart.java
```

---

### D3 — petclinic: live APFD + dashboard (Tab 3)

Shows test-order on a real Spring Boot project. The index was built in the one-time
setup; this run loads it and applies ranking.

```bash
cd third-party/spring-petclinic
mvn test                  # order run — loads index, prints APFD at end
mvn test-order:serve      # opens dashboard on http://localhost:8080
```

**`mvn test` key output:**
```
[test-order] Order mode: injecting PriorityClassOrderer
[test-order] Detected N changed source classes: …
[test-order] → boosting M tests that depend on them
Running …PetControllerTests    ← top-ranked
Running …OwnerControllerTests
…
[test-order] APFD: 0.87
```

**Dashboard (`mvn test-order:serve`):**  
Four tabs to walk through:
- **Tests** — ranked list with scores, APFD trend sparkline
- **Analytics** — APFD over time, first-failure rank distribution
- **Coverage** — which source classes each test touched (dep map)
- **Weights** — live weight sliders; tweak and see re-ranking in real time

Use `-Dtestorder.dashboard.port=9090` to pin a known port:
```bash
mvn test-order:serve -Dtestorder.dashboard.port=9090
```

> **Port conflict:** if port 9090 is in use (e.g. from a previous run), choose any
> free port, e.g. `-Dtestorder.dashboard.port=9091`.

---

## Demo resilience

| What breaks | Recovery |
|-------------|----------|
| Any demo crashes | Switch to slides — each demo slide has a static screenshot fallback |
| Slow network during learn run | All deps are pre-cached in `~/.m2/`; no live HTTP calls during `mvn test` |
| `NoClassDefFoundError: lz4` | Shaded jar missing — run `mvn install -pl test-order-core -am -DskipTests -q` from repo root |
| Spotless BUILD FAILURE in D2 | You forgot `mvn spotless:apply` — run it, then re-run `mvn test` |
| `Address already in use` for dashboard | Use a different port: `-Dtestorder.dashboard.port=9091` |
| Test order looks wrong in D3 | Run `./prepare-demo.sh --check-only` — check petclinic index age; re-learn if >7 days old |
| Forgot to reset after previous D2 | `./prepare-demo.sh` will restore `Cart.java` and clear the index automatically |

---

## File map

```
talks/conference-20min/
├── slides.md           — master Slidev file (all slides inline)
├── style.css           — global CSS (typography, dark/light vars, big-statement, etc.)
├── prepare-demo.sh     — pre-show prep script
├── package.json        — Slidev + D3 dependencies
├── public/
│   ├── images/         — dashboard screenshots (dashboard-*.png), talk photos
│   └── demo.cast       — asciinema recording fallback for D1/D2
└── components/         — Vue components used in slides
    ├── DemoCard.vue         — demo placeholder card (id, cmd, title, watch)
    ├── PipelineDiagram.vue  — animated D3 pipeline (learn → index → score → rank)
    ├── ApfdTimeline.vue     — horizontal APFD bar showing test order vs. failure position
    ├── BenchmarkChart.vue   — bar chart of APFD scores across OSS repos
    ├── ScoringBreakdown.vue — animated D3 scoring bar chart for CartTest
    ├── InstrumentationOverhead.vue — JaCoCo vs test-order overhead comparison bars
    ├── InstrumentationPipeline.vue — bytecode instrumentation flow diagram
    ├── LandscapeMap.vue     — 2D scatter: tools by "time to first value" vs. intelligence
    ├── MavenLog.vue         — terminal-style Maven log display
    ├── DepGraphDiagram.vue  — class dependency graph visualization
    └── DemoCue.vue          — speaker cue card overlay
```

---

## Index and data directories (not in git)

| Path | Contents | Notes |
|------|----------|-------|
| `<repo>/.test-order/` | Reactor-level index for sample-shop | Deleted by `prepare-demo.sh` before D1 |
| `third-party/spring-petclinic/.test-order/` | Petclinic dep index | Preserved across runs; rebuilt with `--learn-petclinic` |
| `third-party/spring-petclinic/.mvn/extensions.xml` | test-order plugin activation | Written by `prepare-demo.sh`; not tracked (dir is gitignored) |
| `samples/sample-shop/src/…/Cart.java.bak` | D2 edit backup | Created automatically before D2 edit; restored by `prepare-demo.sh` |

---

## Timing reference

Target: 20 minutes. Budget per section:

| Section | Slides | Budget |
|---------|--------|--------|
| Hook + show-of-hands | 1–3 | 2 min |
| Why this matters (APFD baseline) | 4–10 | 2 min |
| D0 — baseline demo | 11 | 2.5 min |
| Solution overview + POM install | 12–18 | 2 min |
| D1 — learn → order | 19 | 3 min |
| D2 — edit Cart.java | 20–23 | 2 min |
| Under the hood (scoring) | 24–27 | 2 min |
| D3 — petclinic + dashboard | 28–34 | 3.5 min |
| Results (87–91% APFD) | 35–38 | 1 min |
| Tradeoffs + CTA | 39–44 | 2 min |
| **Total** | | **~22 min** |

First rehearsal always runs long. Cut from the middle — the hook and CTA survive every cut.
