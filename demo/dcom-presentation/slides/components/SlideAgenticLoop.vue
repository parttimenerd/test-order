<template>
  <div class="h-full w-full flex flex-col items-center justify-center relative overflow-hidden" style="background: #0f0f1a">
    <div class="absolute inset-0 bg-gradient-to-br from-indigo-950/30 via-transparent to-emerald-950/20"></div>

    <div class="relative z-10 text-center mb-10">
      <h2 class="!text-4xl !font-bold text-white">
        You just watched it happen.
      </h2>
    </div>

    <div class="relative z-10 w-full max-w-3xl px-8 flex flex-col gap-5">

      <!-- What the agent did -->
      <div v-click="1" class="flex items-center gap-6 bg-white/5 border border-white/10 rounded-xl px-6 py-4">
        <div class="text-3xl font-black text-emerald-400 tabular-nums w-20 text-right flex-shrink-0">34 s</div>
        <div class="flex-1">
          <div class="text-base text-gray-300 font-medium">edit → caught → fixed → green</div>
          <div class="text-sm text-gray-500 mt-0.5">two test runs, one file read, one line changed</div>
        </div>
      </div>

      <!-- What it would have been -->
      <div v-click="1" class="flex items-center gap-6 bg-red-950/30 border border-red-900/40 rounded-xl px-6 py-4">
        <div class="text-3xl font-black text-red-400 tabular-nums w-20 text-right flex-shrink-0">10 min</div>
        <div class="flex-1">
          <div class="text-base text-gray-300 font-medium">same loop with full <span class="font-mono text-gray-400">mvn test</span></div>
          <div class="text-sm text-gray-500 mt-0.5">5 min to catch, 5 min to verify</div>
        </div>
      </div>

      <!-- The point -->
      <div v-click="2" class="mt-2 bg-white/5 border border-yellow-500/20 rounded-xl px-6 py-5 text-center">
        <p class="text-gray-300 text-lg leading-relaxed">
          The bottleneck in AI-assisted development
          <br>isn't intelligence.
          <span class="text-yellow-300 font-bold"> It's the wait.</span>
        </p>
      </div>

    </div>
  </div>
</template>
