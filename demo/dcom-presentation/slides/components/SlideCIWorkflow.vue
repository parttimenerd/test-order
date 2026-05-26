<template>
  <div class="h-full w-full flex flex-col items-center justify-center relative overflow-hidden" style="background: #0f0f1a">
    <div class="absolute inset-0 bg-gradient-to-br from-slate-950/60 via-transparent to-indigo-950/40"></div>

    <div class="relative z-10 w-full max-w-3xl px-12 flex flex-col gap-10">

      <div class="text-gray-400 text-sm font-semibold uppercase tracking-widest">CI workflow</div>

      <div class="flex flex-col gap-5">

        <div class="flex items-center gap-6 bg-emerald-950/40 border border-emerald-500/30 rounded-2xl px-8 py-6">
          <span class="text-emerald-400 font-mono font-bold text-4xl w-12 flex-shrink-0">1</span>
          <div class="flex-1">
            <div class="text-gray-100 font-semibold text-xl">Affected tests</div>
            <div class="text-emerald-400/70 text-base mt-1">dep graph × changed files</div>
          </div>
          <div class="text-emerald-300 font-bold text-2xl">seconds</div>
        </div>

        <div class="flex items-center gap-6 bg-yellow-950/30 border border-yellow-500/20 rounded-2xl px-8 py-6">
          <span class="text-yellow-400 font-mono font-bold text-4xl w-12 flex-shrink-0">2</span>
          <div class="flex-1">
            <div class="text-gray-100 font-semibold text-xl">Broader coverage</div>
            <div class="text-yellow-400/60 text-base mt-1">risk-ranked full module</div>
          </div>
          <div class="text-yellow-300/70 font-bold text-2xl">minutes</div>
        </div>

        <div class="flex items-center gap-6 bg-white/3 border border-white/8 rounded-2xl px-8 py-6">
          <span class="text-gray-500 font-mono font-bold text-4xl w-12 flex-shrink-0">3</span>
          <div class="flex-1">
            <div class="text-gray-400 font-semibold text-xl">Full suite</div>
            <div class="text-gray-600 text-base mt-1">safety net</div>
          </div>
          <div class="text-gray-500 font-bold text-2xl">nightly</div>
        </div>

      </div>

      <div class="text-gray-500 text-sm text-center">bugs surface in Tier 1 — not discovered at Tier 3 the next morning</div>

    </div>
  </div>
</template>
