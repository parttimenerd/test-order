<template>
  <div class="h-full w-full flex flex-col items-center justify-center relative overflow-hidden" style="background: #0f0f1a">
    <div class="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-140 h-140 bg-blue-500/5 rounded-full blur-3xl"></div>
    <div class="relative z-10 text-center max-w-3xl">
      <p class="text-2xl text-gray-500 leading-relaxed">
        Maybe your test suite isn't too large.
      </p>
      <p v-click class="text-6xl font-bold mt-10 leading-tight bg-gradient-to-r from-blue-400 via-purple-400 to-emerald-400 bg-clip-text text-transparent">
        Maybe you're just running<br>the wrong tests first.
      </p>
    </div>
  </div>
</template>
