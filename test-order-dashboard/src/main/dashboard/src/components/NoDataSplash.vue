<script setup lang="ts">
</script>

<template>
  <div style="flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:16px;padding:40px">
    <div style="font-size:3.5rem;opacity:.85">📊</div>
    <h2 style="margin:0;color:var(--text-sec);font-size:1.2rem">No test data yet</h2>
    <p style="margin:0;color:var(--text-muted);font-size:.85rem;text-align:center;max-width:420px;line-height:1.6">
      Run your Maven build with the test-order plugin to generate data.
      The dashboard will populate once the plugin writes its state file.
    </p>
    <div style="margin-top:8px;background:var(--bg-card);border:1px solid var(--border);border-radius:6px;padding:12px 18px;font-family:monospace;font-size:.78rem;color:var(--text-dim);max-width:460px;text-align:left;line-height:1.6">
      <div style="color:var(--text-muted);font-size:.65rem;margin-bottom:4px">Quick start:</div>
      <code style="color:var(--cyan)">mvn test-order:combined test</code>
    </div>
  </div>
</template>
