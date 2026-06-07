<script setup lang="ts">
import { inject, watch, nextTick, onMounted, onUnmounted, computed, ref, type Ref } from 'vue'
import type { DashboardState } from '../composables/useDashboard'
import type { TestHoverState } from '../composables/useTestHover'
import type { TestEntry } from '../types'
import { sn, fmtDur, fmtTime, computeScore } from '../utils'
import { mkChart, destroyCharts, chartOpts } from '../composables/useCharts'
import { useClassHover } from '../composables/useClassInfo'
import ClassInfoCard from './ClassInfoCard.vue'
import TestBadges from './TestBadges.vue'
import DepGraph from './DepGraph.vue'

const d = inject<DashboardState>('dashboard')!
const showToast = inject<(msg: string) => void>('showToast')!
const testHover = inject<TestHoverState>('testHover')!
const shortNames = inject<Ref<boolean>>('shortNames', { value: true } as any)
const focusMode = inject<Ref<boolean>>('focusMode', ref(false))
const toggleFocusMode = inject<() => void>('toggleFocusMode', () => {})
const blameMode = inject<Ref<boolean>>('blameMode', ref(false))

const classHover = useClassHover()
const showAllDeps = ref(false)

// Copy-order format menu
const copyMenuOpen = ref(false)
const COPY_FORMATS = [
  { id: 'names', label: 'Class names (one per line)', fn: (tests: typeof d.filteredTests.value) => tests.map(t => t.name).join('\n') },
  { id: 'maven', label: 'Maven -Dtest=…', fn: (tests: typeof d.filteredTests.value) => '-Dtest=' + tests.map(t => t.name).join(',') },
  { id: 'gradle', label: 'Gradle --tests …', fn: (tests: typeof d.filteredTests.value) => tests.map(t => '--tests ' + t.name).join(' ') },
  { id: 'json', label: 'JSON array', fn: (tests: typeof d.filteredTests.value) => JSON.stringify(tests.map(t => t.name), null, 2) },
] as const
function doCopy(formatId: string) {
  const fmt = COPY_FORMATS.find(f => f.id === formatId)
  if (!fmt) return
  navigator.clipboard?.writeText(fmt.fn(d.filteredTests.value))
  showToast(`Copied ${d.filteredTests.value.length} tests (${fmt.label})`)
  copyMenuOpen.value = false
}

// Close copy menu on outside click
function onDocClick() { copyMenuOpen.value = false }
onMounted(() => document.addEventListener('click', onDocClick))
onUnmounted(() => document.removeEventListener('click', onDocClick))

// Dep-cluster grouping for overview table
const groupByDep = ref(false)
const groupByPkg = ref(false)

// Group key based on test's own package (2nd-to-last segment = immediate package)
function pkgGroupLabel(t: TestEntry): string {
  const parts = t.name.split('.')
  if (parts.length < 2) return t.name
  // Use up to 5 segments for a meaningful group level, but at least 2 from the end
  const depth = Math.min(5, Math.max(2, parts.length - 1))
  return parts.slice(0, depth).join('.')
}
function depClusterLabel(t: TestEntry): string {
  const deps = t.deps || []
  if (!deps.length) return '(no deps)'
  const freq = new Map<string, number>()
  for (const dep of deps) {
    // Extract top 3 package segments as cluster key
    const parts = dep.split('.')
    const key = parts.slice(0, Math.min(3, parts.length - 1)).join('.')
    if (key) freq.set(key, (freq.get(key) ?? 0) + 1)
  }
  let best = '(other)', bestN = 0
  for (const [k, n] of freq) { if (n > bestN) { best = k; bestN = n } }
  return best
}

// Blame mode: tests linked to changed source classes (dep overlap > 0 or isChanged)
const blameLinkedTests = computed(() => {
  const s = new Set<string>()
  if (!d.dd.changedClasses.length && !d.dd.changedTestClasses.length) return s
  for (const t of d.tests) {
    if (t.depOverlap > 0 || t.isChanged || t.hasStaticFieldOverlap) s.add(t.name)
  }
  return s
})

// Stability score: 0–100 composite of fail rate, flakiness, duration variance, and run confidence
const stabilityScores = computed(() => {
  const m = new Map<string, number>()
  if (!d.tests.length) return m

  // Count seen/failed per test from run history
  const seenCount = new Map<string, number>()
  const failCount = new Map<string, number>()
  for (const r of d.runs) {
    for (const o of (r.outcomes || [])) {
      seenCount.set(o.testClass, (seenCount.get(o.testClass) ?? 0) + 1)
      if (o.failed) failCount.set(o.testClass, (failCount.get(o.testClass) ?? 0) + 1)
    }
  }

  for (const t of d.tests) {
    const seen = seenCount.get(t.name) ?? 0
    const failed = failCount.get(t.name) ?? 0
    const failRate = seen > 0 ? failed / seen : 0

    // Confidence discount for tests seen fewer than 5 runs
    const confidence = seen === 0 ? 0.5 : Math.min(1, seen / 5)

    // 1. Reliability: inverse fail rate (0=always fail, 1=never fail)
    const reliability = 1 - failRate

    // 2. Consistency: inverse duration variance (clamped 0–1)
    const variance = t.durationVariance ?? 0
    const consistency = Math.max(0, 1 - Math.min(1, variance * 2))

    // 3. Flakiness penalty: if both passes and failures exist, it's flaky
    const flaky = d.flakyTests.value.has(t.name) ? 0 : 1

    // Composite: reliability 50%, flakiness 30%, consistency 20%
    const raw = reliability * 0.5 + flaky * 0.3 + consistency * 0.2

    // Apply confidence discount for tests with little history
    const score = Math.round(raw * confidence * 100)
    m.set(t.name, score)
  }
  return m
})

const depClusters = computed(() => {
  let tests = topN.value > 0 ? d.filteredTests.value.slice(0, topN.value) : d.filteredTests.value
  // Handle stability sort locally (not a TestEntry property)
  if (d.sortKey.value === 'stability') {
    const dir = d.sortDir.value === 'asc' ? 1 : -1
    tests = [...tests].sort((a, b) => dir * ((stabilityScores.value.get(a.name) ?? 0) - (stabilityScores.value.get(b.name) ?? 0)))
  }
  if (!groupByDep.value && !groupByPkg.value) return [{ label: '', tests }]
  const clusterMap = new Map<string, TestEntry[]>()
  for (const t of tests) {
    const label = groupByPkg.value ? pkgGroupLabel(t) : depClusterLabel(t)
    const arr = clusterMap.get(label) ?? []
    arr.push(t)
    clusterMap.set(label, arr)
  }
  // Sort clusters: by min rank of their first test
  return [...clusterMap.entries()]
    .sort((a, b) => (a[1][0]?.rank ?? 999) - (b[1][0]?.rank ?? 999))
    .map(([label, tests]) => ({ label, tests }))
})

// Top-N limiter for overview table
const TOP_N_OPTIONS = [10, 25, 50, 0] // 0 = All
const topN = ref(parseInt(localStorage.getItem('overviewTopN') || '0', 10))
function setTopN(n: number) {
  topN.value = n
  localStorage.setItem('overviewTopN', String(n))
}

// Reset showAllDeps when test changes
watch(() => d.selectedTest.value, () => { showAllDeps.value = false })

// Display name: abbreviated or full
function dn(name: string): string {
  return shortNames.value ? sn(name) : name
}

const hasPFail = d.dd.ml?.hasPredictions ?? false

// Position context: 2 neighbors above and 2 below the selected test in full list
const posNeighbors = computed(() => {
  const sel = d.selectedTest.value
  if (!sel) return []
  const idx = d.tests.findIndex(t => t.name === sel.name)
  if (idx < 0) return [sel]
  const start = Math.max(0, idx - 2)
  const end = Math.min(d.tests.length - 1, idx + 2)
  return d.tests.slice(start, end + 1)
})

// Rank trend: compare avg position in older half vs newer half of runs (d.runs oldest-first)
const rankTrend = computed(() => {
  const t = d.selectedTest.value
  if (!t || d.runs.length < 4) return null
  const positions = d.runs.map(r => {
    if (!r.outcomes?.length) return null
    const sorted = [...r.outcomes].sort((a, b) => computeScore(b, d.dd.weights, d.origSCB) - computeScore(a, d.dd.weights, d.origSCB))
    const idx = sorted.findIndex(o => o.testClass === t.name)
    return idx >= 0 ? idx + 1 : null
  }).filter(p => p !== null) as number[]
  if (positions.length < 4) return null
  const half = Math.floor(positions.length / 2)
  // positions[0]=oldest (d.runs is oldest-first); slice(0,half)=early, slice(-half)=recent
  const recent = positions.slice(-half).reduce((s, v) => s + v, 0) / half
  const early = positions.slice(0, half).reduce((s, v) => s + v, 0) / half
  const delta = Math.round(early - recent) // positive = rank number went down = improving
  if (Math.abs(delta) < 2) return { dir: 'stable' as const, delta: 0, early: Math.round(early), late: Math.round(recent) }
  return { dir: delta > 0 ? 'improving' as const : 'worsening' as const, delta, early: Math.round(early), late: Math.round(recent) }
})

const rankTrendTip = computed(() => {
  if (!rankTrend.value) return ''
  const rt = rankTrend.value
  const suffix = rt.dir === 'improving' ? ' (moving earlier — good)' : rt.dir === 'worsening' ? ' (moving later — deprioritized)' : ' (stable)'
  return `Rank trend over ${d.runs.length} runs: avg position ${rt.early} → ${rt.late}${suffix}`
})

// Plain-English explanation of why this test is ranked where it is
const whyRanked = computed(() => {
  const t = d.selectedTest.value
  if (!t) return ''
  const comps = d.scoreComps.value.filter(c => c.value !== 0)
  if (!comps.length) return `Ranked #${t.rank} with score ${t.score} (no significant score contributors).`
  // Sort by absolute value descending, take top 3
  const top = [...comps].sort((a, b) => Math.abs(b.value) - Math.abs(a.value)).slice(0, 3)
  const parts = top.map(c => {
    if (c.label === 'Failures') return `recent fail history (score ${t.failScore.toFixed(1)})`
    if (c.label === 'Dep Overlap') return `${t.depOverlap} changed-class dep${t.depOverlap !== 1 ? 's' : ''}`
    if (c.label === 'Changed') return `modified test source`
    if (c.label === 'New') return `new test (first run)`
    if (c.label === 'Speed+') return `fast (${t.duration >= 0 ? (t.duration).toFixed(0) + 'ms' : 'unknown'})`
    if (c.label === 'Speed-') return `slow (${t.duration >= 0 ? (t.duration).toFixed(0) + 'ms' : 'unknown'})`
    if (c.label === 'Static') return `static field overlap`
    if (c.label === 'Set-cover') return `coverage set-cover bonus`
    if (c.label === 'Complexity') return `${t.complexityOverlap} complex-change overlap${t.complexityOverlap !== 1 ? 's' : ''}`
    return c.explanation || c.label
  })
  const mainReason = parts[0]
  const others = parts.slice(1)
  const othersStr = others.length ? ` + ${others.join(', ')}` : ''
  return `Ranked #${t.rank} mainly due to ${mainReason}${othersStr}.`
})

// Per-run position (rank) for the selected test
const testRunPositions = computed(() => {
  const t = d.selectedTest.value
  if (!t || !d.runs.length) return []
  return d.runs.map((r, i) => {
    const o = (r.outcomes || []).find(o => o.testClass === t.name)
    if (!o) return { runIdx: i, ts: r.timestamp, present: false, failed: false, rank: null, totalTests: r.totalTests }
    const sorted = [...(r.outcomes || [])].sort((a, b) => b.score - a.score)
    const rank = sorted.findIndex(x => x.testClass === t.name) + 1
    return { runIdx: i, ts: r.timestamp, present: true, failed: o.failed, rank, totalTests: r.totalTests }
  })
})

// Top-5 tests sharing most dep overlap with the selected test
const similarTests = computed(() => {
  const t = d.selectedTest.value
  if (!t || !t.deps || t.deps.length === 0) return []
  const myDeps = new Set(t.deps)
  return d.tests
    .filter(other => other.name !== t.name && other.deps && other.deps.length > 0)
    .map(other => {
      const overlap = other.deps!.filter(d => myDeps.has(d)).length
      return { test: other, overlap, pct: Math.round(overlap / myDeps.size * 100) }
    })
    .filter(r => r.overlap > 0)
    .sort((a, b) => b.overlap - a.overlap)
    .slice(0, 6)
})

// Map: dep class → number of tests that cover it (for "hot" highlighting)
const hotDepMap = computed(() => {
  const m = new Map<string, number>()
  for (const t of d.tests) {
    for (const dep of (t.deps || [])) {
      m.set(dep, (m.get(dep) ?? 0) + 1)
    }
  }
  return m
})

// Threshold: a dep is "hot" if it's covered by >15% of all tests (min 3)
const hotDepThreshold = computed(() => Math.max(3, Math.ceil(d.tests.length * 0.15)))

// Coverage siblings: other tests that share ≥1 source class with the selected test
// Returns top-N sorted by shared class count, with per-class breakdown
const coverageSiblings = computed(() => {
  const t = d.selectedTest.value
  if (!t || !d.hasCoverage || !d.dd.coverage?.classes?.length || !t.deps?.length) return []
  const myDepSet = new Set(t.deps)
  // For each other test, count and list which classes they share with selected test
  const sibMap = new Map<string, { name: string; shared: string[] }>()
  for (const cls of d.dd.coverage.classes) {
    if (!myDepSet.has(cls.name)) continue
    for (const testName of (cls.tests || [])) {
      if (testName === t.name) continue
      const entry = sibMap.get(testName) ?? { name: testName, shared: [] }
      entry.shared.push(cls.name)
      sibMap.set(testName, entry)
    }
  }
  return [...sibMap.values()]
    .sort((a, b) => b.shared.length - a.shared.length)
    .slice(0, 8)
    .map(s => ({
      ...s,
      test: d.tests.find(t2 => t2.name === s.name),
      pct: Math.round((s.shared.length / t.deps.length) * 100)
    }))
    .filter(s => s.test)
})

const multiStats = computed(() => {
  const tests = d.selectedTestObjects.value
  if (tests.length < 2) return null
  const allDeps = tests.map(t => new Set(t.deps || []))
  const unionDeps = new Set(allDeps.flatMap(s => [...s]))
  let sharedDeps = allDeps[0]
  for (let i = 1; i < allDeps.length; i++) sharedDeps = new Set([...sharedDeps].filter(d => allDeps[i].has(d)))
  const totalDur = tests.reduce((s, t) => t.duration >= 0 ? s + t.duration : s, 0)
  const failCount = tests.filter(t => t.failScore > 0).length
  const flakyCount = tests.filter(t => d.flakyTests.value.has(t.name)).length
  const maxS = Math.max(...tests.map(t => Math.abs(t.score)), 1)
  return { unionDeps: unionDeps.size, sharedDeps: sharedDeps.size, totalDur, failCount, flakyCount, maxS }
})

const maxScore = computed(() => {
  if (!d.tests.length) return 1
  return Math.max(...d.tests.map(t => Math.abs(t.score)), 1)
})

// Returns [fail, dep, changed/new, speed, static] contribution widths (px, out of 28) for mini stacked bar
function scoreStackedBar(t: import('../types').TestEntry): { w: number; color: string }[] {
  const w = d.dd.weights
  const fail = t.failScore > 0 ? Math.min(Math.ceil(t.failScore), w.maxFailure) : 0
  const dep = t.depOverlap > 0 && t.depTotal > 0 ? Math.min(Math.ceil((t.depOverlap / Math.sqrt(t.depTotal)) * w.depOverlap), w.depOverlap) : 0
  const chg = t.isChanged ? w.changedTest : t.isNew ? w.newTest : 0
  const spd = t.speedRatio < 0 ? Math.round(Math.abs(t.speedRatio) * w.speed) : 0
  const stat = t.hasStaticFieldOverlap ? Math.round(w.staticFieldBonus ?? 0) : 0
  const total = fail + dep + chg + spd + stat
  if (total <= 0) return []
  const segs = [
    { v: fail, color: '#ef4444' },
    { v: dep,  color: '#3b82f6' },
    { v: chg,  color: '#eab308' },
    { v: spd,  color: '#22c55e' },
    { v: stat, color: '#a855f7' },
  ].filter(s => s.v > 0)
  const TOTAL_W = 28
  let used = 0
  return segs.map((s, i) => {
    const w = i === segs.length - 1 ? TOTAL_W - used : Math.max(1, Math.round((s.v / total) * TOTAL_W))
    used += w
    return { w, color: s.color }
  })
}

const medianDuration = computed(() => {
  const durs = d.tests.filter(t => t.duration >= 0).map(t => t.duration).sort((a, b) => a - b)
  if (!durs.length) return -1
  const mid = Math.floor(durs.length / 2)
  return durs.length % 2 ? durs[mid] : (durs[mid - 1] + durs[mid]) / 2
})

const totalSuiteMs = computed(() =>
  d.tests.filter(t => t.duration >= 0).reduce((s, t) => s + t.duration, 0)
)

const maxDuration = computed(() => {
  const durs = d.tests.filter(t => t.duration >= 0).map(t => t.duration)
  return durs.length ? Math.max(...durs) : 1
})

const filteredSuiteMs = computed(() =>
  d.filteredTests.value.filter(t => t.duration >= 0).reduce((s, t) => s + t.duration, 0)
)

const DUR_BUCKETS = [
  { key: 'dur-0', label: '<10ms',    min: 0,    max: 10,   color: 'var(--green)' },
  { key: 'dur-1', label: '10-100ms', min: 10,   max: 100,  color: 'var(--accent-light)' },
  { key: 'dur-2', label: '100ms-1s', min: 100,  max: 1000, color: 'var(--yellow)' },
  { key: 'dur-3', label: '1-5s',     min: 1000, max: 5000, color: 'var(--orange)' },
  { key: 'dur-4', label: '>5s',      min: 5000, max: Infinity, color: 'var(--red)' },
]

const durHistogram = computed(() => {
  const withDur = d.tests.filter(t => t.duration >= 0)
  const counts = DUR_BUCKETS.map(b => withDur.filter(t => t.duration >= b.min && t.duration < b.max).length)
  const maxCount = Math.max(...counts, 1)
  return DUR_BUCKETS.map((b, i) => ({ ...b, count: counts[i], pct: Math.round(counts[i] / withDur.length * 100), barH: Math.max(2, Math.round(counts[i] / maxCount * 32)) }))
})

// Stability distribution for health micro-dashboard
const stabilityBuckets = computed(() => {
  if (!stabilityScores.value.size) return []
  const buckets = [
    { label: 'High (76-100)', color: 'var(--green)', min: 76, max: 100, count: 0 },
    { label: 'Medium (51-75)', color: 'var(--yellow)', min: 51, max: 75, count: 0 },
    { label: 'Low (26-50)', color: 'var(--orange)', min: 26, max: 50, count: 0 },
    { label: 'Critical (0-25)', color: 'var(--red)', min: 0, max: 25, count: 0 },
  ]
  for (const score of stabilityScores.value.values()) {
    const b = buckets.find(b => score >= b.min && score <= b.max)
    if (b) b.count++
  }
  const total = d.tests.length
  return buckets.map(b => ({ ...b, pct: total > 0 ? Math.round(b.count / total * 100) : 0 }))
})

const unstableCount = computed(() => {
  let n = 0
  for (const score of stabilityScores.value.values()) if (score < 50) n++
  return n
})

const avgStability = computed(() => {
  if (!stabilityScores.value.size) return 0
  let sum = 0
  for (const score of stabilityScores.value.values()) sum += score
  return Math.round(sum / stabilityScores.value.size)
})

// Suite time budget: total suite time and breakdown by speed tier
const suiteBudget = computed(() => {
  const allWithDur = d.tests.filter(t => t.duration >= 0)
  if (!allWithDur.length) return null
  const totalMs = allWithDur.reduce((s, t) => s + t.duration, 0)
  const slow = allWithDur.filter(t => t.isSlow)
  const fast = allWithDur.filter(t => t.isFast)
  const medium = allWithDur.filter(t => !t.isSlow && !t.isFast)
  const slowMs = slow.reduce((s, t) => s + t.duration, 0)
  const fastMs = fast.reduce((s, t) => s + t.duration, 0)
  const mediumMs = medium.reduce((s, t) => s + t.duration, 0)
  // Top-N simulation: how much time saved by removing N slowest tests?
  const sortedByDur = [...allWithDur].sort((a, b) => b.duration - a.duration)
  const top5Ms = sortedByDur.slice(0, 5).reduce((s, t) => s + t.duration, 0)
  const top10Ms = sortedByDur.slice(0, 10).reduce((s, t) => s + t.duration, 0)
  return {
    totalMs, slowMs, fastMs, mediumMs,
    slowPct: Math.round(slowMs / totalMs * 100),
    fastPct: Math.round(fastMs / totalMs * 100),
    medPct: Math.round(mediumMs / totalMs * 100),
    slowCount: slow.length, fastCount: fast.length, medCount: medium.length,
    top5Ms, top10Ms,
    top5Pct: Math.round(top5Ms / totalMs * 100),
    top10Pct: Math.round(top10Ms / totalMs * 100),
  }
})

const lastRunAlert = computed(() => {
  const diff = d.runDiff.value
  if (!diff.length) return null
  const newlyFailed = diff.filter(e => e.status === 'newly-failed')
  const recovered = diff.filter(e => e.status === 'recovered')
  const regressed = diff.filter(e => e.status === 'regressed')
  if (!newlyFailed.length && !recovered.length && !regressed.length) return null
  return { newlyFailed, recovered, regressed }
})

function scoreTip(name: string): string {
  return d.getScoreBreakdown(name, 'orig') + '\n\nClick to open detailed score modal'
}

// Tiny rank-trend sparkline as SVG path string
function rankSparkSvg(name: string): string | null {
  const ranks = d.testRankHistoryMap.value.get(name)
  if (!ranks || ranks.length < 2) return null
  const W = 40, H = 14
  const maxR = Math.max(...ranks)
  const minR = Math.min(...ranks)
  const range = Math.max(maxR - minR, 1)
  const pts = ranks.map((r, i) => {
    const x = (i / (ranks.length - 1)) * W
    const y = H - ((maxR - r) / range) * (H - 2) - 1
    return `${x.toFixed(1)},${y.toFixed(1)}`
  })
  // Color: first vs last rank (lower rank = better = earlier in run order)
  const trend = ranks[ranks.length - 1] - ranks[0]
  const color = trend < -1 ? '#4ade80' : trend > 1 ? '#f87171' : '#64748b'
  return `<svg width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" style="display:block;overflow:visible"><polyline points="${pts.join(' ')}" fill="none" stroke="${color}" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>`
}

// Lazy score history map: name → last 8 scores (oldest→newest)
const scoreHistoryMap = computed(() => {
  const m = new Map<string, number[]>()
  for (const r of d.runs) {
    for (const o of (r.outcomes || [])) {
      const arr = m.get(o.testClass) ?? []
      arr.push(o.score)
      m.set(o.testClass, arr)
    }
  }
  return m
})

// Confidence: fraction of runs where this test appeared (0–100%)
// Tests seen in all runs = 100%, brand-new tests = low confidence
const confidenceMap = computed(() => {
  const m = new Map<string, number>()
  if (!d.runs.length) return m
  const counts = new Map<string, number>()
  for (const r of d.runs) {
    const seen = new Set((r.outcomes || []).map(o => o.testClass))
    for (const name of d.tests.map(t => t.name)) {
      if (seen.has(name)) counts.set(name, (counts.get(name) ?? 0) + 1)
    }
  }
  for (const t of d.tests) {
    m.set(t.name, Math.round(((counts.get(t.name) ?? 0) / d.runs.length) * 100))
  }
  return m
})

// First-seen: for each test, which run index (0-based, oldest-first) it first appeared in
const firstSeenMap = computed(() => {
  const m = new Map<string, number>() // name → oldest-first run index when first seen
  if (!d.runs.length) return m
  // Iterate oldest to newest so the first occurrence is the actual first-seen
  for (let i = 0; i < d.runs.length; i++) {
    for (const o of (d.runs[i].outcomes || [])) {
      if (!m.has(o.testClass)) m.set(o.testClass, i)
    }
  }
  return m
})

// First-seen info for the selected test
const selectedFirstSeen = computed(() => {
  const t = d.selectedTest.value
  if (!t || d.runs.length < 2) return null
  const idx = firstSeenMap.value.get(t.name) // oldest-first index
  if (idx === undefined) return null
  const totalRuns = d.runs.length
  const isRecent = idx >= totalRuns - 2 // seen within last 2 runs
  const runNum = totalRuns - idx
  const ts = d.runs[idx]?.timestamp
  return { runNum, totalRuns, isRecent, ts }
})

// Score sparkline SVG (higher score = higher bar = better)
function scoreSparkSvg(name: string): string | null {
  const scores = scoreHistoryMap.value.get(name)
  if (!scores || scores.length < 2) return null
  const last = scores.slice(-8) // newest 8 runs, oldest-left for sparkline
  const W = 40, H = 14
  const maxS = Math.max(...last, 1)
  const minS = Math.min(...last, 0)
  const range = Math.max(maxS - minS, 1)
  const pts = last.map((s, i) => {
    const x = (i / (last.length - 1)) * W
    const y = H - ((s - minS) / range) * (H - 2) - 1
    return `${x.toFixed(1)},${y.toFixed(1)}`
  })
  const trend = last[last.length - 1] - last[0]
  const color = trend > 1 ? '#4ade80' : trend < -1 ? '#f87171' : '#64748b'
  return `<svg width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" style="display:block;overflow:visible"><polyline points="${pts.join(' ')}" fill="none" stroke="${color}" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>`
}

function initDetailCharts(t: TestEntry) {
  try {
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
    const chronRuns = d.runs // oldest-first for chronological left→right charts
    const labels = chronRuns.map(r => fmtTime(r.timestamp))
    const failData = chronRuns.map(r => {
      const o = (r.outcomes || []).find(o => o.testClass === t.name)
      if (!o) return null
      return o.failed ? 1 : 0
    })
    const ema = t.duration >= 0 ? t.duration : null
    mkChart('hd-main', {
      type: 'bar', data: { labels, datasets: [
        { label: 'failed', data: failData.map(v => v === 1 ? 1 : v === 0 ? 0.15 : null),
          backgroundColor: failData.map(v => v === 1 ? 'rgba(239,68,68,.75)' : v === 0 ? 'rgba(34,197,94,.45)' : 'rgba(71,85,105,.2)'),
          borderWidth: 0, spanGaps: false },
      ] }, options: {
        ...chartOpts(),
        plugins: { ...(chartOpts() as any).plugins, legend: { display: false } },
        scales: {
          x: { ticks: { color: '#8899aa', font: { size: 9 }, maxRotation: 30 }, grid: { color: 'rgba(71,85,105,.25)' } },
          y: { display: false, min: 0, max: 1.2 },
        },
      },
    })
  }
  if (d.runs.length > 1) {
    const chronRuns2 = d.runs // oldest-first for chronological left→right charts
    const labels = chronRuns2.map(r => fmtTime(r.timestamp))
    const scores = chronRuns2.map(r => {
      const o = (r.outcomes || []).find(o => o.testClass === t.name)
      return o ? computeScore(o, d.dd.weights, d.origSCB) : null
    })
    mkChart('hs-main', {
      type: 'line', data: { labels, datasets: [
        { label: 'Score', data: scores, borderColor: '#6366f1', backgroundColor: 'rgba(99,102,241,.1)', fill: true, tension: 0.3, pointRadius: 3, pointHoverRadius: 5, spanGaps: true },
      ] }, options: chartOpts(),
    })
    const positions = chronRuns2.map(r => {
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
  } catch (e) { console.error('[dashboard] Detail charts failed:', e) }
}

watch(() => d.selectedTest.value, (newVal) => {
  if (d.activeTab.value === 'tests' && newVal && d.selectedTests.value.size <= 1) {
    nextTick(() => nextTick(() => initDetailCharts(newVal)))
  }
})
watch(() => d.activeTab.value, (tab) => {
  if (tab === 'tests' && d.selectedTest.value && d.selectedTests.value.size <= 1) {
    nextTick(() => nextTick(() => initDetailCharts(d.selectedTest.value!)))
  }
})
watch(() => d.lw, () => {
  if (d.selectedTest.value && d.activeTab.value === 'tests') {
    nextTick(() => nextTick(() => initDetailCharts(d.selectedTest.value!)))
  }
}, { deep: true })

// Minimap scroll navigator
const tableScrollEl = ref<HTMLElement | null>(null)
const minimapCanvas = ref<HTMLCanvasElement | null>(null)
const minimapScrollRatio = ref(0) // 0–1 current scroll position

const minimapColors = computed(() => {
  const tests = topN.value > 0 ? d.filteredTests.value.slice(0, topN.value) : d.filteredTests.value
  return tests.map(t => {
    if (t.failScore > 0) return '#ef4444'
    if (t.isNew) return '#4ade80'
    if (t.isChanged) return '#fbbf24'
    if (t.hasStaticFieldOverlap) return '#a855f7'
    if (t.depOverlap > 0) return '#3b82f6'
    if (t.isSlow) return '#fb923c'
    return '#374151'
  })
})

function drawMinimap() {
  const canvas = minimapCanvas.value
  if (!canvas) return
  const colors = minimapColors.value
  const n = colors.length
  if (!n) return
  const W = canvas.width
  const H = canvas.height
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.clearRect(0, 0, W, H)
  // Draw test bands
  for (let i = 0; i < n; i++) {
    const y = (i / n) * H
    const h = Math.max(1, H / n)
    ctx.fillStyle = colors[i]
    ctx.globalAlpha = 0.85
    ctx.fillRect(0, y, W, h)
  }
  ctx.globalAlpha = 1
  // Draw selected test marker
  const sel = d.selectedTest.value
  const tests = topN.value > 0 ? d.filteredTests.value.slice(0, topN.value) : d.filteredTests.value
  if (sel) {
    const idx = tests.findIndex(t => t.name === sel.name)
    if (idx >= 0) {
      const y = (idx / n) * H
      ctx.fillStyle = '#ffffff'
      ctx.globalAlpha = 0.9
      ctx.fillRect(0, Math.max(0, y - 1), W, 3)
      ctx.globalAlpha = 1
    }
  }
  // Draw viewport indicator
  const el = tableScrollEl.value
  if (el && el.scrollHeight > el.clientHeight) {
    const ratio = el.scrollTop / (el.scrollHeight - el.clientHeight)
    const viewH = (el.clientHeight / el.scrollHeight) * H
    const viewY = ratio * (H - viewH)
    ctx.strokeStyle = 'rgba(255,255,255,0.4)'
    ctx.lineWidth = 1
    ctx.strokeRect(0.5, viewY, W - 1, viewH)
    ctx.fillStyle = 'rgba(255,255,255,0.06)'
    ctx.fillRect(1, viewY, W - 2, viewH)
  }
}

function onMinimapClick(e: MouseEvent) {
  const canvas = minimapCanvas.value
  const el = tableScrollEl.value
  if (!canvas || !el) return
  const rect = canvas.getBoundingClientRect()
  const ratio = (e.clientY - rect.top) / rect.height
  el.scrollTop = ratio * (el.scrollHeight - el.clientHeight)
}

function onTableScroll() {
  const el = tableScrollEl.value
  if (!el) return
  minimapScrollRatio.value = el.scrollHeight > el.clientHeight
    ? el.scrollTop / (el.scrollHeight - el.clientHeight) : 0
  drawMinimap()
}

watch([minimapColors, () => d.selectedTest.value, minimapScrollRatio], () => {
  nextTick(drawMinimap)
})

onMounted(() => nextTick(drawMinimap))

watch(() => d.activeTab.value, (tab) => {
  if (tab === 'tests') nextTick(drawMinimap)
})

// Hover preview popover
const hoverTest = ref<TestEntry | null>(null)
const hoverPos = ref({ top: 0, left: 0, right: false })
let hoverTimer: ReturnType<typeof setTimeout> | null = null

function showRowPreview(t: TestEntry, e: MouseEvent) {
  hoverTimer && clearTimeout(hoverTimer)
  const target = e.currentTarget as HTMLElement
  hoverTimer = setTimeout(() => {
    if (!target) return
    const rect = target.getBoundingClientRect()
    const winW = window.innerWidth
    const popW = 240
    const right = rect.right + popW + 8 > winW
    hoverPos.value = {
      top: rect.top + rect.height / 2,
      left: right ? rect.left - popW - 4 : rect.right + 4,
      right,
    }
    hoverTest.value = t
  }, 280)
}

function hideRowPreview() {
  hoverTimer && clearTimeout(hoverTimer)
  hoverTimer = null
  hoverTest.value = null
}

function previewScoreBars(t: TestEntry) {
  const w = d.dd.weights
  const total = Math.max(t.score, 1)
  const fail = t.failScore > 0 ? Math.min(Math.ceil(t.failScore), w.maxFailure) : 0
  const dep = t.depOverlap > 0 && t.depTotal > 0 ? Math.min(Math.ceil((t.depOverlap / Math.sqrt(t.depTotal)) * w.depOverlap), w.depOverlap) : 0
  const chg = t.isChanged ? w.changedTest : t.isNew ? w.newTest : 0
  const spd = t.isSlow ? 0 : Math.round(w.speed * 0.5)
  const stf = t.hasStaticFieldOverlap ? w.staticFieldBonus : 0
  return [
    { label: 'Fail', w: Math.round((fail / total) * 100), c: '#ef4444' },
    { label: 'Dep', w: Math.round((dep / total) * 100), c: '#3b82f6' },
    { label: 'Chg', w: Math.round((chg / total) * 100), c: '#eab308' },
    { label: 'Spd', w: Math.round((spd / total) * 100), c: '#22c55e' },
    { label: 'Stf', w: Math.round((stf / total) * 100), c: '#a855f7' },
  ].filter(s => s.w > 0)
}
</script>

<template>
  <div v-if="d.activeTab.value === 'tests'">

    <!-- No selection: overview -->
    <div v-if="d.selectedTests.value.size === 0">
      <div style="display:flex;flex-wrap:wrap;gap:8px;margin-bottom:10px">
        <div class="kpi tests-overview__kpi">
          <div class="tests-overview__kpi-label" title="Total tests being ordered">Total Tests</div>
          <div class="tests-overview__kpi-value" style="color:var(--accent-light)">{{ d.tests.length }}</div>
        </div>
        <div class="kpi tests-overview__kpi tests-overview__kpi--filter" :class="{ 'tests-overview__kpi--active': d.badgeFilter.value === 'changed' }" @click="d.setBadgeFilter('changed')" title="Tests with modified source — get a +changedTest score bonus. Click to filter.">
          <div class="tests-overview__kpi-label">Changed</div>
          <div class="tests-overview__kpi-value" style="color:var(--yellow)">{{ d.tests.filter(t => t.isChanged).length }}</div>
        </div>
        <div class="kpi tests-overview__kpi tests-overview__kpi--filter" :class="{ 'tests-overview__kpi--active': d.badgeFilter.value === 'new' }" @click="d.setBadgeFilter('new')" title="Tests added since last run — get a +newTest score bonus. Click to filter.">
          <div class="tests-overview__kpi-label">New</div>
          <div class="tests-overview__kpi-value" style="color:var(--green)">{{ d.tests.filter(t => t.isNew).length }}</div>
        </div>
        <div class="kpi tests-overview__kpi tests-overview__kpi--filter" :class="{ 'tests-overview__kpi--active': d.badgeFilter.value === 'failing' }" @click="d.setBadgeFilter('failing')" title="Tests with recent failure history (EMA-decayed failScore > 0). Click to filter.">
          <div class="tests-overview__kpi-label">Fail History</div>
          <div class="tests-overview__kpi-value" style="color:var(--red)">{{ d.tests.filter(t => t.failScore > 0).length }}</div>
        </div>
        <div v-if="d.flakyTests.value.size > 0" class="kpi tests-overview__kpi tests-overview__kpi--filter" :class="{ 'tests-overview__kpi--active': d.badgeFilter.value === 'flaky' }" @click="d.setBadgeFilter('flaky')" title="Tests that have both passes and failures across recorded runs — intermittently failing. Click to filter.">
          <div class="tests-overview__kpi-label">Flaky</div>
          <div class="tests-overview__kpi-value" style="color:var(--orange)">{{ d.flakyTests.value.size }}</div>
        </div>
        <div class="kpi tests-overview__kpi tests-overview__kpi--filter" :class="{ 'tests-overview__kpi--active': d.badgeFilter.value === 'static' }" @click="d.setBadgeFilter('static')" title="Tests sharing static field accesses with changed classes — indirect dependency signal. Click to filter.">
          <div class="tests-overview__kpi-label">Static Overlap</div>
          <div class="tests-overview__kpi-value" style="color:var(--purple)">{{ d.tests.filter(t => t.hasStaticFieldOverlap).length }}</div>
        </div>
        <div class="kpi tests-overview__kpi tests-overview__kpi--filter" :class="{ 'tests-overview__kpi--active': d.badgeFilter.value === 'depoverlap' }" @click="d.setBadgeFilter('depoverlap')" title="Tests with ≥1 dependency on changed source classes — directly affected by changes. Click to filter.">
          <div class="tests-overview__kpi-label">Dep Overlap</div>
          <div class="tests-overview__kpi-value" style="color:var(--cyan)">{{ d.tests.filter(t => t.depOverlap > 0).length }}</div>
        </div>
        <div class="kpi tests-overview__kpi tests-overview__kpi--filter" :class="{ 'tests-overview__kpi--active': d.badgeFilter.value === 'slow' }" @click="d.setBadgeFilter('slow')" title="Tests slower than median duration — they get a speed penalty in scoring. Click to filter.">
          <div class="tests-overview__kpi-label">Slow</div>
          <div class="tests-overview__kpi-value" style="color:var(--orange)">{{ d.tests.filter(t => t.isSlow).length }}</div>
        </div>
        <div class="kpi tests-overview__kpi tests-overview__kpi--filter" :class="{ 'tests-overview__kpi--active': d.badgeFilter.value === 'fast' }" @click="d.setBadgeFilter('fast')" title="Tests faster than median duration — they get a speed bonus in scoring. Click to filter.">
          <div class="tests-overview__kpi-label">Fast</div>
          <div class="tests-overview__kpi-value" style="color:var(--green)">{{ d.tests.filter(t => t.isFast).length }}</div>
        </div>
        <div v-if="d.tests.filter(t => t.durationVariance >= (d.dd.config.emaVarianceThreshold ?? 0.5)).length > 0"
          class="kpi tests-overview__kpi tests-overview__kpi--filter"
          :class="{ 'tests-overview__kpi--active': d.badgeFilter.value === 'variance' }"
          @click="d.setBadgeFilter('variance')"
          :title="'Tests with high EMA duration variance (≥' + (d.dd.config.emaVarianceThreshold ?? 0.5) + ') — inconsistent timing, may indicate flakiness or JVM warmup effects. Click to filter.'"
        >
          <div class="tests-overview__kpi-label">High Variance</div>
          <div class="tests-overview__kpi-value" style="color:var(--yellow)">{{ d.tests.filter(t => t.durationVariance >= (d.dd.config.emaVarianceThreshold ?? 0.5)).length }}</div>
        </div>
        <div v-if="medianDuration >= 0" class="kpi tests-overview__kpi" :title="'Median test duration (EMA). ' + d.tests.filter(t => t.duration >= 0).length + ' of ' + d.tests.length + ' tests have duration data.'">
          <div class="tests-overview__kpi-label">Median Dur</div>
          <div class="tests-overview__kpi-value" style="color:var(--text-sec)">{{ fmtDur(medianDuration) }}</div>
        </div>
        <div v-if="totalSuiteMs > 0" class="kpi tests-overview__kpi" :title="'Estimated total suite run time (sum of all EMA durations). Actual wall time depends on parallelism.'">
          <div class="tests-overview__kpi-label">Suite Time</div>
          <div class="tests-overview__kpi-value" style="color:var(--text-sec)">{{ fmtDur(totalSuiteMs) }}</div>
        </div>
        <div v-if="d.hasCoverage" class="kpi tests-overview__kpi tests-overview__kpi--filter" @click="d.setTab('analytics')" title="Source-class coverage — click to see Coverage in Analytics tab">
          <div class="tests-overview__kpi-label">Src Coverage</div>
          <div class="tests-overview__kpi-value" :style="{ color: d.covPercent.value >= 80 ? 'var(--green)' : d.covPercent.value >= 50 ? 'var(--yellow)' : 'var(--red)' }">{{ d.covPercent.value }}%</div>
        </div>
        <div v-if="d.badgeFilter.value" class="tests-overview__filter-clear" @click="d.setBadgeFilter(null)" title="Clear filter (Esc)">
          ✕ {{ d.badgeFilter.value }}
        </div>
      </div>
      <!-- Last run changes alert -->
      <div v-if="lastRunAlert" class="tests__run-alert" @click="d.setTab('analytics')" role="button" tabindex="0" title="Click to open Analytics tab for full run comparison">
        <span class="tests__run-alert-icon">⚡</span>
        <span class="tests__run-alert-text">Last run changes:</span>
        <span v-if="lastRunAlert.newlyFailed.length" class="tests__run-alert-pill tests__run-alert-pill--fail">✕ {{ lastRunAlert.newlyFailed.length }} newly failed</span>
        <span v-if="lastRunAlert.recovered.length" class="tests__run-alert-pill tests__run-alert-pill--pass">✓ {{ lastRunAlert.recovered.length }} recovered</span>
        <span v-if="lastRunAlert.regressed.length" class="tests__run-alert-pill tests__run-alert-pill--warn">↓ {{ lastRunAlert.regressed.length }} rank drop</span>
        <span class="tests__run-alert-link">→ View in Analytics</span>
      </div>
      <!-- Run order preview bar -->
      <div v-if="d.tests.length >= 5" class="run-preview">
        <span class="run-preview__label">Run order:</span>
        <div
          v-for="(t, i) in d.tests.slice(0, 5)"
          :key="t.name"
          class="run-preview__item"
          :class="{
            'run-preview__item--fail': t.failScore > 0,
            'run-preview__item--flaky': d.flakyTests.value.has(t.name),
            'run-preview__item--changed': t.isChanged,
            'run-preview__item--new': t.isNew,
            'run-preview__item--selected': d.selectedTest.value?.name === t.name
          }"
          :title="t.name + '\nRank #' + (i+1) + ' · Score ' + t.score.toFixed(1) + (t.failScore > 0 ? ' · fail history' : '') + (t.isChanged ? ' · changed' : '') + (t.isNew ? ' · new' : '')"
          @click="d.selectTest(t)"
        >
          <span class="run-preview__num">{{ i + 1 }}</span>
          <span class="run-preview__name">{{ sn(t.name) }}</span>
          <span v-if="t.failScore > 0" class="run-preview__flag">✕</span>
          <span v-else-if="t.isChanged" class="run-preview__flag" style="color:var(--yellow)">✎</span>
          <span v-else-if="t.isNew" class="run-preview__flag" style="color:var(--green)">★</span>
        </div>
        <span v-if="d.tests.length > 5" class="run-preview__more">+{{ d.tests.length - 5 }} more</span>
      </div>

      <!-- Blame mode banner -->
      <div v-if="blameMode" class="blame-banner">
        <span class="blame-banner__icon">✎</span>
        <strong>Blame mode</strong> — {{ blameLinkedTests.size }} test{{ blameLinkedTests.size === 1 ? '' : 's' }} linked to {{ d.dd.changedClasses.length }} changed class{{ d.dd.changedClasses.length === 1 ? '' : 'es' }}
        <span style="color:var(--text-muted);margin-left:4px">(dep overlap, static field, or directly changed)</span>
        <button class="blame-banner__close" @click="blameMode.value = false" title="Exit blame mode (B)">✕</button>
      </div>

      <div style="display:flex;align-items:center;gap:8px;margin-bottom:6px;flex-wrap:wrap">
        <h3 style="font-size:.82rem;color:var(--text-sec);margin:0">All tests by priority <span style="font-weight:400;font-size:.7rem;color:var(--text-muted)">(click to inspect · Ctrl+click multi-select · Shift+click range)</span></h3>
        <div style="margin-left:auto;display:flex;align-items:center;gap:3px">
          <button class="tests__topn-btn" :class="{ 'tests__topn-btn--active': groupByDep }" @click="groupByDep = !groupByDep; if (groupByDep) groupByPkg = false" title="Group tests by their dominant dependency package cluster">⊞ Cluster</button>
          <button class="tests__topn-btn" :class="{ 'tests__topn-btn--active': groupByPkg }" @click="groupByPkg = !groupByPkg; if (groupByPkg) groupByDep = false" title="Group tests by their Java package (first 4 segments)">⊟ Package</button>
          <span style="font-size:.6rem;color:var(--text-muted);margin-left:4px">Show:</span>
          <button v-for="n in TOP_N_OPTIONS" :key="n" class="tests__topn-btn" :class="{ 'tests__topn-btn--active': topN === n }" @click="setTopN(n)" :title="n === 0 ? 'Show all tests' : 'Show only top ' + n + ' tests'">{{ n === 0 ? 'All' : n }}</button>
        </div>
      </div>
      <div style="display:flex;align-items:stretch;gap:3px">
        <div ref="tableScrollEl" style="overflow-x:auto;max-height:500px;overflow-y:auto;flex:1;min-width:0" @scroll="onTableScroll">
        <table>
          <thead class="tests-overview__thead">
            <tr>
              <th class="th--right th-sort" @click="d.sortBy('rank')" :class="{ 'th-sort--active': d.sortKey.value === 'rank' }" title="Sort by rank">Rank<span v-if="d.sortKey.value === 'rank'">{{ d.sortDir.value === 'asc' ? ' ↑' : ' ↓' }}</span></th>
              <th class="th--left th-sort" @click="d.sortBy('name')" :class="{ 'th-sort--active': d.sortKey.value === 'name' }" title="Sort by name">Test<span v-if="d.sortKey.value === 'name'">{{ d.sortDir.value === 'asc' ? ' ↑' : ' ↓' }}</span></th>
              <th class="th--right th-sort" @click="d.sortBy('score')" :class="{ 'th-sort--active': d.sortKey.value === 'score' }" title="Sort by score — click score cell to see breakdown">
                Score
                <span class="tests-score-legend-wrap" @click.stop>
                  <span class="tests-score-legend-icon">ⓘ</span>
                  <span class="tests-score-legend-pop">
                    <span class="tests-score-legend-row"><span class="tests-score-legend-dot" style="background:#ef4444"></span>Fail history</span>
                    <span class="tests-score-legend-row"><span class="tests-score-legend-dot" style="background:#3b82f6"></span>Dep overlap</span>
                    <span class="tests-score-legend-row"><span class="tests-score-legend-dot" style="background:#eab308"></span>Changed / New</span>
                    <span class="tests-score-legend-row"><span class="tests-score-legend-dot" style="background:#22c55e"></span>Speed bonus</span>
                    <span class="tests-score-legend-row"><span class="tests-score-legend-dot" style="background:#a855f7"></span>Static fields</span>
                  </span>
                </span>
                <span v-if="d.sortKey.value === 'score'">{{ d.sortDir.value === 'asc' ? ' ↑' : ' ↓' }}</span>
              </th>
              <th class="th--left">Flags</th>
              <th class="th--right th-sort" @click="d.sortBy('duration')" :class="{ 'th-sort--active': d.sortKey.value === 'duration' }" title="Sort by EMA-smoothed duration">Duration<span v-if="d.sortKey.value === 'duration'">{{ d.sortDir.value === 'asc' ? ' ↑' : ' ↓' }}</span></th>
              <th v-if="d.tests.some(t => t.durationVariance > 0.1)" class="th--right th-sort" @click="d.sortBy('durationVariance')" :class="{ 'th-sort--active': d.sortKey.value === 'durationVariance' }" title="Sort by EMA duration variance — how inconsistent the test's runtime is. High variance may indicate flakiness or environment sensitivity. Threshold ≥0.5 = High Variance badge.">Var<span v-if="d.sortKey.value === 'durationVariance'">{{ d.sortDir.value === 'asc' ? ' ↑' : ' ↓' }}</span></th>
              <th class="th--right th-sort" @click="d.sortBy('depTotal')" :class="{ 'th-sort--active': d.sortKey.value === 'depTotal' }" title="Sort by total dependencies / overlap with changed classes">Deps / Overlap<span v-if="d.sortKey.value === 'depTotal'">{{ d.sortDir.value === 'asc' ? ' ↑' : ' ↓' }}</span></th>
              <th v-if="d.runs.length" class="th--left" title="Pass/fail history across last 5 runs (newest right). Green = pass, red = fail, gray = absent.">History</th>
              <th v-if="d.runs.length >= 3" class="th--left" title="Rank trend across last 8 runs. Green = moving earlier (improving), red = moving later (worsening).">Trend</th>
              <th v-if="d.runs.length >= 3" class="th--left" title="Score history across recorded runs. Green = score increasing (getting prioritized more), red = decreasing.">Score ↗</th>
              <th v-if="d.runs.length >= 3" class="th--right" title="Score confidence: % of runs where this test was observed. Low = sparse data, score may be inaccurate.">Conf.</th>
              <th v-if="d.runs.length >= 3" class="th--right th-sort" @click="d.sortBy('stability')" :class="{ 'th-sort--active': d.sortKey.value === 'stability' }" title="Sort by stability score (0–100): composite of fail rate, flakiness, and duration variance. 100 = perfectly stable, never fails, consistent timing.">Stab.<span v-if="d.sortKey.value === 'stability'">{{ d.sortDir.value === 'asc' ? ' ↑' : ' ↓' }}</span></th>
              <th v-if="hasPFail" class="th--right th-sort" @click="d.sortBy('mlPFail')" :class="{ 'th-sort--active': d.sortKey.value === 'mlPFail' }" title="Sort by ML-predicted failure probability">P(fail)<span v-if="d.sortKey.value === 'mlPFail'">{{ d.sortDir.value === 'asc' ? ' ↑' : ' ↓' }}</span></th>
            </tr>
          </thead>
          <tbody>
            <template v-for="cluster in depClusters" :key="cluster.label">
              <tr v-if="(groupByDep || groupByPkg) && cluster.label" class="tests-cluster-header">
                <td colspan="20">
                  <span class="tests-cluster-header__pkg">{{ cluster.label }}</span>
                  <span class="tests-cluster-header__count">{{ cluster.tests.length }} test{{ cluster.tests.length === 1 ? '' : 's' }}</span>
                  <span v-if="groupByPkg" class="tests-cluster-header__meta">
                    avg score {{ Math.round(cluster.tests.reduce((s, t) => s + t.score, 0) / cluster.tests.length) }}
                    <template v-if="cluster.tests.filter(t => t.failScore > 0).length > 0">
                      · <span style="color:var(--red)">{{ cluster.tests.filter(t => t.failScore > 0).length }}✕</span>
                    </template>
                  </span>
                </td>
              </tr>
              <tr v-for="(t, tIdx) in cluster.tests" :key="t.name" @click="d.selectTest(t, $event)" class="tests-overview__row"
                :class="{ 'tests-overview__row--dimmed': t.score === 0 && !t.failScore && !t.isChanged && !t.isNew, 'tests-overview__row--even': tIdx % 2 === 0, 'tests-overview__row--blame': blameMode && blameLinkedTests.has(t.name), 'tests-overview__row--blame-dim': blameMode && !blameLinkedTests.has(t.name) }"
                :style="{ borderLeft: t.failScore > 0 ? '2px solid rgba(248,113,113,.5)' : t.isNew ? '2px solid rgba(74,222,128,.5)' : t.isChanged ? '2px solid rgba(251,191,36,.5)' : '2px solid transparent' }"
                @mouseenter="showRowPreview(t, $event)"
                @mouseleave="hideRowPreview()"
              >
              <td class="td--right td--dim">#{{ t.rank }}</td>
              <td class="td--name" :title="t.name"
                @mouseenter="classHover.show(t.name, $event)"
                @mousemove="classHover.move($event)"
                @mouseleave="classHover.hide()"
              >{{ dn(t.name) }}</td>
              <td class="td--right td--accent">
                <button
                  class="tests-score-btn"
                  type="button"
                  :title="scoreTip(t.name)"
                  @click.stop="d.openScoreModal(t.name, 'orig', 'Tests Overview')"
                >
                  <span v-if="t.score > 0" class="tests-score-stack">
                    <span
                      v-for="(seg, si) in scoreStackedBar(t)"
                      :key="si"
                      class="tests-score-stack__seg"
                      :style="{ width: seg.w + 'px', background: seg.color }"
                    ></span>
                  </span>
                  <span v-else-if="t.score < 0" class="tests-score-bar" :style="{ width: Math.max(3, Math.round((Math.abs(t.score) / maxScore) * 28)) + 'px', background: 'var(--red)', opacity: 0.65 }"></span>
                  <span class="tests-score-num" :style="{ color: t.score < 0 ? 'var(--red)' : undefined }">{{ t.score }}</span>
                </button>
              </td>
              <td><TestBadges :test="t" :flaky="d.flakyTests.value.has(t.name)" /></td>
              <td class="td--right td--dim tests-overview__dur-cell" :class="{ 'tests-overview__dur--slow': t.isSlow, 'tests-overview__dur--fast': t.isFast }" :title="t.duration >= 0 ? t.duration.toFixed(1) + 'ms' + (t.isSlow ? ' · slow (above median)' : t.isFast ? ' · fast (below median)' : '') : 'No duration data'">
                <span v-if="t.duration >= 0" class="tests-overview__dur-bar" :style="{ width: Math.max(2, Math.round(t.duration / maxDuration * 28)) + 'px', background: t.isSlow ? 'var(--orange)' : t.isFast ? 'var(--green)' : 'var(--accent)' }"></span>
                {{ t.duration >= 0 ? fmtDur(t.duration) : '' }}
              </td>
              <td v-if="d.tests.some(tt => tt.durationVariance > 0.1)" class="td--right td--dim td--var"
                :title="t.durationVariance > 0 && t.duration > 0 ? 'Duration variance: ±' + Math.round(Math.sqrt(t.durationVariance)) + 'ms (CV ' + Math.round(Math.sqrt(t.durationVariance) / t.duration * 100) + '%)' + (t.durationVariance >= (d.dd.config.emaVarianceThreshold ?? 0.5) ? ' — HIGH' : '') : 'No variance data'"
              >
                <span v-if="t.durationVariance > 0 && t.duration > 0">
                  <span class="td--var-bar" :style="{
                    width: Math.max(2, Math.min(24, Math.round(Math.sqrt(t.durationVariance) / t.duration * 80))) + 'px',
                    background: t.durationVariance >= (d.dd.config.emaVarianceThreshold ?? 0.5) ? 'var(--yellow)' : 'rgba(100,116,139,.5)'
                  }"></span>
                </span>
              </td>
              <td class="td--right td--dim">
                {{ t.depTotal || 0 }}<span v-if="t.depOverlap > 0" style="color:var(--cyan)"> / {{ t.depOverlap }}</span>
              </td>
              <td v-if="d.runs.length" class="td--history">
                <div class="tests-hist-strip" :title="d.testHistoryMap.value.get(t.name) ? (d.testHistoryMap.value.get(t.name)!.fail > 0 ? d.testHistoryMap.value.get(t.name)!.fail + ' failures / ' + (d.testHistoryMap.value.get(t.name)!.pass + d.testHistoryMap.value.get(t.name)!.fail) + ' runs' : 'all passed') : ''">
                  <template v-if="d.testHistoryMap.value.get(t.name)">
                    <div
                      v-for="(failed, hi) in d.testHistoryMap.value.get(t.name)!.last8"
                      :key="hi"
                      class="tests-hist-dot"
                      :class="failed ? 'tests-hist-dot--fail' : 'tests-hist-dot--pass'"
                      :title="'Run #' + (d.testHistoryMap.value.get(t.name)!.last8.length - hi) + ': ' + (failed ? 'FAILED' : 'passed')"
                    ></div>
                  </template>
                </div>
              </td>
              <td v-if="d.runs.length >= 3" class="td--history" :title="d.testRankHistoryMap.value.has(t.name) ? 'Rank trend over ' + d.testRankHistoryMap.value.get(t.name)!.length + ' runs: ' + d.testRankHistoryMap.value.get(t.name)!.join(' → ') : 'No trend data'">
                <span v-if="rankSparkSvg(t.name)" v-html="rankSparkSvg(t.name)"></span>
              </td>
              <td v-if="d.runs.length >= 3" class="td--history" :title="scoreHistoryMap.get(t.name) ? 'Score history (oldest→newest): ' + scoreHistoryMap.get(t.name)!.join(' → ') : 'No score history'">
                <span v-if="scoreSparkSvg(t.name)" v-html="scoreSparkSvg(t.name)"></span>
              </td>
              <td v-if="d.runs.length >= 3" class="td--right td--conf"
                :title="'Confidence: seen in ' + (confidenceMap.get(t.name) ?? 0) + '% of ' + d.runs.length + ' runs. Higher = more reliable score data.'"
                :style="{ color: (confidenceMap.get(t.name) ?? 0) >= 80 ? 'var(--green)' : (confidenceMap.get(t.name) ?? 0) >= 50 ? 'var(--yellow)' : 'var(--red)' }"
              >{{ confidenceMap.get(t.name) ?? 0 }}%</td>
              <td v-if="d.runs.length >= 3" class="td--right td--stab"
                :title="'Stability score: ' + (stabilityScores.get(t.name) ?? 0) + '/100 — composite of fail rate, flakiness, and duration variance. ' + (stabilityScores.get(t.name) ?? 0) >= 80 ? 'Highly stable.' : (stabilityScores.get(t.name) ?? 0) >= 50 ? 'Moderately stable.' : 'Unstable — needs attention.'"
                :style="{ color: (stabilityScores.get(t.name) ?? 0) >= 80 ? 'var(--green)' : (stabilityScores.get(t.name) ?? 0) >= 50 ? 'var(--yellow)' : 'var(--red)' }"
              >{{ stabilityScores.get(t.name) ?? 0 }}</td>
              <td v-if="hasPFail" class="td--right" :style="{ color: t.mlPFail != null && t.mlPFail > 0.5 ? 'var(--red)' : t.mlPFail != null && t.mlPFail > 0.2 ? 'var(--yellow)' : 'var(--text-muted)' }">{{ t.mlPFail != null ? (t.mlPFail * 100).toFixed(1) + '%' : '' }}</td>
            </tr>
            </template>
          </tbody>
        </table>
      </div>
      <canvas
        ref="minimapCanvas"
        class="tests-minimap"
        width="10"
        height="500"
        title="Minimap — click to jump · red=fail history · yellow=changed/new · orange=slow · blue=dep overlap · purple=static · white=selected"
        @click="onMinimapClick"
      ></canvas>
      </div>
      <!-- Duration histogram -->
      <div v-if="durHistogram.some(b => b.count > 0)" class="tests__dur-hist">
        <span class="tests__dur-hist-label">Duration:</span>
        <template v-for="b in durHistogram" :key="b.key">
          <button
            v-if="b.count > 0"
            class="tests__dur-bar-wrap"
            :class="{ 'tests__dur-bar-wrap--active': d.badgeFilter.value === b.key }"
            :title="b.label + ': ' + b.count + ' tests (' + b.pct + '%) — click to filter'"
            @click="d.setBadgeFilter(d.badgeFilter.value === b.key ? null : b.key)"
          >
            <div class="tests__dur-bar" :style="{ height: b.barH + 'px', background: b.color, opacity: d.badgeFilter.value && d.badgeFilter.value !== b.key ? 0.35 : 1 }"></div>
            <span class="tests__dur-bar-lbl">{{ b.label }}</span>
            <span class="tests__dur-bar-count">{{ b.count }}</span>
          </button>
        </template>
        <button v-if="d.badgeFilter.value?.startsWith('dur-')" class="tests__dur-clear" @click="d.setBadgeFilter(null)" title="Clear duration filter">× clear</button>
      </div>
      <!-- Suite time budget panel -->
      <div v-if="suiteBudget" class="suite-budget">
        <div class="suite-budget__total">
          <span class="suite-budget__label">Suite time:</span>
          <span class="suite-budget__value">{{ fmtDur(suiteBudget.totalMs) }}</span>
        </div>
        <div class="suite-budget__bar" title="Suite time by speed tier">
          <div class="suite-budget__seg suite-budget__seg--slow" :style="{ width: suiteBudget.slowPct + '%' }" :title="'Slow tests: ' + fmtDur(suiteBudget.slowMs) + ' (' + suiteBudget.slowPct + '%, ' + suiteBudget.slowCount + ' tests)'"></div>
          <div class="suite-budget__seg suite-budget__seg--med" :style="{ width: suiteBudget.medPct + '%' }" :title="'Medium tests: ' + fmtDur(suiteBudget.mediumMs) + ' (' + suiteBudget.medPct + '%, ' + suiteBudget.medCount + ' tests)'"></div>
          <div class="suite-budget__seg suite-budget__seg--fast" :style="{ width: suiteBudget.fastPct + '%' }" :title="'Fast tests: ' + fmtDur(suiteBudget.fastMs) + ' (' + suiteBudget.fastPct + '%, ' + suiteBudget.fastCount + ' tests)'"></div>
        </div>
        <div class="suite-budget__legend">
          <span class="suite-budget__leg suite-budget__leg--slow" :title="'Slow tests consume ' + suiteBudget.slowPct + '% of total time'">
            slow {{ fmtDur(suiteBudget.slowMs) }} ({{ suiteBudget.slowPct }}%)
          </span>
          <span class="suite-budget__leg suite-budget__leg--med">med {{ fmtDur(suiteBudget.mediumMs) }}</span>
          <span class="suite-budget__leg suite-budget__leg--fast">fast {{ fmtDur(suiteBudget.fastMs) }}</span>
        </div>
        <div v-if="suiteBudget.top5Pct > 5" class="suite-budget__sim" :title="'Removing top 5 slowest tests would save ' + fmtDur(suiteBudget.top5Ms) + ' (' + suiteBudget.top5Pct + '% of total suite time)'">
          <span class="suite-budget__sim-icon">⚡</span>
          <span>Removing top 5 slowest saves <strong>{{ fmtDur(suiteBudget.top5Ms) }}</strong> ({{ suiteBudget.top5Pct }}% of suite)</span>
          <button class="suite-budget__sim-btn" @click="d.setBadgeFilter('slow')" title="Filter to slow tests">show slow →</button>
        </div>
      </div>
      <!-- Suite health micro-dashboard -->
      <div v-if="d.runs.length >= 3 && d.tests.length" class="health-micro">
        <span class="health-micro__label">Stability:</span>
        <div class="health-micro__bars">
          <div
            v-for="bucket in stabilityBuckets"
            :key="bucket.label"
            class="health-micro__bar-seg"
            :style="{ width: bucket.pct + '%', background: bucket.color }"
            :title="bucket.label + ': ' + bucket.count + ' tests (' + bucket.pct + '%)'"
          ></div>
        </div>
        <span class="health-micro__legend">
          <span v-for="b in stabilityBuckets" :key="b.label" :style="{ color: b.color }" :title="b.label + ': ' + b.count + ' tests'">{{ b.count }}</span>
        </span>
        <span class="health-micro__sep">·</span>
        <span class="health-micro__label">Flaky:</span>
        <span class="health-micro__val" :style="{ color: d.flakyTests.value.size > 0 ? 'var(--orange)' : 'var(--green)' }">{{ d.flakyTests.value.size }}</span>
        <span class="health-micro__sep">·</span>
        <span class="health-micro__label">Unstable (Stab.&lt;50):</span>
        <span class="health-micro__val" :style="{ color: unstableCount > 0 ? 'var(--red)' : 'var(--green)' }">{{ unstableCount }}</span>
        <span class="health-micro__sep">·</span>
        <span class="health-micro__label">Avg stab.:</span>
        <span class="health-micro__val" :style="{ color: avgStability >= 80 ? 'var(--green)' : avgStability >= 50 ? 'var(--yellow)' : 'var(--red)' }">{{ avgStability }}</span>
      </div>
      <p style="color:var(--text-muted);font-size:.68rem;margin-top:8px;display:flex;align-items:center;flex-wrap:wrap;gap:8px">
        <span><kbd class="tests-kbd">j</kbd><kbd class="tests-kbd">k</kbd> navigate &nbsp;·&nbsp; <kbd class="tests-kbd">⏎</kbd> select &nbsp;·&nbsp; <kbd class="tests-kbd">/</kbd> search &nbsp;·&nbsp; <kbd class="tests-kbd">1</kbd>–<kbd class="tests-kbd">3</kbd> tabs &nbsp;·&nbsp; <kbd class="tests-kbd">Esc</kbd> clear</span>
        <!-- Sort reset pill -->
        <button
          v-if="d.sortKey.value !== 'rank'"
          class="tests__sort-reset-pill"
          :title="'Currently sorted by ' + d.sortKey.value + ' ' + (d.sortDir.value === 'asc' ? 'ascending' : 'descending') + ' — click to reset to default rank order'"
          @click="d.sortBy('rank')"
        >⊘ sorted by {{ d.sortKey.value }} {{ d.sortDir.value === 'asc' ? '↑' : '↓' }} &nbsp;×&nbsp; reset</button>
        <span v-if="d.badgeFilter.value || d.searchQ.value" style="color:var(--accent-light)">
          Showing {{ d.filteredTests.value.length }} of {{ d.tests.length }}
          <span v-if="d.tests.length - d.filteredTests.value.length > 0" style="color:var(--text-muted)"> ({{ d.tests.length - d.filteredTests.value.length }} hidden)</span>
          <span v-if="filteredSuiteMs > 0" style="color:var(--text-muted)"> · {{ fmtDur(filteredSuiteMs) }} total</span>
        </span>
        <span v-if="topN > 0 && d.filteredTests.value.length > topN" style="color:var(--yellow);font-size:.62rem">
          top {{ topN }} of {{ d.filteredTests.value.length }} shown
        </span>
        <button
          class="tests__copy-all-btn"
          :title="'Select all ' + d.filteredTests.value.length + ' visible tests for comparison (Ctrl+click rows to add individual tests)'"
          @click="d.selectAllVisible()"
        >⊞ Select all {{ d.filteredTests.value.length }}</button>
        <button
          class="tests__copy-all-btn"
          :title="'Copy all ' + d.filteredTests.value.length + ' visible test names to clipboard (one per line)'"
          @click="navigator.clipboard?.writeText(d.filteredTests.value.map(t => t.name).join('\n')); showToast('Copied ' + d.filteredTests.value.length + ' test names')"
        >⎘ Copy {{ d.filteredTests.value.length }} names</button>
        <!-- Copy format dropdown -->
        <div class="tests__copy-menu-wrap">
          <button
            class="tests__copy-all-btn tests__copy-menu-trigger"
            :title="'Copy run order in various formats'"
            @click.stop="copyMenuOpen = !copyMenuOpen"
          >⎘ Format ▾</button>
          <div v-if="copyMenuOpen" class="tests__copy-menu" @click.stop>
            <button
              v-for="fmt in COPY_FORMATS"
              :key="fmt.id"
              class="tests__copy-menu-item"
              @click="doCopy(fmt.id)"
            >{{ fmt.label }}</button>
          </div>
        </div>
        <button
          class="tests__copy-all-btn"
          :title="'Export all visible tests as CSV'"
          @click="d.exportCsv()"
        >↓ CSV</button>
      </p>
    </div>

    <!-- Multiple tests selected: comparison -->
    <div v-else-if="d.selectedTests.value.size > 1">
      <div style="display:flex;align-items:center;gap:8px;margin-bottom:8px;flex-wrap:wrap">
        <button @click="d.clearSelection()" class="tests__back-btn" title="Clear selection (Esc)">← Back</button>
        <span style="font-size:.9rem;font-weight:700;color:var(--accent-light)">{{ d.selectedTests.value.size }} tests selected</span>
        <span style="font-size:.72rem;color:var(--text-muted)">Ctrl/⌘+click to toggle · click to focus one</span>
      </div>

      <!-- Aggregate stats -->
      <div v-if="multiStats" style="display:flex;flex-wrap:wrap;gap:6px;margin-bottom:10px">
        <div class="kpi tests-overview__kpi" title="Total unique source-class dependencies across all selected tests">
          <div class="tests-overview__kpi-label">Union Deps</div>
          <div class="tests-overview__kpi-value" style="color:var(--accent-light)">{{ multiStats.unionDeps }}</div>
        </div>
        <div class="kpi tests-overview__kpi" :title="'Source classes covered by ALL ' + d.selectedTests.value.size + ' selected tests (intersection)'">
          <div class="tests-overview__kpi-label">Shared Deps</div>
          <div class="tests-overview__kpi-value" :style="{ color: multiStats.sharedDeps > 0 ? 'var(--cyan)' : 'var(--text-muted)' }">{{ multiStats.sharedDeps }}</div>
        </div>
        <div class="kpi tests-overview__kpi" title="Combined estimated run time (sum of EMA durations)">
          <div class="tests-overview__kpi-label">Total Time</div>
          <div class="tests-overview__kpi-value" style="color:var(--text-sec)">{{ fmtDur(multiStats.totalDur) }}</div>
        </div>
        <div v-if="multiStats.failCount > 0" class="kpi tests-overview__kpi" title="Selected tests with fail history">
          <div class="tests-overview__kpi-label">Fail History</div>
          <div class="tests-overview__kpi-value" style="color:var(--red)">{{ multiStats.failCount }}</div>
        </div>
        <div v-if="multiStats.flakyCount > 0" class="kpi tests-overview__kpi" title="Flaky tests among selected">
          <div class="tests-overview__kpi-label">Flaky</div>
          <div class="tests-overview__kpi-value" style="color:var(--orange)">{{ multiStats.flakyCount }}</div>
        </div>
      </div>

      <!-- Score comparison bars -->
      <div v-if="multiStats" style="margin-bottom:10px">
        <div class="card" style="padding:8px">
          <div class="card-label" style="margin-bottom:6px">Score Comparison</div>
          <div style="display:flex;flex-direction:column;gap:3px">
            <div v-for="t in d.selectedTestObjects.value" :key="t.name" style="display:flex;align-items:center;gap:6px">
              <span style="font-size:.58rem;color:var(--text-sec);width:18px;text-align:right;flex-shrink:0">#{{ t.rank }}</span>
              <span style="font-size:.65rem;color:var(--text);flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-width:0;max-width:180px" :title="t.name">{{ dn(t.name) }}</span>
              <div style="flex:1;height:8px;background:var(--border);border-radius:4px;overflow:hidden;min-width:40px">
                <div :style="{ width: (Math.max(0, t.score) / multiStats!.maxS * 100) + '%', height: '100%', background: t.score < 0 ? 'var(--red)' : 'var(--accent)', borderRadius: '4px', transition: 'width .2s' }"></div>
              </div>
              <span style="font-size:.65rem;font-weight:700;color:var(--accent-light);width:24px;text-align:right;flex-shrink:0" :style="{ color: t.score < 0 ? 'var(--red)' : undefined }">{{ t.score }}</span>
            </div>
          </div>
        </div>
      </div>

      <div style="overflow-x:auto;max-height:260px;overflow-y:auto;margin-bottom:12px">
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
              <th v-if="d.runs.length" class="th--left" title="Pass/fail history across last 5 runs">History</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in d.selectedTestObjects.value" :key="t.name" @click="d.selectTest(t, $event)" class="tests-overview__row"
              :class="{ 'tests-multi__row--focused': d.selectedTest.value && d.selectedTest.value.name === t.name }"
              :style="{ borderLeft: t.failScore > 0 ? '2px solid rgba(248,113,113,.5)' : '2px solid transparent' }"
            >
              <td class="td--right td--dim">#{{ t.rank }}</td>
              <td class="td--name td--narrow" :title="t.name">{{ dn(t.name) }}</td>
              <td class="td--right td--accent">
                <button
                  class="tests-score-btn"
                  type="button"
                  :title="scoreTip(t.name)"
                  @click.stop="d.openScoreModal(t.name, 'orig', 'Multi-select')"
                >{{ t.score }}</button>
              </td>
              <td><TestBadges :test="t" :flaky="d.flakyTests.value.has(t.name)" /></td>
              <td class="td--right td--dim" :class="{ 'tests-overview__dur--slow': t.isSlow, 'tests-overview__dur--fast': t.isFast }">{{ t.duration >= 0 ? fmtDur(t.duration) : '' }}</td>
              <td class="td--right td--dim">{{ t.depOverlap }}</td>
              <td class="td--right td--dim">{{ t.depTotal }}</td>
              <td v-if="d.runs.length" class="td--history">
                <div class="tests-hist-strip" :title="d.testHistoryMap.value.get(t.name) ? (d.testHistoryMap.value.get(t.name)!.fail > 0 ? d.testHistoryMap.value.get(t.name)!.fail + ' failures / ' + (d.testHistoryMap.value.get(t.name)!.pass + d.testHistoryMap.value.get(t.name)!.fail) + ' runs' : 'all passed') : ''">
                  <template v-if="d.testHistoryMap.value.get(t.name)">
                    <div v-for="(failed, hi) in d.testHistoryMap.value.get(t.name)!.last8" :key="hi"
                      class="tests-hist-dot" :class="failed ? 'tests-hist-dot--fail' : 'tests-hist-dot--pass'"></div>
                  </template>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <DepGraph label="Combined Dependency Graph" />
    </div>

    <!-- Single test selected: detail view -->
    <div v-else-if="d.selectedTest.value" class="tests__detail-enter">
      <div class="tests-detail__sticky-header">
        <button @click="d.clearSelection()" class="tests__back-btn" title="Back to overview (Esc)">← Back</button>
        <span style="font-size:.9rem;font-weight:700;color:var(--accent-light)">#{{ d.selectedTest.value.rank }}</span>
        <span style="font-size:.85rem;font-weight:600;color:var(--text);word-break:break-all;flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" :title="d.selectedTest.value.name">{{ d.selectedTest.value.name }}</span>
        <button
          type="button"
          class="tests__copy-btn"
          :title="'Copy full class name: ' + d.selectedTest.value.name"
          @click="navigator.clipboard?.writeText(d.selectedTest.value!.name); showToast('Copied: ' + d.selectedTest.value!.name.split('.').pop())"
        >⎘</button>
        <button
          type="button"
          class="tests__nav-btn"
          :disabled="d.filteredTests.value.findIndex(x => x.name === d.selectedTest.value!.name) <= 0"
          @click="d.navigateTestDetail('prev')"
          title="Previous test (← or H)"
        >‹</button>
        <span style="font-size:.58rem;color:var(--text-muted);flex-shrink:0">{{ d.filteredTests.value.findIndex(x => x.name === d.selectedTest.value!.name) + 1 }}/{{ d.filteredTests.value.length }}</span>
        <button
          type="button"
          class="tests__nav-btn"
          :disabled="d.filteredTests.value.findIndex(x => x.name === d.selectedTest.value!.name) >= d.filteredTests.value.length - 1"
          @click="d.navigateTestDetail('next')"
          title="Next test (→ or L)"
        >›</button>
        <button
          type="button"
          class="tests-detail-score-btn"
          :title="scoreTip(d.selectedTest.value.name)"
          @click="d.openScoreModal(d.selectedTest.value.name, 'orig', 'Test Detail')"
        >Score: {{ d.selectedTest.value.score }}</button>
        <button
          type="button"
          class="tests__focus-btn"
          :class="{ 'tests__focus-btn--on': focusMode }"
          @click="toggleFocusMode()"
          :title="focusMode ? 'Exit focus mode (Z)' : 'Focus mode — hide sidebar (Z)'"
        >{{ focusMode ? '⊠' : '⊡' }}</button>
      </div>

      <!-- Position context strip: neighbors in priority order -->
      <div v-if="d.selectedTest.value" class="pos-strip">
        <template v-for="t in posNeighbors" :key="t.name">
          <div
            class="pos-strip__item"
            :class="{
              'pos-strip__item--current': t.name === d.selectedTest.value.name,
              'pos-strip__item--fail': t.failScore > 0,
              'pos-strip__item--flaky': d.flakyTests.value.has(t.name)
            }"
            :title="t.name + '\nRank #' + t.rank + ' · Score ' + t.score.toFixed(1)"
            @click="t.name !== d.selectedTest.value!.name && d.selectTest(t)"
          >
            <span class="pos-strip__rank">#{{ t.rank }}</span>
            <span class="pos-strip__name">{{ sn(t.name) }}</span>
            <span class="pos-strip__score">{{ t.score.toFixed(0) }}</span>
          </div>
        </template>
        <span v-if="d.selectedTest.value.rank > 3" class="pos-strip__ellipsis">…</span>
        <span class="pos-strip__total">of {{ d.tests.length }}</span>
      </div>
      <div style="display:flex;flex-wrap:wrap;gap:6px;margin-bottom:8px">
        <TestBadges :test="d.selectedTest.value" size="md" :flaky="d.flakyTests.value.has(d.selectedTest.value.name)" />
        <span v-if="d.selectedTest.value.methods && d.selectedTest.value.methods.length" class="badge" style="background:rgba(99,102,241,.2);color:var(--accent-light)" title="Test methods with individual dep tracking">{{ d.selectedTest.value.methods.length }} methods</span>
        <span class="tests-detail__stat" title="EMA-smoothed expected duration">⏱ {{ d.selectedTest.value.duration >= 0 ? fmtDur(d.selectedTest.value.duration) : '?' }}</span>
        <span class="tests-detail__stat" :style="{ color: d.selectedTest.value.failScore > 0 ? 'var(--red)' : 'var(--text-muted)' }" title="EMA-decayed historical failure score (raw)">✕ fail score {{ d.selectedTest.value.failScore.toFixed(2) }}</span>
        <span class="tests-detail__stat" :style="{ color: d.selectedTest.value.depOverlap > 0 ? 'var(--cyan)' : 'var(--text-muted)' }" title="Source class dependencies (overlapping / total)">◈ {{ d.selectedTest.value.depOverlap }}/{{ d.selectedTest.value.depTotal }} deps</span>
        <span v-if="d.testOutcomes.value.filter(o => o.present).length > 0" class="tests-detail__stat"
          :style="{ color: d.testOutcomes.value.filter(o => o.present && o.failed).length > 0 ? 'var(--orange)' : 'var(--green)' }"
          :title="'Fail rate across ' + d.testOutcomes.value.filter(o => o.present).length + ' runs'"
        >
          {{ d.testOutcomes.value.filter(o => o.present && o.failed).length }}✕ / {{ d.testOutcomes.value.filter(o => o.present).length }} runs
          ({{ Math.round(d.testOutcomes.value.filter(o => o.present && o.failed).length / d.testOutcomes.value.filter(o => o.present).length * 100) }}% fail rate)
        </span>
        <span v-if="d.selectedTest.value.failScore > 0 && d.testOutcomes.value.length > 0" class="tests-detail__stat" style="color:var(--red)">· last failed {{ fmtTime(d.testOutcomes.value.filter(o => o.present && o.failed).at(-1)?.ts ?? d.testOutcomes.value.filter(o => o.present).at(-1)?.ts) }}</span>
        <span v-if="rankTrend" class="tests-detail__stat tests-detail__rank-trend"
          :class="rankTrend.dir === 'improving' ? 'tests-detail__rank-trend--up' : rankTrend.dir === 'worsening' ? 'tests-detail__rank-trend--down' : ''"
          :title="rankTrendTip"
        >
          {{ rankTrend.dir === 'improving' ? '▲' : rankTrend.dir === 'worsening' ? '▼' : '=' }}
          rank {{ rankTrend.dir === 'stable' ? 'stable' : (rankTrend.dir === 'improving' ? '+' : '') + rankTrend.delta }}
        </span>
        <!-- First-seen age indicator -->
        <span
          v-if="selectedFirstSeen"
          class="tests-detail__stat"
          :class="selectedFirstSeen.isRecent ? 'tests-detail__first-seen--new' : ''"
          :title="'First seen in run #' + selectedFirstSeen.runNum + ' of ' + selectedFirstSeen.totalRuns + (selectedFirstSeen.ts ? ' · ' + fmtTime(selectedFirstSeen.ts) : '')"
        >
          {{ selectedFirstSeen.isRecent ? '🆕 first seen' : '⊙ since' }} run #{{ selectedFirstSeen.runNum }}
        </span>
      </div>

      <!-- Why ranked explanation -->
      <p v-if="whyRanked" class="tests-detail__why">{{ whyRanked }}</p>

      <!-- Score breakdown + Run history -->
      <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px">
        <div class="card">
          <div class="card-label">Score Breakdown <span style="font-size:.58rem;color:var(--text-muted)">(hover segments for details)</span></div>
          <div class="test-detail__canvas-wrap" style="height:64px"><canvas id="bd-main"></canvas></div>
          <div style="display:flex;flex-wrap:wrap;gap:4px;margin-top:6px">
            <template v-for="c in d.scoreComps.value" :key="c.label">
              <div v-if="c.value !== 0" class="test-detail__score-item" :title="c.explanation">
                <span class="test-detail__score-dot" :style="{ background: c.color }"></span>
                <span class="test-detail__score-label">{{ c.label }}</span>
                <span class="test-detail__score-val" :class="{ 'test-detail__score-val--pos': c.value > 0, 'test-detail__score-val--neg': c.value < 0 }">{{ c.value > 0 ? '+' : '' }}{{ c.value }}</span>
              </div>
            </template>
            <span v-if="d.scoreComps.value.every(c => c.value === 0)" style="font-size:.65rem;color:var(--text-muted)">All components zero — score = 0</span>
          </div>
        </div>
        <div class="card">
          <div class="card-label">Run History
            <span style="font-size:.58rem;color:var(--text-muted)">
              ({{ d.testOutcomes.value.filter(o => o.present).length }}/{{ d.testOutcomes.value.length }} runs ·
              {{ d.testOutcomes.value.filter(o => o.present && o.failed).length }} fails)
            </span>
          </div>
          <div style="display:flex;flex-wrap:wrap;gap:2px;padding:2px 0;margin-bottom:6px">
            <div
              v-for="(r, i) in [...d.testOutcomes.value].reverse()"
              :key="i"
              class="test-detail__run-sq"
              :class="{
                'test-detail__run-sq--pass': r.present && !r.failed,
                'test-detail__run-sq--fail': r.present && r.failed,
                'test-detail__run-sq--absent': !r.present,
              }"
              :title="'Run #' + (i+1) + ': ' + fmtTime(r.ts) + (r.present ? (r.failed ? ' — FAILED' : ' — passed') : ' — not in run') + '\n\nClick to inspect this run in Analytics'"
              @click="d.navigateToRun(d.testOutcomes.value.length - 1 - i)"
            >
              <span v-if="r.present && r.failed" style="font-size:.48rem;line-height:1;font-weight:700">!</span>
            </div>
          </div>
          <div class="card-label">Pass/Fail per Run <span style="font-size:.58rem;color:var(--text-muted)">(EMA dur: {{ d.selectedTest.value.duration >= 0 ? fmtDur(d.selectedTest.value.duration) : 'unknown' }}{{ d.selectedTest.value.isSlow ? ' · slow' : d.selectedTest.value.isFast ? ' · fast' : '' }})</span></div>
          <div class="test-detail__canvas-wrap" style="height:50px"><canvas id="hd-main"></canvas></div>
        </div>
      </div>

      <!-- Score over time + Run position -->
      <div v-if="d.runs.length > 1" style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:10px">
        <div class="card">
          <div class="card-label">Score over runs</div>
          <div v-if="d.testOutcomes.value.every(o => !o.present)" style="height:80px;display:flex;align-items:center;justify-content:center;color:var(--text-muted);font-size:.68rem">No outcome data in run history</div>
          <div v-else class="test-detail__canvas-wrap test-detail__canvas-wrap--trend"><canvas id="hs-main"></canvas></div>
        </div>
        <div class="card">
          <div class="card-label">Run position (lower = earlier)</div>
          <div v-if="d.testOutcomes.value.every(o => !o.present)" style="height:80px;display:flex;align-items:center;justify-content:center;color:var(--text-muted);font-size:.68rem">No outcome data in run history</div>
          <div v-else class="test-detail__canvas-wrap test-detail__canvas-wrap--trend"><canvas id="hp-main"></canvas></div>
        </div>
      </div>

      <!-- Run position history table -->
      <div v-if="testRunPositions.length > 0" style="margin-top:10px">
        <div class="card" style="padding:8px">
          <div class="card-label" style="margin-bottom:4px">Run Position History <span style="font-size:.58rem;color:var(--text-muted)">— click a run to inspect in Analytics</span></div>
          <div style="display:flex;flex-wrap:wrap;gap:3px">
            <div
              v-for="r in [...testRunPositions].reverse()"
              :key="r.runIdx"
              class="test-detail__run-pos"
              :class="{ 'test-detail__run-pos--fail': r.present && r.failed, 'test-detail__run-pos--pass': r.present && !r.failed, 'test-detail__run-pos--absent': !r.present }"
              :title="'Run #' + (d.runs.length - r.runIdx) + ' · ' + fmtTime(r.ts) + (r.present ? ' · rank ' + r.rank + '/' + r.totalTests + (r.failed ? ' · FAILED' : ' · passed') : ' · not in run') + '\n\nClick to inspect'"
              @click="d.navigateToRun(r.runIdx)"
            >
              <div class="test-detail__run-pos-num">#{{ d.runs.length - r.runIdx }}</div>
              <div class="test-detail__run-pos-rank" v-if="r.present">{{ r.rank }}</div>
              <div class="test-detail__run-pos-rank" v-else style="opacity:.3">—</div>
            </div>
          </div>
        </div>
      </div>

      <!-- Similar tests: tests sharing most deps with selected -->
      <div v-if="similarTests.length > 0" style="margin-top:10px">
        <div class="card" style="padding:8px">
          <div class="card-label" style="margin-bottom:6px">
            Similar Tests
            <span style="font-size:.58rem;color:var(--text-muted)">— by shared source-class coverage · click to navigate · Ctrl+click to compare</span>
          </div>
          <div style="display:flex;flex-direction:column;gap:3px">
            <div
              v-for="r in similarTests"
              :key="r.test.name"
              class="test-detail__similar-row"
              @click="$event.ctrlKey || $event.metaKey ? d.selectTest(r.test, $event) : d.selectTest(r.test, null)"
              @mouseenter="testHover.show(r.test.name, $event)" @mousemove="testHover.move($event)" @mouseleave="testHover.hide()"
              :title="r.test.name + '\n' + r.overlap + ' shared deps (' + r.pct + '% of selected test\'s deps)\nClick to navigate · Ctrl+click to add to comparison'"
            >
              <span class="test-detail__similar-rank">#{{ r.test.rank }}</span>
              <span class="test-detail__similar-name">{{ dn(r.test.name) }}</span>
              <div class="test-detail__similar-bar-wrap" :title="r.overlap + ' shared / ' + d.selectedTest.value!.deps.length + ' total deps'">
                <div class="test-detail__similar-bar" :style="{ width: r.pct + '%' }"></div>
              </div>
              <span class="test-detail__similar-pct">{{ r.overlap }}<span style="opacity:.5">/{{ r.pct }}%</span></span>
              <span v-if="d.flakyTests.value.has(r.test.name)" class="badge" style="font-size:.45rem;padding:0 3px;background:rgba(124,45,18,.45);color:#fb923c">FLAKY</span>
            </div>
          </div>
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
            <div v-if="m.memberDeps && m.memberDeps.length" style="margin-top:4px;font-size:.58rem;color:var(--text-muted);line-height:1.4">
              <span style="color:var(--accent-light)">prod methods:</span>
              {{ m.memberDeps.slice(0, 3).map(k => k.includes('#') ? k.substring(k.lastIndexOf('.') + 1) : k).join(', ') }}<span v-if="m.memberDeps.length > 3"> +{{ m.memberDeps.length - 3 }} more</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Coverage section: which source classes does this test cover -->
      <div v-if="d.hasCoverage && d.selectedTest.value.deps && d.selectedTest.value.deps.length" style="margin-top:10px">
        <div class="card">
          <div class="card-label" style="margin-bottom:6px;display:flex;align-items:center;gap:8px;flex-wrap:wrap">
            Source Class Coverage
            <span style="color:var(--text-muted);font-size:.6rem">— {{ d.selectedTest.value.deps.length }} class{{ d.selectedTest.value.deps.length === 1 ? '' : 'es' }}</span>
            <span v-if="d.selectedTest.value.deps.filter(dep => d.changedSet.has(dep)).length > 0" style="font-size:.6rem;color:var(--yellow)">· {{ d.selectedTest.value.deps.filter(dep => d.changedSet.has(dep)).length }} changed</span>
            <span v-if="d.selectedTest.value.deps.filter(dep => (hotDepMap.get(dep) ?? 0) >= hotDepThreshold).length > 0" style="font-size:.6rem;color:var(--orange)">· {{ d.selectedTest.value.deps.filter(dep => (hotDepMap.get(dep) ?? 0) >= hotDepThreshold).length }} hot</span>
            <button
              v-if="d.selectedTest.value.deps.length > 40"
              class="test-detail__show-all-btn"
              @click="showAllDeps = !showAllDeps"
            >{{ showAllDeps ? 'Show less' : 'Show all ' + d.selectedTest.value.deps.length }}</button>
          </div>
          <div style="display:flex;flex-wrap:wrap;gap:4px">
            <template v-for="dep in (showAllDeps ? [...d.selectedTest.value.deps].sort((a, b) => {
              const hotA = (hotDepMap.get(a) ?? 0) >= hotDepThreshold ? 1 : 0
              const hotB = (hotDepMap.get(b) ?? 0) >= hotDepThreshold ? 1 : 0
              const chA = d.changedSet.has(a) ? 2 : 0
              const chB = d.changedSet.has(b) ? 2 : 0
              return (chB + hotB) - (chA + hotA)
            }) : [...d.selectedTest.value.deps].sort((a, b) => {
              const hotA = (hotDepMap.get(a) ?? 0) >= hotDepThreshold ? 1 : 0
              const hotB = (hotDepMap.get(b) ?? 0) >= hotDepThreshold ? 1 : 0
              const chA = d.changedSet.has(a) ? 2 : 0
              const chB = d.changedSet.has(b) ? 2 : 0
              return (chB + hotB) - (chA + hotA)
            }).slice(0, 40))" :key="dep">
              <span
                class="test-detail__cov-tag"
                :class="{ 'test-detail__cov-tag--changed': d.changedSet.has(dep), 'test-detail__cov-tag--hot': !d.changedSet.has(dep) && (hotDepMap.get(dep) ?? 0) >= hotDepThreshold }"
                :title="dep + (d.changedSet.has(dep) ? ' (CHANGED)' : '') + ((hotDepMap.get(dep) ?? 0) >= hotDepThreshold ? ' (HOT — covered by ' + hotDepMap.get(dep) + ' tests)' : '') + ' — click to view in Coverage'"
                @click="d.navigateToCovClass(dep)"
                @mouseenter="classHover.show(dep, $event)"
                @mousemove="classHover.move($event)"
                @mouseleave="classHover.hide()"
              >{{ dn(dep) }}<span v-if="d.changedSet.has(dep)" class="test-detail__cov-changed">✎</span><span v-else-if="(hotDepMap.get(dep) ?? 0) >= hotDepThreshold" class="test-detail__cov-hot" :title="hotDepMap.get(dep) + ' tests cover this class'">🔥</span></span>
            </template>
            <button v-if="!showAllDeps && d.selectedTest.value.deps.length > 40" class="test-detail__show-all-btn" @click="showAllDeps = true">+{{ d.selectedTest.value.deps.length - 40 }} more…</button>
          </div>
          <div v-if="d.selectedTest.value.memberDeps && d.selectedTest.value.memberDeps.length" style="margin-top:6px;font-size:.6rem;color:var(--text-muted)">
            {{ d.selectedTest.value.memberDeps.length }} member-level deps tracked — <span style="cursor:pointer;color:var(--accent-light)" @click="d.openScoreModal(d.selectedTest.value!.name, 'orig', 'Coverage')">see score modal for details</span>
          </div>
        </div>
      </div>

      <DepGraph />

      <!-- Coverage siblings: other tests sharing same source classes -->
      <div v-if="coverageSiblings.length > 0" style="margin-top:10px">
        <div class="card" style="padding:8px">
          <div class="card-label" style="margin-bottom:6px">
            Coverage Siblings
            <span style="font-size:.58rem;color:var(--text-muted)">— tests covering same source classes · click to navigate</span>
          </div>
          <div style="display:flex;flex-direction:column;gap:3px">
            <div
              v-for="s in coverageSiblings"
              :key="s.name"
              class="test-detail__similar-row"
              @click="$event.ctrlKey || $event.metaKey ? d.selectTest(s.test!, $event) : d.selectTest(s.test!, null)"
              :title="s.name + '\n' + s.shared.length + ' shared classes (' + s.pct + '% of selected test\'s coverage)\nShared: ' + s.shared.slice(0,5).map(c => c.split('.').pop()).join(', ') + (s.shared.length > 5 ? '…' : '') + '\nClick to navigate · Ctrl+click to compare'"
            >
              <span class="test-detail__similar-rank">#{{ s.test!.rank }}</span>
              <span class="test-detail__similar-name">{{ dn(s.name) }}</span>
              <div class="test-detail__similar-bar-wrap">
                <div class="test-detail__similar-bar test-detail__similar-bar--cov" :style="{ width: s.pct + '%' }"></div>
              </div>
              <span class="test-detail__similar-pct">{{ s.shared.length }}<span style="opacity:.5">/{{ s.pct }}%</span></span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
  <ClassInfoCard v-if="classHover.visible.value" :info="classHover.info.value" :x="classHover.x.value" :y="classHover.y.value" />
  <!-- Row hover preview popover -->
  <Teleport to="body">
    <div
      v-if="hoverTest"
      class="row-preview"
      :style="{ top: hoverPos.top + 'px', left: hoverPos.left + 'px', transform: 'translateY(-50%)' }"
      @mouseenter="hoverTimer && clearTimeout(hoverTimer)"
      @mouseleave="hideRowPreview()"
    >
      <div class="row-preview__name" :title="hoverTest.name">{{ dn(hoverTest.name) }}</div>
      <div class="row-preview__rank">Rank #{{ hoverTest.rank }} · Score {{ hoverTest.score.toFixed(1) }}</div>
      <!-- Score bar -->
      <div class="row-preview__score-bar">
        <div v-for="bar in previewScoreBars(hoverTest)" :key="bar.label" class="row-preview__score-seg"
          :style="{ width: bar.w + '%', background: bar.c }"
          :title="bar.label + ' ' + bar.w + '%'"
        ></div>
      </div>
      <div class="row-preview__score-labels">
        <span v-for="bar in previewScoreBars(hoverTest)" :key="bar.label" :style="{ color: bar.c }">{{ bar.label }}</span>
      </div>
      <!-- Badges -->
      <div class="row-preview__badges">
        <span v-if="hoverTest.failScore > 0" class="row-preview__badge row-preview__badge--fail">fail hist</span>
        <span v-if="hoverTest.isNew" class="row-preview__badge row-preview__badge--new">new</span>
        <span v-if="hoverTest.isChanged" class="row-preview__badge row-preview__badge--changed">changed</span>
        <span v-if="hoverTest.isSlow" class="row-preview__badge row-preview__badge--slow">slow</span>
        <span v-if="hoverTest.isFast" class="row-preview__badge row-preview__badge--fast">fast</span>
      </div>
      <!-- Duration + variance -->
      <div v-if="hoverTest.duration >= 0" class="row-preview__dur">
        {{ fmtDur(hoverTest.duration) }}
        <span v-if="hoverTest.durationVariance > 0" style="color:var(--text-muted)"> ±{{ Math.round(Math.sqrt(hoverTest.durationVariance)) }}ms</span>
      </div>
      <!-- Run history dots -->
      <div v-if="d.testHistoryMap.value.get(hoverTest.name)" class="row-preview__hist">
        <div
          v-for="(failed, hi) in d.testHistoryMap.value.get(hoverTest.name)!.last8"
          :key="hi"
          class="tests-hist-dot"
          :class="failed ? 'tests-hist-dot--fail' : 'tests-hist-dot--pass'"
          style="width:7px;height:7px"
        ></div>
      </div>
      <!-- Rank trend sparkline -->
      <div v-if="rankSparkSvg(hoverTest.name)" v-html="rankSparkSvg(hoverTest.name)" class="row-preview__spark"></div>
      <div class="row-preview__hint">click to inspect</div>
    </div>
  </Teleport>
</template>

<style scoped>
/* Overview */
.tests-overview__kpi { padding: 6px 10px; }
.tests-overview__kpi-label { color: var(--text-sec); font-size: .6rem; font-weight: 600; text-transform: uppercase; letter-spacing: .4px; }
.tests-overview__kpi-value { font-size: 1rem; font-weight: 700; }
.tests-overview__kpi--filter { cursor: pointer; transition: border-color var(--tr-fast), background var(--tr-fast); }
.tests-overview__kpi--filter:hover { border-color: rgba(99,102,241,.4); background: rgba(99,102,241,.06); }
.tests-overview__kpi--active { border-color: var(--accent) !important; background: var(--accent-bg) !important; }
.tests-overview__filter-clear { display: flex; align-items: center; padding: 4px 10px; font-size: .7rem; color: var(--text-muted); background: var(--bg-card); border: 1px solid var(--border); border-radius: var(--radius); cursor: pointer; transition: color var(--tr-fast); }
.tests-overview__filter-clear:hover { color: var(--text); }
.tests-overview__thead { position: sticky; top: 0; background: var(--bg-base); z-index: 1; }
.tests-overview__row { cursor: pointer; }
.tests-overview__row--dimmed { opacity: .5; }
.tests-overview__row--even { background: rgba(255,255,255,.018); }
.tests-overview__row--even:hover { background: rgba(99,102,241,.09) !important; }

/* Top-N selector */
.tests__topn-btn {
  padding: 1px 6px; font-size: .6rem; border: 1px solid var(--border); border-radius: 3px;
  background: none; cursor: pointer; color: var(--text-muted); transition: all var(--tr-fast);
}
.tests__topn-btn:hover { color: var(--text-sec); border-color: var(--text-sec); }
.tests__topn-btn--active { color: var(--accent-light); border-color: var(--accent); background: var(--accent-bg); }
.tests-cluster-header { background: rgba(99,102,241,.08); border-top: 1px solid rgba(99,102,241,.2); }
.tests-cluster-header td { padding: 3px 6px; }
.tests-cluster-header__pkg { font-size: .62rem; font-weight: 700; color: var(--accent-light); letter-spacing: .3px; font-family: monospace; }
.tests-cluster-header__count { font-size: .58rem; color: var(--text-muted); margin-left: 8px; }
.tests-cluster-header__meta { font-size: .58rem; color: var(--text-muted); margin-left: 8px; }
.td--conf { font-size: .62rem; font-weight: 600; min-width: 32px; }
.td--stab { font-size: .66rem; font-weight: 700; min-width: 28px; }

/* Table cell helpers */
.th--right { padding: 4px 8px; text-align: right; }
.th--left { padding: 4px 8px; text-align: left; }
.td--right { padding: 3px 8px; text-align: right; font-size: .75rem; }
.td--dim { color: var(--text-sec); }
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
.test-detail__run-sq { width: 14px; height: 14px; border-radius: 2px; flex-shrink: 0; border: 1px solid; display: flex; align-items: center; justify-content: center; cursor: pointer; transition: opacity .12s; }
.test-detail__run-sq:hover { opacity: .75; }
.test-detail__run-sq--pass { background: #22c55e; border-color: #15803d; }
.test-detail__run-sq--fail { background: #ef4444; border-color: #b91c1c; color: #fff; }
.test-detail__run-sq--absent { background: var(--bg-base); border-color: var(--border); }

.test-detail__run-pos {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  width: 34px; min-height: 36px; border-radius: 4px; cursor: pointer; border: 1px solid;
  transition: opacity .12s, transform .1s; padding: 2px 0; gap: 1px;
}
.test-detail__run-pos:hover { opacity: .8; transform: translateY(-1px); }
.test-detail__run-pos--pass { background: rgba(34,197,94,.15); border-color: rgba(34,197,94,.35); }
.test-detail__run-pos--fail { background: rgba(239,68,68,.2); border-color: rgba(239,68,68,.5); }
.test-detail__run-pos--absent { background: var(--bg-base); border-color: var(--border); opacity: .5; }
.test-detail__run-pos-num { font-size: .48rem; color: var(--text-muted); line-height: 1; }
.test-detail__run-pos-rank { font-size: .68rem; font-weight: 700; color: var(--text); line-height: 1; }
.test-detail__run-pos--fail .test-detail__run-pos-rank { color: var(--red); }
.test-detail__run-pos--pass .test-detail__run-pos-rank { color: var(--green); }

.test-detail__similar-row {
  display: flex; align-items: center; gap: 6px; padding: 3px 4px; border-radius: 3px;
  cursor: pointer; transition: background .1s;
}
.test-detail__similar-row:hover { background: rgba(99,102,241,.08); }
.test-detail__similar-rank { font-size: .58rem; color: var(--text-sec); width: 24px; flex-shrink: 0; text-align: right; }
.test-detail__similar-name { font-size: .68rem; color: var(--text); flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
.test-detail__similar-bar-wrap { width: 60px; height: 6px; background: var(--border); border-radius: 3px; flex-shrink: 0; overflow: hidden; }
.test-detail__similar-bar { height: 100%; background: var(--accent); border-radius: 3px; }
.test-detail__similar-bar--cov { background: var(--cyan); }
.test-detail__similar-pct { font-size: .6rem; color: var(--accent-light); flex-shrink: 0; min-width: 36px; text-align: right; }

/* Inline stats in test detail header */
.tests-detail__stat { font-size: .65rem; color: var(--text-muted); white-space: nowrap; }
.tests-detail__rank-trend { font-weight: 700; padding: 1px 5px; border-radius: 3px; background: rgba(71,85,105,.3); }
.tests-detail__rank-trend--up { color: var(--green); background: rgba(34,197,94,.12); }
.tests-detail__rank-trend--down { color: var(--red); background: rgba(239,68,68,.12); }
.tests-detail__first-seen--new { color: var(--green); font-weight: 700; padding: 1px 5px; border-radius: 3px; background: rgba(34,197,94,.15); }
.tests-detail__why {
  margin: 4px 0 8px; font-size: .68rem; color: var(--text-sec);
  background: rgba(99,102,241,.06); border-left: 2px solid var(--accent);
  padding: 4px 8px; border-radius: 0 4px 4px 0; line-height: 1.5;
}
.test-detail__method-card {
  background: var(--bg-base); border: 1px solid var(--border); border-radius: 4px; padding: 4px 8px;
  font-size: .7rem; display: flex; justify-content: space-between; align-items: center;
  cursor: pointer; transition: background .1s; user-select: none;
}
.test-detail__method-card--selected { background: rgba(99, 102, 241, .12); }
.test-detail__method-name { color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.test-detail__method-deps { color: var(--accent-light); font-weight: 600; flex-shrink: 0; margin-left: 8px; }

/* Back button */
.tests__back-btn {
  padding: 3px 10px; font-size: .72rem; color: var(--text-sec);
  background: var(--bg-card); border: 1px solid var(--border); border-radius: 4px;
  cursor: pointer; transition: all var(--tr-fast); flex-shrink: 0;
}
.tests__back-btn:hover { color: var(--text); border-color: var(--accent); background: var(--accent-bg); }

.tests__copy-btn {
  padding: 2px 6px; font-size: .72rem; color: var(--text-muted);
  background: none; border: 1px solid var(--border); border-radius: 4px;
  cursor: pointer; transition: all var(--tr-fast); flex-shrink: 0;
}
.tests__copy-btn:hover { color: var(--accent-light); border-color: var(--accent); }

.tests__nav-btn {
  padding: 1px 7px; font-size: .95rem; line-height: 1.3; color: var(--text-muted);
  background: none; border: 1px solid var(--border); border-radius: 4px;
  cursor: pointer; transition: all var(--tr-fast); flex-shrink: 0;
}
.tests__nav-btn:hover:not(:disabled) { color: var(--accent-light); border-color: var(--accent); background: var(--accent-bg); }
.tests__nav-btn:disabled { opacity: .3; cursor: default; }

.th-sort--active { color: var(--accent-light); }

.tests-score-btn {
  border: none;
  background: none;
  color: inherit;
  font: inherit;
  cursor: pointer;
  padding: 0;
  text-decoration: none;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.tests-score-bar {
  display: inline-block;
  height: 5px;
  background: var(--accent);
  border-radius: 2px;
  flex-shrink: 0;
  transition: width .2s;
  opacity: .65;
}
.tests-score-btn:hover .tests-score-bar { opacity: 1; }
.tests-score-stack {
  display: inline-flex; height: 5px; border-radius: 2px; overflow: hidden;
  flex-shrink: 0; opacity: .7; transition: opacity .15s;
}
.tests-score-btn:hover .tests-score-stack { opacity: 1; }
.tests-score-stack__seg { display: inline-block; height: 100%; flex-shrink: 0; }

/* Score bar legend popover */
.tests-score-legend-wrap {
  position: relative; display: inline-flex; align-items: center; vertical-align: middle; cursor: default;
}
.tests-score-legend-icon {
  font-size: .65rem; color: var(--text-muted); margin-left: 3px; user-select: none;
  transition: color var(--tr-fast);
}
.tests-score-legend-wrap:hover .tests-score-legend-icon { color: var(--accent-light); }
.tests-score-legend-pop {
  display: none; position: absolute; top: 100%; right: 0; z-index: 50;
  background: #1e293b; border: 1px solid var(--border); border-radius: 6px;
  padding: 7px 10px; min-width: 130px; box-shadow: 0 6px 20px rgba(0,0,0,.4);
  flex-direction: column; gap: 4px;
  pointer-events: none;
}
.tests-score-legend-wrap:hover .tests-score-legend-pop { display: flex; }
.tests-score-legend-row {
  display: flex; align-items: center; gap: 6px;
  font-size: .62rem; color: var(--text-sec); white-space: nowrap; font-weight: 400;
}
.tests-score-legend-dot {
  width: 8px; height: 8px; border-radius: 2px; flex-shrink: 0;
}
.tests-score-num {
  text-decoration: underline dotted;
  text-underline-offset: 2px;
}
.tests-detail__sticky-header {
  position: sticky; top: -10px; z-index: 10;
  display: flex; align-items: center; gap: 8px; flex-wrap: nowrap;
  padding: 6px 10px; margin: -10px -10px 8px -10px;
  background: var(--bg-base);
  border-bottom: 1px solid var(--border);
}

.tests-detail-score-btn {
  margin-left: auto;
  border: none;
  background: none;
  color: var(--accent);
  font-size: 1rem;
  font-weight: 700;
  cursor: pointer;
  padding: 0;
  text-decoration: underline dotted;
  text-underline-offset: 2px;
}

.tests__focus-btn {
  flex-shrink: 0; background: none; border: 1px solid var(--border); border-radius: 3px;
  color: var(--text-muted); cursor: pointer; font-size: .78rem; padding: 1px 5px;
  line-height: 1; transition: color .12s, border-color .12s, background .12s;
}
.tests__focus-btn:hover { color: var(--accent-light); border-color: var(--accent); }
.tests__focus-btn--on { color: var(--accent-light); border-color: var(--accent); background: var(--accent-bg); }

.tests-overview__dur--slow { color: var(--orange) !important; }
.tests-overview__dur--fast { color: var(--cyan) !important; }
.tests-overview__dur-cell { display: flex; align-items: center; justify-content: flex-end; gap: 4px; }
.tests-overview__dur-bar { display: inline-block; height: 5px; border-radius: 2px; flex-shrink: 0; opacity: .65; }
.td--var { display: flex; align-items: center; justify-content: flex-end; gap: 3px; font-size: .68rem; min-width: 44px; }
.td--var-bar { display: inline-block; height: 4px; border-radius: 2px; flex-shrink: 0; opacity: .7; }

/* Coverage tags in test detail */
.test-detail__cov-tag {
  font-size: .62rem; padding: 1px 6px; background: rgba(51,65,85,.5); color: var(--text-sec);
  border-radius: 3px; white-space: nowrap; cursor: pointer; transition: background var(--tr-fast);
  border: 1px solid transparent;
}
.test-detail__cov-tag:hover { background: rgba(99,102,241,.2); border-color: rgba(99,102,241,.3); }
.test-detail__cov-tag--changed { background: rgba(234,179,8,.15); color: var(--yellow); border-color: rgba(234,179,8,.2); }
.test-detail__cov-tag--hot { background: rgba(249,115,22,.1); color: var(--orange); border-color: rgba(249,115,22,.25); }
.test-detail__cov-tag--hot:hover { background: rgba(249,115,22,.2); border-color: rgba(249,115,22,.4); }
.test-detail__cov-changed { font-size: .55rem; margin-left: 2px; }
.test-detail__cov-hot { font-size: .6rem; margin-left: 2px; }
.test-detail__cov-more { font-size: .6rem; color: var(--text-muted); padding: 1px 6px; }
.test-detail__show-all-btn {
  font-size: .6rem; padding: 1px 8px; border-radius: 3px; border: 1px solid var(--border);
  background: none; color: var(--accent-light); cursor: pointer; transition: all var(--tr-fast);
}
.test-detail__show-all-btn:hover { border-color: var(--accent); background: var(--accent-bg); }

/* Kbd hints */
.tests-kbd {
  display: inline-block; padding: 0 4px; border: 1px solid var(--border); border-radius: 3px;
  font-family: inherit; font-size: .6rem; background: var(--bg-card); color: var(--text-sec);
  line-height: 1.5; margin: 0 1px;
}
.tests__copy-all-btn {
  font-size: .62rem; padding: 2px 8px; border-radius: 4px; border: 1px solid var(--border);
  background: var(--bg-card); color: var(--text-sec); cursor: pointer; white-space: nowrap;
  transition: all var(--tr-fast);
}
.tests__copy-all-btn:hover { border-color: var(--accent); color: var(--accent-light); }

/* Copy format dropdown */
.tests__copy-menu-wrap { position: relative; display: inline-block; }
.tests__copy-menu-trigger { border-color: rgba(99,102,241,.3); color: var(--accent-light); }
.tests__copy-menu {
  position: absolute; bottom: calc(100% + 4px); left: 0; z-index: 30;
  background: var(--bg-card); border: 1px solid var(--border); border-radius: 6px;
  padding: 4px; min-width: 200px; box-shadow: 0 6px 20px rgba(0,0,0,.35);
  display: flex; flex-direction: column; gap: 1px;
}
.tests__copy-menu-item {
  text-align: left; padding: 5px 10px; font-size: .68rem; color: var(--text-sec);
  background: none; border: none; border-radius: 4px; cursor: pointer; white-space: nowrap;
  transition: background .1s, color .1s;
}
.tests__copy-menu-item:hover { background: var(--accent-bg); color: var(--text); }

.tests__sort-reset-pill {
  font-size: .6rem; padding: 2px 8px; border-radius: 10px; border: 1px solid rgba(251,191,36,.35);
  background: rgba(251,191,36,.08); color: var(--yellow); cursor: pointer; white-space: nowrap;
  transition: all var(--tr-fast);
}
.tests__sort-reset-pill:hover { background: rgba(251,191,36,.18); border-color: var(--yellow); }

/* Inline run history dots */
.td--history { padding: 3px 8px; }
.tests-hist-strip { display: flex; gap: 2px; align-items: center; }
.tests-hist-dot {
  width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0;
}
.tests-hist-dot--pass { background: var(--green); opacity: .7; }
.tests-hist-dot--fail { background: var(--red); }

/* Slide-in animation for detail panel */
.tests__detail-enter { animation: detailSlideIn .18s ease-out; }
@keyframes detailSlideIn { from { opacity: 0; transform: translateX(8px); } to { opacity: 1; transform: none; } }

/* Suite time budget */
.tests-minimap {
  flex-shrink: 0;
  width: 10px;
  border-radius: 3px;
  background: rgba(15,23,42,.6);
  cursor: pointer;
  border: 1px solid rgba(255,255,255,.06);
  opacity: .85;
  transition: opacity .15s;
}
.tests-minimap:hover { opacity: 1; }

.suite-budget {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  margin-top: 6px; padding: 5px 8px; border-radius: 5px;
  background: rgba(15,23,42,.4); border: 1px solid var(--border);
  font-size: .62rem;
}
.suite-budget__total { display: flex; align-items: center; gap: 4px; flex-shrink: 0; }
.suite-budget__label { color: var(--text-muted); }
.suite-budget__value { color: var(--text); font-weight: 700; }
.suite-budget__bar {
  display: flex; height: 6px; border-radius: 3px; overflow: hidden;
  flex: 0 0 80px; background: rgba(51,65,85,.4);
}
.suite-budget__seg { height: 100%; }
.suite-budget__seg--slow { background: #fb923c; }
.suite-budget__seg--med  { background: #6366f1; opacity: .7; }
.suite-budget__seg--fast { background: #4ade80; opacity: .8; }
.suite-budget__legend { display: flex; gap: 6px; flex-wrap: wrap; }
.suite-budget__leg { font-size: .58rem; color: var(--text-muted); }
.suite-budget__leg--slow { color: #fb923c; }
.suite-budget__leg--fast { color: #4ade80; }
.suite-budget__sim {
  display: flex; align-items: center; gap: 5px;
  color: var(--text-sec); font-size: .6rem; flex-wrap: wrap;
  border-left: 2px solid rgba(251,191,36,.4); padding-left: 8px; margin-left: 4px;
}
.suite-budget__sim-icon { color: #fbbf24; }
.suite-budget__sim-btn {
  font-size: .58rem; padding: 1px 6px; border: 1px solid var(--border); border-radius: 3px;
  background: none; color: var(--accent-light); cursor: pointer;
}
.suite-budget__sim-btn:hover { border-color: var(--accent); }

/* Duration histogram */
.tests__dur-hist {  display: flex; align-items: flex-end; gap: 4px; margin-top: 8px; padding: 4px 6px 4px;
  background: var(--bg-base); border: 1px solid var(--border); border-radius: 6px;
  overflow-x: auto;
}
.tests__dur-hist-label { font-size: .6rem; color: var(--text-muted); flex-shrink: 0; align-self: center; margin-right: 2px; }
.tests__dur-bar-wrap {
  display: flex; flex-direction: column; align-items: center; gap: 2px; cursor: pointer;
  background: none; border: 1px solid transparent; border-radius: 4px; padding: 3px 5px;
  transition: all var(--tr-fast); min-width: 44px; flex-shrink: 0;
}
.tests__dur-bar-wrap:hover { border-color: var(--border); background: rgba(255,255,255,.03); }
.tests__dur-bar-wrap--active { border-color: var(--accent) !important; background: var(--accent-bg) !important; }
.tests__dur-bar { border-radius: 2px 2px 0 0; width: 22px; transition: height .2s; }
.tests__dur-bar-lbl { font-size: .52rem; color: var(--text-muted); white-space: nowrap; }
.tests__dur-bar-count { font-size: .6rem; color: var(--text-sec); font-weight: 600; }
.tests__dur-clear {
  font-size: .58rem; padding: 2px 7px; border: 1px solid var(--border); border-radius: 4px;
  background: none; cursor: pointer; color: var(--text-muted); align-self: center; margin-left: 2px;
  transition: all var(--tr-fast);
}
.tests__dur-clear:hover { color: var(--red); border-color: var(--red); }

/* Last run alert banner */
.tests__run-alert {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  padding: 6px 10px; margin-bottom: 8px; border-radius: 6px;
  background: rgba(239,68,68,.07); border: 1px solid rgba(239,68,68,.25);
  cursor: pointer; transition: border-color var(--tr-fast), background var(--tr-fast);
  font-size: .7rem;
}
.tests__run-alert:hover { border-color: rgba(239,68,68,.5); background: rgba(239,68,68,.12); }
.tests__run-alert-icon { flex-shrink: 0; }
.tests__run-alert-text { color: var(--text-sec); flex-shrink: 0; }
.tests__run-alert-pill {
  padding: 1px 7px; border-radius: 10px; font-size: .62rem; font-weight: 700;
}
.tests__run-alert-pill--fail { background: rgba(239,68,68,.2); color: var(--red); }
.tests__run-alert-pill--pass { background: rgba(74,222,128,.15); color: var(--green); }
.tests__run-alert-pill--warn { background: rgba(251,191,36,.15); color: var(--yellow); }
.tests__run-alert-link { margin-left: auto; color: var(--text-muted); font-size: .62rem; flex-shrink: 0; }

/* Row hover preview popover */
.row-preview {
  position: fixed; z-index: 9000; width: 230px;
  background: var(--bg-card); border: 1px solid var(--border);
  border-radius: 7px; padding: 10px 12px;
  box-shadow: 0 8px 24px rgba(0,0,0,.5), 0 2px 8px rgba(0,0,0,.3);
  pointer-events: auto;
  animation: row-preview-in .12s ease-out;
}
@keyframes row-preview-in {
  from { opacity: 0; transform: translateY(-50%) scale(.96); }
  to   { opacity: 1; transform: translateY(-50%) scale(1); }
}
.row-preview__name {
  font-size: .7rem; font-weight: 600; color: var(--text);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; margin-bottom: 2px;
}
.row-preview__rank {
  font-size: .6rem; color: var(--text-muted); margin-bottom: 6px;
}
.row-preview__score-bar {
  display: flex; height: 6px; border-radius: 3px; overflow: hidden;
  background: rgba(51,65,85,.5); margin-bottom: 3px;
}
.row-preview__score-seg { height: 100%; transition: width .3s; }
.row-preview__score-labels {
  display: flex; gap: 6px; margin-bottom: 6px;
}
.row-preview__score-labels span { font-size: .55rem; font-weight: 600; }
.row-preview__badges {
  display: flex; flex-wrap: wrap; gap: 3px; margin-bottom: 6px;
}
.row-preview__badge {
  font-size: .55rem; padding: 1px 5px; border-radius: 3px; font-weight: 600;
}
.row-preview__badge--fail { background: rgba(239,68,68,.15); color: #f87171; }
.row-preview__badge--new { background: rgba(74,222,128,.15); color: #4ade80; }
.row-preview__badge--changed { background: rgba(251,191,36,.15); color: #fbbf24; }
.row-preview__badge--slow { background: rgba(251,146,60,.15); color: #fb923c; }
.row-preview__badge--fast { background: rgba(34,211,238,.12); color: #22d3ee; }
.row-preview__dur {
  font-size: .62rem; color: #94a3b8; margin-bottom: 5px;
}
.row-preview__hist {
  display: flex; gap: 2px; margin-bottom: 5px; align-items: center;
}
.row-preview__spark {
  margin-bottom: 4px;
}
.row-preview__hint {
  font-size: .55rem; color: #475569; border-top: 1px solid #1e293b;
  padding-top: 5px; margin-top: 2px;
}
.run-preview {
  display: flex; align-items: center; gap: 5px; flex-wrap: wrap;
  padding: 5px 8px; margin-bottom: 8px;
  background: rgba(30,41,59,.5); border: 1px solid var(--border);
  border-radius: 6px; overflow: hidden;
}
.run-preview__label { font-size: .6rem; color: var(--text-muted); white-space: nowrap; flex-shrink: 0; }
.run-preview__item {
  display: inline-flex; align-items: center; gap: 3px;
  padding: 2px 7px 2px 5px; border-radius: 10px;
  border: 1px solid var(--border); background: rgba(51,65,85,.4);
  cursor: pointer; white-space: nowrap; transition: all var(--tr-fast);
}
.run-preview__item:hover { background: rgba(99,102,241,.2); border-color: var(--accent); }
.run-preview__item--selected { border-color: var(--accent); background: rgba(99,102,241,.2); }
.run-preview__item--fail { border-color: rgba(248,113,113,.4); background: rgba(248,113,113,.1); }
.run-preview__item--fail:hover { background: rgba(248,113,113,.2); }
.run-preview__item--flaky { border-color: rgba(251,146,60,.4); background: rgba(251,146,60,.08); }
.run-preview__item--changed { border-color: rgba(234,179,8,.3); }
.run-preview__item--new { border-color: rgba(74,222,128,.3); }
.run-preview__num { font-size: .58rem; color: var(--text-muted); font-weight: 700; min-width: 10px; }
.run-preview__name { font-size: .65rem; color: var(--text-sec); }
.run-preview__flag { font-size: .6rem; line-height: 1; color: var(--red); }
.run-preview__more { font-size: .6rem; color: var(--text-muted); white-space: nowrap; }
.tests-overview__row--blame { background: rgba(234,179,8,.08) !important; box-shadow: inset 2px 0 0 var(--yellow); }
.tests-overview__row--blame:hover { background: rgba(234,179,8,.15) !important; }
.tests-overview__row--blame-dim { opacity: .35; }
.blame-banner {
  display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
  padding: 5px 10px; margin-bottom: 8px; border-radius: 5px;
  background: rgba(234,179,8,.1); border: 1px solid rgba(234,179,8,.3);
  font-size: .68rem; color: var(--yellow);
}
.blame-banner__icon { font-size: .8rem; }
.blame-banner strong { color: var(--yellow); }
.blame-banner__close {
  margin-left: auto; border: 1px solid rgba(234,179,8,.4); background: transparent;
  color: var(--yellow); width: 18px; height: 18px; border-radius: 4px;
  cursor: pointer; font-size: .65rem; display: flex; align-items: center; justify-content: center;
  transition: all var(--tr-fast); flex-shrink: 0;
}
.blame-banner__close:hover { background: rgba(234,179,8,.2); }
.health-micro {
  display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
  padding: 5px 8px; margin-top: 6px;
  background: rgba(30,41,59,.4); border: 1px solid var(--border);
  border-radius: 5px; font-size: .62rem;
}
.health-micro__label { color: var(--text-muted); white-space: nowrap; }
.health-micro__val { font-weight: 700; }
.health-micro__sep { color: var(--border); }
.health-micro__bars {
  display: flex; height: 8px; border-radius: 4px; overflow: hidden;
  width: 80px; flex-shrink: 0; gap: 1px;
}
.health-micro__bar-seg { height: 100%; min-width: 2px; transition: width .3s ease; }
.health-micro__legend { display: flex; gap: 5px; align-items: center; font-weight: 700; font-size: .6rem; }
.pos-strip {
  display: flex; align-items: center; gap: 3px; flex-wrap: nowrap;
  overflow: hidden; padding: 5px 0 6px; margin-bottom: 4px;
  border-bottom: 1px solid rgba(51,65,85,.5);
}
.pos-strip__item {
  display: inline-flex; align-items: center; gap: 3px;
  padding: 2px 7px; border-radius: 10px; cursor: pointer;
  border: 1px solid var(--border); background: rgba(30,41,59,.5);
  transition: all var(--tr-fast); flex-shrink: 0; white-space: nowrap;
}
.pos-strip__item:hover:not(.pos-strip__item--current) { background: rgba(99,102,241,.15); border-color: var(--accent); }
.pos-strip__item--current {
  background: rgba(99,102,241,.2); border-color: var(--accent);
  cursor: default; box-shadow: 0 0 0 1px rgba(99,102,241,.3);
}
.pos-strip__item--fail { border-color: rgba(248,113,113,.4); background: rgba(248,113,113,.08); }
.pos-strip__item--current.pos-strip__item--fail { background: rgba(248,113,113,.18); }
.pos-strip__item--flaky { border-color: rgba(251,146,60,.35); }
.pos-strip__rank { font-size: .58rem; color: var(--text-muted); font-weight: 700; }
.pos-strip__name { font-size: .62rem; color: var(--text-sec); max-width: 140px; overflow: hidden; text-overflow: ellipsis; }
.pos-strip__item--current .pos-strip__name { color: var(--accent-light); font-weight: 600; }
.pos-strip__score { font-size: .58rem; color: var(--text-muted); }
.pos-strip__ellipsis { font-size: .65rem; color: var(--text-muted); padding: 0 2px; }
.pos-strip__total { font-size: .58rem; color: var(--text-muted); white-space: nowrap; margin-left: 2px; }
</style>
<style>
/* Row preview popover — must be global since it's teleported to body */
.row-preview .tests-hist-dot { width: 7px !important; height: 7px !important; }
</style>
