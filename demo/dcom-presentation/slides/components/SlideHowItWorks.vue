<template>
  <div class="h-full w-full flex flex-col items-center justify-center relative overflow-hidden" style="background: #0f0f1a">
    <div class="absolute inset-0 bg-gradient-to-br from-blue-950/40 via-transparent to-purple-950/30"></div>

    <div class="relative z-10 w-full max-w-5xl px-8 flex flex-col gap-6">

      <!-- Pipeline row -->
      <div class="flex items-stretch">

        <div class="flex flex-col items-center gap-1 flex-1 min-w-0">
          <div class="w-full bg-purple-950/60 border border-purple-500/40 rounded-lg px-2 py-2 text-center">
            <div class="text-purple-300 font-semibold text-xs leading-tight">Learn pass</div>
            <div class="text-purple-400/60 text-xs mt-0.5 leading-tight">bytecode instrumentation</div>
          </div>
          <div class="text-purple-400/50 text-xs whitespace-nowrap">once · 12% overhead</div>
        </div>

        <div class="text-gray-600 text-sm self-start mt-2.5 flex-shrink-0 px-1">→</div>

        <div class="flex flex-col items-center gap-1 flex-1 min-w-0">
          <div class="w-full bg-blue-950/60 border border-blue-500/40 rounded-lg px-2 py-2 text-center">
            <div class="text-blue-300 font-semibold text-xs leading-tight">Dep graph</div>
            <div class="text-blue-400/60 text-xs mt-0.5 leading-tight">test → prod classes</div>
          </div>
          <div class="text-blue-400/50 text-xs whitespace-nowrap">~500 KB · in git</div>
        </div>

        <div class="text-gray-600 text-sm self-start mt-2.5 flex-shrink-0 px-1">→</div>

        <div class="flex flex-col items-center gap-1 flex-1 min-w-0">
          <div class="w-full bg-slate-800/60 border border-slate-500/40 rounded-lg px-2 py-2 text-center">
            <div class="text-gray-300 font-semibold text-xs leading-tight">git diff</div>
            <div class="text-gray-400/60 text-xs mt-0.5 leading-tight">changed classes</div>
          </div>
          <div class="text-gray-400/50 text-xs whitespace-nowrap">every commit</div>
        </div>

        <div class="text-gray-600 text-sm self-start mt-2.5 flex-shrink-0 px-1">→</div>

        <div class="flex flex-col items-center gap-1 flex-1 min-w-0">
          <div class="w-full bg-emerald-950/60 border border-emerald-500/40 rounded-lg px-2 py-2 text-center">
            <div class="text-emerald-300 font-semibold text-xs leading-tight">Score &amp; select top N</div>
            <div class="text-emerald-400/60 text-xs mt-0.5 leading-tight">overlap · fail · speed</div>
          </div>
          <div class="text-emerald-400/50 text-xs whitespace-nowrap">~17s · not 5 min</div>
        </div>

      </div>

      <!-- Two-column detail -->
      <div class="grid grid-cols-2 gap-5">

        <div class="bg-white/4 border border-white/8 rounded-xl px-5 py-4">
          <div class="text-gray-400 text-xs font-semibold uppercase tracking-wider mb-3">CI pipeline</div>
          <div class="flex flex-col gap-2">
            <div class="flex items-baseline gap-2">
              <span class="text-emerald-400 font-mono text-xs w-6 flex-shrink-0">1</span>
              <span class="text-gray-300 text-sm">selected affected tests · <span class="text-emerald-400">seconds</span></span>
            </div>
            <div class="flex items-baseline gap-2">
              <span class="text-yellow-400 font-mono text-xs w-6 flex-shrink-0">2</span>
              <span class="text-gray-300 text-sm">high-score remainder · minutes</span>
            </div>
            <div class="flex items-baseline gap-2">
              <span class="text-gray-400 font-mono text-xs w-6 flex-shrink-0">3</span>
              <span class="text-gray-300 text-sm">full suite · nightly</span>
            </div>
            <div class="text-gray-500 text-xs mt-1">each stage skipped if previous failed · fast feedback, zero gaps</div>
          </div>
        </div>

        <div class="bg-white/4 border border-white/8 rounded-xl px-5 py-4">
          <div class="text-gray-400 text-xs font-semibold uppercase tracking-wider mb-3">Works with</div>
          <div class="grid grid-cols-2 gap-x-3 gap-y-1.5">
            <span class="text-gray-300 text-sm">Maven · Gradle</span>
            <span class="text-gray-300 text-sm">JUnit 5 · JUnit 4</span>
            <span class="text-gray-300 text-sm">TestNG · Kotest</span>
            <span class="text-gray-300 text-sm">zero config</span>
          </div>
        </div>

      </div>

    </div>
  </div>
</template>

