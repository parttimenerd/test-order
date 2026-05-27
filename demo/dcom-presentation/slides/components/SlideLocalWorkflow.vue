<template>
  <div class="h-full w-full flex flex-col items-center justify-center relative overflow-hidden" style="background: #0f0f1a">
    <div class="absolute inset-0 bg-gradient-to-br from-blue-950/40 via-transparent to-purple-950/30"></div>

    <div class="relative z-10 w-full max-w-5xl px-12 flex flex-col gap-10">

      <div class="text-gray-400 text-sm font-semibold uppercase tracking-widest">Local workflow</div>

      <div class="flex items-center gap-0">

        <div class="flex flex-col items-center gap-3 flex-1">
          <div class="w-full bg-purple-950/60 border border-purple-500/40 rounded-2xl px-6 py-6 text-center">
            <div class="text-purple-300 font-bold text-xl">Learn pass</div>
            <div class="text-purple-400/70 text-base mt-2">bytecode instrumentation</div>
          </div>
          <div class="text-purple-400/60 text-sm">once · ~10% overhead · run in CI</div>
        </div>

        <div class="flex flex-col items-center flex-shrink-0 px-3 pb-8">
          <div class="text-gray-500 text-xs mb-1">builds</div>
          <div class="text-gray-400 text-2xl">→</div>
        </div>

        <div class="flex flex-col items-center gap-3 flex-1">
          <div class="w-full bg-blue-950/60 border border-blue-500/40 rounded-2xl px-6 py-6 text-center">
            <div class="text-blue-300 font-bold text-xl">Dep graph</div>
            <div class="text-blue-400/70 text-base mt-2">test → prod classes</div>
          </div>
          <div class="text-blue-400/60 text-sm">~500 KB · local or from CI</div>
        </div>

        <div class="flex flex-col items-center flex-shrink-0 px-3 pb-8">
          <div class="text-gray-500 text-xs mb-1">intersect with</div>
          <div class="text-gray-400 text-2xl">→</div>
        </div>

        <div class="flex flex-col items-center gap-3 flex-1">
          <div class="w-full bg-slate-800/60 border border-slate-500/40 rounded-2xl px-6 py-6 text-center">
            <div class="text-gray-200 font-bold text-xl">Changed files</div>
            <div class="text-gray-400/70 text-base mt-2">git diff · uncommitted</div>
          </div>
          <div class="text-gray-500 text-sm">every run</div>
        </div>

        <div class="flex flex-col items-center flex-shrink-0 px-3 pb-8">
          <div class="text-gray-500 text-xs mb-1">score &amp; pick top N</div>
          <div class="text-gray-400 text-2xl">→</div>
        </div>

        <div class="flex flex-col items-center gap-3 flex-1">
          <div class="w-full bg-emerald-950/60 border border-emerald-500/40 rounded-2xl px-6 py-6 text-center">
            <div class="text-emerald-300 font-bold text-xl">Affected tests</div>
            <div class="text-emerald-400/70 text-base mt-2">overlap · failure rate · speed</div>
          </div>
          <div class="text-emerald-400/60 text-sm">~25s · not 5 min</div>
        </div>

      </div>

      <div class="text-gray-500 text-sm text-center">Maven · Gradle &nbsp;·&nbsp; JUnit · TestNG · Kotest &nbsp;·&nbsp; <span class="text-emerald-400 font-semibold">zero config</span></div>

    </div>
  </div>
</template>
