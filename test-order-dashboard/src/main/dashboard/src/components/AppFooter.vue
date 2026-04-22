<script setup lang="ts">
import { inject } from 'vue'
import type { DashboardState } from '../composables/useDashboard'

const d = inject<DashboardState>('dashboard')!
</script>

<template>
  <footer style="background:var(--bg-card);border-top:1px solid var(--border);padding:4px 16px;font-size:.68rem;color:var(--text-muted);display:flex;gap:10px;flex-shrink:0;overflow:hidden">
    <span>v{{ d.dd.project.pluginVersion }}</span>
    <span>·</span>
    <span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ d.dd.project.stateFilePath }}</span>
    <span class="app-footer__fixed">·</span>
    <span class="app-footer__fixed">{{ d.dd.project.generated }}</span>
    <span class="app-footer__fixed">·</span>
    <span class="app-footer__fixed">{{ d.runs.length }} runs</span>
  </footer>
</template>

<style scoped>
.app-footer__fixed { flex-shrink: 0; }
</style>
