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
mdc: true
fonts:
  sans: Inter
  serif: Playfair Display
  mono: JetBrains Mono
---

<!--
PRESENTER CHECKLIST:
- Terminal font: 20pt+ (test on projector!)
- Have sample project ready with pre-built index
- No Wi-Fi needed (all local)
- Timing: Title 15s → Pain 90s → Reorder Anim 20s → Magic 75s → Results 10s → Agentic 15s → AgenticDemo 75s → HowToUse+Close 30s = ~6min
-->

<div class="h-full w-full flex flex-col items-center justify-center relative overflow-hidden">

  <!-- Animated gradient orbs -->
  <div class="absolute -top-20 -left-20 w-96 h-96 bg-blue-500/10 rounded-full blur-3xl animate-pulse"></div>
  <div class="absolute -bottom-32 -right-20 w-120 h-120 bg-purple-500/10 rounded-full blur-3xl animate-pulse" style="animation-delay: 1s"></div>
  <div class="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-160 h-160 bg-emerald-500/5 rounded-full blur-3xl"></div>

  <div class="relative z-10 text-center">
    <h1 class="!text-6xl !font-bold !leading-tight bg-gradient-to-r from-blue-400 via-purple-400 to-emerald-400 bg-clip-text text-transparent">
      You Are Running<br>the Wrong Tests First
    </h1>

    <div v-click class="mt-10">
      <p class="text-2xl text-gray-400 leading-relaxed tracking-wide">
        Predictive Test Ordering for<br>Faster Feedback & Agentic Development
      </p>
    </div>
  </div>

  <div class="absolute bottom-8 left-8 z-10">
    <div class="text-lg font-medium text-gray-400">Johannes Bechberger</div>
    <div class="text-sm text-gray-600 mt-1">SAP DCOM 2026 · Demo Jam</div>
  </div>

  <div class="absolute bottom-8 right-8 z-10 opacity-30">
    <svg width="80" height="80" viewBox="0 0 24 24" fill="none" stroke="url(#grad1)" stroke-width="1.5">
      <defs>
        <linearGradient id="grad1" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" style="stop-color:#60a5fa"/>
          <stop offset="100%" style="stop-color:#34d399"/>
        </linearGradient>
      </defs>
      <path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5"/>
    </svg>
  </div>

</div>

<style>
.slidev-layout { background: #0f0f1a !important; }
</style>

<!--
"The most expensive thing in software delivery is waiting for feedback."

→ Immediately switch to terminal for the pain demo.
-->

---
transition: fade
---

<div class="h-full w-full flex flex-col items-center justify-center relative overflow-hidden">

  <div class="absolute inset-0 bg-gradient-to-br from-blue-950/40 via-transparent to-purple-950/30"></div>

  <div class="relative z-10 text-center max-w-4xl">
    <h2 class="!text-5xl !font-bold !leading-snug text-white mb-12">
      What if we only ran the tests <br>
      <span class="bg-gradient-to-r from-emerald-400 to-cyan-400 bg-clip-text text-transparent">that matter?</span>
    </h2>
  </div>

  <div class="relative z-10 w-full max-w-3xl">

```mermaid {theme: 'dark', scale: 1.3}
flowchart LR
    A["<b>git diff</b>"] --> B["<b>Dependency Graph</b><br/><small>learned once</small>"]
    B --> C["<b>Run Top N</b><br/><small>affected tests only</small>"]

    style A fill:#1e3a5f,stroke:#3b82f6,color:#93c5fd
    style B fill:#3b1f5e,stroke:#8b5cf6,color:#c4b5fd
    style C fill:#1a3d2e,stroke:#10b981,color:#6ee7b7
```

  </div>

  <div v-click class="relative z-10 mt-14">
    <div class="inline-flex items-center gap-3 bg-white/5 backdrop-blur-sm border border-white/10 rounded-full px-8 py-3">
      <div class="w-2 h-2 bg-emerald-400 rounded-full animate-pulse"></div>
      <span class="text-xl text-gray-300">One Maven plugin. Zero config. Let me show you.</span>
    </div>
  </div>

</div>

<style>
.slidev-layout { background: #0f0f1a !important; }
</style>

<!--
"What if we could know which tests are affected by a change?"
"Learn a dependency graph once, then select on every commit."

→ Immediately to terminal for the pain demo (3 min full run).
-->

---
transition: fade
clicks: 4
---

<div class="h-full w-full flex flex-col relative overflow-hidden px-12 py-8">

  <div class="absolute inset-0 bg-gradient-to-br from-indigo-950/30 via-transparent to-emerald-950/20"></div>

  <div class="relative z-10 text-center mb-6">
    <h2 class="!text-3xl !font-bold text-white">
      How <span class="bg-gradient-to-r from-emerald-400 to-cyan-400 bg-clip-text text-transparent">Test Selection</span> Works
    </h2>
  </div>

  <div class="relative z-10 flex gap-8 flex-1">

    <!-- Left: Changed code -->
    <div class="flex-1 flex flex-col">
      <div v-click="1" class="mb-3">
        <span class="text-sm uppercase tracking-wider text-amber-400 font-semibold">Changed file</span>
        <div class="mt-2 bg-black/40 border border-amber-500/30 rounded-lg p-4 font-mono text-sm">
          <div class="text-gray-500">// DestinationService.java</div>
          <div class="text-red-400/70 line-through">- return cache.get(name);</div>
          <div class="text-emerald-400">+ return cache.getOrFetch(name, this::resolve);</div>
        </div>
      </div>

      <div v-click="2" class="mt-4">
        <span class="text-sm uppercase tracking-wider text-blue-400 font-semibold">Dependency graph knows:</span>
        <div class="mt-2 text-gray-400 text-sm space-y-1">
          <div class="flex items-center gap-2">
            <div class="w-2 h-2 bg-emerald-400 rounded-full"></div>
            <span>3 tests exercise this code path</span>
          </div>
          <div class="flex items-center gap-2">
            <div class="w-2 h-2 bg-gray-600 rounded-full"></div>
            <span>5 tests don't touch it at all</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Right: Test list with reordering -->
    <div class="flex-1 flex flex-col">
      <span class="text-sm uppercase tracking-wider text-gray-400 font-semibold mb-3">Test execution order</span>

      <div class="space-y-2 relative">
        <!-- Before: alphabetical order -->
        <div v-click="[0,4]" class="space-y-2">
          <div class="flex items-center gap-3 bg-white/5 border border-white/10 rounded-lg px-4 py-2.5 font-mono text-sm">
            <span class="text-gray-500 w-5">1.</span>
            <span class="text-gray-300">AuthTokenProviderTest</span>
          </div>
          <div class="flex items-center gap-3 bg-white/5 border border-white/10 rounded-lg px-4 py-2.5 font-mono text-sm">
            <span class="text-gray-500 w-5">2.</span>
            <span class="text-gray-300">CacheConfigTest</span>
          </div>
          <div :class="$clicks >= 2 ? 'border-emerald-500/40 bg-emerald-950/20' : 'border-white/10 bg-white/5'" class="flex items-center gap-3 border rounded-lg px-4 py-2.5 font-mono text-sm transition-all duration-500">
            <span class="text-gray-500 w-5">3.</span>
            <span :class="$clicks >= 2 ? 'text-emerald-300' : 'text-gray-300'" class="transition-colors duration-500">DestinationResolverTest</span>
            <span v-if="$clicks >= 2" class="ml-auto text-xs text-emerald-400 bg-emerald-400/10 px-2 py-0.5 rounded">calls changed code</span>
          </div>
          <div :class="$clicks >= 2 ? 'border-emerald-500/40 bg-emerald-950/20' : 'border-white/10 bg-white/5'" class="flex items-center gap-3 border rounded-lg px-4 py-2.5 font-mono text-sm transition-all duration-500">
            <span class="text-gray-500 w-5">4.</span>
            <span :class="$clicks >= 2 ? 'text-emerald-300' : 'text-gray-300'" class="transition-colors duration-500">DestinationServiceTest</span>
            <span v-if="$clicks >= 2" class="ml-auto text-xs text-emerald-400 bg-emerald-400/10 px-2 py-0.5 rounded">calls changed code</span>
          </div>
          <div class="flex items-center gap-3 bg-white/5 border border-white/10 rounded-lg px-4 py-2.5 font-mono text-sm">
            <span class="text-gray-500 w-5">5.</span>
            <span class="text-gray-300">HttpClientFactoryTest</span>
          </div>
          <div :class="$clicks >= 2 ? 'border-emerald-500/40 bg-emerald-950/20' : 'border-white/10 bg-white/5'" class="flex items-center gap-3 border rounded-lg px-4 py-2.5 font-mono text-sm transition-all duration-500">
            <span class="text-gray-500 w-5">6.</span>
            <span :class="$clicks >= 2 ? 'text-emerald-300' : 'text-gray-300'" class="transition-colors duration-500">OnPremiseProxyTest</span>
            <span v-if="$clicks >= 2" class="ml-auto text-xs text-emerald-400 bg-emerald-400/10 px-2 py-0.5 rounded">calls changed code</span>
          </div>
          <div class="flex items-center gap-3 bg-white/5 border border-white/10 rounded-lg px-4 py-2.5 font-mono text-sm">
            <span class="text-gray-500 w-5">7.</span>
            <span class="text-gray-300">RetryHandlerTest</span>
          </div>
          <div class="flex items-center gap-3 bg-white/5 border border-white/10 rounded-lg px-4 py-2.5 font-mono text-sm">
            <span class="text-gray-500 w-5">8.</span>
            <span class="text-gray-300">TenantIsolationTest</span>
          </div>
        </div>

        <!-- After: reordered (affected first) -->
        <div v-click="4" class="space-y-2 absolute inset-0">
          <div class="flex items-center gap-3 bg-emerald-950/30 border border-emerald-500/40 rounded-lg px-4 py-2.5 font-mono text-sm">
            <span class="text-emerald-400 w-5 font-bold">1.</span>
            <span class="text-emerald-300 font-medium">DestinationResolverTest</span>
            <span class="ml-auto text-xs font-bold text-emerald-400">★ affected</span>
          </div>
          <div class="flex items-center gap-3 bg-emerald-950/30 border border-emerald-500/40 rounded-lg px-4 py-2.5 font-mono text-sm">
            <span class="text-emerald-400 w-5 font-bold">2.</span>
            <span class="text-emerald-300 font-medium">DestinationServiceTest</span>
            <span class="ml-auto text-xs font-bold text-emerald-400">★ affected</span>
          </div>
          <div class="flex items-center gap-3 bg-emerald-950/30 border border-emerald-500/40 rounded-lg px-4 py-2.5 font-mono text-sm">
            <span class="text-emerald-400 w-5 font-bold">3.</span>
            <span class="text-emerald-300 font-medium">OnPremiseProxyTest</span>
            <span class="ml-auto text-xs font-bold text-emerald-400">★ affected</span>
          </div>
          <div class="flex items-center gap-3 bg-white/3 border border-white/5 rounded-lg px-4 py-2.5 font-mono text-sm opacity-40">
            <span class="text-gray-600 w-5">4.</span>
            <span class="text-gray-500">AuthTokenProviderTest</span>
            <span class="ml-auto text-xs text-gray-600">skipped</span>
          </div>
          <div class="flex items-center gap-3 bg-white/3 border border-white/5 rounded-lg px-4 py-2.5 font-mono text-sm opacity-40">
            <span class="text-gray-600 w-5">5.</span>
            <span class="text-gray-500">CacheConfigTest</span>
            <span class="ml-auto text-xs text-gray-600">skipped</span>
          </div>
          <div class="flex items-center gap-3 bg-white/3 border border-white/5 rounded-lg px-4 py-2.5 font-mono text-sm opacity-40">
            <span class="text-gray-600 w-5">6.</span>
            <span class="text-gray-500">HttpClientFactoryTest</span>
            <span class="ml-auto text-xs text-gray-600">skipped</span>
          </div>
          <div class="flex items-center gap-3 bg-white/3 border border-white/5 rounded-lg px-4 py-2.5 font-mono text-sm opacity-40">
            <span class="text-gray-600 w-5">7.</span>
            <span class="text-gray-500">RetryHandlerTest</span>
            <span class="ml-auto text-xs text-gray-600">skipped</span>
          </div>
          <div class="flex items-center gap-3 bg-white/3 border border-white/5 rounded-lg px-4 py-2.5 font-mono text-sm opacity-40">
            <span class="text-gray-600 w-5">8.</span>
            <span class="text-gray-500">TenantIsolationTest</span>
            <span class="ml-auto text-xs text-gray-600">skipped</span>
          </div>
        </div>
      </div>
    </div>

  </div>

  <!-- Bottom label -->
  <div v-click="3" class="relative z-10 text-center mt-4">
    <div class="inline-flex items-center gap-3 bg-emerald-500/10 border border-emerald-500/20 rounded-full px-6 py-2">
      <span class="text-lg text-emerald-300 font-medium">3 tests instead of 8 → 10× faster feedback</span>
    </div>
  </div>

</div>

<style>
.slidev-layout { background: #0f0f1a !important; }
</style>

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
---

<div class="h-full w-full flex flex-col items-center justify-center relative overflow-hidden">

  <!-- Dramatic glow behind the numbers -->
  <div class="absolute top-1/3 left-1/4 w-64 h-64 bg-red-500/15 rounded-full blur-3xl"></div>
  <div class="absolute top-1/3 right-1/4 w-64 h-64 bg-emerald-500/15 rounded-full blur-3xl"></div>

  <div class="relative z-10 flex items-end justify-center gap-20 mb-12">
    <div v-motion :initial="{ scale: 0.5, opacity: 0 }" :enter="{ scale: 1, opacity: 1, transition: { delay: 200, duration: 600 } }" class="text-center">
      <div class="text-8xl font-black text-red-400 tabular-nums tracking-tight" style="text-shadow: 0 0 40px rgba(248,113,113,0.3)">3:03</div>
      <div class="mt-4 text-lg text-red-300/60 uppercase tracking-widest font-medium">before</div>
    </div>

    <div v-motion :initial="{ x: -20, opacity: 0 }" :enter="{ x: 0, opacity: 1, transition: { delay: 600, duration: 400 } }" class="mb-8">
      <svg class="w-12 h-12 text-gray-600" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M5 12h14M12 5l7 7-7 7"/>
      </svg>
    </div>

    <div v-motion :initial="{ scale: 0.5, opacity: 0 }" :enter="{ scale: 1, opacity: 1, transition: { delay: 800, duration: 600 } }" class="text-center">
      <div class="text-8xl font-black text-emerald-400 tabular-nums tracking-tight" style="text-shadow: 0 0 40px rgba(52,211,153,0.3)">0:17</div>
      <div class="mt-4 text-lg text-emerald-300/60 uppercase tracking-widest font-medium">after</div>
    </div>
  </div>

  <div v-click class="relative z-10 mt-8">
    <p class="text-3xl text-gray-300 text-center leading-relaxed">
      Same change. Same confidence.
      <span class="font-bold bg-gradient-to-r from-yellow-300 to-amber-400 bg-clip-text text-transparent">10× faster.</span>
    </p>
  </div>

  <div v-click class="relative z-10 mt-12">
    <p class="text-xl text-gray-500 text-center italic">
      Now imagine an AI agent doing this ten times in a row...
    </p>
  </div>

</div>

<style>
.slidev-layout { background: #0f0f1a !important; }
</style>

<!--
"You just saw it. Same change, same results, ten times faster."
"But this is just one developer. What about an AI agent iterating in a loop?"

→ Next slide sets up the agentic demo.
-->

---
transition: slide-left
clicks: 3
---

<div class="h-full w-full flex flex-col items-center justify-center relative overflow-hidden">

  <div class="absolute inset-0 bg-gradient-to-br from-indigo-950/30 via-transparent to-emerald-950/20"></div>

  <div class="relative z-10 text-center mb-12">
    <h2 class="!text-5xl !font-bold text-white">
      The <span class="bg-gradient-to-r from-blue-400 to-violet-400 bg-clip-text text-transparent">Agentic</span> Multiplier
    </h2>
  </div>

  <div class="relative z-10 w-full max-w-3xl">

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

  </div>

  <div class="relative z-10 mt-14 flex justify-center gap-8">
    <div v-click="1" class="bg-white/5 backdrop-blur-sm border border-white/10 rounded-xl px-6 py-4 text-center">
      <div class="text-3xl font-bold text-red-400">3 min</div>
      <div class="text-sm text-gray-500 mt-1">kills the loop</div>
    </div>
    <div v-click="2" class="flex items-center">
      <svg class="w-8 h-8 text-gray-600" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M5 12h14M12 5l7 7-7 7"/>
      </svg>
    </div>
    <div v-click="3" class="bg-white/5 backdrop-blur-sm border border-emerald-500/20 rounded-xl px-6 py-4 text-center">
      <div class="text-3xl font-bold text-emerald-400">17 sec</div>
      <div class="text-sm text-gray-500 mt-1">keeps it flowing</div>
    </div>
  </div>

</div>

<style>
.slidev-layout { background: #0f0f1a !important; }
</style>

<!--
"An agent iterates: generate, test, fix, retry."
"3 minutes per loop kills the workflow. 17 seconds keeps it flowing."
"Let me show you."

→ Switch to VS Code for live agentic demo.
-->

---
transition: fade
---

<div class="h-full w-full flex flex-col items-center justify-center relative overflow-hidden">

  <!-- Ambient glow -->
  <div class="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-140 h-140 bg-blue-500/5 rounded-full blur-3xl"></div>

  <div class="relative z-10 text-center max-w-3xl">
    <p class="text-4xl leading-relaxed text-gray-300">
      Maybe your test suite isn't too large.
    </p>
    <p v-click class="text-5xl font-bold mt-8 leading-relaxed bg-gradient-to-r from-blue-400 via-purple-400 to-emerald-400 bg-clip-text text-transparent">
      Maybe you're just running<br>the wrong tests first.
    </p>
  </div>

  <div v-click class="relative z-10 mt-16">
    <div class="inline-block bg-white/5 backdrop-blur-sm border border-emerald-500/20 rounded-xl px-8 py-4">
      <code class="text-xl text-emerald-400 font-mono">me.bechberger:test-order-maven-plugin</code>
    </div>
  </div>

  <div class="absolute bottom-10 left-1/2 -translate-x-1/2 z-10">
    <div class="w-16 h-1 bg-gradient-to-r from-blue-500 to-emerald-500 rounded-full opacity-40"></div>
  </div>

</div>

<style>
.slidev-layout { background: #0f0f1a !important; }
</style>

<!--
Let this land. Pause. Then advance to the how-to-use slide.
-->

---
transition: fade
clicks: 3
---

<div class="h-full w-full flex flex-col relative overflow-hidden px-12 py-8">

  <div class="absolute inset-0 bg-gradient-to-br from-blue-950/20 via-transparent to-purple-950/20"></div>

  <div class="relative z-10 text-center mb-8">
    <h2 class="!text-4xl !font-bold text-white">Get Started in 30 Seconds</h2>
  </div>

  <div class="relative z-10 flex gap-8 flex-1 items-start">

    <!-- Left: Steps -->
    <div class="flex-1 space-y-5">

      <div v-click="1" class="flex gap-4 items-start">
        <div class="flex-shrink-0 w-8 h-8 rounded-full bg-blue-500/20 border border-blue-500/40 flex items-center justify-center text-blue-400 font-bold text-sm">1</div>
        <div class="flex-1">
          <div class="text-gray-200 font-semibold mb-2">Add to your pom.xml</div>
          <div class="bg-black/40 border border-white/10 rounded-lg p-3 font-mono text-xs leading-relaxed">
            <span class="text-gray-500">&lt;plugin&gt;</span><br>
            <span class="text-gray-500">&nbsp;&nbsp;&lt;groupId&gt;</span><span class="text-blue-300">me.bechberger</span><span class="text-gray-500">&lt;/groupId&gt;</span><br>
            <span class="text-gray-500">&nbsp;&nbsp;&lt;artifactId&gt;</span><span class="text-emerald-300">test-order-maven-plugin</span><span class="text-gray-500">&lt;/artifactId&gt;</span><br>
            <span class="text-gray-500">&lt;/plugin&gt;</span>
          </div>
        </div>
      </div>

      <div v-click="2" class="flex gap-4 items-start">
        <div class="flex-shrink-0 w-8 h-8 rounded-full bg-purple-500/20 border border-purple-500/40 flex items-center justify-center text-purple-400 font-bold text-sm">2</div>
        <div class="flex-1">
          <div class="text-gray-200 font-semibold mb-2">First run learns dependencies</div>
          <div class="bg-black/40 border border-white/10 rounded-lg p-3 font-mono text-sm">
            <span class="text-gray-500">$</span> <span class="text-yellow-300">mvn test</span>
          </div>
          <div class="text-xs text-gray-500 mt-1">Instruments tests & builds dependency index</div>
        </div>
      </div>

      <div v-click="3" class="flex gap-4 items-start">
        <div class="flex-shrink-0 w-8 h-8 rounded-full bg-emerald-500/20 border border-emerald-500/40 flex items-center justify-center text-emerald-400 font-bold text-sm">3</div>
        <div class="flex-1">
          <div class="text-gray-200 font-semibold mb-2">Every run after: fast feedback</div>
          <div class="bg-black/40 border border-white/10 rounded-lg p-3 font-mono text-sm">
            <span class="text-gray-500">$</span> <span class="text-yellow-300">mvn test-order:select test</span>
          </div>
          <div class="text-xs text-gray-500 mt-1">Runs only tests affected by your changes</div>
        </div>
      </div>

    </div>

    <!-- Right: QR code + links -->
    <div class="flex flex-col items-center justify-center gap-6 w-64">

      <div class="bg-white rounded-2xl p-4 shadow-2xl shadow-blue-500/10">
        <img src="/qr-github.svg" class="w-44 h-44" alt="QR code to GitHub repo" />
      </div>

      <div class="text-center space-y-2">
        <div class="text-blue-400 font-mono text-sm">github.com/parttimenerd/test-order</div>
        <div class="flex gap-3 justify-center mt-3">
          <div class="bg-white/5 border border-white/10 rounded-lg px-3 py-1.5">
            <div class="text-[10px] text-gray-500 uppercase">Maven</div>
            <code class="text-emerald-400 text-xs">me.bechberger:test-order-maven-plugin</code>
          </div>
        </div>
        <div class="flex gap-3 justify-center">
          <div class="bg-white/5 border border-white/10 rounded-lg px-3 py-1.5">
            <div class="text-[10px] text-gray-500 uppercase">Gradle</div>
            <code class="text-emerald-400 text-xs">me.bechberger.test-order</code>
          </div>
        </div>
      </div>

    </div>

  </div>

  <div class="absolute bottom-6 left-1/2 -translate-x-1/2 z-10 text-sm text-gray-600">
    Johannes Bechberger · SAP DCOM 2026
  </div>

</div>

<style>
.slidev-layout { background: #0f0f1a !important; }
</style>

<!--
Leave this up while people take photos / scan the QR code.
"Add the plugin, run tests once to learn, then enjoy 10× faster feedback."
"Scan the QR code or find us on GitHub. Works with Maven and Gradle."
-->
