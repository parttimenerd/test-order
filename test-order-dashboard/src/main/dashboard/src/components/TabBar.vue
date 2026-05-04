<script setup lang="ts">
import { inject } from 'vue'
import type { DashboardState } from '../composables/useDashboard'

const d = inject<DashboardState>('dashboard')!
</script>

<template>
  <nav style="background:var(--bg-card);border-bottom:1px solid var(--border);display:flex;overflow-x:auto;flex-shrink:0;align-items:center">
    <button
      v-for="(tab, i) in d.TABS.value"
      :key="tab.id"
      class="tab-btn"
      :class="{ active: d.activeTab.value === tab.id }"
      @click="d.setTab(tab.id)"
    ><span class="tab-btn__num">{{ i + 1 }}</span>{{ tab.label }}</button>
    <span v-if="!d.hasCoverage" style="font-size:.65rem;color:var(--text-muted);margin-left:auto;margin-right:12px;cursor:help;border-bottom:1px dotted var(--text-muted)" title="Coverage data requires dependency data from the test-order agent. Run tests with instrumentation to collect it.">coverage N/A</span>
  </nav>
</template>

<style scoped>
.tab-btn__num {
  display: inline-flex; align-items: center; justify-content: center;
  width: 14px; height: 14px; border-radius: 3px; font-size: .55rem;
  background: rgba(71, 85, 105, .3); color: var(--text-muted);
  margin-right: 4px; font-weight: 700; vertical-align: middle;
}
.tab-btn.active .tab-btn__num { background: rgba(99, 102, 241, .25); color: var(--accent-light); }
</style>
