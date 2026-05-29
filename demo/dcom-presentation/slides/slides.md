---
theme: default
title: "You're Running the Wrong Tests First"
info: |
  SAP d-com Mannheim 2026 — 6-minute Demo Jam

  Predictive Test Ordering for Faster CI and Agentic Development
author: Johannes Bechberger
keywords: testing,CI,test-prioritization,java,demo-jam
exportFilename: dcom-demo-jam-wrong-tests-first
drawings:
  persist: false
transition: slide-left
layout: full
fonts:
  sans: Inter
  serif: Playfair Display
  mono: JetBrains Mono
---

<SlideHero />

<!--
[VS Code open on cloud-sdk-java/, terminal visible]

"This is the SAP Cloud SDK for Java — nine years old, 65 modules, a typical large
 Java project at SAP. Let me show you something."

→ Run: mvn clean test
  [while it compiles]
  "Every developer on this project runs this dozens of times a day. Right now it's
   compiling all 65 modules before a single test executes."
  [kill at ~90s]
  "I'm not going to make you sit through the whole thing. But you get the idea.
   Slow tests kill developer velocity — every minute waiting for feedback is a
   minute not spent writing code."

→ Advance to SlideBeforePlugin.
-->

---
transition: fade
layout: full
---

<div class="h-full w-full flex flex-col items-center justify-center relative overflow-hidden" style="background: #0f0f1a">
<div class="absolute inset-0 bg-gradient-to-br"></div>

<div class="relative z-10 w-full max-w-5xl px-12 flex flex-col gap-8">

<div class="text-gray-400 text-sm font-semibold uppercase tracking-widest text-center">normally: run all tests</div>

<div class="grid grid-cols-2 gap-x-6 gap-y-4 items-center">

<div class="bg-purple-950/40 border border-purple-500/40 rounded-2xl px-2 py-1 text-x">

```java
@Test void testA() {
  // ...
  assertEquals(x,
    methodA());
  // ...
}
```

</div>

<div class="bg-blue-950/40 border border-blue-500/40 rounded-2xl px-2 py-1">

```java
int methodA() {
  // ...
}
```

</div>

<div class="bg-purple-950/40 border border-purple-500/25 rounded-2xl px-2 py-1 opacity-70">

```java
@Test void testB() {
  // ...
  assertEquals(y,
    methodB());
  // ...
}
```

</div>

<div class="bg-blue-950/40 border border-blue-500/25 rounded-2xl px-2 py-1 opacity-70">

```java
int methodB() {
  // ...
}
```

</div>

</div>



</div>
</div>

<!--
[slide on screen: testA → methodA, testB → methodB]

"So normally we run all tests."

"It's simple, it works, it's fine."

"But what if we could improve it?
 What if, when we change only methodA, we could only run testA?"

"In an initial learn pass, the plugin watches which production methods each test
 actually calls — not just which classes it touches, but which specific methods
 end up on the call stack:"

→ Advance to SlideDepGraph.
-->

---
transition: fade
layout: full
---

<SlideDepGraph />

<!--
[slide on screen: dep-graph — testA → methodA, testB → methodB]

"Then later, when we change a method, the plugin re-runs only the tests whose
 recorded trace actually executed that method. A test that touches the same
 class but never calls the changed method gets skipped — that's the precision
 that makes the selection small. Yes we would still run those skipped tests
 eventually, just to be sure, but we don't always have to run them."

"This is where the experimental, and open-source test-order plugin for Maven and
 Gradle comes in. Now let's see how this works for the cloud-sdk, so we add the
 plugin to the maven pom. Of course we would need to run a learn run, but we
 prepared it beforehand — the CI already built it and we just download it."

→ Switch to terminal in demo dir.
  ./add-test-order.sh
  [diff scrolls — audience sees one plugin block added; learn data copied in]

→ Switch to VS Code. Run in terminal:
  mvn test-order:select test
  [only the affected test classes execute]

"We see that we only executed the few tests that have been affected by our change.
 Which is great. It's even better for agentic coding, for example to fix the bug,
 as LLMs tend to add a lot of tests which slows down dev velocity significantly."

"Let's try it out:"

→ Type in VS Code Copilot: "fix the bug" and paste the error message.
  [agent runs mvn test-order:select test automatically]

"With the plugin we, by default first execute the likely affected tests, then tests
 that cover a lot of the code base and have shown to be effective bug finders and
 then the rest."

→ Advance to SlideCIWorkflow.
-->

---
transition: fade
layout: full
---

<SlideCIWorkflow />

<!--
"In CI, you can also stack the tests in three tiers. First you run the affected
 tests — that takes seconds. Then broader coverage — minutes. And the remaining
 tests run automatically once those tiers pass — same build, no waiting until
 morning. And the learn index that CI builds can automatically be picked up by
 every developer on the team — so nobody has to run the learn pass locally."

→ Advance to SlideClose.
-->

---
transition: fade
layout: full
---

<SlideClose />

<!--
[slide shows three checkmarks + QR code + github.com/parttimenerd/test-order]

"Stop running tests that never fail."
[pause — 2 seconds, let the line land]

"Run only the tests that hit your change. Keep your agent loop moving.
 Catch bugs in seconds, not overnight."

[gesture to QR]
"Scan it. Try it on your repo this week."

[done — walk off or take questions.]
-->
