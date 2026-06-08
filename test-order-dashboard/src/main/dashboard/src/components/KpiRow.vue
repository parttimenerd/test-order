<script setup lang="ts">
import { inject, computed, ref, nextTick } from 'vue'
import type { DashboardState } from '../composables/useDashboard'
import { fmtDur, fmtTime, sn } from '../utils'
import { useClassHover } from '../composables/useClassInfo'
import ClassInfoCard from './ClassInfoCard.vue'

const d = inject<DashboardState>('dashboard')!
const classHover = useClassHover()

const apfdColor = computed(() => {
  const v = d.avgApfd.value
  if (v === null) return 'var(--text-sec)'
  return v >= 0.7 ? 'var(--green)' : v >= 0.5 ? 'var(--yellow)' : 'var(--red)'
})
const failColor = computed(() => {
  const r = d.latestRun.value
  return r && r.totalFailures > 0 ? 'var(--red)' : 'var(--green)'
})

// Compute test reliability: % of tests that have never failed across all runs
const reliabilityPct = computed(() => {
  if (!d.runs.length) return null
  const failedOnce = new Set<string>()
  for (const r of d.runs) {
    for (const o of (r.outcomes || [])) {
      if (o.failed) failedOnce.add(o.testClass)
    }
  }
  const total = d.tests.length
  if (!total) return null
  return Math.round(((total - failedOnce.size) / total) * 100)
})

// Use shared suiteHealthBreakdown from composable
const suiteHealth = computed(() => {
  const h = d.suiteHealthBreakdown.value
  if (!h) return null
  const tip = [
    `Suite Health Score: ${h.grade} (${h.composite}/100)`,
    `  APFD:        ${h.apfdScore}% × 30%`,
    `  Reliability: ${h.relScore}% × 30%`,
    `  Flakiness:   ${h.flakyScore}% × 20%`,
    `  Coverage:    ${h.covScore}% × 20%`,
    d.hasCoverage ? '' : '  (Coverage component estimated — no instrumentation data)',
  ].filter(Boolean).join('\n')
  return { grade: h.grade, color: h.color, composite: h.composite, tip }
})

function openHealth() {
  d.setTab('analytics')
  nextTick(() => document.getElementById('suite-health')?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
}

// Latest APFD trend: arrow showing improvement/regression vs previous run
const apfdTrend = computed(() => {
  if (d.runs.length < 2) return null
  const prev = d.runs[d.runs.length - 2].apfd
  const curr = d.runs[d.runs.length - 1].apfd
  const delta = curr - prev
  if (Math.abs(delta) < 0.005) return { dir: '→', color: 'var(--text-muted)', tip: 'stable' }
  return delta > 0
    ? { dir: '↑', color: 'var(--green)', tip: `+${(delta * 100).toFixed(1)}% from previous run` }
    : { dir: '↓', color: 'var(--red)', tip: `${(delta * 100).toFixed(1)}% from previous run` }
})

// History browser: selected run index (-1 = latest / live)
// sparkBars index: i=0 → newest run (d.runs[length-1]); i=length-1 → oldest run (d.runs[0])
const historyIdx = ref(-1)
const isLive = computed(() => historyIdx.value === -1 || historyIdx.value === 0)
const currentHistIdx = computed(() => historyIdx.value === -1 ? 0 : historyIdx.value)
const historyRun = computed(() => {
  if (!d.runs.length) return null
  // sparkBars index i=0=newest → d.runs[length-1]; i=length-1=oldest → d.runs[0]
  return d.runs[d.runs.length - 1 - currentHistIdx.value] ?? null
})

function goToRun(i: number) {
  historyIdx.value = i
}

function openInAnalytics(sliderIdx: number) {
  // sliderIdx 0=oldest; convert to d.runs index (0=newest)
  d.navigateToRun(d.runs.length - 1 - sliderIdx)
}

// Current pass/fail streak (d.runs is oldest-first; start from newest run d.runs[length-1])
const streak = computed(() => {
  if (!d.runs.length) return null
  const isPass = (r: typeof d.runs[0]) => r.totalFailures === 0
  const first = d.runs[d.runs.length - 1]
  const passing = isPass(first)
  let count = 1
  for (let i = d.runs.length - 2; i >= 0; i--) {
    if (isPass(d.runs[i]) === passing) count++
    else break
  }
  // Best streak: longest consecutive pass streak ever
  let bestPassStreak = 0
  let cur = 0
  for (let i = d.runs.length - 1; i >= 0; i--) {
    if (isPass(d.runs[i])) { cur++; bestPassStreak = Math.max(bestPassStreak, cur) }
    else cur = 0
  }
  return { passing, count, bestPassStreak }
})

// Last failure: how many runs ago was the most recent failure, and which test caused it
const lastFailureInfo = computed(() => {
  if (d.runs.length < 2) return null
  // d.runs is oldest-first; iterate newest-first to find most recent failure
  for (let i = d.runs.length - 1; i >= 0; i--) {
    if (d.runs[i].totalFailures > 0) {
      const cleanCount = d.runs.length - 1 - i // number of consecutive clean runs after this failure
      const failRun = d.runs[i]
      const failedTests = (failRun.outcomes || []).filter(o => o.failed).map(o => o.testClass)
      return { runsAgo: d.runs.length - 1 - i, cleanCount, ts: failRun.timestamp, failures: failRun.totalFailures, failedTests }
    }
  }
  // All runs passed
  return { runsAgo: -1, cleanCount: d.runs.length, ts: null, failures: 0, failedTests: [] }
})

// At-risk tests: top tests by failScore that appeared in recent runs, sorted by risk
const atRiskTests = computed(() => {
  if (!d.tests.length) return []
  // Focus on tests with actual fail signal (failScore > 0) or flaky
  const candidates = d.tests
    .filter(t => t.failScore > 0 || d.flakyTests.value.has(t.name))
    .sort((a, b) => {
      // Combine failScore with flakiness weight
      const scoreA = a.failScore + (d.flakyTests.value.has(a.name) ? 0.3 : 0)
      const scoreB = b.failScore + (d.flakyTests.value.has(b.name) ? 0.3 : 0)
      return scoreB - scoreA
    })
  return candidates.slice(0, 3)
})

// Estimated time savings from prioritization: (avgAPFD - 0.5) × suiteDuration
// Only meaningful when there are failing runs and we have a suite duration estimate.
const timeSavings = computed(() => {
  if (d.avgApfd.value === null || d.avgApfd.value <= 0.5) return null
  const failingRuns = d.runs.filter(r => r.totalFailures > 0)
  if (!failingRuns.length) return null
  // Estimate suite duration from test durations (sum of all test durations)
  const knownDurations = d.tests.filter(t => t.duration >= 0)
  if (knownDurations.length < 5) return null
  const suiteDurMs = knownDurations.reduce((s, t) => s + t.duration, 0)
  if (suiteDurMs <= 0) return null
  // Expected time to detect first failure with prioritized order vs random:
  // Random: detects at ~50% through suite. Prioritized: at avgAPFD% through.
  // Time saved = (0.5 - (1 - avgAPFD)) * suiteDur  = (avgAPFD - 0.5) * suiteDur
  const savedMs = (d.avgApfd.value - 0.5) * suiteDurMs
  return { savedMs, suiteDurMs, apfd: d.avgApfd.value, failingRuns: failingRuns.length }
})

// Tests affected by the current change set — matches `mvn test-order:affected`
// tier-1 selection: depOverlap > 0 OR isChanged OR isNew (or test class itself
// modified). Tile click navigates to the Tests tab with the matching filter.
const changeAffectedCount = computed(() => {
  const changedTestSet = new Set(d.dd.changedTestClasses)
  return d.tests.filter(t =>
    t.depOverlap > 0 || t.isChanged || t.isNew || changedTestSet.has(t.name)
  ).length
})

// Mini sparkline bars: each run = a bar whose height encodes APFD (0–100%) and color encodes pass/fail
const sparkBars = computed(() => {
  if (!d.runs.length) return []
  const maxFail = Math.max(...d.runs.map(r => r.totalFailures), 1)
  // Build bars newest-first (i=0=newest=d.runs[length-1], i=length-1=oldest=d.runs[0])
  return [...d.runs].reverse().map((r, i) => {
    const apfdPct = Math.round(r.apfd * 100)
    const hasFail = r.totalFailures > 0
    const h = 10 + Math.round(apfdPct * 0.9)
    const failAlpha = hasFail ? Math.min(0.9, 0.3 + (r.totalFailures / maxFail) * 0.6) : 0
    return { i, apfdPct, hasFail, failures: r.totalFailures, h, failAlpha, ts: r.timestamp }
  })
})
</script>

<template>
  <div class="kpi-row">
    <!-- Avg APFD with trend indicator -->
    <div class="kpi kpi-row__kpi" :title="'Average APFD across ' + d.runs.length + ' run(s). APFD (Average Percentage of Faults Detected) measures how early failing tests appear in the ordered list. Higher is better; random ordering = 50%.'">
      <div class="kpi-row__label">Avg APFD</div>
      <div class="kpi-row__value" :style="{ color: apfdColor }">
        {{ d.avgApfd.value !== null ? (d.avgApfd.value * 100).toFixed(1) + '%' : 'N/A' }}
        <span v-if="apfdTrend" :style="{ color: apfdTrend.color, fontSize: '.65rem' }" :title="apfdTrend.tip">{{ apfdTrend.dir }}</span>
      </div>
    </div>

    <!-- Latest run failures -->
    <div class="kpi kpi-row__kpi" :title="'Failures in the most recent run. 0 = all tests passed.'">
      <div class="kpi-row__label">Latest Failures</div>
      <div class="kpi-row__value" :style="{ color: failColor }">
        {{ d.latestRun.value ? d.latestRun.value.totalFailures : '—' }}
      </div>
    </div>

    <!-- Current streak -->
    <div
      v-if="streak && d.runs.length >= 2"
      class="kpi kpi-row__kpi kpi-row__kpi--clickable"
      @click="d.setTab('analytics')"
      :title="(streak.passing ? 'Currently ' + streak.count + ' consecutive passing run' + (streak.count > 1 ? 's' : '') : streak.count + ' run' + (streak.count > 1 ? 's' : '') + ' in a row with failures') + (streak.bestPassStreak > 0 ? '\nBest pass streak ever: ' + streak.bestPassStreak : '') + '\nClick to open Analytics'"
    >
      <div class="kpi-row__label">Streak</div>
      <div class="kpi-row__streak" :class="streak.passing ? 'kpi-row__streak--pass' : 'kpi-row__streak--fail'">
        <span class="kpi-row__streak-count">{{ streak.count }}</span>
        <span class="kpi-row__streak-icon">{{ streak.passing ? '✓' : '✕' }}</span>
      </div>
      <div v-if="streak.bestPassStreak > streak.count && streak.passing" class="kpi-row__streak-best">best {{ streak.bestPassStreak }}</div>
    </div>

    <!-- Last failure KPI: how many clean runs since last failure -->
    <div
      v-if="lastFailureInfo && d.runs.length >= 2"
      class="kpi kpi-row__kpi kpi-row__kpi--clickable"
      @click="d.setTab('analytics')"
      :title="lastFailureInfo.runsAgo === -1
        ? 'All ' + lastFailureInfo.cleanCount + ' recorded runs passed — no failures on record. Click to open Analytics.'
        : lastFailureInfo.runsAgo === 0
          ? 'Latest run had ' + lastFailureInfo.failures + ' failure(s): ' + lastFailureInfo.failedTests.slice(0,3).map(n => n.split('.').pop()).join(', ') + (lastFailureInfo.failedTests.length > 3 ? '…' : '') + '\nClick to open Analytics.'
          : lastFailureInfo.cleanCount + ' clean run' + (lastFailureInfo.cleanCount !== 1 ? 's' : '') + ' since last failure (' + lastFailureInfo.failures + ' fail' + (lastFailureInfo.failures !== 1 ? 's' : '') + ' in run #' + (lastFailureInfo.runsAgo + 1) + ')\nFailed: ' + lastFailureInfo.failedTests.slice(0,3).map(n => n.split('.').pop()).join(', ') + (lastFailureInfo.failedTests.length > 3 ? '…' : '') + '\nClick to open Analytics.'"
    >
      <div class="kpi-row__label">Last Failure</div>
      <div v-if="lastFailureInfo.runsAgo === 0" class="kpi-row__value" style="color:var(--red)">now</div>
      <div v-else-if="lastFailureInfo.runsAgo === -1" class="kpi-row__value" style="color:var(--green)">never</div>
      <div v-else class="kpi-row__last-fail">
        <span class="kpi-row__last-fail-count">{{ lastFailureInfo.cleanCount }}</span>
        <span class="kpi-row__last-fail-label">ago</span>
      </div>
    </div>

    <!-- Tests with fail history (if any) - clickable to filter -->
    <div
      v-if="d.tests.filter(t => t.failScore > 0).length > 0"
      class="kpi kpi-row__kpi kpi-row__kpi--clickable"
      @click="d.setBadgeFilter('failing'); d.setTab('tests')"
      title="Tests with EMA-decayed failure history — run these early to detect recurring failures. Click to filter in Tests tab."
    >
      <div class="kpi-row__label">Fail History</div>
      <div class="kpi-row__value" style="color:var(--red)">
        {{ d.tests.filter(t => t.failScore > 0).length }}
      </div>
    </div>

    <!-- At-risk tests chip — top tests by combined failScore + flakiness signal -->
    <div
      v-if="atRiskTests.length > 0"
      class="kpi kpi-row__kpi kpi-row__kpi--clickable kpi-row__kpi--at-risk"
      @click="d.setBadgeFilter('failing'); d.setTab('tests')"
      :title="'Tests most at risk of failing next run (by EMA fail score + flakiness):\n' + atRiskTests.map((t, i) => `${i+1}. ${t.name.split('.').pop()} (score ${t.failScore.toFixed(2)}${d.flakyTests.value.has(t.name) ? ' · flaky' : ''})`).join('\n') + '\nClick to filter in Tests tab.'"
    >
      <div class="kpi-row__label">⚠ At Risk</div>
      <div class="kpi-row__at-risk-list">
        <span v-for="t in atRiskTests" :key="t.name" class="kpi-row__at-risk-name">
          {{ t.name.split('.').pop() }}{{ d.flakyTests.value.has(t.name) ? '~' : '' }}
        </span>
      </div>
    </div>

    <!-- Time savings from prioritization -->
    <div
      v-if="timeSavings"
      class="kpi kpi-row__kpi kpi-row__kpi--clickable kpi-row__kpi--savings"
      @click="d.setTab('analytics')"
      :title="'Estimated time saved by prioritized test ordering vs random:\n  Suite duration (sum of test durations): ' + fmtDur(timeSavings.suiteDurMs) + '\n  Avg APFD: ' + (timeSavings.apfd * 100).toFixed(1) + '% vs 50% random baseline\n  ≈ detect failures ' + fmtDur(timeSavings.savedMs) + ' earlier on average\n  Across ' + timeSavings.failingRuns + ' failing run(s)\nClick to view Analytics.'"
    >
      <div class="kpi-row__label">⚡ Time Saved</div>
      <div class="kpi-row__savings-value">{{ fmtDur(timeSavings.savedMs) }}</div>
      <div class="kpi-row__savings-sub">earlier detection</div>
    </div>

    <!-- Run count with history navigation + sparkline -->
    <div v-if="d.runs.length" class="kpi kpi-row__kpi kpi-row__kpi--history" :title="'Run history — use arrows to browse past runs, or click a bar in the sparkline'">
      <div class="kpi-row__label">Runs ({{ d.runs.length }})</div>
      <!-- Sparkline: bar per run, height=APFD, color=fail/pass -->
      <div class="kpi-row__sparkline" v-if="d.runs.length > 1">
        <div
          v-for="bar in sparkBars"
          :key="bar.i"
          class="kpi-row__spark-bar"
          :class="{ 'kpi-row__spark-bar--selected': bar.i === currentHistIdx }"
          :style="{
            height: bar.h + '%',
            background: bar.hasFail
              ? `rgba(248, 113, 113, ${bar.failAlpha})`
              : 'rgba(74, 222, 128, .45)',
            borderColor: bar.i === currentHistIdx ? (bar.hasFail ? 'var(--red)' : 'var(--green)') : 'transparent',
          }"
          :title="`Run #${bar.i + 1} · ${fmtTime(bar.ts)} · APFD ${bar.apfdPct}% · ${bar.hasFail ? bar.failures + ' failures' : 'all passed'}`"
          @click="goToRun(bar.i)"
        ></div>
      </div>
      <!-- Range slider -->
      <input
        v-if="d.runs.length > 1"
        type="range"
        class="kpi-row__slider"
        :min="0"
        :max="d.runs.length - 1"
        :value="currentHistIdx"
        @input="goToRun(+($event.target as HTMLInputElement).value)"
        :title="`Run ${currentHistIdx + 1} of ${d.runs.length}`"
      />
      <div style="display:flex;align-items:center;gap:4px;margin-top:2px">
        <button class="kpi-row__hist-btn" @click="goToRun(d.runs.length - 1)" :disabled="currentHistIdx === d.runs.length - 1" title="Oldest run («)">«</button>
        <button class="kpi-row__hist-btn" @click="goToRun(Math.min(d.runs.length - 1, currentHistIdx + 1))" :disabled="currentHistIdx === d.runs.length - 1" title="Previous (older) run (‹)">‹</button>
        <span class="kpi-row__hist-pos" :style="{ color: isLive ? 'var(--green)' : 'var(--yellow)' }">
          {{ isLive ? 'latest' : '#' + (currentHistIdx + 1) }}
        </span>
        <button class="kpi-row__hist-btn" @click="goToRun(Math.max(0, currentHistIdx - 1))" :disabled="isLive" title="Next (newer) run (›)">›</button>
        <button class="kpi-row__hist-btn" @click="historyIdx = -1" :disabled="isLive" title="Latest run (»)">»</button>
        <button class="kpi-row__hist-btn kpi-row__hist-btn--analytics" @click="openInAnalytics(currentHistIdx)" title="Open this run in Analytics tab">↗</button>
      </div>
      <div v-if="historyRun" class="kpi-row__hist-time" :title="fmtTime(historyRun.timestamp)">
        {{ historyRun.totalFailures > 0 ? historyRun.totalFailures + ' fail' : '✓ pass' }}
        · APFD {{ (historyRun.apfd * 100).toFixed(1) }}%
        · {{ historyRun.totalTests }} tests
      </div>
    </div>

    <!-- Test reliability -->
    <div v-if="reliabilityPct !== null" class="kpi kpi-row__kpi kpi-row__kpi--clickable" @click="d.setBadgeFilter('failing'); d.setTab('tests')" :title="'Percentage of tests that have never failed across all recorded runs. Lower means more flaky/unstable tests.\nClick to filter failing tests in Tests tab.'">
      <div class="kpi-row__label">Test Reliability</div>
      <div class="kpi-row__value" :style="{ color: (reliabilityPct ?? 0) >= 90 ? 'var(--green)' : (reliabilityPct ?? 0) >= 70 ? 'var(--yellow)' : 'var(--red)' }">
        {{ reliabilityPct }}%
      </div>
    </div>

    <!-- Fastest / Slowest -->
    <div class="kpi kpi-row__kpi kpi-row__kpi--speed" :title="'Fastest and slowest test by EMA duration. These anchor the speed scoring bonus/penalty. Click to navigate to the test.'">
      <div class="kpi-row__label">Fastest / Slowest</div>
      <template v-if="d.fastestTest.value || d.slowestTest.value">
        <div class="kpi-row__speed-item kpi-row__speed-item--clickable" v-if="d.fastestTest.value" @click="d.selectTest(d.fastestTest.value!, null); d.setTab('tests')" :title="'Fastest: ' + d.fastestTest.value.name">
          <span class="kpi-row__speed-icon" style="color:var(--cyan)">▲</span>
          <span class="kpi-row__test-name kpi-row__test-name--fast" :title="d.fastestTest.value.name"
            @mouseenter="classHover.show(d.fastestTest.value!.name, $event)" @mousemove="classHover.move($event)" @mouseleave="classHover.hide()">{{ d.fastestTest.value.name.split('.').pop() }}</span>
          <span class="kpi-row__dur">{{ d.fastestTest.value.duration >= 0 ? fmtDur(d.fastestTest.value.duration) : '' }}</span>
        </div>
        <div class="kpi-row__speed-item kpi-row__speed-item--clickable" v-if="d.slowestTest.value" @click="d.selectTest(d.slowestTest.value!, null); d.setTab('tests')" :title="'Slowest: ' + d.slowestTest.value.name">
          <span class="kpi-row__speed-icon" style="color:var(--orange)">▼</span>
          <span class="kpi-row__test-name kpi-row__test-name--slow" :title="d.slowestTest.value.name"
            @mouseenter="classHover.show(d.slowestTest.value!.name, $event)" @mousemove="classHover.move($event)" @mouseleave="classHover.hide()">{{ d.slowestTest.value.name.split('.').pop() }}</span>
          <span class="kpi-row__dur">{{ d.slowestTest.value.duration >= 0 ? fmtDur(d.slowestTest.value.duration) : '' }}</span>
        </div>
      </template>
      <span v-else class="kpi-row__no-data">run tests to populate</span>
    </div>

    <!-- Change-affected tests count -->
    <div
      class="kpi kpi-row__kpi kpi-row__kpi--clickable"
      @click="d.setBadgeFilter('affected'); d.setTab('tests')"
      :title="'Tests `mvn test-order:affected test` would run — tier 1 of the tiered selection (tests with dep overlap on changed source, tests whose own source changed, or new tests).\n' + d.dd.changedClasses.length + ' source class' + (d.dd.changedClasses.length === 1 ? '' : 'es') + (d.dd.changedTestClasses.length ? ' + ' + d.dd.changedTestClasses.length + ' test class' + (d.dd.changedTestClasses.length === 1 ? '' : 'es') : '') + ' changed.\nClick to filter affected tests.'">
      <div class="kpi-row__label">Change Affected</div>
      <div class="kpi-row__value" :style="{ color: changeAffectedCount > 0 ? 'var(--yellow)' : 'var(--text-sec)' }">
        {{ changeAffectedCount }}<span v-if="d.dd.changedClasses.length || d.dd.changedTestClasses.length" style="font-size:.6rem;color:var(--text-muted);font-weight:500;margin-left:3px">/ {{ d.dd.changedClasses.length + d.dd.changedTestClasses.length }} src</span>
      </div>
    </div>

    <!-- Suite Health Score -->
    <div v-if="suiteHealth" class="kpi kpi-row__kpi kpi-row__kpi--clickable kpi-row__kpi--health" @click="openHealth()" :title="suiteHealth.tip">
      <div class="kpi-row__label">Health</div>
      <div class="kpi-row__health-grade" :style="{ color: suiteHealth.color }">{{ suiteHealth.grade }}</div>
      <div class="kpi-row__health-score">{{ suiteHealth.composite }}/100</div>
    </div>

    <!-- Source coverage (if available) -->
    <div v-if="d.hasCoverage" class="kpi kpi-row__kpi kpi-row__kpi--clickable" @click="d.setTab('analytics')" title="Source class coverage tracked by test suite — click to see Coverage tab in Analytics">
      <div class="kpi-row__label">Src Coverage</div>
      <div style="display:flex;align-items:center;gap:6px">
        <div class="kpi-row__progress">
          <div class="kpi-row__progress-fill" :style="{ width: d.covPercent.value + '%', background: d.covPercent.value >= 80 ? 'var(--green)' : d.covPercent.value >= 50 ? 'var(--yellow)' : 'var(--red)' }"></div>
        </div>
        <span class="kpi-row__value" :style="{ color: d.covPercent.value >= 80 ? 'var(--green)' : d.covPercent.value >= 50 ? 'var(--yellow)' : 'var(--red)' }">{{ d.covPercent.value }}%</span>
      </div>
    </div>
  </div>
  <ClassInfoCard v-if="classHover.visible.value" :info="classHover.info.value" :x="classHover.x.value" :y="classHover.y.value" />
</template>

<style scoped>
.kpi-row {
  background: var(--bg-card); border-bottom: 1px solid var(--border);
  padding: 5px 14px; display: grid; grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
  gap: 6px; flex-shrink: 0; align-items: stretch;
}
.kpi-row__kpi { padding: 5px 10px; min-width: 90px; }
.kpi-row__kpi--history { min-width: 180px; }
.kpi-row__kpi--speed { min-width: 140px; }
.kpi-row__kpi--clickable { cursor: pointer; }
.kpi-row__kpi--clickable:hover { border-color: rgba(99,102,241,.4); background: rgba(99,102,241,.07); }
.kpi-row__label { color: var(--text-sec); font-size: .6rem; margin-bottom: 2px; font-weight: 600; text-transform: uppercase; letter-spacing: .4px; }
.kpi-row__value { font-size: 1.1rem; font-weight: 700; }
.kpi-row__dur { font-size: .6rem; color: var(--text-muted); flex-shrink: 0; }
.kpi-row__test-name { font-size: .72rem; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; min-width: 0; }
.kpi-row__test-name--fast { color: var(--cyan); }
.kpi-row__test-name--slow { color: var(--orange); }
.kpi-row__speed-item { display: flex; align-items: center; gap: 4px; min-width: 0; }
.kpi-row__speed-item--clickable { cursor: pointer; border-radius: 3px; transition: background var(--tr-fast); padding: 1px 2px; margin: 0 -2px; }
.kpi-row__speed-item--clickable:hover { background: rgba(99,102,241,.1); }
.kpi-row__speed-icon { font-size: .55rem; flex-shrink: 0; }
.kpi-row__no-data { font-size: .62rem; color: var(--text-muted); font-style: italic; }

/* History browser controls */
.kpi-row__hist-btn {
  background: none; border: 1px solid var(--border); border-radius: 3px;
  color: var(--text-sec); cursor: pointer; padding: 1px 5px; font-size: .72rem;
  line-height: 1.2; transition: all var(--tr-fast);
}
.kpi-row__hist-btn:hover:not(:disabled) { color: var(--text); border-color: var(--accent); }
.kpi-row__hist-btn:disabled { opacity: .3; cursor: default; }
.kpi-row__hist-btn--analytics { color: var(--accent-light); border-color: var(--accent); margin-left: auto; }
.kpi-row__hist-btn--analytics:hover { background: rgba(99,102,241,.12); }
.kpi-row__hist-pos { font-size: .78rem; font-weight: 700; min-width: 38px; text-align: center; }
.kpi-row__hist-time { font-size: .58rem; color: var(--text-sec); margin-top: 2px; white-space: nowrap; }

/* Sparkline mini bar chart */
.kpi-row__sparkline {
  display: flex; align-items: flex-end; gap: 1px;
  height: 28px; width: 100%; overflow: hidden; margin-bottom: 2px;
}
.kpi-row__spark-bar {
  flex: 1; min-width: 3px; border-radius: 1px 1px 0 0; cursor: pointer;
  transition: opacity .1s, border-color .1s; border: 1px solid transparent; border-bottom: none;
}
.kpi-row__spark-bar:hover { opacity: .8; }

/* Range slider for run navigation */
.kpi-row__slider {
  width: 100%; height: 4px; margin: 2px 0 0;
  -webkit-appearance: none; appearance: none;
  background: var(--border); border-radius: 2px; outline: none; cursor: pointer;
}
.kpi-row__slider::-webkit-slider-thumb {
  -webkit-appearance: none; appearance: none;
  width: 10px; height: 10px; border-radius: 50%;
  background: var(--accent-light); border: 1px solid var(--accent);
  cursor: pointer; transition: background var(--tr-fast);
}
.kpi-row__slider::-webkit-slider-thumb:hover { background: var(--accent); }

/* Coverage progress bar */
.kpi-row__progress { height: 6px; width: 50px; background: var(--bg-base); border-radius: 3px; overflow: hidden; }
.kpi-row__progress-fill { height: 100%; border-radius: 3px; transition: width .3s; }

/* Streak indicator */
.kpi-row__streak { display: flex; align-items: baseline; gap: 3px; }
.kpi-row__streak-count { font-size: 1.1rem; font-weight: 700; }
.kpi-row__streak-icon { font-size: .7rem; font-weight: 700; }
.kpi-row__streak--pass .kpi-row__streak-count { color: var(--green); }
.kpi-row__streak--pass .kpi-row__streak-icon { color: var(--green); }
.kpi-row__streak--fail .kpi-row__streak-count { color: var(--red); }
.kpi-row__streak--fail .kpi-row__streak-icon { color: var(--red); }
.kpi-row__streak-best { font-size: .55rem; color: var(--text-muted); margin-top: 1px; }

/* Last failure KPI */
.kpi-row__last-fail { display: flex; align-items: baseline; gap: 3px; }
.kpi-row__last-fail-count { font-size: 1.1rem; font-weight: 700; color: var(--green); }
.kpi-row__last-fail-label { font-size: .6rem; color: var(--text-muted); }

/* Suite health score */
.kpi-row__kpi--health { min-width: 70px; }
.kpi-row__health-grade { font-size: 1.3rem; font-weight: 800; line-height: 1; letter-spacing: -.02em; }
.kpi-row__health-score { font-size: .52rem; color: var(--text-muted); margin-top: 1px; }

/* At-risk tests chip */
.kpi-row__kpi--at-risk { border-color: rgba(248,113,113,.25); }
.kpi-row__kpi--at-risk:hover { border-color: rgba(248,113,113,.5); background: rgba(248,113,113,.06); }
.kpi-row__at-risk-list { display: flex; flex-direction: column; gap: 1px; margin-top: 1px; }
.kpi-row__at-risk-name { font-size: .58rem; color: var(--red); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 120px; }

/* Time savings chip */
.kpi-row__kpi--savings { border-color: rgba(74,222,128,.2); min-width: 80px; }
.kpi-row__kpi--savings:hover { border-color: rgba(74,222,128,.4); background: rgba(74,222,128,.06); }
.kpi-row__savings-value { font-size: 1rem; font-weight: 700; color: var(--green); line-height: 1.1; }
.kpi-row__savings-sub { font-size: .52rem; color: var(--text-muted); margin-top: 1px; white-space: nowrap; }
</style>
