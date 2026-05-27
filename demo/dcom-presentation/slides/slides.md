---
theme: default
title: "You're Running the Wrong Tests First"
info: |
  SAP DCOM 2026 — 6-minute Demo Jam

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

<SlideBeforePlugin />

<!--
[slide on screen: testA → methodA, testB → methodB]

"So normally we run all tests."

"It's simple, it works, it's fine."

"But what if we could improve it?
 What if, when we change only methodA, we could only run testA?"

"This is where my new test-order plugin comes in.
 In an initial step, it learns which code testA really executes:"

→ Advance to SlideDepGraph.
-->

---
transition: fade
layout: full
---

<SlideDepGraph />

<!--
[slide on screen: dep-graph — testA → methodA, testB → methodB]

"Then later when we change code in methodA, we know that we don't need to bother
 with testB. Yes we would still run it, just to be sure, but we don't always have
 to run it."

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
 tests — that takes seconds. Then broader coverage — minutes. And the full suite
 runs overnight. Bugs surface in the first tier, not discovered the next morning.
 And the learn index that CI builds can automatically be picked up by every
 developer on the team — so nobody has to run the learn pass locally."

→ Advance to SlideClose.
-->

---
transition: fade
layout: full
---

<SlideClose />

<!--
[slide shows QR code + github.com/parttimenerd/test-order]

"Stop wasting hours on tests that never fail."
[pause — 3 full seconds]
"Scan it."
[pause — 2 seconds]
"Your CI will thank you."

[done — walk off or take questions.]
-->
