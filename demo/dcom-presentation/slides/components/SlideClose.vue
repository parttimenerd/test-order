<template>
  <div class="h-full w-full flex flex-col items-center justify-center relative overflow-hidden" style="background: #0f0f1a">
    <div class="absolute inset-0 bg-gradient-to-br from-blue-950/25 via-transparent to-purple-950/25"></div>
    <div class="absolute -top-20 -left-20 w-96 h-96 bg-blue-500/8 rounded-full blur-3xl"></div>
    <div class="absolute -bottom-20 -right-20 w-96 h-96 bg-emerald-500/8 rounded-full blur-3xl"></div>

    <div class="relative z-10 flex flex-col items-center gap-8">
      <p class="text-2xl text-gray-400 text-center">
        One plugin. One learn run. Every commit after that:
        <span class="text-emerald-400 font-semibold">signal in seconds.</span>
      </p>
      <div class="bg-white rounded-2xl p-5 shadow-2xl shadow-blue-500/20">
        <img src="/qr-github.svg" class="w-48 h-48" alt="QR code to GitHub repo" />
      </div>
      <div class="text-center space-y-2">
        <div class="text-blue-400 font-mono text-lg font-medium">github.com/parttimenerd/test-order</div>
        <div class="text-gray-500 text-sm">Apache 2.0 · Maven &amp; Gradle · zero config</div>
      </div>
    </div>

    <div class="absolute bottom-10 left-1/2 -translate-x-1/2 z-10 text-gray-700 text-sm">
      Johannes Bechberger · SAP DCOM 2026
    </div>
  </div>
</template>
