---
theme: seriph
title: "You Are Running the Wrong Tests First"
info: |
  test-order — 60-minute conference talk.
  Local-first, zero-config test prioritization for Maven and Gradle.
class: text-center
highlighter: shiki
colorSchema: 'dark'
lineNumbers: false
drawings:
  persist: false
transition: slide-left
mdc: true
fonts:
  sans: 'Merriweather'
  serif: 'DM Serif Display'
  mono: 'JetBrains Mono'
layout: cover
---

<style>
:global(.scoring-table) { font-size: 0.78rem; line-height: 1.3; margin-top: 0.25rem; }
:global(.scoring-table table) { width: 100%; border-collapse: collapse; margin: 0 !important; }
:global(.scoring-table th) { font-size: 0.72rem; text-transform: uppercase; letter-spacing: 0.05em; color: #64748b; padding: 1px 10px 3px !important; border-bottom: 1px solid #334155; line-height: 1.3 !important; }
:global(.scoring-table td) { padding: 2px 10px !important; border-bottom: 1px solid #1e293b; vertical-align: middle; line-height: 1.3 !important; margin: 0 !important; }
:global(.scoring-table tr) { margin: 0 !important; padding: 0 !important; }
:global(.scoring-table tr:last-child td) { border-bottom: none; }
:global(.scoring-table td:nth-child(2)) { text-align: center; font-weight: 600; color: #fbbf24; white-space: nowrap; }
:global(.big-statement) {
  font-size: 3rem;
  font-weight: 700;
  line-height: 1.3;
  text-align: center;
  width: 100%;
}
:global(.slidev-slide pre),
:global(.slidev-slide .shiki) {
  font-size: 1.05rem;
  line-height: 1.6;
  border-radius: 10px;
  padding: 18px 22px;
}
:global(.pull-quote) {
  border-left: 4px solid #f97316;
  padding: 0.9rem 1.4rem;
  margin: 1.2rem 0;
  font-size: 1.35rem;
  font-weight: 600;
  line-height: 1.45;
  color: #f1f5f9;
  background: rgba(249, 115, 22, 0.07);
  border-radius: 0 8px 8px 0;
}
:global(.hands-up) {
  display: inline-flex;
  align-items: center;
  gap: 0.6rem;
  background: rgba(250, 204, 21, 0.12);
  border: 1px solid rgba(250, 204, 21, 0.4);
  border-radius: 8px;
  padding: 0.6rem 1.2rem;
  font-size: 1.1rem;
  font-weight: 600;
  color: #fef08a;
  margin-top: 1.2rem;
}
:global(.tag-bad) {
  display: inline-block;
  background: rgba(239,68,68,0.15);
  border: 1px solid rgba(239,68,68,0.4);
  color: #fca5a5;
  border-radius: 6px;
  padding: 0.15rem 0.6rem;
  font-size: 0.85rem;
  font-weight: 700;
  font-family: 'JetBrains Mono', monospace;
  margin-right: 0.4rem;
  vertical-align: middle;
}
:global(.tag-ok) {
  display: inline-block;
  background: rgba(148,163,184,0.12);
  border: 1px solid rgba(148,163,184,0.35);
  color: #94a3b8;
  border-radius: 6px;
  padding: 0.15rem 0.6rem;
  font-size: 0.85rem;
  font-weight: 700;
  font-family: 'JetBrains Mono', monospace;
  margin-right: 0.4rem;
  vertical-align: middle;
}
:global(.deco-quote) {
  font-family: cursive !important;
  font-size: 14rem;
  font-weight: 900;
  line-height: 0.9;
  position: absolute;
  top: 0.5rem;
  left: 1.5rem;
  user-select: none;
  pointer-events: none;
  opacity: 0.13;
}
:global(.quote-slide) {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 0 5rem;
  position: relative;
  overflow: hidden;
}
:global(.quote-text) {
  font-size: 2.6rem;
  font-weight: 600;
  line-height: 1.4;
  color: #f1f5f9;
  max-width: 52rem;
  position: relative;
  z-index: 10;
}
:global(.quote-attr) {
  font-size: 1rem;
  color: #64748b;
  margin-top: 2rem;
  font-weight: 400;
  font-style: normal;
  position: relative;
  z-index: 10;
}
:global(.quote-accent-bar) {
  position: absolute;
  left: 2.5rem;
  top: 15%;
  bottom: 15%;
  width: 6px;
  border-radius: 3px;
  opacity: 0.6;
}
</style>


<!-- ═══ SLIDE 1: COVER ════════════════════════════════════════════════════════ -->

<div class="relative z-10">

# You Are Running the Wrong Tests First

<div class="pt-6 text-base opacity-50">
  Johannes Bechberger · @parttimenerd · SAP SE
</div>

</div>

<!--
OPENING LINE (spoken): "Regression testing is an important but costly software engineering task." — Legunsen et al., FSE 2016. Pause. "And yet here we are, still running tests in alphabetical order."
-->

---
layout: image
image: /images/baselone26-sponsors.png
title: "Sponsors"
---

<!-- ═══ SLIDE 2: FEEDBACK LOOP ════════════════════════════════════════════════ -->

---
layout: default
title: "The Feedback Loop"
class: slide-base
---

## The Feedback Loop

<FeedbackLoop class="mt-4" />

<div v-click class="pull-quote mt-6">
  The bottleneck isn't how fast your tests run.<br/>
  It's <strong>which one runs first.</strong>
</div>

<!--
Don't rush this slide. The audience recognises this loop — it's their daily life.
Let "which one runs first" land before advancing.
-->

<!-- ═══ SLIDE 3: D0 LIVE DEMO ════════════════════════════════════════════════ -->

---
layout: default
title: "D0 — CI Baseline Demo"
class: slide-base
---

<DemoCard id="D0" title="What we do today">

```bash
cd third-party/commons-collections
./make-change-commons.sh   # remove one line from remove()
mvn test
```

</DemoCard>

<!--
DEMO D0 — commons-collections, NO test-order. prepare-demo.sh ensures .mvn/extensions.xml is absent.

SETUP (run prepare-demo.sh before going on stage):
- Source is clean — make-change-commons.sh applies the patch live on stage
- .test-order/ absent, .mvn/extensions.xml has no test-order plugin

STEPS:
1. cd third-party/commons-collections
2. mvn test  — DO NOT kill it early, let it reach pos 237

NARRATE as it runs:
- "This is Apache Commons Collections. Real open-source project. 321 tests."
- "I removed one line from EnumerationIterator.remove() — a `last = null` reset."
- "Alphabetical order. A… B… C… E… we're at test 80 out of 321… still going…"
- Keep narrating as it crawls past 150, 200, 237
- When EnumerationIteratorTest fails: "There it is. Position 237. We ran 236 tests first."
- "Every one of those was wasted time."

Let it sink in for 2 seconds. Then advance.

FALLBACK: asciinema play public/demo-d0.cast
-->

<!-- ═══ SLIDE 4: SHOW OF HANDS ════════════════════════════════════════════════ -->

---
layout: center
title: "Show of Hands"
---

<div class="big-statement">
  Who's been context-switched out of a problem<br/>because CI took too long?
</div>

<div class="hands-up mt-10">✋ Keep your hand up if it happened this week.</div>

<!--
First audience interaction. Feed the response: "Right. That's what we're fixing today."
-->

<!-- ═══ SLIDE 5: APFD TIMELINE ════════════════════════════════════════════════ -->

---
layout: default
title: "APFD Timeline"
class: slide-base
---

## The Cost of Wrong Order

<ApfdTimeline />

<!--
The chart shows first failure landing at ~80% of total runtime in alphabetical suites.
"You already felt this. Position 237 out of 321 — that's 74% of the suite run before the failure."
-->

<!-- ═══ SLIDE 6: GOOGLE STAT ════════════════════════════════════════════════ -->

---
layout: default
title: "91% Stat"
---

<QuoteCard
  attr="Memon et al., Taming Google-Scale Continuous Testing, ICSE-SEIP 2017 · 5.5M targets, 500K+ code changes"
  accentColor="#fb923c"
>
  <strong>91.3% of tests at Google passed and never failed.<span class="text-orange-400"> Not once.</span></strong>
</QuoteCard>

<!--
Pause 3 seconds. Let it land.
TRANSITION: "Which leads to a hard logical claim."
-->

<!-- ═══ ACT 1: THE FIX ════════════════════════════════════════════════════════ -->

---
layout: default
title: "Memon Quote"
---

<QuoteCard
  attr="Memon et al., Taming Google-Scale Continuous Testing, ICSE-SEIP 2017"
  accentColor="#c084fc"
>
  "Very few of our tests ever fail, but those that do are generally
  <span class="text-purple-300 not-italic font-bold">'closer'</span>
  to the code they test."
</QuoteCard>

<!--
The research foundation. "Closer" is doing a lot of work here — we're about to operationalize it.
-->

---
layout: center
title: "Logic Claim"
---

<div class="big-statement">
  deps(test) ∩ changed = ∅<br/>
  <span class="text-2xl opacity-70 mt-4 block">→ cannot fail on this change</span>
</div>

<div class="text-center text-sm opacity-50 mt-6"><code>changed</code> = classes whose bytecode differs since last compile — detected automatically via opcode hash</div>

<!--
One line. No prose. This is the math behind everything that follows.
"If a test hasn't called the changed code, it cannot fail on this change."
"changed = any class whose compiled bytecode changed — test-order detects this automatically on every run via opcode hash, no manual annotation needed."
-->

---
layout: default
title: "Pipeline Diagram"
class: slide-base
---

## Record the map once. Rank on every push.

<PipelineDiagram />

<!--
Learn run builds the dep map. Every subsequent run uses it to reorder.
Zero overhead in order mode — no instrumentation runs, just a sort.
-->

---
layout: default
title: "Maven POM"
class: slide-base
---

## One file. That's the entire setup.

**`.mvn/extensions.xml`** — works for single-module and multi-module projects

```xml
<?xml version="1.0" encoding="UTF-8"?>
<extensions>
  <extension>
    <groupId>me.bechberger</groupId>
    <artifactId>test-order-maven-plugin</artifactId>
    <version>0.1.0</version>
  </extension>
</extensions>
```

<div class="mt-5 text-slate-400 text-sm">

Loads as a Maven build extension — before the build lifecycle. No `mvn` command changes needed.  
For single-module projects only: can also go in `pom.xml` under `<build><extensions>`.

</div>

<!--
Create .mvn/extensions.xml in the project root — that's it.
This is the only method that works in multi-module projects (the plugin must load before the reactor is built).
Single-module projects can also use pom.xml <build><extensions>, but .mvn/extensions.xml is simpler and always works.
"That's the entire setup. Everything else is automatic."
-->

---
layout: default
title: "Runtime Auto-Detection"
class: slide-base
---

## Two modes. Automatic.

<div class="grid grid-cols-2 gap-8 mt-6">
<div class="p-6 rounded-xl bg-zinc-800/60 border border-zinc-700">

### No index yet → **Learn**

```
[test-order] learn mode
Instrumentation overhead: ~13%  # measured on spring-petclinic, MEMBER mode
Recording dep map...
```

</div>
<div class="p-6 rounded-xl bg-zinc-800/60 border border-zinc-700">

### Index present → **Order**

```
[test-order] order mode
Overhead: zero
EnumerationIteratorTest → #1
```

</div>
</div>

<!--
5 change modes exist (uncommitted, since-last-commit, since-last-run, auto, explicit) — reference for Q&A only.
The key message: it detects which mode to use. You never configure this.
-->

---
layout: default
title: "D1 — spring-petclinic Live Demo"
class: slide-base
---

<DemoCard id="D1" title="test-order in action">

```bash
cd third-party/spring-petclinic
# index is cold — first run will learn
mvn test
./petclinic-make-change.sh  # edit VisitController.java
mvn test          # VisitControllerTests → #1, red immediately
mvn test-order:show
```

</DemoCard>

<!--
DEMO D1 — spring-petclinic, cold index.

SETUP: Maven compile cache pre-warmed (run `mvn test-only` once before talk).
.test-order/ index ABSENT — D1 live-learns from scratch.
VisitController.java clean — petclinic-make-change.sh applies the edit live.

STEPS:
1. cd third-party/spring-petclinic
2. mvn test  — first run, learn mode. ~5s. Show the "[test-order] learn mode" output.
3. ./petclinic-make-change.sh  — edits flash message in processNewVisitForm
4. mvn test  — order mode. VisitControllerTests ranked #1. Watch it go red immediately.
5. Ctrl+C after first red line.
6. mvn test-order:show  — show the annotated ranking.

NARRATE:
- After learn run: "It just recorded which classes each test touched."
- After edit: "I changed one line in VisitController — a flash message."
- After red: "Same Maven command. One line in pom.xml. Now the right test runs first."

FALLBACK: asciinema play public/demo-d1.cast
-->

---
layout: default
title: "Scoring Breakdown"
class: slide-base
---

## Why did VisitControllerTests rank #1?

<ScoringBreakdown />

<!--
Shown on slide (dominant signals for VisitControllerTests):
- Dep overlap: VisitController is directly in the dep map → +2 (normalized by sqrt(depTotal))
- Pkg proximity: same package as changed class → +2
- Change complexity: 1 changed class in dep set → +1

Not shown — all contribute to every score:
- Fail history: EMA decay over recent runs (test that failed 3 runs ago still gets a fading boost)
- Changed test: test class itself was edited → large fixed bonus, always top-ranked
- New test: never run before → moved to position 1 unconditionally
- Speed: fast tests up, slow tests down — keeps the feedback loop tight
- Name match: "VisitControllerTests" ↔ "VisitController" — heuristic name proximity bonus
- Static field overlap: shared static fixture with a changed class
- Set-cover / kill rate: unique coverage bonus for tests covering ground others don't

Total: 5 → VisitControllerTests ranks #1 out of all suite classes.
-->

---
layout: default
title: "test-order:show Output"
class: slide-base
---

## `mvn test-order:show`

```
[test-order] Ranked 15 tests (order mode, since-last-commit)

  #1  VisitControllerTests          score=14  ★ dep-overlap=8  fail=4  new=2
  #2  PetControllerTests            score=9   dep-overlap=6  speed=3
  #3  OwnerControllerTests          score=7   dep-overlap=5  speed=2
  ...
 #15  PetTypeFormatterTests         score=1   speed=1
```

<!--
"This is what it tells you before you run. You can see exactly why each test is ranked where it is."
TRANSITION: "That was 15 tests on a toy project. Does this hold up on a real project with hundreds of tests and a real bug?"
-->

<!-- ═══ ACT 2: PROVE IT ═══════════════════════════════════════════════════════ -->

---
layout: default
title: "Real Bug Setup + Diff"
class: slide-base
---

## Position 237. One missing line.

<div class="grid grid-cols-2 gap-8 mt-4">
<div>

**Apache Commons Collections**
321 tests · alphabetically: failure at **#237**

```java
public E remove() {
    if (last == null) {
        throw new IllegalStateException(...);
    }
-   last = null;  // ← this line was removed
    iterator.remove();
}
```

</div>
<div class="flex flex-col justify-center">

<div class="pull-quote">
  You already felt position 237.<br/>
  Let's replay it — with test-order.
</div>

</div>
</div>

<!--
Callback to D0. The audience already lived the 5-minute wait.
Keep it tight — don't re-explain the bug. One sentence max.
-->

---
layout: default
title: "D2 — commons-collections Live Demo"
class: slide-base
---

<DemoCard id="D2" title="Same bug. test-order active.">

```bash
cd third-party/commons-collections
./setup-test-order.sh     # adds .mvn/extensions.xml + restores index
mvn test-order:show       # EnumerationIteratorTest → #1
mvn test                  # Ctrl+C after first red (~10s)
```

</DemoCard>

<!--
DEMO D2 — commons-collections, test-order added live via setup-test-order.sh.

STEPS:
1. ./setup-test-order.sh  — adds test-order to .mvn/extensions.xml (shows diff), restores pre-trained index
2. mvn test-order:show  — show ranking: EnumerationIteratorTest at #1
3. mvn test  — it goes red immediately
4. Ctrl+C after first red line

NARRATE for setup-test-order.sh:
- "One file. .mvn/extensions.xml. You can see the diff right there."

NARRATE:
- "Same project. Same bug. Same 321 tests."
- "We waited at position 237 ten minutes ago."
- After red: "That's position 1. Ten seconds instead of five minutes."
- Pause 3 seconds. Let the contrast with D0 land.

FALLBACK: screenshot pair — D0 terminal (pos 237) vs D2 terminal (pos 1) side by side.
-->

---
layout: default
title: "Benchmark Chart"
class: slide-base
---

## 7 repos. 65 bugs. Consistent improvement.

<BenchmarkChart />

<div class="text-sm opacity-60 mt-4 text-center">
  62 of 65 patches triggered a test failure — results include no_failure cases, not cherry-picked.
  Data: <code>target/bench-apfd/results.csv</code>
</div>

<!--
"This isn't one lucky project. Seven open-source repos, 65 bugs total — 62 triggered a test failure."
Acknowledge the misses honestly — 3 patches hit no_failure (guava locale/timeout issue).
The chart shows the full picture: commons-collections 26% → 0.9%, jackson-core 13% → 4%.
TRANSITION: "What does this cost? One question every audience member is thinking."
-->

---
layout: default
title: "Overhead"
class: slide-base
---

## The cost: one learn run. Zero overhead after that.

<OverheadChart />

<!--
spring-petclinic, 15 tests, MEMBER mode (default). Source: docs/PERFORMANCE_OPTIMIZATIONS.md.
"The instrumentation runs once. Every run after that — no agent, no overhead. The index is just a sort key."
"Commit .test-order/ to your repo and CI never pays it either."
TRANSITION: "same story across all benchmark repos — three modes, one pattern"
-->

---
layout: default
title: "Learn overhead across repos and modes"
class: slide-base
---

## Same story across all benchmark repos.

<LearnOverheadChart />

<div class="text-sm opacity-50 mt-3 text-center">
  Mean overhead ± std err · 3 runs each · MEMBER★ is the default mode
</div>

<!--
"FULL tracks at class granularity — lightest instrumentation, fewer edges. METHOD adds call edges per method. MEMBER also tracks field accesses — most precise, similar cost."
"All three modes pay the overhead exactly once. Order runs carry zero instrumentation regardless of mode."
Variation across repos reflects suite size and test-class coupling density, not mode quality.
TRANSITION: "You've seen the terminal. How do you track this over time?"
-->

<!-- ═══ ACT 3: DASHBOARD ══════════════════════════════════════════════════════ -->

---
layout: center
title: "Dashboard Transition"
---

<div class="big-statement">Let's go into the dashboard.</div>

<!--
Single sentence. Visual breath. Let the audience shift from terminal to browser.
-->

---
layout: default
title: "D3 — Dashboard Live Demo"
class: slide-base
---

<DemoCard id="D3" title="test-order:dashboard">

```bash
cd third-party/spring-petclinic
mvn test-order:dashboard
open target/test-order-dashboard/index.html
```

</DemoCard>

<!--
DEMO D3 — spring-petclinic, index warm from D1 with multiple runs recorded.

TABS TO SHOW (live, ~5 min):
1. Tests tab: VisitControllerTests at #1, score bar, "Why" breakdown, sparkline history
2. Analytics tab: APFD trend across D1 runs, rank heatmap
3. Weights tab: drag "dep overlap" slider → watch VisitControllerTests move in real time

MENTION BUT DON'T DEMO:
- ML Health tab, Mutations tab (PIT kill-rate), Static Analysis tab
- "Three more tabs we won't walk through today."

NARRATE:
- "You recognise VisitControllerTests — that's the test we just saw go red in D1."
- "Tests tab: rank, score, dep overlap count, run history. Evidence, not alphabetical order."
- On weights tab: "You can tune the scoring for your project and see the effect live."

FALLBACK: dashboard screenshots from public/images/ (dashboard-tests.png, dashboard-analytics.png, dashboard-weights.png)
-->

---
layout: default
title: "KPI Bar Decoded"
class: slide-base
---

## Six numbers that tell the story

<div class="grid grid-cols-3 gap-6 mt-6 text-center">
  <div class="p-4 rounded-xl bg-zinc-800/60 border border-zinc-700">
    <div class="text-3xl font-bold text-green-400">APFD</div>
    <div class="text-sm opacity-70 mt-2">Average % of faults<br/>detected early</div>
  </div>
  <div class="p-4 rounded-xl bg-zinc-800/60 border border-zinc-700">
    <div class="text-3xl font-bold text-red-400">Failures</div>
    <div class="text-sm opacity-70 mt-2">Tests that failed<br/>in recent runs</div>
  </div>
  <div class="p-4 rounded-xl bg-zinc-800/60 border border-zinc-700">
    <div class="text-3xl font-bold text-yellow-400">Pass streak</div>
    <div class="text-sm opacity-70 mt-2">Consecutive green<br/>runs per test</div>
  </div>
  <div class="p-4 rounded-xl bg-zinc-800/60 border border-zinc-700">
    <div class="text-3xl font-bold text-orange-400">At-risk</div>
    <div class="text-sm opacity-70 mt-2">Tests showing<br/>DEGRADING trend</div>
  </div>
  <div class="p-4 rounded-xl bg-zinc-800/60 border border-zinc-700">
    <div class="text-3xl font-bold text-blue-400">Time saved</div>
    <div class="text-sm opacity-70 mt-2">Minutes not spent<br/>on wrong tests</div>
  </div>
  <div class="p-4 rounded-xl bg-zinc-800/60 border border-zinc-700">
    <div class="text-3xl font-bold text-purple-400">Health A–F</div>
    <div class="text-sm opacity-70 mt-2">Overall suite<br/>quality grade</div>
  </div>
</div>

---
layout: default
title: "Staleness Flow"
class: slide-base
---

## How the index stays fresh

<StalenessFlow />

<!--
Three mechanisms:
1. Opcode hash checked on every run — if bytecode changed, invalidate
2. Dep fingerprint on source changes — trigger selective re-learn
3. Over-approximate for unknown classes — deferred safety net
"You never manually refresh the index."
-->

---
layout: default
title: "Landscape Map"
class: slide-base
---

## Where test-order fits

<LandscapeMap />

<!--
Different trade-offs from Ekstazi/STARTS/Skippy/Cloud TIA.
Don't bash other tools — just show where test-order sits in the space.
-->

---
layout: default
title: "When NOT to Use This"
class: slide-base
---

## When NOT to use this

<div class="grid grid-cols-1 gap-4 mt-6 max-w-2xl mx-auto">
  <div class="p-4 rounded-xl bg-red-900/20 border border-red-800/40">
    <strong>&lt;20 tests</strong> — the overhead of building a dep map isn't worth it
  </div>
  <div class="p-4 rounded-xl bg-red-900/20 border border-red-800/40">
    <strong>Reflection-only dependencies</strong> — bytecode instrumentation can't see through <code>Class.forName()</code>
  </div>
  <div class="p-4 rounded-xl bg-red-900/20 border border-red-800/40">
    <strong>Dynamic classloaders</strong> — classes loaded at runtime won't appear in the dep map
  </div>
</div>

<!--
Honesty builds trust. Saying "here's where it doesn't work" makes everything else more believable.
TRANSITION: "Your laptop is sorted. What about the pipeline?"
-->

<!-- ═══ ACT 4: CI ════════════════════════════════════════════════════════════ -->

---
layout: center
title: "CI Transition"
---

<div class="big-statement">What about the pipeline?</div>

<!--
Visual breath. Audience shifts from "this works on my machine" to "this works at org scale."
-->

---
layout: default
title: "The CI Problem"
class: slide-base
---

## The pipeline pays full price on every push

<div class="mt-6 text-xl leading-relaxed">
Full suite · every push · dozens per day · every run pays full cost.
</div>

<div class="hands-up mt-8">✋ Who has CI that takes more than 10 minutes?</div>

<!--
Second audience interaction. Feed: "That's the norm, not the exception."
-->

---
layout: default
title: "Affected-Only"
class: slide-base
---

## `mvn test-order:affected test`

<MavenLog>

```
[test-order] change detection: since-last-commit
[test-order] changed class: EnumerationIterator
[test-order] selected 9 tests (316 deferred)

Running EnumerationIteratorTest ...  FAILED
```

</MavenLog>

<!--
Use v-click to reveal "selected 9 tests (316 deferred)" after the changed-class line.
"One changed class. Nine tests selected. 316 deferred."
-->

---
layout: default
title: "Tiered CI Pipeline"
class: slide-base
---

## Three tiers. One index.

<div class="grid grid-cols-3 gap-6 mt-6 text-center">
  <div class="p-5 rounded-xl bg-zinc-800/60 border border-green-700/60">
    <div class="text-lg font-bold text-green-400">Tier 1 — Push check</div>
    <div class="text-sm opacity-70 mt-2"><code>test-order:affected</code><br/>Changed classes only<br/>Seconds to red</div>
  </div>
  <div class="p-5 rounded-xl bg-zinc-800/60 border border-yellow-700/60">
    <div class="text-lg font-bold text-yellow-400">Tier 2 — Nightly</div>
    <div class="text-sm opacity-70 mt-2"><code>test-order:learn</code> + <code>run-tier 1</code><br/>Refresh index + top-scored tests<br/>Minutes</div>
  </div>
  <div class="p-5 rounded-xl bg-zinc-800/60 border border-zinc-600">
    <div class="text-lg font-bold text-zinc-400">Tier 3 — Pre-release</div>
    <div class="text-sm opacity-70 mt-2"><code>run-tier 2/3</code><br/>Full suite<br/>Pre-merge gate</div>
  </div>
</div>

<!--
Actual goals: tiered-select + run-tier 1/2/3. Parallel sharding (shard=k/N) available — mention only if asked.
-->

---
layout: default
title: "GitHub Actions YAML"
class: slide-base
---

## Ten lines to wire it into CI

```yaml {all|4-7|9-10}
- name: Run tests
  run: mvn test-order:affected test   # PR push — affected only

- name: Cache test-order index
  uses: actions/cache@v4
  with:
    path: .test-order/
    key: test-order-${{ hashFiles('src/**') }}

    # changeMode=since-last-commit detects what changed in this PR
  env:
    MAVEN_OPTS: -Dtestorder.changeMode=since-last-commit
```

<!--
Highlight steps:
- 4-7: cache the index between runs — this is what makes it cheap on every PR
- 9-10: since-last-commit tells test-order what changed relative to the base branch
Nightly job (not shown): `mvn test-order:learn test` — forces a fresh learn run to refresh the index.
Speaker note: mention `download` goal (fetch pre-built index from CI artifacts) as an aside.
-->

---
layout: default
title: "Scale Results"
class: slide-base
---

## Large multi-module project · 52 modules

<div class="grid grid-cols-2 gap-8 mt-6">
<div class="p-6 rounded-xl bg-red-900/20 border border-red-800/40 text-center">
  <div class="text-4xl font-bold text-red-400">90s</div>
  <div class="mt-2 opacity-70"><code>mvn clean test</code></div>
  <div class="text-sm mt-1 opacity-50">Compile crawl. Zero tests started.</div>
</div>
<div class="p-6 rounded-xl bg-green-900/20 border border-green-800/40 text-center">
  <div class="text-4xl font-bold text-green-400">55s → RED</div>
  <div class="mt-2 opacity-70"><code>mvn test-order:affected test</code></div>
  <div class="text-sm mt-1 opacity-50">Right failure. Right away.</div>
</div>
</div>

<div class="pull-quote mt-8">
  80% earlier signal × 30 pushes/day = a completely different dev loop.
</div>

<!--
Numbers from a real run on a large internal project (think sap-cap scale — 52 modules).
Don't name the project on stage.
TRANSITION: "Before we go under the hood — a quick tour of what else is in the box."
-->

<!-- ═══ INTERLUDE: FEATURE CATALOGUE ══════════════════════════════════════════ -->

---
layout: default
title: "Gradle"
class: slide-base
---

## Gradle? Three lines.

```groovy
plugins {
    id 'me.bechberger.test-order' version '0.1.0'
}
```

```bash
./gradlew test   # same index format, same dep map
```

<!--
Same index works for both Maven and Gradle. You can share an index across build tools.
-->

---
layout: default
title: "Multi-Framework"
class: slide-base
---

## Works with your test framework

<div class="grid grid-cols-2 gap-4 mt-6">
  <div class="p-4 rounded-xl bg-zinc-800/60 border border-zinc-700">✓ JUnit 5 / JUnit 6</div>
  <div class="p-4 rounded-xl bg-zinc-800/60 border border-zinc-700">✓ JUnit 4 via Vintage</div>
  <div class="p-4 rounded-xl bg-zinc-800/60 border border-zinc-700">✓ TestNG 7.x+</div>
  <div class="p-4 rounded-xl bg-zinc-800/60 border border-zinc-700">✓ Kotest</div>
</div>

---
layout: default
title: "Annotations"
class: slide-base
---

## Fine-grained control

```java
@AlwaysRun          // always ranked #1 regardless of score
class SmokeTest { }

@TestOrder(scoreBonus = 10)          // flat bonus to score
class CriticalPathTest { }

@TestOrder(changeBonus = 5)          // bonus only when deps overlap a change
class IntegrationTest { }
```

<div class="mt-6 text-base opacity-70">
  Flaky quarantine: <code>testorder.flaky.quarantine=true</code> — reports ABORTED not FAILED. Build stays green.
</div>

---
layout: default
title: "TDD Enforcement"
class: slide-base
---

## `testorder.tdd=true`

```
[test-order] TDD VIOLATION: NewFeatureTest
  → Test passed on first run with no prior failure recorded.
  → Write the failing test BEFORE the implementation.
BUILD FAILURE
```

<v-click>

<div class="pull-quote mt-6">
  Flip this on in CI. It will catch tests written after the feature, not before.
</div>

</v-click>

<!--
Pause for the laugh after "BUILD FAILURE". Let it land.
Then: "This is opt-in. You don't have to use it. But if you care about TDD discipline in CI, one property."
The v-click reveals the pull-quote after the code — gives the audience time to read the error first.
-->

---
layout: default
title: "Goals Overview"
class: slide-base
---

## Six goals. Zero config.

<div class="grid grid-cols-3 gap-4 mt-6 text-sm">
  <div class="p-4 rounded-xl bg-zinc-800/60 border border-zinc-700">
    <div class="font-bold text-green-400 font-mono">show</div>
    <div class="opacity-70 mt-1">Ranking preview before you run</div>
  </div>
  <div class="p-4 rounded-xl bg-zinc-800/60 border border-zinc-700">
    <div class="font-bold text-blue-400 font-mono">affected</div>
    <div class="opacity-70 mt-1">CI selection — changed classes only</div>
  </div>
  <div class="p-4 rounded-xl bg-zinc-800/60 border border-zinc-700">
    <div class="font-bold text-purple-400 font-mono">dashboard</div>
    <div class="opacity-70 mt-1">Local analytics + run history</div>
  </div>
  <div class="p-4 rounded-xl bg-zinc-800/60 border border-zinc-700">
    <div class="font-bold text-yellow-400 font-mono">optimize</div>
    <div class="opacity-70 mt-1">Tune scoring weights to your project</div>
  </div>
  <div class="p-4 rounded-xl bg-zinc-800/60 border border-zinc-700">
    <div class="font-bold text-orange-400 font-mono">analyze-mutations</div>
    <div class="opacity-70 mt-1">PIT kill-rate per test</div>
  </div>
  <div class="p-4 rounded-xl bg-zinc-800/60 border border-zinc-700">
    <div class="font-bold text-red-400 font-mono">detect-dependencies</div>
    <div class="opacity-70 mt-1">Find order-dependent tests</div>
  </div>
</div>

<!--
diagnose and download exist too — mention only if asked.
TRANSITION: "Now — how does it actually work?"
-->

---
layout: default
title: "Staleness and Safety Nets"
class: slide-base
---

## The index never goes stale

<div class="grid grid-cols-1 gap-4 mt-6 max-w-2xl mx-auto">
  <div class="p-4 rounded-xl bg-zinc-800/60 border border-zinc-700">
    <strong>Opcode hash</strong> — checked on every run. Bytecode changed → invalidate entry.<br/>
    <span class="text-sm opacity-60">e.g. you edit <code>CartService.java</code> and recompile → its entry is removed, affected tests re-run uncached</span>
  </div>
  <div class="p-4 rounded-xl bg-zinc-800/60 border border-zinc-700">
    <strong>Dep fingerprint</strong> — source change triggers selective re-learn for affected classes only.<br/>
    <span class="text-sm opacity-60">e.g. you add a new dependency to <code>pom.xml</code> → plugin switches to learn mode automatically</span>
  </div>
  <div class="p-4 rounded-xl bg-zinc-800/60 border border-zinc-700">
    <strong>Unknown classes</strong> — over-approximated and deferred, never silently skipped.<br/>
    <span class="text-sm opacity-60">e.g. a new class with no dep record → all tests that could possibly touch it are included</span>
  </div>
</div>

<!--
TRANSITION: "Now — how does it actually work?"
-->

<!-- ═══ ACT 5: UNDER THE HOOD ════════════════════════════════════════════════ -->

---
layout: default
title: "Instrumentation Pipeline"
class: slide-base
---

## One `invokestatic` per method entry.

<InstrumentationPipeline />

<!--
The entire recording mechanism is one static method call injected at method entry.
Everything else (thread-local bitset, batch flush, index server) flows from this single hook.
-->

---
layout: default
title: "Instrumentation Modes"
class: slide-base
---

## Three modes. Similar overhead.

| Mode | Tracks | Precision | Index size |
|------|--------|-----------|-----------|
| `CLASS` | Which classes load | Coarsest | Smallest |
| `METHOD` | Per-method calls | Medium | Medium |
| `MEMBER` *(default)* | Field + method accesses | Most precise | Largest |

<div class="mt-6 text-base opacity-70">
  All three modes have similar overhead (~11–13%). The real differentiator is precision.<br/>
  Use <code>CLASS</code> for huge legacy suites where index size matters. <code>MEMBER</code> works everywhere.
</div>

---
layout: default
title: "ASM Injection"
class: slide-base
---

## ASM · a handful of bytes per method

```java
// Before instrumentation
public void remove() {
    last = null;
    iterator.remove();
}

// After — ASM injects at method entry
public void remove() {
    UsageStore.recordMemberUsageIdFast(42);  // ← injected: one invokestatic
    last = null;
    iterator.remove();
}
```

<!--
ASM operates at bytecode level — faster than Javassist, no runtime dependency.
The integer ID maps back to the class/method in the index.
-->

---
layout: default
title: "D4 — meta-agent Live Demo"
class: slide-base
---

<DemoCard id="D4" title="See the injection live">

```bash
cd third-party/spring-petclinic
./learn-with-meta-agent.sh --meta-agent
# browser opens → http://localhost:7071/classes
```

</DemoCard>

<!--
DEMO D4 — spring-petclinic learn run with meta-agent attached, server stays up after tests.

SETUP (run prepare-demo.sh before going on stage):
  meta-agent.jar present at third-party/spring-petclinic/.tools/meta-agent.jar
  petclinic index PRESENT (warm from D1 or snapshot) so tests run fast

STEPS:
1. ./learn-with-meta-agent.sh --meta-agent
   — browser opens automatically to http://localhost:7071/classes
   — tests finish in ~5s; server stays up (keepalive)
2. In browser: click any class (e.g. VisitController)
   — show the "Original" and "Transformed" decompiled panels side-by-side
   — point at the injected UsageStore.recordMemberUsageIdFast(…) call at method entry
3. Open http://localhost:7071/instrumentators
   — shows test-order's ClassFileTransformer registered
4. Click Stop in the browser nav to exit the JVM

NARRATE:
- "We just saw the bytecode diff on a slide. Here it is on a real class."
- "meta-agent wraps any ClassFileTransformer and decompiles before and after."
- "That call at the top of every method — that's what records the dep map."
- "Stop exits the server cleanly."

FALLBACK: screenshot of meta-agent /full-diff/class?pattern=VisitController in public/images/meta-agent-diff.png
-->

---
layout: default
title: "Thread-Local Bitset"
class: slide-base
---

## Zero contention until test method ends

```
Thread 1: test A runs      [bitset: 0b10110100...]
Thread 2: test B runs      [bitset: 0b01001011...]
Thread 3: test C runs      [bitset: 0b11000010...]
                                        ↓
                           batch flush at test end
                                        ↓
                              index server (TCP)
```

<!--
Each test accumulates its dep hits in a thread-local bitset.
No synchronization during the test — only one flush per test method at the end.
"Zero overhead to the hot path."
-->

---
layout: default
title: "TCP Socket Batch"
class: slide-base
---

## Binary batch over TCP socket

<SocketBatchDiagram />

<!--
Index server runs in the build tool process (Maven/Gradle).
Each forked JVM flushes its bitset as a binary batch at the end of the test session.
TCP (not Unix socket) — works across fork boundaries.
-->

---
layout: default
title: "Index Format"
class: slide-base
---

## Radix trie + RoaringBitmap

<div class="grid grid-cols-2 gap-8 mt-6">
<div>

**Radix trie** maps class FQCNs → integer IDs

- Package prefixes shared → compact storage
- DFS traversal gives same-package classes contiguous IDs

</div>
<div>

**RoaringBitmap** stores the dep set per test

- Compressed: contiguous IDs → run containers
- Fast diff on git change: XOR two bitmaps

</div>
</div>

---
layout: default
title: "Dep Graph Diagram"
class: slide-base
---

## The index: test → class → method edges

<DepGraphDiagram />

<!--
Each test node has a set of class IDs it touched.
At METHOD/MEMBER level, the edges go deeper — to individual method invocations.
This is what makes depOverlapPoints precise: it counts exact class-level overlaps with changed_classes.
-->

---
layout: default
title: "Selective Learn"
class: slide-base
---

## BFS selective re-learn · 4-hop limit

<SelectiveLearnDiagram />

<!--
When testorder.learn.selective=true:
BFS expands from changed classes through the static call graph up to 4 hops.
Only classes reachable from the change are re-instrumented.
Unchanged classes reuse their existing dep records.
"You only pay for what changed."
-->

---
layout: default
title: "Scoring Signals"
class: slide-base
---

<h2 class="!text-2xl !mb-1">8 signals. Three tiers.</h2>

<div class="scoring-table">

| Signal | Weight | Why |
|--------|--------|-----|
| `depOverlapPoints` | **8** | Direct dep overlap — strongest predictor |
| `failurePoints` | **4** | Recent failure history |
| `newTestPoints` | 2 | Never run before — unknown risk |
| `changedTestPoints` | 2 | Test file itself changed |
| `setCoverPoints` | 2 | Unique coverage not in higher-ranked tests |
| `packageProximityPoints` | 2 | Same package as changed class |
| `nameMatchPoints` | 1 | Name heuristic (e.g. FooTest ↔ Foo) |
| `speedPoints` | −1 to −3 | Penalise slow tests unless highly relevant |

</div>

<div class="text-sm opacity-50 mt-3">Weights are defaults — <code>mvn test-order:optimize</code> tunes them against your run history.</div>

<!--
Source: Yoo & Harman 2012 — failure history + code churn = two strongest predictors.
Removed from table for clarity: complexityPoints (1), staticFieldPoints (1), killRatePoints (1) — minor contributors, rarely the deciding factor.
test-order:optimize tunes these weights using a genetic algorithm against your run history.
-->

---
layout: default
title: "test-order:optimize"
class: slide-base
---

## `test-order:optimize`

```
[test-order] optimize: running genetic algorithm over 47 historical runs...
[test-order] population=150, generations=200, fitness=APFD
[test-order] best weights: depOverlap=9.2, failure=4.8, speed=-2.1, ...
[test-order] APFD improvement: 0.71 → 0.84
```

<!--
Jenetics-based genetic algorithm. Population of 150 weight vectors.
Fitness function: APFD over your run history.
"The default weights work well. This makes them work better for your specific project."
TRANSITION: "There's a second problem in your test suite. And reordering is what reveals it."
-->

<!-- ═══ ACT 6: OD TESTS ═══════════════════════════════════════════════════════ -->

---
layout: center
title: "OD Tests Intro"
---

<div class="big-statement">
  There's a second problem<br/>in your test suite.
</div>

<!--
Show-of-hands #3: "Has anyone had a test that passes alone but fails in the suite — or vice versa?"
Feed: "Some of those are order-dependent tests. Not flaky. Not broken. Just wrong order."
Alphabetical ordering hides them — they only surface when order changes.
-->

---
layout: default
title: "OD Research"
class: slide-base
---

## Order-dependent tests are more common than you think

<div class="grid grid-cols-2 gap-6 mt-6">
  <div class="p-4 rounded-xl bg-zinc-800/60 border border-zinc-700">
    <strong>Luo et al. ICSE 2014</strong><br/>
    <span class="opacity-70">4.56% of open-source test suites had OD failures. Victim/brittle taxonomy.</span>
  </div>
  <div class="p-4 rounded-xl bg-zinc-800/60 border border-zinc-700">
    <strong>Shi et al. FSE 2019</strong><br/>
    <span class="opacity-70">Setter/polluter refinement — not just brittle, but the specific cause.</span>
  </div>
  <div class="p-4 rounded-xl bg-zinc-800/60 border border-zinc-700">
    <strong>Bell et al. ASE 2015</strong><br/>
    <span class="opacity-70">Random shuffle: ~60% detection. Isolation probes confirm root cause.</span>
  </div>
  <div class="p-4 rounded-xl bg-zinc-800/60 border border-zinc-700">
    <strong>Li et al. ISSTA 2023</strong><br/>
    <span class="opacity-70">Tuscan square design: 97.2% detection in ~105 runs.</span>
  </div>
</div>

---
layout: center
title: "Option 1: Brute Force"
---

## Option 1: Brute force

<div class="big-statement mt-6">O(N!)</div>

<div class="text-2xl opacity-70 mt-6 text-center">
  N=10 → 3,628,800 permutations<br/>
  N=50 → 3 × 10<sup>64</sup>
</div>

<!--
"Untenable for any real suite. You'd still be running this talk in heat death of the universe."
-->

---
layout: center
title: "Option 2: Random Shuffle"
---

## Option 2: Random shuffle

<div class="grid grid-cols-2 gap-12 mt-8 text-center max-w-2xl mx-auto">
  <div>
    <div class="text-4xl font-bold text-green-400">~60%</div>
    <div class="opacity-70 mt-2">detection rate<br/>(Bell et al. 2015)</div>
  </div>
  <div>
    <div class="text-4xl font-bold text-red-400">~40%</div>
    <div class="opacity-70 mt-2">missed<br/>correlated patterns</div>
  </div>
</div>

<!--
"Cheap. Better than nothing. But misses 40% — including correlated patterns where two tests must be adjacent to trigger the failure."
-->

---
layout: default
title: "Option 3: Tuscan Square"
class: slide-base
---

## Option 3: Tuscan square

<TuscanSquareViz />

<div class="mt-6 text-center">
  N=50 → <strong>50 runs</strong> · every ordered pair covered<br/>
  vs brute force: <strong>3 × 10<sup>64</sup> permutations</strong>
</div>

<!--
Pause after "3 × 10^64". Let the comparison land.
"A Tuscan square is a combinatorial design that guarantees every ordered pair (A before B) appears in exactly one row.
N tests → N runs. That's the whole trick."
-->

---
layout: default
title: "detect-dependencies Algorithm"
class: slide-base
---

## How `detect-dependencies` works

<DetectDepsFlow class="mt-6" />

<div class="mt-5 grid grid-cols-2 gap-x-8 text-sm text-slate-400">
<div>Phase 1 runs N orderings (Tuscan square) — every ordered pair is covered. Any test that flips pass→fail is a <em>potential victim</em>.</div>
<div>Phase 2 runs surgical isolation probes: T alone, then with each predecessor. Classify as <strong class="text-slate-300">victim</strong> (polluter breaks it) or <strong class="text-slate-300">brittle</strong> (depends on prior state).</div>
</div>

<!--
The algorithm combines the Tuscan systematic coverage with targeted isolation probes.
"You don't run 3×10^64 permutations. You run N orderings, then surgical probes."
-->

---
layout: center
title: "OD Detection Result"
---

<div class="big-statement">
  97.2% detection rate.
</div>

<div class="text-xl opacity-70 mt-6 text-center">
  Run before release. Fix the test, not the order.
</div>

<!--
Li et al. ISSTA 2023.
"If it finds something: the test has a hidden dependency on execution order.
That's a bug in the test — fix the test."
-->

---
layout: default
title: "detect-dependencies Goal"
class: slide-base
---

## `mvn test-order:detect-dependencies`

```
[test-order] Generating Tuscan square for 47 tests...
[test-order] Running 47 orderings...
[test-order] Potential victims: UserServiceTest, CartTest
[test-order] Running isolation probes...
[test-order] FOUND: CartTest is a victim — polluted by UserServiceTest
[test-order] Suggested fix: CartTest.tearDown() should reset UserRepository
```

<!--
The goal runs the full Tuscan + exclusion probe algorithm.
Output names the victim, the polluter, and suggests a fix location.
"Run this before a release. Not on every commit — it's expensive. If it finds something, fix the test."
TRANSITION: "So — where does this leave us?"
-->

<!-- ═══ CLOSING ══════════════════════════════════════════════════════════════ -->

---
layout: center
title: "Closing — Proof of Concept"
---

<div class="big-statement">
  The technique works.<br/>
  The research is solid.<br/>
  The tool is a proof of concept.
</div>

<div class="mt-8 text-center opacity-70">
  v0.1.0 · Maven Central · Apache 2.0 · open for experimentation
</div>

<!--
Honest framing: not production-ready, not used in production anywhere.
This is a research prototype demonstrating the approach is viable.
"Try it on a project you own. File issues. The approach is sound — the tool needs real-world feedback to mature."
-->

---
layout: default
title: "Three Takeaways"
class: slide-base
---

## Three things to take away

<div class="grid grid-cols-1 gap-5 mt-6 max-w-2xl mx-auto">
  <div class="p-5 rounded-xl bg-zinc-800/60 border border-zinc-700 text-lg">
    ① Tests closest to the change fail first. This is a law, not a heuristic.
  </div>
  <div class="p-5 rounded-xl bg-zinc-800/60 border border-zinc-700 text-lg">
    ② One line of config to try it. Everything else is automatic.
  </div>
  <div class="p-5 rounded-xl bg-zinc-800/60 border border-zinc-700 text-lg">
    ③ It works on real bugs at scale. The benchmark shows the full picture — misses included.
  </div>
</div>

---
layout: center
title: "Callback"
---

<div class="big-statement">One line in pom.xml.</div>

<div class="text-xl opacity-70 mt-6 text-center">
  That's it. The runner figures out the rest.
</div>

<!--
Callback to the opening demo. "Remember position 237? That was before this line existed."
-->

---
layout: center
title: "Q&A"
---

# Questions?

<div class="grid grid-cols-3 gap-8 mt-10 text-center">
  <div>
    <div class="text-sm opacity-60 mb-2">Docs</div>
    <img src="/images/test-order-qr.png" class="w-24 h-24 mx-auto" />
  </div>
  <div>
    <div class="text-sm opacity-60 mb-2">GitHub Issues</div>
    <img src="/images/feedback-qr.png" class="w-24 h-24 mx-auto" />
  </div>
  <div>
    <div class="text-sm opacity-60 mb-2">SapMachine</div>
    <img src="https://api.qrserver.com/v1/create-qr-code/?size=180x180&data=https://sapmachine.io&bgcolor=18181b&color=ffffff&qzone=1" class="w-24 h-24 mx-auto rounded-lg" />
  </div>
</div>

<!--
Open for questions. Point to the QR codes for docs, issues, and SapMachine.
-->
