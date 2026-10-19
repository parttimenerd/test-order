---
theme: seriph
title: "Stop Wasting Hours on Tests That Never Fail"
info: |
  test-order — 20-minute conference talk.
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
.big-statement {
  font-size: 3rem;
  font-weight: 700;
  line-height: 1.3;
  text-align: center;
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

<div class="relative z-10">

# Stop Wasting Hours on Tests 
# That Never Fail


<div class="pt-6 text-base opacity-50">
  Johannes Bechberger · @parttimenerd · SAP SE
</div>

</div>

<!--
- Punched cards at Bletchley Park — the original "sort by relevance" problem
- OPENING LINE (spoken): "Regression testing is an important but costly software engineering task." — Legunsen et al., FSE 2016. Pause. "And yet here we are, still running tests in alphabetical order."
-->

---
layout: default
title: "D0 — CI Baseline Demo"
class: slide-base
---

<DemoCard id="D0" title="Running tests takes time">

```bash
cd third-party/commons-collections
./make-change-commons.sh # remove one line from remove()
mvn test
```

</DemoCard>

<!--
DEMO D0 — CI baseline, NO test-order. prepare-demo.sh ensures .mvn/extensions.xml is absent.

Watch for: EnumerationIteratorTest fails — but it runs alphabetically, at position 237 out of 321.
That means 236 tests run first, wasting time.

SETUP (before going on stage — run prepare-demo.sh):
- Source is clean — make-change-commons.sh applies the patch live on stage
- NO test-order in .mvn/extensions.xml — setup-test-order.sh adds it live in D1

STEPS:
1. cd third-party/commons-collections
2. ./make-change-commons.sh  — apply the patch
3. mvn test  — let it scroll, do NOT kill it

NARRATE as it runs:
- "This is Apache Commons Collections. Real open-source project. 321 tests."
- "I removed one line from EnumerationIterator.remove() — a `last = null` reset."
- "Alphabetical order. A… B… C… E… we're at test 80 out of 321… still going…"
- Keep narrating as it crawls past 150, 200, 237
- When EnumerationIteratorTest finally fails: "There it is. Position 237. We ran 236 tests first."
- "Every one of those was wasted time. The runner had no idea which test covered that line."

After failure lands: let it sink in for 2 seconds. Then advance.

FALLBACK: asciinema play public/demo-d0.cast  (shows the full scrolling run ending at pos 237)
NEXT: "Now let's give the runner that map."
-->

---
layout: center
title: "Research: Tests Closer to Changed Code Fail First"
class: slide-base
---

<div class="absolute inset-0 bg-zinc-900/52 z-0" />

<QuoteCard
  attr="Memon et al., Taming Google-Scale Continuous Testing, ICSE-SEIP 2017"
  class="relative z-10"
>
  "Very few of our tests ever fail, but those that do are generally <em>'closer'</em> to the code they test."
</QuoteCard>

<img src="/images/google-scale-ct-qr.png" class="absolute bottom-6 right-6 w-24 h-24 z-10" />

<!--
- This is their exact wording. "Closer" = shorter path in the dependency graph.
- We operationalize "closer" as: deps(test) ∩ changed_classes — set intersection.
- Bigger intersection → test is more likely to fail on this change → higher score.
- TRANSITION: "which leads to a hard logical claim"
-->

---
layout: center
title: "Zero Overlap = Cannot Fail"
class: slide-base
---

<div class="absolute inset-0 bg-zinc-900/58 z-0" />

<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

If a test <span class="c-blue">hasn't called</span> <br/>the <span class="c-green">changed code</span>,<br/>it <span class="c-orange">cannot fail</span> on this change.

</div>

</div>

<!--
- "Cannot" — not "probably won't." Cannot. Emphasize the word
- "Changed code" = any class whose bytecode changed since last compile — detected automatically via opcode hash, no flags needed.
- Measured at Google scale; we act on it locally with bytecode instrumentation
- This is the guarantee that makes affected-test selection sound
- TRANSITION: "there's a tool that does exactly this — zero config, local, v0.1"
-->

---
layout: center
title: "Zero-Config Local Solution"
class: slide-base
---

<div class="absolute inset-0 bg-zinc-900/58 z-0" />
<div class="relative z-10 flex flex-col items-center text-center">

<div class="big-statement">

There's a zero-config,<br/>local solution.

</div>

<v-click/>

<div class="text-5xl font-bold mt-8 text-blue-400 tracking-wide">test-order</div>

</div>

<img src="/images/test-order-qr.png" class="absolute bottom-6 right-6 w-24 h-24 z-10" />

<!--
- Pause after "There's a zero-config, local solution." Let it sit. 3 seconds.
- Click: reveal 'test-order'. Still don't speak.
- TRANSITION: "Let me show you what that means."
-->

---
layout: default
title: "Failure detection — earlier vs later"
class: slide-base
---

<ApfdTimeline />

<div class="mt-3 text-sm text-center opacity-50">Failure found in first 10% of the suite vs. 80% — same tests, different order.</div>

<!--
- X axis: tests in the order they run. Y axis: cumulative faults found.
- Alphabetical: OrderTest runs 8th out of 10 — you don't know about the failure until 80% of the suite is done.
- Ranked: OrderTest runs first — failure at position 1. APFD jumps from ~55% to ~95%.
- APFD = area under that curve, normalised. Higher = earlier detection.
- "This is the pain. Every push, every time."
-->

---
layout: default
title: "How approaches compare"
class: slide-base
---

<LandscapeMap />

<!--
- X axis: time to first value — how long before you get meaningful results.
- Y axis: prioritization intelligence — how well it ranks failing tests first.
- Random/shuffle: free, immediate. Removes alphabetical bias, doesn't prioritize.
- Ekstazi/STARTS: re-instrument or re-analyse every run for freshness. Good accuracy, no staleness risk. Trade-off: overhead on every run.
- test-order: instrument once, pay 13% on run 1, zero extra overhead after that. Index can go stale — we detect and degrade gracefully. Different trade-off, not a better one.
- Coverage-based (Skippy, OpenClover): source instrumentation overhead on every run.
- Cloud TIA (Launchable, Develocity): best ceiling, but needs months of CI history and a cloud subscription.
- "Pick the trade-off that fits your workflow. If freshness is critical and overhead is fine, Ekstazi is a great choice. If you want zero overhead on order runs and can accept a nightly learn, test-order fits."
- TRANSITION: "Here's the mechanism."
-->

---
layout: default
class: slide-base
---

# Learn once. Rank on every run.

<div style="max-height: 90%; overflow: hidden; width:120%">
<PipelineDiagram />
</div>

<!--
- learn run: bytecode agent on, every method entry recorded → index.lz4
- order run: agent off, index loaded, git diff intersected → ranked order
- The index is just a file — commit it or cache it in CI
-->

<!-- ═══ DEMOS ═══════════════════════════════════════════════════════════════════ -->

---
layout: default
title: "D1 — Learn & Order Demo"
class: slide-base
---

<DemoCard id="D1" title="Same bug. Test jumps from #237 to #1.">

```bash
cd third-party/commons-collections
./setup-test-order.sh   # adds .mvn/extensions.xml 
                        # and restores index
./make-change-commons.sh
mvn test-order:show
mvn test
```

</DemoCard>

<!--
DEMO D1 — same project, same bug, test-order active.

Watch for: EnumerationIteratorTest jumps to #1 in the ranking table.
Kill with Ctrl+C as soon as the first red appears — ~10 seconds vs the 5-minute D0 run.

STEPS:
1. cd third-party/commons-collections
2. ./setup-test-order.sh  — adds test-order to .mvn/extensions.xml (shows diff), restores pre-trained index
3. ./make-change-commons.sh  — reapply the missing-last-null patch
4. mvn test-order:show  — show ranked table; point at Score and Why columns
5. mvn test  — EnumerationIteratorTest runs first; Ctrl+C after first RED line

NARRATE for setup-test-order.sh:
- "One file. That's it. .mvn/extensions.xml — you can see the diff right there."
- "And the pre-trained index is already in place — we learned it this morning."

NARRATE for :show output:
- "Every test has a score. And every score has a reason — the Why column."
- "This isn't a black box. You can see exactly why each test was prioritized."
- Point at EnumerationIteratorTest: "Score driven by direct call into remove() — the exact method I broke."

NARRATE as mvn test runs:
- "Same bug. Same 321 tests. The only difference: test-order knows which test covers that method."
- Ctrl+C at first red: "Done. The whole audience just saved four and a half minutes."
- "From position 237 to position 1. That's what a dep-based ranking buys you."

FALLBACK: asciinema play public/demo1.cast
NEXT: while it runs, explain what's happening under the hood
-->

---
layout: default
title: "D2 — spring-petclinic live edit"
class: slide-base
---

<DemoCard id="D2" title="Real project. Live edit. Wrong test first → right test first.">

```bash
cd third-party/spring-petclinic
mvn test                    # learn run — records dep map
./petclinic-make-change.sh  # edit VisitController.java
mvn test-order:show
mvn test                    # VisitControllerTests → #1
```

</DemoCard>

<!--
DEMO D2 — spring-petclinic, cold index.

SETUP (run prepare-demo.sh before going on stage):
- petclinic index ABSENT (or replaced with snapshot so we start fresh for the live learn)
- VisitController.java clean — make-change script applies it live

STEPS:
1. cd third-party/spring-petclinic
2. mvn test  — first run, learn mode. ~5 s. Show "[test-order] learn mode" line.
3. ./petclinic-make-change.sh  — edits flash message in processNewVisitForm
4. mvn test-order:show  — show ranked table; point at VisitControllerTests at #1
5. mvn test  — Ctrl+C after first RED line (~2 s)

NARRATE:
- After learn: "Spring Boot project. 15 tests, 5 seconds, dep map saved."
- After edit: "I changed one line in VisitController — a flash message."
- After show: "VisitControllerTests is ranked #1. Dep overlap hit."
- After red: "Same command. No config change. The right test ran first."

FALLBACK: screenshot pair — mvn test-order:show output + terminal with VisitControllerTests red at #1
NEXT: "And you can see the full evidence in the dashboard."
-->

---
layout: default
title: "D3 — Dashboard live demo"
class: slide-base
---

<DemoCard id="D3" title="The full picture — test-order:dashboard">

```bash
cd third-party/spring-petclinic
mvn test-order:dashboard
open target/test-order-dashboard/index.html
```

</DemoCard>

<!--
DEMO D3 — spring-petclinic, index warm from D2 run.

TABS TO SHOW (live, ~2 min):
1. Tests tab: VisitControllerTests at #1, score bar, "Why" breakdown, sparkline history
2. Analytics tab: APFD trend (D2 runs visible), rank heatmap
3. Weights tab: drag "dep overlap" slider → watch VisitControllerTests move in real time

NARRATE:
- "You recognise VisitControllerTests — that's the test we just saw go red."
- "Tests tab: rank, score, dep overlap count, run history. Evidence, not alphabetical order."
- On weights: "You can tune scoring for your project and see the effect live."
- "Three more tabs — ML health, mutations (PIT kill rate), static analysis — we won't walk those today."

FALLBACK: screenshots from public/images/ — dashboard-tests.png, dashboard-analytics.png, dashboard-weights.png
-->

---
layout: default
class: slide-base
---

# The scoring formula

<ScoringBreakdown />

<!--
Shown on slide (the three main signals for this example):
- Dep overlap: CartTest covers 1 of 21 deps that changed → +2 (normalized by sqrt(depTotal) to penalize broad integration tests)
- Pkg proximity: test lives in same package as changed class → +2
- Change complexity: 1 changed class in the dep set → +1

Not shown — also contribute to every score:
- Fail history: EMA over recent runs — a test that failed 3 runs ago still gets a decaying boost
- Changed test: test class itself was edited → large fixed bonus (always prioritized)
- New test: never run before → always moved to position 1 (we have no data, must run it)
- Speed: fast tests get a bonus, slow tests a penalty — keeps the feedback loop tight
- Name match: "CartTest" ↔ "Cart" — heuristic name proximity bonus
- Static field overlap: shares a static field (e.g. a shared fixture) with a changed class
- Set-cover / kill rate: unique coverage bonus for tests that cover ground no other selected test does

Total for CartTest: 5 → ranks #1 out of 321.
-->

---
layout: default
title: "Scores in the dashboard"
class: slide-base
---

<img src="/images/dashboard-tests.png" class="absolute inset-0 w-full h-full object-cover object-top" />

<!--
- The dashboard's Tests tab shows every test ranked by score.
- PLACE column: final ranking. SCORE: raw numeric score. OVERLAP: how many of its deps matched changed classes. HISTORY: EMA of recent failures.
- "This is what the runner sees. Not alphabetical order — evidence-based order."
-->

---
layout: default
title: "Setup — pom.xml"
class: slide-base
---

# Step 1: add to `pom.xml`

```xml {all|2-3|4|5|6-10}
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>test-order-maven-plugin</artifactId>
  <version>0.1.1</version>
  <extensions>true</extensions>
  <executions><execution>
    <goals>
      <goal>prepare</goal>
    </goals>
  </execution></executions>
</plugin>
```

<style>
.slidev-layout pre, .slidev-layout pre code, .slidev-layout .shiki { font-size: 1.3rem !important; line-height: 1.6 !important; }
.slidev-layout pre { padding: 1rem 1.2rem !important; }
</style>

<!--
Point at the screen as you click through.

- [all visible] "Nine lines. That's the entire install."
- [click 1] "groupId me.bechberger, artifactId test-order-maven-plugin. Central, no extra repo."
- [click 2] "<extensions>true</extensions> — this is the one line people miss. It registers the plugin as a build extension so it can hook into Surefire before any tests execute. Without it, the plugin loads too late and does nothing."
- [click 3] "One goal: prepare. It auto-detects whether to learn or order — you never pass a flag."
- "mvn test works exactly as before. Nothing else changes."
- TRANSITION: "If you're in a multi-module reactor, one more file."
-->

---
layout: default
title: "Setup — .mvn/extensions.xml"
class: slide-base
---

# Step 2: multi-module? Add `.mvn/extensions.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<extensions>
  <extension>
    <groupId>me.bechberger</groupId>
    <artifactId>test-order-maven-plugin</artifactId>
    <version>0.1.1</version>
  </extension>
</extensions>
```

<div class="mt-5 flex gap-8 text-lg text-slate-400">
  <div>📦 shared index across modules</div>
  <div>🔍 <code>test-order:show</code> · <code>test-order:affected</code></div>
</div>


<style>
.slidev-layout pre, .slidev-layout pre code, .slidev-layout .shiki { font-size: 1.3rem !important; line-height: 1.6 !important; }
.slidev-layout pre { padding: 1rem 1.2rem !important; }
</style>

<!--
- "Root of your reactor. Same folder as the parent pom."
- "It does two things."
- "One — shared index. Without it every module writes its own .test-order/. A test in module A that calls code in module B? The scorer never sees that dependency. This file puts the index at the root so the whole reactor shares one picture."
- "Two — the commands. mvn test-order:show, test-order:affected — they're reactor-level goals. Maven needs to know the plugin exists before it resolves the lifecycle. Without this file those commands just fail: unknown goal."
- "Large multi-module projects like Spring or Guava have 50–150 modules. One file, eight lines, everything works."
- "Single module? You don't need this. The pom entry is enough."
- Bonus: `mvn test-order:reactor-order` — suggests which Maven modules to build first based on the dep index (highest affected-test count first). Useful in very large reactors where even module resolution time matters.
- TRANSITION: "So what actually happens at runtime?"
-->

---
layout: default
title: "Setup — how it runs"
class: slide-base
---

# Step 3: `mvn test` auto-detects

<div class="mt-4 flex flex-col gap-5 text-xl">

<div class="p-4 rounded-xl bg-red-900/20 border border-red-500/30 flex items-start gap-4">
  <div class="text-red-400 font-bold text-2xl mt-0.5">①</div>
  <div>
    <div class="text-red-300 font-bold">No index found → learn run</div>
    <div class="text-slate-400 text-base mt-1">Bytecode agent on · records every method call · writes <code>.test-order/</code> · normal test results</div>
  </div>
</div>

<div class="p-4 rounded-xl bg-green-900/20 border border-green-500/30 flex items-start gap-4">
  <div class="text-green-400 font-bold text-2xl mt-0.5">②</div>
  <div>
    <div class="text-green-300 font-bold">Index found → order run</div>
    <div class="text-slate-400 text-base mt-1">Agent off · loads index · intersects with <code>git diff</code> · reorders tests · zero overhead</div>
  </div>
</div>

<div class="mt-1 text-slate-500 text-base">Commit or CI-cache <code>.test-order/</code> to skip the learn run on CI.</div>

</div>

<!--
- "No new commands. No flags. No profile switching. Just mvn test."
- "First time you run it — no index exists — the bytecode agent attaches to Surefire. Every method entry in every class is recorded into a thread-local bitset, drained when the test method ends, serialised to .test-order/. Tests still run, results are normal. This costs about 13% overhead (measured on spring-petclinic, MEMBER mode — docs/PERFORMANCE_OPTIMIZATIONS.md)."
- "Every run after that — index is on disk — the agent is not attached. Maven loads the index, intersects it with git diff, scores every test, reorders. Zero instrumentation, zero overhead."
- "Commit .test-order/ to your repo, or cache it in CI. Then CI never pays the 13% again."
- "The index is just files. It travels with your branch. Everyone on the team gets ranked order immediately."
- TRANSITION: "Let me show it on commons-collections."
-->

---
layout: default
class: slide-base
title: "The Transformation Pipeline"
---

# The transformation pipeline

<InstrumentationPipeline />

<!--
- Walk the diagram: class file → Surefire hook → ASM visitor inserts one invokestatic per method entry → fires into thread-local bitset → drained once when test method ends.
- "No source changes, no -javaagent flag, no agent jar on your classpath."
- ByteBuddy builds a full class object model — great for proxies, expensive when you touch 10 000 classes on every learn run. ASM visits the byte stream once.
- The 13% overhead is the instrumented learn run. Order runs carry zero instrumentation.
- Injected call: one invokestatic (5 bytes) per method entry, records class ID into a lock-free long[] bitset, drained once per test class.
-->

---
layout: default
title: "How test-order hooks into JUnit 5 — ClassOrderer"
class: slide-base
hide: t
---

# JUnit5 `ClassOrderer`

```java {all|2-3|5-8|9-10}
class PriorityClassOrderer implements ClassOrderer {
  // loads dep index + git diff + state
  SetupResult setup = ClassOrderingEngine.setup(config);

  public void orderClasses(ClassOrdererContext ctx) {
    Map<ClassDescriptor, Integer> scores = new HashMap<>();
    for (ClassDescriptor d : ctx.getClassDescriptors())
      scores.put(d, scorer.score(d.getTestClass().getName()));
      orderByScoreAndDiversity(ctx.getClassDescriptors(), 
                             scores);
  }
}
```

<!--
- ClassOrderer is a standard JUnit 5 SPI — just implement the interface, register via junit-platform.properties.
- orderClasses() is called exactly once by the JUnit launcher before any test class is instantiated. test-order loads the dep index, scores every ClassDescriptor, then clears and repopulates the list in-place. JUnit sees a reordered list — nothing else changes.
- "No Surefire hacks. No reflection on internal APIs. One interface, one properties file."
- TRANSITION: "How does JUnit know to use it?"
-->

---
layout: default
class: slide-base
---

# CI without paying the learn tax every push

<div class="mt-20 grid grid-cols-3 gap-4 text-base">

<div class="p-4 rounded-lg" style="background: rgba(96,165,250,0.1); border: 1px solid rgba(96,165,250,0.3)">
  <div class="text-blue-400 font-bold mb-2">Nightly / merge</div>
  <code class="text-sm">mvn test-order:learn test</code>
  <div class="mt-2 opacity-70">Full learn run. <br/>Commits <code>.test-order/</code> to cache.</div>
</div>

<div class="p-4 rounded-lg" style="background: rgba(251,191,36,0.1); border: 1px solid rgba(251,191,36,0.3)">
  <div class="text-amber-400 font-bold mb-2">Every PR push</div>
  <code class="text-sm">mvn test-order:affected test</code>
  <div class="mt-2 opacity-70">Only runs tests that overlap with changed classes. Fails fast.</div>
</div>

<div class="p-4 rounded-lg" style="background: rgba(74,222,128,0.1); border: 1px solid rgba(74,222,128,0.3)">
  <div class="text-green-400 font-bold mb-2">If affected succeed</div>
  <code class="text-sm">mvn test-order:run-remaining test</code>
  <div class="mt-2 opacity-70">Remaining tests in ranked order.</div>
</div>

</div>

<!--
- affected test: runs only tests with dep overlap with changed classes — skips the rest entirely
- changeMode=since-last-commit: on CI there are no uncommitted changes — compare against last commit, not working tree
- The index is read-only on push runs — no instrumentation overhead
- "You learn once nightly. Every developer push gets the benefit of last night's index."
- For parallel CI: tiered-select + run-tier splits the ranked list into tiers by score bucket — mention only if asked
- TRANSITION: "so what does this buy you? Numbers"
-->

---
layout: default
class: slide-base
---

# Real bugs: alphabetical surfaces failure at ~24% of runtime; test-order cuts it to ~3%

<BenchmarkChart />

<v-click>

<div class="pull-quote mt-3">
  18 real bugs · commons-collections, commons-io, guava, jackson-core, logbook, spring-ai · each bar = mean ± std error across real bugs only 
</div>

</v-click>

<!--
- X-axis: how far into the suite (by time) before the first failure appears.
- Red bar = alphabetical order — failure surfaces late, ~65–85% through the run.
- Green bar = test-order — failure surfaces in the first few percent.
- Error bars are standard error across real bugs per repo.
- All bars use real bugs only (18 patches across 6 repos). Reproducible: scripts/bench_apfd.sh
- TRANSITION: "what does this cost?"
-->

<!--
═══ BEYOND ORDERING ════════════════════════════════════════════════════════
-->

---
layout: default
title: "Overhead"
class: slide-base
---

## The cost: one learn run. Zero overhead after that.

<OverheadChart />

<!--
Chart shows wall-clock time as a multiple of the no-plugin baseline (1.0×). Three bars per repo = three instrumentation granularities:
- CLASS (blue): records which classes each test touched — cheapest, coarsest
- METHOD (purple): adds per-method entry/exit edges — finer-grained, slightly more overhead
- MEMBER* (pink, default): adds field read/write tracking on top — most precise, highest overhead

The overhead only applies to the learn run — the very first run after a change, when the agent is attached and recording the dependency map. Every subsequent run uses the saved index: no agent, no instrumentation, zero overhead.

Real numbers (7-run trimmed mean, log scale). Most projects: 4–56% overhead on the one learn run. spring-ai at ~3× is an outlier — it runs tests in parallel and test-order's instrumentation adds coordination cost. logbook (~50%) also uses parallel execution. For serial suites (commons-collections through jackson-core) overhead is 4–44%, dominated by JVM startup and compile time rather than instrumentation itself.

MEMBER* is the default because it produces the most accurate dependency edges — especially for tests that interact via shared state. CLASS is the right choice if you want minimum overhead and your tests don't rely on field-level coupling.

"You pay once. Every run after that — including all your CI runs if you commit the index — is free."

TRANSITION: "so what does this look like end to end? Let me show you."
-->


<!-- ═══ BEYOND ORDERING ════════════════════════════════════════════════════════ -->

---
layout: default
title: "Index staleness"
class: slide-base
hide: true
---

# What if the index goes stale?

<StalenessFlow />

<!--
- EVERY RUN: BytecodeHashStore.scan() re-hashes all .class files (opcode-level, strips debug info). Any class whose bytecode changed is added to the changed set automatically — no re-learn needed. Comment edits and reformats don't trigger it.
- DEP CHANGE: pom.xml/build.gradle fingerprint checked on every run. Mismatch → auto switches to learn mode. You never have to remember to re-learn after adding a dependency.
- UNKNOWN: new class not seen at learn time → all tests that could possibly touch it are included. Over-approximation only — safe. Never skips a test it shouldn't.
- Plus: BytecodeDependencyAugmenter adds any missing test→prod edges via static call graph analysis on every order run. Index gets better over time even without explicit re-learns.
-->

---
layout: default
title: "Per-test coverage"
class: slide-base
hide: tr
---

# The learn run maps what each test actually touches

<div class="mt-12 flex items-center gap-10 font-mono">
  <span class="px-5 py-3 rounded-xl bg-blue-900/60 border border-blue-500/50 text-blue-200 font-bold text-2xl">CartTest</span>
  <span class="text-slate-400 text-3xl">→</span>
  <div class="flex flex-col gap-3">
    <span class="px-4 py-2 rounded-lg bg-blue-900/40 border border-blue-600/40 text-blue-300 text-xl">CartService <span class="text-green-400 ml-3">✓</span></span>
    <span class="px-4 py-2 rounded-lg bg-blue-900/40 border border-blue-600/40 text-blue-300 text-xl">PriceCalc <span class="text-green-400 ml-3">✓</span></span>
    <span class="px-4 py-2 rounded-lg bg-slate-800/40 border border-slate-600/30 text-slate-500 text-xl">UserRepo <span class="text-slate-600 ml-3">—</span></span>
  </div>
</div>

<div class="mt-10 text-slate-400 text-2xl">From bytecode. Zero config. No JaCoCo.</div>

<!--
- The dep index is already per-test — a side effect of the learn run. Dashboard shows a treemap of what each test covers.
- TRANSITION: "And a bonus: it also sees your flaky tests."
-->

---
layout: default
title: "Flaky quarantine"
class: slide-base
hide: t
---

# Flaky tests don't have to break your build

<div class="mt-10 space-y-8 font-mono">
  <div>
    <div class="text-slate-500 text-lg mb-3">without</div>
    <div class="flex items-center gap-3 text-xl">
      <span class="px-4 py-2 rounded-lg bg-green-900/30 border border-green-600/40 text-green-300">PASS</span>
      <span class="px-4 py-2 rounded-lg bg-red-900/40 border border-red-500/50 text-red-300">FAIL</span>
      <span class="px-4 py-2 rounded-lg bg-green-900/30 border border-green-600/40 text-green-300">PASS</span>
      <span class="text-red-400 font-bold ml-3 text-2xl">→ BUILD FAILED</span>
    </div>
  </div>
  <div>
    <div class="text-slate-500 text-lg mb-3">with <code class="text-amber-300">retries=2</code></div>
    <div class="flex items-center gap-3 text-xl">
      <span class="px-4 py-2 rounded-lg bg-green-900/30 border border-green-600/40 text-green-300">PASS</span>
      <span class="px-4 py-2 rounded-lg bg-amber-900/40 border border-amber-500/50 text-amber-200">ABORTED</span>
      <span class="px-4 py-2 rounded-lg bg-green-900/30 border border-green-600/40 text-green-300">PASS</span>
      <span class="text-green-400 font-bold ml-3 text-2xl">→ BUILD OK</span>
    </div>
  </div>
</div>

<div class="mt-10 text-slate-500 text-lg"><code>testorder.flaky.retries=2</code> &nbsp;·&nbsp; <code>testorder.flaky.quarantine=true</code></div>

<!--
- testorder.flaky.retries=N retries the test N times before marking ABORTED (not FAILED).
- testorder.flaky.quarantine=true makes ABORTED non-blocking — build stays green while you investigate.
- TRANSITION: "And two more goals worth knowing about."
-->

---
layout: center
title: "CTA — Try It Today"
class: slide-base
---

<img src="/images/wiki-loc-catalog.jpg" class="absolute inset-0 w-full h-full object-cover opacity-50" />
<div class="absolute inset-0 bg-zinc-900/52 z-0" />

<div class="relative z-10 flex flex-col items-center text-center">

<div class="big-statement">

You don't have to wait<br/>20 minutes anymore.

</div>

<div class="pt-8 text-xl opacity-70">v0.1 · Apache 2.0 · PRs welcome</div>

<div class="pt-4 text-2xl font-mono text-sky-300">
  parttimenerd.github.io/test-order
</div>

<img src="/images/test-order-qr.png" class="rounded-lg" width="80" />

</div>

<!--
- Callback to the opening hands-up question
- `mvn test-order:affected test` for the inner dev loop
-->

<!--
═══ Q&A ══════════════════════════════════════════════════════════════════════
-->

---
layout: default
title: "Method ordering and Gradle"
class: slide-base
---

# Also works for…

<div class="mt-6 grid grid-cols-2 gap-6 text-lg">

<div>
  <div class="text-purple-300 font-bold mb-2">Method ordering</div>
  <code>-Dtestorder.methodOrder.enabled=true</code>
  <div class="mt-2 opacity-60 text-base">Reorders methods within each class.</div>
</div>

<div>
  <div class="text-blue-300 font-bold mb-2">Gradle</div>
  <code>./gradlew test --init-script test-order-init.gradle</code>
  <div class="mt-2 opacity-60 text-base">Zero changes to <code>build.gradle</code>.</div>
</div>

<div>
  <div class="text-amber-300 font-bold mb-2">Annotations</div>
  <code>@AlwaysRun</code> · <code>@QuarantinedTest</code>
  <div class="mt-2 opacity-60 text-base">Pin tests to position 1 or quarantine flaky ones.</div>
</div>

<div>
  <div class="text-green-300 font-bold mb-2">Multi-framework</div>
  <div class="mt-2 opacity-60 text-base">JUnit 4/5/6 · TestNG · Kotest — Maven and Gradle.</div>
</div>

</div>

<!--
- Method ordering: same dep index, one flag. Useful for large test classes with 50+ methods.
- Gradle: init script applies plugin to every Java subproject automatically. Same auto-detect logic.
- @AlwaysRun: pinned to slot 1 every run regardless of score. Use for smoke tests / sanity checks.
- @QuarantinedTest: test still runs but results are isolated; flaky tests can't pollute the score history.
-->

---
layout: none
title: "Q&A"
class: slide-base
---

<img src="/images/wiki-loc-catalog.jpg" class="absolute inset-0 w-full h-full object-cover opacity-45" />
<div class="absolute inset-0 bg-zinc-900/58" />

<div class="absolute inset-0 flex flex-col items-center justify-center text-center px-12">

<div class="big-statement" style="font-size:4rem">

Questions?

</div>

<div class="mt-12 flex gap-12 items-start justify-center w-full">

<div class="flex flex-col items-center gap-3">
  <img src="/images/test-order-qr.png" class="rounded-lg" width="180" height="180" />
  <div class="text-sm font-mono opacity-80">parttimenerd.github.io/test-order</div>
  <div class="text-xs opacity-50">docs · repo</div>
</div>

<div class="flex flex-col items-center gap-3">
  <img src="/images/sapmachine-qr.png" class="rounded-lg" width="180" height="180" />
  <div class="text-sm font-mono opacity-80">sapmachine.io</div>
  <div class="text-xs opacity-50">my team</div>
</div>

</div>

</div>

<!--
Keep this slide up for the full Q&A.

Likely questions:
- "Kotlin / Scala?" JVM bytecode — source language is irrelevant.
- "Parallel test execution?" Class-level: ordering is a scheduling priority, fine.
  Method-level: per-thread bitset, fine.
- "Can the index be wrong?" Over-approximation only — false positives, never false negatives.
- "Develocity PTS / Launchable?" Both are strong once you have months of failure history — ML signal is genuinely powerful at scale. test-order's trade-off: local, no training data needed, useful from run 2; their accuracy grows stronger over time.
  Machalica et al. (Facebook, ICSE 2019): 2× cost reduction with ML — but requires labelled failure data, a training pipeline, and data egress.
- "CI caching?" Cache `.test-order/` between runs (tiny) or commit to git.
- "How does APFD relate to wall time?" 22-min suite at 85% APFD = first failure at ~minute 3, not minute 18.
- "Order-dependent tests?" Li et al. (ISSTA 2023): Tuscan-square designs, 97.2% detection at ~105 test orders vs. n! brute force.
- "Why not just run tests in parallel?" Parallelism reduces total time; ordering reduces time-to-first-failure. They compose.
-->
