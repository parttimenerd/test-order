<script setup lang="ts">
import { inject } from 'vue'
import type { DashboardState } from '../composables/useDashboard'
import { sn, fmtDur } from '../utils'

const d = inject<DashboardState>('dashboard')!
</script>

<template>
  <header style="background:var(--bg-card);border-bottom:1px solid var(--border);padding:6px 16px;display:flex;align-items:center;gap:12px;flex-shrink:0;flex-wrap:wrap">
    <span style="font-weight:700;font-size:1rem;color:var(--accent-light)">{{ d.dd.project.name }}</span>
    <span style="color:var(--border)">│</span>
    <span class="app-header__stat">{{ d.tests.length }} tests</span>
    <span
      v-if="d.dd.changedClasses.length"
      style="color:var(--yellow);font-size:.78rem;cursor:pointer"
      @click="d.showChangedPanel.value = !d.showChangedPanel.value"
      :title="'Click to ' + (d.showChangedPanel.value ? 'hide' : 'show') + ' changed classes'"
    >{{ d.dd.changedClasses.length }} changed ▾</span>
    <span class="app-header__stat">median {{ fmtDur(d.dd.medianDuration) }}</span>
    <span
      style="color:var(--text-muted);font-size:.68rem;margin-left:auto;cursor:help"
      :title="'Index: ' + d.dd.project.indexFilePath + '\nState: ' + d.dd.project.stateFilePath + '\nGenerated: ' + d.dd.project.generated"
    >ℹ️ v{{ d.dd.project.pluginVersion }}</span>
  </header>

  <!-- Changed classes expandable panel -->
  <div v-if="d.showChangedPanel.value && d.dd.changedClasses.length" style="background:var(--bg-card);border-bottom:1px solid var(--border);padding:5px 16px;max-height:120px;overflow-y:auto">
    <div style="display:flex;flex-wrap:wrap;gap:4px">
      <span v-for="c in d.dd.changedClasses" :key="c" class="changed-panel__tag">{{ sn(c) }}</span>
    </div>
    <div v-if="d.dd.changedTestClasses.length" style="margin-top:4px;display:flex;flex-wrap:wrap;gap:4px">
      <span style="font-size:.6rem;color:var(--text-dim);margin-right:4px">Test classes:</span>
      <span v-for="c in d.dd.changedTestClasses" :key="c" class="changed-panel__tag changed-panel__tag--test">{{ sn(c) }}</span>
    </div>
  </div>
</template>

<style scoped>
.app-header__stat { color: var(--text-sec); font-size: .78rem; }
.changed-panel__tag {
  font-size: .65rem;
  padding: 1px 6px;
  background: rgba(234, 179, 8, .12);
  color: var(--yellow);
  border-radius: 3px;
  white-space: nowrap;
}
.changed-panel__tag--test { background: rgba(234, 179, 8, .25); }
</style>
