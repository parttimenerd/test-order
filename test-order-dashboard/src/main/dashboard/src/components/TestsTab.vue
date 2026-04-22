<script setup lang="ts">
import { inject, watch, nextTick, onMounted } from 'vue'
import type { DashboardState } from '../composables/useDashboard'
import type { TestEntry } from '../types'
import { sn, fmtDur, fmtTime, computeScore } from '../utils'
import { mkChart, destroyCharts, chartOpts } from '../composables/useCharts'
import TestBadges from './TestBadges.vue'
import DepGraph from './DepGraph.vue'

const d = inject<DashboardState>('dashboard')!

function initDetailCharts(t: TestEntry) {
  destroyCharts('bd-main', 'hd-main', 'hs-main', 'hp-main')
  const comps = d.scoreComps.value.filter(c => c.value !== 0)
  mkChart('bd-main', {
    type: 'bar',
    data: { labels: [''], datasets: comps.map(c => ({ label: c.label, data: [c.value], backgroundColor: c.color, stack: 's' })) },
    options: {
      indexAxis: 'y' as const, responsive: true, maintainAspectRatio: false, animation: { duration: 150 },
      plugins: { legend: { display: true, position: 'bottom' as const, labels: { color: '#64748b', font: { size: 9 }, boxWidth: 10, padding: 6 } }, tooltip: { backgroundColor: '#1e293b', borderColor: '#475569', borderWidth: 1, titleColor: '#e2e8f0', bodyColor: '#94a3b8' } },
      scales: { x: { stacked: true, ticks: { color: '#475569', font: { size: 9 } }, grid: { color: 'rgba(71,85,105,.25)' } }, y: { stacked: true, display: false } },
    },
  })
  if (d.runs.length) {
    const labels = d.runs.map(r => fmtTime(r.timestamp))
    const ema = t.duration >= 0 ? t.duration : null
    mkChart('hd-main', {
      type: 'line', data: { labels, datasets: [
        { label: 'EMA', data: labels.map(() => ema), borderColor: '#818cf8', borderDash: [5, 4], borderWidth: 1.5, pointRadius: 0, tension: 0, spanGaps: true },
      ] }, options: chartOpts(),
    })
  }
  if (d.runs.length > 1) {
    const labels = d.runs.map(r => fmtTime(r.timestamp))
    const scores = d.runs.map(r => {
      const o = (r.outcomes || []).find(o => o.testClass === t.name)
      return o ? computeScore(o, d.dd.weights, d.origSCB) : null
    })
    mkChart('hs-main', {
      type: 'line', data: { labels, datasets: [
        { label: 'Score', data: scores, borderColor: '#6366f1', backgroundColor: 'rgba(99,102,241,.1)', fill: true, tension: 0.3, pointRadius: 3, pointHoverRadius: 5, spanGaps: true },
      ] }, options: chartOpts(),
    })
    const positions = d.runs.map(r => {
      if (!r.outcomes?.length) return null
      const sorted = [...r.outcomes].sort((a, b) => computeScore(b, d.dd.weights, d.origSCB) - computeScore(a, d.dd.weights, d.origSCB))
      const idx = sorted.findIndex(o => o.testClass === t.name)
      return idx >= 0 ? idx + 1 : null
    })
    const co = chartOpts() as Record<string, Record<string, unknown>>
    mkChart('hp-main', {
      type: 'line', data: { labels, datasets: [
        { label: 'Position', data: positions, borderColor: '#f59e0b', backgroundColor: 'rgba(245,158,11,.1)', fill: true, tension: 0.3, pointRadius: 3, pointHoverRadius: 5, spanGaps: true },
      ] }, options: { ...chartOpts(), scales: { ...(co.scales || {}), y: { ...((co.scales?.y || {}) as Record<string, unknown>), reverse: true } } },
    })
  }
}

watch(() => d.selectedTest.value, (newVal) => {
  if (d.activeTab.value === 'tests' && newVal && d.selectedTests.value.size <= 1) {
    nextTick(() => initDetailCharts(newVal))
  }
})
watch(() => d.lw, () => {
  if (d.selectedTest.value && d.activeTab.value === 'tests') {
    nextTick(() => initDetailCharts(d.selectedTest.value!))
  }
}, { deep: true })
</script>

<template>
  <div v-if="d.activeTab.value === 'tests'">

    <!-- No selection: overview -->
    <div v-if="d.selectedTests.value.size === 0">
      <div style="display:flex;flex-wrap:wrap;gap:8px;margin-bottom:12px">
        <div class="kpi tests-overview__kpi">
          <div class="tests-overview__kpi-label">Total Tests</div>
          <div class="tests-overview__kpi-value" style="color:var(--accent-light)">{{ d.tests.length }}</div>
        </div>
        <div class="kpi tests-overview__kpi tests-overview__kpi--filter" :class="{ 'tests-overview__kpi--active': d.badgeFilter.value === 'changed' }" @click="d.setBadgeFilter('changed')" title="Click to filter by changed tests">
          <div class="tests-overview__kpi-label">Changed</div>
          <div class="tests-overview__kpi-value" style="color:var(--yellow)">{{ d.tests.filter(t => t.isChanged).length }}</div>
        </div>
        <div class="kpi tests-overview__kpi tests-overview__kpi--filter" :class="{ 'tests-overview__kpi--active': d.badgeFilter.value === 'new' }" @click="d.setBadgeFilter('new')" title="Click to filter by new tests">
          <div class="tests-overview__kpi-label">New</div>
          <div class="tests-overview__kpi-value" style="color:var(--green)">{{ d.tests.filter(t => t.isNew).length }}</div>
        </div>
        <div class="kpi tests-overview__kpi tests-overview__kpi--filter" :class="{ 'tests-overview__kpi--active': d.badgeFilter.value === 'failing' }" @click="d.setBadgeFilter('failing')" title="Click to filter by tests with recent failures">
          <div class="tests-overview__kpi-label">Failing</div>
          <div class="tests-overview__kpi-value" style="color:var(--red)">{{ d.tests.filter(t => t.failScore > 0).length }}</div>
        </div>
        <div class="kpi tests-overview__kpi tests-overview__kpi--filter" :class="{ 'tests-overview__kpi--active': d.badgeFilter.value === 'static' }" @click="d.setBadgeFilter('static')" title="Tests sharing static field accesses with changed classes — click to filter">
          <div class="tests-overview__kpi-label">Static Overlap</div>
          <div class="tests-overview__kpi-value" style="color:var(--purple)">{{ d.tests.filter(t => t.hasStaticFieldOverlap).length }}</div>
        </div>
        <div v-if="d.badgeFilter.value" class="tests-overview__filter-clear" @click="d.setBadgeFilter(null)" title="Clear filter">
          ✕ {{ d.badgeFilter.value }}
        </div>
      </div>
      <h3 style="font-size:.82rem;color:var(--text-sec);margin-bottom:6px">All tests by priority</h3>
      <div style="overflow-x:auto;max-height:500px;overflow-y:auto">
        <table>
          <thead class="tests-overview__thead">
            <tr>
              <th class="th--right">Rank</th>
              <th class="th--left">Test</th>
              <th class="th--right" title="Composite priority score — higher means run sooner">Score</th>
              <th class="th--left">Flags</th>
              <th class="th--right">Duration</th>
              <th class="th--right" title="Number of source-class dependencies tracked for this test">Deps</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in d.filteredTests.value" :key="t.name" @click="d.selectTest(t, $event)" class="tests-overview__row" :class="{ 'tests-overview__row--dimmed': t.score === 0 }">
              <td class="td--right td--dim">#{{ t.rank }}</td>
              <td class="td--name" :title="t.name">{{ sn(t.name) }}</td>
              <td class="td--right td--accent">{{ t.score }}</td>
              <td><TestBadges :test="t" /></td>
              <td class="td--right td--dim">{{ t.duration >= 0 ? fmtDur(t.duration) : '' }}</td>
              <td class="td--right td--dim">{{ t.depTotal || 0 }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p style="color:var(--text-muted);font-size:.72rem;margin-top:10px">Click a test to see details · Ctrl/⌘+click for multi-select · Shift+click for range</p>
    </div>

    <!-- Multiple tests selected: comparison -->
    <div v-else-if="d.selectedTests.value.size > 1">
      <div style="display:flex;align-items:center;gap:8px;margin-bottom:10px;flex-wrap:wrap">
        <span style="font-size:.9rem;font-weight:700;color:var(--accent-light)">{{ d.selectedTests.value.size }} tests selected</span>
        <span style="font-size:.72rem;color:var(--text-muted)">Ctrl/⌘+click to toggle · click to focus one</span>
      </div>
      <div style="overflow-x:auto;max-height:300px;overflow-y:auto;margin-bottom:12px">
        <table>
          <thead class="tests-overview__thead">
            <tr>
              <th class="th--right">Rank</th>
              <th class="th--left">Test</th>
              <th class="th--right">Score</th>
              <th class="th--left">Flags</th>
              <th class="th--right">Duration</th>
              <th class="th--right" title="Deps overlapping with changed source classes — higher means more relevant">Dep overlap</th>
              <th class="th--right" title="Total source-class dependencies tracked for this test">Total deps</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in d.selectedTestObjects.value" :key="t.name" @click="d.selectTest(t, $event)" class="tests-overview__row"
              :class="{ 'tests-multi__row--focused': d.selectedTest.value && d.selectedTest.value.name === t.name }">
              <td class="td--right td--dim">#{{ t.rank }}</td>
              <td class="td--name td--narrow" :title="t.name">{{ sn(t.name) }}</td>
              <td class="td--right td--accent">{{ t.score }}</td>
              <td><TestBadges :test="t" /></td>
              <td class="td--right td--dim">{{ t.duration >= 0 ? fmtDur(t.duration) : '' }}</td>
              <td class="td--right td--dim">{{ t.depOverlap }}</td>
              <td class="td--right td--dim">{{ t.depTotal }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <DepGraph label="Combined Dependency Graph" />
    </div>

    <!-- Single test selected: detail view -->
    <div v-else-if="d.selectedTest.value">
      <div style="display:flex;align-items:center;gap:8px;margin-bottom:10px;flex-wrap:wrap">
        <span style="font-size:.9rem;font-weight:700;color:var(--accent-light)">#{{ d.selectedTest.value.rank }}</span>
        <span style="font-size:.85rem;font-weight:600;color:var(--text);word-break:break-all">{{ d.selectedTest.value.name }}</span>
        <span style="font-size:1rem;font-weight:700;color:var(--accent);margin-left:auto">Score: {{ d.selectedTest.value.score }}</span>
      </div>
      <div style="display:flex;gap:4px;flex-wrap:wrap;margin-bottom:10px">
        <TestBadges :test="d.selectedTest.value" size="md" />
        <span v-if="d.selectedTest.value.methods && d.selectedTest.value.methods.length" class="badge" style="background:rgba(99,102,241,.2);color:var(--accent-light)">{{ d.selectedTest.value.methods.length }} methods</span>
      </div>

      <!-- Score breakdown + Run history -->
      <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px">
        <div class="card">
          <div class="card-label">Score Breakdown</div>
          <div class="test-detail__canvas-wrap" style="height:64px"><canvas id="bd-main"></canvas></div>
          <div style="display:flex;flex-wrap:wrap;gap:4px;margin-top:6px">
            <template v-for="c in d.scoreComps.value" :key="c.label">
              <div v-if="c.value !== 0" class="test-detail__score-item">
                <span class="test-detail__score-dot" :style="{ background: c.color }"></span>
                <span class="test-detail__score-label">{{ c.label }}</span>
                <span class="test-detail__score-val" :class="{ 'test-detail__score-val--pos': c.value > 0, 'test-detail__score-val--neg': c.value < 0 }">{{ c.value > 0 ? '+' : '' }}{{ c.value }}</span>
              </div>
            </template>
          </div>
        </div>
        <div class="card">
          <div class="card-label">Run History</div>
          <div style="display:flex;flex-wrap:wrap;gap:2px;padding:2px 0;margin-bottom:6px">
            <div
              v-for="(r, i) in d.testOutcomes.value"
              :key="i"
              class="test-detail__run-sq"
              :class="{
                'test-detail__run-sq--pass': r.present && !r.failed,
                'test-detail__run-sq--fail': r.present && r.failed,
                'test-detail__run-sq--absent': !r.present,
              }"
              :title="fmtTime(r.ts) + (r.present ? (r.failed ? ' — FAILED' : ' — passed') : ' — not in run')"
            ></div>
          </div>
          <div class="card-label">Expected duration (EMA reference)</div>
          <div class="test-detail__canvas-wrap" style="height:50px"><canvas id="hd-main"></canvas></div>
        </div>
      </div>

      <!-- Score over time + Run position -->
      <div v-if="d.runs.length > 1" style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:10px">
        <div class="card">
          <div class="card-label">Score over runs</div>
          <div class="test-detail__canvas-wrap test-detail__canvas-wrap--trend"><canvas id="hs-main"></canvas></div>
        </div>
        <div class="card">
          <div class="card-label">Run position (lower = earlier)</div>
          <div class="test-detail__canvas-wrap test-detail__canvas-wrap--trend"><canvas id="hp-main"></canvas></div>
        </div>
      </div>

      <!-- Method detail table -->
      <div v-if="d.selectedTest.value.methods && d.selectedTest.value.methods.length" style="margin-top:10px">
        <div class="card-label" style="margin-bottom:4px">
          Test Methods ({{ d.selectedTest.value.methods.length }})
          <span style="color:var(--text-muted);font-size:.62rem">— click in sidebar or below to focus dep graph</span>
        </div>
        <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:4px">
          <div
            v-for="m in d.selectedTest.value.methods"
            :key="m.name"
            @click="d.selectMethod(m, $event)"
            class="test-detail__method-card"
            :class="{ 'test-detail__method-card--selected': d.selectedMethods.value.has(m.name) }"
          >
            <span class="test-detail__method-name" :title="m.name">{{ m.name }}</span>
            <span class="test-detail__method-deps">{{ m.depCount }} deps</span>
          </div>
        </div>
      </div>

      <DepGraph />
    </div>
  </div>
</template>

<style scoped>
/* Overview */
.tests-overview__kpi { padding: 6px 10px; }
.tests-overview__kpi-label { color: var(--text-dim); font-size: .6rem; }
.tests-overview__kpi-value { font-size: 1rem; font-weight: 700; }
.tests-overview__kpi--filter { cursor: pointer; transition: border-color var(--tr-fast), background var(--tr-fast); }
.tests-overview__kpi--filter:hover { border-color: rgba(99,102,241,.4); background: rgba(99,102,241,.06); }
.tests-overview__kpi--active { border-color: var(--accent) !important; background: var(--accent-bg) !important; }
.tests-overview__filter-clear { display: flex; align-items: center; padding: 4px 10px; font-size: .7rem; color: var(--text-muted); background: var(--bg-card); border: 1px solid var(--border); border-radius: var(--radius); cursor: pointer; transition: color var(--tr-fast); }
.tests-overview__filter-clear:hover { color: var(--text); }
.tests-overview__thead { position: sticky; top: 0; background: var(--bg-base); z-index: 1; }
.tests-overview__row { cursor: pointer; }
.tests-overview__row--dimmed { opacity: .5; }

/* Table cell helpers */
.th--right { padding: 4px 8px; text-align: right; }
.th--left { padding: 4px 8px; text-align: left; }
.td--right { padding: 3px 8px; text-align: right; font-size: .75rem; }
.td--dim { color: var(--text-dim); }
.td--accent { color: var(--accent-light); font-weight: 700; }
.td--name { padding: 3px 8px; color: var(--text); max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.td--narrow { max-width: 200px; }

/* Multi-select */
.tests-multi__row--focused { background: rgba(99, 102, 241, .1); }

/* Detail view */
.test-detail__canvas-wrap { position: relative; }
.test-detail__canvas-wrap--trend { height: 80px; }
.test-detail__score-item { display: flex; align-items: center; gap: 3px; }
.test-detail__score-dot { width: 8px; height: 8px; border-radius: 2px; flex-shrink: 0; }
.test-detail__score-label { font-size: .65rem; color: var(--text-sec); }
.test-detail__score-val { font-size: .65rem; font-weight: 700; }
.test-detail__score-val--pos { color: var(--green); }
.test-detail__score-val--neg { color: var(--red); }
.test-detail__run-sq { width: 12px; height: 12px; border-radius: 2px; flex-shrink: 0; border: 1px solid; }
.test-detail__run-sq--pass { background: #22c55e; border-color: #15803d; }
.test-detail__run-sq--fail { background: #ef4444; border-color: #b91c1c; }
.test-detail__run-sq--absent { background: var(--bg-base); border-color: var(--border); }
.test-detail__method-card {
  background: var(--bg-base); border: 1px solid var(--border); border-radius: 4px; padding: 4px 8px;
  font-size: .7rem; display: flex; justify-content: space-between; align-items: center;
  cursor: pointer; transition: background .1s; user-select: none;
}
.test-detail__method-card--selected { background: rgba(99, 102, 241, .12); }
.test-detail__method-name { color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.test-detail__method-deps { color: var(--accent-light); font-weight: 600; flex-shrink: 0; margin-left: 8px; }
</style>
