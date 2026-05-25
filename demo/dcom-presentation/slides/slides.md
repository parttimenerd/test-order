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
- Have sample project ready with pre-built index
- No Wi-Fi needed (all local)
- Timing: Title 20s → Pain 90s → Reorder Anim 20s → Magic 75s → Results 10s → Transition 20s → Agentic 15s → AgenticDemo 75s → Close 30s = ~6min

[click] subtitle appears
[click] show the four ecosystems we support — Java, JUnit 5, Maven, Gradle
-->

---
transition: fade
layout: full
---

<SlideHowItWorks>

```mermaid {theme: 'dark', scale: 1.3}
flowchart LR
    A["<b>git diff</b>"] --> B["<b>Dependency Graph</b><br/><small>learned once</small>"]
    B --> C["<b>Run Top N</b><br/><small>affected tests only</small>"]

    style A fill:#1e3a5f,stroke:#3b82f6,color:#93c5fd
    style B fill:#3b1f5e,stroke:#8b5cf6,color:#c4b5fd
    style C fill:#1a3d2e,stroke:#10b981,color:#6ee7b7
```

</SlideHowItWorks>

<!--
"What if we could know which tests are affected by a change?"
"Learn a dependency graph once, then select on every commit."

→ Immediately to terminal for the pain demo (3 min full run).
-->

---
transition: fade
clicks: 4
layout: full
---

<SlideTestSelection />

<!--
Click through:
1. Show the changed file (code diff)
2. Show which tests are connected to that code
3. Show the "3 instead of 8" label
4. Swap to reordered list (affected first, rest skipped)

"The plugin learned which tests exercise which code.
When you change DestinationService, it knows exactly which 3 tests to run."

→ Back to terminal for the magic 17-second demo.
-->

---
transition: zoom
layout: full
---

<SlideResults />

<!--
"You just saw it. Same change, same results, ten times faster."
"But this is just one developer. What about an AI agent iterating in a loop?"

→ Next slide sets up the agentic demo.
-->

---
transition: fade
layout: full
---

<SlideTransition />

<!--
Breathe. Let the contrast land.
"3 minutes per loop is death by a thousand cuts — for you, and for an AI agent."
"test-order turns that into 17 seconds. Same confidence, 10× the throughput."

→ Next slide: the agentic loop diagram.
-->

---
transition: slide-left
clicks: 3
layout: full
---

<SlideAgentic>

```mermaid {theme: 'dark', scale: 1.2}
flowchart LR
    A["🤖 <b>AI Agent</b><br/><small>generates code</small>"] --> B["⚡ <b>test-order</b><br/><small>17 seconds</small>"]
    B --> C{Pass?}
    C -->|"green"| D["✅ <b>Ship it</b>"]
    C -->|"red"| A

    style A fill:#1e3a5f,stroke:#3b82f6,color:#93c5fd
    style B fill:#1a3d2e,stroke:#10b981,color:#6ee7b7
    style C fill:#3d2f0a,stroke:#f59e0b,color:#fcd34d
    style D fill:#1a3d2e,stroke:#10b981,color:#6ee7b7
```

</SlideAgentic>

<!--
"An agent iterates: generate, test, fix, retry."
"3 minutes per loop kills the workflow. 17 seconds keeps it flowing."
"Let me show you."

→ Switch to VS Code for live agentic demo.
-->

---
transition: fade
layout: full
---

<SlideKicker />

<!--
Let this land. Pause. Then advance to the closing slide.
-->

---
transition: fade
layout: full
---

<SlideClose />

<!--
"Star the repo, drop in the plugin, and tell me how much time you saved."
"Thank you."
-->
