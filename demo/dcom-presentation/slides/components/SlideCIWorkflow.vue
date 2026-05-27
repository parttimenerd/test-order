<template>
  <div class="h-full w-full flex flex-col items-center justify-center relative overflow-hidden" style="background: #0f0f1a">
    <div class="absolute inset-0 bg-gradient-to-br from-slate-950/60 via-transparent to-indigo-950/40"></div>

    <div class="relative z-10 w-full max-w-3xl px-12 flex flex-col gap-10">

      <div class="flex flex-col gap-5">

        <div class="flex items-center gap-6 bg-blue-950/40 border border-blue-500/30 rounded-2xl px-8 py-6">
          <span class="text-blue-400 font-mono font-bold text-4xl w-12 flex-shrink-0">0</span>
          <div class="flex-1">
            <div class="text-gray-100 font-semibold text-xl">Learn dependencies</div>
            <div class="text-blue-400/70 text-base mt-1">record what each test executes</div>
          </div>
          <div class="text-blue-300 font-bold text-2xl">nightly</div>
        </div>

        <div class="flex items-center gap-6 bg-emerald-950/40 border border-emerald-500/30 rounded-2xl px-8 py-6">
          <span class="text-emerald-400 font-mono font-bold text-4xl w-12 flex-shrink-0">1</span>
          <div class="flex-1">
            <div class="text-gray-100 font-semibold text-xl">Affected tests</div>
            <div class="text-emerald-400/70 text-base mt-1">tests that hit the change</div>
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
            <div class="text-gray-400 font-semibold text-xl">Remaining tests</div>
            <div class="text-gray-600 text-base mt-1">always run if tiers 1 &amp; 2 pass</div>
          </div>
          <div class="text-gray-500 font-bold text-2xl">on green</div>
        </div>

      </div>

    </div>
  </div>
</template>
