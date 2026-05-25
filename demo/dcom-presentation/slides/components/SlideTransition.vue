<template>
  <div class="h-full w-full flex flex-col items-center justify-center relative overflow-hidden" style="background: #0f0f1a">
    <div class="absolute inset-0 bg-gradient-to-br from-slate-950/60 via-transparent to-indigo-950/40"></div>
    <div class="absolute top-1/3 left-1/4 w-80 h-80 bg-red-500/8 rounded-full blur-3xl"></div>
    <div class="absolute top-1/3 right-1/4 w-80 h-80 bg-emerald-500/8 rounded-full blur-3xl"></div>
    <div class="relative z-10 w-full max-w-5xl px-12">
      <div class="grid grid-cols-2 gap-16 items-center">
        <div class="space-y-5">
          <div class="flex items-center gap-3 mb-6">
            <div class="w-3 h-3 bg-red-400 rounded-full"></div>
            <span class="text-sm uppercase tracking-widest text-red-400 font-semibold">Legacy Maintenance</span>
          </div>
          <div class="space-y-4 text-gray-400">
            <div class="flex items-start gap-3">
              <span class="text-red-400 text-lg mt-0.5">✗</span>
              <span class="text-base leading-snug">Edit a method → wait <span class="text-red-300 font-semibold">3 minutes</span> to know if it broke</span>
            </div>
            <div class="flex items-start gap-3">
              <span class="text-red-400 text-lg mt-0.5">✗</span>
              <span class="text-base leading-snug">AI agent makes 10 edits → <span class="text-red-300 font-semibold">30 minutes</span> of test time</span>
            </div>
            <div class="flex items-start gap-3">
              <span class="text-red-400 text-lg mt-0.5">✗</span>
              <span class="text-base leading-snug">Slow feedback loops <span class="text-red-300 font-semibold">kill iteration speed</span></span>
            </div>
            <div class="flex items-start gap-3">
              <span class="text-red-400 text-lg mt-0.5">✗</span>
              <span class="text-base leading-snug">Developers skip running tests <span class="text-red-300 font-semibold">altogether</span></span>
            </div>
          </div>
        </div>
        <div class="space-y-5">
          <div class="flex items-center gap-3 mb-6">
            <div class="w-3 h-3 bg-emerald-400 rounded-full animate-pulse"></div>
            <span class="text-sm uppercase tracking-widest text-emerald-400 font-semibold">With test-order</span>
          </div>
          <div class="space-y-4 text-gray-300">
            <div class="flex items-start gap-3">
              <span class="text-emerald-400 text-lg mt-0.5">✓</span>
              <span class="text-base leading-snug">Same edit → feedback in <span class="text-emerald-300 font-semibold">17 seconds</span></span>
            </div>
            <div class="flex items-start gap-3">
              <span class="text-emerald-400 text-lg mt-0.5">✓</span>
              <span class="text-base leading-snug">AI agent makes 10 edits → <span class="text-emerald-300 font-semibold">~3 minutes</span> total</span>
            </div>
            <div class="flex items-start gap-3">
              <span class="text-emerald-400 text-lg mt-0.5">✓</span>
              <span class="text-base leading-snug">Tight loops <span class="text-emerald-300 font-semibold">accelerate agentic coding</span></span>
            </div>
            <div class="flex items-start gap-3">
              <span class="text-emerald-400 text-lg mt-0.5">✓</span>
              <span class="text-base leading-snug">One plugin, <span class="text-emerald-300 font-semibold">zero config</span> — add &amp; go</span>
            </div>
          </div>
        </div>
      </div>
      <div class="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 flex flex-col items-center gap-1">
        <div class="w-px h-16 bg-gradient-to-b from-transparent via-gray-600 to-transparent"></div>
        <svg class="w-6 h-6 text-gray-500" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M5 12h14M12 5l7 7-7 7"/>
        </svg>
        <div class="w-px h-16 bg-gradient-to-b from-transparent via-gray-600 to-transparent"></div>
      </div>
    </div>
  </div>
</template>
