<script setup lang="ts">
import { inject, computed } from 'vue'
import type { DashboardState } from '../composables/useDashboard'
import { sn, fmtDur } from '../utils'

const d = inject<DashboardState>('dashboard')!

const apfdColor = computed(() => {
  const v = d.avgApfd.value
  if (v === null) return 'var(--text-dim)'
  return v >= 0.7 ? 'var(--green)' : v >= 0.5 ? 'var(--yellow)' : 'var(--red)'
})
const failColor = computed(() => {
  const r = d.latestRun.value
  return r && r.totalFailures > 0 ? 'var(--red)' : 'var(--green)'
})
</script>

<template>
  <div style="background:var(--bg-card);border-bottom:1px solid var(--border);padding:5px 16px;display:flex;gap:8px;overflow-x:auto;flex-shrink:0">
    <div class="kpi">
      <div class="kpi__label">Avg APFD</div>
      <div class="kpi__value" :style="{ color: apfdColor }">
        {{ d.avgApfd.value !== null ? (d.avgApfd.value * 100).toFixed(1) + '%' : 'N/A' }}
      </div>
    </div>
    <div class="kpi">
      <div class="kpi__label">Latest Failures</div>
      <div class="kpi__value" :style="{ color: failColor }">
        {{ d.latestRun.value ? d.latestRun.value.totalFailures : '—' }}
      </div>
    </div>
    <div class="kpi">
      <div class="kpi__label">Fastest / Slowest</div>
      <div style="display:flex;gap:6px;align-items:baseline">
        <span style="font-size:.72rem;font-weight:600;color:var(--cyan);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:90px" :title="d.fastestTest.value?.name ?? ''">{{ d.fastestTest.value ? sn(d.fastestTest.value.name) : '—' }}</span>
        <span class="kpi__dur">{{ d.fastestTest.value && d.fastestTest.value.duration >= 0 ? fmtDur(d.fastestTest.value.duration) : '' }}</span>
        <span style="color:var(--border);font-size:.65rem">/</span>
        <span style="font-size:.72rem;font-weight:600;color:var(--orange);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:90px" :title="d.slowestTest.value?.name ?? ''">{{ d.slowestTest.value ? sn(d.slowestTest.value.name) : '—' }}</span>
        <span class="kpi__dur">{{ d.slowestTest.value && d.slowestTest.value.duration >= 0 ? fmtDur(d.slowestTest.value.duration) : '' }}</span>
      </div>
    </div>
    <div class="kpi">
      <div class="kpi__label">Changed Tests</div>
      <div class="kpi__value" style="color:var(--yellow)">{{ d.dd.changedTestClasses.length }}</div>
    </div>
  </div>
</template>

<style scoped>
.kpi__label { color: var(--text-dim); font-size: .6rem; margin-bottom: 2px; }
.kpi__value { font-size: 1.1rem; font-weight: 700; }
.kpi__dur { font-size: .6rem; color: var(--text-muted); }
</style>
