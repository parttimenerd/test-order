<script setup lang="ts">
import { inject, computed, ref } from 'vue'
import type { DashboardState } from '../composables/useDashboard'
import { sn } from '../utils'

const d = inject<DashboardState>('dashboard')!

const sa = computed(() => d.dd.staticAnalysis)
const modules = computed(() => sa.value?.modules ?? [])
const total = computed(() => sa.value?.totalUncertainClasses ?? 0)

const searchQ = ref('')
const selectedModule = ref<string | null>(null)

const activeModule = computed(() => {
  if (selectedModule.value) return modules.value.find(m => m.module === selectedModule.value) ?? null
  return modules.value.length === 1 ? modules.value[0] : null
})

const filteredClasses = computed(() => {
  const cls = activeModule.value?.classes ?? []
  if (!searchQ.value.trim()) return cls
  const q = searchQ.value.toLowerCase()
  return cls.filter(c => c.toLowerCase().includes(q))
})
</script>

<template>
  <div v-show="d.activeTab.value === 'staticanalysis'" style="animation:fadeIn .15s ease-out">
    <h3 style="font-size:.82rem;color:var(--text-sec);margin-bottom:10px">Static Analysis — Selective Learn Scope</h3>

    <div v-if="!sa || !sa.enabled" style="color:var(--text-muted);font-size:.8rem;margin-top:12px">
      No selective-learn data available. Run with
      <code>-Dtestorder.learn.selective=true</code> to record the instrumentation scope.
    </div>

    <template v-else>
      <!-- Summary -->
      <div style="display:flex;gap:12px;flex-wrap:wrap;margin-bottom:16px">
        <div class="sa-card sa-card--total">
          <div class="sa-card__value">{{ total }}</div>
          <div class="sa-card__label">Uncertain classes</div>
        </div>
        <div class="sa-card">
          <div class="sa-card__value">{{ modules.length }}</div>
          <div class="sa-card__label">Module{{ modules.length !== 1 ? 's' : '' }}</div>
        </div>
      </div>

      <!-- Module selector (only shown for multi-module builds) -->
      <div v-if="modules.length > 1" style="margin-bottom:12px;display:flex;gap:8px;flex-wrap:wrap">
        <button
          v-for="m in modules"
          :key="m.module"
          class="mod-btn"
          :class="{ 'mod-btn--active': selectedModule === m.module }"
          @click="selectedModule = selectedModule === m.module ? null : m.module"
        >
          {{ m.module }} <span class="mod-btn__badge">{{ m.count }}</span>
        </button>
      </div>

      <!-- Class list -->
      <div v-if="activeModule" style="margin-top:4px">
        <div style="display:flex;align-items:center;gap:8px;margin-bottom:8px">
          <input
            v-model="searchQ"
            class="sa-search"
            placeholder="Filter classes…"
            type="search"
          />
          <span style="font-size:.72rem;color:var(--text-muted);flex-shrink:0">
            {{ filteredClasses.length }} / {{ activeModule.count }}
          </span>
        </div>
        <div style="overflow-y:auto;max-height:480px">
          <div
            v-for="cls in filteredClasses"
            :key="cls"
            class="cls-row"
            :title="cls"
          >
            <span class="cls-pkg">{{ cls.includes('.') ? cls.slice(0, cls.lastIndexOf('.') + 1) : '' }}</span>
            <span class="cls-name">{{ cls.includes('.') ? cls.slice(cls.lastIndexOf('.') + 1) : cls }}</span>
          </div>
          <div v-if="!filteredClasses.length" style="color:var(--text-muted);font-size:.8rem;padding:8px 0">
            No classes match the filter.
          </div>
        </div>
      </div>

      <div
        v-else-if="modules.length > 1"
        style="color:var(--text-muted);font-size:.8rem;margin-top:8px"
      >
        Select a module above to browse its uncertain classes.
      </div>

      <p style="color:var(--text-muted);font-size:.75rem;margin-top:16px;line-height:1.5">
        These are the classes the static call-graph analysis identified as reachable from the current
        changes (changed classes + transitive callees, up to 4 hops). Only these classes were
        instrumented during the last selective-learn run.
      </p>
    </template>
  </div>
</template>

<style scoped>
.sa-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 10px 16px;
  min-width: 80px;
  text-align: center;
}
.sa-card__value { font-size: 1.4rem; font-weight: 700; color: var(--text); }
.sa-card__label { font-size: .65rem; color: var(--text-muted); text-transform: uppercase; letter-spacing: .5px; }
.sa-card--total .sa-card__value { color: var(--accent, #6366f1); }

.mod-btn {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 4px 10px;
  font-size: .72rem;
  color: var(--text-sec);
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
  transition: border-color .15s;
}
.mod-btn:hover { border-color: var(--accent, #6366f1); }
.mod-btn--active { border-color: var(--accent, #6366f1); color: var(--accent, #6366f1); background: color-mix(in srgb, var(--accent, #6366f1) 10%, transparent); }
.mod-btn__badge {
  background: var(--border);
  border-radius: 10px;
  padding: 1px 6px;
  font-size: .65rem;
}

.sa-search {
  flex: 1;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 4px 10px;
  font-size: .8rem;
  color: var(--text);
  outline: none;
  min-width: 0;
}
.sa-search:focus { border-color: var(--accent, #6366f1); }

.cls-row {
  display: flex;
  align-items: baseline;
  gap: 0;
  padding: 2px 4px;
  border-radius: 4px;
  font-size: .78rem;
  line-height: 1.6;
}
.cls-row:hover { background: var(--bg-card); }
.cls-pkg { color: var(--text-muted); }
.cls-name { color: var(--text); font-weight: 500; }
</style>
