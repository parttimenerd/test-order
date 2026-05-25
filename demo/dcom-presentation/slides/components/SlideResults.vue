<template>
  <div class="h-full w-full flex flex-col items-center justify-center relative overflow-hidden" style="background: #0f0f1a">
    <div class="absolute top-1/3 left-1/4 w-64 h-64 bg-red-500/15 rounded-full blur-3xl"></div>
    <div class="absolute top-1/3 right-1/4 w-64 h-64 bg-emerald-500/15 rounded-full blur-3xl"></div>
    <div class="relative z-10 flex items-end justify-center gap-20 mb-12">
      <div v-motion :initial="{ scale: 0.5, opacity: 0 }" :enter="{ scale: 1, opacity: 1, transition: { delay: 200, duration: 600 } }" class="text-center">
        <div class="text-8xl font-black text-red-400 tabular-nums tracking-tight" style="text-shadow: 0 0 40px rgba(248,113,113,0.3)">3:03</div>
        <div class="mt-4 text-lg text-red-300/60 uppercase tracking-widest font-medium">before</div>
      </div>
      <div v-motion :initial="{ x: -20, opacity: 0 }" :enter="{ x: 0, opacity: 1, transition: { delay: 600, duration: 400 } }" class="mb-8">
        <svg class="w-12 h-12 text-gray-600" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M5 12h14M12 5l7 7-7 7"/>
        </svg>
      </div>
      <div v-motion :initial="{ scale: 0.5, opacity: 0 }" :enter="{ scale: 1, opacity: 1, transition: { delay: 800, duration: 600 } }" class="text-center">
        <div class="text-8xl font-black text-emerald-400 tabular-nums tracking-tight" style="text-shadow: 0 0 40px rgba(52,211,153,0.3)">0:17</div>
        <div class="mt-4 text-lg text-emerald-300/60 uppercase tracking-widest font-medium">after</div>
      </div>
    </div>
    <div v-click class="relative z-10 mt-8">
      <p class="text-3xl text-gray-300 text-center leading-relaxed">
        Same change. Same confidence.
        <span class="font-bold bg-gradient-to-r from-yellow-300 to-amber-400 bg-clip-text text-transparent">10× faster.</span>
      </p>
    </div>
    <div v-click class="relative z-10 mt-12">
      <p class="text-xl text-gray-500 text-center italic">
        Now imagine a Copilot agent doing this — making a mistake, catching it, fixing it.
      </p>
    </div>
  </div>
</template>
