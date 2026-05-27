<template>
  <div class="h-full w-full flex flex-col items-center justify-center relative overflow-hidden" style="background: #0f0f1a">
    <div class="absolute inset-0 bg-gradient-to-br from-blue-950/30 via-transparent to-slate-950/40"></div>

    <div class="relative z-10 w-full max-w-5xl px-12 flex flex-col gap-8">

      <div class="text-gray-400 text-sm font-semibold uppercase tracking-widest text-center">normally: run all tests</div>

      <!-- Row: testA / methodA -->
      <div class="flex items-center gap-0">

        <pre class="flex-1 bg-purple-950/60 border border-purple-500/40 rounded-2xl px-5 py-4 text-sm font-mono leading-relaxed m-0"><span class="text-yellow-300">@Test</span> <span class="text-pink-400">void</span> <span class="text-sky-300">testA</span><span class="text-gray-400">() {</span>
  <span class="text-gray-500">// ...</span>
  <span class="text-sky-300">assertEquals</span><span class="text-gray-400">(</span><span class="text-gray-200">x</span><span class="text-gray-400">,</span>
    <span class="text-sky-300">methodA</span><span class="text-gray-400">());</span>
  <span class="text-gray-500">// ...</span>
<span class="text-gray-400">}</span></pre>

        <div class="flex items-center gap-1 px-6">
          <div class="h-0.5 w-12 bg-gray-600"></div>
          <div class="text-gray-500 text-xl">→</div>
        </div>

        <pre class="flex-1 bg-blue-950/60 border border-blue-500/40 rounded-2xl px-5 py-4 text-sm font-mono leading-relaxed m-0"><span class="text-pink-400">int</span> <span class="text-sky-300">methodA</span><span class="text-gray-400">() {</span>
  <span class="text-gray-500">// ...</span>
<span class="text-gray-400">}</span></pre>

      </div>

      <!-- Row: testB / methodB -->
      <div class="flex items-center gap-0">

        <pre class="flex-1 bg-purple-950/40 border border-purple-500/25 rounded-2xl px-5 py-4 text-sm font-mono leading-relaxed m-0 opacity-70"><span class="text-yellow-300">@Test</span> <span class="text-pink-400">void</span> <span class="text-sky-300">testB</span><span class="text-gray-400">() {</span>
  <span class="text-gray-500">// ...</span>
  <span class="text-sky-300">assertEquals</span><span class="text-gray-400">(</span><span class="text-gray-200">y</span><span class="text-gray-400">,</span>
    <span class="text-sky-300">methodB</span><span class="text-gray-400">());</span>
  <span class="text-gray-500">// ...</span>
<span class="text-gray-400">}</span></pre>

        <div class="flex items-center gap-1 px-6">
          <div class="h-0.5 w-12 bg-gray-700/60"></div>
          <div class="text-gray-600 text-xl">→</div>
        </div>

        <pre class="flex-1 bg-blue-950/40 border border-blue-500/25 rounded-2xl px-5 py-4 text-sm font-mono leading-relaxed m-0 opacity-70"><span class="text-pink-400">int</span> <span class="text-sky-300">methodB</span><span class="text-gray-400">() {</span>
  <span class="text-gray-500">// ...</span>
<span class="text-gray-400">}</span></pre>

      </div>

      <div class="text-gray-500 text-base text-center italic">simple · it works · it's fine</div>

    </div>
  </div>
</template>
