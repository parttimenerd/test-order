<script setup lang="ts">
import { computed, inject } from 'vue'
import type { DashboardState } from '../composables/useDashboard'
import { sn } from '../utils'

const d = inject<DashboardState>('dashboard')!
const h = computed(() => d.suiteHealthBreakdown.value)

const components = computed(() => {
  if (!h.value) return []
  return [
    { label: 'APFD',        value: h.value.apfdScore,   weight: 30, color: 'var(--accent)' },
    { label: 'Reliability', value: h.value.relScore,    weight: 30, color: 'var(--green)' },
    { label: 'Flakiness',   value: h.value.flakyScore,  weight: 20, color: 'var(--yellow)' },
    { label: 'Coverage',    value: h.value.covScore,    weight: 20, color: 'var(--cyan)' },
  ]
})

const recommendations = computed(() => {
  if (!h.value) return []
  const recs: string[] = []
  if (h.value.flakyList.length > 0)
    recs.push(`Consider quarantining ${h.value.flakyList.length} flaky test${h.value.flakyList.length > 1 ? 's' : ''}`)
  if (h.value.apfdScore < 70)
    recs.push(`APFD ${h.value.apfdScore}% is low — review weight tuning in Weights tab`)
  if (h.value.covScore < 60)
    recs.push(`Coverage ${h.value.covScore}% — consider adding instrumentation to uncovered packages`)
  if (h.value.methodCovPct !== null && h.value.methodCovPct < 50)
    recs.push(`Only ${h.value.methodCovPct}% of tracked methods exercised — check for dead code or missing tests`)
  if (h.value.relScore < 80)
    recs.push(`${h.value.failedOnce.length} test${h.value.failedOnce.length !== 1 ? 's' : ''} have failed at least once — check their stability`)
  return recs
})
</script>

<template>
  <div v-if="h" class="shc">
    <!-- Header: grade + composite score -->
    <div class="shc__header">
      <div class="shc__grade" :style="{ color: h.color }">{{ h.grade }}</div>
      <div class="shc__score-wrap">
        <div class="shc__score">{{ h.composite }}<span class="shc__score-denom">/100</span></div>
        <div class="shc__title">Suite Health Score</div>
      </div>
    </div>

    <!-- Component bars -->
    <div class="shc__bars">
      <div v-for="c in components" :key="c.label" class="shc__bar-row">
        <span class="shc__bar-label">{{ c.label }}</span>
        <div class="shc__bar-track">
          <div class="shc__bar-fill" :style="{ width: c.value + '%', background: c.color }"></div>
        </div>
        <span class="shc__bar-pct" :style="{ color: c.value >= 80 ? 'var(--green)' : c.value >= 60 ? 'var(--yellow)' : 'var(--red)' }">{{ c.value }}%</span>
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
        <span class="shc__rec-bullet">•</span> {{ rec }}
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
  flex: 1; height: 6px; background: var(--border); border-radius: 3px; overflow: hidden;
}
.shc__bar-fill { height: 100%; border-radius: 3px; transition: width .3s; }
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
.shc__rec { font-size: .68rem; color: var(--text-sec); padding: 2px 0; display: flex; gap: 5px; }
.shc__rec-bullet { color: var(--accent-light); flex-shrink: 0; }
</style>
