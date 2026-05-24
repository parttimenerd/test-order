<script setup lang="ts">
import type { ClassInfo } from '../composables/useClassInfo'

defineProps<{
  info: ClassInfo | null
  x: number
  y: number
}>()
</script>

<template>
  <Teleport to="body">
    <div v-if="info" class="class-card" :style="{ left: x + 'px', top: y + 'px' }">
      <div class="class-card__name">{{ info.className.split('.').pop() }}</div>
      <div class="class-card__fqn">{{ info.className }}</div>
      <div v-if="info.javadoc" class="class-card__doc">{{ info.javadoc }}</div>
      <div v-if="info.methods && info.methods.length" class="class-card__methods">
        <div class="class-card__methods-head">Public methods</div>
        <div v-for="m in info.methods" :key="m" class="class-card__method">{{ m }}</div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.class-card {
  position: fixed;
  z-index: 9000;
  background: #1e293b;
  border: 1px solid #334155;
  border-radius: 6px;
  padding: 8px 12px;
  min-width: 200px;
  max-width: 380px;
  box-shadow: 0 8px 24px rgba(0,0,0,.7);
  pointer-events: none;
  font-size: .72rem;
}
.class-card__name {
  font-size: .82rem;
  font-weight: 700;
  color: #e2e8f0;
  margin-bottom: 2px;
}
.class-card__fqn {
  font-size: .58rem;
  color: #64748b;
  margin-bottom: 6px;
  word-break: break-all;
}
.class-card__doc {
  color: #94a3b8;
  line-height: 1.45;
  margin-bottom: 6px;
  font-style: italic;
}
.class-card__methods-head {
  font-size: .58rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: .4px;
  color: #6366f1;
  margin-bottom: 4px;
}
.class-card__method {
  font-family: monospace;
  font-size: .65rem;
  color: #7dd3fc;
  line-height: 1.6;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
