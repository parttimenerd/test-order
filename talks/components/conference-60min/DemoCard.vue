<template>
  <div class="demo-card">
    <div class="header">
      <span class="badge">DEMO {{ id }}</span>
    </div>
    <h2 class="title">{{ title }}</h2>
    <div class="cmd"><slot /></div>
  </div>
</template>

<script setup>
defineProps({
  id: { type: String, required: true },
  title: { type: String, default: '' },
})
</script>

<style scoped>
.demo-card {
  border: 2px solid #3b82f6;
  border-radius: 14px;
  padding: 1.6rem 2rem;
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.10), rgba(139, 92, 246, 0.07));
  font-family: 'Inter', sans-serif;
}
.header {
  display: flex;
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
.title {
  font-size: 1.8rem;
  margin: 0.4rem 0 1rem;
  color: #67e8f9;
  font-weight: 700;
}
.cmd {
  border-radius: 8px;
  font-size: 1.5rem;
  line-height: 1.6;
  margin: 0;
  overflow: hidden;
}
.cmd :deep(pre) {
  background: #0f172a !important;
  margin: 0;
  padding: 1.4rem 1.8rem;
  border-radius: 8px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 1.35rem !important;
  line-height: 1.6 !important;
  white-space: pre;
  overflow-x: auto;
}
.cmd :deep(code) {
  font-family: 'JetBrains Mono', monospace;
  font-size: 1.35rem !important;
}

/* light-mode overrides handled in non-scoped block below */
</style>

<style>
html:not(.dark) .demo-card .title {
  color: #0369a1 !important;
}
html:not(.dark) .demo-card .cmd pre {
  background: #f8fafc !important;
  border: 1px solid rgba(0,0,0,0.10) !important;
}
</style>
