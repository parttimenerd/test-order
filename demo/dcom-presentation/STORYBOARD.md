---
title: "You're Running the Wrong Tests First"
duration: "6:00"
speaker: Johannes Bechberger
event: SAP d-com Mannheim 2026
---

# Storyboard — "You're Running the Wrong Tests First"

## Slide 1 of 4 — Hero  (~1:30)

*[hero.png — full-bleed image, no live code yet]*
```
┌─────────────────────────────────────────────────────────────────────┐
│                                                                     │
│  YOU ARE              ┌── THE OLD WAY ──┐  ┌── THE SMART WAY ──┐   │
│  RUNNING THE          │ Run everything  │  │ Run what matters  │   │
│  ██████  (red)        │ Wait forever    │  │ Fail fast         │   │
│  WRONG                │                 │  │                   │   │
│  TESTS FIRST          │ ●━━━━━━━━━━━ ✗  │  │ ●━━━━━━━━━━━ ✓   │   │
│                       │ ●━━━━━━━━━━━ ✗  │  │ ●━━━━━━━━━━━ ✓   │   │
│  JUnit + Maven        │ ●━━━━━━━━━━━ ✗  │  │ ● (skipped)      │   │
│  [logos bottom-left]  │                 │  │ ● (skipped)      │   │
│                       │ TIME TAKEN      │  │ TIME TAKEN        │   │
│                       │    10:42        │  │    00:43          │   │
│                       └─────────────────┘  └───────────────────┘   │
│                       YOUR TRAINING  BYTECODE           RUN         │
│                       DATA           INSTRUMENTATION    IMPACTED    │
└─────────────────────────────────────────────────────────────────────┘
```

**BEAT 1 — intro (~0:20)**

> "Welcome, I'm Johannes Bechberger from the SAP Machine team —"
> *(point to shirt)*
> "— we build SAP Machine, SAP's own Java runtime distribution, running Java
> workloads across SAP's entire cloud. But today I'm not talking about any
> Java feature. I'm talking about something that every Java developer in this
> room does every single day: running tests."

---

**BEAT 2 — the claim (~0:15)**

> "I'll make a claim: you're probably running the wrong tests first — especially
> if you're developing Java applications with JUnit and Maven, and sometimes
> Gradle."

---

**BEAT 3 — the pain (~0:25)**

> "What we often do while working is make a small change — something that
> might optimize performance — and then run all the tests. Literally all
> of them, even ones not affected by our change. That's been the way for
> the last 20–25 years."
>
> "It kills developer velocity. As someone from Gradle once said: if you
> double test time, you quarter developer productivity. That's not great."

---

**BEAT 4 — live pain (~0:30)**

*Terminal open in `cloud-sdk-java/`. Font size large. Notifications off.*

**ACTION — run:**
```
$ mvnd test
```
Let it compile and begin running. Kill with Ctrl+C after ~25 seconds.

> "Cloud SDK for Java — a real open-source project we maintain at SAP. I'll
> stop it here — would have taken another five minutes to finish."

*FALLBACK if mvnd hangs or is slow to start: "You get the idea — I'll spare you the wait." Kill immediately and move on.*

**→ advance to slide 2** — bridge: "So what if our test runner could *learn* what each test actually executes? Let me show you."

---

## Slide 2 of 4 — Live: Tiny Shop, End-to-End  (~3:00)

*[Slide is a static anchor — Cart / Product / Invoice diagram. The real work
happens in the terminal + browser, not on the slide.]*

```
┌─────────────────────────────────────────────────────────────────────┐
│                                                                     │
│              demo-shop  —  3 classes, 14 tests                      │
│                                                                     │
│   ┌──────────┐   ┌──────────┐                                      │
│   │ Product  │   │   Cart   │ ◀────── depends on ─────┐            │
│   └──────────┘   └──────────┘                          │            │
│         ▲             ▲                                │            │
│         │             │                          ┌─────┴────┐       │
│         └─────────────┴──────── depends on ──────│ Invoice  │       │
│                                                  └──────────┘       │
│                                                                     │
│   tests:    ProductTest        CartTest        InvoiceTest          │
│             (5 tests)          (5 tests)       (4 tests)            │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```
*Cart depends on Product. Invoice depends on Cart and Product. Edit Cart →
expect CartTest + InvoiceTest to run; ProductTest gets skipped.*

---

*Setup: VS Code is open with `demo-shop/` as the workspace. `Cart.java` is
visible in the editor, integrated terminal pane open at the bottom, AI side
panel (Cursor / Copilot Chat) docked right. A separate browser window has
TWO tabs already loaded: (1) the dashboard for a **real production app** —
pre-warmed before the talk so the dep graph is interesting, (2) a blank tab
ready for the demo-shop dashboard. Both `mvnd test-order:serve` processes are
already running in background terminals.*

---

**BEAT 1 — show the project (~0:15)**

*In VS Code, expand the file explorer.*

> "Three production classes — Product, Cart, Invoice — and a matching test
> class for each. 14 tests total. Cart uses Product, Invoice uses both."

---

**BEAT 2 — learn pass (~0:20)**

> "First, the learn run. The plugin records what each test actually executes."

**ACTION — in the integrated terminal, run:**
```
$ mvnd test-order:learn test
```
Completes in seconds, all 14 tests pass.

> "Done. The dependency map lives in `.test-order/`."

---

**BEAT 3 — dashboard on a real app (~0:25)**

> "Before we look at the toy, here's the dashboard running on a real
> application — Cloud SDK for Java. Same plugin, same view."

**ACTION — switch to browser, tab 1 (already loaded).**

Tests tab is already open. Press **`g`** to land on the dep graph; hover a
test to show its methods. If chasing a specific test: **`Cmd+F`** to search.

> "Hundreds of tests, every one mapped to the methods it actually executes.
> This is what selective execution stands on — at any size."

*FALLBACK if the tab didn't pre-load: skip straight to beat 4.*

---

**BEAT 4 — dashboard on demo-shop (~0:25)**

> "Now the same view for our tiny shop."

**ACTION — switch to browser, tab 2.** (Dashboard for demo-shop is already
running on a different port; tab is pre-loaded.)

Use the keyboard shortcuts to navigate:
- **`Cmd+F`** — focus search, type `addProducts`
- **`g`** — jump to the dep graph (Tests tab + Focus mode)
- **`m`** — toggle method mode to show the method→class graph for the
  selected test; press `m` again to drop back to test-level

The detail panel shows the production methods exercised: `Cart.add`,
`Cart.size`, `Cart.total`, `Product.<init>`, `Product.getPrice`.

> "Every test, every method it touched. Small enough to read in one glance."

---

**BEAT 5 — let an AI change the code (~0:30)**

*Switch back to VS Code with `Cart.java` open. AI side panel visible.*

**ACTION — type prompt into the AI:**
```
Refactor Cart.total() to use a for-loop instead of a stream.
```

Wait for the agent to apply the edit. Show the diff in the editor for ~3 seconds.

> "An AI agent makes a small change to Cart. In a normal workflow it would
> now run all 14 tests to check itself."

---

**BEAT 6 — selective run (~0:35)**

**ACTION — in the integrated terminal, run:**
```
$ mvnd test-order:affected test -Dtestorder.changeMode=since-last-run -Dtestorder.affected.topN=2
```

The plugin compares current bytecode against the hashes from the learn run
and picks the affected tests. **CartTest** and **InvoiceTest** run;
**ProductTest** is skipped. Finishes in 2–3 seconds.

> "Cart changed — so CartTest and InvoiceTest run, because Invoice depends
> on Cart. ProductTest is skipped. One test class saved, in seconds."

*If the agent's change broke a test: "And there's the bug — caught in
seconds, not overnight."*
*If green: "Fast, focused feedback — exactly what an agent loop needs."*

---

**BEAT 7 — run remaining (~0:20)**

> "We don't skip tests forever — just first."

**ACTION — in the integrated terminal, run:**
```
$ mvnd test-order:run-remaining test
```
ProductTest runs. Done.

> "We always run everything eventually. We just don't make you wait for
> the answer to the question you asked."

**→ advance to slide 3** — bridge: "And this works in CI too."

---

## Slide 3 of 4 — CI Workflow: Three-Tier Pipeline  (~0:30)

```
┌─────────────────────────────────────────────────────────────────────┐
│                                                                     │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │  0  │  Learn dependencies                     │   nightly   │  │
│   │     │  record what each test executes          │             │  │
│   └──────────────────────────────────────────────────────────────┘  │
│         │                                                            │
│         ▼                                                            │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │  1  │  Affected tests                          │   seconds   │  │
│   │     │  tests that hit the change               │             │  │
│   └──────────────────────────────────────────────────────────────┘  │
│         │                                                            │
│         ▼                                                            │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │  2  │  Broader coverage                        │   minutes   │  │
│   │     │  risk-ranked full module                 │             │  │
│   └──────────────────────────────────────────────────────────────┘  │
│         │                                                            │
│         ▼  (only if tiers 1 & 2 pass)                               │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │  3  │  Remaining tests                         │  on green   │  │
│   │     │  always run if tiers 1 & 2 pass          │             │  │
│   └──────────────────────────────────────────────────────────────┘  │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```
*Tiers 0–3 stacked top-to-bottom, sequential flow. Timing badge on right of each row.*

**BEAT 1 — walk the tiers (~0:30)**

> "In CI you can stack the same idea: first the affected tests in seconds,
> then a broader pass, then everything else once those pass. Same build,
> no waiting until morning."

**→ advance to slide 4** — bridge: "So — what do you do with all this?"

---

## Slide 4 of 4 — Close  (~0:30)

```
┌─────────────────────────────────────────────────────────────────────┐
│                                                                     │
│   Stop running tests that never fail.                               │
│                                                                     │
│   ✓  Only run tests that hit the change      ┌─────────────────┐   │
│                                              │                 │   │
│   ✓  Agentic loops that don't stall          │    [QR code]    │   │
│                                              │                 │   │
│   ✓  Bugs in seconds, not overnight          └─────────────────┘   │
│                                              github.com/           │
│                                              parttimenerd/         │
│                                              test-order            │
│                                              experimental ·        │
│                                              open-source           │
│                                                                     │
│           Johannes Bechberger · SAP d-com Mannheim 2026            │
└─────────────────────────────────────────────────────────────────────┘
```
*Three green checkmarks left, QR code right linking to GitHub repo.*

**BEAT 1 — the open-source call (~0:20)**

> "Stop running tests that never fail. Run the tests that hit your change —
> catch bugs in seconds, not overnight."
> *(pause)*
> "This is open source. Built at SAP, for everyone. Scan the code, try it,
> break it, improve it — that's how we build better software together."

---

**BEAT 2 — close (~0:10)**

**ACTION:** Gesture toward the QR code on screen.

> "Help me find bugs before our customers do."
>
> "That's all. Thank you — hit me up afterwards."

---

## Timing Guide

| Slide | Beat                              | Target  |
|-------|-----------------------------------|---------|
| 1/4   | Beat 1 — intro                    | ~0:20   |
| 1/4   | Beat 2 — the claim                | ~0:15   |
| 1/4   | Beat 3 — the pain                 | ~0:25   |
| 1/4   | Beat 4 — live pain                | ~0:30   |
| **1/4 total** |                           | **~1:30** |
| 2/4   | Beat 1 — show the project         | ~0:15   |
| 2/4   | Beat 2 — learn pass               | ~0:20   |
| 2/4   | Beat 3 — dashboard, real app      | ~0:25   |
| 2/4   | Beat 4 — dashboard, demo-shop     | ~0:25   |
| 2/4   | Beat 5 — AI changes code          | ~0:30   |
| 2/4   | Beat 6 — selective run            | ~0:35   |
| 2/4   | Beat 7 — run remaining            | ~0:20   |
| **2/4 total** |                           | **~3:10** |
| 3/4   | Beat 1 — walk the tiers           | ~0:30   |
| **3/4 total** |                           | **~0:30** |
| 4/4   | Beat 1 — open-source call         | ~0:20   |
| 4/4   | Beat 2 — close                    | ~0:10   |
| **4/4 total** |                           | **~0:30** |
| **Grand total** |                         | **~5:40** |

*Buffer: ~0:20 of slack to 6:00. If running long after the AI edit (slide 2
beat 5), skip the real-app dashboard fly-around in beat 3 and jump straight
from learn → demo-shop dashboard.*

---

## Pre-flight Checklist

### cloud-sdk-java terminal (slide 1)
- [ ] Kill any running `mvn` process before going on stage
- [ ] Terminal open in `cloud-sdk-java/`, `mvn` on PATH, window font size large
- [ ] Practice the Ctrl+C kill in the right terminal window

### VS Code workspace (slide 2)
- [ ] VS Code open with `demo-shop/` as the workspace root
- [ ] `Cart.java` open in the editor, integrated terminal pane visible at the bottom
- [ ] AI side panel (Cursor or Copilot Chat) docked on the right, model loaded, logged in
- [ ] Integrated terminal `cd`'d into `demo-shop/`, `JAVA_HOME` = sapmachine-21, font size large
- [ ] `./reset-shop.sh` then `./prepare-shop.sh` ran successfully — `.test-order/`
      contains a baked learn index, sources are clean
- [ ] Run `mvnd test-order:affected test -Dtestorder.changeMode=since-last-run -Dtestorder.affected.topN=2`
      once with no edits to verify 2 tests run and 1 is deferred (baseline)

### Browser (slide 2)
- [ ] Run `./launch-presentation.sh` from `demo-shop/` — starts both
      dashboards in the background and opens them in the default browser:
      - **Tab 1** http://localhost:8765 — cloud-sdk-java (real app), Tests tab open
      - **Tab 2** http://localhost:8766 — demo-shop, Tests tab open
- [ ] Browser window positioned so VS Code → browser is one Cmd+Tab away
- [ ] Pre-recorded screenshot of each dashboard ready as fallback
- [ ] After the talk, run `./stop-presentation.sh` to kill the background `mvnd` processes

**Dashboard keyboard shortcuts (memorize before stage):**

| Key       | Action                                               |
|-----------|------------------------------------------------------|
| `Cmd+F`   | Focus search input (also selects existing text)      |
| `g`       | Jump to dep graph (Tests tab + Focus mode)           |
| `m`       | Toggle method mode — picks first method of current test to show method→class graph; press again to drop back |

### AI prompt
- [ ] Rehearse the prompt at least twice — choose one that produces a
      minimal, predictable Cart edit (current pick: *"Refactor Cart.total()
      to use a for-loop instead of a stream."*)
- [ ] Have a hand-typed fallback edit ready (e.g., add a no-op comment in `total()`)
      in case the AI is slow or unavailable

### slides
- [ ] Slides on external display, presenter view on laptop
- [ ] QR code in focus on slide 4 — check it's not cut off on the projector
