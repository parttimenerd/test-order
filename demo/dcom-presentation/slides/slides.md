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
PRESENTER CHECKLIST:
- Terminal font: 20pt+ (test on projector!)
- cloud-sdk-java built and index ready (./prepare.sh)
- VS Code open on cloud-sdk-java/, .github/copilot-instructions.md visible in a tab
- Dashboard tab open at localhost:8080 (optional)
- Clock/timer visible on presenter display
- No Wi-Fi needed (all local)

TARGET TIMING:
  0:00  Hero image — silence + opening line (10s)
  0:10  Switch to VS Code — SDK intro (30s)
  0:40  Pain — mvn clean test (~90s, killed)
  2:10  Magic — toggle + make-change + select (~40s)
  2:50  Local workflow slide (~20s)
  3:10  CI workflow slide (~20s)
  3:30  VS Code — Copilot run 1 red (~32s)
  4:02  Copilot fixes + run 2 green (~27s)
  4:29  test-order:show (~20s)
  4:49  Close slide — kicker + CTA (~35s)
  ─────────────────────────
  Total: ~5:24 (leaves ~35s buffer for pauses, slow Copilot, audience reaction)

  NOTE: The browser detour (localhost:8080) does not fit the budget. Cut it.

[hold the image for ~5 seconds — let "YOU ARE RUNNING THE WRONG TESTS FIRST" land]

"Let me show you why."

→ Switch to VS Code (already open on cloud-sdk-java). Terminal is visible.
  "SAP Cloud SDK for Java. Open-source, nearly ten years old, 65 modules — it's the
   backbone for building connected SaaS apps on SAP BTP. It handles outbound communication,
   multi-tenancy, resilience — so you can focus on business logic."

  "It's a great example of the Java projects we build here at SAP: long-lived, widely
   depended on, constantly evolving. Real teams, real releases, real pressure to ship."

  "And its full test suite takes over five minutes to run."

→ Run: mvn clean test
  [while it compiles — ~20s before first output]
  "Every developer on this project runs this dozens of times a day. Right now it's
   compiling 65 modules before a single test executes."
  [tests start running]
  "Watch the clock. This is what waiting for feedback feels like."
  [around 90s mark — kill it]
  "I'm not going to make you sit through the whole thing."

→ In demo dir: toggle-test-order.sh on, make-change.sh, then back to cloud-sdk-java:
  cd /path/to/demo/dcom-presentation && ./toggle-test-order.sh on && ./make-change.sh
  cd cloud-sdk-java && mvn test-order:select test
  "Same project. Same change. Plugin on."
  [select runs — tests start in seconds]
  "Seven test classes. Not sixty-five modules."
  [result comes back RED — ~25s total]
  "Twenty-five seconds. Build failure. That's the feedback."

→ Advance to SlideTransition slide.
-->

---
transition: fade
layout: full
---

<SlideLocalWorkflow />

<!--
[advance after magic beat]
"Here's what just happened. Learn pass runs once — instruments bytecode, builds a dep
graph of every production class each test touches. ~500KB, stored locally."

"Every run: git diff, intersect the graph, score by overlap, failure history, and speed.
Select the top N. Everything else deferred."

→ Advance to CI workflow slide.
-->

---
transition: fade
layout: full
---

<SlideCIWorkflow />

<!--
"In CI you stack three tiers. Tier 1: affected subset — seconds. Tier 2: broader
coverage — minutes. Tier 3: full suite overnight."

"Bugs surface in Tier 1. Not discovered at Tier 3 the next morning."

→ Switch to VS Code. Show .github/copilot-instructions.md tab.
  "Here's the entire AI integration. One file. Three lines:
   'After every code change, run: mvn test-order:select test'"
  Type the Copilot prompt: "Implement the changes needed to fix the failing test"
  [first run starts — ~25s wait]
  "The agent gets a result in 25 seconds. Not 5 minutes."
  [run 1 finishes — red; ensure failure output is visible on screen before Copilot starts fixing]
  "There's the bug."
  [Copilot fixes, run 2 starts — narrate lightly]
  "It read the stack trace. Fixed the negation."
  [green]
  "25 seconds to catch. 25 to verify."

→ In terminal:
  mvn test-order:show -pl cloudplatform/connectivity-destination-service
  "Full ranked list. StrategyResolverTest — 12 deps on the class I changed. Failure rate 1.0."
  Scroll to Method Order section briefly.
  "Methods ranked within each class too. Highest failure signal first."
  "There's also detect-dependencies for order-dependent tests — but that's a full rerun, not today."

→ Back to slides. Advance to Close.
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
