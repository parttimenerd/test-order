---
theme: seriph
title: "You Are Running the Wrong Tests First"
info: |
  test-order — 50-minute developer talk.
  Local-first, zero-config test prioritization for Java · v0.1 · early-stage
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

<img src="/images/wiki-bletchley-cards.jpg" class="absolute inset-0 w-full h-full object-cover opacity-40" />
<div class="absolute inset-0 bg-black/50 z-0" />

<div class="relative z-10">

# You Are Running the Wrong Tests First

<div class="pt-8 text-xl opacity-60">
  Local, zero-config test prioritization for Java · v0.1 · early-stage
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
layout: default
class: bg-zinc-900 text-white
---

<DemoCard id="D0" duration="3 min" :cmd="`cd third-party/spring-petclinic\n# No test-order. Plain mvn test.\nmvn test -pl . -Dsurefire.failIfNoSpecifiedTests=false`" title="What CI does today" watch="Tests run A-Z. VisitControllerTests is V. You broke something there. Spring boots up 4 times before you find out."></DemoCard>

<div class="pt-4 text-lg text-center opacity-70">
  Watch where VisitControllerTests appears in the output.
</div>

<!--
- Run this BEFORE the talk starts — show the scrollback or replay with asciinema.
- Spring context starts cold: ~8s per context. By the time VisitControllerTests runs, you've already waited through A-U.
- "You already knew which test. The runner just didn't."
- Let the room feel it: failure is near the very end of output.
- FALLBACK: asciinema play public/demo-d0.cast
Transition: "Who's been here?"
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-apollo10-mission-control.jpg" class="absolute inset-0 w-full h-full object-cover opacity-15" />
<div class="absolute inset-0 bg-zinc-900/78 z-0" />

<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

Who's waited 20 minutes for CI<br/>to tell you the test that failed<br/>ran last, alphabetically?

</div>

<div class="hands-up mt-10">✋ raise your hand</div>

</div>

<!--
- PAUSE. Let hands go up. Don't rush it.
- "You already knew which test. The runner just didn't."
- Count the hands. Then: "keep yours up if it's more than once a week." Watch them stay.
Transition: "Here's what that wait looks like, drawn out."
-->


---
layout: default
class: bg-zinc-900 text-white
---

<ApfdTimeline />

<div class="pt-4 text-2xl text-rose-400 font-bold text-center">
  You changed that file. The signal was already there.
</div>

<!--
- The timeline bar IS the pain. Don't narrate it — point at it silently for 2 seconds.
- "Every one of those passing tests ran before the one that found your bug."
- "The bytecode already knew which test hit which class. We just never gave the runner that map."
- One sentence: that's the entire talk.
Transition: "So what do people reach for when they feel this pain?"
-->


---
layout: center
class: bg-zinc-900 text-white
---

<div class="big-statement">
Other approaches<br/>worth knowing
</div>

<!--
- Each of these solves a real problem. Acknowledge that.
- test-order is exploring a specific niche — not claiming to be better in all cases.
Transition: "Here's how they sit relative to each other."
-->


---
layout: default
class: bg-zinc-900 text-white
---

# Where each approach fits

<LandscapeMap />

<!--
- X axis: time to first value — how long before you get meaningful results.
- Y axis: prioritization intelligence — how well it ranks failing tests first.
- Random/shuffle: free, immediate. Removes alphabetical bias, doesn't prioritize.
- Stop-on-first-failure: free, orthogonal — combine with any approach.
- Manual @Order: works well for stable small suites.
- Coverage-based (Skippy, OpenClover): good accuracy, needs source instrumentation overhead on every run.
- Cloud TIA (Launchable, Develocity): excellent once warmed — requires a cloud pipeline and weeks/months of failure history to reach full accuracy.
- test-order: tries to occupy the top-left — local, zero-config, useful from run 2 without training data.
- "These aren't competing products. They're different trade-offs for different teams."
Transition: "Here's what test-order's specific trade-off looks like in numbers."
-->


---
layout: center
class: bg-zinc-900 text-white text-center
---

<div class="text-7xl font-black text-amber-400">50% → 87–91%</div>

<div class="pt-2 text-xl opacity-70">Average Percentage of Faults Detected</div>

<!--
- Drop this number. Silence for 3 seconds.
- 50% = alphabetical. You're already doing 50% and calling it "running tests."
- 87-91% = test-order, 7 real open-source repos.
- This isn't a lab result. Run it yourself: scripts/third_party_test_plan.sh
Transition: "Let me make that concrete."
-->


---
layout: default
class: bg-zinc-900 text-white
---

# 7 benchmarked OSS repos, consistently

<BenchmarkChart />

<v-click>

<div class="mt-4 text-sm opacity-60 text-center italic">Reproducible: <code>scripts/third_party_test_plan.sh bugs commons-lang</code></div>

</v-click>

<!--
- Alphabetical hovers at 50% — random baseline. test-order: 87-93%.
- Ranges across 5 injected bugs × 3 runs — varies by which class was changed.
- These are real project indexes checked in to this repo. Anyone can reproduce.
Transition: "Why does it work? The idea is 25 years old."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-fermi-blackboard.jpg" class="absolute inset-0 w-full h-full object-cover opacity-15" />
<div class="absolute inset-0 bg-zinc-900/80 z-0" />

<div class="relative z-10 flex flex-col items-center text-center">

<div class="big-statement">

Why does it work?<br/>The research is 25 years old.

</div>

</div>

<!--
- We didn't invent the signals. We just made them zero-config.
- Rothermel 1999: founded TCP, defined APFD.
- Yoo & Harman 2012: 20-year survey — failure history + code churn are the two strongest predictors.
- Luo 2014: 12% of flaky tests are order-dependent — grounds the quarantine feature.
- Google 2017: 5.5M targets, measured in production.
Transition: "Google's number is the most striking."
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

</div>

<!--
- Memon et al., ICSE-SEIP 2017. 5.5M targets, 500K+ code changes.
- "91.3% passed and never failed once." Not a theory — a measurement.
- The 9% aren't random. They're geometrically closer to the change.
- Quote from the paper confirms it.
Transition: exact words from the paper.
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
<div class="quote-attr">Memon et al., Taming Google-Scale Continuous Testing, ICSE-SEIP 2017</div>

</div>

<!--
- "Closer" = shorter path in the dependency graph.
- We operationalize this as set intersection: deps(test) ∩ changed_classes.
- Bigger intersection → higher score. That's the whole scoring model.
Transition: "Which leads to a hard logical claim."
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
- Say it slowly. "Cannot" — not "probably won't." Cannot.
- This is deterministic, not probabilistic. The dep map is complete.
- Google measured it at 5.5M scale. We act on it locally with bytecode.
Transition: "So how do we know what each test touches? We record it."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-hollerith-leiden.jpg" class="absolute inset-0 w-full h-full object-cover opacity-15" />
<div class="absolute inset-0 bg-zinc-900/80 z-0" />
<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

Record the map once.<br/>Use it on every push.

</div>

</div>

<!--
- "Record" = one learn run with a Java agent. Writes .test-order/ index.
- "Every push" = every subsequent run is uninstrumented — pure ranking, no overhead.
- This is the key insight: pay the cost once, use the map forever.
Transition: "There's a tool that does exactly this."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-telegraph-tokyo.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/82 z-0" />
<div class="relative z-10 flex flex-col items-center text-center">

<div class="big-statement">

There's a zero-config,<br/>local solution.

</div>

<v-click>

<div class="text-3xl font-bold mt-8 text-blue-400 tracking-wide">test-order</div>

</v-click>

<v-click>

<div class="text-xl mt-4 opacity-70">
  zero config &nbsp;·&nbsp; bytecode instrumentation &nbsp;·&nbsp; Maven &amp; Gradle &nbsp;·&nbsp; v0.1
</div>

</v-click>

</div>

<!--
- Pause after "There's a zero-config, local solution." Let it sit. 3 seconds.
- Click: reveal the name. Still don't speak.
- Click: reveal the tagline.
- Now: "It's called test-order. I spent two years of evenings building it so you don't have to."
Transition: "Two commands. That's the whole model."
-->


---
layout: default
class: bg-zinc-900 text-white
---

# Two runs. That's the whole model.

<PipelineDiagram />

<!--
- Walk left to right: LEARN writes the index once, ORDER uses git diff + index on every run.
- index = for each test class, a set of class IDs it called — stored as RoaringBitmaps.
- Score step: set intersection of deps(test) ∩ changed_classes → overlap signal + other signals → additive total.
- "Same code + same diff + same index → same ranked order. Fully deterministic."
Transition: "What does that look like on a developer's laptop?"
-->


---
layout: section
---

<img src="/images/wiki-wacs-teletype.jpg" class="absolute inset-0 w-full h-full object-cover opacity-28" />
<div class="absolute inset-0 bg-zinc-900/72 z-0" />

<div class="relative z-10 text-center">

# Ten lines. Then run `mvn test`.

<div class="pt-4 opacity-70">Maven or Gradle — same result</div>

</div>

<!--
- "Ten lines of POM. That's the entire install. Let me show you."
- Don't explain before showing. Show first.
Transition: straight to POM code.
-->


---
layout: default
class: bg-zinc-900 text-white
---

# Maven POM: ten lines

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
- Walk highlights: groupId/artifactId → extensions=true → prepare goal.
- extensions=true: the #1 install mistake. Registers test-order as a Maven lifecycle participant.
- prepare goal: auto-detects — no index → learn run; index found → order run.
- `mvn test` works exactly as before. No flags. No config.
Transition: "Let me run it."
-->


---
layout: default
class: bg-zinc-900 text-white
---

# Demo 1: learn → order → rank shift

<DemoCard id="D1" duration="6 min" :cmd="`cd samples/sample-shop\nmvn test                  # learn run\nmvn test                  # order run\nmvn test-order:show       # scores + why\n# --- now make a change ---\n# Add: if (item == null) throw new IllegalArgumentException();\n$EDITOR src/main/java/com/example/shop/Cart.java\nmvn test\nmvn test-order:show       # watch CartTest jump to #1`" title="Two runs. Then one edit. Watch the rank respond." watch="Run 1: index written. Run 2: order changed, APFD printed. Edit: CartTest jumps to #1 — changed-test=9, overlap=2."></DemoCard>

<div class="pt-3 text-sm opacity-50 text-center"><em>Switch to terminal tab #1.</em></div>

<!--
- DON'T paste — type aloud. Narrate each command before pressing Enter.
- Run 1: "Auto-instrumenting 42 classes" — index written to .test-order/
- Run 2: "Same command. No flags. The order changed." — point at the order in output.
- :show: walk the Why column. "Every score is debuggable."
- Edit: open Cart.java, add null-check to add(). "One line. Now watch."
- Run 3: CartTest at #1. "No retraining. A set intersection on the existing index."
- Fallback: asciinema play public/demo-d1.cast
Transition: "Here's what :show prints."
-->


---
layout: default
class: bg-zinc-900 text-white
---

# What `:show` prints

```ansi
$ mvn test-order:show

Changed: com.example.shop.Cart

 # │ Score │ Class       │ Why
───┼───────┼─────────────┼───────────────────────────
 1 │  14.0 │ CartTest    │ changed=9, overlap=2, pkg=2
 2 │   7.0 │ ProductTest │ overlap=5, pkg=2
 3 │   0.0 │ InvoiceTest │ (no overlap) [SLOW 320ms]

[test-order] Run APFD: 92.9%
```

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
class: bg-zinc-900 text-white
---

# The edit we're about to make

```java {all|3}
public void add(Item item) {
    if (item == null) throw new IllegalArgumentException();
    items.add(item);
}
```

<!--
- Show the code BEFORE switching to the IDE — sets expectations.
- "I'll add exactly this. Then run mvn test. That's it."
- The highlight animation walks: show the whole method first, then focus on line 3.
- After the click: "this one line is enough. The set intersection already knows."
Transition: switch to IDE / terminal.
-->



---
layout: default
class: bg-zinc-900 text-white
---

# Gradle: three lines

```groovy
plugins {
  id 'me.bechberger.test-order' version '0.1.0'
}
```

<!--
- Three lines. That's it. "Same data model, same scoring, same index format."
- Don't run it live — save the time.
- "Gradle is in the repo: samples/sample-vintage-gradle. Same APFD, camelCase tasks."
Transition: "What does the learn run actually cost?"
-->


---
layout: default
class: bg-zinc-900 text-white
---

# Instrumentation overhead

<InstrumentationOverhead />

<!--
- You pay once, on the learn run. Overhead numbers are vs. spring-petclinic baseline 4.93s → ~5.5s.
- Every ordered run after is identical to uninstrumented.
- Default is MEMBER. Switch with -Dtestorder.instrumentation.mode=METHOD.
- The 0% is the important number — say it explicitly after the click.
Transition: "Here's a real project, pre-indexed."
-->


---
layout: default
class: bg-zinc-900 text-white
---

# Demo 2: spring-petclinic, pre-indexed

<DemoCard id="D2" duration="6 min" :cmd="`cd third-party/spring-petclinic\n# learn ran last night in CI, zero overhead today\nmvn test\nmvn test-order:dashboard`" title="Real project. Watch APFD live, then open dashboard." watch="Test order in terminal. First failure surfaces early. Then dashboard: APFD trend, run history, cache tab."></DemoCard>

<!--
- "Learn ran last night. Today's run is zero overhead." — normal CI workflow.
- Narrate the APFD line as it updates.
- Dashboard tour (90s): Tests tab (score bars, why, sparklines), Analytics (APFD trend, heatmap), Cache tab (deferred tests).
- Fallback: public/dashboard-overview.png, analytics-tab.png
Transition: "Six numbers on the dashboard KPI bar — let me decode them."
-->


---
layout: default
class: bg-zinc-900 text-white
---

# The dashboard: six numbers

<div class="mt-6 grid grid-cols-3 gap-4">

<div class="p-4 rounded-lg bg-zinc-800 border border-zinc-700 text-center">
  <div class="font-bold text-orange-300 text-2xl font-mono">APFD</div>
</div>

<v-click>

<div class="p-4 rounded-lg bg-zinc-800 border border-zinc-700 text-center">
  <div class="font-bold text-rose-300 text-2xl font-mono">Failures</div>
</div>

</v-click>
<v-click>

<div class="p-4 rounded-lg bg-zinc-800 border border-zinc-700 text-center">
  <div class="font-bold text-green-300 text-2xl font-mono">Pass streak</div>
</div>

</v-click>
<v-click>

<div class="p-4 rounded-lg bg-zinc-800 border border-zinc-700 text-center">
  <div class="font-bold text-blue-300 text-2xl font-mono">At-risk</div>
</div>

</v-click>
<v-click>

<div class="p-4 rounded-lg bg-zinc-800 border border-zinc-700 text-center">
  <div class="font-bold text-violet-300 text-2xl font-mono">Time saved</div>
</div>

</v-click>
<v-click>

<div class="p-4 rounded-lg bg-zinc-800 border border-zinc-700 text-center">
  <div class="font-bold text-yellow-300 text-2xl font-mono">Health A–F</div>
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

<img src="/images/wiki-lego-bricks.jpg" class="absolute inset-0 w-full h-full object-cover opacity-28" />
<div class="absolute inset-0 bg-zinc-900/75 z-0" />

<div class="relative z-10 text-center">

# Honest Assessment

<div class="pt-4 opacity-70">where it breaks, what we're still building</div>

</div>

<!--
- Earn trust now — while demos are fresh in the room's mind.
- "It works. Here's where it doesn't."
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
class: bg-zinc-900 text-white
---

<div class="pt-8 grid grid-cols-3 gap-6 text-center">
  <div class="p-6 rounded-lg bg-rose-950 border border-rose-800 text-white">
    <div class="text-4xl font-bold text-rose-400">&lt; 20</div>
    <div class="pt-3 text-base opacity-80">tiny suites</div>
  </div>
  <div class="p-6 rounded-lg bg-rose-950 border border-rose-800 text-white">
    <div class="text-4xl font-bold text-rose-400">⚙</div>
    <div class="pt-3 text-base opacity-80">reflection-only paths</div>
  </div>
  <div class="p-6 rounded-lg bg-rose-950 border border-rose-800 text-white">
    <div class="text-4xl font-bold text-rose-400">⚡</div>
    <div class="pt-3 text-base opacity-80">dynamic classloading</div>
  </div>
</div>

<!--
- Tiny suites: no ordering headroom. Parallelism helps more.
- Reflection: Spring AOP and Mockito are fine — they go through bytecode. "Build the object graph from YAML at runtime" loses us.
- Dynamic classloading: classes loaded after JVM start aren't in the instrumentation window. Standard Surefire forks are fine.
Transition: "v0.1 means we found things that break. Here's the deal."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-lego-bricks.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/82 z-0" />
<div class="relative z-10 flex flex-col items-center text-center max-w-2xl mx-auto">

<div class="big-statement">
You will find bugs.<br/>
<span class="text-emerald-400">That's the point.</span>
</div>

<v-click>
<div class="mt-8 text-xl opacity-80">
  It's open source. Apache 2.0.<br/>
  File an issue. Send a PR. Tell me what broke.
</div>
</v-click>

<v-click>
<div class="mt-6 font-mono text-lg text-sky-300">
  github.com/parttimenerd/test-order
</div>
</v-click>

</div>

<!--
- This is NOT a disclaimer slide. It's an invitation.
- "v0.1 means I've run it on 7 repos. You have repos I've never seen."
- Real users finding real edge cases is how OSS tools get good.
- "If it breaks on your project, I want to know. That's more valuable than any benchmark I can run."
- Don't apologize. This is the honest expectation-setting that earns long-term trust.
Transition: "Let me show you how it's built, so you can contribute."
-->


---
layout: section
---

<img src="/images/wiki-cat-reading.jpg" class="absolute inset-0 w-full h-full object-cover opacity-28" />
<div class="absolute inset-0 bg-zinc-900/72 z-0" />
<div class="relative z-10">

# Under the Hood

<div class="pt-4 opacity-70">bytecode · data structures · performance engineering</div>

</div>

<!--
- Skip entirely if time is short — jump to "Beyond Ordering."
- Audience has seen it work. This rewards the technically curious.
Transition: "One injected call. Let me open it up."
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
layout: default
class: bg-zinc-900 text-white injection-slide
---

# The injection: one `invokestatic`

<style>
.injection-slide pre, .injection-slide pre code, .injection-slide .shiki { font-size: 1.1rem !important; }
</style>

<div class="grid grid-cols-2 gap-4 mt-2">

<div>

```java
// BEFORE
public Money total() {
    return items.stream()
        .map(Item::price)
        .reduce(ZERO, Money::add);
}
```

</div>
<div>

```java {2}
// AFTER
public Money total() {
    UsageStore.recordId(4711);
    return items.stream()
        .map(Item::price)
        .reduce(ZERO, Money::add);
}
```

</div>
</div>

<div class="mt-4 grid grid-cols-2 gap-4">

<v-click>
<div class="p-3 rounded bg-zinc-800 border-l-4 border-blue-500 text-sm">
  <span class="font-semibold text-blue-300">ASM streaming visitor</span> — no object model, single pass
</div>
</v-click>

<v-click>
<div class="p-3 rounded bg-zinc-800 border-l-4 border-green-500 text-sm">
  <span class="font-semibold text-green-300">Integer class ID</span> — pre-computed, ~50× faster than strings
</div>
</v-click>

</div>

<!--
- "ByteBuddy builds a full object model per class. We visit the stream once — next slide shows the numbers."
- No full class model in memory; manual maxStack tracking.
- Integer IDs are the single biggest win — pre-computed before any test runs. Strings cost hashing, allocation, concurrent-map contention.
- Five bytes: iconst/bipush/sipush/ldc + invokestatic. Stack delta 0. No allocation, no GC pressure.
Transition: "Why not ByteBuddy — let me show you the numbers."
-->


---
layout: default
class: bg-zinc-900 text-white
---

# Why not ByteBuddy?

<div class="mt-3 grid grid-cols-2 gap-5">

<div class="p-4 rounded-lg border-2 border-red-700/70" style="background: rgba(127,29,29,0.25)">

<div class="font-semibold text-red-400 mb-4">ByteBuddy</div>

<div class="font-mono text-sm text-red-200" style="background: rgba(127,29,29,0.5); padding: 0.5rem; border-radius: 6px">
  10 000 classes × 200 µs = <strong>2 s overhead</strong>
</div>

</div>

<div class="p-4 rounded-lg border-2 border-green-700/70" style="background: rgba(6,78,59,0.25)">

<div class="font-semibold text-green-400 mb-4">ASM — streaming</div>

<div class="font-mono text-sm text-green-200" style="background: rgba(6,78,59,0.5); padding: 0.5rem; border-radius: 6px">
  10 000 classes × 4 µs = <strong>40 ms overhead</strong>
</div>

</div>

</div>

<!--
- ByteBuddy is great for runtime proxies and agent-based tools — it's not the wrong choice in general.
- For our case: we touch every class, every test run, during class loading. The per-class cost multiplies.
- ASM is what javac, the JDK instrumentation API, and every serious bytecode tool reach for at scale.
- "Zero classpath deps" is a bonus: we don't pull ByteBuddy (+ Byte-Buddy-Agent) into the user's test classpath.
Transition: "Here's the whole transformation in one picture."
-->


---
layout: default
class: bg-zinc-900 text-white
---

# The transformation pipeline

<InstrumentationPipeline />

<!--
- Walk the diagram left to right: class on disk → Surefire hook loads it → ASM visits → invokestatic inserted → bitset records which class ids fired → drained to index.
- "The transformer is registered once. Every class that loads during a test goes through it automatically — including classes loaded lazily mid-test."
- Selective learn skips classes whose bytecode hash hasn't changed — so repeat runs only re-instrument what's new.
Transition: "This is fundamentally different from JaCoCo."
-->


---
layout: default
class: bg-zinc-900 text-white
---

# Not the same as JaCoCo

<div class="mt-4 grid grid-cols-2 gap-6">

<div class="p-5 rounded-lg border border-zinc-600 bg-zinc-800/50">

### JaCoCo / Cobertura

<div class="mt-3 space-y-2 text-base opacity-80">

- Aggregate bit per line
- "Was line 42 hit by **any** test in the suite?"
- Suite-level answer — loses which test touched what

</div>

<div class="mt-4 font-mono text-xs text-zinc-400 bg-zinc-900 p-2 rounded">Cart.java:42 → hit ✓</div>

</div>

<div class="p-5 rounded-lg border border-sky-700 bg-sky-950/40">

### test-order

<div class="mt-3 space-y-2 text-base opacity-80">

- Per-test-method dep set
- "Which **specific test** touched which **classes**?"
- Needed for scoring: CartTest's deps ∩ changed files

</div>

<div class="mt-4 font-mono text-xs text-sky-300 bg-zinc-900 p-2 rounded">CartTest → {Cart, Invoice, Money}</div>

</div>

</div>

<!--
- JaCoCo answers "was this code covered?" — useful for dead code, not for ordering.
- We need per-test attribution: "did CartTest touch Cart.java?" — not just "was Cart.java touched?"
- The intersection (dep set ∩ changed files) is the signal. Coverage tools don't produce it.
- This is why no one has done this with JaCoCo: the data model is wrong for the problem.
Transition: "The bitset is why the overhead is this low."
-->


---
layout: default
class: bg-zinc-900 text-white
---

# Thread-local bitset

```java {all|1-2|4-6}
// one long[] per thread — no contention
static final ThreadLocal<long[]> BITS =
    ThreadLocal.withInitial(() -> new long[N / 64 + 1]);

public static void recordId(int id) {  // inlined by JIT
    long[] bits = BITS.get();
    bits[id >>> 6] |= (1L << id);
}
```

<!--
- Naive: ConcurrentHashMap<String, Set<String>>. Every call = hash + lookup + set.add. Contention under parallel runs.
- Ours: push an int, OR into a long array. JIT inlines it entirely.
- Bitset drained once when the test method ends — one aggregation per test.
- Thread-local means parallel Maven forks work out of the box.
Transition: "Don't take my word for it — see the injection live."
-->


---
layout: default
class: bg-zinc-900 text-white
---

# Live: read the index yourself

```bash {1|2-4|6-8}
cd samples/sample-shop && mvn test -Dtestorder.mode=learn

java -jar test-order-core-*-jar-with-deps.jar \
  deps .test-order/test-dependencies.lz4 \
  com.example.shop.CartTest

# com.example.shop.Cart
# com.example.shop.Invoice
# com.example.shop.Money   … (8 classes)
```

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
class: bg-zinc-900 text-white
---

# Demo 3: meta-agent, see the injection live

<DemoCue>switching to browser · localhost:7071</DemoCue>

<DemoCard id="D3" duration="4 min" :cmd="`# meta-agent at localhost:7071\nopen http://localhost:7071/instrumentators\nopen http://localhost:7071/classes\nopen http://localhost:7071/full-diff/com.example.Cart`" title="What does test-order actually do to your bytecode?" watch="Vineflower decompilation diff: UsageStore.recordUsageIdFast at every method entry. Nothing else."></DemoCard>

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
class: bg-zinc-900 text-white
---

# How the index gets to disk

<SocketBatchDiagram />

<!--
<!--
- Surefire forks a JVM per test class — hundreds of forks in a big build.
- Old: each fork loaded the 5 MB index, merged, wrote back. 100–500 ms per fork → ~20s of pure I/O. Or 800 file cycles per fork in MEMBER mode.
- Current: fork sends one binary blob over a local socket at exit. Plugin aggregates, serializes once.
- Per-file fallback still exists for standalone agent use.
Transition: "What's actually in that file?"
-->


---
layout: default
class: bg-zinc-900 text-white
---

# The index format

```
test-dependencies.lz4
├── Magic: "TORD"
├── SECTION 1: ClassNameTrie   ← radix trie
├── SECTION 2: TestClasses
├── SECTION 3: DepGroups       ← RoaringBitmap, deduped
├── SECTION 4: MethodDeps
└── SECTION 5: MemberDeps
```

<div class="mt-3 grid grid-cols-2 gap-3">

<v-click>
<div class="p-3 rounded bg-zinc-800 border-l-4 border-sky-400 text-sm">
  <div class="font-semibold text-sky-300 mb-1">Radix trie</div>
  <div class="opacity-80">Shared prefixes stored once. <code>com.example.shop.*</code> = one node, not 50 strings.</div>
</div>
</v-click>

<v-click>
<div class="p-3 rounded bg-zinc-800 border-l-4 border-purple-400 text-sm">
  <div class="font-semibold text-purple-300 mb-1">RoaringBitmap — "a better compressed bitset"</div>
  <div class="opacity-80">Sparse class-ID sets stored as compressed 16-bit chunks. roaringbitmap.org</div>
</div>
</v-click>

</div>

<!--
- Section-based: unknown sections skipped by length — forward compatible.
- "TORD" magic identifies the payload inside the LZ4 frame.
- ClassNameTrie: 500 classes sharing "com.example.shop." store that prefix once. Lookups O(name length).
- RoaringBitmap: "a better compressed bitset" — roaringbitmap.org. Cuts a 10k-class dense case 80–90%.
- Row-dedup: integration tests with identical dep sets point at one bitmap. Cuts index 30–60%.
Transition: "We can even skip instrumenting most of the codebase."
-->


---
layout: default
class: bg-zinc-900 text-white
---

# Selective learn: BFS before the JVM starts

<SelectiveLearnDiagram />

<!--
- Full learn: instruments every loaded class. Simple, complete, ~13% overhead.
- Selective: git diff → BFS over the stored static call graph → whitelist of names to instrument.
- Empty whitelist = agent doesn't attach at all. `git pull` with no local edits: 0% overhead.
- 4-hop limit is empirical: measured signal-to-noise across OSS repos; curve flattens at 4.
- BFS runs over the existing index — no JVM needed.
Transition: "Five performance decisions made this fast."
-->


---
layout: default
class: bg-zinc-900 text-white
---

<img src="/images/loc-scientist-chalkboard.jpg" class="absolute inset-0 w-full h-full object-cover opacity-10" />
<div class="absolute inset-0 bg-zinc-900/88 z-0" />

<div class="relative z-10">

# Five decisions that make it fast

<div class="mt-8 space-y-4">

<div class="flex items-center gap-4 text-xl">
  <span class="font-mono text-emerald-400 font-bold text-2xl min-w-[2rem]">①</span>
  <span class="font-semibold">Integer IDs</span>
</div>

<v-click>

<div class="flex items-center gap-4 text-xl">
  <span class="font-mono text-emerald-400 font-bold text-2xl min-w-[2rem]">②</span>
  <span class="font-semibold">Thread-local bitsets</span>
</div>

</v-click>
<v-click>

<div class="flex items-center gap-4 text-xl">
  <span class="font-mono text-emerald-400 font-bold text-2xl min-w-[2rem]">③</span>
  <span class="font-semibold">Socket batch</span>
</div>

</v-click>
<v-click>

<div class="flex items-center gap-4 text-xl">
  <span class="font-mono text-emerald-400 font-bold text-2xl min-w-[2rem]">④</span>
  <span class="font-semibold">In-JVM cache</span>
</div>

</v-click>
<v-click>

<div class="flex items-center gap-4 text-xl">
  <span class="font-mono text-emerald-400 font-bold text-2xl min-w-[2rem]">⑤</span>
  <span class="font-semibold">Frequency filter</span>
</div>

</v-click>

</div>

</div>

<!--
- Reveal one at a time — let each decision land.
- "Each of these was a measured regression before we fixed it."
- Integer IDs: first iteration used class-name strings. 4000 string.equals() per call in a 4000-class project.
- Thread-local: ConcurrentHashMap had measurable contention under parallel Surefire forks.
- Socket batch: 100 forks × 500ms I/O = 50s of pure overhead on a large module.
- In-JVM cache: 100-module build was reloading + decompressing the same 5 MB index 100 times.
- Frequency filter: Jackson ClassUtil, log facades appear in ~99% of dep sets — bloat index, score tests identically, add zero signal.
Transition: "Now the scoring formula itself."
-->


---
layout: default
class: bg-zinc-900 text-white
---

# 7 scoring signals, all configurable

<div class="text-sm opacity-60 mb-2">Defaults work out of the box · tune via POM properties · <code>mvn test-order:optimize</code> auto-tunes after 5 runs</div>

<div class="pt-1 grid grid-cols-2 gap-6">

<div>

| Signal | Weight | Rationale |
|--------|:------:|-----------|
| New test | **+15** | No history, learn it first |
| Changed test | **+9** | You edited it |
| Recent failure | 0–5 | EMA d=0.3 · Yoo & Harman |
| **Dep overlap** | **0–5** | √-norm intersection score |

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

</div>

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
class: bg-zinc-900 text-white
---

# The scoring algorithm

```python
def score(test, changed, index):
    deps = index[test]
    overlap = deps & changed

    s  = W_CHANGED if test_changed(test) else 0
    s += W_OVERLAP * len(overlap)
    s += W_PACKAGE if same_package(test, changed) else 0
    s += W_SPEED   if is_fast(test) else 0
    s *= ema_failure_weight(test)
    return s
```

<div class="mt-3 grid grid-cols-2 gap-3 text-sm opacity-80">
  <div class="p-2 rounded bg-zinc-800 border-l-2 border-amber-400">All weights configurable in POM / <code>test-order.xml</code></div>
  <div class="p-2 rounded bg-zinc-800 border-l-2 border-emerald-400"><code>mvn test-order:optimize</code> auto-tunes after 5 runs</div>
</div>

<!--
- Every term additive and visible in the :show Why column.
- Default weights work well out of the box — but every weight is a property: testorder.weight.changed=9
- EMA = exponential moving average of past failures — history matters but decays.
- After 5 runs with failures, `mvn test-order:optimize` tunes weights via genetic algorithm over APFD history.
- No black box. Debuggable by design.
Transition: "CartTest scores 14. Here's exactly why."
-->


---
layout: default
class: bg-zinc-900 text-white
---

# CartTest scores 14: here's every point

<div class="grid grid-cols-2 gap-6 mt-2">
<div>

```
deps    = {Cart, Invoice, …}
changed = {Cart}
```

</div>
<div>

<ScoringBreakdown />

</div>
</div>

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
class: bg-zinc-900 text-white
---

# Demo 3 (cont.): meta-agent shows instrumentation mode diff

```ansi {1|3-4|6-9}
GET /full-diff/com.example.Cart

=== BEFORE ===
public Money total() { return items.stream()…; }

=== AFTER ===
public Money total() {
    UsageStore.recordId(4711);
    return items.stream()…;
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
Transition: "That's how deep it goes. Now let's come back up."
-->


---
layout: center
class: text-center bg-zinc-900 text-white
---

<div class="big-statement">
Want to know <em>why</em> it's this fast?
</div>

<v-click>

<div class="text-xl mt-8 opacity-75">
  One injected call. One bitset. One file.<br/>
  Five engineering decisions made that possible.
</div>

</v-click>

<!--
- Optional section — skip if running short on time; go straight to Beyond Ordering.
- The audience has seen it work and knows the limits. Now reward the curious ones.
Transition: "Let me open it up."
-->


---
layout: section
---

<img src="/images/wiki-eniac-programmers.jpg" class="absolute inset-0 w-full h-full object-cover opacity-28" />
<div class="absolute inset-0 bg-zinc-900/72 z-0" />

<div class="relative z-10 text-center">

# Beyond Ordering

<div class="pt-4 opacity-60">three problems ordering alone can't solve</div>

</div>

<!--
- Ordering helps when deps are visible.
- These handle what ordering can't: flakiness, unchanged code, order-dependent state.
- All opt-in. Day-one you touch none of them.
Transition: "First — auto-retry."
-->


---
layout: default
class: bg-zinc-900 text-white flaky-slide
---

<style>
.flaky-slide pre,
.flaky-slide pre code,
.flaky-slide .shiki,
.flaky-slide .shiki-container { 
  font-size: 1.15rem !important;
  background: #1e293b !important;
  background-color: #1e293b !important;
}
</style>

# Handling flaky tests

<div class="mt-8 grid grid-cols-2 gap-10">

<div class="p-6 rounded-lg border border-zinc-600 bg-zinc-800">

### Auto-retry

```java
@RetryingTest(3)
void fetchRates() { … }
```

<div class="mt-3 text-sm opacity-70">JUnit 5 · for DNS blips, rate limits, pool saturation</div>

</div>

<div class="p-6 rounded-lg border border-zinc-600 bg-zinc-800">

### Quarantine

```java
@QuarantinedTest
void flakyTest() { … }
```

<div class="mt-3 text-sm opacity-70">Tests still run · can't break the build</div>

</div>

</div>

<!--
- Auto-retry: InvocationInterceptor, JUnit 5. For DNS blips, pool saturation, rate-limit hiccups.
- The EMA score doesn't spike on a single retry — noise filtered.
- Quarantine: @QuarantinedTest. Tests still run — they just can't break the build.
- Luo et al. 2014: 4.56% of Google TAP failures were flaky — enough EMA noise to bury real signal.
Transition: "Problem 3 — running tests that can't possibly fail."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-cat-reading.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/82 z-0" />
<div class="relative z-10 flex flex-col items-center text-center">

<div class="text-xl text-rose-400 font-semibold mb-6">Ordering still runs tests that can't fail on this change</div>

<div class="big-statement">
Skip-if-unchanged
</div>

<div class="pt-8 text-xl opacity-70">
  Stable dep set + pass streak → defer. Cap: 90%.
</div>

</div>

<!--
- The cache. Same dep hash + no failures for N runs → "safe to skip."
- 90% cap: you can never accidentally defer the whole suite.
- The logical extension of "if it hasn't touched the changed code, it cannot fail."
Transition: "Problem 4 — tests that depend on each other's side effects."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-cat-reading.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/82 z-0" />
<div class="relative z-10 flex flex-col items-center text-center">

<div class="text-xl text-rose-400 font-semibold mb-6">Pass in isolation. Fail when another test runs first.</div>

<div class="big-statement">
Order-dependent<br/>tests
</div>

</div>

<!--
- OD tests: TestA passes alone. TestA fails when TestB ran before it.
- Root cause: shared mutable state — static fields, Spring context, Mockito static, DB rows.
- Reordering (what test-order does for speed) can *expose* latent OD tests.
- We need to *detect* them before they become noise.
Transition: "This is a well-studied problem. Here's what we know."
-->


---
layout: default
class: bg-zinc-900 text-white
---

# What the research found

<div class="mt-5 space-y-4">

<div class="p-4 rounded-lg bg-zinc-800/60 border-l-4 border-rose-500">
  <span class="font-semibold text-rose-300">Luo et al., ICSE 2014</span>
  <span class="ml-3 opacity-80">4.56% of Google TAP suite failures were order-dependent</span>
</div>

<div class="p-4 rounded-lg bg-zinc-800/60 border-l-4 border-amber-500">
  <span class="font-semibold text-amber-300">Shi et al., FSE 2019</span>
  <span class="ml-3 opacity-80">OD tests found in 20 of 26 studied open-source Java projects</span>
</div>

<div class="p-4 rounded-lg bg-zinc-800/60 border-l-4 border-sky-500">
  <span class="font-semibold text-sky-300">Bell et al., ASE 2015</span>
  <span class="ml-3 opacity-80">Random shuffling finds ~60% of OD tests; needs many runs</span>
</div>

<div class="p-4 rounded-lg bg-zinc-800/60 border-l-4 border-emerald-500">
  <span class="font-semibold text-emerald-300">Li et al., ISSTA 2023</span>
  <span class="ml-3 opacity-80">Combinatorial (Tuscan square) designs: 97.2% detection, ~105 runs</span>
</div>

</div>

<!--
- Google data: 1 in 22 suite failures is order-dependent — not flaky, not broken, just wrong order.
- 20/26 Java projects = you almost certainly have some. They hide because your CI runs a fixed order.
- Bell 2015 showed random helps but you need dozens of random runs to get good coverage.
- Li 2023 is the state of the art: structured designs get near-complete pair coverage in a fixed small number of runs.
Transition: "Three detection approaches. Let me compare them."
-->


---
layout: default
class: bg-zinc-900 text-white
---

# Option 1: brute force

<div class="grid grid-cols-2 gap-8 mt-4">

<div>

```python
for o in permutations(t):
    run(o)
```

</div>

<div class="flex flex-col justify-center space-y-3">

<div class="p-3 rounded bg-zinc-800/60 border-l-4 border-rose-500 text-sm">
  <div class="font-semibold text-rose-300">n=10 tests → 3,628,800 runs</div>
  <div class="mt-1 opacity-70">Nobody runs this.</div>
</div>

<div class="p-3 rounded bg-zinc-800/50 border border-zinc-700 text-sm opacity-70">
  ✓ Finds every OD test<br/>
  ✗ Combinatorially impossible at scale
</div>

</div>

</div>

<!--
- It's complete. It's correct. It's useless at scale.
- 10 tests = 3.6M. 20 tests = 2.4 × 10^18. Nobody runs this.
- We need structure.
Transition: "Maybe randomness helps?"
-->


---
layout: default
class: bg-zinc-900 text-white
---

# Option 2: random shuffle

<div class="grid grid-cols-2 gap-8 mt-4">

<div>

```python
for _ in range(k):
    shuffle(tests)
    run(tests)
```

</div>

<div class="flex flex-col justify-center space-y-3">

<div class="p-3 rounded bg-zinc-800/60 border-l-4 border-amber-500 text-sm">
  <div class="font-semibold text-amber-300">Bell et al., ASE 2015</div>
  <div class="mt-1 opacity-70">~60% detection rate. Needs dozens of runs.</div>
</div>

<div class="p-3 rounded bg-zinc-800/50 border border-zinc-700 text-sm opacity-70">
  ✓ Simple to implement<br/>
  ✗ No guarantee pair (b before a) ever runs<br/>
  ✗ Coverage is probabilistic
</div>

</div>

</div>

<!--
- Better than nothing. Each shuffle gives new ordering.
- But you might run it 20 times and still never see (b, a) in that specific order.
- Bell 2015: ~60% detection, which means 40% slip through.
Transition: "There's a structured design that guarantees full pair coverage."
-->


---
layout: default
class: bg-zinc-900 text-white
---

# Option 3: Tuscan square

<div class="grid grid-cols-2 gap-6 mt-3">

<div>

```python
S = tuscan_square(n)
for row in S:   # n rows
    run(row)
# all ordered pairs ✓
```

<div class="mt-3 p-3 rounded bg-sky-950/40 border border-sky-700 text-sky-200 text-sm">
  Every ordered pair (a→b) <em>and</em> (b→a) appears at least once across the rows.<br/>
  <span class="opacity-70 text-xs">Li et al., ISSTA 2023 — 97.2% detection rate</span>
</div>

</div>

<div>

<TuscanSquareViz />

</div>

</div>

<!--
- Latin square where every ordered pair appears in both directions.
- n tests → n rows. n=50 = 50 runs vs 3×10^64 brute force.
- Li 2023: 97.2% detection with this design. That's the state of the art.
- The viz shows a 5×5 example. As each row runs, new pairs get covered. After 5 rows: all 20 ordered pairs.
Transition: "Here's exactly what we generate and how we detect."
-->


---
layout: default
class: bg-zinc-900 text-white detect-algo-slide
---

<style>
.detect-algo-slide pre, .detect-algo-slide pre code, .detect-algo-slide .shiki { font-size: 1.05rem !important; line-height: 1.5 !important; }
</style>

# `detect-dependencies`: the algorithm

<div class="grid grid-cols-2 gap-5 mt-3">

<div>

```python
S = tuscan_square(tests)  # ~n rows

results = {}
for row in S:
    results[row] = run_suite(row)

for test in tests:
    outcomes = [results[r][test]
                for r in S]
    if not all_same(outcomes):
        report_od(test)  # OD found!
```

</div>

<div class="space-y-3 text-sm">

<div class="p-3 rounded bg-sky-950/40 border border-sky-700">
  <div class="font-semibold text-sky-300">Why it works</div>
  <div class="mt-1 opacity-75">Every (a, b) pair runs in both orders. An OD test fails when its "polluter" precedes it — the square guarantees we see that ordering.</div>
</div>

<div class="p-3 rounded bg-zinc-800/60 border-l-4 border-amber-500">
  <div class="font-semibold text-amber-300">n=50 tests → ~50 runs</div>
  <div class="mt-1 opacity-75">vs 3×10<sup>64</sup> brute force</div>
</div>

<div class="p-3 rounded bg-emerald-950/40 border border-emerald-700">
  <div class="font-semibold text-emerald-300">Run before release, not per-commit</div>
</div>

</div>

</div>

<!--
- The Tuscan square is generated at runtime — we compute it for exactly your suite size.
- "Run it before you ship, not 20 times a day."
- If it finds something: that's a pre-existing bug that was hidden by fixed ordering. Fix the test, not the order.
- &lt;2% in our benchmarks — low, but non-zero. Real projects have them.
Transition: "Back to the headline numbers."
-->


---
layout: center
class: bg-zinc-900 text-white text-center
---

<div class="text-6xl font-black text-emerald-400">95% of the time: failing test at #1</div>

<div class="pt-4 text-2xl opacity-70">7 benchmarked OSS repos · average rank: 1.4</div>

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

<div class="text-xl text-center mt-8 opacity-70">
  Universal utilities · reflection-only paths
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
layout: center
class: text-center bg-zinc-900 text-white
---

<div class="big-statement">
That's your laptop.<br/>What about 52 modules?
</div>

<v-click>

<div class="text-xl mt-8 opacity-75">
  Or when CI runs your suite on every commit, dozens of times a day.
</div>

</v-click>

<!--
- "We've proven accuracy. Now: does it hold at real project scale?"
- The scale angle is the "why now" — not a feature, a forcing function.
Transition: "Faster feedback compounds when CI runs more frequently."
-->


---
layout: section
---

<img src="/images/wiki-kuka-robot.jpg" class="absolute inset-0 w-full h-full object-cover opacity-28" />
<div class="absolute inset-0 bg-zinc-900/72 z-0" />
<div class="relative z-10">

# At Scale

<div class="pt-4 opacity-60">from your laptop to 52 modules</div>

</div>

<!--
- The last demo section.
- All those features compound at scale.
- Two slides set up the high-frequency angle, then the multi-module demo.
Transition: "Your suite runs on every push. Sometimes dozens a day."
-->


---
layout: center
class: bg-zinc-900 text-white
---

<img src="/images/wiki-kuka-robot.jpg" class="absolute inset-0 w-full h-full object-cover opacity-12" />
<div class="absolute inset-0 bg-zinc-900/82 z-0" />
<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

Your suite runs<br/>on every push.<br/>Sometimes dozens a day.

</div>

</div>

<!--
- CI runs on every push. Feature branches. PR checks. Fix → push → wait → fix → push.
- Every run pays the full suite cost.
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

Signal earlier in the run.<br/>Move on faster.

</div>

<div class="pt-8 text-xl text-center opacity-60">
  Zero config change. CI just runs <span class="font-mono text-emerald-300">mvn test</span>.
</div>

</div>

<!--
- Compounding: 80% earlier signal × many CI runs per day = a different dev loop.
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
class: bg-zinc-900 text-white
---

# Demo 4: SAP CDS Services

<DemoCard id="D4" duration="5 min" :cmd="`cd third-party/cds-services\n# The pain: kill at 90s, no test has started\nmvn clean test\n# The fix: affected-only on the same change\nmvn test-order:affected test`" title="52 Maven modules. Affected-only vs. full suite." watch="Pain: 90 s, compile crawl, no test started.Fix: ~55 s, RED build, right failure."></DemoCard>

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

You don't have to wait<br/>20 minutes anymore.

</div>

<v-click>

<div class="pt-6 text-xl text-center opacity-70">v0.1 · proof of concept · usable today in CI</div>

</v-click>

<v-click>

<div class="pt-4 text-2xl font-mono text-center">
  github.com/parttimenerd/test-order
</div>

</v-click>

</div>

<!--
- EXPLICIT CALLBACK: "At the start I asked who's waited 20 minutes for CI to tell you the failing test ran last. That wait is over."
- Pause after the big statement. Let it land before clicking.
- Install: ten lines POM, three lines Gradle.
- Reorder: `mvn test` twice. Failures surface earlier — typically in the first 20% of wall time.
- Measure: APFD every run. Dashboard for trends.
- Tune: weights tab. Meets you where you are.
- Say the URL twice — it's on the recording.
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
- "Develocity PTS / Launchable?" Both are strong at scale with months of failure history — ML on cloud history is genuinely powerful (Machalica 2019: 2× cost cut). test-order's trade-off: local, no training data, useful from run 2; their ML signal grows stronger over time once you have the history.
- "CI state sharing?" Cache .test-order/ between runs or commit it.
- "GitHub Actions cache?" Add .test-order/ to your cache key.
- "APFD vs wall time?" 22-min suite at APFD 85% = first failure ~minute 3, not 18.
- "Doesn't reordering cause OD failures?" Potentially — detect-dependencies mode finds them first. <2% OD rate in the OSS repos tested.
-->
