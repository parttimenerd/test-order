<template>
  <div class="h-full w-full flex flex-col items-center justify-center relative overflow-hidden" style="background: #0f0f1a">
    <div class="absolute -top-20 -left-20 w-96 h-96 bg-blue-500/10 rounded-full blur-3xl animate-pulse"></div>
    <div class="absolute -bottom-32 -right-20 w-120 h-120 bg-purple-500/10 rounded-full blur-3xl animate-pulse" style="animation-delay: 1s"></div>
    <div class="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-160 h-160 bg-emerald-500/5 rounded-full blur-3xl"></div>
    <div class="relative z-10 text-center">
      <h1 class="!text-6xl !font-bold !leading-tight bg-gradient-to-r from-blue-400 via-purple-400 to-emerald-400 bg-clip-text text-transparent">
        You Are Running<br>the Wrong Tests First
      </h1>
      <div v-click class="mt-10">
        <p class="text-2xl text-gray-400 leading-relaxed tracking-wide">
          Predictive Test Ordering for<br>Faster Feedback &amp; Agentic Development
        </p>
      </div>
      <div v-click class="mt-10 flex items-center justify-center gap-10">
        <div class="flex flex-col items-center gap-2">
          <img src="/sapmachine.svg" class="w-12 h-12 object-contain rounded-lg opacity-80" alt="SapMachine" />
          <span class="text-xs text-gray-500 font-medium">SapMachine</span>
        </div>
        <div class="flex flex-col items-center gap-2">
          <img src="/junit5.png" class="w-12 h-12 object-contain opacity-80" alt="JUnit 5" />
          <span class="text-xs text-gray-500 font-medium">JUnit 5</span>
        </div>
        <div class="flex flex-col items-center gap-2">
          <img src="/maven.png" class="h-10 w-24 object-contain opacity-80" alt="Maven" />
          <span class="text-xs text-gray-500 font-medium">Maven</span>
        </div>
        <div class="flex flex-col items-center gap-2">
          <img src="/gradle.png" class="w-12 h-12 object-contain opacity-80" alt="Gradle" />
          <span class="text-xs text-gray-500 font-medium">Gradle</span>
        </div>
      </div>
    </div>
    <div class="absolute bottom-8 left-8 z-10">
      <div class="text-lg font-medium text-gray-400">Johannes Bechberger</div>
      <div class="text-sm text-gray-600 mt-1">SAP DCOM 2026 · Demo Jam</div>
    </div>
  </div>
</template>
