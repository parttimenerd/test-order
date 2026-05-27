<template>
  <div class="h-full w-full flex flex-col items-center justify-center relative overflow-hidden" style="background: #0f0f1a">
    <div class="absolute inset-0 bg-gradient-to-br from-blue-950/25 via-transparent to-purple-950/25"></div>
    <div class="absolute -top-20 -left-20 w-96 h-96 bg-blue-500/8 rounded-full blur-3xl"></div>
    <div class="absolute -bottom-20 -right-20 w-96 h-96 bg-emerald-500/8 rounded-full blur-3xl"></div>

    <div class="relative z-10 w-full max-w-5xl px-12 flex flex-col gap-10">

      <div class="text-white text-3xl font-semibold text-center tracking-tight">
        Stop running tests that never fail.
      </div>

      <div class="flex gap-12 items-center">

        <!-- Left: benefits -->
        <div class="flex-1 flex flex-col gap-6">
          <div class="flex items-start gap-3">
            <span class="text-emerald-400 font-bold text-xl mt-0.5">✓</span>
            <span class="text-gray-100 text-xl">Only run tests that hit the change</span>
          </div>
          <div class="flex items-start gap-3">
            <span class="text-emerald-400 font-bold text-xl mt-0.5">✓</span>
            <span class="text-gray-100 text-xl">Agentic loops that don't stall</span>
          </div>
          <div class="flex items-start gap-3">
            <span class="text-emerald-400 font-bold text-xl mt-0.5">✓</span>
            <span class="text-gray-100 text-xl">Bugs in seconds, not overnight</span>
          </div>
        </div>

        <!-- Right: QR code -->
        <div class="flex flex-col items-center gap-3 w-60">
          <div class="bg-white rounded-2xl p-4 shadow-2xl shadow-blue-500/20">
            <img src="/qr-github.svg" class="w-40 h-40" alt="QR code to GitHub repo" />
          </div>
          <div class="text-blue-300 font-mono text-sm text-center leading-tight">
            github.com/parttimenerd/<br>test-order
          </div>
          <div class="text-gray-400 text-xs mt-1">experimental · open-source</div>
        </div>

      </div>

    </div>

    <div class="absolute bottom-10 left-1/2 -translate-x-1/2 z-10 text-gray-700 text-sm">
      Johannes Bechberger · SAP d-com Mannheim 2026
    </div>
  </div>
</template>
