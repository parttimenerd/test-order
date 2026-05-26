<template>
  <div class="h-full w-full flex flex-col items-center justify-center relative overflow-hidden" style="background: #0f0f1a">
    <div class="absolute inset-0 bg-gradient-to-br from-blue-950/40 via-transparent to-purple-950/30"></div>

    <div class="relative z-10 w-full max-w-6xl px-10 flex flex-col gap-7">

      <!-- Local workflow -->
      <div>
        <div class="text-gray-400 text-xs font-semibold uppercase tracking-widest mb-4">Local &amp; CI workflow</div>
        <div class="flex items-center">

          <div class="flex flex-col items-center gap-2 flex-1">
            <div class="w-full bg-purple-950/60 border border-purple-500/40 rounded-xl px-4 py-4 text-center">
              <div class="text-purple-300 font-bold text-base">Learn pass</div>
              <div class="text-purple-400/70 text-sm mt-1">bytecode instr.</div>
            </div>
            <div class="text-purple-400/60 text-xs">once · 12% overhead</div>
          </div>

          <div class="flex flex-col items-center flex-shrink-0 px-2 pb-5">
            <div class="text-gray-500 text-xs mb-0.5">builds</div>
            <div class="text-gray-400 text-xl">→</div>
          </div>

          <div class="flex flex-col items-center gap-2 flex-1">
            <div class="w-full bg-blue-950/60 border border-blue-500/40 rounded-xl px-4 py-4 text-center">
              <div class="text-blue-300 font-bold text-base">Dep graph</div>
              <div class="text-blue-400/70 text-sm mt-1">test → prod classes</div>
            </div>
            <div class="text-blue-400/60 text-xs">~500 KB · local</div>
          </div>

          <div class="flex flex-col items-center flex-shrink-0 px-2 pb-5">
            <div class="text-gray-500 text-xs mb-0.5">intersect with</div>
            <div class="text-gray-400 text-xl">→</div>
          </div>

          <div class="flex flex-col items-center gap-2 flex-1">
            <div class="w-full bg-slate-800/60 border border-slate-500/40 rounded-xl px-4 py-4 text-center">
              <div class="text-gray-200 font-bold text-base">Changed files</div>
              <div class="text-gray-400/70 text-sm mt-1">every commit</div>
            </div>
            <div class="text-gray-500 text-xs">uncommitted or staged</div>
          </div>

          <div class="flex flex-col items-center flex-shrink-0 px-2 pb-5">
            <div class="text-gray-500 text-xs mb-0.5">score &amp; pick top N</div>
            <div class="text-gray-400 text-xl">→</div>
          </div>

          <div class="flex flex-col items-center gap-2 flex-1">
            <div class="w-full bg-emerald-950/60 border border-emerald-500/40 rounded-xl px-4 py-4 text-center">
              <div class="text-emerald-300 font-bold text-base">Run affected tests</div>
              <div class="text-emerald-400/70 text-sm mt-1">overlap · fail · speed</div>
            </div>
            <div class="text-emerald-400/60 text-xs">~25s · not 5 min</div>
          </div>

        </div>
      </div>

      <!-- Bottom two columns -->
      <div class="grid grid-cols-2 gap-6">

        <!-- CI tiers -->
        <div class="bg-white/5 border border-white/10 rounded-2xl px-7 py-5">
          <div class="text-gray-400 text-xs font-semibold uppercase tracking-widest mb-4">CI tiers</div>
          <div class="flex flex-col gap-3">
            <div class="flex items-center gap-3">
              <span class="text-emerald-400 font-mono font-bold text-lg w-7 flex-shrink-0">1</span>
              <div>
                <span class="text-gray-200 text-base">Affected tests</span>
                <span class="text-emerald-400 text-sm font-semibold ml-2">→ seconds</span>
              </div>
            </div>
            <div class="flex items-center gap-3">
              <span class="text-yellow-400 font-mono font-bold text-lg w-7 flex-shrink-0">2</span>
              <div>
                <span class="text-gray-200 text-base">Broader coverage</span>
                <span class="text-yellow-400/70 text-sm ml-2">→ minutes</span>
              </div>
            </div>
            <div class="flex items-center gap-3">
              <span class="text-gray-500 font-mono font-bold text-lg w-7 flex-shrink-0">3</span>
              <div>
                <span class="text-gray-400 text-base">Full suite</span>
                <span class="text-gray-500 text-sm ml-2">→ nightly</span>
              </div>
            </div>
            <div class="text-gray-600 text-xs mt-1">bugs surface in Tier 1, not Tier 3</div>
          </div>
        </div>

        <!-- Tech stack -->
        <div class="bg-white/5 border border-white/10 rounded-2xl px-7 py-5">
          <div class="text-gray-400 text-xs font-semibold uppercase tracking-widest mb-4">Works with</div>
          <div class="grid grid-cols-2 gap-x-4 gap-y-3">
            <div class="flex items-center gap-2">
              <span class="text-blue-400 text-sm">▸</span>
              <span class="text-gray-200 text-base">Maven</span>
            </div>
            <div class="flex items-center gap-2">
              <span class="text-blue-400 text-sm">▸</span>
              <span class="text-gray-200 text-base">Gradle</span>
            </div>
            <div class="flex items-center gap-2">
              <span class="text-blue-400 text-sm">▸</span>
              <span class="text-gray-200 text-base">JUnit</span>
            </div>
            <div class="flex items-center gap-2">
              <span class="text-blue-400 text-sm">▸</span>
              <span class="text-gray-200 text-base">TestNG · Kotest</span>
            </div>
          </div>
          <div class="mt-4 pt-3 border-t border-white/8 text-emerald-400 text-sm font-semibold">zero config · drop in the plugin</div>
        </div>

      </div>
    </div>
  </div>
</template>
