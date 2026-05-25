<template>
  <div class="h-full w-full flex flex-col relative overflow-hidden px-12 py-8" style="background: #0f0f1a">
    <div class="absolute inset-0 bg-gradient-to-br from-indigo-950/30 via-transparent to-emerald-950/20"></div>
    <div class="relative z-10 text-center mb-6">
      <h2 class="!text-3xl !font-bold text-white">
        How <span class="bg-gradient-to-r from-emerald-400 to-cyan-400 bg-clip-text text-transparent">Test Selection</span> Works
      </h2>
    </div>
    <div class="relative z-10 flex gap-8 flex-1">
      <div class="flex-1 flex flex-col">
        <div v-click="1" class="mb-3">
          <span class="text-sm uppercase tracking-wider text-amber-400 font-semibold">Changed file</span>
          <div class="mt-2 bg-black/40 border border-amber-500/30 rounded-lg p-4 font-mono text-sm">
            <div class="text-gray-500">// DestinationService.java</div>
            <div class="text-red-400/70 line-through">- return cache.get(name);</div>
            <div class="text-emerald-400">+ return cache.getOrFetch(name, this::resolve);</div>
          </div>
        </div>
        <div v-click="2" class="mt-4">
          <span class="text-sm uppercase tracking-wider text-blue-400 font-semibold">Dependency graph knows:</span>
          <div class="mt-2 text-gray-400 text-sm space-y-1">
            <div class="flex items-center gap-2">
              <div class="w-2 h-2 bg-emerald-400 rounded-full"></div>
              <span>3 tests exercise this code path</span>
            </div>
            <div class="flex items-center gap-2">
              <div class="w-2 h-2 bg-gray-600 rounded-full"></div>
              <span>5 tests don't touch it at all</span>
            </div>
          </div>
        </div>
      </div>
      <div class="flex-1 flex flex-col">
        <span class="text-sm uppercase tracking-wider text-gray-400 font-semibold mb-3">Test execution order</span>
        <div class="space-y-2">
          <div v-click="[0,4]" class="space-y-2">
            <div class="flex items-center gap-3 bg-white/5 border border-white/10 rounded-lg px-4 py-2.5 font-mono text-sm">
              <span class="text-gray-500 w-5">1.</span>
              <span class="text-gray-300">AuthTokenProviderTest</span>
            </div>
            <div class="flex items-center gap-3 bg-white/5 border border-white/10 rounded-lg px-4 py-2.5 font-mono text-sm">
              <span class="text-gray-500 w-5">2.</span>
              <span class="text-gray-300">CacheConfigTest</span>
            </div>
            <div :class="$clicks >= 2 ? 'border-emerald-500/40 bg-emerald-950/20' : 'border-white/10 bg-white/5'" class="flex items-center gap-3 border rounded-lg px-4 py-2.5 font-mono text-sm transition-all duration-500">
              <span class="text-gray-500 w-5">3.</span>
              <span :class="$clicks >= 2 ? 'text-emerald-300' : 'text-gray-300'" class="transition-colors duration-500">DestinationResolverTest</span>
              <span v-if="$clicks >= 2" class="ml-auto text-xs text-emerald-400 bg-emerald-400/10 px-2 py-0.5 rounded">calls changed code</span>
            </div>
            <div :class="$clicks >= 2 ? 'border-emerald-500/40 bg-emerald-950/20' : 'border-white/10 bg-white/5'" class="flex items-center gap-3 border rounded-lg px-4 py-2.5 font-mono text-sm transition-all duration-500">
              <span class="text-gray-500 w-5">4.</span>
              <span :class="$clicks >= 2 ? 'text-emerald-300' : 'text-gray-300'" class="transition-colors duration-500">DestinationServiceTest</span>
              <span v-if="$clicks >= 2" class="ml-auto text-xs text-emerald-400 bg-emerald-400/10 px-2 py-0.5 rounded">calls changed code</span>
            </div>
            <div class="flex items-center gap-3 bg-white/5 border border-white/10 rounded-lg px-4 py-2.5 font-mono text-sm">
              <span class="text-gray-500 w-5">5.</span>
              <span class="text-gray-300">HttpClientFactoryTest</span>
            </div>
            <div :class="$clicks >= 2 ? 'border-emerald-500/40 bg-emerald-950/20' : 'border-white/10 bg-white/5'" class="flex items-center gap-3 border rounded-lg px-4 py-2.5 font-mono text-sm transition-all duration-500">
              <span class="text-gray-500 w-5">6.</span>
              <span :class="$clicks >= 2 ? 'text-emerald-300' : 'text-gray-300'" class="transition-colors duration-500">OnPremiseProxyTest</span>
              <span v-if="$clicks >= 2" class="ml-auto text-xs text-emerald-400 bg-emerald-400/10 px-2 py-0.5 rounded">calls changed code</span>
            </div>
            <div class="flex items-center gap-3 bg-white/5 border border-white/10 rounded-lg px-4 py-2.5 font-mono text-sm">
              <span class="text-gray-500 w-5">7.</span>
              <span class="text-gray-300">RetryHandlerTest</span>
            </div>
            <div class="flex items-center gap-3 bg-white/5 border border-white/10 rounded-lg px-4 py-2.5 font-mono text-sm">
              <span class="text-gray-500 w-5">8.</span>
              <span class="text-gray-300">TenantIsolationTest</span>
            </div>
          </div>
          <div v-click="4" class="space-y-2 absolute inset-0">
            <div class="flex items-center gap-3 bg-emerald-950/30 border border-emerald-500/40 rounded-lg px-4 py-2.5 font-mono text-sm">
              <span class="text-emerald-400 w-5 font-bold">1.</span>
              <span class="text-emerald-300 font-medium">DestinationResolverTest</span>
              <span class="ml-auto text-xs font-bold text-emerald-400">★ affected</span>
            </div>
            <div class="flex items-center gap-3 bg-emerald-950/30 border border-emerald-500/40 rounded-lg px-4 py-2.5 font-mono text-sm">
              <span class="text-emerald-400 w-5 font-bold">2.</span>
              <span class="text-emerald-300 font-medium">DestinationServiceTest</span>
              <span class="ml-auto text-xs font-bold text-emerald-400">★ affected</span>
            </div>
            <div class="flex items-center gap-3 bg-emerald-950/30 border border-emerald-500/40 rounded-lg px-4 py-2.5 font-mono text-sm">
              <span class="text-emerald-400 w-5 font-bold">3.</span>
              <span class="text-emerald-300 font-medium">OnPremiseProxyTest</span>
              <span class="ml-auto text-xs font-bold text-emerald-400">★ affected</span>
            </div>
            <div class="flex items-center gap-3 bg-white/3 border border-white/5 rounded-lg px-4 py-2.5 font-mono text-sm opacity-40">
              <span class="text-gray-600 w-5">4.</span>
              <span class="text-gray-500">AuthTokenProviderTest</span>
              <span class="ml-auto text-xs text-gray-600">skipped</span>
            </div>
            <div class="flex items-center gap-3 bg-white/3 border border-white/5 rounded-lg px-4 py-2.5 font-mono text-sm opacity-40">
              <span class="text-gray-600 w-5">5.</span>
              <span class="text-gray-500">CacheConfigTest</span>
              <span class="ml-auto text-xs text-gray-600">skipped</span>
            </div>
            <div class="flex items-center gap-3 bg-white/3 border border-white/5 rounded-lg px-4 py-2.5 font-mono text-sm opacity-40">
              <span class="text-gray-600 w-5">6.</span>
              <span class="text-gray-500">HttpClientFactoryTest</span>
              <span class="ml-auto text-xs text-gray-600">skipped</span>
            </div>
            <div class="flex items-center gap-3 bg-white/3 border border-white/5 rounded-lg px-4 py-2.5 font-mono text-sm opacity-40">
              <span class="text-gray-600 w-5">7.</span>
              <span class="text-gray-500">RetryHandlerTest</span>
              <span class="ml-auto text-xs text-gray-600">skipped</span>
            </div>
            <div class="flex items-center gap-3 bg-white/3 border border-white/5 rounded-lg px-4 py-2.5 font-mono text-sm opacity-40">
              <span class="text-gray-600 w-5">8.</span>
              <span class="text-gray-500">TenantIsolationTest</span>
              <span class="ml-auto text-xs text-gray-600">skipped</span>
            </div>
          </div>
        </div>
      </div>
    </div>
    <div v-click="3" class="relative z-10 text-center mt-4">
      <div class="inline-flex items-center gap-3 bg-emerald-500/10 border border-emerald-500/20 rounded-full px-6 py-2">
        <span class="text-lg text-emerald-300 font-medium">3 tests instead of 8 → 10× faster feedback</span>
      </div>
    </div>
  </div>
</template>
