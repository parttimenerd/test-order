---
theme: seriph
title: "Stop Wasting Hours on Tests That Never Fail"
info: |
  test-order — 60-minute conference talk.
  Local-first, zero-config test prioritization for Maven and Gradle.
class: text-center
highlighter: shiki
lineNumbers: false
drawings:
  persist: false
transition: none
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
:global(.big-statement p) {
  font-size: inherit !important;
  line-height: inherit !important;
  margin: 0 !important;
}
:global(.slidev-slide pre),
:global(.slidev-slide .shiki),
:global(.slidev-layout pre),
:global(.slidev-layout .shiki) {
  font-size: 1.3rem !important;
  line-height: 1.6 !important;
  border-radius: 10px;
  padding: 1rem 1.2rem !important;
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

/* ── light mode overrides ─────────────────────────────────── */
:global(html:not(.dark) .scoring-table th) { color: #475569; border-bottom-color: #cbd5e1; }
:global(html:not(.dark) .scoring-table td) { border-bottom-color: #e2e8f0; }
:global(html:not(.dark) .scoring-table td:nth-child(2)) { color: #d97706; }
:global(html:not(.dark) .pull-quote) { color: #1e293b; background: rgba(249,115,22,0.07); }
:global(html:not(.dark) .hands-up) { color: #854d0e; background: rgba(250,204,21,0.18); border-color: rgba(250,204,21,0.5); }
:global(html:not(.dark) .tag-bad) { background: rgba(239,68,68,0.10); color: #dc2626; }
:global(html:not(.dark) .tag-ok)  { background: rgba(148,163,184,0.12); color: #475569; }
:global(html:not(.dark) .quote-text) { color: #1e293b; }
:global(html:not(.dark) .quote-attr) { color: #475569; }
:global(.ci-config pre), :global(.ci-config .shiki) { font-size: 0.92rem !important; line-height: 1.45 !important; }
</style>


<!-- ═══ COVER ═════════════════════════════════════════════════════════════════ -->

<div class="absolute inset-0 z-0" style="background: blue" />


<LightModeToggle />

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
layout: image
image: /images/baselone26-sponsors.png
title: "Sponsors"
---

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

<div class="absolute inset-0 z-0" />

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

<div class="absolute inset-0  z-0" />

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

<div class="absolute inset-0  z-0" />
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
mention how simple test-order is, just :affected, :learn, :run-remaining, :show, nothing more









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

<div class="p-4 rounded-xl  border border-red-500/30 flex items-start gap-4">
  <div class="text-red-400 font-bold text-2xl mt-0.5">①</div>
  <div>
    <div class="text-red-300 font-bold">No index found → learn run</div>
    <div class="text-slate-400 text-base mt-1">Bytecode agent on · records every method call · writes <code>.test-order/</code> · normal test results</div>
  </div>
</div>

<div class="p-4 rounded-xl  border border-green-500/30 flex items-start gap-4">
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
hide: t
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

<!--
═══ ACT 2: PROVE IT ═══════════════════════════════════════════════════════
-->

---
layout: default
title: "test-order:show Output"
class: slide-base
hide: tur
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

<!--
═══ ACT 2: PROVE IT ═══════════════════════════════════════════════════════
-->

---
layout: default
title: "test-order:show Output"
class: slide-base
hide: t
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

<!--
═══ ACT 2: PROVE IT ═══════════════════════════════════════════════════════
-->

---
layout: default
title: "test-order:show Output"
class: slide-base
hide: true
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

<!--
═══ ACT 2: PROVE IT ═══════════════════════════════════════════════════════
-->

---
layout: default
class: slide-base
---

## When does the first test fail?

<BenchmarkChart />

<!--
- X-axis: % of suite wall time elapsed when first failure surfaces (lower = better).
- Red bar = alphabetical order. Green bar = test-order.
- Error bars are standard error across all patches per repo.
- TRANSITION: "what does this cost?"
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
hide: true
---

## Same story across all benchmark repos.

<OverheadChart />

<!--
Chart shows wall-clock time as a multiple of the no-plugin baseline (1.0×). Three bars per repo = three instrumentation granularities:
- CLASS (blue): records which classes each test touched — cheapest, coarsest
- METHOD (purple): adds per-method entry/exit edges — finer-grained, slightly more overhead
- MEMBER* (pink, default): adds field read/write tracking on top — most precise, highest overhead

The overhead only applies to the learn run. Every subsequent run uses the saved index: no agent, no instrumentation, zero overhead.
TRANSITION: "You've seen the terminal. How do you track this over time?"
-->

<!--
═══ ACT 3: DASHBOARD ══════════════════════════════════════════════════════
-->

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
title: "D4 — Dashboard Live Demo"
class: slide-base
---

<DemoCard id="D4" title="test-order:dashboard">

```bash
cd third-party/spring-petclinic
mvn test-order:dashboard
open target/test-order-dashboard/index.html
```

</DemoCard>

<!--
DEMO D4 — spring-petclinic, index warm from D2 with multiple runs recorded.

TABS TO SHOW (live, ~5 min):
1. Tests tab: VisitControllerTests at #1, score bar, "Why" breakdown, sparkline history
2. Analytics tab: APFD trend across D2 runs, rank heatmap
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
  <div class="p-4 rounded-xl  border border-zinc-700">
    <div class="text-3xl font-bold text-green-400">APFD</div>
    <div class="text-sm opacity-70 mt-2">Average % of faults<br/>detected early</div>
  </div>
  <div class="p-4 rounded-xl  border border-zinc-700">
    <div class="text-3xl font-bold text-red-400">Failures</div>
    <div class="text-sm opacity-70 mt-2">Tests that failed<br/>in recent runs</div>
  </div>
  <div class="p-4 rounded-xl  border border-zinc-700">
    <div class="text-3xl font-bold text-yellow-400">Pass streak</div>
    <div class="text-sm opacity-70 mt-2">Consecutive green<br/>runs per test</div>
  </div>
  <div class="p-4 rounded-xl border border-zinc-700">
    <div class="text-3xl font-bold text-orange-400">At-risk</div>
    <div class="text-sm opacity-70 mt-2">Tests showing<br/>DEGRADING trend</div>
  </div>
  <div class="p-4 rounded-xl border border-zinc-700">
    <div class="text-3xl font-bold text-blue-400">Time saved</div>
    <div class="text-sm opacity-70 mt-2">Minutes not spent<br/>on wrong tests</div>
  </div>
  <div class="p-4 rounded-xl  border border-zinc-700">
    <div class="text-3xl font-bold text-purple-400">Health A–F</div>
    <div class="text-sm opacity-70 mt-2">Overall suite<br/>quality grade</div>
  </div>
</div>

---
layout: default
title: "Staleness Flow"
class: slide-base
hide: tru
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
hide: t
---

## Where test-order fits

<LandscapeMap />

<!--
Different trade-offs from Ekstazi/STARTS/Skippy/Cloud TIA.
Don't bash other tools — just show where test-order sits in the space.
-->

---
layout: default
title: "Landscape Map"
class: slide-base
hide: true
---

## Where test-order fits

<LandscapeMap />

<!--
Different trade-offs from Ekstazi/STARTS/Skippy/Cloud TIA.
Don't bash other tools — just show where test-order sits in the space.
-->

---
layout: default
title: "Staleness Flow"
class: slide-base
hide: true
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
title: "CI Config Sample"
class: slide-base ci-config
---

## GitHub Actions — drop-in config (1/2)

```yaml
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }        # needed for git diff

      - name: Restore test-order index
        uses: actions/cache@v4
        with:
          path: .test-order/
          key: test-order-${{ github.ref }}-${{ github.sha }}
          restore-keys: |
            test-order-refs/heads/main-
            test-order-

      - name: "Learn: full instrumented run"
        if: github.ref == 'refs/heads/main'
        run: mvn test -Dtestorder.mode=learn
```

<!--
- fetch-depth: 0 needed so test-order can diff against the merge base
- restore-keys: PRs fall back to the latest main cache
- learn run on main keeps the index fresh after every merge
-->

---
layout: default
title: "CI Config Sample 2"
class: slide-base ci-config
---

## GitHub Actions — drop-in config (2/2)

```yaml
      - name: Run affected tests
        if: github.ref != 'refs/heads/main'
        run: |
          mvn test-order:affected test \
            -Dtestorder.changeMode=since-last-commit

      - name: Run remaining tests (if affected passed)
        if: github.ref != 'refs/heads/main'
        run: mvn test-order:run-remaining test

      - name: Save test-order index
        if: github.ref == 'refs/heads/main'
        uses: actions/cache/save@v4
        with:
          path: .test-order/
          key: test-order-${{ github.ref }}-${{ github.sha }}
```

<!--
- affected + run-remaining only on PRs; learn + save only on main
- run-remaining: completes the full suite after affected tests pass
-->

---
layout: default
title: "CI Live Demo"
class: slide-base
---

# See it live

<div class="flex flex-col items-center justify-center h-64 gap-6">
  <a href="https://github.com/parttimenerd/sample-ci-test-order/actions" target="_blank"
     class="text-2xl font-bold text-blue-400 hover:text-blue-300 underline">
    github.com/parttimenerd/sample-ci-test-order
  </a>
  <div class="text-slate-400 text-lg">→ Actions tab</div>
  <img src="/images/sample-ci-qr.png"/>
</div>

<!--
- Open the Actions tab live
- Show a green PR run: affected step picks up only the changed tests
- Show a failing PR run: affected step fails, run-remaining is skipped entirely
-->

---
layout: default
title: "Affected-Only"
class: slide-base
hide: trru
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
title: "Affected-Only"
class: slide-base
hide: tr
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
title: "Affected-Only"
class: slide-base
hide: true
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
title: "Scale Results"
class: slide-base
---

## Large multi-module project · 52 modules

<div class="grid grid-cols-2 gap-8 mt-6">
<div class="p-6 rounded-xl border border-red-800/40 text-center">
  <div class="text-4xl font-bold text-red-400">90s</div>
  <div class="mt-2 opacity-70"><code>mvn clean test</code></div>
  <div class="text-sm mt-1 opacity-50">Compile crawl. Zero tests started.</div>
</div>
<div class="p-6 rounded-xl  border border-green-800/40 text-center">
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

<!--
═══ INTERLUDE: FEATURE CATALOGUE ══════════════════════════════════════════
-->

---
layout: default
title: "Gradle"
class: slide-base
hide: t
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
title: "Gradle"
class: slide-base
hide: true
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
title: "Annotations"
class: slide-base
---

## Fine-grained control

```java
@AlwaysRun          // always ranked #1 regardless of score
class SmokeTest { }

@TestOrder(scoreBonus = 10)
class CriticalPathTest { }

@TestOrder(changeBonus = 5) 
class IntegrationTest { }
```

<!--
SWITCH TO TERMINAL — demo @AlwaysRun live on petclinic:
  Add @AlwaysRun to VisitControllerTests, then run mvn test-order:show — watch it jump to #1.
Say: "Let me show you this one live — 30 seconds."
-->

---
layout: default
title: "D2.5 – Annotations Live"
class: slide-base
hide: t
---

<DemoCard id="D2.5" title="@AlwaysRun live — watch the ranking change">

```bash
cd third-party/spring-petclinic

# Step 1 – see current ranking
mvn test-order:show

# Step 2 – add @AlwaysRun to VisitControllerTests (one line)
# Open VisitControllerTests.java, add @AlwaysRun above the class declaration

# Step 3 – ranking preview instantly updates
mvn test-order:show   # VisitControllerTests → #1
```

</DemoCard>

<!--
RETURN TO SLIDES after this demo.
The point: annotations don't require a re-learn. The plugin reads them at show time.
Keep it tight — step 1, add annotation, step 3. No more.
-->

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
RETURN TO SLIDES.
-->

---
layout: default
title: "Agentic TDD Flow"
class: slide-base
---

## AI agents + CI = TDD enforcement

<AgenticTddFlow class="mt-6" />

<!--
AI agents write code fast. Without CI enforcement they skip the red-green cycle.
testorder.tdd=true blocks any PR where a test never failed before it passed.
The agent is forced back to write the failing test first — TDD discipline without the honour system.
-->

---
layout: default
title: "Agentic Loop — skill"
class: slide-base
---

## The test-order skill — agents run it natively

<div class="mt-6 grid grid-cols-2 gap-6">

<div class="p-5 rounded-xl" style="background: rgba(96,165,250,0.08); border: 1px solid rgba(96,165,250,0.25)">
  <div class="text-blue-400 font-bold mb-3 text-lg">Agent's inner loop</div>
  <ol class="text-sm space-y-2 opacity-85 list-decimal list-inside">
    <li>Make a code change</li>
    <li><code>mvn test-order:affected test</code></li>
    <li>Read the failure — fix root cause</li>
    <li>Repeat until green</li>
    <li>Open PR → CI runs full suite</li>
  </ol>
</div>

<div class="p-5 rounded-xl" style="background: rgba(74,222,128,0.08); border: 1px solid rgba(74,222,128,0.25)">
  <div class="text-green-400 font-bold mb-3 text-lg">Why it helps agents</div>
  <ul class="text-sm space-y-2 opacity-85">
    <li>Affected tests only → <strong>10× faster feedback</strong></li>
    <li>JSON output → easy to parse failures</li>
    <li>Skill file ships the exact commands</li>
    <li>No hallucinated flags — manifest gates every goal</li>
  </ul>
</div>

</div>

<div class="mt-4 text-sm opacity-60 text-center">
  <code>skills/test-order/SKILL.md</code> — drop it in your project, agents auto-load it
</div>

<!--
The skill file at skills/test-order/SKILL.md documents every stable goal, JSON output flags, and the Run: error-recovery pattern.
Claude Code (and any other agent) picks it up automatically via .claude/ discovery.
The agent never has to guess — it reads the manifest, picks the right command, and gets structured output back.
-->

---
layout: default
title: "D6 — Agentic Loop Live Demo"
class: slide-base
---

<DemoCard id="D6" title="Claude Code × test-order: fix a bug in < 60 s">

```bash
cd third-party/spring-petclinic

# Agent already has skills/test-order/SKILL.md loaded.
# Introduce a bug, then hand off to the agent:

claude "There's a regression in VisitController. Find it,
        fix it, and verify with test-order:affected. Use
        the test-order skill."
```

</DemoCard>

<!--
DEMO D6 — agentic loop live.

SETUP (prepare-demo.sh handles this):
  spring-petclinic index WARM from D2
  skills/test-order/SKILL.md present in project root
  Bug pre-introduced: petclinic-make-change.sh inserts a deliberate regression

STEPS:
1. Run petclinic-make-change.sh to introduce the bug (or do it live: remove a null-guard in VisitController)
2. Open a new terminal, cd to spring-petclinic
3. Run: claude "There's a regression in VisitController. Find it, fix it, and verify with test-order:affected."
4. Narrate as the agent runs:
   - "It invokes test-order:affected — only the tests that could possibly be affected by VisitController"
   - "It reads the structured output, pinpoints the failure, edits the file"
   - "Second affected run — green. Done."
5. Show the selected test count vs full suite size (9 tests vs 42)

NARRATE:
- "The skill file tells the agent exactly which commands are stable. No hallucinated flags."
- "Affected mode gives the agent a tight feedback loop — it's not waiting for 300 tests when only 9 matter."
- "On CI the full suite still runs. The agent just doesn't have to wait for it."

FALLBACK: asciinema recording at public/demo-d6.cast
TIME: aim for 45–60 seconds of agent output on screen








# Agent inner loop (watch the terminal):
#  1. mvn test-order:affected test          ← only relevant tests
#  2. reads failure, edits source
#  3. mvn test-order:affected test          ← green
#  4. opens PR summary
-->

---
layout: default
title: "D2.5 – Annotations Live"
class: slide-base
hide: true
---

<DemoCard id="D2.5" title="@AlwaysRun live — watch the ranking change">

```bash
cd third-party/spring-petclinic

# Step 1 – see current ranking
mvn test-order:show

# Step 2 – add @AlwaysRun to VisitControllerTests (one line)
# Open VisitControllerTests.java, add @AlwaysRun above the class declaration

# Step 3 – ranking preview instantly updates
mvn test-order:show   # VisitControllerTests → #1
```

</DemoCard>

<!--
RETURN TO SLIDES after this demo.
The point: annotations don't require a re-learn. The plugin reads them at show time.
Keep it tight — step 1, add annotation, step 3. No more.
-->

---
layout: default
title: "Staleness and Safety Nets"
class: slide-base
---

## The index never goes stale

<div class="grid grid-cols-1 gap-4 mt-6 max-w-2xl mx-auto">
  <div class="p-4 rounded-xl border border-zinc-700">
    <strong>Opcode hash</strong> — checked on every run. Bytecode changed → invalidate entry.<br/>
    <span class="text-sm opacity-60">e.g. you edit <code>CartService.java</code> and recompile → its entry is removed, affected tests re-run uncached</span>
  </div>
  <div class="p-4 rounded-xl  border border-zinc-700">
    <strong>Dep fingerprint</strong> — source change triggers selective re-learn for affected classes only.<br/>
    <span class="text-sm opacity-60">e.g. you add a new dependency to <code>pom.xml</code> → plugin switches to learn mode automatically</span>
  </div>
  <div class="p-4 rounded-xl border border-zinc-700">
    <strong>Unknown classes</strong> — over-approximated and deferred, never silently skipped.<br/>
    <span class="text-sm opacity-60">e.g. a new class with no dep record → all tests that could possibly touch it are included</span>
  </div>
</div>

<!--
TRANSITION: "Now — how does it actually work?"
-->

<!--
═══ ACT 5: UNDER THE HOOD ════════════════════════════════════════════════
-->

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

// After
public void remove() {
    UsageStore.recordMemberUsageIdFast(42);
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
hide: tr
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
title: "TCP Socket Batch"
class: slide-base
hide: true
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
title: "Dep Graph Diagram"
class: slide-base
hide: rt
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
hide: tru
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
title: "Selective Learn"
class: slide-base
hide: true
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
title: "Dep Graph Diagram"
class: slide-base
hide: tr
---

## The index: test → class → method edges

<DepGraphDiagram />

<!--
Each test node has a set of class IDs it touched.
At METHOD/MEMBER level, the edges go deeper — to individual method invocations.
This is what makes depOverlapPoints precise: it counts exact class-level overlaps with changed_classes.
-->

---
layout: center
title: "OD Tests Intro"
hide: t
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
layout: center
title: "OD Tests Intro"
hide: true
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
title: "Order-dependency detection"
class: slide-base
hide: t
---

## Detecting order-dependent tests

<div class="grid grid-cols-2 gap-12 mt-4 items-start">
<div class="text-center pt-4">

<div class="text-8xl font-black text-red-400/70 leading-none">N!</div>
<div class="text-xl font-bold text-red-300 mt-3">brute force</div>
<div class="text-sm opacity-50 mt-2">N=50 → 3 × 10<sup>64</sup> runs</div>

</div>
<div class="text-center">

<div class="text-8xl font-black text-green-400/80 leading-none pt-4">N</div>
<div class="text-xl font-bold text-green-300 mt-3">Tuscan square</div>
<div class="text-sm opacity-50 mt-2">every ordered pair covered</div>

<TuscanSquareViz class="mt-4" style="max-height: 180px" />

</div>
</div>

<v-click>

<div class="pull-quote mt-4">
  97.2% detection (Li et al. ISSTA 2023) · Phase 1 finds victims · Phase 2 names the polluter
</div>

</v-click>

<!--
"test-order already knows which pairs to watch — shared static state, dep overlap, run history. That cuts the search before we even start."
"A Tuscan square guarantees every ordered pair (A before B) appears exactly once. N tests → N runs."
"Phase 1: any test that flips pass→fail is a potential victim. Phase 2: surgical isolation — run T alone, then with each predecessor. Names the polluter, suggests the fix."
TRANSITION: "So — where does this leave us?"
-->

<!--
═══ CLOSING ══════════════════════════════════════════════════════════════
-->

---
layout: default
title: "Order-dependency detection"
class: slide-base
hide: true
---

## Detecting order-dependent tests

<div class="grid grid-cols-2 gap-12 mt-4 items-start">
<div class="text-center pt-4">

<div class="text-8xl font-black text-red-400/70 leading-none">N!</div>
<div class="text-xl font-bold text-red-300 mt-3">brute force</div>
<div class="text-sm opacity-50 mt-2">N=50 → 3 × 10<sup>64</sup> runs</div>

</div>
<div class="text-center">

<div class="text-8xl font-black text-green-400/80 leading-none pt-4">N</div>
<div class="text-xl font-bold text-green-300 mt-3">Tuscan square</div>
<div class="text-sm opacity-50 mt-2">every ordered pair covered</div>

<TuscanSquareViz class="mt-4" style="max-height: 180px" />

</div>
</div>

<v-click>

<div class="pull-quote mt-4">
  97.2% detection (Li et al. ISSTA 2023) · Phase 1 finds victims · Phase 2 names the polluter
</div>

</v-click>

<!--
"test-order already knows which pairs to watch — shared static state, dep overlap, run history. That cuts the search before we even start."
"A Tuscan square guarantees every ordered pair (A before B) appears exactly once. N tests → N runs."
"Phase 1: any test that flips pass→fail is a potential victim. Phase 2: surgical isolation — run T alone, then with each predecessor. Names the polluter, suggests the fix."
TRANSITION: "So — where does this leave us?"
-->

<!--
═══ CLOSING ══════════════════════════════════════════════════════════════
-->

---
layout: default
title: "Three Takeaways"
class: slide-base
hide: t
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
layout: default
title: "Three Takeaways"
class: slide-base
hide: true
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

<!--
Callback to the opening demo. "Remember position 237? That was before this line existed."
-->

---
layout: default
title: "Dep Graph Diagram"
class: slide-base
hide: true
---

## The index: test → class → method edges

<DepGraphDiagram />

<!--
Each test node has a set of class IDs it touched.
At METHOD/MEMBER level, the edges go deeper — to individual method invocations.
This is what makes depOverlapPoints precise: it counts exact class-level overlaps with changed_classes.
-->

---
layout: none
title: "Q&A"
class: slide-base
---

<div class="absolute inset-0 " />

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
  <img src="/images/feedback-qr.png" class="rounded-lg" width="180" height="180" />
  <div class="text-sm font-mono opacity-80">feedback</div>
  <div class="text-xs opacity-50">session rating</div>
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
- "CI caching?" Cache `.test-order/` between runs (tiny) or commit to git.
- "How does APFD relate to wall time?" 22-min suite at 85% APFD = first failure at ~minute 3, not minute 18.
- "Order-dependent tests?" Li et al. (ISSTA 2023): Tuscan-square designs, 97.2% detection at ~105 test orders vs. n! brute force.
- "Why not just run tests in parallel?" Parallelism reduces total time; ordering reduces time-to-first-failure. They compose.
-->
