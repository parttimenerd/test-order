---
theme: seriph
title: "You Are Running the Wrong Tests First"
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

<div class="relative z-10">

# You Are Running the Wrong Tests First

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

<DemoCard id="D0" title="What we do today">

```bash
cd third-party/cloud-sdk-java
# invert currentTenantIsProvider() — adds !
./make-change.sh
mvn test
```

</DemoCard>

<!--
DEMO D0 — CI baseline, NO test-order. cloud-sdk-java, real SAP library.

Watch for: DestinationRetrievalStrategyResolverTest fails — but it runs alphabetically, AFTER
AuthTokenHeaderProviderTest, ConnectivityServiceTest, DestinationKeyStoreExtractorTest,
DestinationServiceAdapterTest, DestinationServiceAuthenticationTest. Five unrelated tests run before the broken one.

STEPS:
1. cd third-party/cloud-sdk-java
2. ./make-change.sh  — inverts Objects.equals → !Objects.equals in currentTenantIsProvider()
3. mvn test  — let it scroll

NARRATE as it runs:
- "This is cloud-sdk-java — SAP's open-source connectivity library. Real production code."
- "I just inverted one boolean. A tenant check. Objects.equals → !Objects.equals."
- "Alphabetical order. Auth… Connectivity… DestinationKeyStore… five tests before we get there."
- When DestinationRetrievalStrategyResolverTest finally fails: "There it is. We already knew exactly
  which class was broken. The runner had no idea."

After it finishes:
- "Every test that ran before DestinationRetrievalStrategyResolverTest was wasted time."

FALLBACK: asciinema play public/demo-d0.cast
NEXT: "Now let's give the runner that map."
-->

---
layout: center
title: "Research: Tests Closer to Changed Code Fail First"
class: quote-slide slide-base
---

<div class="absolute inset-0 bg-zinc-900/52 z-0" />

<div class="quote-text relative z-10">
  "Very few of our tests ever fail, but those that do are generally 'closer' to the code they test."
</div>
<div class="quote-attr relative z-10">Memon et al., Taming Google-Scale Continuous Testing, ICSE-SEIP 2017</div>

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

If a test <span class="c-blue">hasn't called</span> <br/>the changed code,<br/>it <span class="c-orange">cannot</span> fail on this change.

</div>

</div>

<!--
- "Cannot" — not "probably won't." Cannot. Emphasize the word
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

<v-click>

<div class="text-5xl font-bold mt-8 text-blue-400 tracking-wide">test-order</div>

</v-click>

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

<DemoCard id="D1" title="Same bug. One change. Test jumps to #1.">

```bash
cd third-party/cloud-sdk-java
./copy-in-db.sh
./make-change.sh
mvn test-order:show
mvn test
```

</DemoCard>

<!--
DEMO D1 — same project, same bug, test-order active.

Watch for: DestinationRetrievalStrategyResolverTest jumps to #1 in the ranking table.
APFD score printed at end of mvn test.

STEPS:
1. cd third-party/cloud-sdk-java
2. ./copy-in-db.sh  — restore pre-trained .test-order index
3. ./make-change.sh  — inject ! bug
4. mvn test-order:show  — show ranked table; point at Score and Why columns
5. mvn test  — DestinationRetrievalStrategyResolverTest runs first; APFD printed at end

NARRATE for :show output:
- "Every test has a score. And every score has a reason — the Why column."
- "This isn't a black box. You can see exactly why each test was prioritized."
- Point at DestinationKeyStoreExtractorTest: "Score driven by direct call
  into currentTenantIsProvider — the exact method I changed."

NARRATE as mvn test runs:
- "Same bug. Same project. The only difference: test-order knows which test covers that boolean."
- Let it reach the APFD line: "Closer to 1.0 means we surfaced the fault earlier."
- "You saved every test class that ran before DestinationRetrievalStrategyResolverTest in D0."

FALLBACK: asciinema play public/demo.cast
NEXT: while it runs, explain what's happening under the hood
-->

---
layout: default
class: slide-base
---

# The scoring formula

<ScoringBreakdown />

<v-click>

<div class="mt-2 text-base opacity-70">+ <strong>fail history</strong>: recent failures get an EMA boost</div>

</v-click>

<!--
- Scores are additive. Each signal is independent.
- Dep overlap is normalized by sqrt(deps × 5) — penalizes huge "integration" tests that touch everything.
- The EMA decay: a test that failed 3 runs ago still gets a small boost, fading each run.
- ResolverTest: it's the only test whose dep set directly contains DestinationRetrievalStrategyResolver — direct hit, ranked #1.
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
  <version>0.1.0</version>
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
    <version>0.1.0</version>
  </extension>
</extensions>
```

<div class="mt-5 flex gap-8 text-lg text-slate-400">
  <div>📦 shared index across modules</div>
  <div>🔍 <code>test-order:show</code> · <code>test-order:affected</code></div>
</div>
<div class="text-slate-600 text-base mt-3">Single-module: skip this step.</div>

<style>
.slidev-layout pre, .slidev-layout pre code, .slidev-layout .shiki { font-size: 1.3rem !important; line-height: 1.6 !important; }
.slidev-layout pre { padding: 1rem 1.2rem !important; }
</style>

<!--
- "Root of your reactor. Same folder as the parent pom."
- "It does two things."
- "One — shared index. Without it every module writes its own .test-order/. A test in module A that calls code in module B? The scorer never sees that dependency. This file puts the index at the root so the whole reactor shares one picture."
- "Two — the commands. mvn test-order:show, test-order:affected — they're reactor-level goals. Maven needs to know the plugin exists before it resolves the lifecycle. Without this file those commands just fail: unknown goal."
- "cloud-sdk-java has about 150 modules. One file, eight lines, everything works."
- "Single module? You don't need this. The pom entry is enough."
- Bonus: `mvn test-order:reactor-order` — suggests which Maven modules to build first based on the dep index (highest affected-test count first). Useful in very large reactors where even module resolution time matters.
- TRANSITION: "So what actually happens at runtime?"
-->

---
layout: default
title: "Setup — how it runs"
class: slide-base
---

# Step 3: `mvn test` — it auto-detects

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
- "First time you run it — no index exists — the bytecode agent attaches to Surefire. Every method entry in every class is recorded into a thread-local bitset, drained when the test method ends, serialised to .test-order/. Tests still run, results are normal. This costs about 13% overhead."
- "Every run after that — index is on disk — the agent is not attached. Maven loads the index, intersects it with git diff, scores every test, reorders. Zero instrumentation, zero overhead."
- "Commit .test-order/ to your repo, or cache it in CI. Then CI never pays the 13% again."
- "The index is just files. It travels with your branch. Everyone on the team gets ranked order immediately."
- TRANSITION: "Let me show it on cloud-sdk-java."
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
title: "How test-order hooks into JUnit 5 — ClassOrderer registration"
class: slide-base
---

# JUnit5 `ClassOrderer` registration

```properties
# junit-platform.properties
# written automatically by the plugin — you never touch this
junit.jupiter.testclass.order.default = \
  me.bechberger.testorder.junit.PriorityClassOrderer
```

<div class="mt-6 text-lg text-slate-300">The Maven plugin writes this file into the test classpath during <code>prepare</code>.</div>

<!--
- The Maven plugin writes junit-platform.properties into target/test-classes/ automatically. You never touch it.
- JUnit 5's DefaultLauncher reads junit-platform.properties before any test class is loaded. It instantiates the configured ClassOrderer and calls orderClasses() exactly once.
- No annotation on your test classes. No changes to your build scripts.
- "No Surefire hacks. No reflection on internal APIs. One interface, one properties file."
- TRANSITION: "Same story for method order."
-->

---
layout: default
title: "Affected Selection — Skip Unrelated Tests"
class: slide-base
---

# Zero overlap = zero runtime. Guaranteed.

<MavenLog :log="`
[INFO] --- test-order:0.1.0:affected (default-cli) @ connectivity-destination-service ---
[INFO] Changed: DestinationRetrievalStrategyResolver
[INFO] Selected 9 tests (5 scored + 4 new), deferred 10
BUILD SUCCESS
`" />

<div class="mt-6 text-base opacity-60">Over-approximation only — false positives, never false negatives.</div>

<!--
- "Ordering moves relevant tests first. Affected selection goes further — skips unrelated tests entirely."
- Real output from cloud-sdk-java: 1 changed class, 19 tests in the module, 9 run, 10 deferred (skipped entirely)
- False positives: extra tests run when overlap is ambiguous — safe
- False negatives: a relevant test skipped — never happens
- The dep index is an over-approximation of runtime coverage. Sound by construction
- TRANSITION: "so what does this buy you? Numbers"
-->


<!-- ═══ RESULTS ════════════════════════════════════════════════════════════════ -->

---
layout: default
class: slide-base
---

# CI without paying the learn tax every push

<div class="mt-6 grid grid-cols-3 gap-4 text-base">

<div class="p-4 rounded-lg" style="background: rgba(96,165,250,0.1); border: 1px solid rgba(96,165,250,0.3)">
  <div class="text-blue-400 font-bold mb-2">Nightly / merge</div>
  <code class="text-sm">mvn test</code>
  <div class="mt-2 opacity-70">Full learn run. Updates index. Commits <code>.test-order/</code> to cache.</div>
</div>

<div class="p-4 rounded-lg" style="background: rgba(251,191,36,0.1); border: 1px solid rgba(251,191,36,0.3)">
  <div class="text-amber-400 font-bold mb-2">Every PR push</div>
  <code class="text-sm">mvn test-order:affected test</code>
  <div class="mt-2 opacity-70">Only runs tests that overlap with changed classes. Fails fast.</div>
</div>

<div class="p-4 rounded-lg" style="background: rgba(74,222,128,0.1); border: 1px solid rgba(74,222,128,0.3)">
  <div class="text-green-400 font-bold mb-2">Full suite later</div>
  <code class="text-sm">mvn test</code>
  <div class="mt-2 opacity-70">Remaining tests in ranked order. Run in parallel if needed.</div>
</div>

</div>

<div class="mt-5 text-base opacity-60">One learn pass. Every push gets ranked order. Add <code>-Dtestorder.changeMode=committed</code> on CI.</div>

<!--
- affected test: runs only tests with dep overlap with changed classes — skips the rest entirely
- changeMode=committed: on CI there are no uncommitted changes — compare against last commit, not working tree
- The index is read-only on push runs — no instrumentation overhead
- "You learn once nightly. Every developer push gets the benefit of last night's index."
- For parallel CI: tiered-select + run-tier splits the ranked list into tiers by score bucket — mention only if asked
- TRANSITION: "so what does this buy you? Numbers"
-->




---
layout: default
class: slide-base
---

# Alphabetical: failure at ~70% of runtime — test-order surfaces it in &lt;10%

<BenchmarkChart />

<v-click>

<div class="pull-quote mt-3">
  Synthetic one-line bugs · commons-collections, commons-lang, jackson-core, spring-ai, logbook, spring-petclinic · each bar = mean ± std error over ~10 patches per repo
</div>

</v-click>

<!--
- X-axis: how far into the suite (by time) before the first failure appears.
- Red bar = alphabetical order — failure surfaces late, ~65–85% through the run.
- Green bar = test-order — failure surfaces in the first few percent.
- Error bars are standard error across ~10 synthetic one-line patches per repo.
- All bugs are synthetic one-liners (same methodology as Ekstazi/STARTS papers). Reproducible: scripts/bench_apfd.sh
- TRANSITION: "there's one place this matters even more than CI"
-->


<!-- ═══ BEYOND ORDERING ════════════════════════════════════════════════════════ -->

---
layout: default
title: "Index staleness"
class: slide-base
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
title: "More than ordering"
class: slide-base
---

# The learn run gives you more for free

<div class="mt-6 grid grid-cols-2 gap-4 text-base">

<div class="p-4 rounded-lg bg-blue-950/50 border border-blue-700/40">
  <div class="text-blue-300 font-bold mb-1">📊 Per-test coverage</div>
  <div class="opacity-70">Which classes each test actually calls — from bytecode.</div>
</div>

<div class="p-4 rounded-lg bg-amber-950/50 border border-amber-700/40">
  <div class="text-amber-300 font-bold mb-1">⚡ Flaky detection</div>
  <code class="text-sm">mvn test-order:flaky</code>
  <div class="mt-1 opacity-60 text-sm">STARTS / Ekstazi / Skippy can't do this.</div>
</div>

<div class="p-4 rounded-lg bg-rose-950/50 border border-rose-700/40">
  <div class="text-rose-300 font-bold mb-1">🔗 Order-dependent tests</div>
  <code class="text-sm">mvn test-order:orderdep</code>
  <div class="mt-1 opacity-60 text-sm">You're reordering anyway — OD bugs surface for free.</div>
</div>

<div class="p-4 rounded-lg bg-violet-950/50 border border-violet-700/40">
  <div class="text-violet-300 font-bold mb-1">⏭ Skip-if-unchanged caching</div>
  <code class="text-sm">testorder.cache.skipUnchanged=true</code>
  <div class="mt-1 opacity-60 text-sm">Stable deps + N green runs → skip entirely.</div>
</div>

</div>

<!--
- All four fall out of the same bytecode infrastructure — no extra config on top of the learn run.
- Coverage: dep index is already per-test. Dashboard shows a treemap.
- Flaky: re-run N times without code changes. If results differ, it's flaky. None of the pure selection tools do this.
- Order-dep: run each test in isolation and different orderings. Reordering is the point — OD bugs surface automatically.
- Caching: different from affected selection — no git diff needed. Pure dep stability + run history. Safety cap: maxSkipFraction=0.9.
-->

---

<img src="/images/black-cat.png" class="absolute right-8 bottom-0 h-4/5 z-0 object-contain object-bottom opacity-80" />
<div class="absolute inset-0 bg-zinc-900/70 z-[-1]" />

<div class="relative z-10">

# When NOT to use this

<div class="mt-4 mb-6 text-base text-amber-400/80 font-semibold">⚠ v0.1 — highly experimental. Works on many projects; breaks on some.</div>

<div class="space-y-4">

<div class="flex items-center gap-4 text-xl">
  <span class="tag-bad">&lt; 20</span>
  <span>Tiny suites — overhead isn't worth it</span>
</div>

<div class="flex items-center gap-4 text-xl">
  <span class="tag-bad">OPAQUE</span>
  <span>Reflection-heavy deps — missing edges reduce ranking precision</span>
</div>

<div class="flex items-center gap-4 text-xl">
  <span class="tag-ok">DYNAMIC</span>
  <span>Custom classloaders (OSGi, Quarkus dev) — use <code>instrumentation=online</code></span>
</div>

<div class="flex items-center gap-4 text-xl">
  <span class="tag-bad">FLAKY</span>
  <span>Highly flaky suites — quarantine first</span>
</div>

</div>

</div>

<!--
- "v0.1. I've run it on 20+ OSS projects. Most work. Some don't. That's the deal."
- Tiny suites (<20): instrumentation overhead isn't worth it; just parallelize
- Reflection-heavy: Class.forName, runtime-wired proxies — bytecode edges missing → over-approximates (runs extra tests, never skips needed ones), but ranking precision drops
- Dynamic classloaders: NOT a don't-use — switch to instrumentation=online; handles OSGi/Quarkus dev/plugin systems
- Flaky: corrupt the EMA failure-history signal; quarantine first with @QuarantinedTest (ABORTED not FAILED — build stays green)
- Note: Spring AOP and Mockito work fine — they go through bytecode
- "This earns trust. Nothing works everywhere"
-->


<!-- ═══ CTA ════════════════════════════════════════════════════════════════════ -->

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

</div>

<!--
- Callback to the opening hands-up question
- `mvn test-order:affected test` for the inner dev loop
-->


<!-- ═══ Q&A ══════════════════════════════════════════════════════════════════════ -->

---
layout: default
title: "Method ordering and Gradle"
class: slide-base
---

# Also works for…

<div class="mt-8 grid grid-cols-2 gap-8 text-lg">

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

</div>

<!--
- Method ordering: same dep index, one flag. Useful for large test classes with 50+ methods.
- Gradle: init script applies plugin to every Java subproject automatically. Same auto-detect logic.
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
  <img src="https://api.qrserver.com/v1/create-qr-code/?size=180x180&data=https://parttimenerd.github.io/test-order/&bgcolor=18181b&color=ffffff&qzone=1" class="rounded-lg" width="180" height="180" />
  <div class="text-sm font-mono opacity-80">parttimenerd.github.io/test-order</div>
  <div class="text-xs opacity-50">docs · repo</div>
</div>

<div class="flex flex-col items-center gap-3">
  <img src="https://api.qrserver.com/v1/create-qr-code/?size=180x180&data=https://mostlynerdless.de&bgcolor=18181b&color=ffffff&qzone=1" class="rounded-lg" width="180" height="180" />
  <div class="text-sm font-mono opacity-80">mostlynerdless.de</div>
  <div class="text-xs opacity-50">blog</div>
</div>

<div class="flex flex-col items-center gap-3">
  <img src="https://api.qrserver.com/v1/create-qr-code/?size=180x180&data=https://sapmachine.io&bgcolor=18181b&color=ffffff&qzone=1" class="rounded-lg" width="180" height="180" />
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
