---
theme: default
title: "You Are Running the Wrong Tests First"
info: |
  SAP DCOM 2026 — 6-minute Demo Jam

  Predictive Test Ordering for Faster CI and Agentic Development
author: Johannes Bechberger
keywords: testing,CI,test-prioritization,java,demo-jam
exportFilename: dcom-demo-jam
drawings:
  persist: false
transition: slide-left
layout: full
fonts:
  sans: Inter
  serif: Playfair Display
  mono: JetBrains Mono
---

<SlideTitle />

<!--
PRESENTER CHECKLIST:
- Terminal font: 20pt+ (test on projector!)
- cloud-sdk-java built and index ready (./prepare.sh)
- VS Code open on cloud-sdk-java/, .github/copilot-instructions.md visible in a tab
- Dashboard tab open at localhost:8080 (optional)
- No Wi-Fi needed (all local)

TARGET TIMING:
  0:00  Title slide (20s)
  0:20  Pain — VS Code terminal, mvn clean test (2:00, killed)
  2:20  Magic — toggle + make-change + select (40s)
  3:00  HowItWorks slide (30s)
  3:30  VS Code: copilot-instructions.md + Copilot run 1 red (~32s)
  4:02  Copilot fixes + run 2 green (~27s)
  4:29  test-order:show + mention detect-dependencies (~25s)
  4:54  Voice: kicker lines (15s)
  5:09  Close slide (10s)
  ─────────────────────────
  Total: ~5:19 (leaves ~40s buffer for pauses, slow Copilot, audience reaction)

  [OPTIONAL at 3:00 if ahead: switch to browser localhost:8080 ~15s, then HowItWorks]

"The most expensive thing in software delivery is waiting for feedback."

→ Switch to VS Code (already open on cloud-sdk-java). Terminal is visible.
  "SAP Cloud SDK for Java. The real thing. 65 modules."
-->

---
transition: fade
layout: full
---

<SlideHowItWorks />

<!--
[audience just watched toggle + make-change + select go RED in ~17s]

"Seven test classes. Seventeen seconds. Build failure. Not five minutes."

"Here's why. One learn pass — the plugin instruments your bytecode at the JVM level,
no source changes, no annotations. It records every production class each test touches
at runtime. That's the dependency graph. About 500KB. Fits in git. You build it once."

"After that it's just: git diff → intersect with the graph → score by overlap, failure
history, and speed → select the top N. Everything else deferred."

"In CI you run three tiers: Tier 1 is the affected subset — seconds. Tier 2 is broader
coverage — minutes. Tier 3 is the full suite overnight. Bugs surface in Tier 1, not Tier 3."

"Maven, Gradle, JUnit 5, JUnit 4, TestNG, Kotest. Zero config."

[OPTIONAL if ahead of pace ~15s]
→ Switch to browser at localhost:8080
  "It tracks every run — APFD, flakiness, which tests are your best signals."
  Switch back to VS Code.
[/OPTIONAL]

→ Switch to VS Code. Show .github/copilot-instructions.md tab.
  "Here's the entire AI integration. One file."
  Type the Copilot prompt. While first run starts (~17s wait), narrate:
  "Three lines. After every change: mvn test-order:select test.
   The agent gets a result in 17 seconds. Not 5 minutes."
  [run 1 finishes — red]
  "There's the bug."
  [Copilot fixes, run 2 starts — narrate lightly]
  "It read the stack trace. Fixed the negation."
  [green]
  "17 seconds to catch. 17 to verify."

→ In terminal (~1s to run, ~25s to narrate):
  mvn test-order:show -pl cloudplatform/connectivity-destination-service
  "Full ranked list. StrategyResolverTest — 12 deps on the class I changed. Failure rate 1.0."
  Scroll to Method Order section briefly.
  "Methods ranked within each class too. Highest failure signal first."
  "And there's detect-dependencies — finds tests that only fail in a specific order.
   Order-dependent tests. The ones that pass in isolation but break each other in CI."
  "We don't run that live — it reruns the full suite. But it's there."

→ Back to slides. Say kicker lines before advancing:
  "Maybe your test suite isn't too large."
  [pause — 3 full seconds]
  "Maybe you're just running the wrong tests first."
  [hold — let it land]
  → Advance to Close.
-->

---
transition: fade
layout: full
---

<SlideClose />

<!--
"Drop in the plugin. Tell me how much time you saved."

[done — walk off or take questions.]
-->
