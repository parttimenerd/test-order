<script setup lang="ts">
import { computed, inject } from 'vue'
import type { DashboardState } from '../composables/useDashboard'
import { sn } from '../utils'

const d = inject<DashboardState>('dashboard')!
const h = computed(() => d.suiteHealthBreakdown.value)

const components = computed(() => {
  if (!h.value) return []
  return [
    { label: 'APFD',        value: h.value.apfdScore,   weight: 30, color: 'var(--accent)',  naText: h.value.hasApfd ? null : 'N/A',
      tip: 'APFD (Average Percentage of Faults Detected): measures how early failing tests appear in the ordered list. 100% = all failures detected first; 50% = random. Contributes 30% to Suite Health.' },
    { label: 'Reliability', value: h.value.relScore,    weight: 30, color: 'var(--green)',   naText: null,
      tip: 'Reliability: % of tests that have never failed across all recorded runs. Lower = more tests have failure history. Contributes 30% to Suite Health.' },
    { label: 'Stability',   value: h.value.flakyScore,  weight: 20, color: 'var(--yellow)',  naText: null,
      tip: 'Stability (flakiness inverse): 100% = no flaky tests, 0% = most tests flaky. Formula: max(0, 100 − flakyPct × 3). ' + h.value.flakyList.length + ' flaky test' + (h.value.flakyList.length !== 1 ? 's' : '') + ' → score ' + h.value.flakyScore + '%. Contributes 20% to Suite Health.' },
    { label: 'Coverage',    value: h.value.covScore,    weight: 20, color: 'var(--cyan)',    naText: null,
      tip: 'Coverage: % of tracked source classes exercised by at least one test. If no instrumentation data, estimated at 50%. Contributes 20% to Suite Health.' },
  ]
})

interface Rec { msg: string; action?: () => void; actionLabel?: string }
const recommendations = computed((): Rec[] => {
  if (!h.value) return []
  const recs: Rec[] = []
  if (h.value.flakyList.length > 0)
    recs.push({ msg: `Consider quarantining ${h.value.flakyList.length} flaky test${h.value.flakyList.length > 1 ? 's' : ''}`, action: () => { d.setBadgeFilter('flaky'); d.setTab('tests') }, actionLabel: 'Filter →' })
  if (h.value.hasApfd && h.value.apfdScore < 70)
    recs.push({ msg: `APFD ${h.value.apfdScore}% is low — review weight tuning in Weights tab`, action: () => d.setTab('weights'), actionLabel: 'Weights →' })
  if (h.value.covScore < 60)
    recs.push({ msg: `Coverage ${h.value.covScore}% — consider adding instrumentation to uncovered packages`, action: () => { d.setTab('analytics'); setTimeout(() => document.getElementById('analytics-coverage')?.scrollIntoView({ behavior: 'smooth', block: 'start' }), 80) }, actionLabel: 'Coverage →' })
  if (h.value.methodCovPct !== null && h.value.methodCovPct < 50)
    recs.push({ msg: `Only ${h.value.methodCovPct}% of tracked methods exercised — check for dead code or missing tests` })
  if (h.value.relScore < 80)
    recs.push({ msg: `${h.value.failedOnce.length} test${h.value.failedOnce.length !== 1 ? 's' : ''} have failed at least once — check their stability`, action: () => { d.setBadgeFilter('failing'); d.setTab('tests') }, actionLabel: 'Filter →' })
  return recs
})
</script>

<template>
  <div v-if="h" class="shc">
    <!-- Header: grade + composite score -->
    <div class="shc__header" :title="'Suite Health Score: ' + h.composite + '/100 (Grade ' + h.grade + ')\nA→F composite of APFD (30%), Reliability (30%), Stability (20%), and Coverage (20%).\nHover each bar for details on how that component is calculated.'">
      <div class="shc__grade" :style="{ color: h.color }">{{ h.grade }}</div>
      <div class="shc__score-wrap">
        <div class="shc__score">{{ h.composite }}<span class="shc__score-denom">/100</span></div>
        <div class="shc__title">Suite Health Score</div>
      </div>
    </div>

    <!-- Component bars -->
    <div class="shc__bars">
      <div v-for="c in components" :key="c.label" class="shc__bar-row" :title="c.tip">
        <span class="shc__bar-label">{{ c.label }}</span>
        <div class="shc__bar-track">
          <div v-if="!c.naText" class="shc__bar-fill" :style="{ width: Math.max(c.value, c.value === 0 ? 0 : 2) + '%', background: c.color, opacity: c.value === 0 ? 0.35 : 1 }"></div>
          <div v-if="!c.naText && c.value === 0" class="shc__bar-zero" :style="{ background: 'var(--red)' }"></div>
        </div>
        <span v-if="c.naText" class="shc__bar-pct" style="color:var(--text-muted)">{{ c.naText }}</span>
        <span v-else class="shc__bar-pct" :style="{ color: c.value >= 80 ? 'var(--green)' : c.value >= 60 ? 'var(--yellow)' : 'var(--red)' }">{{ c.value }}%</span>
        <span class="shc__bar-weight">×{{ c.weight / 100 }}</span>
      </div>
    </div>

    <!-- Flags/insights -->
    <div v-if="h.flakyList.length || h.failedOnce.length || h.methodCovPct !== null" class="shc__insights">
      <div v-if="h.flakyList.length" class="shc__insight">
        <span class="shc__insight-icon" style="color:var(--yellow)">⚡</span>
        <span>{{ h.flakyList.length }} flaky: </span>
        <span
          v-for="name in h.flakyList.slice(0, 3)"
          :key="name"
          class="shc__test-link"
          @click="d.navigateToTestFromCov(name)"
        >{{ sn(name) }}</span>
        <span v-if="h.flakyList.length > 3" class="shc__insight-more"> +{{ h.flakyList.length - 3 }}</span>
      </div>
      <div v-if="h.failedOnce.length" class="shc__insight">
        <span class="shc__insight-icon" style="color:var(--red)">✗</span>
        <span>{{ h.failedOnce.length }} test{{ h.failedOnce.length !== 1 ? 's' : '' }} failed in history</span>
      </div>
      <div v-if="h.methodCovPct !== null" class="shc__insight">
        <span class="shc__insight-icon" style="color:var(--cyan)">⬡</span>
        <span>Method coverage: {{ h.methodCovPct }}%</span>
      </div>
    </div>

    <!-- Recommendations -->
    <div v-if="recommendations.length" class="shc__recs">
      <div class="shc__recs-title">Recommendations</div>
      <div v-for="(rec, i) in recommendations" :key="i" class="shc__rec">
        <span class="shc__rec-bullet">•</span>
        <span>{{ rec.msg }}</span>
        <button v-if="rec.action" class="shc__rec-action" @click="rec.action!()">{{ rec.actionLabel }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.shc {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 14px 16px;
  margin-bottom: 12px;
  font-size: .74rem;
  color: var(--text);
}

.shc__header {
  display: flex; align-items: center; gap: 14px; margin-bottom: 12px;
}
.shc__grade {
  font-size: 2.4rem; font-weight: 900; line-height: 1;
  text-shadow: 0 0 20px currentColor; flex-shrink: 0;
}
.shc__score {
  font-size: 1.4rem; font-weight: 800; color: var(--text);
}
.shc__score-denom { font-size: .7rem; color: var(--text-muted); }
.shc__title { font-size: .6rem; text-transform: uppercase; letter-spacing: .5px; color: var(--text-muted); margin-top: 2px; }

.shc__bars { display: flex; flex-direction: column; gap: 5px; margin-bottom: 10px; }
.shc__bar-row { display: flex; align-items: center; gap: 6px; }
.shc__bar-label { width: 72px; color: var(--text-sec); font-size: .67rem; flex-shrink: 0; }
.shc__bar-track {
  flex: 1; height: 6px; background: var(--border); border-radius: 3px; overflow: hidden; position: relative;
}
.shc__bar-fill { height: 100%; border-radius: 3px; transition: width .3s; }
.shc__bar-zero { position: absolute; left: 0; top: 0; width: 3px; height: 100%; border-radius: 3px; opacity: .7; }
.shc__bar-pct { width: 34px; text-align: right; font-size: .67rem; font-weight: 700; flex-shrink: 0; }
.shc__bar-weight { width: 30px; color: var(--text-muted); font-size: .6rem; flex-shrink: 0; }

.shc__insights { display: flex; flex-direction: column; gap: 3px; margin-bottom: 10px; }
.shc__insight { display: flex; align-items: center; gap: 4px; font-size: .68rem; color: var(--text-sec); flex-wrap: wrap; }
.shc__insight-icon { font-size: .7rem; flex-shrink: 0; }
.shc__test-link {
  color: var(--accent-light); cursor: pointer; text-decoration: underline dotted;
  font-size: .65rem;
}
.shc__test-link:hover { color: var(--text); }
.shc__test-link:not(:last-of-type)::after { content: ', '; color: var(--text-muted); }
.shc__insight-more { color: var(--text-muted); font-size: .62rem; }

.shc__recs { border-top: 1px solid var(--border); padding-top: 8px; }
.shc__recs-title { font-size: .62rem; text-transform: uppercase; letter-spacing: .4px; color: var(--text-muted); margin-bottom: 4px; }
.shc__rec { font-size: .68rem; color: var(--text-sec); padding: 2px 0; display: flex; align-items: center; gap: 5px; }
.shc__rec-bullet { color: var(--accent-light); flex-shrink: 0; }
.shc__rec-action {
  margin-left: auto; flex-shrink: 0;
  font-size: .58rem; padding: 1px 6px; border-radius: 8px;
  border: 1px solid rgba(99,102,241,.35); background: none; color: var(--accent-light);
  cursor: pointer; transition: all .12s;
}
.shc__rec-action:hover { background: var(--accent-bg); border-color: var(--accent); }
</style>
