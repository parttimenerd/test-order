<template>
  <div class="h-full w-full flex flex-col items-center justify-center relative overflow-hidden" style="background: #0f0f1a">
    <div class="absolute inset-0 bg-gradient-to-br from-purple-950/30 via-transparent to-emerald-950/20"></div>

    <div class="relative z-10 w-full max-w-4xl px-12 flex flex-col gap-10">

      <div class="text-gray-400 text-sm font-semibold uppercase tracking-widest text-center">learn pass: what each test really executes</div>

      <!-- Dep graph diagram -->
      <div class="flex items-center justify-center gap-0">

        <!-- Tests -->
        <div class="flex flex-col gap-8 items-end w-48">
          <div class="w-full bg-purple-950/60 border border-purple-500/40 rounded-2xl px-5 py-4 text-center">
            <div class="text-purple-300 font-mono font-bold text-xl">testA</div>
          </div>
          <div class="w-full bg-purple-950/40 border border-purple-500/25 rounded-2xl px-5 py-4 text-center">
            <div class="text-purple-300/70 font-mono font-bold text-xl">testB</div>
          </div>
        </div>

        <!-- Arrow group -->
        <div class="flex flex-col gap-8 items-center px-10">
          <!-- testA → methodA (solid, highlighted) -->
          <div class="flex flex-col items-center gap-1">
            <div class="flex items-center gap-1">
              <div class="h-0.5 w-20 bg-emerald-500/70"></div>
              <div class="text-emerald-400 text-xl">→</div>
            </div>
            <div class="text-emerald-400/80 text-xs font-semibold uppercase tracking-wider">re-run</div>
          </div>
          <!-- testB → methodB (dimmer) -->
          <div class="flex flex-col items-center gap-1">
            <div class="flex items-center gap-1">
              <div class="h-0.5 w-20 bg-gray-600/60"></div>
              <div class="text-gray-500 text-xl">→</div>
            </div>
            <div class="text-gray-500 text-xs font-semibold uppercase tracking-wider">skipped</div>
          </div>
        </div>

        <!-- Methods -->
        <div class="flex flex-col gap-8 items-start w-48">
          <div class="w-full bg-emerald-950/60 border border-emerald-500/40 rounded-2xl px-5 py-4 text-center">
            <div class="text-emerald-300 font-mono font-bold text-xl">methodA</div>
          </div>
          <div class="w-full bg-blue-950/40 border border-blue-500/25 rounded-2xl px-5 py-4 text-center">
            <div class="text-blue-300/70 font-mono font-bold text-xl">methodB</div>
          </div>
        </div>

      </div>

    </div>
  </div>
</template>
