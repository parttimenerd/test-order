<script setup lang="ts">
defineProps<{
  parseError: string | null
}>()
</script>

<template>
  <div style="flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:12px">
    <div style="font-size:3rem">📊</div>
    <h2 style="margin:0;color:var(--text-sec);font-size:1.2rem">No test data yet</h2>
    <p style="margin:0;color:var(--text-muted);font-size:.85rem">Run your Maven build with the test-order plugin to generate data.</p>
    <p v-if="parseError" style="margin:0;color:var(--red);font-size:.75rem;font-family:monospace">{{ parseError }}</p>
  </div>
</template>
