<template>
  <div class="h-full w-full flex flex-col items-center justify-center relative overflow-hidden" style="background: #0f0f1a">
    <div class="absolute inset-0 bg-gradient-to-br from-indigo-950/30 via-transparent to-emerald-950/20"></div>

    <div class="relative z-10 text-center mb-10">
      <h2 class="!text-4xl !font-bold text-white">
        AI + <span class="bg-gradient-to-r from-blue-400 to-emerald-400 bg-clip-text text-transparent">test-order</span>
        = Tight Feedback Loop
      </h2>
    </div>

    <!-- Loop diagram -->
    <div class="relative z-10 w-full max-w-4xl px-8">
      <div class="flex items-center justify-between gap-3">

        <!-- Step 1: AI edits -->
        <div v-click="1" class="flex flex-col items-center gap-2 flex-1">
          <div class="w-full bg-white/5 border border-blue-500/30 rounded-xl px-4 py-4 text-center">
            <div class="text-2xl mb-1">🤖</div>
            <div class="text-sm font-semibold text-blue-300">AI edits code</div>
            <div class="text-xs text-gray-500 mt-1">adds discount validation</div>
          </div>
        </div>

        <!-- Arrow 1 -->
        <div v-click="1" class="text-gray-600 text-xl flex-shrink-0">→</div>

        <!-- Step 2: test-order:select -->
        <div v-click="2" class="flex flex-col items-center gap-2 flex-1">
          <div class="w-full bg-white/5 border border-emerald-500/30 rounded-xl px-4 py-4 text-center">
            <div class="text-2xl mb-1">⚡</div>
            <div class="text-sm font-semibold text-emerald-300">test-order:select</div>
            <div class="text-xs text-gray-500 mt-1">runs affected tests only</div>
          </div>
        </div>

        <!-- Arrow 2 -->
        <div v-click="2" class="text-gray-600 text-xl flex-shrink-0">→</div>

        <!-- Step 3: Red — bug caught -->
        <div v-click="3" class="flex flex-col items-center gap-2 flex-1">
          <div class="w-full bg-red-950/40 border border-red-500/50 rounded-xl px-4 py-4 text-center">
            <div class="text-2xl mb-1">❌</div>
            <div class="text-sm font-semibold text-red-300">Bug caught!</div>
            <div class="text-xs text-gray-500 mt-1 font-mono">~17 seconds</div>
          </div>
        </div>

        <!-- Arrow 3: loop back -->
        <div v-click="4" class="text-gray-600 text-xl flex-shrink-0">→</div>

        <!-- Step 4: AI fixes -->
        <div v-click="4" class="flex flex-col items-center gap-2 flex-1">
          <div class="w-full bg-white/5 border border-violet-500/30 rounded-xl px-4 py-4 text-center">
            <div class="text-2xl mb-1">🔧</div>
            <div class="text-sm font-semibold text-violet-300">AI fixes it</div>
            <div class="text-xs text-gray-500 mt-1">reads failure output</div>
          </div>
        </div>

        <!-- Arrow 4 -->
        <div v-click="5" class="text-gray-600 text-xl flex-shrink-0">→</div>

        <!-- Step 5: Green -->
        <div v-click="5" class="flex flex-col items-center gap-2 flex-1">
          <div class="w-full bg-emerald-950/40 border border-emerald-500/50 rounded-xl px-4 py-4 text-center">
            <div class="text-2xl mb-1">✅</div>
            <div class="text-sm font-semibold text-emerald-300">Green!</div>
            <div class="text-xs text-gray-500 mt-1 font-mono">~17 seconds</div>
          </div>
        </div>

      </div>

      <!-- Total time callout -->
      <div v-click="6" class="mt-10 flex items-center justify-center gap-4">
        <div class="bg-white/5 border border-white/10 rounded-xl px-8 py-3 text-center">
          <span class="text-gray-400 text-sm">Edit → catch → fix → green: </span>
          <span class="text-emerald-400 font-bold text-lg">under 40 seconds</span>
          <span class="text-gray-500 text-sm ml-2">vs 6 minutes with full mvn test</span>
        </div>
      </div>
    </div>

    <!-- Bottom: copilot-instructions hint -->
    <div v-click="7" class="relative z-10 mt-8 max-w-3xl w-full px-8">
      <div class="bg-black/30 border border-white/10 rounded-lg px-5 py-3 flex items-center gap-4">
        <div class="text-gray-400 text-xs font-mono flex-shrink-0">.github/copilot-instructions.md</div>
        <div class="w-px h-4 bg-white/20 flex-shrink-0"></div>
        <div class="text-gray-300 text-xs font-mono truncate">
          After changes: <span class="text-emerald-400">mvn test-order:select test -pl srv</span>
        </div>
      </div>
    </div>
  </div>
</template>
