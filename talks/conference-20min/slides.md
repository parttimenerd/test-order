---
theme: seriph
title: "You Are Running the Wrong Tests First"
info: |
  test-order — 20-minute conference talk.
  Local-first, zero-config test prioritization for Maven and Gradle.
class: text-center
highlighter: shiki
lineNumbers: false
drawings:
  persist: false
transition: slide-left
mdc: true
fonts:
  sans: 'Inter'
  serif: 'Source Serif Pro'
  mono: 'JetBrains Mono'
layout: cover
---

<style>
.big-statement {
  font-size: 3rem;
  font-weight: 700;
  line-height: 1.3;
  text-align: center;
}

.quote-slide {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  text-align: center;
  padding: 6rem 4rem;
  position: relative;
}
.quote-slide::before {
  content: '\201C';
  position: absolute;
  left: 40px;
  top: 40%;
  transform: translateY(-50%);
  font-size: 15rem;
  font-weight: 900;
  color: rgba(148, 113, 217, 0.18);
  line-height: 1;
  z-index: 0;
}
.quote-text {
  font-size: 1.85rem;
  line-height: 1.7;
  font-style: italic;
  max-width: 820px;
  font-weight: 500;
  position: relative;
  z-index: 1;
}
.quote-attr {
  font-size: 1.05rem;
  opacity: 0.7;
  margin-top: 1.5rem;
  font-weight: 500;
}

.slidev-slide pre,
.slidev-slide .shiki {
  font-size: 1.05rem;
  line-height: 1.6;
  border-radius: 10px;
  padding: 18px 22px;
}

.pull-quote {
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

.hands-up {
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

.tag-bad {
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

.tag-ok {
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
</style>


<!-- ═══ COVER ═════════════════════════════════════════════════════════════════ -->

<img src="/images/wiki-bletchley-cards.jpg" class="absolute inset-0 w-full h-full object-cover opacity-25" />
<div class="absolute inset-0 bg-black/60 z-0" />

<div class="relative z-10">

# You Are Running the Wrong Tests First

<div class="pt-6 text-xl opacity-70">
  bytecode instrumentation · zero config · faster feedback · v0.1 · early-stage
</div>

<div class="pt-4 text-base opacity-40">
  Johannes Bechberger · @parttimenerd · SAP SE
</div>

</div>

<!--
- Punched cards at Bletchley Park — the original "sort by relevance" problem
- Don't introduce yourself yet. Pause. Let the title land
- Then the opening question, hand-raise
- Read the room before you commit to a tone
- TRANSITION: go straight into the CI-wait question, don't narrate the agenda
-->


<!-- ═══ HOOK ═══════════════════════════════════════════════════════════════════ -->

---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-apollo10-mission-control.jpg" class="absolute inset-0 w-full h-full object-cover opacity-15" />
<div class="absolute inset-0 bg-zinc-900/75 z-0" />

<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

Who's waited 20 minutes for CI<br/>
to tell you the test that failed<br/>
was the first thing you changed?

</div>

<div class="hands-up mt-10">✋ raise your hand</div>

</div>

<!--
- Apollo 10 Mission Control — everyone waiting for a signal that takes 20 minutes
- PAUSE. Let hands go up. Don't rush it
- Follow-up: "keep your hand up if it happens more than once a week"
- "That's the problem. You already knew. The runner just didn't"
- TRANSITION: don't advance until you see hands — then cut to the timeline
-->


---
layout: default
---

<ApfdTimeline />

<div class="pt-3 text-2xl text-rose-400 font-bold text-center">
  You changed that file 22 minutes ago.
</div>

<v-click>

<div class="mt-3 pull-quote">
  The signal was already in the bytecode.
</div>

</v-click>

<!--
- The failing test exercised the class you just edited — we knew which one
- "Which test touches which class. We just never used it"
- This single sentence is the entire talk — say it slowly
- The 22-minute bar is the pain; the pull-quote is the promise
- TRANSITION: "before the fix, let me show you why nothing else works"
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-switchboard-1922.jpg" class="absolute inset-0 w-full h-full object-cover opacity-15" />
<div class="absolute inset-0 bg-zinc-900/78 z-0" />

<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

Default test order<br/>is alphabetical.

</div>

<v-click>

<div class="text-xl text-center mt-8 opacity-90">
  Surefire: alphabetical.<br/>
  JUnit 5: <em>"deterministic but nonobvious."</em>
</div>

</v-click>

<v-click>

<div class="text-2xl text-center mt-6 text-rose-400 font-bold">
  ZipUtilsTest runs before AuthServiceTest.<br/>
  You changed auth. You'll find out last.
</div>

</v-click>

</div>

<!--
- Maven Surefire orders by alphabetical class name — nothing to do with relevance
- JUnit 5 docs say "deterministic but intentionally nonobvious" verbatim
- Correlation between alphabetical order and relevance-to-your-change is zero
- The ZipUtils / AuthService example makes it felt, not just understood
- TRANSITION: "so people reach for tools — here's what they try"
-->


---
layout: default
---

# What people already try

<div class="mt-8 space-y-4">

<v-click>

<div class="flex items-center gap-4 text-xl">
  <span class="tag-ok">STRONG</span>
  <span><strong>Cloud TIA</strong> — Launchable, Develocity PTS</span>
</div>

</v-click>
<v-click>

<div class="flex items-center gap-4 text-xl">
  <span class="tag-ok">WORKS</span>
  <span><strong>Coverage-based</strong> — Skippy, OpenClover</span>
</div>

</v-click>
<v-click>

<div class="flex items-center gap-4 text-xl">
  <span class="tag-ok">SIMPLE</span>
  <span><strong>Manual <code>@Order</code></strong></span>
</div>

</v-click>
<v-click>

<div class="flex items-center gap-4 text-xl">
  <span class="tag-ok">EASY</span>
  <span><strong>Random / shuffle</strong></span>
</div>

</v-click>
<v-click>

<div class="flex items-center gap-4 text-xl">
  <span class="tag-ok">FAST</span>
  <span><strong>Stop-on-first-failure</strong></span>
</div>

</v-click>

</div>

<v-click>

<div class="hands-up mt-6">✋ tried any of these in the last year?</div>

</v-click>

<!--
- Acknowledge each approach honestly — they all solve a real problem.
- Cloud TIA (Develocity, Launchable): strongest at scale with long failure history. Needs months of data, a cloud pipeline, data egress. We win on day-1, cost, data residency.
- Coverage-based (Skippy, OpenClover): source instrumentation is fragile across refactors; good on stable codebases.
- Stop-on-first-failure: pairs well with test-order — we put the right test first, fail-fast cuts everything after.
- Our position: local, deterministic, zero config, useful from run 2.
- Coverage-based (Skippy, OpenClover): source instrumentation breaks on refactors, gaps = silent misses
- Manual @Order: doesn't scale past one developer, rots fast
- Random/shuffle: still 50% APFD, no better than alphabetical on average
- Stop-on-first-failure: saves wall time once a failure is found, doesn't surface it earlier
- SHOW OF HANDS #2 — scan the room, read the audience's sophistication
- "Cloud TIA wins on long-history teams with a budget. Our position: local, day-1, zero config"
- TRANSITION: "but the underlying idea is real — and old"
-->


<!-- ═══ THE IDEA ════════════════════════════════════════════════════════════════ -->

---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-fermi-blackboard.jpg" class="absolute inset-0 w-full h-full object-cover opacity-20" />
<div class="absolute inset-0 bg-zinc-900/70 z-0" />

<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

The signal is real.<br/>25 years of papers say so.

</div>

</div>

<!--
- Fermi at the blackboard — establishing something that looks obvious in retrospect
- This is NOT invented here — it's validated across decades of research
- Set up the four papers I'm about to walk through
- The point: we didn't invent the idea, we made it zero-config
- TRANSITION: "here's the trail"
-->


---
layout: default
---

# The research foundation

<v-click>

<div class="mt-4 p-4 rounded-lg bg-blue-950/50 border border-blue-800/50">
  <span class="font-bold text-blue-300">Rothermel et al. (1999)</span>
  <span class="ml-2 opacity-80">— founded TCP/RTS; defined APFD as the metric</span>
</div>

</v-click>
<v-click>

<div class="mt-3 p-4 rounded-lg bg-blue-950/50 border border-blue-800/50">
  <span class="font-bold text-blue-300">Yoo & Harman (2012)</span>
  <span class="ml-2 opacity-80">— 20-year survey; alphabetical order = <strong>50% APFD</strong> baseline</span>
</div>

</v-click>
<v-click>

<div class="mt-3 p-4 rounded-lg bg-rose-950/50 border border-rose-800/50">
  <span class="font-bold text-rose-300">Luo et al. (2014)</span>
  <span class="ml-2 opacity-80">— flaky tests corrupt failure history signal</span>
</div>

</v-click>
<v-click>

<div class="mt-5 pull-quote">
  25 years of research. The signals are real and well-understood.
</div>

</v-click>

<!--
- Rothermel 1999: founded test-case prioritization, defined APFD as the standard metric
- Yoo & Harman 2012: 20-year survey — failure history + code churn are the two strongest signals; alphabetical is 50% baseline
- Luo 2014: 51 Apache projects — 4.56% of Google TAP failures were flaky; grounds @QuarantinedTest feature
- TRANSITION: "But numbers from 2012 — what does this look like at production scale today?"
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-widener-card-catalog.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/82 z-0" />

<div class="relative z-10 flex flex-col items-center">

<div class="text-xl text-violet-300 font-semibold mb-6">Memon et al. / Google · 5.5M test targets · 2017</div>

<div class="big-statement">

91% of tests<br/>never fail.

</div>

<v-click>

<div class="text-xl text-center mt-8 opacity-90">
  The 9% that do fail are <em>closer</em> to recently changed code.
</div>

</v-click>

</div>

<!--
- PAUSE after "91% never fail." Let it land — 5 seconds.
- This isn't theory, it's Google's production measurement at 5.5M scale.
- "The 9% that do fail aren't random — they cluster near changes."
- This is the empirical basis for dep-overlap scoring. Not a heuristic — a measured fact.
- TRANSITION: show the exact quote from the paper
-->


---
layout: center
class: quote-slide bg-zinc-900 text-white
---

<img src="/images/wiki-widener-card-catalog.jpg" class="absolute inset-0 w-full h-full object-cover opacity-20" />
<div class="absolute inset-0 bg-zinc-900/72 z-0" />

<div class="quote-text relative z-10">
  "Very few of our tests ever fail, but those that do are generally 'closer' to the code they test."
</div>
<div class="quote-attr relative z-10">Memon et al. — Taming Google-Scale Continuous Testing, ICSE-SEIP 2017</div>

<!--
- This is their exact wording. "Closer" = shorter path in the dependency graph.
- We operationalize "closer" as: deps(test) ∩ changed_classes — set intersection.
- Bigger intersection → test is more likely to fail on this change → higher score.
- TRANSITION: "which leads to a hard logical claim"
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-bombe-wiring.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/80 z-0" />

<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

If a test hasn't touched<br/>the changed code,<br/>it <em>cannot</em> fail on this change.

</div>

</div>

<!--
- "Cannot" — not "probably won't." Cannot. Emphasize the word
- Measured at Google scale; we act on it locally with bytecode instrumentation
- This is the guarantee that makes affected-test selection sound
- TRANSITION: "let me show you how we detect what was touched — and how fast it is"
-->


<!-- ═══ HOW IT WORKS ═══════════════════════════════════════════════════════════ -->

---
layout: section
---

<img src="/images/wiki-eniac-programmers.jpg" class="absolute inset-0 w-full h-full object-cover opacity-20" />
<div class="absolute inset-0 bg-zinc-900/72 z-0" />

<div class="relative z-10 text-center">

# Record once. Score instantly. Zero retraining.

<div class="pt-4 opacity-70">bytecode instrumentation · dependency index · scoring</div>

</div>


---
layout: default
---

# Two runs. That's the whole model.

<PipelineDiagram />

<div class="mt-6 grid grid-cols-2 gap-4">

<v-click>

<div class="p-4 rounded-lg bg-blue-950/50 border border-blue-800/50 text-center">
  <div class="text-2xl font-bold text-blue-300">~13%</div>
  <div class="text-sm opacity-70 mt-1">learn-run overhead</div>
  <div class="text-sm mt-2">one call per method entry</div>
</div>

</v-click>
<v-click>

<div class="p-4 rounded-lg bg-green-950/50 border border-green-800/50 text-center">
  <div class="text-2xl font-bold text-green-300">0%</div>
  <div class="text-sm opacity-70 mt-1">order-run overhead</div>
  <div class="text-sm mt-2">agent never attaches</div>
</div>

</v-click>

</div>

<!--
- Walk the diagram left to right: first run we watch, every run after we rank
- Learn run ~13%: one invokestatic per method entry records which classes each test exercises
- Order run 0%: agent never attaches — ranking resolves before Surefire forks any JVM
- The two stat boxes land the key numbers without a table
- "The 0% is the number that matters in production"
- TRANSITION: "let's look inside the learn run"
-->


---
layout: two-cols
---

# Inside the learn run

```java {1|2|3-4|all}
public Money total() {
    UsageStore.recordUsageIdFast(4711);
    return items.stream()
        .map(Item::price)
        .reduce(ZERO, Money::add);
}
```

<div class="mt-4 text-sm opacity-60">
  5 bytes · pre-computed ID<br/>
  thread-local bitset<br/>
  no reflection
</div>

::right::

<div class="pl-6 pt-2">

<DepGraphDiagram />

<div class="text-sm opacity-60 mt-2">
  BFS from changed class
</div>

</div>

<!--
- Left: the injected call. Walk highlights — line 1 original method, line 2 injected, lines 3-4 original body
- 5 bytes: pre-computed integer ID, no string hashing, no proxy, no wrapping
- Right: BFS graph — we instrument only the reachable subgraph, not everything
- Beyond 4 hops every test has some connection; signal-to-noise inverts
- TRANSITION: "once we know what's touched, we score it"
-->


---
layout: default
---

# 7 signals, all configurable — dep overlap is the core

<ScoringBreakdown />

<v-click>

<div class="pull-quote mt-3">
  Dep overlap is the core signal — additive, not multiplicative.
</div>

</v-click>

<!--
- Split table: left column (high-weight signals) first, right revealed after
- If you only remember one line: dep overlap — √-normalized class intersection, higher = more overlap with the changed set
- Recent failure EMA: Yoo & Harman 2012's strongest secondary signal
- √-normalized overlap: a test with 1 dep shouldn't outrank one with 50 at the same ratio
- Additive means every term is independently debuggable — the dashboard shows each contribution
- TRANSITION: "and it takes ten lines to install"
-->


---
layout: default
---

# Installing: ten lines of POM

```xml {all|2-3|5|6-10}
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>test-order-maven-plugin</artifactId>
  <version>0.1.0</version>
  <extensions>true</extensions>
  <executions>
    <execution>
      <goals><goal>prepare</goal></goals>
    </execution>
  </executions>
</plugin>
```

<v-click>

<div class="pull-quote mt-4">
  Line 5 is the load-bearing one.<br/>
  Without <code>extensions=true</code>, no index gets written — learn mode silently does nothing.
</div>

</v-click>

<!--
- Click 1: highlight groupId/artifactId — "me.bechberger, test-order-maven-plugin"
- Click 2: highlight extensions=true — this is the #1 install mistake
- Click 3: highlight executions — prepare auto-detects: no index → learn, index found → order
- "mvn test works exactly as before — same Surefire config, same reports, same everything"
- TRANSITION: "enough slides — let me show it running"
-->


<!-- ═══ DEMOS ═══════════════════════════════════════════════════════════════════ -->

---
layout: section
---

<img src="/images/wiki-wacs-teletype.jpg" class="absolute inset-0 w-full h-full object-cover opacity-20" />
<div class="absolute inset-0 bg-zinc-900/72 z-0" />

<div class="relative z-10 text-center">

# Demos

<div class="pt-4 opacity-70">learn · rank shift · spring-petclinic + dashboard · affected</div>

</div>


---
layout: default
---

<DemoCard id="D1" duration="3:00" :cmd="`cd samples/sample-shop\nmvn test                  # learn run\nmvn test                  # order run — same command, order changed\nmvn test-order:show       # scores + why`" title="Zero to ordered in two commands" watch="Run #1: &quot;Auto-instrumenting 42 classes&quot;. Run #2: tests reordered, APFD printed. :show gives score + why column per test."></DemoCard>

<!--
DEMO STEPS:
1. cd samples/sample-shop
2. mvn test → "Auto-instrumenting 42 classes", all tests run normally
3. mvn test → same command, no flags, tests run in a different order, APFD line appears
4. mvn test-order:show → ranked table with Score and Why columns

- Type aloud. Don't paste
- After run #2: "same command. No flags. The order changed"
- After :show: point at the Why column — "every score is debuggable"
- FALLBACK: asciinema play public/demo.cast
- TRANSITION: while it runs, explain what's happening under the hood
-->


---
layout: default
---

<DemoCue>demo running — return at ":show output"</DemoCue>

# What's happening right now

<div class="mt-8 grid grid-cols-3 gap-6 text-center">

<v-click>

<div class="p-6 rounded-lg bg-blue-950/60 border border-blue-800/50">
  <div class="text-4xl mb-3">🔍</div>
  <div class="font-semibold text-blue-300 text-xl">Attach</div>
  <div class="text-sm opacity-60 mt-2">hooks class loader</div>
</div>

</v-click>
<v-click>

<div class="p-6 rounded-lg bg-violet-950/60 border border-violet-800/50">
  <div class="text-4xl mb-3">📝</div>
  <div class="font-semibold text-violet-300 text-xl">Record</div>
  <div class="text-sm opacity-60 mt-2">method entry → bitset</div>
</div>

</v-click>
<v-click>

<div class="p-6 rounded-lg bg-green-950/60 border border-green-800/50">
  <div class="text-4xl mb-3">💾</div>
  <div class="font-semibold text-green-300 text-xl">Write</div>
  <div class="text-sm opacity-60 mt-2">one <code>.lz4</code> file</div>
</div>

</v-click>

</div>

<v-click>

<div class="mt-8 text-center text-2xl font-semibold opacity-75">
  No cloud. No model. One file.
</div>

</v-click>

<!--
- Attach: bytecode transformer hooks into every class loader
- Record: every method entry writes (test → class) to a thread-local bitset
- Write: suite finishes → .test-order/test-dependencies.lz4
- Three cards replace a bullet list — easier to scan from the back row
- Final line lands the "it's just a file" simplicity — no cloud, no training pipeline
- TRANSITION: come back to the terminal when :show prints
-->


---
layout: default
---

# `:show` output — every field

```ansi {1|3-4|5-7|9-10}
$ mvn test-order:show

Changed classes: com.example.shop.Cart (1 uncommitted change)

 # │ Score │ Class                        │ Why
───┼───────┼──────────────────────────────┼──────────────────────────
 1 │  14.0 │ com.example.shop.CartTest    │ changed-test=9, overlap=2, pkg=2, speed=+1
 2 │   7.0 │ com.example.shop.ProductTest │ overlap=5, pkg-prox=2
 3 │   0.0 │ com.example.shop.InvoiceTest │ (no overlap) [SLOW 320ms]

[test-order] Run APFD: 92.9%  (first failure at test 1 of 3)
[test-order] Estimated time saved: 21 s vs. alphabetical order
```

<div class="mt-3 grid grid-cols-2 gap-2 text-xs">

<v-click>

<div class="p-2 rounded bg-zinc-800 border-l-2 border-orange-400">
  <span class="font-bold text-orange-300">APFD 0.929</span> — failure surfaced at 7% of suite wall time
</div>

</v-click>
<v-click>

<div class="p-2 rounded bg-zinc-800 border-l-2 border-green-400">
  <span class="font-bold text-green-300">Estimated time saved</span> — vs. alphabetical, assumes Ctrl-C on first failure
</div>

</v-click>

</div>

<!--
- Click 1: command — "nothing special, just mvn test-order:show"
- Click 2: "Changed:" line — test-order detected Cart.java is edited
- Click 3: ranked table — walk Why column: "changed-test=9 because CartTest.java itself was edited; overlap=2 because Cart is in CartTest's dep set; pkg=2 same package; speed=+1 fast test"
- Click 4: APFD + time saved — "92.9% APFD means the failure surfaced after 7% of wall time, not 80%; 21s saved on a tiny suite — scale that to a 22-minute suite"
- "SLOW 320ms on InvoiceTest → scores 0, runs last"
- "Every run prints APFD. That's your continuous proof."
- TRANSITION: "now watch what happens when I actually make a change"
-->


---
layout: default
---

# The one-line change

```java {all|3}
public void add(Item item) {
    // adding this:
    if (item == null) throw new IllegalArgumentException();
    items.add(item);
}
```

<v-click>

<div class="mt-4 p-3 rounded bg-zinc-800 border-l-4 border-orange-500 text-sm font-semibold">
  Cart is in CartTest's dep set. One line edit. Watch the rank move.
</div>

</v-click>

<!--
- Show the edit BEFORE opening the IDE — audience knows what to watch for.
- "I'll add exactly this. Then run mvn test."
- Click: "this is enough. The intersection already knows Cart changed."
Transition: switch to IDE.
-->


---
layout: default
---

<DemoCard id="D2" duration="2:00" :cmd="`# Add null-check to Cart.add() — one line\n$EDITOR src/main/java/com/example/shop/Cart.java\nmvn test\nmvn test-order:show`" title="Edit one method. Watch CartTest jump to #1." watch="Why column: changed-test=9, overlap=5. Score 14. Nothing retrained — a set intersection on the existing index."></DemoCard>

<!--
DEMO STEPS:
1. Open Cart.java in add(), insert: if (item == null) throw new IllegalArgumentException();
2. mvn test → "2 changed classes detected (uncommitted)", CartTest runs first
3. mvn test-order:show → CartTest rank #1, InvoiceTest rank last (score 0, SLOW)

- "No retraining. No ML pipeline. A set intersection"
- Demo steps: open Cart.java, add `if (item == null) throw new IllegalArgumentException();` in add(), save, run.
- PAUSE after the rank shift appears. Let the room react.
- FALLBACK: asciinema play public/demo.cast (skip to rank-shift section)
- TRANSITION: "want to see what's in that index? two commands"
-->


---
layout: default
---

# What's in the index?

```bash {1-2|4-6|8-10}
# After the learn run, the index is just a file:
ls -lh .test-order/test-dependencies.lz4   # ~12 KB

# Which classes did CartTest call?
java -jar ~/.m2/.../test-order-core-*-jar-with-dependencies.jar \
  deps .test-order/test-dependencies.lz4 com.example.shop.CartTest

# Output:
# com.example.shop.Cart
# com.example.shop.Invoice  … (8 classes total)
```

<v-click>

<div class="mt-4 p-3 rounded bg-zinc-800 border-l-4 border-emerald-500 text-sm font-semibold">
  Cart is in CartTest's deps. Cart.java changed. Score = 14. That's the whole story.
</div>

</v-click>

<!--
- This is the "it's just a file" moment — demystifies the black box.
- The CLI jar ships alongside the plugin. No separate install.
- If a test ranks unexpectedly, this is the first debugging step.
- TRANSITION: "that's a toy shop — let's do a real Spring project"
-->


---
layout: default
---

<DemoCard id="D3" duration="4:00" :cmd="`cd third-party/spring-petclinic\n# learn ran in CI last night — zero overhead today\nmvn test\nmvn test-order:dashboard`" title="Real Spring Boot project. Live APFD. Then the dashboard." watch="First failure surfaces early in terminal. Dashboard: APFD trend, rank heatmap, score breakdown modal, weights tuning."></DemoCard>

<!--
DEMO STEPS:
1. cd third-party/spring-petclinic
2. mvn test → "learn ran 2025-01-10, index loaded" → order run → APFD line printed
3. mvn test-order:dashboard → opens browser
4. Dashboard tour (90 seconds max):
   - Tests tab: score bar chart, Why column hover, run-history sparklines
   - Analytics tab: APFD timeline over runs, rank heatmap
   - Weights tab: drag "dep overlap" slider, watch CartTest rank change live

- "The learn run ran last night in CI. Today's run is zero overhead"
- "This is the normal workflow: learn once in CI, rank on every dev run"
- FALLBACK: screenshots dashboard-overview.png, analytics-tab.png, dashboard-weights.png
- TRANSITION: while the suite runs, cover the context on the next slide
-->


---
layout: default
---

<DemoCue>demo running — return at "Dashboard"</DemoCue>

# The learn run is the only cost you pay

<div class="mt-6 grid grid-cols-2 gap-6">

<v-click>

<div class="p-4 rounded-lg bg-zinc-800 border border-zinc-700">
  <div class="font-semibold text-orange-300 mb-2">Right now</div>
  <div class="text-sm opacity-80">First run on spring-petclinic — 40+ tests, full Spring context</div>
  <div class="text-sm text-orange-400 font-mono mt-1">This is the learn run. It runs once.</div>
</div>

</v-click>
<v-click>

<div class="p-4 rounded-lg bg-zinc-800 border border-zinc-700">
  <div class="font-semibold text-green-300 mb-2">Every run after this</div>
  <div class="text-sm opacity-80">learn already happened in CI last night</div>
  <div class="text-sm text-green-400 font-mono mt-1">zero overhead · just ordering</div>
</div>

</v-click>

</div>

<v-click>

<div class="mt-6 text-center text-lg font-semibold opacity-80">
  Watch for the APFD line — that number is the proof.
</div>

</v-click>

<!--
- "The learn run is the cost you pay once. In CI it runs overnight. Dev runs have zero overhead."
- Don't just fill time — make the CI workflow concrete while the suite runs
- If the suite finishes fast, skip v-clicks 2 and 3 and go straight to the dashboard
- TRANSITION: when the APFD line prints, open the dashboard
-->


---
layout: image-right
image: /images/dashboard-overview.png
backgroundSize: contain
---

# The dashboard

```bash
mvn test-order:serve
```

<div class="mt-4 space-y-3">

<v-click>

<div class="p-3 rounded bg-zinc-800 border-l-4 border-orange-500">
  <div class="font-semibold">Tests tab</div>
  <div class="text-sm opacity-70 mt-1">score breakdown per test</div>
</div>

</v-click>
<v-click>

<div class="p-3 rounded bg-zinc-800 border-l-4 border-violet-500">
  <div class="font-semibold">Analytics tab</div>
  <div class="text-sm opacity-70 mt-1">APFD trend · rank heatmap</div>
</div>

</v-click>
<v-click>

<div class="p-3 rounded bg-zinc-800 border-l-4 border-blue-500">
  <div class="font-semibold">Weights tab</div>
  <div class="text-sm opacity-70 mt-1">drag sliders · live re-rank</div>
</div>

</v-click>

</div>

<!--
- Tests tab: ranked list, score breakdown per test, run-history sparklines
- Analytics tab: APFD trend over runs, rank heatmap, failure correlation
- Weights tab: drag sliders, watch ranks change in real time
- Keep the tour brief — one tab each
- "The weights tab is for teams that want to tune. Day-one you don't touch it"
- The screenshot on the right gives context while you narrate
- TRANSITION: "ordering is one mode — the other is skipping"
-->


---
layout: default
---

<DemoCard id="D4" duration="2:00" :cmd="`mvn test-order:affected test`" title="Skip the unrelated tests entirely." watch="N test classes skipped. 1 runs. BUILD SUCCESS in &lt;2 s. Same change — only tests that could possibly fail."></DemoCard>

<!--
DEMO STEPS:
1. Still on the same change (sample-shop or spring-petclinic)
2. mvn test-order:affected test
3. Prints: "Skipped N test classes (no dependency overlap with changed code)"
4. Only the affected class(es) run, BUILD SUCCESS in seconds

- "Ordering moves relevant tests first. Affected selection skips unrelated tests entirely"
- "Use this in your inner dev loop. Full suite in CI"
- FALLBACK: type the expected output live as a code block
- TRANSITION: show the output and the safety guarantee
-->


---
layout: default
---

# Affected output

```
[INFO] Skipped 3 test classes (no dependency overlap with changed code)
[INFO] Running com.example.shop.CartTest
Tests run: 4, Failures: 0, Errors: 0
BUILD SUCCESS in 1.2 s
```

<v-click>

<div class="mt-6 grid grid-cols-2 gap-6">

<div class="p-4 rounded-lg bg-green-950/50 border border-green-800/50 text-center">
  <div class="text-3xl font-bold text-green-300">✓ false positives</div>
  <div class="text-sm opacity-70 mt-2">safe — extra tests run</div>
</div>

<div class="p-4 rounded-lg bg-red-950/50 border border-red-800/50 text-center">
  <div class="text-3xl font-bold text-red-300">✗ false negatives</div>
  <div class="text-sm opacity-70 mt-2"><strong>never happens</strong></div>
</div>

</div>

</v-click>

<!--
- The two-column layout makes the safety guarantee visual, not just stated
- False positives: extra tests run when overlap is ambiguous — safe
- False negatives: a relevant test skipped — never happens
- The dep index is an over-approximation of runtime coverage. Sound by construction
- TRANSITION: "so what does this buy you? Numbers"
-->


<!-- ═══ RESULTS ════════════════════════════════════════════════════════════════ -->

---
layout: fact
---

# 50% → 87–91%

<div class="pt-2 text-2xl opacity-80">
  APFD — failures surface in the first 20% of wall time
</div>

<div class="pt-6 text-base opacity-50 max-w-2xl mx-auto">
  Alphabetical / random baseline = 50% APFD · Yoo &amp; Harman 2012<br/>
  Measured across 7 benchmarked OSS repos (20+ in regression suite) · synthetic one-line bugs · rank of first failing test<br/>
  Average rank of first failing test: <strong class="text-white text-lg">1.4</strong>
</div>

<!--
- 50% APFD = alphabetical baseline (Yoo & Harman 2012 standard)
- 87–91% = test-order across commons-lang, jackson-core, okhttp, spring-ai, guava, netty…
- Average rank 1.4: when we miss #1, the failing test is still #2 almost every time
- PAUSE 3 seconds after advancing. Don't speak. Let it land
- TRANSITION: "let me make that concrete with real numbers"
-->


---
layout: default
---

# 7 benchmarked OSS repos — same result every time

<BenchmarkChart />

<v-click>

<div class="pull-quote mt-3">
  Synthetic one-line bugs. Reproducible: <code>scripts/third_party_test_plan.sh bugs commons-lang</code>
</div>

</v-click>

<!--
- Alphabetical baseline: always ~50%, exactly as Yoo & Harman predicted — it's a coin flip.
- test-order: 85–93% across utilities, Spring, AI libraries.
- Ranges = 5 injected bugs × 3 runs — varies by which class was touched.
- Real test counts from live indexes in this checkout (verified).
- TRANSITION: "there's one place this matters even more than CI"
-->


<!-- ═══ BEYOND ORDERING ════════════════════════════════════════════════════════ -->

---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-kuka-robot.jpg" class="absolute inset-0 w-full h-full object-cover opacity-20" />
<div class="absolute inset-0 bg-zinc-900/75 z-0" />

<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

An AI coding agent<br/>runs your test suite<br/>hundreds of times per session.

</div>

<v-click>

<div class="text-2xl text-orange-400 mt-8 text-center">
  Signal in the first 20%. The agent moves on.
</div>

</v-click>

<v-click>

<div class="text-base text-center mt-4 opacity-60 font-mono">
  Zero config change. It just runs <span class="text-emerald-300">mvn test</span>.
</div>

</v-click>

<v-click>

<div class="hands-up mt-6">✋ using an AI coding agent today?</div>

</v-click>

</div>

<!--
- SHOW OF HANDS #3
- KUKA robot — autonomous, repetitive, tireless
- Claude Code, Cursor, Copilot Workspace all run mvn test in a loop: fix → test → fix → test
- Every iteration pays the full suite cost today
- "80% earlier signal × hundreds of iterations = qualitatively different dev loop"
- "Zero config change for the agent — test-order intercepts Surefire transparently"
- TRANSITION: "it's not magic — here's where it breaks"
-->


<!-- ═══ HONEST TRADEOFFS ═══════════════════════════════════════════════════════ -->

---
layout: default
---

<img src="/images/wiki-lego-bricks.jpg" class="absolute inset-0 w-full h-full object-cover opacity-10" />
<div class="absolute inset-0 bg-zinc-900/85 z-0" />

<div class="relative z-10">

# When NOT to use this

<div class="mt-8 space-y-4">

<v-click>

<div class="flex items-center gap-4 text-xl">
  <span class="tag-bad">&lt; 20</span>
  <span><strong>Tiny suites</strong> — just parallelize</span>
</div>

</v-click>
<v-click>

<div class="flex items-center gap-4 text-xl">
  <span class="tag-bad">OPAQUE</span>
  <span><strong>Reflection-only paths</strong> — invisible to bytecode</span>
</div>

</v-click>
<v-click>

<div class="flex items-center gap-4 text-xl">
  <span class="tag-bad">DYNAMIC</span>
  <span><strong>Custom classloaders after JVM start</strong> — OSGi, Quarkus dev</span>
</div>

</v-click>
<v-click>

<div class="flex items-center gap-4 text-xl">
  <span class="tag-bad">FLAKY</span>
  <span><strong>Highly flaky suites</strong> — quarantine first</span>
</div>

</v-click>

</div>

<v-click>

<div class="mt-6 p-4 rounded-lg bg-amber-950/60 border border-amber-700 text-amber-200 text-base text-center">
  <strong>v0.1 — early-stage.</strong> Useful in CI today; not yet battle-tested at enterprise scale.
</div>

</v-click>

</div>

<!--
- Mirror the tag style from "what people already try" — consistent visual language
- Tiny suites (<20): instrumentation overhead isn't worth it; just parallelize
- Reflection-only: Class.forName, runtime-wired proxies invisible to the transformer
- Dynamic classloaders: OSGi, Quarkus dev mode, plugin systems loading after JVM start
- Flaky: corrupt the EMA failure-history signal; quarantine first with @QuarantinedTest (ABORTED not FAILED — build stays green)
- Note: Spring AOP and Mockito work fine — they go through bytecode
- "This earns trust. Nothing works everywhere"
- TRANSITION: "if it fits, here's how to start"
-->


<!-- ═══ CTA ════════════════════════════════════════════════════════════════════ -->

---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-loc-catalog.jpg" class="absolute inset-0 w-full h-full object-cover opacity-20" />
<div class="absolute inset-0 bg-zinc-900/72 z-0" />

<div class="relative z-10 flex flex-col items-center text-center">

<div class="big-statement">

You don't have to wait<br/>20 minutes anymore.

</div>

<v-click>

<div class="pt-10 text-2xl font-mono">
  install → reorder → measure → tune
</div>

<div class="pt-6 text-xl font-mono">
  github.com/parttimenerd/test-order
</div>

<div class="pt-5 text-base opacity-50">
  <span class="font-mono text-emerald-300">mvn test-order:diagnose</span> checks your setup · Apache 2.0 · v0.1 — early-stage, feedback welcome
</div>

</v-click>

</div>

<!--
- LOC librarians — orderly, finding the right thing fast
- Four words. The whole talk
- Install: ten lines of POM
- Reorder: mvn test twice. Failures surface earlier — typically in the first 20% on the benchmarked repos
- Measure: APFD on every run. Dashboard for trends
- Tune: weights tab
- SAY THE URL TWICE. It is on the recording
- TRANSITION: open the floor for questions
-->


<!-- ═══ Q&A ══════════════════════════════════════════════════════════════════════ -->

---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-loc-catalog.jpg" class="absolute inset-0 w-full h-full object-cover opacity-15" />
<div class="absolute inset-0 bg-zinc-900/80 z-0" />

<div class="relative z-10 flex flex-col items-center text-center">

<div class="big-statement">

Questions?

</div>

<div class="pt-10 text-xl font-mono opacity-90">
  github.com/parttimenerd/test-order
</div>

</div>

<!--
Keep this slide up for the full Q&A.

Likely questions:
- "Kotlin / Scala?" JVM bytecode — source language is irrelevant.
- "Parallel test execution?" Class-level: ordering is a scheduling priority, fine.
  Method-level: per-thread bitset, fine.
- "Can the index be wrong?" Over-approximation only — false positives, never false negatives.
- "Develocity PTS / Launchable?" Both are strong once you have months of failure history — ML signal is genuinely better at scale. We win on day-1 (no training data needed), data residency, and running cost.
  Machalica et al. (Facebook, ICSE 2019): 2× cost reduction with ML — but requires labelled failure data, a training pipeline, and data egress.
- "CI caching?" Cache `.test-order/` between runs (tiny) or commit to git.
- "How does APFD relate to wall time?" 22-min suite at 85% APFD = first failure at ~minute 3, not minute 18.
- "Order-dependent tests?" Li et al. (ISSTA 2023): Tuscan-square designs, 97.2% detection at ~105 test orders vs. n! brute force.
- "Why not just run tests in parallel?" Parallelism reduces total time; ordering reduces time-to-first-failure. They compose.
-->
