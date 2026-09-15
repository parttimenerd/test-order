<template>
  <div class="demo-card">
    <div class="header">
      <span class="badge">DEMO {{ id }}</span>
      <span class="timing">≈ {{ duration }}</span>
    </div>
    <h2 class="title"><slot name="title" /></h2>
    <pre class="cmd">{{ cmd }}</pre>
    <div class="watch"><slot name="watch" /></div>
  </div>
</template>

<script setup>
defineProps({
  id: { type: String, required: true },
  duration: { type: String, required: true },
  cmd: { type: String, required: true },
});
</script>

<style scoped>
.demo-card {
  border: 2px solid #3b82f6;
  border-radius: 14px;
  padding: 1.6rem 2rem;
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.06), rgba(139, 92, 246, 0.04));
  font-family: 'Inter', sans-serif;
}
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.6rem;
}
.badge {
  background: #3b82f6;
  color: white;
  padding: 0.2rem 0.7rem;
  border-radius: 6px;
  font-size: 0.85rem;
  font-weight: 700;
  letter-spacing: 0.04em;
}
.timing {
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.95rem;
  color: #64748b;
}
.title {
  font-size: 2.0rem;
  margin: 0.4rem 0 1rem;
  color: #0f172a;
}
.cmd {
  background: #0f172a;
  color: #e2e8f0;
  padding: 1.1rem 1.4rem;
  border-radius: 8px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 1.2rem;
  line-height: 1.55;
  margin: 0 0 0.9rem 0;
  white-space: pre-wrap;
}
.watch {
  font-size: 1.15rem;
  color: #475569;
  font-style: italic;
}
</style>
