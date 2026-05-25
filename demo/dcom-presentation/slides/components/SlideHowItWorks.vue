<template>
  <div class="h-full w-full flex flex-col items-center justify-center relative overflow-hidden" style="background: #0f0f1a">
    <div class="absolute inset-0 bg-gradient-to-br from-blue-950/40 via-transparent to-purple-950/30"></div>
    <div class="relative z-10 text-center max-w-4xl">
      <h2 class="!text-5xl !font-bold !leading-snug text-white mb-12">
        What if we only ran the tests <br>
        <span class="bg-gradient-to-r from-emerald-400 to-cyan-400 bg-clip-text text-transparent">that matter?</span>
      </h2>
    </div>
    <div class="relative z-10 w-full max-w-3xl">
      <slot />
    </div>
    <div v-click class="relative z-10 mt-14">
      <div class="inline-flex items-center gap-3 bg-white/5 backdrop-blur-sm border border-white/10 rounded-full px-8 py-3">
        <div class="w-2 h-2 bg-emerald-400 rounded-full animate-pulse"></div>
        <span class="text-xl text-gray-300">One Maven plugin. Zero config. Let me show you.</span>
      </div>
    </div>
  </div>
</template>
