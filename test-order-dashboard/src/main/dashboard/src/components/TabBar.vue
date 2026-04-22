<script setup lang="ts">
import { inject } from 'vue'
import type { DashboardState } from '../composables/useDashboard'

const d = inject<DashboardState>('dashboard')!
</script>

<template>
  <nav style="background:var(--bg-card);border-bottom:1px solid var(--border);display:flex;overflow-x:auto;flex-shrink:0;align-items:center">
    <button
      v-for="tab in d.TABS.value"
      :key="tab.id"
      class="tab-btn"
      :class="{ active: d.activeTab.value === tab.id }"
      @click="d.setTab(tab.id)"
    >{{ tab.label }}</button>
    <span v-if="!d.hasCoverage" style="font-size:.65rem;color:var(--text-muted);margin-left:auto;margin-right:12px;cursor:help;border-bottom:1px dotted var(--text-muted)" title="Coverage data requires dependency data from the test-order agent. Run tests with instrumentation to collect it.">coverage N/A</span>
  </nav>
</template>
