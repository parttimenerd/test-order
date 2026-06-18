# test-order — 50-minute conference talk

A demo-heavy Slidev deck on test-order: problem, usage, features, implementation, and a production-scale demo.

## Run the deck

```bash
cd talks/conference-50min
npm install
npm run dev          # opens http://localhost:3030
```

Presenter mode: <http://localhost:3030/presenter>

## Pre-flight checklist (do this before going on stage)

**T-30 min**

```bash
cd demo/dcom-presentation
./prepare.sh      # ~15 min first time, no-op after
```

**T-10 min**

```bash
cd demo/dcom-presentation
./reset.sh        # clean state for the live demo
```

**T-5 min** — open seven iTerm2 tabs (font 22pt+):

| Tab | Purpose | Pre-cd to |
|---|---|---|
| 1 | D1 sample-basic | `samples/sample-basic` |
| 2 | D2 / D7 / D8 sample-shop | `samples/sample-shop` |
| 3 | D3 spring-ai | `third-party/spring-ai` |
| 4 | D4 dashboard launcher | `samples/sample-shop` |
| 5 | D5 multi-module | `samples/sample-multi` |
| 6 | D6 OD-bug detector | `samples/sample-od-bugs` |
| 7 | D9 cloud-sdk-java | `demo/dcom-presentation/cloud-sdk-java` |

Pre-warm the dashboard: `mvn test-order:dashboard` once in tab 4 — leave the tab open in the browser.

## Demo cheat sheet

| # | Slide title | Command | Wall time |
|---|---|---|---|
| D1 | Demo 1 — the happy path | `mvn test && mvn test && mvn test-order:show` | 30 s |
| D2 | Demo 2 — change a class, watch the rank shift | edit Cart.java → `mvn test && mvn test-order:show` | 45 s |
| D3 | Demo 3 — Spring AI bug injection | `bash ../../scripts/demo-spring-ai.sh` | 3:45 |
| D4 | Demo 4 — interactive dashboard tour | `mvn test-order:dashboard` (browser) | 3 min |
| D5 | Demo 5 — reactor-level aggregation | `mvn test && mvn test-order:show -pl :module-checkout` | 45 s |
| D6 | Demo 6 — order-dependent test detection | `mvn test-order:detect-dependencies` | 60 s |
| D7 | Demo 7 — `:affected` for sub-minute CI | `mvn test-order:affected test` | 30 s |
| D8 | Demo 8 — kill-rate as a scoring signal | `mvn test-order:analyze-mutations && mvn test-order:show` | 90 s |
| D9 | Demo 9 — SAP Cloud SDK for Java | `mvn test-order:affected test` (post-`make-change.sh`) | 3 min |

Total demo wall time: ~15 min. Total spoken time: ~35 min. Target deck wall time: 48–52 min.

## Demo flow pattern

Every demo follows the same pattern:

1. Land on the demo card slide. Read the command aloud as you type.
2. Hit Enter. Advance immediately to cover slides — *don't wait*.
3. Talk over cover slides while demo runs. Look up at terminal occasionally.
4. When demo finishes, return to result slide and recap what the audience saw.

The `<DemoCue>` badge in the corner of cover slides reminds you a demo is running.

## Fallbacks

- D3 wedge: replay `public/demo-spring-ai.cast` with `asciinema play`.
- Dashboard fails to render: screenshots in `public/` cover all five tabs (`dashboard-overview.png`, `analytics-tab.png`, `dashboard-weights.png`, `ml-tab.png`, `staticanalysis-tab.png`).
- D9 wedge: switch to D7 on `samples/sample-shop` — same idea, smaller scale.

## Export

```bash
npm run build              # static HTML in dist/
npm run export-pdf         # PDF at ../test-order-talk.pdf
```
