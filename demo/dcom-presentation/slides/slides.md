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

"In the last two decades a lot of how we program our applications changed:
 we went from clumsy text editors to fully fledged IDEs, from basic
 auto-completion to agentic development. But what didn't change?
 Executing tests. That's what we want to change."

[short pause — let it land]

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

→ Drop to terminal + VS Code for the entire demo (no slides between).
  ./add-test-order.sh
  mvn test-order:affected test    [magic beat — about half the tests run]
  Copilot "fix the bug" + paste error → agent loop converges fast
  mvn test-order:diagnose / export-json   [the "share with a colleague" beat —
                                           one file you can hand to support]

→ Advance to SlideClose for the wrap.
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
