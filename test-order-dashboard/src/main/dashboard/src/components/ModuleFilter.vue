<template>
  <div v-if="modules.length > 1" class="module-filter" :class="{ 'module-filter--active': !!modelValue }">
    <div class="module-filter__header">
      <span class="module-filter__label">Module</span>
      <span v-if="modelValue" class="module-filter__active-badge">focus</span>
      <button v-if="modelValue" class="module-filter__clear" @click="$emit('update:modelValue', null)" title="Clear module filter">×</button>
    </div>
    <!-- chip bar for ≤8 modules, dropdown for more -->
    <div v-if="modules.length <= 8" class="module-filter__chips">
      <button
        v-for="mod in modules"
        :key="mod"
        class="module-filter__chip"
        :class="{ 'module-filter__chip--active': modelValue === mod }"
        :title="mod"
        @click="$emit('update:modelValue', modelValue === mod ? null : mod)"
      >{{ shortName(mod) }}</button>
    </div>
    <select v-else class="module-filter__select" :value="modelValue ?? ''" @change="onChange">
      <option value="">All modules ({{ modules.length }})</option>
      <option v-for="mod in modules" :key="mod" :value="mod">{{ shortName(mod) }}</option>
    </select>
    <div v-if="modelValue && count != null" class="module-filter__count">
      {{ count }} test{{ count === 1 ? '' : 's' }} in focus
    </div>
  </div>
</template>

<script setup lang="ts">
const props = defineProps<{
  modules: string[]
  modelValue: string | null
  count?: number | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string | null]
}>()

function shortName(mod: string): string {
  const dot = mod.lastIndexOf('.')
  if (dot < 0) return mod
  const dash = mod.indexOf('-', dot)
  if (dash < 0) return mod
  return mod.substring(dash + 1)
}

function onChange(e: Event) {
  const v = (e.target as HTMLSelectElement).value
  emit('update:modelValue', v || null)
}
</script>

<style scoped>
.module-filter { margin-bottom: 8px; border-radius: 6px; padding: 4px 6px; border: 1px solid transparent; transition: border-color .15s, background .15s; }
.module-filter--active { border-color: rgba(99,102,241,.4); background: rgba(99,102,241,.07); }
.module-filter__header { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; }
.module-filter__label { font-size: 11px; font-weight: 600; color: var(--text-secondary, #888); text-transform: uppercase; letter-spacing: .04em; }
.module-filter__active-badge {
  font-size: .5rem; font-weight: 700; padding: 0 4px; border-radius: 8px;
  background: var(--accent, #6366f1); color: #fff; letter-spacing: .04em; text-transform: uppercase;
}
.module-filter__clear { background: none; border: none; cursor: pointer; color: var(--text-secondary, #888); font-size: 14px; line-height: 1; padding: 0 2px; margin-left: auto; }
.module-filter__clear:hover { color: var(--text-primary, #eee); }
.module-filter__chips { display: flex; flex-wrap: wrap; gap: 4px; }
.module-filter__chip {
  font-size: 11px; padding: 2px 7px; border-radius: 10px; border: 1px solid var(--border, #444);
  background: var(--bg-card, #2a2a2a); color: var(--text-secondary, #aaa);
  cursor: pointer; white-space: nowrap; max-width: 120px; overflow: hidden; text-overflow: ellipsis;
  transition: background .15s, color .15s, border-color .15s;
}
.module-filter__chip:hover { background: var(--accent-dim, #3a3a4a); color: var(--text-primary, #eee); border-color: rgba(99,102,241,.5); }
.module-filter__chip--active { background: var(--accent, #5b8dd9); color: #fff; border-color: var(--accent, #5b8dd9); box-shadow: 0 0 0 2px rgba(99,102,241,.25); }
.module-filter__select {
  width: 100%; font-size: 12px; padding: 4px 6px; border-radius: 4px;
  background: var(--bg-card, #2a2a2a); color: var(--text-primary, #eee);
  border: 1px solid var(--border, #444); cursor: pointer;
}
.module-filter__count { font-size: 11px; color: var(--accent-light, #818cf8); margin-top: 4px; font-weight: 600; }
</style>
