---
theme: seriph
title: "You Are Running the Wrong Tests First"
info: |
  test-order — 50-minute developer talk.
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
.big-statement p {
  font-size: 3rem !important;
  font-weight: 700 !important;
  line-height: 1.3 !important;
  text-align: center !important;
  width: 100% !important;
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

.big-statement {
  font-size: 3rem;
  font-weight: 700;
  line-height: 1.3;
  text-align: center;
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
</style>

<img src="/images/wiki-bletchley-cards.jpg" class="absolute inset-0 w-full h-full object-cover opacity-25" />
<div class="absolute inset-0 bg-black/60 z-0" />

<div class="relative z-10">

# You Are Running the Wrong Tests First

<div class="pt-8 text-xl opacity-60">
  Local, zero-config test prioritization for Java
</div>

<div class="abs-br m-6 text-sm opacity-50">
  Johannes Bechberger · @parttimenerd
</div>

</div>

<!--
- Don't introduce yourself. Don't preview the agenda.
- Open cold with the question on the next slide.
- Let the title sit for a beat.
Transition: straight into the show-of-hands question.
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-apollo10-mission-control.jpg" class="absolute inset-0 w-full h-full object-cover opacity-15" />
<div class="absolute inset-0 bg-zinc-900/78 z-0" />

<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

Who's waited 20 minutes for CI<br/>to tell you the test that failed<br/>was the first thing you changed?

</div>

</div>

<!--
- Pause. Let hands go up — most of the room.
- "You already knew. The runner just didn't."
- Don't move on until you see hands.
Transition: "Here's what that wait looks like."
-->


---
layout: default
---

<ApfdTimeline />

<div class="pt-4 text-2xl text-rose-400 font-bold text-center">
  You changed that file 22 minutes ago.
</div>

<v-click>

<div class="pt-3 text-lg text-center opacity-90">
  The signal was already there.
</div>

</v-click>

<!--
- The JVM bytecode already knows which test hits which class.
- We just never used that map.
- 36× faster time-to-feedback for this exact case.
- Pass case: same wall time, zero overhead.
Transition: quick intro of me.
-->


---
layout: default
---

# Hi. I'm Johannes.

<div class="pt-8 text-2xl font-semibold leading-relaxed">
  OpenJDK developer.<br/>
  I got annoyed enough to build something.
</div>

<div class="pt-8 text-base opacity-50">
  @parttimenerd · mostlynerdless.de
</div>

<!--
- 20 seconds max. SAP & SapMachine team.
- Built test-order as a side project.
- Too many lunch breaks waiting for CI.
Transition: "So why is CI this slow? Start with the order."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-switchboard-1922.jpg" class="absolute inset-0 w-full h-full object-cover opacity-15" />
<div class="absolute inset-0 bg-zinc-900/80 z-0" />

<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

Default test order is alphabetical.

</div>

<v-click>

<div class="text-xl text-center mt-8 opacity-90">
  JUnit 5: <em>"deterministic but intentionally nonobvious."</em>
</div>

</v-click>

</div>

<!--
- Maven Surefire: filesystem scan, alphabetical class name.
- JUnit 5 docs literally say "intentionally nonobvious."
- Nonobvious = no one promises it helps you.
- Correlation with "relevance to your change" = 0.
Transition: make it concrete with two class names.
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-hollerith-leiden.jpg" class="absolute inset-0 w-full h-full object-cover opacity-15" />
<div class="absolute inset-0 bg-zinc-900/80 z-0" />

<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

ZipUtilsTest runs<br/>before AuthServiceTest.

</div>

<v-click>

<div class="text-2xl text-center mt-8 opacity-90">
  You changed auth. You'll find out last.
</div>

</v-click>

</div>

<!--
- Concrete. Makes the problem felt.
- Alphabetical Z-before-A only if... invert the example live if needed.
Transition: "So what do people do about it?"
-->


---
layout: default
---

# What people already try

<div class="mt-6 space-y-3">

<v-click>

<div class="flex items-start gap-3 text-lg">
  <span class="tag-bad">PAID</span>
  <span><strong>Cloud TIA</strong> (Launchable, Develocity PTS) — data leaves your network</span>
</div>

</v-click>
<v-click>

<div class="flex items-start gap-3 text-lg">
  <span class="tag-bad">FRAGILE</span>
  <span><strong>Coverage-based</strong> (Skippy, OpenClover) — breaks on refactors</span>
</div>

</v-click>
<v-click>

<div class="flex items-start gap-3 text-lg">
  <span class="tag-bad">STALE</span>
  <span><strong>Manual <code>@Order</code></strong> — stale within a sprint</span>
</div>

</v-click>
<v-click>

<div class="flex items-start gap-3 text-lg">
  <span class="tag-bad">GUESS</span>
  <span><strong>Random / shuffle</strong> — 50% APFD, no better than alphabetical</span>
</div>

</v-click>
<v-click>

<div class="flex items-start gap-3 text-lg">
  <span class="tag-bad">INCOMPLETE</span>
  <span><strong>Stop-on-first-failure</strong> — cuts time, doesn't surface the right failure first</span>
</div>

</v-click>

</div>

<!--
- Don't dismiss these. Each is real.
- Cloud TIA wins on long-history projects — paid, data leaves your network.
- Stop-on-first-failure cuts wall time but doesn't change WHICH test runs first.
- test-order complements fail-fast: first failure is the right one.
- Our position: local, deterministic, zero config, day-1 useful.
Transition: "The idea isn't new — 25 years of research."
-->


---
layout: section
---

<img src="/images/wiki-fermi-blackboard.jpg" class="absolute inset-0 w-full h-full object-cover opacity-20" />
<div class="absolute inset-0 bg-zinc-900/72 z-0" />

<div class="relative z-10 text-center">

# The Idea

<div class="pt-4 opacity-70">25 years of research, one weekend of plumbing</div>

</div>

<!--
- A few slides: foundation, then the claim.
Transition: "The signal is real."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-fermi-blackboard.jpg" class="absolute inset-0 w-full h-full object-cover opacity-15" />
<div class="absolute inset-0 bg-zinc-900/80 z-0" />

<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

The signal is real.<br/>25 years of papers say so.

</div>

</div>

<!--
- Rothermel et al. 1999: first formal TCP results.
- Yoo & Harman 2012: surveyed 20+ years. APFD is the standard metric.
- Point isn't "look at the research" — it's "we're not guessing."
Transition: walk the trail, one paper per slide.
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-agassiz-chalkboard.jpg" class="absolute inset-0 w-full h-full object-cover opacity-15" />
<div class="absolute inset-0 bg-zinc-900/80 z-0" />

<div class="relative z-10 flex flex-col items-center text-center">

<div class="text-2xl font-semibold text-blue-300 mb-6">Rothermel et al., 1999</div>

<div class="big-statement">

TCP is a real,<br/>measurable problem.

</div>

<v-click>

<div class="text-base text-center mt-6 opacity-60">
  → test-order: APFD is the metric we print on every run
</div>

</v-click>

</div>

<!--
- The founding test-case-prioritization paper.
- Defined the problem, the metrics, the safety criterion.
- Every subsequent paper cites it.
- Audience who knows TCP will nod here.
Transition: "Two decades later, someone surveyed all of it."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-agassiz-chalkboard.jpg" class="absolute inset-0 w-full h-full object-cover opacity-15" />
<div class="absolute inset-0 bg-zinc-900/80 z-0" />

<div class="relative z-10 flex flex-col items-center text-center">

<div class="text-2xl font-semibold text-blue-300 mb-6">Yoo & Harman, 2012</div>

<div class="big-statement">

Failure history + code churn<br/>= strong signal.

</div>

<v-click>

<div class="text-base text-center mt-6 opacity-60">
  → test-order: EMA failure decay + dep-overlap are our top two signals
</div>

</v-click>

</div>

<!--
- Canonical TCP/RTS survey — two decades of results.
- APFD becomes the standard metric here.
- The heuristics we use are the validated ones.
Transition: "Then Google measured it at scale."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-widener-card-catalog.jpg" class="absolute inset-0 w-full h-full object-cover opacity-15" />
<div class="absolute inset-0 bg-zinc-900/78 z-0" />

<div class="relative z-10 flex flex-col items-center text-center">

<div class="text-2xl font-semibold text-violet-300 mb-6">Memon et al. / Google, 2017</div>

<div class="big-statement">

Failing tests cluster<br/>near changed code.

</div>

</div>

<!--
- 5.5M test targets, 500K+ code changes.
- 91% of tests never fail — the failing ones are "closer" to the change.
- The most concrete number in the field. This is the evidence.
Transition: "One more — the flakiness angle."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-agassiz-chalkboard.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/82 z-0" />

<div class="relative z-10 flex flex-col items-center text-center">

<div class="text-2xl font-semibold text-rose-300 mb-6">Luo et al., 2014</div>

<div class="big-statement">

Order-dependency is<br/>12% of flaky root causes.

</div>

<v-click>

<div class="text-base text-center mt-6 opacity-60">
  → test-order: <code>@QuarantinedTest</code> + detect-dependencies mode
</div>

</v-click>

</div>

<!--
- 51 Apache projects, 201 flaky-test commits.
- Test order dependency: 12% of root causes.
- 74% of those fixed by cleaning shared state.
- Grounds our quarantine + OD-detection features.
Transition: "We built the zero-config version of all this."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-telegraph-tokyo.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/82 z-0" />
<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

We built the zero-config version.

</div>

</div>

<!--
- No ML pipeline, no cloud, no training data.
- Just the validated heuristics, run locally.
Transition: "Let me show you the one number that matters — 91%."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-widener-card-catalog.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/82 z-0" />
<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

91% of tests at Google<br/>never failed. Not once.

</div>

<v-click>

<div class="text-xl text-center mt-8 opacity-90">
  99 passing runs for every 1 that finds a bug.
</div>

</v-click>

</div>

<!--
- Memon et al., ICSE-SEIP 2017. Google's TAP system.
- 5.5M targets, 500K+ changes: "91.3% passed and never failed once."
- PASSED:FAILED ratio per code change = 99:1.
- Random ordering buries that 1%.
- Not a Google problem — every project above a certain size.
Transition: quote from the same paper.
-->


---
layout: center
class: quote-slide bg-zinc-900 text-white
---

<img src="/images/wiki-widener-card-catalog.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/80 z-0" />
<div class="relative z-10 flex flex-col items-center justify-center">

<div class="quote-text">
"Very few of our tests ever fail, but those that do are generally 'closer' to the code they test."
</div>
<div class="quote-attr">Memon et al. — Taming Google-Scale Continuous Testing, ICSE-SEIP 2017</div>

</div>

<!--
- The empirical foundation for dep-overlap scoring.
- "Closer" = shorter path in the dependency graph.
- We operationalize it as set intersection: deps(test) ∩ changed_classes.
- Bigger overlap → higher score.
Transition: "Which leads to the logical core."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-bombe-wiring.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/82 z-0" />
<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

If a test hasn't touched<br/>the changed code,<br/>it <em>cannot</em> fail on this change.

</div>

</div>

<!--
- Say it slowly. This is the logical foundation.
- "Cannot" — not "probably won't." Cannot.
- Google measured it at 5.5M scale; now we act on it.
Transition: "So how do we know what each test touches? Record it."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-hollerith-leiden.jpg" class="absolute inset-0 w-full h-full object-cover opacity-15" />
<div class="absolute inset-0 bg-zinc-900/80 z-0" />
<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

Record the map once.<br/>Use it forever.

</div>

<v-click>

<div class="text-xl text-center mt-8 opacity-90">
  One instrumented run. Zero overhead after.
</div>

</v-click>

</div>

<!--
- "Record the map" = one learn run with a Java agent.
- "Use it forever" = every subsequent run is uninstrumented.
Transition: "It's literally two runs."
-->


---
layout: default
---

# Two runs. That's the whole model.

<PipelineDiagram />

<!--
- Walk left to right: LEARN writes the index once, ORDER uses git diff + index on every run.
- index = for each test class, a set of class IDs it called — stored as RoaringBitmaps.
- Score step: set intersection of deps(test) ∩ changed_classes → overlap signal + other signals → additive total.
- "Same code + same diff + same index → same ranked order. Fully deterministic."
Transition: "And here's what it buys you."
-->


---
layout: fact
---

# 50% → 85–90%

<div class="pt-2 text-2xl opacity-80">
  APFD — failures surface in the first 20% of wall time.
</div>

<div class="pt-8 text-base opacity-50">
  Average Percentage of Faults Detected · 100% = all failures first · 50% = random / alphabetical<br/>
  Measured: synthetic one-line bugs · 20+ OSS repos · rank of first failing test
</div>

<!--
- The hero claim. Say it once, then silence for 3 seconds.
- 50% = random/alphabetical baseline (Yoo & Harman 2012).
- 85–90% = test-order, 20+ open-source repos.
- Method: inject a one-line bug, record rank of first failing test.
- Reproducible: scripts/third_party_test_plan.sh.
Transition: "Let me make that concrete with real project numbers."
-->


---
layout: default
---

# Benchmarks: 20+ OSS repos

<BenchmarkChart />

<v-click>

<div class="pull-quote mt-2">
  Method: synthetic one-line bugs · rank of first failing test · 5 bugs × 3 runs each.<br/>
  Reproducible: <code>scripts/third_party_test_plan.sh bugs commons-lang</code>
</div>

</v-click>

<!--
- Alphabetical APFD hovers at 50% — random baseline, exactly as Yoo & Harman predicted.
- test-order: 85–93% across utilities, networking, DI framework, AI libraries.
- These are ranges across 5 injected bugs × 3 runs — individual results vary by which class was changed.
- "scripts/third_party_test_plan.sh bugs commons-lang — anyone can reproduce this."
- Real test counts from the live indexes present in this checkout.
Transition: "Enough theory — what does a developer actually do?"
-->


---
layout: section
---

<img src="/images/wiki-wacs-teletype.jpg" class="absolute inset-0 w-full h-full object-cover opacity-20" />
<div class="absolute inset-0 bg-zinc-900/72 z-0" />

<div class="relative z-10 text-center">

# Adoption

<div class="pt-4 opacity-70">what a developer actually does</div>

</div>

<!--
- 18 minutes. Four live demos.
Transition: "Maven first — ten lines."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-telegraph-tokyo.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/82 z-0" />
<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

Maven: ten lines of POM.

</div>

</div>

<!--
- Next slide shows the code.
Transition: show the POM.
-->


---
layout: default
---

```xml {all|2-3|5|6-10}{lines:true}
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

<!--
- Ten lines, full Maven setup.
- Line 5 (extensions=true) is load-bearing — registers the lifecycle participant. #1 install mistake.
- `prepare` auto-detects: no index → learn; index found → order.
- `mvn test` works exactly as before.
Transition: "Let's run it from scratch."
-->


---
layout: default
---

# Demo 1 — Maven from scratch

<DemoCard id="D1" duration="5 min" :cmd="`cd samples/sample-shop
mvn test                  # learn run
mvn test                  # order run
mvn test-order:show       # scores + why`">
  <template #title>Zero to ordered in two commands</template>
  <template #watch>Watch: <code>.test-order/</code> appears after run #1. Run #2 shows tests in scored order with a why column.</template>
</DemoCard>

<div class="pt-4 text-sm opacity-50 text-center"><em>Switch to terminal tab #1.</em></div>

<!--
- Type commands aloud — don't paste.
- After #1: "Six files in .test-order/. That's everything."
- After #2: "Same command. No flags. The order changed."
- After :show: point at score + why columns. "Every score is debuggable."
- Fallback: asciinema play public/demo-d1.cast
Transition: "Here's what :show prints."
-->


---
layout: default
---

# What `:show` prints — every field explained

```ansi
$ mvn test-order:show

Changed: com.example.shop.Cart  (1 uncommitted edit)

 # │ Score │ Class                        │ Why
───┼───────┼──────────────────────────────┼──────────────────────────────────
 1 │  14.0 │ com.example.shop.CartTest    │ changed-test=9, overlap=2, pkg=2, speed=+1
 2 │   7.0 │ com.example.shop.ProductTest │ overlap=5, pkg-prox=2
 3 │   0.0 │ com.example.shop.InvoiceTest │ (no overlap)  [SLOW 320ms]

[test-order] Run APFD: 92.9%  (first failure at test 1 of 3)
[test-order] Estimated time saved: 21 s vs. alphabetical order
```

<div class="mt-4 grid grid-cols-2 gap-3 text-sm">

<v-click>

<div class="p-3 rounded bg-zinc-800 border-l-4 border-blue-500">
  <div class="font-mono text-blue-300 font-bold">Score</div>
  <div class="opacity-80 mt-1">Additive integer. Every signal visible in <code>Why</code>. Fully reproducible.</div>
</div>

</v-click>
<v-click>

<div class="p-3 rounded bg-zinc-800 border-l-4 border-violet-500">
  <div class="font-mono text-violet-300 font-bold">Why</div>
  <div class="opacity-80 mt-1">Per-signal breakdown: <code>changed-test</code>, <code>overlap</code>, <code>pkg-prox</code>, <code>speed</code>, <code>failure</code>…</div>
</div>

</v-click>
<v-click>

<div class="p-3 rounded bg-zinc-800 border-l-4 border-orange-500">
  <div class="font-mono text-orange-300 font-bold">APFD</div>
  <div class="opacity-80 mt-1">0–1. Fraction of the suite elapsed before the first failure. Printed every run.</div>
</div>

</v-click>
<v-click>

<div class="p-3 rounded bg-zinc-800 border-l-4 border-green-500">
  <div class="font-mono text-green-300 font-bold">Estimated time saved</div>
  <div class="opacity-80 mt-1">Time to first failure vs. alphabetical order. Assumes Ctrl-C on first failure.</div>
</div>

</v-click>

</div>

<!--
- "Changed:" line — which classes test-order detected as changed (the diff)
- Score column: additive integer — additive means every contributing term is independently debuggable.
- Why column: per-signal labels; "overlap=2" means 2 of CartTest's deps match the changed set.
- APFD: 1.0 = all failures before all passes. 0.5 = random baseline. 0.929 = first failure at 7% of wall time.
- "Estimated time saved" assumes developer Ctrl-C's after first failure — most realistic inner-loop scenario.
- SLOW tag on InvoiceTest: duration above suite median, zero overlap → last.
- "Every value is debuggable. If a test ranks unexpectedly, read the Why column."
Transition: "Now edit one file and watch the rank move."
-->


---
layout: default
---

# The edit we're about to make

```java {all|3}
public void add(Item item) {
    // add this one line:
    if (item == null) throw new IllegalArgumentException();
    items.add(item);
}
```

<v-click>

<div class="mt-4 p-3 rounded bg-zinc-800 border-l-4 border-orange-500 text-sm">
  One line. Cart is in CartTest's dep set. Watch the rank move.
</div>

</v-click>

<!--
- Show the code BEFORE switching to the IDE — sets expectations.
- "I'll add exactly this. Then run mvn test. That's it."
- The highlight animation walks: show the whole method first, then focus on line 3.
- After the click: "this one line is enough. The set intersection already knows."
Transition: switch to IDE / terminal.
-->


---
layout: default
---

# Demo 2 — edit → rank shift

<DemoCard id="D2" duration="2 min" :cmd="`# In Cart.java: change add() to reject null items
# e.g. add:  if (item == null) throw new IllegalArgumentException();
$EDITOR src/main/java/com/example/shop/Cart.java
mvn test
mvn test-order:show`">
  <template #title>Change one method. Watch CartTest jump to #1.</template>
  <template #watch>Why column: <code>changed-test=9, overlap=5</code>. Nothing retrained — score recomputed from the existing index and the git diff.</template>
</DemoCard>

<!--
- The demo where it clicks: no ML, no retraining — just set intersection.
- The edit gives CartTest BOTH the "changed test" signal (+9) AND the dep-overlap signal (Cart is in its deps).
- Score goes from ~5 (overlap only) to 14 (overlap + changed-test).
- Watch the room when CartTest moves to #1 before you even run it.
- Say explicitly: "I didn't retrain anything. The index was recorded last run. The score recomputes in milliseconds."
Transition: "Gradle is even shorter."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-telegraph-tokyo.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/82 z-0" />
<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

Gradle: three lines.

</div>

</div>

<!--
- Next slide shows the code. Same arc, different build tool.
Transition: show the Gradle block.
-->


---
layout: default
---

```groovy
plugins {
  id 'me.bechberger.test-order' version '0.1.0'
}
```

<div class="pt-8 text-2xl font-semibold">
  Same core, same scoring, same index.
</div>

<!--
- Thin wrapper. Identical behaviour to Maven.
- Multi-project builds share one .test-order/ at the root.
Transition: "Let's run the Gradle demo — fast."
-->


---
layout: default
---

# Demo 3 — Gradle

<DemoCard id="D3" duration="2 min" :cmd="`cd samples/sample-vintage-gradle
./gradlew test            # learn + order
./gradlew testOrderShow   # ranking`">
  <template #title>Three lines. Same result.</template>
  <template #watch>Point out: camelCase tasks, Gradle daemon stays warm, <code>.test-order/</code> at project root. Same APFD line.</template>
</DemoCard>

<!--
- DON'T repeat Maven narration. "Same data model, same scoring, same index."
- Show two diffs only: task naming + daemon warmup.
- Running long? Skip it: "Gradle is three lines — same outcome."
- Fallback: asciinema play public/demo-d3.cast
Transition: "What does the learn run actually cost?"
-->


---
layout: default
---

# Instrumentation modes

<InstrumentationOverhead />

<v-click>

<div class="pt-3 text-xl font-bold text-emerald-400 text-center">
  Order run overhead: 0%. No agent attached.
</div>

</v-click>

<!--
- You pay once, on the learn run. Overhead numbers are vs. spring-petclinic baseline 4.93s → ~5.5s.
- Every ordered run after is identical to uninstrumented.
- Default is MEMBER. Switch with -Dtestorder.instrumentation.mode=METHOD.
- The 0% is the important number — say it explicitly after the click.
Transition: "Here's a real project, pre-indexed."
-->


---
layout: default
---

# Demo 4 — spring-petclinic, pre-indexed

<DemoCard id="D4" duration="6 min" :cmd="`cd third-party/spring-petclinic
# learn ran last night in CI — zero overhead today
mvn test
mvn test-order:dashboard`">
  <template #title>Real project. Watch APFD live, then open dashboard.</template>
  <template #watch>Test order in terminal. First failure surfaces early. Then dashboard: APFD trend, run history, cache tab.</template>
</DemoCard>

<!--
- "Learn ran last night. Today's run is zero overhead." — normal CI workflow.
- Narrate the APFD line as it updates.
- Dashboard tour (90s): Tests tab (score bars, why, sparklines), Analytics (APFD trend, heatmap), Cache tab (deferred tests).
- Fallback: public/dashboard-overview.png, analytics-tab.png
Transition: "Six numbers on the dashboard KPI bar — let me decode them."
-->


---
layout: default
---

# The dashboard KPI bar — six numbers

<div class="mt-6 grid grid-cols-3 gap-4 text-sm">

<v-click>

<div class="p-4 rounded-lg bg-zinc-800 border border-zinc-700">
  <div class="font-bold text-orange-300 text-xl font-mono">APFD</div>
  <div class="opacity-80 mt-2">Fraction of suite elapsed before first failure. <code>0.92</code> = failure at 8% of wall time.</div>
</div>

</v-click>
<v-click>

<div class="p-4 rounded-lg bg-zinc-800 border border-zinc-700">
  <div class="font-bold text-rose-300 text-xl font-mono">Latest failures</div>
  <div class="opacity-80 mt-2">Count of tests that failed in the most recent run. Drives EMA scoring on the next run.</div>
</div>

</v-click>
<v-click>

<div class="p-4 rounded-lg bg-zinc-800 border border-zinc-700">
  <div class="font-bold text-green-300 text-xl font-mono">Pass streak</div>
  <div class="opacity-80 mt-2">Consecutive clean runs. High streak = lower failure-recency scores across the board.</div>
</div>

</v-click>
<v-click>

<div class="p-4 rounded-lg bg-zinc-800 border border-zinc-700">
  <div class="font-bold text-blue-300 text-xl font-mono">At-risk tests</div>
  <div class="opacity-80 mt-2">Tests with non-zero EMA failure score — still carrying history from a past failure.</div>
</div>

</v-click>
<v-click>

<div class="p-4 rounded-lg bg-zinc-800 border border-zinc-700">
  <div class="font-bold text-violet-300 text-xl font-mono">Time saved</div>
  <div class="opacity-80 mt-2">Cumulative: wall time to first failure vs. alphabetical across all tracked runs.</div>
</div>

</v-click>
<v-click>

<div class="p-4 rounded-lg bg-zinc-800 border border-zinc-700">
  <div class="font-bold text-yellow-300 text-xl font-mono">Health grade (A–F)</div>
  <div class="opacity-80 mt-2">Combined score: APFD trend + flaky rate + index freshness. A = tuned and effective.</div>
</div>

</v-click>

</div>

<!--
- Walk through each KPI in order: APFD → failures → streak → at-risk → saved → grade.
- "At-risk" is the one teams find most useful: shows which tests are 'hot' right now — recent failures are still influencing scoring.
- Health grade A–F: quick signal for managers; "if the grade drops to C, run optimize."
- Time saved is cumulative — shows ROI over the project lifetime.
- "None of these need config. They update every run automatically."
Transition: "Now the developer's view — how it can be this fast."
-->


---
layout: section
---

<img src="/images/wiki-eniac-programmers.jpg" class="absolute inset-0 w-full h-full object-cover opacity-20" />
<div class="absolute inset-0 bg-zinc-900/72 z-0" />

<div class="relative z-10 text-center">

# Under the Hood

<div class="pt-4 opacity-70">bytecode · data structures · performance engineering</div>

</div>

<!--
- 15 minutes. How the sausage is made.
- Audience has seen it work — now they'll understand WHY it's fast.
Transition: "I'm the developer — let me open it up."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-eniac-programmers.jpg" class="absolute inset-0 w-full h-full object-cover opacity-15" />
<div class="absolute inset-0 bg-zinc-900/80 z-0" />
<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

I am the developer.<br/>Let me show you<br/>how it actually works.

</div>

</div>

<!--
- Set expectations: deeper than the demos.
- "Here for theory? Stay. Want the POM? It's on the CTA slide."
Transition: "The entire instrumentation surface is one call."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-eniac-programmers.jpg" class="absolute inset-0 w-full h-full object-cover opacity-15" />
<div class="absolute inset-0 bg-zinc-900/80 z-0" />
<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

One <span class="font-mono text-emerald-300">invokestatic</span><br/>per method entry.

</div>

<v-click>

<div class="text-xl text-center mt-8 opacity-90">
  Five bytes. Pre-computed integer ID.
</div>

</v-click>

</div>

<!--
- ASM, not ByteBuddy. Streaming visitor, manual maxStack tracking.
- Thread-local bitset keyed by integer class IDs — ~50× faster than strings.
- This is the whole surface. One call per method. Nothing else.
Transition: "Here's the before/after."
-->


---
layout: two-cols
---

# The injection: ASM, not ByteBuddy

```java {all|3|4-5}
// BEFORE
public Money total() {
    return items.stream()
        .map(Item::price)
        .reduce(ZERO, Money::add);
}
```

```java {all|2}
// AFTER (ClassFileTransformer output)
public Money total() {
    UsageStore.recordUsageIdFast(4711);
    return items.stream()
        .map(Item::price)
        .reduce(ZERO, Money::add);
}
```

::right::

<div class="pl-6 pt-2 space-y-4">

<v-click>

<div class="p-3 rounded bg-zinc-800 border-l-4 border-blue-500">
  <div class="font-semibold text-blue-300 text-sm">Why ASM, not ByteBuddy?</div>
  <div class="text-sm opacity-80 mt-1">Streaming visitor. Zero classpath deps.</div>
</div>

</v-click>
<v-click>

<div class="p-3 rounded bg-zinc-800 border-l-4 border-green-500">
  <div class="font-semibold text-green-300 text-sm">Why integer IDs?</div>
  <div class="text-sm opacity-80 mt-1">~50× faster than strings.</div>
</div>

</v-click>
<v-click>

<div class="p-3 rounded bg-zinc-800 border-l-4 border-violet-500">
  <div class="font-semibold text-violet-300 text-sm">Five bytes</div>
  <div class="text-sm opacity-80 mt-1">Push + <code>invokestatic</code>. That's all.</div>
</div>

</v-click>

</div>

<!--
- "ByteBuddy builds a full object model per class. We visit the stream once."
- No full class model in memory; manual maxStack tracking.
- Integer IDs are the single biggest win — pre-computed before any test runs. Strings cost hashing, allocation, concurrent-map contention.
- Five bytes: iconst/bipush/sipush/ldc + invokestatic. Stack delta 0. No allocation, no GC pressure.
Transition: "Where do those IDs go? A thread-local bitset."
-->


---
layout: default
---

# Thread-local bitset: why it's fast

```java {all|1-2|4-5|7-9}
// Each test method gets a thread-local long[]
private static final ThreadLocal<long[]> BITS =
    ThreadLocal.withInitial(() -> new long[CLASS_COUNT / 64 + 1]);

// recordUsageIdFast: inlined by JIT — no method call overhead
public static void recordUsageIdFast(int classId) {
    long[] bits = BITS.get();
    bits[classId >>> 6] |= (1L << classId);  // set bit N
}
```

<div class="mt-6 grid grid-cols-3 gap-4 text-center">

<v-click>

<div class="p-3 rounded-lg bg-zinc-800 border border-zinc-700">
  <div class="text-lg font-bold text-green-300">No locks</div>
  <div class="text-sm opacity-70 mt-1">Zero contention across runners</div>
</div>

</v-click>
<v-click>

<div class="p-3 rounded-lg bg-zinc-800 border border-zinc-700">
  <div class="text-lg font-bold text-blue-300">~50×</div>
  <div class="text-sm opacity-70 mt-1">Faster than a string map</div>
</div>

</v-click>
<v-click>

<div class="p-3 rounded-lg bg-zinc-800 border border-zinc-700">
  <div class="text-lg font-bold text-violet-300">Aggregated once</div>
  <div class="text-sm opacity-70 mt-1">Per test method, not per call</div>
</div>

</v-click>

</div>

<!--
- Naive: ConcurrentHashMap<String, Set<String>>. Every call = hash + lookup + set.add. Contention under parallel runs.
- Ours: push an int, OR into a long array. JIT inlines it entirely.
- Bitset drained once when the test method ends — one aggregation per test.
- Thread-local means parallel Maven forks work out of the box.
Transition: "Don't take my word for it — see the injection live."
-->


---
layout: default
---

# Live: read the index yourself

```bash {1|3-4|6-8|10-12}
# After any learn run:
cd samples/sample-shop && mvn test -Dtestorder.mode=learn

# Which classes did CartTest call?
java -jar test-order-core-*-jar-with-dependencies.jar \
  deps .test-order/test-dependencies.lz4 \
  com.example.shop.CartTest

# Output:
# com.example.shop.Cart
# com.example.shop.Invoice
# com.example.shop.Money   … (8 classes)
```

<v-click>

<div class="mt-4 p-3 rounded bg-zinc-800 border-l-4 border-emerald-500 text-sm">
  The index is just a file. You can inspect it, diff it, commit it.
</div>

</v-click>

<!--
- This is the "try it yourself" moment — audience can do this on their laptop tonight.
- The CLI jar ships with the plugin. No extra install.
- "deps" subcommand: lists all classes a given test class called during its learn run.
- Practical: if a test ranks unexpectedly, check its deps list — often reveals a missing learn run or stale index.
- PAUSE after the click.
Transition: "The meta-agent makes this visual — let's see it."
-->


---
layout: default
---

# Demo 5 — meta-agent: see the injection live

<DemoCue>switching to browser · localhost:7071</DemoCue>

<DemoCard id="D5" duration="4 min" :cmd="`# meta-agent at localhost:7071
open http://localhost:7071/instrumentators
open http://localhost:7071/classes
open http://localhost:7071/full-diff/com.example.Cart`">
  <template #title>What does test-order actually do to your bytecode?</template>
  <template #watch>Vineflower decompilation diff — <code>UsageStore.recordUsageIdFast</code> at every method entry. Nothing else.</template>
</DemoCard>

<!--
- meta-agent instruments other agents' ClassFileTransformers, decompiles via Vineflower.
- /instrumentators — test-order-agent listed.
- /classes — every class touched this run.
- /full-diff/com.example.Cart — original vs. instrumented, side by side.
- "This is the entire footprint. One line per method. No proxy, no wrapping."
- Fallback: public/meta-agent-*.png
Transition: "So how does the index get to disk?"
-->


---
layout: default
---

# How the index gets to disk

<SocketBatchDiagram />

<v-click>

<div class="mt-4 grid grid-cols-2 gap-6">

<div class="p-4 rounded-lg bg-red-950/40 border border-red-800/50">
  <div class="font-semibold text-red-300 text-sm mb-2">Old design (abandoned)</div>
  <div class="text-sm opacity-80">Every fork reloaded + resaved the full index.</div>
</div>

<div class="p-4 rounded-lg bg-green-950/40 border border-green-800/50">
  <div class="font-semibold text-green-300 text-sm mb-2">Socket batch (current)</div>
  <div class="text-sm opacity-80">One binary write per fork at shutdown.</div>
</div>

</div>

</v-click>

<!--
- Surefire forks a JVM per test class — hundreds of forks in a big build.
- Old: each fork loaded the 5 MB index, merged, wrote back. 100–500 ms per fork → ~20s of pure I/O. Or 800 file cycles per fork in MEMBER mode.
- Current: fork sends one binary blob over a local socket at exit. Plugin aggregates, serializes once.
- Per-file fallback still exists for standalone agent use.
Transition: "What's actually in that file?"
-->


---
layout: default
---

# The index format

```
test-dependencies.lz4
├── LZ4 frame
│   ├── Magic: "TORD" (4 bytes)
│   ├── Format version: 1 (2 bytes)
│   ├── Section count (4 bytes)
│   ├── SECTION 1: ClassNameTrie   ← radix trie of all class names
│   ├── SECTION 2: TestClasses     ← ordered list of test class IDs
│   ├── SECTION 3: DepGroups       ← RoaringBitmap per test, row-deduplicated
│   ├── SECTION 4: MethodDeps      ← per-test-method dep bitmaps (optional)
│   └── SECTION 5: MemberDeps      ← exact field/method access (MEMBER mode)
```

<v-click>

<div class="mt-4 grid grid-cols-2 gap-4 text-sm">

<div class="p-3 rounded bg-zinc-800 border-l-4 border-blue-500">
  <div class="font-semibold text-blue-300">ClassNameTrie</div>
  <div class="opacity-80 mt-1">Shared prefixes stored once.</div>
</div>

<div class="p-3 rounded bg-zinc-800 border-l-4 border-violet-500">
  <div class="font-semibold text-violet-300">RoaringBitmap rows</div>
  <div class="opacity-80 mt-1">Identical dep sets share one entry.</div>
</div>

</div>

</v-click>

<!--
- Section-based: unknown sections skipped by length — forward compatible.
- "TORD" magic identifies the payload inside the LZ4 frame.
- ClassNameTrie: 500 classes sharing "com.example.shop." store that prefix once. Lookups O(name length).
- RoaringBitmap: compressed sparse bitset; cuts a 10k-class dense case 80–90%.
- Row-dedup: integration tests with identical dep sets point at one bitmap. Cuts index 30–60%.
Transition: "We can even skip instrumenting most of the codebase."
-->


---
layout: default
---

# Selective learn: BFS before the JVM starts

<SelectiveLearnDiagram />

<v-click>

<div class="mt-4 grid grid-cols-2 gap-4">

<div class="p-4 rounded-lg bg-zinc-800 border border-zinc-700">
  <div class="font-semibold mb-2">Why 4 hops?</div>
  <div class="text-sm opacity-70">Beyond 4, everything is reachable — noise.</div>
</div>

<div class="p-4 rounded-lg bg-zinc-800 border border-zinc-700">
  <div class="font-semibold mb-2">Why before the JVM?</div>
  <div class="text-sm opacity-70">Uncertain set must be known at attach time.</div>
</div>

</div>

</v-click>

<!--
- Full learn: instruments every loaded class. Simple, complete, ~13% overhead.
- Selective: git diff → BFS over the stored static call graph → whitelist of names to instrument.
- Empty whitelist = agent doesn't attach at all. `git pull` with no local edits: 0% overhead.
- 4-hop limit is empirical: measured signal-to-noise across OSS repos; curve flattens at 4.
- BFS runs over the existing index — no JVM needed.
Transition: "Five performance decisions made this fast."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/loc-scientist-chalkboard.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/82 z-0" />
<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

Integer IDs, not strings.

</div>

<v-click>

<div class="text-xl text-center mt-8 opacity-90">
  ~50× vs. ConcurrentHashMap&lt;String, Set&gt;
</div>

</v-click>

</div>

<!--
- First of five perf decisions.
- Pre-computed before the JVM starts.
- First iteration used class-name strings: 4000 string.equals() per test-method call in a 4000-class project.
- Worse under parallel runners.
Transition: "Second decision — thread-local bits."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/loc-scientist-chalkboard.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/82 z-0" />
<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

Thread-local bits.

</div>

<v-click>

<div class="text-xl text-center mt-8 opacity-90">
  Zero lock contention. Drained once per test.
</div>

</v-click>

</div>

<!--
- Second perf decision.
- No lock contention between parallel test runners.
- Drained once per test method, not per call site.
Transition: "Third — socket batch."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/loc-scientist-chalkboard.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/82 z-0" />
<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

Socket batch.

</div>

<v-click>

<div class="text-xl text-center mt-8 opacity-90">
  Killed the 100–500 ms per-fork round-trip.
</div>

</v-click>

</div>

<!--
- Third perf decision.
- One binary write per fork at shutdown.
- Eliminated the per-fork index reload/merge/resave round-trip.
Transition: "Fourth — in-JVM cache."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/loc-scientist-chalkboard.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/82 z-0" />
<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

In-JVM cache.

</div>

<v-click>

<div class="text-xl text-center mt-8 opacity-90">
  Skips LZ4 decompression in multi-module builds.
</div>

</v-click>

</div>

<!--
- Fourth perf decision.
- ConcurrentHashMap keyed by (path, mtime, size).
- A 100-module build calls load() 100× for the same 5 MB file.
- mtime in the key means stale reads are impossible.
Transition: "Fifth — frequency filter."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/loc-scientist-chalkboard.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/82 z-0" />
<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

Frequency filter.

</div>

<v-click>

<div class="text-xl text-center mt-8 opacity-90">
  Drop deps in &gt;80% of tests — pure noise.
</div>

</v-click>

</div>

<!--
- Fifth perf decision.
- Dropped at write time.
- Jackson's ClassUtil, log facades appear in ~99% of dep sets.
- They bloat the index, slow intersection, and score tests identically — no signal.
Transition: "Now the scoring formula itself."
-->


---
layout: default
---

# The scoring formula: design rationale

<div class="pt-2 grid grid-cols-2 gap-6">

<div>

| Signal | Weight | Rationale |
|--------|:------:|-----------|
| New test | **+15** | No history — learn it first |
| Changed test | **+9** | You edited it |
| Recent failure | 0–5 | EMA d=0.3 · Yoo & Harman |
| **Dep overlap** | **0–5** | √-norm · Memon et al. |

</div>

<v-click>

<div>

| Signal | Weight | Rationale |
|--------|:------:|-----------|
| Complexity | 0–2 | Deflate entropy proxy |
| Package proximity | +2 | Heuristic: co-location |
| Speed | ±1 | log₂-scale tie-break |
| Tie-break | — | Jaccard diversity |

</div>

</v-click>

<v-click>

<div class="mt-4 p-3 rounded bg-zinc-800 border-l-4 border-orange-500 text-sm">
  <strong>Additive, not multiplicative</strong> — each term is independently debuggable.
</div>

</v-click>

<!--
- Additive so I can see which signal betrayed the ordering — every term visible in the dashboard.
- Multiplicative lets one signal dominate silently; this can't.
- Overlap is √-normalized: a 1-dep test shouldn't outrank a 50-dep test at the same proportion.
- Jaccard tie-break: equal scores ordered by max dep-set distance. Breadth before redundancy.
- After 5 runs with failures, `mvn test-order:optimize` tunes weights via genetic algorithm over APFD history.
Transition: "What does that look like for a real test? One worked example."
-->


---
layout: default
---

# Scoring in practice: CartTest gets 14

```ansi
deps(CartTest) = {Cart, Invoice, Money, …} ← 8 classes
changed        = {Cart}                    ← 1 match
```

<ScoringBreakdown />

<!--
- Walk through each signal contribution for CartTest — the exact number shown in the :show output.
- "You edited CartTest.java itself — that's the +9."
- "CartTest calls Cart — overlap = 1 class out of 8 deps. √-normalized."
- "Same package com.example.shop — +2."
- "Runs in 80ms, suite median is 200ms — fast, so +1."
- Every term is visible. The dashboard shows this breakdown per test.
- InvoiceTest has 0 overlap, no edit, slower → scores 0.
Transition: "Back to the meta-agent — the mode diff."
-->


---
layout: default
---

# Demo 5 (cont.) — the meta-agent shows instrumentation mode diff

```ansi {1|3-4|6-8}
GET /full-diff/com.example.Cart

=== BEFORE ===
public Money total() { return items.stream().map(...).reduce(...); }

=== AFTER ===
public Money total() {
    UsageStore.recordUsageIdFast(4711);
    return items.stream().map(...).reduce(...);
}
```

<v-click>

<div class="mt-4 p-3 rounded bg-zinc-800 border-l-4 border-emerald-500 text-sm">
  One line per method. That's the entire footprint.
</div>

</v-click>

<!--
- Stays on screen while D5 runs — gives context for what they'll see.
- Vineflower decompiles the transformed bytecode on the fly.
- Every touched class at /classes; every instrumented method visible.
- "You can verify exactly what we inject. No hidden state, no side channels."
Transition: "Now the honest part — where it breaks."
-->


---
layout: section
---

<img src="/images/wiki-lego-bricks.jpg" class="absolute inset-0 w-full h-full object-cover opacity-18" />
<div class="absolute inset-0 bg-zinc-900/75 z-0" />

<div class="relative z-10 text-center">

# Honest Assessment

<div class="pt-4 opacity-70">where it breaks, what we're still building</div>

</div>

<!--
- 6 minutes. Earns more trust than any feature slide.
Transition: "When NOT to use this."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-lego-bricks.jpg" class="absolute inset-0 w-full h-full object-cover opacity-15" />
<div class="absolute inset-0 bg-zinc-900/80 z-0" />
<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

When NOT to use this.

</div>

</div>

<!--
- Next slide has the three cases. Let the title land.
Transition: reveal the three failure cases.
-->


---
layout: default
---

<div class="pt-8 grid grid-cols-3 gap-6 text-center">
  <div class="p-6 rounded-lg bg-rose-950 border border-rose-800 text-white">
    <div class="text-4xl font-bold text-rose-400">&lt; 20</div>
    <div class="pt-3 text-base opacity-80">tiny suites<br/>run them all in parallel</div>
  </div>
  <div class="p-6 rounded-lg bg-rose-950 border border-rose-800 text-white">
    <div class="text-4xl font-bold text-rose-400">⚙</div>
    <div class="pt-3 text-base opacity-80">reflection-only call paths<br/>we can't see them</div>
  </div>
  <div class="p-6 rounded-lg bg-rose-950 border border-rose-800 text-white">
    <div class="text-4xl font-bold text-rose-400">⚡</div>
    <div class="pt-3 text-base opacity-80">dynamic classloading<br/>Quarkus dev mode</div>
  </div>
</div>

<!--
- Tiny suites: no ordering headroom. Parallelism helps more.
- Reflection: Spring AOP and Mockito are fine — they go through bytecode. "Build the object graph from YAML at runtime" loses us.
- Dynamic classloading: classes loaded after JVM start aren't in the instrumentation window. Standard Surefire forks are fine.
Transition: "Ordering alone can't fix everything — here's what's beyond it."
-->


---
layout: section
---

<img src="/images/wiki-cat-reading.jpg" class="absolute inset-0 w-full h-full object-cover opacity-18" />
<div class="absolute inset-0 bg-zinc-900/72 z-0" />
<div class="relative z-10">

# Beyond Ordering

<div class="pt-4 opacity-60">the problems ordering alone can't solve</div>

</div>

<!--
- Ordering helps when deps are visible.
- These handle what ordering can't: flakiness, unchanged code, order-dependent state.
- All opt-in. Day-one you touch none of them.
Transition: "First — auto-retry."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-cat-reading.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/82 z-0" />
<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

Auto-retry

</div>

<div class="pt-10 flex justify-center">

```java
@RetryingTest(3)
void fetchRatesFromExternalApi() { … }
```

</div>

</div>

<!--
- InvocationInterceptor, JUnit 5.
- For tests that fail on DNS blip, pool saturation, rate-limit hiccup.
- Failed once? Re-run N times before reporting FAILED. ABORTED if all attempts fail.
- Network-adjacent tests stop breaking builds.
Transition: "Next — quarantine."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-cat-reading.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/82 z-0" />
<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

Quarantine

</div>

<div class="pt-8 text-xl text-center opacity-90">
  Flaky test throws <span class="font-mono text-emerald-300">TestAbortedException</span>: ABORTED, not FAILED.<br/>
  Build stays green. You fix it when you have time.
</div>

<div class="pt-6 text-base text-center opacity-60">
  4.56% of failures at Google TAP were flaky. · Luo et al., FSE 2014
</div>

</div>

<!--
- @QuarantinedTest. Tests still run — they just can't break the build.
- Luo et al. 2014: Google TAP had 73K flaky failures of 1.6M total (4.56%).
- "78% of flaky tests are flaky from the first time written" (Luo 2014).
- Flaky failures in the signal are noise — quarantine removes them before they corrupt the EMA decay.
Transition: "Next — skip-if-unchanged."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-cat-reading.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/82 z-0" />
<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

Skip-if-unchanged

</div>

<div class="pt-8 text-xl text-center opacity-90">
  Stable dep set + N-run pass streak → defer the test.<br/>
  Skip fraction capped at 90%.
</div>

</div>

<!--
- The cache. Same dep hash + no failures for N runs → "safe to skip."
- 90% cap: you can never accidentally defer the whole suite.
Transition: "And detecting order-dependent tests."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-cat-reading.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/82 z-0" />
<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

Order-dependent test detection

</div>

<div class="pt-8 text-xl text-center opacity-90">
  Tuscan-square combinatorial designs (Li et al., ISSTA 2023).<br/>
  97.2% detection rate. ~105 test orders. vs. <em>n!</em> for brute force.
</div>

</div>

<!--
- detect-dependencies mode.
- Li et al. 2023: "97.2% of known OD tests with 104.7 orders on average per subject." Brute force = n!.
- Tuscan squares give pair-coverage: every pair appears in both orderings at least once.
- "Static state leaking between tests? We find it."
Transition: "Back to the headline — does it actually catch bugs?"
-->


---
layout: fact
---

# 95%

<div class="pt-2 text-3xl opacity-90">bug-detection rate · 20+ OSS repos</div>

<div class="pt-6 text-2xl font-bold text-emerald-400">
  Average rank of first failing test: 1.4
</div>

<div class="pt-6 text-base opacity-60 max-w-2xl mx-auto">
  commons-lang · jackson-core · okhttp · netty · resilience4j · spring-ai · guava · logbook…<br/>
  Injected real-shaped one-line bugs. Checked rank of first failing test.
</div>

<!--
- 95% = rank #1 on the first run.
- Average rank 1.4 = when we miss #1, it's still #2 almost every time.
- MISSED: utility classes in nearly every test's dep set, or pure reflection paths.
- Reproduction: scripts/third_party_test_plan.sh — anyone can validate.
Transition: "And the 5% we miss? It's predictable."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-bombe-wiring.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/82 z-0" />
<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

The 5% that miss<br/>are predictable.

</div>

<v-click>

<div class="text-xl text-center mt-8 opacity-90">
  Universal utility classes — no discrimination signal.<br/>
  Reflection-only paths — invisible to bytecode.
</div>

</v-click>

</div>

<!--
- Earns credibility: naming the failure mode precisely, not hiding it.
- Average rank of a MISSED bug: still 1.4. MISSED means rank ≥ 2 but usually top 5.
- If your change is to a near-universal utility class: run the full suite. Otherwise trust the ordering.
Transition: "The bigger reason scale matters now: AI agents."
-->


---
layout: section
---

<img src="/images/wiki-kuka-robot.jpg" class="absolute inset-0 w-full h-full object-cover opacity-18" />
<div class="absolute inset-0 bg-zinc-900/72 z-0" />
<div class="relative z-10">

# At Scale

<div class="pt-4 opacity-60">from your laptop to 52 modules</div>

</div>

<!--
- The last demo section.
- All those features compound at scale.
- Two slides set up the agentic angle, then the multi-module demo.
Transition: "AI agents run your suite hundreds of times a session."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-kuka-robot.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/82 z-0" />
<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

An AI coding agent runs<br/>your test suite hundreds<br/>of times per session.

</div>

</div>

<!--
- Claude Code, Copilot Workspace, Cursor — all run `mvn test` in a loop. No lunch break.
- fix → test → fix → test → fix → test.
- Every iteration pays the full suite cost.
- Why scale matters NOW in a way it didn't 5 years ago.
Transition: "With ordering, the signal is in the first 20%."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-kuka-robot.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/82 z-0" />
<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

Signal in the first 20%.<br/>The agent moves on.

</div>

<div class="pt-8 text-xl text-center opacity-60">
  Zero config change. The agent just runs <span class="font-mono text-emerald-300">mvn test</span>.
</div>

</div>

<!--
- Compounding: 80% earlier signal × hundreds of iterations = a different dev loop.
- No integration needed — test-order intercepts Surefire/Gradle transparently.
Transition: "Now the theatrical setup — 65 modules."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-kuka-robot.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/82 z-0" />
<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

52 Maven modules.<br/>90 seconds.<br/>No test has started.

</div>

</div>

<!--
- Theatrical setup before the demo card. Don't rush.
- "This is real. I'll show the full build first, then the same change with test-order."
Transition: run Demo 6.
-->


---
layout: default
---

# Demo 6 — SAP CDS Services

<DemoCard id="D6" duration="5 min" :cmd="`cd third-party/cds-services
# The pain: kill at 90s — no test has started
mvn clean test
# The fix: affected-only on the same change
mvn test-order:affected test`">
  <template #title>52 Maven modules. Affected-only vs. full suite.</template>
  <template #watch><strong>Pain:</strong> 90 s, compile crawl, no test started.<br/><strong>Fix:</strong> ~55 s, RED build, right failure.</template>
</DemoCard>

<!--
- Run `mvn clean test`. Watch it crawl. At 90s: "Still compiling module 12 of 52. No test started." Ctrl-C. "That's the pain."
- Same change, plugin on: `mvn test-order:affected test`. ~55s, RED, right failure.
- After: open dashboard. Rank heatmap — failures clustered at the top.
- "Real production codebase at SAP. Not a sample."
- Fallback: D4 + public/dcom-rehearse-final.png
Transition: "Four words to take home."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-loc-catalog.jpg" class="absolute inset-0 w-full h-full object-cover opacity-15" />
<div class="absolute inset-0 bg-zinc-900/80 z-0" />
<div class="relative z-10 flex flex-col items-center">

<div class="big-statement text-center w-full">

install → reorder → measure → tune

</div>

<div class="pt-10 text-2xl font-mono text-center">
  github.com/parttimenerd/test-order
</div>

<div class="pt-6 text-base opacity-50 text-center">
  <span class="font-mono text-emerald-300">mvn test-order:diagnose</span> on your own project · Apache 2.0
</div>

</div>

<!--
- Four words, the whole talk.
- Install: ten lines POM, three lines Gradle.
- Reorder: `mvn test` twice. Failures in the first 5–10%.
- Measure: APFD every run. Dashboard for trends.
- Tune: weights tab. Meets you where you are.
- Say the URL twice — it's on the recording.
- `test-order:diagnose` = health check: suggests mode, shows overhead estimate.
Transition: pause, then advance to Q&A.
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-loc-catalog.jpg" class="absolute inset-0 w-full h-full object-cover opacity-15" />
<div class="absolute inset-0 bg-zinc-900/80 z-0" />
<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

Questions?

</div>

<div class="pt-10 text-xl font-mono text-center opacity-90">
  github.com/parttimenerd/test-order
</div>

</div>

<!--
- Keep this slide up for the full Q&A.
Likely questions:
- "Kotlin?" Yes — runs through JUnit/TestNG. Agent doesn't care about source language.
- "Bazel?" Not yet. Maven/Gradle plugins only.
- "Parallel execution?" Class-level: ordering is a scheduling priority. Method-level: per-thread bitset, fine.
- "Can the index be wrong?" Over-approximation only. False positives (extra tests), never false negatives.
- "Develocity PTS / Launchable?" ML on cloud history. Facebook PTS (Machalica 2019): 2× cost cut, >99.9% caught — but needs labeled data, a pipeline, data egress. We win on day-1, cost, residency; genetic optimizer closes the gap after 5+ runs.
- "CI state sharing?" Cache .test-order/ between runs or commit it.
- "GitHub Actions cache?" Add .test-order/ to your cache key.
- "APFD vs wall time?" 22-min suite at APFD 85% = first failure ~minute 3, not 18.
- "Doesn't reordering cause OD failures?" Potentially — detect-dependencies mode finds them first. <2% OD rate in the OSS repos tested.
-->
