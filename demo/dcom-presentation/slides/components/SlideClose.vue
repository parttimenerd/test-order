<template>
  <div class="h-full w-full flex relative overflow-hidden" style="background: #0f0f1a">
    <div class="absolute inset-0 bg-gradient-to-br from-blue-950/25 via-transparent to-purple-950/25"></div>
    <div class="absolute -top-20 -left-20 w-96 h-96 bg-blue-500/8 rounded-full blur-3xl"></div>
    <div class="absolute -bottom-20 -right-20 w-96 h-96 bg-emerald-500/8 rounded-full blur-3xl"></div>
    <div class="relative z-10 flex-1 flex flex-col justify-center px-14 py-12">
      <h2 class="!text-4xl !font-bold text-white mb-10">
        Why <span class="bg-gradient-to-r from-blue-400 to-emerald-400 bg-clip-text text-transparent">test-order</span>?
      </h2>
      <div class="space-y-5">
        <div class="flex items-start gap-4">
          <div class="mt-1 flex-shrink-0 w-6 h-6 rounded-full bg-emerald-500/20 border border-emerald-500/40 flex items-center justify-center">
            <div class="w-2 h-2 bg-emerald-400 rounded-full"></div>
          </div>
          <div>
            <div class="text-gray-100 font-semibold">10× faster feedback</div>
            <div class="text-gray-500 text-sm">3:03 → 0:17 on a real SAP project</div>
          </div>
        </div>
        <div class="flex items-start gap-4">
          <div class="mt-1 flex-shrink-0 w-6 h-6 rounded-full bg-blue-500/20 border border-blue-500/40 flex items-center justify-center">
            <div class="w-2 h-2 bg-blue-400 rounded-full"></div>
          </div>
          <div>
            <div class="text-gray-100 font-semibold">Zero config, one plugin</div>
            <div class="text-gray-500 text-sm">Drop it in — works with Maven &amp; Gradle</div>
          </div>
        </div>
        <div class="flex items-start gap-4">
          <div class="mt-1 flex-shrink-0 w-6 h-6 rounded-full bg-violet-500/20 border border-violet-500/40 flex items-center justify-center">
            <div class="w-2 h-2 bg-violet-400 rounded-full"></div>
          </div>
          <div>
            <div class="text-gray-100 font-semibold">Agentic-friendly</div>
            <div class="text-gray-500 text-sm">Fast loops keep AI agents in flow</div>
          </div>
        </div>
        <div class="flex items-start gap-4">
          <div class="mt-1 flex-shrink-0 w-6 h-6 rounded-full bg-amber-500/20 border border-amber-500/40 flex items-center justify-center">
            <div class="w-2 h-2 bg-amber-400 rounded-full"></div>
          </div>
          <div>
            <div class="text-gray-100 font-semibold">Learn once, select forever</div>
            <div class="text-gray-500 text-sm">Offline bytecode instrumentation, no server needed</div>
          </div>
        </div>
        <div class="flex items-start gap-4">
          <div class="mt-1 flex-shrink-0 w-6 h-6 rounded-full bg-cyan-500/20 border border-cyan-500/40 flex items-center justify-center">
            <div class="w-2 h-2 bg-cyan-400 rounded-full"></div>
          </div>
          <div>
            <div class="text-gray-100 font-semibold">Open source</div>
            <div class="text-gray-500 text-sm">Apache 2.0 · contributions welcome</div>
          </div>
        </div>
      </div>
      <div class="mt-10 space-y-2">
        <div class="bg-black/30 border border-white/10 rounded-lg px-4 py-2.5 font-mono text-xs">
          <span class="text-gray-600">groupId:</span> <span class="text-blue-300">me.bechberger</span>
          <span class="text-gray-600 ml-4">artifactId:</span> <span class="text-emerald-300">test-order-maven-plugin</span>
        </div>
        <div class="bg-black/30 border border-white/10 rounded-lg px-4 py-2.5 font-mono text-xs">
          <span class="text-gray-600">Gradle plugin id:</span> <span class="text-emerald-300">me.bechberger.test-order</span>
        </div>
      </div>
      <div class="mt-8 text-sm text-gray-600">
        Johannes Bechberger · SAP DCOM 2026
      </div>
    </div>
    <div class="relative z-10 w-px bg-gradient-to-b from-transparent via-white/10 to-transparent my-12"></div>
    <div class="relative z-10 w-80 flex flex-col items-center justify-center gap-8 px-8">
      <div class="bg-white rounded-2xl p-5 shadow-2xl shadow-blue-500/20">
        <img src="/qr-github.svg" class="w-52 h-52" alt="QR code to GitHub repo" />
      </div>
      <div class="text-center">
        <div class="text-blue-400 font-mono text-sm font-medium">github.com/parttimenerd/test-order</div>
        <div class="text-gray-600 text-xs mt-2">Scan to open the repo</div>
      </div>
    </div>
  </div>
</template>
