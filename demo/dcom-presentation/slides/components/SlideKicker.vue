<template>
  <div class="h-full w-full flex flex-col items-center justify-center relative overflow-hidden" style="background: #0f0f1a">
    <div class="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-140 h-140 bg-blue-500/5 rounded-full blur-3xl"></div>
    <div class="relative z-10 text-center max-w-3xl">
      <p class="text-4xl leading-relaxed text-gray-300">
        Maybe your test suite isn't too large.
      </p>
      <p v-click class="text-5xl font-bold mt-8 leading-relaxed bg-gradient-to-r from-blue-400 via-purple-400 to-emerald-400 bg-clip-text text-transparent">
        Maybe you're just running<br>the wrong tests first.
      </p>
    </div>
    <div v-click class="relative z-10 mt-16">
      <div class="inline-block bg-white/5 backdrop-blur-sm border border-emerald-500/20 rounded-xl px-8 py-4">
        <code class="text-xl text-emerald-400 font-mono">me.bechberger:test-order-maven-plugin</code>
      </div>
    </div>
    <div class="absolute bottom-10 left-1/2 -translate-x-1/2 z-10">
      <div class="w-16 h-1 bg-gradient-to-r from-blue-500 to-emerald-500 rounded-full opacity-40"></div>
    </div>
  </div>
</template>
