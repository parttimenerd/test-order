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


<!-- ═══ HOOK ═══════════════════════════════════════════════════════════════════ -->

---
layout: center
class: slide-base
---

<img src="/images/wiki-apollo10-mission-control.jpg" class="absolute inset-0 w-full h-full object-cover opacity-15" />
<div class="absolute inset-0 bg-zinc-900/75 z-0" />

<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

Who's waited 20 minutes for CI<br/>
to tell you the test that failed<br/>
ran last, alphabetically?

</div>

<div class="hands-up mt-10">✋ raise your hand</div>

</div>

<!--
- Hands up. Wait. "Keep yours up if it's more than once a week."
-->


<!-- ═══ PAIN DEMO ════════════════════════════════════════════════════════════════ -->

---
layout: default
class: slide-base
---

<DemoCard id="D0" :cmd="`cd third-party/spring-petclinic\n# No test-order. Plain mvn test.\nmvn test -pl . -Dsurefire.failIfNoSpecifiedTests=false`" title="What CI does today" watch="Tests run in JVM discovery order. VisitControllerTests runs 9th out of 18. You broke something there. Spring boots up multiple times before you find out."></DemoCard>

<!--
- Run this BEFORE the talk starts — show the scrollback or replay with asciinema.
- Spring context starts cold: ~8s per context. By the time VisitControllerTests runs you've waited through 8 other tests.
- Actual order: VetControllerTests → VetTests → OwnerControllerTests → PetValidatorTests → PetControllerTests → VisitControllerTests → …
- "You already knew which test. The runner just didn't."
- FALLBACK: asciinema play public/demo-d0.cast
- TRANSITION: "here's what that wait looks like, drawn out"
-->


---
layout: default
class: slide-base
---

<ApfdTimeline />

<!--
- The timeline bar IS the pain. Point at it silently for 2 seconds first.
- "Every one of those passing tests ran before the one that found your bug."
- "The bytecode already knew. We just never gave the runner that map."
- v-click: say this sentence slowly. It's the entire talk in one line.
- TRANSITION: "the underlying idea is well-grounded though — and old"
-->


<!-- ═══ THE IDEA ════════════════════════════════════════════════════════════════ -->


---
layout: center
class: quote-slide slide-base
---

<img src="/images/wiki-widener-card-catalog.jpg" class="absolute inset-0 w-full h-full object-cover opacity-20" />
<div class="absolute inset-0 bg-zinc-900/72 z-0" />

<div class="quote-text relative z-10">
  "Very few of our tests ever fail, but those that do are generally 'closer' to the code they test."
</div>
<div class="quote-attr relative z-10">Memon et al., Taming Google-Scale Continuous Testing, ICSE-SEIP 2017</div>

<!--
- This is their exact wording. "Closer" = shorter path in the dependency graph.
- We operationalize "closer" as: deps(test) ∩ changed_classes — set intersection.
- Bigger intersection → test is more likely to fail on this change → higher score.
- TRANSITION: "which leads to a hard logical claim"
-->


---
layout: center
class: slide-base
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
- TRANSITION: "there's a tool that does exactly this — zero config, local, v0.1"
-->


---
layout: center
class: slide-base
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

</div>

<!--
- Pause after "There's a zero-config, local solution." Let it sit. 3 seconds.
- Click: reveal the name. Still don't speak.
- Click: reveal the tagline.
- "It's called test-order. I spent two years of evenings building it so you don't have to."
TRANSITION: "How does it compare to what you might already know?"
-->


<!-- ═══ INSTALL ══════════════════════════════════════════════════════════════════ -->

---
layout: default
class: slide-base
---

# Learn once. Rank on every run.

<div style="max-height: 78%; overflow: hidden">
<PipelineDiagram />
</div>

<!--
- learn run: bytecode agent on, every method entry recorded → index.lz4
- order run: agent off, index loaded, git diff intersected → ranked order
- The index is just a file — commit it or cache it in CI
-->


---
layout: default
class: slide-base
---

# Ten lines of POM. Then `mvn test`.

```xml {all|2-3|4|5-9}
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>test-order-maven-plugin</artifactId>
  <version>0.1.0</version>
  <extensions>true</extensions>
  <executions><execution>
    <goals><goal>prepare</goal></goals>
  </execution></executions>
</plugin>
```

<div class="mt-4 flex gap-8 text-xl">
  <div><span class="text-red-400 font-bold">No index found</span> → learn run · instruments classes · writes <code>.test-order/</code></div>
  <div><span class="text-green-400 font-bold">Index found</span> → order run · no instrumentation · zero overhead</div>
</div>

<div class="mt-3" style="font-size: 0.9rem; opacity: 0.6">
  Multi-module: also add to <code style="background: rgba(255,255,255,0.07); padding: 0.1em 0.4em; border-radius: 4px">.mvn/extensions.xml</code> for cross-module index merging
</div>

<style>
.slidev-layout pre, .slidev-layout pre code, .slidev-layout .shiki { font-size: 1.25rem !important; line-height: 1.55 !important; }
.slidev-layout pre { padding: 0.9rem 1.1rem !important; }
</style>

<!--
- Click 1: groupId/artifactId — "me.bechberger, test-order-maven-plugin"
- Click 2: extensions=true — the #1 install mistake
- Click 3: executions — prepare auto-detects: no index → learn, index found → order
- "mvn test works exactly as before. No flags."
- TRANSITION: "let me run it from scratch"
-->


<!-- ═══ DEMOS ═══════════════════════════════════════════════════════════════════ -->

---
layout: default
class: slide-base
---

<DemoCard id="D1" :cmd="`cd samples/sample-shop\nmvn test                  # learn run\nmvn test                  # order run (order changed)\nmvn test-order:show       # scores + why`" title="First run: learning. Second run: ordered." watch="Run #1: &quot;Auto-instrumenting 42 classes&quot;. Run #2: tests reordered, APFD printed. :show gives score + why column per test."></DemoCard>

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
class: slide-base
---

# Why tests that touch Cart move to the top

<div class="flex flex-col gap-6 mt-8" style="font-size: 1.15rem">

<div class="flex items-center gap-3">
  <div class="chain-box changed">Cart.java edited</div>
  <div class="chain-arrow">→</div>
  <div class="chain-box index">InvoiceTest touches Cart+Invoice<br/><span style="font-size:0.8em;opacity:0.7">2/3 deps overlap → score 8</span></div>
  <div class="chain-arrow">→</div>
  <div class="chain-box top">InvoiceTest = #1</div>
</div>

<div class="flex items-center gap-3">
  <div class="chain-box changed">Cart.java edited</div>
  <div class="chain-arrow">→</div>
  <div class="chain-box index">CartTest touches Cart<br/><span style="font-size:0.8em;opacity:0.7">1/2 deps overlap → score 6</span></div>
  <div class="chain-arrow">→</div>
  <div class="chain-box top2">CartTest = #2</div>
</div>

<div class="flex items-center gap-3">
  <div class="chain-box neutral">ProductTest</div>
  <div class="chain-arrow">→</div>
  <div class="chain-box neutral-dim">never touched Cart<br/><span style="font-size:0.8em;opacity:0.7">zero overlap</span></div>
  <div class="chain-arrow">→</div>
  <div class="chain-box skip">stays low</div>
</div>

</div>

<div class="mt-8" style="font-size: 0.95rem; opacity: 0.5">No retraining. The dep index is a static set intersection — built once, used forever.</div>

<style>
.chain-box { border-radius: 8px; padding: 0.5rem 0.9rem; font-weight: 600; line-height: 1.3; }
.changed  { background: rgba(248,113,113,0.15); border: 1.5px solid #f87171; color: #fca5a5; white-space: nowrap; }
.index    { background: rgba(167,139,250,0.12); border: 1.5px solid #a78bfa; color: #c4b5fd; }
.top      { background: rgba(74,222,128,0.15);  border: 1.5px solid #4ade80; color: #86efac; white-space: nowrap; }
.top2     { background: rgba(74,222,128,0.10);  border: 1.5px solid #22c55e; color: #4ade80; white-space: nowrap; }
.neutral  { background: rgba(148,163,184,0.10); border: 1.5px solid #475569; color: #94a3b8; white-space: nowrap; }
.neutral-dim { background: rgba(71,85,105,0.08); border: 1.5px solid #334155; color: #64748b; }
.skip     { background: rgba(71,85,105,0.12);   border: 1.5px solid #475569; color: #64748b; white-space: nowrap; }
.chain-arrow { color: #475569; font-size: 1.3rem; flex-shrink: 0; }
</style>

<!--
- The learn run built a map: test → {classes it touched}
- On every subsequent run: git diff gives changed classes; intersect with map → rank
- InvoiceTest depends on Cart AND Invoice → higher overlap (2/3 deps) → score 8
- CartTest depends on Cart only → lower overlap (1/2 deps) → score 6
- ProductTest never touched Cart → zero overlap, runs last
- "The intersection already knows. No ML, no retraining, no history needed."
-->


---
layout: default
class: slide-base
---

<DemoCard id="D2" :cmd="`# Add null-check to Cart.add(), one line\n$EDITOR src/main/java/com/example/shop/Cart.java\nmvn spotless:apply   # project enforces formatting\nmvn test\nmvn test-order:show`" title="One method changed. Tests that touch Cart jump to the top." watch="InvoiceTest #1 (deps: Cart+Invoice), CartTest #2 (dep: Cart). Nothing retrained: a set intersection on the existing index."></DemoCard>

<!--
DEMO STEPS:
1. Open Cart.java in add(), insert:
     if (product == null)
         throw new IllegalArgumentException();
2. mvn spotless:apply  ← IMPORTANT: project enforces Palantir Java format; skip this → BUILD FAILURE
3. mvn test → "1 changed class detected (Cart)", InvoiceTest #1, CartTest #2
4. mvn test-order:show → InvoiceTest score 8 (2/3 deps overlap), CartTest score 6 (1/2 deps overlap)

- InvoiceTest scores higher because it depends on BOTH Cart AND Invoice (2 of 3 deps).
- CartTest depends only on Cart (1 of 2 deps). Both tests are boosted; InvoiceTest wins on overlap.
- "No retraining. No ML pipeline. A set intersection."
- PAUSE after the rank shift appears. Let the room react.
- FALLBACK: asciinema play public/demo.cast (skip to rank-shift section)
- TRANSITION: "You've seen it work. Now — where does it fit relative to tools you may already know?"
-->


---
layout: default
class: slide-base
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
- test-order: top-left — local, zero-config, useful from run 2 without training data.
- "These aren't competing products. They're different trade-offs for different teams."
- TRANSITION: "Here's what's actually happening under the hood."
-->


<!-- ═══ UNDER THE HOOD ════════════════════════════════════════════════════════ -->

---
layout: center
---

<img src="https://parttimenerd.github.io/tiny-llm-library-demo/assets/cat-socks-oval-office-Bj7wbdg6.jpg" class="absolute inset-0 w-full h-full object-cover opacity-90" />
<div class="absolute inset-0 bg-zinc-900/55 z-0" />

<!--
- Pause. "Let's look under the hood."
-->


---
layout: section
---

<img src="/images/wiki-cat-reading.jpg" class="absolute inset-0 w-full h-full object-cover opacity-28" />
<div class="absolute inset-0 bg-zinc-900/82 z-0" />
<div class="relative z-10 text-center">

# Under the Hood

<div class="pt-4 opacity-60">how the index gets built</div>

</div>

<!--
- "You've seen it work and seen where it fits. Here's the mechanism."
- Brief — 4 slides.
- TRANSITION: "It starts with bytecode."
-->


---
layout: default
class: slide-base
---

# The transformation pipeline

<InstrumentationPipeline />

<!--
- Walk the diagram: class file → Surefire hook → ASM visitor inserts one invokestatic per method entry → fires into thread-local bitset → drained once when test method ends.
- "No source changes, no -javaagent flag, no agent jar on your classpath."
- ByteBuddy builds a full class object model — great for proxies, expensive when you touch 10 000 classes on every learn run. ASM visits the byte stream once.
- The 13% overhead is the instrumented learn run. Order runs carry zero instrumentation.
Transition: "That bitset is the key — let me show you how the scoring uses it."
-->


---
layout: default
class: slide-base
---

# JaCoCo instruments every run. We instrument once.

<InstrumentationOverhead />

<div class="mt-4 text-sm text-slate-400 text-center">
  JaCoCo: full bytecode rewrite on <em>every</em> run to measure line coverage ·
  test-order: one learn run (~13% overhead), then zero
</div>

<!--
- JaCoCo's goal is different — it measures what lines ran, so it must run on every invocation.
- test-order's goal is a dep map: which test touched which class. Once recorded, it doesn't change unless the code changes.
- The key difference: JaCoCo probes every branch; test-order probes only method entry (1 invokestatic per method = 5 bytes).
- Both use ASM under the hood. The overhead difference comes from what you record, not how.
- Spring AOP and Mockito work fine — they instrument through bytecode too.
- "If you already run JaCoCo in CI, the learn run cost is already something you pay. test-order adds zero on top."
- TRANSITION: "The next slide shows exactly what each tool adds to your bytecode."
-->


---
layout: default
class: slide-base
---

# What each tool injects

<style>
.cmp-wrap { display: flex; gap: 1.5rem; align-items: flex-start; margin-top: 0.5rem; }
.cmp-col  { flex: 1 1 0; min-width: 0; }
.cmp-col pre, .cmp-col pre code, .cmp-col .shiki {
  font-size: 0.82rem !important; line-height: 1.42 !important;
  padding: 10px 12px !important; overflow: hidden !important;
  white-space: pre !important;
}
.col-label { font-size: 0.88rem; font-weight: 700; letter-spacing: 0.05em; margin-bottom: 0.35rem; }
.cmp-note  { font-size: 0.78rem; color: #94a3b8; margin-top: 0.5rem; line-height: 1.5; }
</style>

<div class="cmp-wrap">

<div class="cmp-col">
<div class="col-label text-red-400">JaCoCo — probes every line &amp; branch</div>

```java
// injected field + init on every load
boolean[] $jacocoData =
  $jacocoInit()[42];

public int add(int a, int b) {
  $jacocoData[0] = true; // line probe
  return a + b;          // + branch probes
}                        //   on every run
```

<div class="cmp-note">
  ↳ every run pays the probe cost<br/>
  ↳ branch arrays for every <code>if</code>/<code>for</code>/<code>?:</code><br/>
  ↳ goal: line/branch <strong>coverage report</strong>
</div>
</div>

<div class="cmp-col">
<div class="col-label text-green-400">test-order — one call at method entry</div>

```java
// nothing injected into class

public int add(int a, int b) {
  UsageStore.recordUsageIdFast(42);
  return a + b;
}
// learn run only — stripped on order runs
```

<div class="cmp-note">
  ↳ learn run only (~13% overhead)<br/>
  ↳ one <code>invokestatic</code> per method (5 bytes)<br/>
  ↳ goal: <strong>dep map</strong> test → classes touched
</div>
</div>

</div>

<!--
- The original source: `public int add(int a, int b) { return a + b; }`
- JaCoCo injects a boolean[] field into every class, calls $jacocoInit() at class load,
  and inserts a probe (array store) before every line and at every branch.
- test-order inserts ONE invokestatic at method entry. That's literally 5 bytes:
  BIPUSH <id>, INVOKESTATIC recordUsageIdFast(I)V — from AsmClassTransformer.visitCode().
- The class field injection is why JaCoCo must run on every invocation: the probes are
  reset between runs. test-order writes to a thread-local bitset, drained once when the
  test method ends, then serialised to .test-order/test-dependencies.lz4.
- Order runs have ZERO instrumentation — the transformer is not attached.
- "Same ASM machinery. Completely different goals and completely different overhead profiles."
- TRANSITION: "Why is the overhead so low? Three design choices."
-->


---
layout: default
class: slide-base
---

# Why ~13%? Three design choices

| Choice | What it avoids |
|---|---|
| **ASM streaming** | No intermediate object model — visits 10k classes as byte streams |
| **Thread-local bitset** | `bits[id>>>6] \|= 1L<<id` — no lock on the hot path |
| **Learn once** | Transformer not attached on order runs — zero bytecode overhead after run #1 |

<!--
- ASM: streaming visitor model — reads class bytes once, never builds a DOM. ByteBuddy (used in first prototype) had to construct a full CtClass model; 10× more allocations per class.
- Thread-local bitset: bits[id>>>6] |= 1L<<id — a single bitwise OR with no lock. Compare: ConcurrentHashMap per method would be 100× slower.
- Learn once: the Surefire ClassFileTransformer is only registered during the learn run. On order runs, mvn test is stock Surefire — nothing injected.
- "The 13% is the cost of one-pass streaming instrumentation. Everything else is zero."
- TRANSITION: "That bitset feeds the scoring model."
-->


---
layout: default
class: slide-base
---

# How CartTest scored 14

<ScoringBreakdown />

<v-click>

<div class="mt-3 text-base opacity-70">+ <strong>fail history</strong>: tests that failed recently get an EMA boost — surfaces flaky tests automatically</div>

</v-click>

<!--
- Changed test (+9): CartTest.java itself was edited
- Package proximity (+2): same package as Cart
- Dep overlap (+2): Cart is in deps(CartTest), ⌈1/√8×5⌉ = 2
- Speed bonus (+1): fast test, below median duration
- Fail history: exponential moving average of failure rate — a test that failed 3 runs ago still gets a boost, decaying over time
- All signals additive. Configurable via weights tab (see the weights tab in D3 dashboard).
- Score 0 = test never touched any changed class → runs last
-->


---
layout: default
class: slide-base
---

# The plain file that runs the runner

```bash {1|3-5}
ls -lh .test-order/test-dependencies.lz4   # ~12 KB

java -jar test-order.jar deps \
  .test-order/test-dependencies.lz4 com.example.shop.CartTest
# → com.example.shop.Cart, Invoice … (8 classes)
```

<!--
- This is the "it's just a file" moment — demystifies the black box.
- The CLI jar ships alongside the plugin. No separate install.
- If a test ranks unexpectedly, this is the first debugging step.
- TRANSITION: "that's a toy shop — let's do a real Spring project"
-->


---
layout: default
class: slide-base
---

<DemoCard id="D3" :cmd="`cd third-party/spring-petclinic\n# index from nightly learn run — zero overhead today\nmvn test\nmvn test-order:dashboard`" title="Real Spring Boot project. Live APFD. Then the dashboard." watch="First failure surfaces early in terminal. Dashboard: APFD trend, rank heatmap, score breakdown modal, weights tuning."></DemoCard>

<!--
DEMO STEPS:
1. cd third-party/spring-petclinic
2. mvn test → "learn ran 2025-01-10, index loaded" → order run → APFD line printed
3. mvn test-order:serve → opens browser
4. Walk: Tests tab (score breakdown modal) → Analytics tab (APFD trend) → Coverage tab (treemap) → Weights tab (sliders)

- "The learn run ran last night in CI. Today's run is zero overhead"
- "This is the normal workflow: learn once in CI, rank on every dev run"
- FALLBACK: screenshots in slides that follow — flip through them if browser fails
- TRANSITION: while the suite runs, cover the CI cost model on the next slide
-->


---
layout: default
class: slide-base
---

<DemoCue>demo running, return at "Tests tab"</DemoCue>

# What's happening right now

<div class="mt-8 grid grid-cols-3 gap-6 text-center">

<div class="p-6 rounded-lg bg-blue-950/60 border border-blue-800/50">
  <div class="text-4xl mb-3">🔍</div>
  <div class="font-semibold text-blue-300 text-xl">Attach</div>
  <div class="text-sm text-slate-400 mt-2">hooks into every class loader</div>
</div>

<div class="p-6 rounded-lg bg-violet-950/60 border border-violet-800/50">
  <div class="text-4xl mb-3">📝</div>
  <div class="font-semibold text-violet-300 text-xl">Record</div>
  <div class="text-sm text-slate-400 mt-2">method entry → thread-local bitset</div>
</div>

<div class="p-6 rounded-lg bg-green-950/60 border border-green-800/50">
  <div class="text-4xl mb-3">💾</div>
  <div class="font-semibold text-green-300 text-xl">Write</div>
  <div class="text-sm text-slate-400 mt-2">.test-order/test-dependencies.lz4</div>
</div>

</div>

<!--
- Attach: bytecode transformer hooks into every class loader
- Record: every method entry writes (test → class) to a thread-local bitset
- Write: suite finishes → .test-order/test-dependencies.lz4
- "The learn run ran last night in CI. Today's run is zero overhead — the transformer isn't even attached."
- If the suite finishes fast, skip v-clicks and go straight to the dashboard
- TRANSITION: when the APFD line prints, open the dashboard
-->


---
layout: default
class: slide-base
---

# Tests tab

<img src="/images/dashboard-tests.png" class="w-full rounded-lg mt-2" style="max-height: 530px; object-fit: cover; object-position: top;" />

<!--
- Ranked list — CartTest at #1, score 16, "new test +15, speed +1"
- Click any row: score breakdown modal shows exactly which signal contributed what
- "Every score is debuggable. No black box."
- TRANSITION: analytics tab shows the trend over time
-->


---
layout: default
class: slide-base
---

# Analytics tab

<img src="/images/dashboard-analytics.png" class="w-full rounded-lg mt-2" style="max-height: 530px; object-fit: cover; object-position: top;" />

<!--
- APFD score over runs: starts at 50% (alphabetical), climbs as the index warms
- Score distribution chart bottom-left: most tests score low (unrelated), a few score high (changed deps)
- Dependency count chart: tells you which tests are heavily coupled
- TRANSITION: coverage tab is what makes test-order different from a pure ordering tool
-->


---
layout: default
class: slide-base
---

# Coverage tab

<img src="/images/dashboard-coverage.png" class="w-full rounded-lg mt-2" style="max-height: 530px; object-fit: cover; object-position: top;" />

<!--
- The dep index IS a coverage map: every class reachable from a test is "covered" by that test.
- Treemap: class size = method count, colour = coverage. Red = uncovered, green = well-tested.
- "You get this for free. No JaCoCo config. The learn run already built this map."
- Class coverage 89%, method coverage 85% shown in the header — comparable to a proper coverage tool.
- This is the strongest answer to "why not just use JaCoCo?" — test-order gives you coverage as a side-effect of ordering.
- TRANSITION: weights tab lets you tune the scoring signals
-->


---
layout: default
class: slide-base
---

# Weights tab

<img src="/images/dashboard-weights.png" class="w-full rounded-lg mt-2" style="max-height: 530px; object-fit: cover; object-position: top;" />

<!--
- Sliders: changedTest, changeAround, depOverlap, speedBonus, failHistory…
- Drag a slider → ranked list updates live, no rerun needed
- "Day-one you don't touch this. But if you have a monorepo with 2000 tests and a specific failure pattern, this is where you go."
- mvn test-order:serve to open the dashboard (mention once, it's in the repo README)
- TRANSITION: "ordering is one mode — the other is skipping"
-->


---
layout: default
class: slide-base
---

# Zero overlap = zero runtime. Guaranteed.

<MavenLog :log="`
[INFO] --- test-order-maven-plugin: affected (default-cli) ---
[INFO] Changed classes: Cart.java, CartLineItem.java
[INFO] Skipped 3 test classes (no dependency overlap)
[INFO] Running com.example.shop.CartTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS in 1.2 s
`" />

<div class="mt-6 text-base opacity-60">Over-approximation only — false positives, never false negatives.</div>

<!--
- Don't run this live — save the time. Show it as static output and explain verbally.
- "Ordering moves relevant tests first. Affected selection goes further — skips unrelated tests entirely."
- "One command: mvn test-order:affected test. Skips 3 classes, runs 1, BUILD SUCCESS in 1.2 s."
- The two-column layout makes the safety guarantee visual, not just stated
- False positives: extra tests run when overlap is ambiguous — safe
- False negatives: a relevant test skipped — never happens
- The dep index is an over-approximation of runtime coverage. Sound by construction
- "There's a link in the repo — and I'll mention it at the end."
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
  <code class="text-sm">mvn test-order:tiered-select</code><br/>
  <code class="text-sm">mvn test-order:run-tier -Dtier=1</code>
  <div class="mt-2 opacity-70">Run highest-ranked tier first. Fail fast without touching the rest.</div>
</div>

<div class="p-4 rounded-lg" style="background: rgba(74,222,128,0.1); border: 1px solid rgba(74,222,128,0.3)">
  <div class="text-green-400 font-bold mb-2">Parallel remainder</div>
  <code class="text-sm">run-tier -Dtier=2</code><br/>
  <code class="text-sm">run-tier -Dtier=3</code>
  <div class="mt-2 opacity-70">Lower tiers in parallel agents. Zero extra config in your test code.</div>
</div>

</div>

<div class="mt-5 text-base opacity-60">One learn pass. Every push gets ranked order. Parallel tiers when you need them.</div>

<!--
- tiered-select partitions the ranked list into tiers by score bucket
- run-tier 1 = highest-priority tests, run-tier 2/3 = remainder in parallel CI agents
- The index is read-only on push runs — no instrumentation overhead
- CI caches .test-order/ between runs (restores on each push run)
- "You learn once nightly. Every developer push gets the benefit of last night's index."
- TRANSITION: "so what does this buy you? Numbers"
-->




---
layout: center
class: slide-base
---

<div class="big-statement">Does it actually work?</div>

<!--
- Pause. Numbers next.
-->


---
layout: center
class: slide-base text-center
---

<div class="text-7xl font-black text-amber-400">50% → 87–91%</div>

<div class="pt-4 text-xl opacity-70">APFD · alphabetical baseline 50% · test-order: 87–91%</div>

<!--
- 50% = alphabetical baseline (Yoo & Harman 2012 — it's a coin flip)
- 87–91% = test-order across commons-lang, jackson-core, okhttp, spring-ai, guava, netty…
- Average rank 1.4: when we miss #1, the failing test is #2
- Pause 3 seconds
-->


---
layout: default
class: slide-base
---

# 87–91% APFD across 7 real OSS repos

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
class: slide-base
---

<img src="/images/wiki-kuka-robot.jpg" class="absolute inset-0 w-full h-full object-cover opacity-28" />
<div class="absolute inset-0 bg-zinc-900/75 z-0" />

<div class="relative z-10 flex flex-col items-center">

<div class="big-statement">

Your suite runs<br/>on every push.<br/>Sometimes dozens a day.

</div>

<div class="text-2xl text-orange-400 mt-8 text-center">
  First failure earlier. Every push.
</div>

<div class="hands-up mt-6">✋ running tests in CI on every commit?</div>

</div>

<!--
- SHOW OF HANDS #3
- KUKA robot — repetitive, relentless CI
- CI runs on every push, PR check, fix → push → wait → fix → push
- Every run pays the full suite cost today
- "80% earlier signal × many runs per day = faster feedback loop"
- "Zero config change — test-order intercepts Surefire transparently"
- TRANSITION: "it's not magic — here's where it breaks"
-->


<!-- ═══ HONEST TRADEOFFS ═══════════════════════════════════════════════════════ -->

---
layout: default
class: slide-base
---

<img src="/images/wiki-lego-bricks.jpg" class="absolute inset-0 w-full h-full object-cover opacity-10" />
<div class="absolute inset-0 bg-zinc-900/85 z-0" />

<div class="relative z-10">

# When NOT to use this

<div class="mt-8 space-y-4">

<div class="flex items-center gap-4 text-xl">
  <span class="tag-bad">&lt; 20</span>
  <span>Tiny suites — overhead isn't worth it</span>
</div>

<div class="flex items-center gap-4 text-xl">
  <span class="tag-bad">OPAQUE</span>
  <span>Reflection-only paths — invisible to the transformer</span>
</div>

<div class="flex items-center gap-4 text-xl">
  <span class="tag-bad">DYNAMIC</span>
  <span>Custom classloaders after JVM start (OSGi, Quarkus dev)</span>
</div>

<div class="flex items-center gap-4 text-xl">
  <span class="tag-bad">FLAKY</span>
  <span>Highly flaky suites — quarantine first</span>
</div>

</div>

</div>

<!--
- Mirror the tag style from "what people already try" — consistent visual language
- Tiny suites (<20): instrumentation overhead isn't worth it; just parallelize
- Reflection-only: Class.forName, runtime-wired proxies invisible to the transformer
- Dynamic classloaders: OSGi, Quarkus dev mode, plugin systems loading after JVM start
- Flaky: corrupt the EMA failure-history signal; quarantine first with @QuarantinedTest (ABORTED not FAILED — build stays green)
- Note: Spring AOP and Mockito work fine — they go through bytecode
- "This earns trust. Nothing works everywhere"
- TRANSITION: "v0.1 means we've found things that break. That's the deal."
-->


<!-- ═══ CTA ════════════════════════════════════════════════════════════════════ -->

---
layout: center
class: slide-base
---

<img src="/images/wiki-loc-catalog.jpg" class="absolute inset-0 w-full h-full object-cover opacity-20" />
<div class="absolute inset-0 bg-zinc-900/72 z-0" />

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
layout: none
class: slide-base
---

<img src="/images/wiki-loc-catalog.jpg" class="absolute inset-0 w-full h-full object-cover opacity-15" />
<div class="absolute inset-0 bg-zinc-900/80" />

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
