<template>
  <div class="h-full w-full flex flex-col items-center justify-center relative overflow-hidden" style="background: #0f0f1a">
    <div class="absolute inset-0 bg-gradient-to-br from-indigo-950/30 via-transparent to-emerald-950/20"></div>

    <div class="relative z-10 text-center mb-8">
      <h2 class="!text-4xl !font-bold text-white">
        AI + <span class="bg-gradient-to-r from-blue-400 to-emerald-400 bg-clip-text text-transparent">test-order</span>
        = Tight Feedback Loop
      </h2>
      <!-- copilot-instructions hint — always visible, not gated -->
      <div class="mt-4 inline-flex items-center gap-3 bg-black/30 border border-white/10 rounded-lg px-5 py-2">
        <div class="text-gray-500 text-xs font-mono">.github/copilot-instructions.md</div>
        <div class="w-px h-3 bg-white/20 flex-shrink-0"></div>
        <div class="text-gray-400 text-xs font-mono">
          After changes: <span class="text-emerald-400">mvn test-order:select test</span>
        </div>
      </div>
    </div>

    <!-- Loop diagram -->
    <div class="relative z-10 w-full max-w-4xl px-8">
      <div class="flex items-center justify-between gap-3">

        <!-- Step 1: AI edits -->
        <div v-click="1" class="flex flex-col items-center gap-2 flex-1">
          <div class="w-full bg-white/5 border border-blue-500/30 rounded-xl px-4 py-4 text-center">
            <div class="text-2xl mb-1">🤖</div>
            <div class="text-sm font-semibold text-blue-300">AI edits code</div>
            <div class="text-xs text-gray-500 mt-1">inverts tenant check</div>
          </div>
        </div>

        <!-- Arrow 1 -->
        <div v-click="1" class="text-gray-600 text-xl flex-shrink-0">→</div>

        <!-- Step 2: test-order:select + Red -->
        <div v-click="2" class="flex flex-col items-center gap-2 flex-1">
          <div class="w-full bg-red-950/40 border border-red-500/50 rounded-xl px-4 py-4 text-center">
            <div class="text-2xl mb-1">⚡❌</div>
            <div class="text-sm font-semibold text-red-300">Bug caught</div>
            <div class="text-xs text-gray-500 mt-1 font-mono">~17 seconds</div>
          </div>
        </div>

        <!-- Arrow 2 -->
        <div v-click="3" class="text-gray-600 text-xl flex-shrink-0">→</div>

        <!-- Step 3: AI fixes -->
        <div v-click="3" class="flex flex-col items-center gap-2 flex-1">
          <div class="w-full bg-white/5 border border-violet-500/30 rounded-xl px-4 py-4 text-center">
            <div class="text-2xl mb-1">🔧</div>
            <div class="text-sm font-semibold text-violet-300">AI fixes it</div>
            <div class="text-xs text-gray-500 mt-1">reads failure output</div>
          </div>
        </div>

        <!-- Arrow 3 -->
        <div v-click="3" class="text-gray-600 text-xl flex-shrink-0">→</div>

        <!-- Step 4: Green -->
        <div v-click="3" class="flex flex-col items-center gap-2 flex-1">
          <div class="w-full bg-emerald-950/40 border border-emerald-500/50 rounded-xl px-4 py-4 text-center">
            <div class="text-2xl mb-1">✅</div>
            <div class="text-sm font-semibold text-emerald-300">Green</div>
            <div class="text-xs text-gray-500 mt-1 font-mono">~17 seconds</div>
          </div>
        </div>

      </div>

      <!-- Total time callout -->
      <div v-click="4" class="mt-10 flex items-center justify-center gap-4">
        <div class="bg-white/5 border border-white/10 rounded-xl px-8 py-3 text-center">
          <span class="text-gray-400 text-sm">Edit → catch → fix → green: </span>
          <span class="text-emerald-400 font-bold text-lg">under 40 seconds</span>
          <span class="text-gray-500 text-sm ml-2">vs 5+ minutes with full mvn test</span>
        </div>
      </div>
    </div>

  </div>
</template>
