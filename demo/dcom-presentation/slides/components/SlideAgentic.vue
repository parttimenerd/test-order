<template>
  <div class="h-full w-full flex flex-col items-center justify-center relative overflow-hidden" style="background: #0f0f1a">
    <div class="absolute inset-0 bg-gradient-to-br from-indigo-950/30 via-transparent to-emerald-950/20"></div>
    <div class="relative z-10 text-center mb-12">
      <h2 class="!text-5xl !font-bold text-white">
        The <span class="bg-gradient-to-r from-blue-400 to-violet-400 bg-clip-text text-transparent">Agentic</span> Multiplier
      </h2>
    </div>
    <div class="relative z-10 w-full max-w-3xl">
      <slot />
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
</template>
