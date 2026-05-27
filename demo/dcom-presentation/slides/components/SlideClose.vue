<template>
  <div class="h-full w-full flex flex-col items-center justify-center relative overflow-hidden" style="background: #0f0f1a">
    <div class="absolute inset-0 bg-gradient-to-br from-blue-950/25 via-transparent to-purple-950/25"></div>
    <div class="absolute -top-20 -left-20 w-96 h-96 bg-blue-500/8 rounded-full blur-3xl"></div>
    <div class="absolute -bottom-20 -right-20 w-96 h-96 bg-emerald-500/8 rounded-full blur-3xl"></div>

    <div class="relative z-10 w-full max-w-5xl px-12 flex flex-col gap-8">

      <div class="text-white text-2xl font-semibold text-center">
        Stop wasting hours on tests that never fail.
      </div>

      <div class="flex gap-10 items-center">

        <!-- Left: benefits -->
        <div class="flex-1 flex flex-col gap-5">
          <div class="flex items-start gap-3">
            <span class="text-emerald-400 font-bold text-lg mt-0.5">✓</span>
            <span class="text-gray-200 text-lg">Only run affected tests</span>
          </div>
          <div class="flex items-start gap-3">
            <span class="text-emerald-400 font-bold text-lg mt-0.5">✓</span>
            <span class="text-gray-200 text-lg">Higher dev velocity</span>
          </div>
          <div class="flex items-start gap-3">
            <span class="text-emerald-400 font-bold text-lg mt-0.5">✓</span>
            <span class="text-gray-200 text-lg">Agentic loops that don't stall</span>
          </div>
          <div class="flex items-start gap-3">
            <span class="text-emerald-400 font-bold text-lg mt-0.5">✓</span>
            <span class="text-gray-200 text-lg">Bugs caught immediately, not the next morning</span>
          </div>
          <div class="flex items-start gap-3">
            <span class="text-emerald-400 font-bold text-lg mt-0.5">✓</span>
            <span class="text-gray-200 text-lg">Cut your CI bill — run less, find more</span>
          </div>
        </div>

        <!-- Right: QR code -->
        <div class="flex flex-col items-center gap-4 w-56">
          <div class="text-blue-400 font-mono text-xs text-center leading-tight">github.com/parttimenerd/<br>test-order</div>
          <div class="bg-white rounded-2xl p-4 shadow-2xl shadow-blue-500/20">
            <img src="/qr-github.svg" class="w-36 h-36" alt="QR code to GitHub repo" />
          </div>
          <div class="text-center">
            <div class="text-gray-400 text-sm">experimental · open-source</div>
            <div class="text-gray-600 text-xs mt-1">Apache 2.0 · Maven &amp; Gradle</div>
          </div>
        </div>

      </div>

    </div>

    <div class="absolute bottom-10 left-1/2 -translate-x-1/2 z-10 text-gray-700 text-sm">
      Johannes Bechberger · SAP DCOM 2026
    </div>
  </div>
</template>
