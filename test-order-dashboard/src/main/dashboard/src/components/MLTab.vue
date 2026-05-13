<script setup lang="ts">
import { inject, computed } from 'vue'
import type { DashboardState } from '../composables/useDashboard'
import { sn } from '../utils'

const d = inject<DashboardState>('dashboard')!

const ml = computed(() => d.dd.ml)
const tests = computed(() => ml.value?.tests ?? [])
const summary = computed(() => ml.value?.summary ?? { healthy: 0, degrading: 0, flaky: 0, failing: 0 })

function statusColor(status: string): string {
  switch (status) {
    case 'HEALTHY': return 'var(--green, #22c55e)'
    case 'DEGRADING': return 'var(--yellow, orange)'
    case 'FLAKY': return 'var(--yellow, orange)'
    case 'FAILING': return 'var(--red, #ef4444)'
    default: return 'var(--text-muted)'
  }
}
</script>

<template>
  <div v-show="d.activeTab.value === 'ml'" style="animation:fadeIn .15s ease-out">
    <h3 style="font-size:.82rem;color:var(--text-sec);margin-bottom:10px">ML Health Analysis</h3>

    <!-- Summary cards -->
    <div style="display:flex;gap:12px;flex-wrap:wrap;margin-bottom:16px">
      <div class="ml-card ml-card--healthy">
        <div class="ml-card__value">{{ summary.healthy }}</div>
        <div class="ml-card__label">Healthy</div>
      </div>
      <div class="ml-card ml-card--degrading">
        <div class="ml-card__value">{{ summary.degrading }}</div>
        <div class="ml-card__label">Degrading</div>
      </div>
      <div class="ml-card ml-card--flaky">
        <div class="ml-card__value">{{ summary.flaky }}</div>
        <div class="ml-card__label">Flaky</div>
      </div>
      <div class="ml-card ml-card--failing">
        <div class="ml-card__value">{{ summary.failing }}</div>
        <div class="ml-card__label">Failing</div>
      </div>
      <div class="ml-card">
        <div class="ml-card__value">{{ ml?.runsAnalyzed ?? 0 }}</div>
        <div class="ml-card__label">Runs analyzed</div>
      </div>
    </div>

    <!-- Per-test health table -->
    <div style="overflow-x:auto;max-height:500px;overflow-y:auto">
      <table>
        <thead class="tests-overview__thead">
          <tr>
            <th class="th--left">Test</th>
            <th class="th--left">Status</th>
            <th class="th--right" title="Failure rate across analyzed runs">Fail rate</th>
            <th class="th--left" title="Recent trend direction">Trend</th>
            <th class="th--right">Runs</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in tests" :key="t.testClass" class="tests-overview__row">
            <td class="td--name" :title="t.testClass">{{ sn(t.testClass) }}</td>
            <td :style="{ color: statusColor(t.status), fontWeight: 600, fontSize: '.75rem' }">{{ t.status }}</td>
            <td class="td--right" :style="{ color: t.failRate > 0.5 ? 'var(--red)' : t.failRate > 0.2 ? 'var(--yellow, orange)' : 'var(--text-muted)' }">{{ (t.failRate * 100).toFixed(1) }}%</td>
            <td style="font-size:.75rem">{{ t.recentTrend }}</td>
            <td class="td--right td--dim">{{ t.runsAnalyzed }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <p v-if="!tests.length" style="color:var(--text-muted);font-size:.8rem;margin-top:12px">
      No ML health data available. Run tests with ML history enabled to collect data.
    </p>
  </div>
</template>

<style scoped>
.ml-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 10px 16px;
  min-width: 80px;
  text-align: center;
}
.ml-card__value { font-size: 1.4rem; font-weight: 700; color: var(--text); }
.ml-card__label { font-size: .65rem; color: var(--text-muted); text-transform: uppercase; letter-spacing: .5px; }
.ml-card--healthy .ml-card__value { color: var(--green, #22c55e); }
.ml-card--degrading .ml-card__value { color: var(--yellow, orange); }
.ml-card--flaky .ml-card__value { color: var(--yellow, orange); }
.ml-card--failing .ml-card__value { color: var(--red, #ef4444); }
</style>
