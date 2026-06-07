<script setup lang="ts">
import { inject, watch, nextTick, onMounted, computed, ref, type Ref } from 'vue'
import * as d3 from 'd3'
import type { DashboardState } from '../composables/useDashboard'
import type { TestHoverState } from '../composables/useTestHover'
import { sn, esc, DIST, fmtDur, fmtTime, computeScore, computeApfd, computeSetCoverBonuses } from '../utils'
import { useClassHover } from '../composables/useClassInfo'
import ClassInfoCard from './ClassInfoCard.vue'
import SuiteHealthCard from './SuiteHealthCard.vue'
import { mkChart, destroyCharts, chartOpts } from '../composables/useCharts'

const d = inject<DashboardState>('dashboard')!
const shortNames = inject<Ref<boolean>>('shortNames', { value: true } as any)
const testHover = inject<TestHoverState>('testHover')!

const classHover = useClassHover()

function dn(name: string): string {
  return shortNames.value ? sn(name) : name
}

const TL_IDS = ['tl-apfd', 'tl-fail', 'tl-ffp', 'tl-cnt'] as const
const DIST_IDS = ['d-score', 'd-dur', 'd-deps', 'd-fail'] as const

// Stats summaries
const runStats = computed(() => {
  if (!d.runs.length) return null
  const apfds = d.runs.map(r => r.apfd * 100)
  const failures = d.runs.map(r => r.totalFailures)
  const totalDurationMs = d.tests.filter(t => t.duration >= 0).reduce((s, t) => s + t.duration, 0)
  const failingRuns = d.runs.filter(r => r.firstFailurePosition >= 0)
  const avgFirstFailPos = failingRuns.length
    ? Math.round(failingRuns.reduce((s, r) => s + r.firstFailurePosition, 0) / failingRuns.length)
    : null
  return {
    runsWithFailures: d.runs.filter(r => r.totalFailures > 0).length,
    totalFailureEvents: failures.reduce((s, f) => s + f, 0),
    bestApfd: Math.max(...apfds).toFixed(1),
    worstApfd: Math.min(...apfds).toFixed(1),
    avgApfd: (apfds.reduce((s, a) => s + a, 0) / apfds.length).toFixed(1),
    firstRun: new Date(d.runs[0].timestamp),
    lastRun: new Date(d.runs[d.runs.length - 1].timestamp),
    avgTests: Math.round(d.runs.reduce((s, r) => s + r.totalTests, 0) / d.runs.length),
    totalDurationMs,
    avgFirstFailPos,
  }
})

// Session health arc — trend narrative and pass streak for header banner
const healthArc = computed(() => {
  if (d.runs.length < 2) return null
  const apfds = d.runs.map(r => r.apfd * 100) // runs oldest-first: apfds[0]=oldest, apfds[last]=newest
  const recent3Avg = apfds.slice(-3).reduce((s, a) => s + a, 0) / Math.min(3, apfds.length)
  const early3Avg = apfds.slice(0, 3).reduce((s, a) => s + a, 0) / Math.min(3, apfds.length)
  const apfdDelta = recent3Avg - early3Avg

  // Count most recent consecutive clean runs (d.runs is ascending: d.runs[last] = newest)
  let cleanTail = 0
  for (let i = d.runs.length - 1; i >= 0; i--) {
    if (d.runs[i].totalFailures > 0) break
    cleanTail++
  }

  // Count total unique failing test names across all runs
  const failedTestNames = new Set<string>()
  for (const run of d.runs) {
    for (const o of run.outcomes) {
      if (o.failed) failedTestNames.add(o.testClass)
    }
  }

  // APFD trend: linear regression on chronological order (oldest→newest)
  const apfdsChron = [...apfds].reverse()
  const n = apfdsChron.length
  const meanX = (n - 1) / 2
  const meanY = apfdsChron.reduce((s, a) => s + a, 0) / n
  let num = 0, den = 0
  apfdsChron.forEach((a, i) => { num += (i - meanX) * (a - meanY); den += (i - meanX) ** 2 })
  const slope = den ? num / den : 0

  const trend = slope > 0.5 ? 'improving' : slope < -0.5 ? 'degrading' : 'stable'
  const trendIcon = trend === 'improving' ? '↗' : trend === 'degrading' ? '↘' : '→'
  const trendColor = trend === 'improving' ? 'var(--green)' : trend === 'degrading' ? 'var(--red)' : 'var(--yellow)'

  // Build narrative
  let narrative = `${d.runs.length} runs · APFD ${(+apfds[apfds.length - 1]).toFixed(0)}% latest`
  if (Math.abs(apfdDelta) >= 2) {
    narrative += ` · ${apfdDelta > 0 ? '+' : ''}${apfdDelta.toFixed(0)}% vs early runs`
  }
  if (cleanTail >= 2) narrative += ` · ${cleanTail} clean in a row`
  else if (cleanTail === 0 && d.runs[d.runs.length - 1].totalFailures > 0) {
    const lastRun = d.runs[d.runs.length - 1]
    narrative += ` · last run had ${lastRun.totalFailures} failure${lastRun.totalFailures > 1 ? 's' : ''}`
  }
  if (failedTestNames.size > 0) narrative += ` · ${failedTestNames.size} unique test${failedTestNames.size > 1 ? 's' : ''} ever failed`

  return { trend, trendIcon, trendColor, narrative, apfdDelta, cleanTail, failedTestNames: failedTestNames.size, slope }
})

// Time-range filter for timeline charts
const timeRangeOpt = ref<'all' | 'last5' | 'last10' | 'last30d'>('all')

// Show absent (not-in-run) entries in run diff table
const showAbsent = ref(false)

// Run detail filter state
const showFailuresOnly = ref(false)
const runDetailSearch = ref('')
const runDetailPage = ref(0)
const RUN_DETAIL_PAGE_SIZES = [25, 50, 100, 0] // 0 = All
const runDetailPageSize = ref(25)

const filteredRunOutcomes = computed(() => {
  let outcomes = selectedRunOutcomesSorted.value
  if (showFailuresOnly.value) outcomes = outcomes.filter(o => o.failed)
  if (runDetailSearch.value) {
    const q = runDetailSearch.value.toLowerCase()
    outcomes = outcomes.filter(o => o.testClass.toLowerCase().includes(q))
  }
  return outcomes
})

const pagedRunOutcomes = computed(() => {
  const all = filteredRunOutcomes.value
  if (!runDetailPageSize.value) return all
  const start = runDetailPage.value * runDetailPageSize.value
  return all.slice(start, start + runDetailPageSize.value)
})

const runDetailPageCount = computed(() => {
  if (!runDetailPageSize.value) return 1
  return Math.ceil(filteredRunOutcomes.value.length / runDetailPageSize.value)
})

// Speed ratio history per test across all runs (oldest→newest), for duration trend sparkline
const speedRatioHistoryMap = computed(() => {
  const m = new Map<string, number[]>()
  if (d.runs.length < 3) return m
  for (let i = 0; i < d.runs.length; i++) {
    for (const o of (d.runs[i].outcomes || [])) {
      const arr = m.get(o.testClass) ?? []
      arr.push(o.speedRatio)
      m.set(o.testClass, arr)
    }
  }
  return m
})

function speedTrendSvg(name: string): string | null {
  const series = speedRatioHistoryMap.value.get(name)
  if (!series || series.length < 3) return null
  const last = series.slice(-8)
  const W = 36, H = 12
  const maxV = Math.max(...last.map(Math.abs), 0.1)
  const mid = H / 2
  const pts = last.map((v, i) => {
    const x = (i / (last.length - 1)) * W
    const y = mid - (v / maxV) * (mid - 1)
    return `${x.toFixed(1)},${y.toFixed(1)}`
  })
  const trend = last[last.length - 1] - last[0]
  // speedRatio < 0 = faster than median; > 0 = slower. Trending negative = getting faster = green.
  const color = trend < -0.05 ? '#4ade80' : trend > 0.05 ? '#fb923c' : '#64748b'
  return `<svg width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" style="display:block;overflow:visible"><line x1="0" y1="${mid}" x2="${W}" y2="${mid}" stroke="#334155" stroke-width="0.5"/><polyline points="${pts.join(' ')}" fill="none" stroke="${color}" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>`
}

const historySpan = computed(() => {
  if (d.runs.length < 2) return null
  const ms = d.runs[d.runs.length - 1].timestamp - d.runs[0].timestamp
  const days = Math.floor(ms / 86400000)
  const hours = Math.floor((ms % 86400000) / 3600000)
  if (days > 0) return `${days}d ${hours}h`
  const mins = Math.floor((ms % 3600000) / 60000)
  if (hours > 0) return `${hours}h ${mins}m`
  return `${mins}m`
})

const filteredRuns = computed(() => {
  if (timeRangeOpt.value === 'all') return d.runs
  if (timeRangeOpt.value === 'last5') return d.runs.slice(-5)
  if (timeRangeOpt.value === 'last10') return d.runs.slice(-10)
  if (timeRangeOpt.value === 'last30d') {
    const cutoff = Date.now() - 30 * 24 * 60 * 60 * 1000
    return d.runs.filter(r => r.timestamp >= cutoff)
  }
  return d.runs
})

const runsSpanMultipleDays = computed(() => {
  if (d.runs.length < 2) return false
  const first = new Date(d.runs[0].timestamp).toDateString()
  const last = new Date(d.runs[d.runs.length - 1].timestamp).toDateString()
  return first !== last
})

function fmtRunChipTime(ts: number): string {
  const dt = new Date(ts)
  const hhmm = dt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  if (!runsSpanMultipleDays.value) return hhmm
  return `${String(dt.getMonth() + 1).padStart(2, '0')}/${String(dt.getDate()).padStart(2, '0')} ${hhmm}`
}

function baseOpts() {
  const o = chartOpts() as Record<string, unknown>
  return {
    ...o,
    plugins: {
      ...(o.plugins as Record<string, unknown> ?? {}),
      tooltip: { ...((o.plugins as Record<string, unknown>)?.tooltip ?? {}), mode: 'index' as const, intersect: false },
    },
    scales: {
      x: { ticks: { color: '#8899aa', font: { size: 9 }, maxRotation: 30 }, grid: { color: 'rgba(71,85,105,.25)' } },
      y: { ticks: { color: '#8899aa', font: { size: 10 } }, grid: { color: 'rgba(71,85,105,.25)' } },
    },
  }
}

function initTimeline() {
  const filteredRunsOldestFirst = filteredRuns.value
  if (!filteredRunsOldestFirst.length) return
  destroyCharts(...TL_IDS)

  // Reverse so charts display newest run on the left (descending chronological x-axis)
  const runs = [...filteredRunsOldestFirst].reverse()

  // Include date prefix if runs span >1 calendar day
  const firstDay = new Date(runs[0].timestamp).toDateString()
  const lastDay = new Date(runs[runs.length - 1].timestamp).toDateString()
  const multiDay = firstDay !== lastDay

  const labels = runs.map(r => {
    const dt = new Date(r.timestamp)
    const hhmm = `${String(dt.getHours()).padStart(2, '0')}:${String(dt.getMinutes()).padStart(2, '0')}`
    if (!multiDay) return hhmm
    return `${String(dt.getMonth() + 1).padStart(2, '0')}/${String(dt.getDate()).padStart(2, '0')} ${hhmm}`
  })

  // Chart click: chart index is into newest-first `runs`, but selectRun takes d.runs index.
  // d.runs is sorted ascending, filteredRuns is a suffix slice of d.runs (newest N runs).
  // runs[i] = filteredRunsOldestFirst[filteredRunsOldestFirst.length - 1 - i]
  //         = d.runs[d.runs.length - filteredRunsOldestFirst.length + filteredRunsOldestFirst.length - 1 - i]
  //         = d.runs[d.runs.length - 1 - i]  (when full range)
  // General formula: offset = d.runs.length - filteredRunsOldestFirst.length
  const runOffset = d.runs.length - filteredRunsOldestFirst.length
  const chartIdxToRunIdx = (i: number) => runOffset + filteredRunsOldestFirst.length - 1 - i

  mkChart('tl-apfd', {
    type: 'line', data: { labels, datasets: [
      { label: 'APFD %', data: runs.map(r => +(r.apfd * 100).toFixed(1)), borderColor: '#22c55e', backgroundColor: 'rgba(34,197,94,.1)', fill: true, tension: .3, pointRadius: 3, pointHoverRadius: 5 },
      { label: 'Random (50%)', data: labels.map(() => 50), borderColor: '#475569', borderDash: [6, 4], borderWidth: 1.5, pointRadius: 0, tension: 0 },
    ] }, options: {
      ...baseOpts(),
      plugins: { ...baseOpts().plugins, legend: { display: true, labels: { color: '#8899aa', font: { size: 9 }, boxWidth: 10, padding: 6 } } },
      scales: { ...baseOpts().scales, y: { ...baseOpts().scales.y, min: 0, max: 100 } },
      onClick: (_e: unknown, elements: { index: number }[]) => {
        if (elements.length > 0) selectRun(chartIdxToRunIdx(elements[0].index))
      },
    },
  })
  mkChart('tl-fail', {
    type: 'bar', data: { labels, datasets: [{ label: 'Failures', data: runs.map(r => r.totalFailures), backgroundColor: runs.map(r => r.totalFailures > 0 ? 'rgba(239,68,68,.7)' : 'rgba(34,197,94,.5)') }] },
    options: baseOpts(),
  })
  mkChart('tl-ffp', {
    type: 'line', data: { labels, datasets: [{
      label: 'First failure position', data: runs.map(r => r.firstFailurePosition >= 0 ? r.firstFailurePosition + 1 : 0),
      pointBackgroundColor: runs.map(r => r.firstFailurePosition >= 0 ? 'rgba(239,68,68,.8)' : 'transparent'),
      pointBorderColor: runs.map(r => r.firstFailurePosition >= 0 ? 'rgba(239,68,68,.8)' : 'rgba(71,85,105,.7)'),
      pointBorderWidth: 1.5, pointRadius: 5, showLine: false, borderColor: 'transparent',
    }] }, options: baseOpts(),
  })
  mkChart('tl-cnt', {
    type: 'line', data: { labels, datasets: [{ label: 'Test count', data: runs.map(r => r.totalTests), borderColor: '#6366f1', backgroundColor: 'rgba(99,102,241,.12)', fill: true, stepped: 'before' as const, tension: 0 }] },
    options: baseOpts(),
  })
}

function initDistributions() {
  try {
  destroyCharts(...DIST_IDS)
  const scores = d.tests.map(t => t.score)
  const maxS = Math.max(...scores, 1)
  const bs = Math.max(1, Math.ceil(maxS / DIST.MAX_SCORE_BUCKETS))
  const sBuckets: Record<number, number> = {}
  scores.forEach(s => { const b = Math.floor(s / bs) * bs; sBuckets[b] = (sBuckets[b] || 0) + 1 })
  const sKeys = Object.keys(sBuckets).map(Number).sort((a, b) => a - b)
  mkChart('d-score', {
    type: 'bar', data: { labels: sKeys.map(k => `${k}`), datasets: [{ label: 'Tests', data: sKeys.map(k => sBuckets[k]), backgroundColor: 'rgba(99,102,241,.7)', borderRadius: 2 }] },
    options: { ...chartOpts(), scales: { x: { ticks: { color: '#8899aa', font: { size: 9 } }, grid: { color: 'rgba(71,85,105,.2)' } }, y: { ticks: { color: '#8899aa', font: { size: 9 } }, grid: { color: 'rgba(71,85,105,.2)' } } } },
  })

  const dBkts = [0, 0, 0, 0, 0]
  d.tests.forEach(t => { if (t.duration < 0) return; if (t.duration < 10) dBkts[0]++; else if (t.duration < 100) dBkts[1]++; else if (t.duration < 1000) dBkts[2]++; else if (t.duration < 10000) dBkts[3]++; else dBkts[4]++ })
  mkChart('d-dur', {
    type: 'bar', data: { labels: ['<10ms', '10-100ms', '100ms-1s', '1-10s', '>10s'], datasets: [{ label: 'Tests', data: dBkts, backgroundColor: ['#06b6d4', '#22c55e', '#f59e0b', '#f97316', '#ef4444'], borderRadius: 2 }] },
    options: chartOpts(),
  })

  const deps = d.tests.map(t => t.depTotal || 0)
  const maxD = Math.max(...deps, 1)
  const db = Math.max(1, Math.ceil(maxD / DIST.MAX_DEP_BUCKETS))
  const dBuckets: Record<number, number> = {}
  deps.forEach(dep => { const b = Math.floor(dep / db) * db; dBuckets[b] = (dBuckets[b] || 0) + 1 })
  const dKeys = Object.keys(dBuckets).map(Number).sort((a, b) => a - b)
  mkChart('d-deps', {
    type: 'bar', data: { labels: dKeys.map(k => `${k}`), datasets: [{ label: 'Tests', data: dKeys.map(k => dBuckets[k]), backgroundColor: 'rgba(168,85,247,.7)', borderRadius: 2 }] },
    options: { ...chartOpts(), scales: { x: { ticks: { color: '#8899aa', font: { size: 9 } }, grid: { color: 'rgba(71,85,105,.2)' } }, y: { ticks: { color: '#8899aa', font: { size: 9 } }, grid: { color: 'rgba(71,85,105,.2)' } } } },
  })

  const top20 = [...d.tests].filter(t => t.failScore > 0).sort((a, b) => b.failScore - a.failScore).slice(0, DIST.TOP_FAIL_COUNT)
  mkChart('d-fail', {
    type: 'bar', data: { labels: top20.map(t => sn(t.name)), datasets: [{ label: 'Fail score', data: top20.map(t => +t.failScore.toFixed(2)), backgroundColor: 'rgba(239,68,68,.7)', borderRadius: 2 }] },
    options: { ...chartOpts(), indexAxis: 'y' as const, scales: { x: { ticks: { color: '#8899aa', font: { size: 9 } }, grid: { color: 'rgba(71,85,105,.2)' } }, y: { ticks: { color: '#8899aa', font: { size: 8 }, maxTicksLimit: DIST.TOP_FAIL_COUNT }, grid: { display: false } } } },
  })
  } catch (e) { console.error('[dashboard] Distribution charts failed:', e) }
}

// Treemap: separate layout build from color updates
let treemapLeaves: d3.Selection<SVGRectElement, d3.HierarchyRectangularNode<unknown>, SVGSVGElement, unknown> | null = null
let treemapColorScale: ((t: number) => string) | null = null
let treemapMaxTests = 1

function tileFill(c: any, highlightSources: Set<string> | null): string {
  if (!c) return '#334155'
  if (highlightSources) return highlightSources.has(c.name) ? '#22c55e' : '#1e293b'
  if (d.hasMethodCoverage.value && c.totalMembers > 0) {
    const pct = c.coveredMembers / c.totalMembers
    const sat = Math.max(0.45, Math.min(1.0, 0.45 + (Math.log(Math.max(c.testCount, 1)) / Math.log(Math.max(treemapMaxTests, 2))) * 0.55))
    const rgb = d3.rgb(d3.interpolateRgb('#ef4444', '#22c55e')(pct))
    const gray = 0.299 * rgb.r + 0.587 * rgb.g + 0.114 * rgb.b
    return d3.rgb(gray + (rgb.r - gray) * sat, gray + (rgb.g - gray) * sat, gray + (rgb.b - gray) * sat).formatHex()
  }
  return treemapColorScale!(c.testCount)
}

function buildCoverageTreemap() {
  try {
  const container = document.getElementById('cov-treemap')
  if (!container || !d.dd.coverage?.classes) return
  d3.select(container).selectAll('*').remove()
  treemapLeaves = null
  const W = container.clientWidth || 700, H = container.clientHeight || 350

  const selCov = d.selectionCoverage.value
  const highlightSources = selCov ? selCov.sources : null

  const byPkg: Record<string, typeof d.dd.coverage.classes> = {}
  d.dd.coverage.classes.forEach(c => {
    const pkg = c.package || '(default)'
    if (!byPkg[pkg]) byPkg[pkg] = []
    byPkg[pkg].push(c)
  })
  const root = { name: 'root', children: Object.entries(byPkg).map(([pkg, cls]) => ({ name: pkg, children: cls.map(c => ({ name: c.name, value: Math.max(c.testCount, 1), data: c })) })) }

  const hierarchy = d3.hierarchy(root).sum((nd: any) => nd.value || 0).sort((a, b) => (b.value || 0) - (a.value || 0))
  d3.treemap().size([W, H]).padding(2).paddingTop(16).round(true)(hierarchy as any)

  treemapMaxTests = Math.max(...d.dd.coverage.classes.map(c => c.testCount), 1)
  treemapColorScale = d3.scaleSequential(t => t === 0 ? '#1e293b' : d3.interpolateRgb('#1e3a5f', '#6366f1')(Math.pow(t, 0.5))).domain([0, treemapMaxTests])

  const svg = d3.select(container).append('svg').attr('width', W).attr('height', H)

  const pkgNodes = svg.selectAll('g.pkg').data((hierarchy as any).children || []).join('g').attr('class', 'pkg')
  pkgNodes.append('text')
    .attr('x', (nd: any) => nd.x0 + 4).attr('y', (nd: any) => nd.y0 + 12)
    .attr('font-size', '9.5px').attr('fill', '#cbd5e1').attr('font-weight', '700')
    .attr('paint-order', 'stroke').attr('stroke', 'rgba(0,0,0,0.7)').attr('stroke-width', '2px').attr('stroke-linejoin', 'round')
    .text((nd: any) => { const nm = nd.data.name.split('.').pop(); return (nd.x1 - nd.x0) > 40 ? nm : '' })

  const leaves = svg.selectAll('rect.leaf').data(hierarchy.leaves()).join('rect').attr('class', 'leaf tree-node')
    .attr('x', (nd: any) => nd.x0).attr('y', (nd: any) => nd.y0)
    .attr('width', (nd: any) => Math.max(nd.x1 - nd.x0, 0))
    .attr('height', (nd: any) => Math.max(nd.y1 - nd.y0, 0))
    .attr('fill', (nd: any) => tileFill(nd.data.data, highlightSources))
    .attr('stroke', (nd: any) => {
      if (!nd.data.data) return '#0f172a'
      if (d.covSelectedClass.value?.name === nd.data.data.name) return '#f8fafc'
      if (highlightSources && highlightSources.has(nd.data.data.name)) return '#16a34a'
      return '#0f172a'
    })
    .attr('stroke-width', (nd: any) => {
      if (!nd.data.data) return 1
      if (d.covSelectedClass.value?.name === nd.data.data.name) return 2.5
      if (highlightSources && highlightSources.has(nd.data.data.name)) return 2
      return 1
    })
    .attr('rx', 2)

  svg.selectAll('text.leaf-label').data(hierarchy.leaves()).join('text').attr('class', 'leaf-label')
    .attr('x', (nd: any) => nd.x0 + 4)
    .attr('y', (nd: any) => Math.max(nd.y0 + 14, nd.y0 + ((nd.y1 - nd.y0) / 2) + 4))
    .attr('font-size', (nd: any) => {
      const w = nd.x1 - nd.x0, h = nd.y1 - nd.y0
      if (w < 36 || h < 14) return '0px'
      return w > 80 && h > 20 ? '10px' : '8.5px'
    })
    .attr('fill', (nd: any) => {
      if (!nd.data.data) return '#e2e8f0'
      const c = nd.data.data
      const fill = tileFill(c, highlightSources)
      const brightness = d3.rgb(fill).r * 0.299 + d3.rgb(fill).g * 0.587 + d3.rgb(fill).b * 0.114
      return brightness > 128 ? '#0f172a' : '#f1f5f9'
    })
    .attr('font-weight', '700')
    .attr('paint-order', 'stroke')
    .attr('stroke', (nd: any) => {
      if (!nd.data.data) return 'none'
      const fill = tileFill(nd.data.data, highlightSources)
      const brightness = d3.rgb(fill).r * 0.299 + d3.rgb(fill).g * 0.587 + d3.rgb(fill).b * 0.114
      return brightness > 128 ? 'rgba(255,255,255,0.4)' : 'rgba(0,0,0,0.6)'
    })
    .attr('stroke-width', '2.5px')
    .attr('stroke-linejoin', 'round')
    .text((nd: any) => {
      const w = nd.x1 - nd.x0, h = nd.y1 - nd.y0
      if (w < 36 || h < 14) return ''
      const nm = nd.data.name.split('.').pop()
      const maxChars = Math.floor(w / (w > 80 ? 6.5 : 5.5))
      return nm.length > maxChars ? nm.substring(0, Math.max(3, maxChars - 1)) + '…' : nm
    })

  // SVG-native title tooltips on small tiles
  leaves.append('title').text((nd: any) => nd.data?.data?.name ?? '')

  // Method coverage progress bars (thin strip at bottom of each tile when member data available)
  if (d.hasMethodCoverage.value) {
    svg.selectAll('rect.cov-bar')
      .data(hierarchy.leaves().filter((nd: any) => {
        const c = nd.data?.data; return c && c.totalMembers > 0 && (nd.y1 - nd.y0) > 20
      }))
      .join('rect').attr('class', 'cov-bar')
      .attr('x', (nd: any) => nd.x0 + 1)
      .attr('y', (nd: any) => nd.y1 - 3)
      .attr('width', (nd: any) => {
        const c = nd.data.data
        return Math.max((nd.x1 - nd.x0 - 2) * (c.coveredMembers / c.totalMembers), 0)
      })
      .attr('height', 2)
      .attr('rx', 1)
      .attr('fill', (nd: any) => {
        const c = nd.data.data
        return d3.interpolateRgb('#ef4444', '#22c55e')(c.coveredMembers / c.totalMembers)
      })
      .attr('pointer-events', 'none')
  }

  const tip = d3.select(container).append('div')
    .style('position', 'absolute').style('background', '#1e293b').style('border', '1px solid #334155')
    .style('padding', '6px 10px').style('border-radius', '4px').style('font-size', '11px')
    .style('color', '#e2e8f0').style('pointer-events', 'none').style('opacity', '0').style('z-index', '10')

  leaves.on('mouseover', (e: MouseEvent, nd: any) => {
    if (!nd.data.data) return
    const c = nd.data.data
    const selHit = highlightSources ? (highlightSources.has(c.name) ? ' · <span style="color:#22c55e">covered by selection</span>' : ' · <span style="color:#64748b">not in selection</span>') : ''
    const pctStr = c.totalMembers > 0
      ? ` · <span style="color:#94a3b8">${c.coveredMembers}/${c.totalMembers} methods (${Math.round(c.coveredMembers/c.totalMembers*100)}%)</span>`
      : ''
    tip.style('opacity', '1').html(`<strong>${esc(sn(c.name))}</strong><br><span style="color:#64748b">${c.testCount} test${c.testCount === 1 ? '' : 's'}${selHit}${pctStr}</span>`)
    classHover.show(c.name, e)
  }).on('mousemove', (e: MouseEvent) => { tip.style('left', (e.offsetX + 12) + 'px').style('top', (e.offsetY - 10) + 'px'); classHover.move(e) })
    .on('mouseout', () => { tip.style('opacity', '0'); classHover.hide() })
    .on('click', (_e: MouseEvent, nd: any) => { if (nd.data.data) d.covSelectedClass.value = nd.data.data })

  treemapLeaves = leaves as any
  } catch (e) { console.error('[dashboard] Coverage treemap failed:', e) }
}

/** Fast path: only update treemap fill colors when selection changes */
function updateTreemapColors() {
  if (!treemapLeaves || !treemapColorScale) { buildCoverageTreemap(); return }
  const selCov = d.selectionCoverage.value
  const highlightSources = selCov ? selCov.sources : null
  treemapLeaves
    .attr('fill', (nd: any) => tileFill(nd.data.data, highlightSources))
    .attr('stroke', (nd: any) => {
      if (!nd.data.data) return '#0f172a'
      if (d.covSelectedClass.value?.name === nd.data.data.name) return '#f8fafc'
      if (highlightSources && highlightSources.has(nd.data.data.name)) return '#16a34a'
      return '#0f172a'
    })
    .attr('stroke-width', (nd: any) => {
      if (!nd.data.data) return 1
      if (d.covSelectedClass.value?.name === nd.data.data.name) return 2.5
      if (highlightSources && highlightSources.has(nd.data.data.name)) return 2
      return 1
    })
}

/** Fan-out histogram: distribution of test dep-counts. */
function buildFanOutHistogram() {
  const container = document.getElementById('fanout-hist')
  if (!container) return
  const fo = fanOutDistribution.value
  d3.select(container).selectAll('*').remove()
  if (!fo) return
  const W = container.clientWidth || 600, H = 120
  const m = { t: 6, r: 8, b: 22, l: 28 }
  const svg = d3.select(container).append('svg').attr('width', W).attr('height', H)
  const innerW = W - m.l - m.r, innerH = H - m.t - m.b
  const x = d3.scaleLinear().domain([0, fo.hist.length]).range([0, innerW])
  const y = d3.scaleLinear().domain([0, Math.max(1, ...fo.hist)]).range([innerH, 0])
  const g = svg.append('g').attr('transform', `translate(${m.l},${m.t})`)
  // Mean line
  const meanBucket = fo.bw > 0 ? fo.mean / fo.bw : 0
  if (meanBucket > 0 && meanBucket < fo.hist.length) {
    g.append('line').attr('x1', x(meanBucket)).attr('x2', x(meanBucket))
      .attr('y1', 0).attr('y2', innerH).attr('stroke', '#fbbf24').attr('stroke-dasharray', '2,3').attr('stroke-width', 1)
    g.append('text').attr('x', x(meanBucket) + 3).attr('y', 10).attr('font-size', '8.5px').attr('fill', '#fbbf24').text(`mean ${fo.mean}`)
  }
  // Threshold line
  const thrBucket = fo.bw > 0 ? fo.threshold / fo.bw : 0
  if (thrBucket > 0 && thrBucket < fo.hist.length) {
    g.append('line').attr('x1', x(thrBucket)).attr('x2', x(thrBucket))
      .attr('y1', 0).attr('y2', innerH).attr('stroke', '#ef4444').attr('stroke-dasharray', '2,3').attr('stroke-width', 1)
    g.append('text').attr('x', x(thrBucket) + 3).attr('y', 22).attr('font-size', '8.5px').attr('fill', '#ef4444').text(`+2σ ${fo.threshold}`)
  }
  // Bars
  g.selectAll('rect').data(fo.hist).join('rect')
    .attr('x', (_, i) => x(i) + 1).attr('y', (n: number) => y(n))
    .attr('width', Math.max(1, innerW / fo.hist.length - 2)).attr('height', (n: number) => innerH - y(n))
    .attr('fill', (_, i) => i * fo.bw >= fo.threshold ? '#ef4444' : '#6366f1')
    .append('title').text((n: number, i: number) => `${i * fo.bw}–${(i + 1) * fo.bw - 1} deps · ${n} test${n === 1 ? '' : 's'}`)
  // X axis (a few ticks)
  const tickCount = Math.min(6, fo.hist.length)
  const ticks = d3.range(0, fo.hist.length + 1, Math.max(1, Math.floor(fo.hist.length / tickCount)))
  g.append('g').attr('transform', `translate(0,${innerH})`)
    .selectAll('text').data(ticks).join('text')
    .attr('x', d => x(d)).attr('y', 13).attr('font-size', '9px').attr('fill', '#94a3b8').attr('text-anchor', 'middle')
    .text(d => d * fo.bw)
  g.append('text').attr('x', innerW / 2).attr('y', innerH + 20).attr('font-size', '8.5px').attr('fill', '#64748b').attr('text-anchor', 'middle').text('dep count')
  // Y axis
  g.append('text').attr('x', -2).attr('y', 0).attr('font-size', '9px').attr('fill', '#94a3b8').attr('text-anchor', 'end').attr('dy', '0.7em').text(Math.max(1, ...fo.hist))
  g.append('text').attr('x', -2).attr('y', innerH).attr('font-size', '9px').attr('fill', '#94a3b8').attr('text-anchor', 'end').attr('dy', '0.3em').text('0')
}

/** Rank vs Coverage scatter: x = rank, y = total covered classes. */
function buildRankCoverageScatter() {
  const container = document.getElementById('rank-cov-scatter')
  if (!container) return
  const data = rankCoverageScatter.value
  d3.select(container).selectAll('*').remove()
  if (!data.length) return
  const W = container.clientWidth || 600, H = 300
  const m = { t: 10, r: 12, b: 30, l: 40 }
  const svg = d3.select(container).append('svg').attr('width', W).attr('height', H)
  const innerW = W - m.l - m.r, innerH = H - m.t - m.b
  const xMax = Math.max(...data.map(p => p.rank), 1)
  const yMax = Math.max(...data.map(p => p.total), 1)
  const x = d3.scaleLinear().domain([1, xMax]).range([0, innerW])
  const y = d3.scaleLinear().domain([0, yMax]).range([innerH, 0]).nice()
  const g = svg.append('g').attr('transform', `translate(${m.l},${m.t})`)
  // Top-decile band
  const decile = Math.max(1, Math.ceil(xMax * 0.1))
  g.append('rect').attr('x', 0).attr('y', 0).attr('width', x(decile))
    .attr('height', innerH).attr('fill', 'rgba(99,102,241,0.08)')
  g.append('text').attr('x', x(decile) - 4).attr('y', 12).attr('font-size', '9px').attr('fill', '#818cf8').attr('text-anchor', 'end').text('top 10%')
  // Axes
  g.append('line').attr('x1', 0).attr('y1', innerH).attr('x2', innerW).attr('y2', innerH).attr('stroke', '#334155')
  g.append('line').attr('x1', 0).attr('y1', 0).attr('x2', 0).attr('y2', innerH).attr('stroke', '#334155')
  // X ticks
  const xTicks = x.ticks(6)
  g.selectAll('text.x-tick').data(xTicks).join('text').attr('class', 'x-tick')
    .attr('x', d => x(d)).attr('y', innerH + 14).attr('font-size', '9px').attr('fill', '#94a3b8').attr('text-anchor', 'middle').text(d => d)
  g.append('text').attr('x', innerW / 2).attr('y', innerH + 25).attr('font-size', '9px').attr('fill', '#64748b').attr('text-anchor', 'middle').text('rank (1 = first to run)')
  // Y ticks
  const yTicks = y.ticks(5)
  g.selectAll('text.y-tick').data(yTicks).join('text').attr('class', 'y-tick')
    .attr('x', -4).attr('y', d => y(d)).attr('dy', '0.32em').attr('font-size', '9px').attr('fill', '#94a3b8').attr('text-anchor', 'end').text(d => d)
  g.append('text').attr('transform', `translate(-30,${innerH / 2}) rotate(-90)`).attr('font-size', '9px').attr('fill', '#64748b').attr('text-anchor', 'middle').text('classes covered')
  // Dots — exclusive ones drawn last so they sit on top
  const sorted = [...data].sort((a, b) => a.exclusive - b.exclusive)
  g.selectAll('circle').data(sorted).join('circle')
    .attr('cx', p => x(p.rank)).attr('cy', p => y(p.total))
    .attr('r', p => 3 + Math.sqrt(p.exclusive))
    .attr('fill', p => p.exclusive > 0 ? '#ef4444' : '#3b82f6')
    .attr('fill-opacity', 0.75)
    .attr('stroke', p => p.exclusive > 0 ? '#7f1d1d' : '#1d4ed8').attr('stroke-width', 0.6)
    .style('cursor', 'pointer')
    .on('click', (_e: MouseEvent, p: typeof sorted[number]) => d.navigateToTestFromCov(p.name))
    .append('title')
    .text(p => `${dn(p.name)}\nrank ${p.rank} · ${p.total} class${p.total === 1 ? '' : 'es'} covered${p.exclusive > 0 ? ` · ${p.exclusive} exclusive` : ''}`)
}

/** Cross-package coupling matrix: pkg(test) → pkg(dep) heatmap. */
function buildPkgCouplingMatrix() {
  const container = document.getElementById('pkg-coupling-matrix')
  if (!container) return
  const data = pkgCouplingMatrix.value
  d3.select(container).selectAll('*').remove()
  if (!data) return
  const N = data.order.length
  // Cap dimension — show top-N most-active packages by total edges in/out
  const MAX = 24
  let order = data.order
  if (N > MAX) {
    const scored = data.order.map(p => {
      let t = 0
      for (const q of data.order) t += (data.m.get(p)?.get(q) ?? 0) + (data.m.get(q)?.get(p) ?? 0)
      return { p, t }
    }).sort((a, b) => b.t - a.t).slice(0, MAX).map(x => x.p)
    order = scored.sort()
  }
  const W = container.clientWidth || 600
  const labelH = 90, labelW = 140
  const cell = Math.max(8, Math.min(22, Math.floor((W - labelW - 16) / order.length)))
  const H = labelH + cell * order.length + 16
  const svg = d3.select(container).append('svg').attr('width', W).attr('height', H)
  const g = svg.append('g').attr('transform', `translate(${labelW},${labelH})`)
  let mx = 0
  for (const a of order) for (const b of order) {
    const v = data.m.get(a)?.get(b) ?? 0
    if (v > mx) mx = v
  }
  const color = d3.scaleSequential((t: number) => d3.interpolateInferno(0.15 + 0.7 * t)).domain([0, Math.log(mx + 1) || 1])
  // Cells
  const rows = order.flatMap((a, i) => order.map((b, j) => ({ a, b, i, j, v: data.m.get(a)?.get(b) ?? 0 })))
  g.selectAll('rect').data(rows).join('rect')
    .attr('x', d => d.j * cell).attr('y', d => d.i * cell)
    .attr('width', cell - 1).attr('height', cell - 1)
    .attr('fill', d => d.v === 0 ? 'rgba(51,65,85,0.25)' : color(Math.log(d.v + 1)))
    .style('cursor', d => d.v > 0 ? 'pointer' : 'default')
    .on('click', (_e: MouseEvent, d: any) => {
      if (d.v <= 0) return
      const tail = d.b.split('.').slice(-2).join('.')
      d.covSearchQ.value = tail
    })
    .append('title')
    .text(d => `${d.a} → ${d.b}\n${d.v} dep edge${d.v === 1 ? '' : 's'}`)
  // Diagonal stroke for self-pkg
  g.selectAll('rect.diag').data(rows.filter(r => r.a === r.b && r.v > 0)).join('rect').attr('class', 'diag')
    .attr('x', d => d.j * cell).attr('y', d => d.i * cell)
    .attr('width', cell - 1).attr('height', cell - 1)
    .attr('fill', 'none').attr('stroke', '#fbbf24').attr('stroke-width', 1)
    .attr('pointer-events', 'none')
  // Row labels
  g.selectAll('text.row').data(order).join('text').attr('class', 'row')
    .attr('x', -6).attr('y', (_, i) => i * cell + cell / 2 + 3)
    .attr('font-size', '9px').attr('fill', '#94a3b8').attr('text-anchor', 'end')
    .text(p => p.split('.').slice(-2).join('.'))
    .append('title').text(p => p)
  // Column labels (rotated)
  g.selectAll('text.col').data(order).join('text').attr('class', 'col')
    .attr('transform', (_, j) => `translate(${j * cell + cell / 2 + 3},-6) rotate(-50)`)
    .attr('font-size', '9px').attr('fill', '#94a3b8').attr('text-anchor', 'start')
    .text(p => p.split('.').slice(-2).join('.'))
    .append('title').text(p => p)
  // Caption
  svg.append('text').attr('x', labelW).attr('y', labelH + cell * order.length + 12)
    .attr('font-size', '9px').attr('fill', '#64748b')
    .text(`${order.length} of ${N} packages · darker = more dep edges (log scale, max ${mx})`)
}

// Coverage: package-level stats
const covPackageStats = computed(() => {
  if (!d.dd.coverage?.classes) return []
  const map = new Map<string, { name: string; total: number; covered: number; maxTests: number; avgTests: number }>()
  for (const c of d.dd.coverage.classes) {
    const pkg = c.package || '(default)'
    const e = map.get(pkg) ?? { name: pkg, total: 0, covered: 0, maxTests: 0, avgTests: 0 }
    e.total++
    if (c.testCount > 0) e.covered++
    if (c.testCount > e.maxTests) e.maxTests = c.testCount
    e.avgTests += c.testCount
    map.set(pkg, e)
  }
  return [...map.values()]
    .map(p => ({ ...p, avgTests: p.total > 0 ? +(p.avgTests / p.total).toFixed(1) : 0 }))
    .sort((a, b) => b.covered - a.covered)
})

const covUncoveredCount = computed(() => {
  if (!d.dd.coverage?.classes) return 0
  return d.dd.coverage.classes.filter(c => c.testCount === 0).length
})

const covPkgSort = ref<'coverage' | 'alpha' | 'tests'>('coverage')
const redundancyExpanded = ref<Set<number>>(new Set())
const toggleRedundancy = (i: number) => {
  const s = new Set(redundancyExpanded.value)
  if (s.has(i)) s.delete(i); else s.add(i)
  redundancyExpanded.value = s
}
const covPkgStatsSorted = computed(() => {
  const stats = covPackageStats.value
  if (covPkgSort.value === 'alpha') return [...stats].sort((a, b) => a.name.localeCompare(b.name))
  if (covPkgSort.value === 'tests') return [...stats].sort((a, b) => b.avgTests - a.avgTests)
  return stats // already sorted by coverage
})

// Rank heatmap: for each test, its rank position in each run. Show top-N by rank variance.
const HEATMAP_ROWS = 30
const rankHeatmap = computed(() => {
  if (d.runs.length < 2) return null
  // Build rank map per run per test — use chronological order for left-to-right display
  const chronRuns = [...d.runs].reverse() // newest-first for left→right display
  // Pre-build per-run rank maps for O(1) lookup instead of O(outcomes) findIndex per test per run
  const runRankMaps = chronRuns.map(r => {
    const sorted = [...(r.outcomes || [])].sort((a, b) => b.score - a.score)
    const rm = new Map<string, number>()
    sorted.forEach((o, i) => rm.set(o.testClass, i + 1))
    return rm
  })
  const rows = d.tests.map(t => {
    const ranks = runRankMaps.map(rm => rm.get(t.name) ?? null)
    const presentRanks = ranks.filter(r => r !== null) as number[]
    const variance = presentRanks.length < 2 ? 0 : (() => {
      const mean = presentRanks.reduce((s, r) => s + r, 0) / presentRanks.length
      return presentRanks.reduce((s, r) => s + (r - mean) ** 2, 0) / presentRanks.length
    })()
    return { name: t.name, ranks, variance, failScore: t.failScore }
  })
  // Show tests with highest variance + tests with fail history first
  rows.sort((a, b) => (b.failScore > 0 ? 1 : 0) - (a.failScore > 0 ? 1 : 0) || b.variance - a.variance)
  return {
    rows: rows.slice(0, HEATMAP_ROWS),
    runs: chronRuns,
    totalTests: d.tests.length,
  }
})

// Test reliability: fail rate across all runs, for tests seen in at least 2 runs
const testFailRates = computed(() => {
  if (!d.runs.length) return []
  const counts = new Map<string, { fail: number; seen: number }>()
  for (const r of d.runs) {
    for (const o of (r.outcomes || [])) {
      const e = counts.get(o.testClass) ?? { fail: 0, seen: 0 }
      e.seen++
      if (o.failed) e.fail++
      counts.set(o.testClass, e)
    }
  }
  return [...counts.entries()]
    .filter(([, v]) => v.seen >= 2 && v.fail > 0)
    .map(([name, v]) => ({ name, failRate: v.fail / v.seen, failCount: v.fail, seenCount: v.seen }))
    .sort((a, b) => b.failRate - a.failRate)
    .slice(0, 10)
})

// Risk trend: tests with increasing failure rate over recent runs (linear regression slope)
const risingRiskTests = computed(() => {
  if (d.runs.length < 3) return []
  // Build per-test fail timeline (oldest→newest, 1=fail 0=pass)
  const timeline = new Map<string, number[]>()
  for (let i = 0; i < d.runs.length; i++) {
    const r = d.runs[i]
    const failedSet = new Set((r.outcomes || []).filter(o => o.failed).map(o => o.testClass))
    const seenSet = new Set((r.outcomes || []).map(o => o.testClass))
    for (const name of seenSet) {
      const arr = timeline.get(name) ?? []
      arr.push(failedSet.has(name) ? 1 : 0)
      timeline.set(name, arr)
    }
  }
  // Compute simple linear regression slope for each test seen ≥ 3 times
  const results: { name: string; slope: number; recentFails: number; total: number; series: number[] }[] = []
  for (const [name, series] of timeline) {
    if (series.length < 3) continue
    const n = series.length
    const sumX = (n * (n - 1)) / 2
    const sumX2 = (n * (n - 1) * (2 * n - 1)) / 6
    const sumY = series.reduce((s, v) => s + v, 0)
    const sumXY = series.reduce((s, v, i) => s + i * v, 0)
    const slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)
    const recentFails = series.slice(-3).filter(v => v > 0).length
    results.push({ name, slope, recentFails, total: sumY, series })
  }
  // Rising risk = positive slope + at least 1 recent failure
  return results
    .filter(r => r.slope > 0.02 && r.recentFails > 0)
    .sort((a, b) => b.slope - a.slope)
    .slice(0, 8)
})

// Co-failure pairs: which tests fail together most often across runs
const coFailPairs = computed(() => {
  if (!d.runs.length) return []
  const pairCounts = new Map<string, { a: string; b: string; count: number }>()
  for (const r of d.runs) {
    const failed = (r.outcomes || []).filter(o => o.failed).map(o => o.testClass)
    if (failed.length < 2) continue
    for (let i = 0; i < failed.length; i++) {
      for (let j = i + 1; j < failed.length; j++) {
        const key = [failed[i], failed[j]].sort().join('|||')
        const e = pairCounts.get(key) ?? { a: failed[i], b: failed[j], count: 0 }
        e.count++
        pairCounts.set(key, e)
      }
    }
  }
  return [...pairCounts.values()]
    .filter(p => p.count >= 2)
    .sort((a, b) => b.count - a.count)
    .slice(0, 8)
})

// Co-failure burst detection: runs where failure count is significantly above average
// Flags "incident runs" where 2+ std deviations above mean failure count
const failureBursts = computed(() => {
  const failingRuns = d.runs.filter(r => r.totalFailures > 0)
  if (failingRuns.length < 3) return []
  const counts = failingRuns.map(r => r.totalFailures)
  const mean = counts.reduce((s, c) => s + c, 0) / counts.length
  const variance = counts.reduce((s, c) => s + (c - mean) ** 2, 0) / counts.length
  const std = Math.sqrt(variance)
  if (std < 0.5) return [] // all similar — no bursts
  const threshold = Math.max(mean + std * 1.5, mean + 2) // 1.5σ or +2 minimum
  return failingRuns
    .filter(r => r.totalFailures >= threshold)
    .map(r => ({
      ts: r.timestamp,
      count: r.totalFailures,
      zscore: std > 0 ? (r.totalFailures - mean) / std : 0,
      failures: (r.outcomes || []).filter(o => o.failed).map(o => o.testClass),
    }))
    .sort((a, b) => b.count - a.count)
    .slice(0, 5)
})
const budgetSec = ref(30)

const budgetOptimResult = computed(() => {
  const budgetMs = budgetSec.value * 1000
  if (!d.tests.length || budgetMs <= 0) return null

  // Tests with duration data, sorted by priority (rank = current sorted order)
  const withDur = d.tests.filter(t => t.duration >= 0).sort((a, b) => a.rank - b.rank)
  const noDur = d.tests.filter(t => t.duration < 0).sort((a, b) => a.rank - b.rank)

  // Greedy: take highest-priority tests until budget exhausted
  let accumulated = 0
  const included: typeof d.tests = []
  for (const t of withDur) {
    if (accumulated + t.duration <= budgetMs) {
      included.push(t)
      accumulated += t.duration
    }
  }

  const totalSuiteMs = withDur.reduce((s, t) => s + t.duration, 0)
  const coveragePct = totalSuiteMs > 0 ? Math.round((accumulated / totalSuiteMs) * 100) : 0

  // Estimate APFD for this subset using historical failure data from last run with failures
  let estApfd: number | null = null
  const failingRun = [...d.runs].reverse().find(r => r.totalFailures > 0)
  if (failingRun && failingRun.outcomes.length > 0) {
    const failedNames = new Set(failingRun.outcomes.filter(o => o.failed).map(o => o.testClass))
    const subsetNames = new Set(included.map(t => t.name))
    const subsetOrdered = included.map((t, i) => ({ name: t.name, idx: i + 1 }))
    const n = subsetOrdered.length
    const failPositions = subsetOrdered.filter(t => failedNames.has(t.name)).map(t => t.idx)
    if (n > 0 && failPositions.length > 0) {
      const m = failPositions.length
      const posSum = failPositions.reduce((s, p) => s + p, 0)
      estApfd = 1 - posSum / (n * m) + 1 / (2 * n)
    }
    const missedFails = [...failedNames].filter(n => !subsetNames.has(n))
    return { included, accumulated, coveragePct, estApfd, totalSuiteMs, missedFails, hasDuration: true }
  }

  return { included, accumulated, coveragePct, estApfd: null, totalSuiteMs, missedFails: [], hasDuration: withDur.length > 0 }
})

// Flakiness timeline: for each test with any flakiness, compute per-run pass/fail bits + rolling rate
const flakinessTimeline = computed(() => {
  if (d.runs.length < 3) return []
  // d.runs is oldest-first; keep that order so early/late slices correctly represent time direction.
  const orderedRuns = d.runs
  // Pre-build per-run outcome maps for O(1) lookup
  const runMaps = orderedRuns.map(r => {
    const m = new Map<string, boolean>()
    for (const o of (r.outcomes || [])) m.set(o.testClass, o.failed)
    return m
  })

  // Build per-test per-run result map
  const testResults = new Map<string, (boolean | null)[]>()
  for (const t of d.tests) {
    const bits: (boolean | null)[] = runMaps.map(m => {
      const v = m.get(t.name)
      return v !== undefined ? v : null
    })
    const anyFail = bits.some(b => b === true)
    const anyPass = bits.some(b => b === false)
    if (anyFail && anyPass) testResults.set(t.name, bits)
  }
  if (!testResults.size) return []

  // Compute rolling flakiness rate and delta (first-half vs second-half trend)
  const result: {
    name: string
    bits: (boolean | null)[]
    earlyRate: number
    lateRate: number
    delta: number
    trend: 'rising' | 'falling' | 'stable'
    overallRate: number
  }[] = []

  for (const [name, bits] of testResults) {
    const nonNull = bits.filter(b => b !== null) as boolean[]
    const overallRate = nonNull.length > 0 ? nonNull.filter(Boolean).length / nonNull.length : 0

    // Compare first half vs second half for trend
    const mid = Math.floor(bits.length / 2)
    const early = bits.slice(0, mid).filter(b => b !== null) as boolean[]
    const late = bits.slice(mid).filter(b => b !== null) as boolean[]
    const earlyRate = early.length > 0 ? early.filter(Boolean).length / early.length : 0
    const lateRate = late.length > 0 ? late.filter(Boolean).length / late.length : 0
    const delta = lateRate - earlyRate

    let trend: 'rising' | 'falling' | 'stable' = 'stable'
    if (delta > 0.15) trend = 'rising'
    else if (delta < -0.15) trend = 'falling'

    result.push({ name, bits, earlyRate, lateRate, delta, trend, overallRate })
  }

  // Sort: rising first (most alarming), then by overall rate desc, then falling
  result.sort((a, b) => {
    const order = { rising: 0, stable: 1, falling: 2 }
    if (order[a.trend] !== order[b.trend]) return order[a.trend] - order[b.trend]
    return b.overallRate - a.overallRate
  })

  return result.slice(0, 12)
})

// Longevity cohorts: bin tests by how many runs they've been seen in, compare fail rates per cohort
const longevityCohorts = computed(() => {
  if (d.runs.length < 3) return null
  const totalRuns = d.runs.length

  // Build confidence (seen count) and fail count per test from run history
  const seenCount = new Map<string, number>()
  const failCount = new Map<string, number>()
  for (const r of d.runs) {
    for (const o of (r.outcomes || [])) {
      seenCount.set(o.testClass, (seenCount.get(o.testClass) ?? 0) + 1)
      if (o.failed) failCount.set(o.testClass, (failCount.get(o.testClass) ?? 0) + 1)
    }
  }

  // Assign tests to cohorts by seen fraction
  const cohorts = [
    { label: 'New', desc: '≤30% of runs', minPct: 0, maxPct: 30, color: '#22d3ee' },
    { label: 'Maturing', desc: '31–80% of runs', minPct: 31, maxPct: 80, color: '#a78bfa' },
    { label: 'Established', desc: '>80% of runs', minPct: 81, maxPct: 100, color: '#4ade80' },
  ]

  return cohorts.map(c => {
    const tests = d.tests.filter(t => {
      const seen = seenCount.get(t.name) ?? 0
      const pct = Math.round((seen / totalRuns) * 100)
      return pct >= c.minPct && pct <= c.maxPct
    })
    const withHistory = tests.filter(t => (seenCount.get(t.name) ?? 0) > 0)
    const failed = tests.filter(t => (failCount.get(t.name) ?? 0) > 0)
    const avgFailRate = withHistory.length > 0
      ? withHistory.reduce((s, t) => s + (failCount.get(t.name) ?? 0) / (seenCount.get(t.name) ?? 1), 0) / withHistory.length
      : 0
    return { ...c, count: tests.length, failedCount: failed.length, avgFailRate, tests: tests.slice(0, 5).map(t => t.name) }
  }).filter(c => c.count > 0)
})

// Selected run for drill-down
const selectedRunIdx = computed({
  get: () => d.analyticsSelectedRunIdx.value,
  set: (v: number | null) => { d.analyticsSelectedRunIdx.value = v },
})
const selectedRun = computed(() => {
  if (selectedRunIdx.value === null) return null
  return d.runs[selectedRunIdx.value] ?? null
})
const selectedRunOutcomesSorted = computed(() => {
  if (!selectedRun.value) return []
  return [...(selectedRun.value.outcomes || [])].sort((a, b) => b.score - a.score)
})

// Score map for the chronologically previous run (d.runs is oldest-first, so prev = idx-1)
const prevRunScoreMap = computed<Map<string, number>>(() => {
  const m = new Map<string, number>()
  if (selectedRunIdx.value === null || selectedRunIdx.value <= 0) return m
  const prev = d.runs[selectedRunIdx.value - 1]
  if (!prev?.outcomes) return m
  for (const o of prev.outcomes) m.set(o.testClass, o.score)
  return m
})

// Diff: compare selected run to previous (older) run — d.runs is oldest-first, so prev = idx-1
const runDiff = computed(() => {
  if (selectedRunIdx.value === null || selectedRunIdx.value <= 0) return null
  const cur = selectedRun.value
  const prev = d.runs[selectedRunIdx.value - 1]
  // Only diff when both runs have outcome data (plugin only stores outcomes for failing runs)
  if (!cur?.outcomes?.length || !prev?.outcomes?.length) return null

  const prevFailed = new Set(prev.outcomes.filter(o => o.failed).map(o => o.testClass))
  const prevPresent = new Set(prev.outcomes.map(o => o.testClass))
  const prevRankMap = new Map(prev.outcomes.map((o, i) => [o.testClass, i + 1]))
  const curRankMap = new Map(cur.outcomes.map((o, i) => [o.testClass, i + 1]))

  const newFailures = cur.outcomes.filter(o => o.failed && !prevFailed.has(o.testClass) && prevPresent.has(o.testClass))
  const recoveries = cur.outcomes.filter(o => !o.failed && prevFailed.has(o.testClass))
  const newTests = cur.outcomes.filter(o => !prevPresent.has(o.testClass))
  const RANK_THRESHOLD = 5
  const rankChanges = cur.outcomes
    .filter(o => prevRankMap.has(o.testClass))
    .map(o => ({ name: o.testClass, prevRank: prevRankMap.get(o.testClass)!, curRank: curRankMap.get(o.testClass)!, delta: prevRankMap.get(o.testClass)! - curRankMap.get(o.testClass)! }))
    .filter(r => Math.abs(r.delta) >= RANK_THRESHOLD)
    .sort((a, b) => Math.abs(b.delta) - Math.abs(a.delta))
    .slice(0, 10)
  return { newFailures, recoveries, newTests, rankChanges }
})
const runDiffOpen = ref<{ failures: boolean; recoveries: boolean; new: boolean; ranks: boolean }>({ failures: true, recoveries: true, new: false, ranks: false })

// Two-run comparison mode: shift+click on a run chip to set second run
const compareRunIdx = ref<number | null>(null)
const compareRun = computed(() => compareRunIdx.value !== null ? d.runs[compareRunIdx.value] ?? null : null)

function handleRunChipClick(i: number, e: MouseEvent) {
  if (e.shiftKey && selectedRunIdx.value !== null && selectedRunIdx.value !== i) {
    compareRunIdx.value = compareRunIdx.value === i ? null : i
  } else {
    compareRunIdx.value = null
    selectRun(i)
  }
}

interface CompareRow {
  name: string
  rankA: number | null
  rankB: number | null
  rankDelta: number
  scoreA: number | null
  scoreB: number | null
  scoreDelta: number
  failA: boolean
  failB: boolean
  status: 'new-fail' | 'recovered' | 'still-fail' | 'new' | 'absent' | 'ok'
}

const twoRunComparison = computed(() => {
  if (selectedRunIdx.value === null || compareRunIdx.value === null) return null
  const runA = d.runs[selectedRunIdx.value]
  const runB = d.runs[compareRunIdx.value]
  if (!runA || !runB) return null

  // Build maps: testClass → {rank, score, failed}
  const mapA = new Map<string, { rank: number; score: number; failed: boolean }>()
  const mapB = new Map<string, { rank: number; score: number; failed: boolean }>()
  const sortedA = [...(runA.outcomes || [])].sort((a, b) => b.score - a.score)
  const sortedB = [...(runB.outcomes || [])].sort((a, b) => b.score - a.score)
  sortedA.forEach((o, i) => mapA.set(o.testClass, { rank: i + 1, score: o.score, failed: o.failed }))
  sortedB.forEach((o, i) => mapB.set(o.testClass, { rank: i + 1, score: o.score, failed: o.failed }))

  const allNames = new Set([...mapA.keys(), ...mapB.keys()])
  const rows: CompareRow[] = []
  for (const name of allNames) {
    const a = mapA.get(name)
    const b = mapB.get(name)
    const rankA = a?.rank ?? null
    const rankB = b?.rank ?? null
    const rankDelta = rankA !== null && rankB !== null ? rankB - rankA : 0
    const scoreA = a?.score ?? null
    const scoreB = b?.score ?? null
    const scoreDelta = scoreA !== null && scoreB !== null ? scoreA - scoreB : 0
    const failA = a?.failed ?? false
    const failB = b?.failed ?? false
    let status: CompareRow['status'] = 'ok'
    if (failA && !failB) status = 'new-fail'
    else if (!failA && failB) status = 'recovered'
    else if (failA && failB) status = 'still-fail'
    else if (a && !b) status = 'new'
    else if (!a && b) status = 'absent'
    rows.push({ name, rankA, rankB, rankDelta, scoreA, scoreDelta, scoreB, failA, failB, status })
  }
  // Sort: failures first, then by rank delta magnitude
  rows.sort((a, b) => {
    const statusOrder = { 'new-fail': 0, 'still-fail': 1, 'recovered': 2, 'new': 3, 'absent': 4, 'ok': 5 }
    if (statusOrder[a.status] !== statusOrder[b.status]) return statusOrder[a.status] - statusOrder[b.status]
    return Math.abs(b.rankDelta) - Math.abs(a.rankDelta)
  })

  const idxA = selectedRunIdx.value
  const idxB = compareRunIdx.value
  return {
    labelA: `Run #${d.runs.length - idxA}`,
    labelB: `Run #${d.runs.length - idxB}`,
    runA, runB, rows,
    newFails: rows.filter(r => r.status === 'new-fail').length,
    recovered: rows.filter(r => r.status === 'recovered').length,
    apfdDelta: ((runA.apfd - runB.apfd) * 100),
  }
})


const runComposition = computed(() => {
  if (!selectedRun.value?.outcomes?.length) return null
  const outcomes = selectedRun.value.outcomes
  const total = outcomes.length
  const withFail = outcomes.filter(o => o.failScore > 0).length
  const withDep = outcomes.filter(o => (o.depOverlap ?? 0) > 0).length
  const withChanged = outcomes.filter(o => o.isChanged).length
  const withStatic = outcomes.filter(o => o.hasStaticFieldOverlap).length
  const failed = outcomes.filter(o => o.failed).length
  return { total, withFail, withDep, withChanged, withStatic, failed }
})

// Actionable insights
const insights = computed(() => {
  const items: { icon: string; color: string; msg: string; action?: string; actionLabel?: string }[] = []
  if (!d.runs.length) return items

  const apfds = d.runs.map(r => r.apfd * 100)
  const avgApfd = apfds.reduce((s, a) => s + a, 0) / apfds.length
  if (avgApfd < 50) items.push({ icon: '⚠', color: 'var(--red)', msg: `Avg APFD ${avgApfd.toFixed(0)}% — below random baseline (50%). Test ordering may not be effective yet.` })
  else if (avgApfd < 70) items.push({ icon: '↗', color: 'var(--yellow)', msg: `Avg APFD ${avgApfd.toFixed(0)}% — room for improvement. More run history will help the algorithm learn.` })
  else items.push({ icon: '✓', color: 'var(--green)', msg: `Avg APFD ${avgApfd.toFixed(0)}% — good test ordering. Failing tests are being detected early.` })

  // APFD trend: compare recent 3 vs prior 3 (apfds is oldest-first, so use slice from end)
  if (apfds.length >= 6) {
    const recent = apfds.slice(-3).reduce((s, a) => s + a, 0) / 3
    const prior = apfds.slice(-6, -3).reduce((s, a) => s + a, 0) / 3
    const delta = recent - prior
    if (delta > 3) items.push({ icon: '▲', color: 'var(--green)', msg: `APFD improving: last 3 runs avg ${recent.toFixed(0)}% vs prior 3 avg ${prior.toFixed(0)}% (+${delta.toFixed(1)}pp). Ordering is getting better.` })
    else if (delta < -3) items.push({ icon: '▼', color: 'var(--orange)', msg: `APFD declining: last 3 runs avg ${recent.toFixed(0)}% vs prior 3 avg ${prior.toFixed(0)}% (${delta.toFixed(1)}pp). Check if test composition changed.` })
  }

  const failingTests = d.tests.filter(t => t.failScore > 0)
  if (failingTests.length > 0) items.push({ icon: '✕', color: 'var(--red)', msg: `${failingTests.length} test${failingTests.length > 1 ? 's have' : ' has'} recent failure history — they are already prioritized higher.`, action: 'failing', actionLabel: 'Filter' })

  const slowTests = d.tests.filter(t => t.isSlow)
  if (slowTests.length > d.tests.length * 0.3) items.push({ icon: '🐢', color: 'var(--orange)', msg: `${slowTests.length} slow tests (>${d.tests.length * 0.3 | 0} of ${d.tests.length}). Consider parallelizing or optimizing the slowest ones.` })

  const worstApfd = Math.min(...apfds)
  if (worstApfd < 30 && d.runs.length >= 3) items.push({ icon: '↓', color: 'var(--orange)', msg: `Worst run had APFD ${worstApfd.toFixed(0)}% — failures were detected very late. Check if test set changed significantly in that run.` })

  if (!d.hasCoverage) items.push({ icon: 'ℹ', color: 'var(--text-muted)', msg: 'No source coverage data. Enable METHOD or MEMBER instrumentation mode to unlock dependency tracking and coverage features.' })

  if (testFailRates.value.length > 0 && testFailRates.value[0].failRate >= 0.5) items.push({ icon: '⚠', color: 'var(--red)', msg: `${testFailRates.value[0].name.split('.').pop()} has a ${(testFailRates.value[0].failRate * 100).toFixed(0)}% fail rate — this test is flaky or consistently broken.`, action: 'failing', actionLabel: 'Inspect' })

  return items
})

// Execution timeline: tests in current priority order with duration bars
const MAX_GANTT = 40
const ganttTests = computed(() => {
  const tests = d.tests.slice(0, MAX_GANTT)
  const totalMs = tests.filter(t => t.duration >= 0).reduce((s, t) => s + t.duration, 0)
  const maxDur = Math.max(...tests.filter(t => t.duration >= 0).map(t => t.duration), 1)
  let cumMs = 0
  const TIMELINE_W = 500
  return tests.map((t, i) => {
    const dur = t.duration >= 0 ? t.duration : 0
    const w = dur > 0 ? Math.max(2, Math.round((dur / maxDur) * 80)) : 2
    const color = t.failScore > 0 ? '#ef4444' : t.isNew ? '#4ade80' : t.isChanged ? '#eab308'
      : t.depOverlap > 0 ? '#3b82f6' : t.isSlow ? '#fb923c' : '#6366f1'
    const x = totalMs > 0 ? Math.round((cumMs / totalMs) * TIMELINE_W) : i * 5
    cumMs += dur
    return { name: t.name, dur, w, color, x, rank: t.rank, score: t.score, failScore: t.failScore, isNew: t.isNew, isChanged: t.isChanged }
  })
})

// Top impact source classes: ranked by testCount (how many tests cover each)
const TOP_IMPACT_N = 15
const topImpactClasses = computed(() => {
  if (!d.hasCoverage || !d.dd.coverage?.classes?.length) return []
  return [...d.dd.coverage.classes]
    .sort((a, b) => b.testCount - a.testCount)
    .slice(0, TOP_IMPACT_N)
})

// Coverage efficiency: over-tested (many tests, few methods) vs under-tested (few tests, many methods)
const covEfficiency = computed(() => {
  if (!d.hasCoverage || !d.dd.coverage?.classes?.length) return null
  const classes = d.dd.coverage.classes
  const maxTests = classes.reduce((m, c) => Math.max(m, c.testCount), 0)
  if (maxTests === 0) return null

  const withTests = classes.filter(c => c.testCount > 0)
  const medianTests = withTests.length > 0
    ? withTests.sort((a, b) => a.testCount - b.testCount)[Math.floor(withTests.length / 2)].testCount
    : 0

  const overTested = [...classes]
    .filter(c => c.testCount > medianTests * 3 && medianTests > 0)
    .sort((a, b) => b.testCount - a.testCount)
    .slice(0, 6)

  const underTested = [...classes]
    .filter(c => c.testCount > 0 && c.testCount < Math.max(1, medianTests * 0.3))
    .sort((a, b) => a.testCount - b.testCount)
    .slice(0, 6)

  const uncovered = classes.filter(c => c.testCount === 0)

  return { overTested, underTested, uncovered, medianTests, total: classes.length }
})

// Single-Point-of-Failure: classes with exactly one covering test, and tests that
// are the sole coverer of one or more classes.
const SPOF_TOP_N = 10
const singleTestClasses = computed(() => {
  if (!d.hasCoverage || !d.dd.coverage?.classes?.length) return []
  return d.dd.coverage.classes
    .filter(c => c.testCount === 1)
    .sort((a, b) => a.name.localeCompare(b.name))
})
const exclusiveCoverageByTest = computed(() => {
  if (!d.hasCoverage || !d.dd.coverage?.classes?.length) return []
  const counts = new Map<string, { count: number; classes: string[] }>()
  for (const c of d.dd.coverage.classes) {
    if (c.testCount === 1) {
      const t = c.tests[0]
      const e = counts.get(t) ?? { count: 0, classes: [] }
      e.count++; e.classes.push(c.name)
      counts.set(t, e)
    }
  }
  return [...counts.entries()]
    .map(([test, v]) => ({ test, count: v.count, classes: v.classes }))
    .sort((a, b) => b.count - a.count)
    .slice(0, SPOF_TOP_N)
})

// What-if: how many classes would be left with zero coverage if all currently
// selected tests were removed? (i.e. the selection is the only thing covering them)
const selectionExclusiveLoss = computed(() => {
  const sel = d.selectedTests.value
  if (!sel || !sel.size || !d.dd.coverage?.classes?.length) return null
  let lost = 0
  for (const c of d.dd.coverage.classes) {
    if (c.testCount > 0 && c.tests.every(t => sel.has(t))) lost++
  }
  return lost
})

// Dep fan-out distribution: histogram of t.deps.length and outliers (god tests).
const fanOutDistribution = computed(() => {
  if (!d.tests.length) return null
  const counts = d.tests.map(t => t.deps?.length ?? 0)
  const maxC = Math.max(...counts, 1)
  const buckets = 20
  const bw = Math.max(1, Math.ceil(maxC / buckets))
  const hist = new Array(buckets).fill(0)
  counts.forEach(n => hist[Math.min(buckets - 1, Math.floor(n / bw))]++)
  const mean = counts.reduce((s, n) => s + n, 0) / counts.length
  const sd = Math.sqrt(counts.reduce((s, n) => s + (n - mean) ** 2, 0) / counts.length)
  const threshold = mean + 2 * sd
  const outliers = d.tests
    .map(t => ({ test: t, count: t.deps?.length ?? 0 }))
    .filter(x => x.count > threshold && x.count > 5)
    .sort((a, b) => b.count - a.count)
    .slice(0, 8)
  return { hist, bw, max: maxC, mean: Math.round(mean), sd: Math.round(sd), threshold: Math.round(threshold), outliers }
})

// Rank vs coverage scatter data — does the prioritizer surface coverage-rich tests early?
const rankCoverageScatter = computed(() => {
  if (!d.hasCoverage || !d.dd.coverage?.classes?.length || !d.tests.length) return []
  const excl = new Map<string, number>()
  const tot = new Map<string, number>()
  for (const c of d.dd.coverage.classes) {
    if (c.testCount === 1) excl.set(c.tests[0], (excl.get(c.tests[0]) ?? 0) + 1)
    for (const t of c.tests) tot.set(t, (tot.get(t) ?? 0) + 1)
  }
  return d.tests.map(t => ({
    name: t.name,
    rank: t.rank,
    exclusive: excl.get(t.name) ?? 0,
    total: tot.get(t.name) ?? 0,
  }))
})

// Cross-package coupling matrix — package(test) → package(dep) edge counts.
const pkgCouplingMatrix = computed(() => {
  if (!d.tests.length) return null
  const pkgOf = (n: string) => { const i = n.lastIndexOf('.'); return i > 0 ? n.substring(0, i) : '(default)' }
  const m = new Map<string, Map<string, number>>()
  const pkgs = new Set<string>()
  for (const t of d.tests) {
    const tp = pkgOf(t.name); pkgs.add(tp)
    for (const dep of (t.deps || [])) {
      const dp = pkgOf(dep); pkgs.add(dp)
      if (!m.has(tp)) m.set(tp, new Map())
      const row = m.get(tp)!
      row.set(dp, (row.get(dp) ?? 0) + 1)
    }
  }
  if (pkgs.size < 2) return null
  const order = [...pkgs].sort()
  let total = 0; let mx = 0
  for (const a of order) for (const b of order) {
    const v = m.get(a)?.get(b) ?? 0
    total += v; if (v > mx) mx = v
  }
  return { order, m, max: mx, total }
})

// Suite-wide redundancy clusters — groups of tests with Jaccard ≥ 0.8 on covered classes.
const REDUNDANCY_THRESHOLD = 0.8
const REDUNDANCY_MAX_TESTS = 500
const redundancyClusters = computed(() => {
  if (!d.hasCoverage || !d.dd.coverage?.classes?.length) return []
  const tCov = new Map<string, Set<string>>()
  for (const c of d.dd.coverage.classes) {
    for (const t of c.tests) {
      let s = tCov.get(t); if (!s) { s = new Set(); tCov.set(t, s) }
      s.add(c.name)
    }
  }
  const tests = [...tCov.keys()]
  if (tests.length === 0 || tests.length > REDUNDANCY_MAX_TESTS) return []
  const parent = new Map(tests.map(t => [t, t]))
  const find = (x: string): string => {
    let p = parent.get(x)!
    while (p !== parent.get(p)) p = parent.get(p)!
    parent.set(x, p)
    return p
  }
  const union = (a: string, b: string) => { const ra = find(a), rb = find(b); if (ra !== rb) parent.set(ra, rb) }
  for (let i = 0; i < tests.length; i++) {
    const A = tCov.get(tests[i])!
    if (A.size < 2) continue
    for (let j = i + 1; j < tests.length; j++) {
      const B = tCov.get(tests[j])!
      if (B.size < 2) continue
      const small = A.size <= B.size ? A : B
      const big = small === A ? B : A
      let inter = 0
      for (const x of small) if (big.has(x)) inter++
      if (inter === 0) continue
      const jac = inter / (A.size + B.size - inter)
      if (jac >= REDUNDANCY_THRESHOLD) union(tests[i], tests[j])
    }
  }
  const groups = new Map<string, string[]>()
  for (const t of tests) {
    const r = find(t)
    let g = groups.get(r); if (!g) { g = []; groups.set(r, g) }
    g.push(t)
  }
  const clusters: { tests: string[]; shared: string[]; sharedSize: number }[] = []
  for (const g of groups.values()) {
    if (g.length < 2) continue
    g.sort()
    const sets = g.map(t => tCov.get(t)!)
    const inter = new Set<string>(sets[0])
    for (let i = 1; i < sets.length; i++) for (const x of [...inter]) if (!sets[i].has(x)) inter.delete(x)
    clusters.push({ tests: g, shared: [...inter].sort(), sharedSize: inter.size })
  }
  return clusters.sort((a, b) => b.tests.length - a.tests.length || b.sharedSize - a.sharedSize).slice(0, 6)
})

// First-failure detection heatmap: for each run with failures, which test was first to fail?
// Shows tests as rows, runs as columns. Each cell: colored if test failed in that run,
// starred if it was the FIRST failure in that run. Also computes "detection rank" = avg rank
// when test was the first failure.
const firstFailHeatmap = computed(() => {
  const failingRuns = [...d.runs].filter(r => r.outcomes?.some(o => o.failed))
  if (failingRuns.length < 2) return null
  // Pre-build test rank map for O(1) lookup instead of O(tests) find per call
  const testRankMap = new Map<string, number>(d.tests.map(t => [t.name, t.rank]))
  // Collect all tests that ever failed
  const failedTestNames = new Set<string>()
  for (const r of failingRuns) {
    for (const o of (r.outcomes || [])) {
      if (o.failed) failedTestNames.add(o.testClass)
    }
  }
  if (failedTestNames.size === 0) return null

  // Helper: find lowest-rank failed test in a run
  const firstFailed = (r: (typeof failingRuns)[0]) => {
    const failed = (r.outcomes || []).filter(o => o.failed).map(o => o.testClass)
    const ranked = failed.map(n => ({ n, rank: testRankMap.get(n) ?? 9999 })).sort((a, b) => a.rank - b.rank)
    return ranked[0]?.n ?? ''
  }

  // For each run, find first-failure test (lowest rank among failed)
  const runFirstFail: string[] = failingRuns.map(firstFailed)

  // Compute per-test stats: how many runs it was first-to-fail, avg rank when first
  const testStats = new Map<string, { firstCount: number; totalRank: number }>()
  for (const name of runFirstFail) {
    if (!name) continue
    const rank = testRankMap.get(name) ?? 9999
    const s = testStats.get(name) ?? { firstCount: 0, totalRank: 0 }
    s.firstCount++
    s.totalRank += rank
    testStats.set(name, s)
  }

  // Sort tests: most-often first-to-fail first, then by rank
  const sortedTests = [...failedTestNames]
    .map(n => ({
      name: n,
      rank: testRankMap.get(n) ?? 9999,
      firstCount: testStats.get(n)?.firstCount ?? 0,
      avgFirstRank: testStats.has(n) ? testStats.get(n)!.totalRank / testStats.get(n)!.firstCount : 0,
    }))
    .sort((a, b) => b.firstCount - a.firstCount || a.rank - b.rank)
    .slice(0, 10)

  // Build cell matrix: [testIdx][runIdx] = { failed, isFirst }
  const runs = failingRuns.slice(-12).reverse() // newest 12 failing runs, reversed to oldest-left chronological display
  // Pre-build per-run failed sets for O(1) cell lookup
  const runFailedSets = runs.map(r => new Set((r.outcomes || []).filter(o => o.failed).map(o => o.testClass)))
  const runFirstFailSet = runs.map(firstFailed)

  const rows = sortedTests.map(t => ({
    ...t,
    cells: runs.map((r, ri) => ({
      failed: runFailedSets[ri].has(t.name),
      isFirst: runFirstFailSet[ri] === t.name,
    })),
  }))

  return { rows, runs: runs.map(r => r.timestamp ?? ''), totalFailingRuns: failingRuns.length }
})

// Test retirement candidates: tests that have 0 fail score, not new/changed, low deps
// Since outcomes only record fails, "seen" comes from total runs if test is known
const MIN_RUNS_FOR_RETIREMENT = 5
const retirementCandidates = computed(() => {
  if (d.runs.length < MIN_RUNS_FOR_RETIREMENT) return []
  // Build failed count for each test (outcomes only appear for fails or in runs where they ran)
  const failCount = new Map<string, number>()
  for (const r of d.runs) {
    for (const o of (r.outcomes || [])) {
      if (o.failed) failCount.set(o.testClass, (failCount.get(o.testClass) ?? 0) + 1)
    }
  }
  return d.tests
    .filter(t => {
      if ((failCount.get(t.name) ?? 0) > 0) return false // has failed
      if (t.failScore > 0) return false // has failure signal
      if (t.isNew || t.isChanged) return false // recently changed — keep
      if (d.flakyTests.value.has(t.name)) return false // flaky — keep
      if (t.depOverlap > 0) return false // overlaps changed classes
      // Only include if they've been around long enough (rank isn't brand new)
      return true
    })
    .map(t => ({
      name: t.name,
      rank: t.rank,
      duration: t.duration,
      depTotal: t.depTotal,
      score: t.score,
    }))
    .sort((a, b) => b.duration - a.duration || a.rank - b.rank)
    .slice(0, 10)
})

function selectRun(idx: number) {
  selectedRunIdx.value = selectedRunIdx.value === idx ? null : idx
  showFailuresOnly.value = false
  runDetailSearch.value = ''
  runDetailPage.value = 0
  if (selectedRunIdx.value !== null && typeof window !== 'undefined') {
    setTimeout(() => document.querySelector('.detail-panel')?.scrollIntoView({ behavior: 'smooth', block: 'nearest' }), 80)
  }
}

// APFD factor attribution: counterfactual analysis across all runs with failures
// For each weight factor, compute average APFD if that factor were zeroed out
const apfdAttribution = computed(() => {
  const runsWithFail = d.runs.filter(r => r.outcomes?.length && r.outcomes.some(o => o.failed))
  if (runsWithFail.length === 0) return null

  const w = d.dd.weights
  const scb = computeSetCoverBonuses(
    runsWithFail[0].outcomes.map(o => ({ name: o.testClass, deps: [] } as any)),
    new Set(d.dd.changedClasses || []),
    w.coverageBonus || 0
  )

  function avgApfd(weightOverride: Partial<typeof w>): number {
    const merged = { ...w, ...weightOverride }
    let sum = 0, count = 0
    for (const r of runsWithFail) {
      const sorted = [...r.outcomes].sort((a, b) => computeScore(b, merged, scb) - computeScore(a, merged, scb))
      const apfd = computeApfd(sorted)
      if (apfd !== null) { sum += apfd; count++ }
    }
    return count ? sum / count : 0
  }

  const baseline = avgApfd({})
  const random = 0.5 // theoretical random ordering baseline

  const factors = [
    { key: 'Fail History', zero: { maxFailure: 0 }, color: '#ef4444' },
    { key: 'Dep Overlap', zero: { depOverlap: 0 }, color: '#3b82f6' },
    { key: 'Changed/New', zero: { changedTest: 0, newTest: 0 }, color: '#eab308' },
    { key: 'Speed', zero: { speed: 0, speedPenalty: 0 }, color: '#22c55e' },
    { key: 'Static Fields', zero: { staticFieldBonus: 0 }, color: '#a855f7' },
  ]

  const gain = baseline - random
  const results = factors.map(f => {
    const without = avgApfd(f.zero)
    const contribution = baseline - without
    const pct = gain > 0.001 ? Math.round((contribution / gain) * 100) : 0
    return { ...f, without: Math.round(without * 1000) / 10, contribution: Math.round(contribution * 1000) / 10, pct: Math.max(0, pct) }
  }).sort((a, b) => b.pct - a.pct)

  return { baseline: Math.round(baseline * 1000) / 10, random: 50, gain: Math.round(gain * 1000) / 10, results }
})

// APFD improvement recommendations: specific actionable steps to improve test ordering
interface ApfdRec {
  priority: 'high' | 'medium' | 'low'
  icon: string
  title: string
  detail: string
  action?: { label: string; tab: 'tests' | 'weights'; filter?: string }
  tests?: string[]
}

const apfdRecommendations = computed((): ApfdRec[] => {
  const recs: ApfdRec[] = []
  if (d.runs.length < 2) return recs

  const apfds = d.runs.map(r => r.apfd * 100)
  const avgApfd = apfds.reduce((s, a) => s + a, 0) / apfds.length

  // Nothing to do if APFD is already excellent
  if (avgApfd >= 95 && apfds.every(a => a >= 90)) return recs

  // 1. High-rank but low fail-score tests occupying top slots when failures exist
  const runsWithFail = d.runs.filter(r => r.outcomes?.some(o => o.failed))
  if (runsWithFail.length > 0) {
    // Tests that failed recently but are ranked in bottom half
    const testCount = d.tests.length
    const highFailLowRank = d.tests
      .filter(t => t.failScore > 0 && t.rank > testCount * 0.4)
      .sort((a, b) => b.failScore - a.failScore)
      .slice(0, 3)
    if (highFailLowRank.length > 0) {
      recs.push({
        priority: 'high',
        icon: '⬆',
        title: `${highFailLowRank.length} high-risk test${highFailLowRank.length > 1 ? 's' : ''} ranked in bottom 60%`,
        detail: `Tests with failure history should run early. Increasing "Fail History" weight will move them up. Most affected: ${highFailLowRank.slice(0, 2).map(t => t.name.split('.').pop()).join(', ')}.`,
        action: { label: 'Tune weights', tab: 'weights' },
        tests: highFailLowRank.map(t => t.name),
      })
    }
  }

  // 2. APFD variance: high standard deviation signals unstable ordering
  if (apfds.length >= 4) {
    const mean = avgApfd
    const stdDev = Math.sqrt(apfds.reduce((s, a) => s + (a - mean) ** 2, 0) / apfds.length)
    if (stdDev > 15) {
      const minA = Math.min(...apfds).toFixed(0), maxA = Math.max(...apfds).toFixed(0)
      recs.push({
        priority: 'high',
        icon: '⚡',
        title: `APFD varies widely (±${stdDev.toFixed(0)}pp)`,
        detail: `Range ${minA}%–${maxA}% across runs. Unstable ordering often means test composition changes or flaky tests. More run history will stabilize the scoring model.`,
      })
    }
  }

  // 3. No coverage data → dep overlap factor is blind
  if (!d.hasCoverage && avgApfd < 85) {
    recs.push({
      priority: 'medium',
      icon: '◉',
      title: 'Enable coverage tracking for +10–20pp APFD gain',
      detail: 'Without source coverage, the "Dep Overlap" factor is zero for all tests. Enabling METHOD or MEMBER mode lets the algorithm know which tests cover changed code.',
    })
  } else if (d.hasCoverage) {
    // 4. Low dep-overlap utilization despite coverage being available
    const depActiveTests = d.tests.filter(t => t.depOverlap > 0)
    if (depActiveTests.length < d.tests.length * 0.1 && d.tests.length > 10) {
      recs.push({
        priority: 'medium',
        icon: '◎',
        title: `Low dependency signal (${depActiveTests.length}/${d.tests.length} tests have dep overlap)`,
        detail: 'Most tests have no dependency overlap with changed classes. If no source changes are present this run, dep overlap is expected to be low — it activates on changes.',
      })
    }
  }

  // 5. Factor attribution: dominant single factor vs balanced
  if (apfdAttribution.value && apfdAttribution.value.gain > 5) {
    const top = apfdAttribution.value.results[0]
    const secondFactor = apfdAttribution.value.results[1]
    if (top.pct > 80 && secondFactor.pct < 10) {
      recs.push({
        priority: 'medium',
        icon: '⚖',
        title: `APFD depends almost entirely on "${top.key}" (${top.pct}%)`,
        detail: `Ordering is fragile — if "${top.key}" signal is absent (e.g. no failures for a while), APFD will drop sharply. Consider building up "${secondFactor.key}" signal as a backup.`,
        action: { label: 'Explore weights', tab: 'weights' },
      })
    }
  }

  // 6. Slow tests ranked early (speed penalty not working)
  const slowEarlyTests = d.tests.filter(t => t.isSlow && t.rank <= d.tests.length * 0.2 && t.failScore === 0)
  if (slowEarlyTests.length >= 3) {
    recs.push({
      priority: 'low',
      icon: '🐢',
      title: `${slowEarlyTests.length} slow tests run in top 20% with no failure history`,
      detail: 'Slow tests without a strong reason to run early reduce overall suite speed and don\'t help APFD. Increasing "Speed Penalty" weight will push them down.',
      action: { label: 'Tune weights', tab: 'weights' },
      tests: slowEarlyTests.slice(0, 3).map(t => t.name),
    })
  }

  // 7. Near-perfect APFD — give positive confirmation
  if (avgApfd >= 85 && recs.filter(r => r.priority === 'high').length === 0) {
    recs.push({
      priority: 'low',
      icon: '✓',
      title: `APFD ${avgApfd.toFixed(0)}% — ordering is working well`,
      detail: 'Failing tests are being detected early. Continue accumulating run history to further stabilize the model.',
    })
  }

  return recs
})

function scoreBar(o: { score: number; failScore: number; depOverlap: number; depTotal: number; isNew: boolean; isChanged: boolean; isSlow: boolean; hasStaticFieldOverlap: boolean }) {
  const w = d.dd.weights
  const total = Math.max(o.score, 1)
  const fail = o.failScore > 0 ? Math.min(Math.ceil(o.failScore), w.maxFailure) : 0
  const dep = o.depOverlap > 0 && o.depTotal > 0 ? Math.min(Math.ceil((o.depOverlap / Math.sqrt(o.depTotal)) * w.depOverlap), w.depOverlap) : 0
  const chg = o.isChanged ? w.changedTest : o.isNew ? w.newTest : 0
  const spd = o.isSlow ? 0 : Math.round(w.speed * 0.5)
  const stf = o.hasStaticFieldOverlap ? w.staticFieldBonus : 0
  return [
    { w: Math.round((fail / total) * 100), c: '#ef4444' },
    { w: Math.round((dep / total) * 100), c: '#3b82f6' },
    { w: Math.round((chg / total) * 100), c: '#eab308' },
    { w: Math.round((spd / total) * 100), c: '#22c55e' },
    { w: Math.round((stf / total) * 100), c: '#a855f7' },
  ].filter(s => s.w > 0)
}

function initAll() {
  if (d.activeTab.value !== 'analytics') return
  nextTick(() => {
    try { initTimeline() } catch (e) { console.error('[dashboard] Timeline failed:', e) }
    initDistributions()
    buildCoverageTreemap()
    try { buildFanOutHistogram() } catch (e) { console.error('[dashboard] Fan-out failed:', e) }
    try { buildRankCoverageScatter() } catch (e) { console.error('[dashboard] Rank scatter failed:', e) }
    try { buildPkgCouplingMatrix() } catch (e) { console.error('[dashboard] Pkg matrix failed:', e) }
  })
}

watch(() => d.activeTab.value, (tab) => { if (tab === 'analytics') initAll() })
watch(() => d.selectedTests.value, () => { if (d.activeTab.value === 'analytics' && d.hasCoverage) nextTick(updateTreemapColors) })
watch(() => d.selectedMethods.value, () => { if (d.activeTab.value === 'analytics' && d.hasCoverage) nextTick(updateTreemapColors) })
watch(() => d.covSelectedClass.value, () => { if (d.activeTab.value === 'analytics' && d.hasCoverage) nextTick(updateTreemapColors) })
watch(timeRangeOpt, () => { if (d.activeTab.value === 'analytics') nextTick(initTimeline) })
onMounted(initAll)
</script>

<template>
  <div v-if="d.activeTab.value === 'analytics'">
    <div v-if="d.runs.length === 0 && !d.hasCoverage && !d.tests.length" style="height:180px;display:flex;align-items:center;justify-content:center;color:var(--text-muted)">No run history yet</div>
    <div v-else>
      <!-- Sticky section nav -->
      <nav class="analytics__subnav">
        <a class="analytics__subnav-link" href="#suite-health">📊 Health</a>
        <a class="analytics__subnav-link" href="#analytics-history">⏱ History</a>
        <a class="analytics__subnav-link" href="#analytics-coverage">🗂 Coverage</a>
        <a class="analytics__subnav-link" href="#analytics-analysis">🔬 Analysis</a>
      </nav>

      <!-- Suite Health Card -->
      <div id="suite-health">
        <SuiteHealthCard v-if="d.suiteHealthBreakdown.value" />
      </div>

      <!-- Timeline -->
      <div v-if="d.runs.length" id="analytics-history" style="margin-bottom:12px">
        <!-- Session health arc banner -->
        <div v-if="healthArc" class="analytics__health-arc">
          <span class="analytics__health-arc-icon" :style="{ color: healthArc.trendColor }">{{ healthArc.trendIcon }}</span>
          <span class="analytics__health-arc-text">{{ healthArc.narrative }}</span>
          <span class="analytics__health-arc-badge" :style="{ background: healthArc.trendColor === 'var(--green)' ? 'rgba(74,222,128,.15)' : healthArc.trendColor === 'var(--red)' ? 'rgba(248,113,113,.15)' : 'rgba(251,191,36,.12)', borderColor: healthArc.trendColor, color: healthArc.trendColor }">{{ healthArc.trend }}</span>
        </div>
        <!-- Insights panel -->
        <div v-if="insights.length" style="display:flex;flex-direction:column;gap:4px;margin-bottom:10px">
          <div
            v-for="(ins, i) in insights"
            :key="i"
            class="analytics__insight"
            :style="{ borderLeftColor: ins.color }"
          >
            <span :style="{ color: ins.color, flex:'0 0 auto', fontSize:'.75rem' }">{{ ins.icon }}</span>
            <span style="font-size:.68rem;color:var(--text-sec);flex:1">{{ ins.msg }}</span>
            <span
              v-if="ins.action"
              class="analytics__insight-action"
              @click="d.setBadgeFilter(ins.action as any); d.setTab('tests')"
            >{{ ins.actionLabel }} →</span>
          </div>
        </div>
        <!-- Run statistics summary -->
        <div style="display:flex;flex-wrap:wrap;gap:6px;margin-bottom:8px" v-if="runStats">
          <div class="kpi analytics__stat-kpi" :title="'Total recorded runs. Each run = one test suite execution.'">
            <div class="analytics__stat-label">Total Runs</div>
            <div class="analytics__stat-value" style="color:var(--accent-light)">{{ d.runs.length }}</div>
          </div>
          <div class="kpi analytics__stat-kpi" :title="'Average APFD (Area under the APFD curve). 100% = all failures detected first; 50% = random ordering.'">
            <div class="analytics__stat-label">Avg APFD</div>
            <div class="analytics__stat-value" :style="{ color: +runStats.avgApfd >= 70 ? 'var(--green)' : +runStats.avgApfd >= 50 ? 'var(--yellow)' : 'var(--red)' }">{{ runStats.avgApfd }}%</div>
          </div>
          <div class="kpi analytics__stat-kpi" :title="'Best APFD achieved in a single run.'">
            <div class="analytics__stat-label">Best APFD</div>
            <div class="analytics__stat-value" style="color:var(--green)">{{ runStats.bestApfd }}%</div>
          </div>
          <div class="kpi analytics__stat-kpi" :title="'Worst APFD in a single run — lower means more test-ordering work remaining.'">
            <div class="analytics__stat-label">Worst APFD</div>
            <div class="analytics__stat-value" :style="{ color: +runStats.worstApfd < 50 ? 'var(--red)' : 'var(--yellow)' }">{{ runStats.worstApfd }}%</div>
          </div>
          <div class="kpi analytics__stat-kpi" :title="'Runs that contained at least one test failure.'">
            <div class="analytics__stat-label">Runs w/ Failures</div>
            <div class="analytics__stat-value" :style="{ color: runStats.runsWithFailures > 0 ? 'var(--red)' : 'var(--green)' }">{{ runStats.runsWithFailures }}</div>
          </div>
          <div class="kpi analytics__stat-kpi" :title="'Total cumulative failure events across all runs.'">
            <div class="analytics__stat-label">Total Failures</div>
            <div class="analytics__stat-value" :style="{ color: runStats.totalFailureEvents > 0 ? 'var(--orange)' : 'var(--green)' }">{{ runStats.totalFailureEvents }}</div>
          </div>
          <div class="kpi analytics__stat-kpi" :title="'Average number of tests per run.'">
            <div class="analytics__stat-label">Avg Tests/Run</div>
            <div class="analytics__stat-value" style="color:var(--text-sec)">{{ runStats.avgTests }}</div>
          </div>
          <div v-if="runStats.totalDurationMs > 0" class="kpi analytics__stat-kpi" :title="'Cumulative EMA-tracked duration across all tests in the current ordering. Gives a rough estimate of total suite run time.'">
            <div class="analytics__stat-label">Suite Duration</div>
            <div class="analytics__stat-value" style="color:var(--text-sec)">{{ fmtDur(runStats.totalDurationMs) }}</div>
          </div>
          <div v-if="d.runs.length > 1" class="kpi analytics__stat-kpi" :title="'Time span from first to last recorded run: ' + fmtTime(d.runs[0].timestamp) + ' → ' + fmtTime(d.runs[d.runs.length-1].timestamp)">
            <div class="analytics__stat-label">History Span</div>
            <div class="analytics__stat-value" style="color:var(--text-sec)">{{ historySpan }}</div>
          </div>
          <div v-if="runStats.avgFirstFailPos !== null" class="kpi analytics__stat-kpi" :title="'Average rank position of the first failure when failures occurred. Lower = failures are found earlier in the run = better ordering.'">
            <div class="analytics__stat-label">Avg 1st Fail Pos</div>
            <div class="analytics__stat-value" :style="{ color: (runStats.avgFirstFailPos + 1) <= 3 ? 'var(--green)' : (runStats.avgFirstFailPos + 1) <= 10 ? 'var(--yellow)' : 'var(--orange)' }">{{ runStats.avgFirstFailPos + 1 }}</div>
          </div>
        </div>

        <!-- Time range selector -->
        <div style="display:flex;align-items:center;gap:6px;margin-bottom:6px">
          <span style="font-size:.65rem;color:var(--text-muted)">Show:</span>
          <button v-for="opt in [{v:'all',l:'All'},{v:'last10',l:'Last 10'},{v:'last5',l:'Last 5'},{v:'last30d',l:'Last 30d'}]" :key="opt.v"
            class="analytics__range-btn"
            :class="{ 'analytics__range-btn--active': timeRangeOpt === opt.v }"
            @click="timeRangeOpt = opt.v as any"
          >{{ opt.l }}</button>
          <span style="font-size:.62rem;color:var(--text-muted);margin-left:4px">{{ filteredRuns.length }} run{{ filteredRuns.length === 1 ? '' : 's' }}</span>
        </div>

        <div class="card" style="margin-bottom:8px">
          <div class="card-label">APFD over time <span style="color:var(--border)">(dashed = 0.5 random baseline)</span> <span style="color:var(--text-muted);font-size:.58rem">— higher = ordering found failures earlier · <strong style="color:var(--accent-light)">click point to inspect run</strong></span></div>
          <div class="analytics__canvas" style="height:100px;cursor:pointer"><canvas id="tl-apfd"></canvas></div>
        </div>
        <div style="display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px">
          <div class="card"><div class="card-label">Failures per run <span style="color:var(--text-muted);font-size:.55rem">— green = 0 failures</span></div><div class="analytics__canvas analytics__canvas--sm"><canvas id="tl-fail"></canvas></div></div>
          <div class="card"><div class="card-label">First failure position <span style="color:var(--text-muted);font-size:.55rem">— lower = failure found sooner</span></div><div class="analytics__canvas analytics__canvas--sm"><canvas id="tl-ffp"></canvas></div></div>
          <div class="card"><div class="card-label">Test count per run</div><div class="analytics__canvas analytics__canvas--sm"><canvas id="tl-cnt"></canvas></div></div>
        </div>
      </div>

      <!-- Distributions (always shown when there are tests) -->
      <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:12px">
        <div class="card"><div class="card-label" title="Distribution of current priority scores across all tests">Score distribution <span style="color:var(--text-muted);font-size:.55rem">— current run</span></div><div class="analytics__canvas analytics__canvas--dist"><canvas id="d-score"></canvas></div></div>
        <div class="card"><div class="card-label" title="Histogram of test durations in log-scale buckets">Duration distribution <span style="color:var(--text-muted);font-size:.55rem">(log buckets)</span></div><div class="analytics__canvas analytics__canvas--dist"><canvas id="d-dur"></canvas></div></div>
        <div class="card"><div class="card-label" title="How many source-class dependencies each test has — higher = more tightly coupled">Dependency count <span style="color:var(--text-muted);font-size:.55rem">— distribution</span></div><div class="analytics__canvas analytics__canvas--dist"><canvas id="d-deps"></canvas></div></div>
        <div class="card"><div class="card-label" title="Tests ranked by EMA-decayed failure score — the historical failure signal used in scoring">Top {{ d.tests.filter(t => t.failScore > 0).length || 0 }} by fail score <span style="color:var(--text-muted);font-size:.55rem">— EMA-decayed failure history</span></div><div class="analytics__canvas analytics__canvas--dist"><canvas id="d-fail"></canvas></div></div>
      </div>

      <!-- APFD Factor Attribution -->
      <div v-if="apfdAttribution" style="margin-bottom:12px">
        <h3 style="font-size:.82rem;color:var(--text-sec);margin-bottom:6px" title="Counterfactual analysis: how much does each scoring factor contribute to APFD improvement over random ordering?">APFD Factor Attribution</h3>
        <div class="card" style="padding:10px 12px">
          <div style="display:flex;align-items:center;gap:12px;margin-bottom:8px;flex-wrap:wrap">
            <span style="font-size:.65rem;color:var(--text-muted)">Baseline APFD: <strong style="color:var(--green)">{{ apfdAttribution.baseline }}%</strong></span>
            <span style="font-size:.65rem;color:var(--text-muted)">vs random: <strong style="color:var(--accent-light)">+{{ apfdAttribution.gain.toFixed(1) }}pp gain</strong></span>
            <span style="font-size:.6rem;color:var(--text-muted);margin-left:auto">% of gain lost if factor removed</span>
          </div>
          <div style="display:flex;flex-direction:column;gap:5px">
            <div v-for="f in apfdAttribution.results" :key="f.key" style="display:flex;align-items:center;gap:8px">
              <span style="font-size:.65rem;color:var(--text-sec);width:90px;flex-shrink:0">{{ f.key }}</span>
              <div style="flex:1;height:10px;background:rgba(51,65,85,.5);border-radius:3px;overflow:hidden;position:relative">
                <div :style="{ width: f.pct + '%', background: f.color, height: '100%', borderRadius: '3px', transition: 'width .4s', opacity: '.85' }"></div>
              </div>
              <span style="font-size:.62rem;min-width:36px;text-align:right" :style="{ color: f.pct > 20 ? f.color : 'var(--text-muted)' }">{{ f.pct }}%</span>
              <span style="font-size:.55rem;color:var(--text-muted);min-width:70px;text-align:right" :title="'APFD without this factor: ' + f.without + '%'">−{{ f.contribution.toFixed(1) }}pp without</span>
            </div>
          </div>
        </div>
      </div>

      <!-- APFD Improvement Recommendations -->
      <div v-if="apfdRecommendations.length" style="margin-bottom:12px">
        <h3 style="font-size:.82rem;color:var(--text-sec);margin-bottom:6px" title="Specific, actionable steps you can take to improve test ordering APFD">
          APFD Improvement Recommendations
        </h3>
        <div style="display:flex;flex-direction:column;gap:5px">
          <div
            v-for="(rec, i) in apfdRecommendations"
            :key="i"
            class="apfd-rec"
            :class="'apfd-rec--' + rec.priority"
          >
            <span class="apfd-rec__icon">{{ rec.icon }}</span>
            <div class="apfd-rec__body">
              <div class="apfd-rec__title">{{ rec.title }}</div>
              <div class="apfd-rec__detail">{{ rec.detail }}</div>
              <div v-if="rec.tests && rec.tests.length" class="apfd-rec__tests">
                <span
                  v-for="tn in rec.tests.slice(0, 3)"
                  :key="tn"
                  class="apfd-rec__test-chip"
                  @click="d.navigateToTestFromCov(tn)"
                  :title="tn"
                >{{ tn.split('.').pop() }}</span>
              </div>
            </div>
            <button
              v-if="rec.action"
              class="apfd-rec__action"
              @click="rec.action!.filter ? (d.setBadgeFilter(rec.action!.filter as any), d.setTab(rec.action!.tab)) : d.setTab(rec.action!.tab)"
            >{{ rec.action.label }} →</button>
          </div>
        </div>
      </div>

      <!-- Time Budget Optimizer -->
      <div v-if="d.tests.length" style="margin-bottom:12px">
        <h3 style="font-size:.82rem;color:var(--text-sec);margin-bottom:6px" title="Given a time budget, which tests should you run? Greedy selection by priority score within the time limit.">
          Time Budget Optimizer
        </h3>
        <div class="card budget-card">
          <div class="budget-card__controls">
            <label class="budget-card__label">Budget (seconds):</label>
            <input
              type="number"
              class="budget-card__input"
              v-model.number="budgetSec"
              :min="1"
              :max="9999"
              @click.stop
            />
            <span class="budget-card__hint" v-if="budgetOptimResult">
              → {{ budgetOptimResult.included.length }} of {{ d.tests.length }} tests
              · {{ Math.round(budgetOptimResult.accumulated / 1000) }}s
              · {{ budgetOptimResult.coveragePct }}% of suite time
              <span v-if="budgetOptimResult.estApfd !== null" :style="{ color: budgetOptimResult.estApfd >= 0.7 ? 'var(--green)' : budgetOptimResult.estApfd >= 0.5 ? 'var(--yellow)' : 'var(--red)' }">
                · est. APFD {{ (budgetOptimResult.estApfd * 100).toFixed(0) }}%
              </span>
            </span>
          </div>
          <div v-if="budgetOptimResult && budgetOptimResult.included.length" class="budget-card__body">
            <div class="budget-card__bar-row">
              <div class="budget-card__bar-bg">
                <div class="budget-card__bar-fill" :style="{ width: budgetOptimResult.coveragePct + '%' }"></div>
              </div>
              <span class="budget-card__bar-label">{{ budgetOptimResult.coveragePct }}%</span>
            </div>
            <div class="budget-card__tests">
              <span
                v-for="t in budgetOptimResult.included.slice(0, 20)"
                :key="t.name"
                class="budget-card__test-chip"
                :class="{ 'budget-card__test-chip--fail': t.failScore > 0 }"
                :title="t.name + ' (' + fmtDur(t.duration) + ')'"
                @click="d.selectTest(t)"
              >{{ sn(t.name) }}</span>
              <span v-if="budgetOptimResult.included.length > 20" class="budget-card__test-chip budget-card__test-chip--more">
                +{{ budgetOptimResult.included.length - 20 }} more
              </span>
            </div>
            <div v-if="budgetOptimResult.missedFails.length" class="budget-card__missed">
              <span class="budget-card__missed-label">⚠ Missed failures ({{ budgetOptimResult.missedFails.length }}):</span>
              <span v-for="n in budgetOptimResult.missedFails.slice(0, 5)" :key="n" class="budget-card__missed-chip" :title="n">{{ sn(n) }}</span>
              <span v-if="budgetOptimResult.missedFails.length > 5" style="font-size:.6rem;color:var(--text-muted)">+{{ budgetOptimResult.missedFails.length - 5 }} more</span>
            </div>
          </div>
          <div v-else-if="budgetOptimResult && !budgetOptimResult.included.length" style="font-size:.68rem;color:var(--text-muted);padding:6px 0">
            No tests fit within {{ budgetSec }}s budget. Fastest test takes longer than this.
          </div>
          <div v-else style="font-size:.68rem;color:var(--text-muted);padding:6px 0">
            No duration data available yet — run tests to populate timing information.
          </div>
        </div>
      </div>

      <!-- Execution Timeline (Gantt-style) -->
      <div v-if="longevityCohorts && longevityCohorts.length" style="margin-bottom:12px">
        <h3 style="font-size:.82rem;color:var(--text-sec);margin-bottom:6px" title="Tests grouped by how many runs they've been seen in — do newer tests fail more often than established ones?">
          Test Longevity &amp; Reliability by Cohort
        </h3>
        <div class="card" style="padding:10px 12px">
          <div style="display:flex;gap:8px;flex-wrap:wrap">
            <div
              v-for="c in longevityCohorts"
              :key="c.label"
              class="longevity-cohort"
              :style="{ borderLeftColor: c.color }"
            >
              <div class="longevity-cohort__header">
                <span class="longevity-cohort__label" :style="{ color: c.color }">{{ c.label }}</span>
                <span class="longevity-cohort__desc">{{ c.desc }}</span>
              </div>
              <div class="longevity-cohort__count">{{ c.count }} tests</div>
              <div class="longevity-cohort__fail-row">
                <div class="longevity-cohort__fail-bar-bg">
                  <div class="longevity-cohort__fail-bar"
                    :style="{ width: (c.avgFailRate * 100).toFixed(0) + '%', background: c.avgFailRate > 0.3 ? 'var(--red)' : c.avgFailRate > 0.1 ? 'var(--orange)' : 'var(--green)' }"
                  ></div>
                </div>
                <span class="longevity-cohort__fail-pct" :style="{ color: c.avgFailRate > 0.3 ? 'var(--red)' : c.avgFailRate > 0.1 ? 'var(--orange)' : 'var(--text-muted)' }">
                  {{ c.avgFailRate > 0 ? (c.avgFailRate * 100).toFixed(0) + '% avg fail rate' : 'no failures' }}
                </span>
              </div>
              <div v-if="c.failedCount > 0" style="font-size:.58rem;color:var(--text-muted);margin-top:2px">{{ c.failedCount }} of {{ c.count }} have fail history</div>
            </div>
          </div>
          <div v-if="longevityCohorts.length >= 2" style="margin-top:8px;font-size:.62rem;color:var(--text-muted)">
            <template v-if="longevityCohorts.find(c => c.label === 'New') && longevityCohorts.find(c => c.label === 'Established')">
              <span v-if="(longevityCohorts.find(c => c.label === 'New')!.avgFailRate) > (longevityCohorts.find(c => c.label === 'Established')!.avgFailRate) * 1.5" style="color:var(--yellow)">⚠ New tests have higher fail rates — consider extra review before merging.</span>
              <span v-else style="color:var(--green)">✓ New and established tests have similar reliability.</span>
            </template>
          </div>
        </div>
      </div>

      <!-- Flakiness Timeline -->
      <div v-if="flakinessTimeline.length" style="margin-bottom:12px">
        <h3 style="font-size:.82rem;color:var(--text-sec);margin-bottom:6px" title="Tests with both passes and failures across runs. Dots = each run (● fail, ○ pass, · no data). Trend = comparing first half vs second half of history.">
          Flakiness Timeline
          <span style="font-size:.62rem;color:var(--text-muted);font-weight:400"> — {{ flakinessTimeline.length }} flaky test{{ flakinessTimeline.length === 1 ? '' : 's' }} · each dot = one run</span>
        </h3>
        <div class="card" style="padding:8px 12px">
          <div class="flaky-table">
            <div class="flaky-table__header">
              <span>Test</span>
              <span>Trend</span>
              <span>History (oldest → newest)</span>
              <span style="text-align:right">Fail rate</span>
            </div>
            <div
              v-for="row in flakinessTimeline"
              :key="row.name"
              class="flaky-table__row"
              @click="d.selectTest(d.tests.find(t => t.name === row.name)!)"
            >
              <span class="flaky-table__name" :title="row.name">{{ sn(row.name) }}</span>
              <span
                class="flaky-table__trend"
                :class="row.trend === 'rising' ? 'flaky-table__trend--rising' : row.trend === 'falling' ? 'flaky-table__trend--falling' : 'flaky-table__trend--stable'"
                :title="row.trend === 'rising' ? 'Getting flakier — fail rate increased in recent runs' : row.trend === 'falling' ? 'Stabilizing — fail rate decreased in recent runs' : 'Stable flakiness pattern'"
              >{{ row.trend === 'rising' ? '↑ rising' : row.trend === 'falling' ? '↓ stable' : '~ steady' }}</span>
              <span class="flaky-table__dots">
                <span
                  v-for="(bit, i) in row.bits"
                  :key="i"
                  class="flaky-table__dot"
                  :class="bit === true ? 'flaky-table__dot--fail' : bit === false ? 'flaky-table__dot--pass' : 'flaky-table__dot--none'"
                  :title="bit === true ? 'Failed' : bit === false ? 'Passed' : 'Not recorded'"
                ></span>
              </span>
              <span class="flaky-table__rate" :style="{ color: row.overallRate > 0.5 ? 'var(--red)' : row.overallRate > 0.2 ? 'var(--yellow)' : 'var(--text-sec)' }">{{ (row.overallRate * 100).toFixed(0) }}%</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Test retirement candidates -->
      <div v-if="retirementCandidates.length > 0" style="margin-bottom:12px">
        <h3 style="font-size:.82rem;color:var(--text-sec);margin-bottom:6px">
          Retirement Candidates
          <span style="font-size:.62rem;color:var(--text-muted);font-weight:400"> — {{ retirementCandidates.length }} always-passing, low-signal tests · consider deprioritizing</span>
        </h3>
        <div class="card" style="padding:8px 10px">
          <div class="retire-header">
            <span class="retire__name">Test</span>
            <span class="retire__rank">Rank</span>
            <span class="retire__dur">Duration</span>
            <span class="retire__deps">Deps</span>
          </div>
          <div
            v-for="t in retirementCandidates"
            :key="t.name"
            class="retire-row"
            @click="d.navigateToTestFromCov(t.name)"
            :title="t.name + '\ndepTotal: ' + t.depTotal + '\nClick to inspect'"
          >
            <span class="retire__name" :title="t.name">{{ sn(t.name) }}</span>
            <span class="retire__rank" style="color:var(--text-muted)">#{{ t.rank }}</span>
            <span class="retire__dur" style="color:var(--text-sec)">{{ t.duration >= 0 ? fmtDur(t.duration) : '—' }}</span>
            <span class="retire__deps" style="color:var(--text-muted)">{{ t.depTotal }}</span>
          </div>
          <div style="margin-top:6px;font-size:.6rem;color:var(--text-muted);border-top:1px solid rgba(51,65,85,.4);padding-top:5px">
            ⓘ These tests have <strong style="color:var(--text)">never failed</strong> in {{ d.runs.length }} runs, have no dep overlap with changed classes, and no fail history. Keeping them still validates correctness — but they contribute little to <em style="color:var(--accent-light)">fault detection ordering</em>.
          </div>
        </div>
      </div>

      <!-- First-Failure Detection Heatmap -->
      <div v-if="firstFailHeatmap" style="margin-bottom:12px">
        <h3 style="font-size:.82rem;color:var(--text-sec);margin-bottom:6px">
          First-Failure Detection
          <span style="font-size:.62rem;color:var(--text-muted);font-weight:400"> — {{ firstFailHeatmap.totalFailingRuns }} failing runs · <span style="color:var(--red)">■</span> failed · <span style="color:#fbbf24">★</span> first failure detected · click test to inspect</span>
        </h3>
        <div class="card ffh-wrap">
          <div class="ffh-header">
            <span class="ffh-row-name"></span>
            <span class="ffh-rank-col">Rank</span>
            <span class="ffh-first-col">1st✕</span>
            <span class="ffh-cells">
              <span
                v-for="(ts, ci) in firstFailHeatmap.runs"
                :key="ci"
                class="ffh-cell-hdr"
                :title="ts ? new Date(ts).toLocaleString() : 'Run ' + (ci + 1)"
              >R{{ ci + 1 }}</span>
            </span>
          </div>
          <div
            v-for="row in firstFailHeatmap.rows"
            :key="row.name"
            class="ffh-row"
            @click="d.selectTest(d.tests.find(t => t.name === row.name)!)"
            :title="row.name + '\nRank #' + row.rank + ' · first to fail in ' + row.firstCount + ' run' + (row.firstCount === 1 ? '' : 's')"
          >
            <span class="ffh-row-name" :title="row.name">{{ sn(row.name) }}</span>
            <span class="ffh-rank-col" :style="{ color: row.rank <= 3 ? 'var(--green)' : row.rank <= 10 ? 'var(--accent-light)' : 'var(--text-muted)' }">#{{ row.rank }}</span>
            <span class="ffh-first-col">
              <span
                class="ffh-first-badge"
                :class="row.firstCount > 0 ? 'ffh-first-badge--has' : 'ffh-first-badge--none'"
                :title="row.firstCount > 0 ? 'First to fail in ' + row.firstCount + ' run(s) · avg rank: #' + Math.round(row.avgFirstRank) : 'Never first to fail'"
              >{{ row.firstCount > 0 ? '★' + row.firstCount : '—' }}</span>
            </span>
            <span class="ffh-cells">
              <span
                v-for="(cell, ci) in row.cells"
                :key="ci"
                class="ffh-cell"
                :class="cell.isFirst ? 'ffh-cell--first' : cell.failed ? 'ffh-cell--fail' : 'ffh-cell--pass'"
                :title="(cell.isFirst ? '★ FIRST FAILURE in run ' + (ci + 1) : cell.failed ? 'Failed in run ' + (ci + 1) : 'Passed in run ' + (ci + 1))"
              >{{ cell.isFirst ? '★' : cell.failed ? '·' : '' }}</span>
            </span>
          </div>
        </div>
      </div>

      <!-- Execution Timeline (Gantt-style) -->
      <div v-if="ganttTests.length > 0 && d.tests.some(t => t.duration >= 0)" style="margin-bottom:12px">
        <h3 style="font-size:.82rem;color:var(--text-sec);margin-bottom:6px">
          Execution Timeline
          <span style="font-size:.62rem;color:var(--text-muted);font-weight:400"> — top {{ MAX_GANTT }} tests in priority order · bar width = duration · <span style="color:#ef4444">■</span> fail hist <span style="color:#4ade80">■</span> new <span style="color:#eab308">■</span> changed <span style="color:#3b82f6">■</span> dep overlap <span style="color:#fb923c">■</span> slow <span style="color:#6366f1">■</span> other · click to inspect</span>
        </h3>
        <div class="card gantt-wrap">
          <div
            v-for="(gt, gi) in ganttTests"
            :key="gt.name"
            class="gantt-row"
            :title="'#' + gt.rank + ' ' + gt.name + (gt.dur > 0 ? ' · ' + fmtDur(gt.dur) : ' · no duration') + (gt.failScore > 0 ? ' · fail history' : '') + (gt.isNew ? ' · new' : gt.isChanged ? ' · changed' : '')"
            @click="d.navigateToTestFromCov(gt.name)"
          >
            <span class="gantt-rank">{{ gt.rank }}</span>
            <span class="gantt-name" :title="gt.name">{{ dn(gt.name) }}</span>
            <span
              class="gantt-bar"
              :style="{ width: gt.w + 'px', background: gt.color }"
            ></span>
            <span v-if="gt.dur > 0" class="gantt-dur">{{ fmtDur(gt.dur) }}</span>
          </div>
        </div>
      </div>

      <!-- Test Rank Heatmap -->
      <div v-if="rankHeatmap && d.runs.length >= 2" style="margin-bottom:12px">
        <h3 style="font-size:.82rem;color:var(--text-sec);margin-bottom:4px">
          Rank Position Heatmap
          <span style="font-size:.62rem;color:var(--text-muted);font-weight:400"> — top {{ Math.min(HEATMAP_ROWS, d.tests.length) }} tests by rank variance · darker = higher rank (run earlier) · <strong style="color:var(--accent-light)">click to inspect</strong></span>
        </h3>
        <div class="card" style="padding:8px;overflow-x:auto">
          <div :style="{ display: 'grid', gridTemplateColumns: '140px repeat(' + rankHeatmap.runs.length + ', 1fr)', gap: '1px', minWidth: '400px' }">
            <!-- Header row: run labels -->
            <div style="font-size:.55rem;color:var(--text-muted);padding:1px 0">Test</div>
            <div
              v-for="(r, ri) in rankHeatmap.runs"
              :key="ri"
              style="font-size:.52rem;color:var(--text-muted);text-align:center;padding:1px 0;white-space:nowrap;overflow:hidden;cursor:pointer"
              :style="{ color: selectedRunIdx === (d.runs.length - 1 - ri) ? 'var(--accent-light)' : undefined, fontWeight: selectedRunIdx === (d.runs.length - 1 - ri) ? '700' : undefined }"
              :title="fmtTime(r.timestamp) + ' — ' + r.totalTests + ' tests, ' + (r.totalFailures ? r.totalFailures + ' failures' : 'all passed') + ' · click to inspect this run'"
              @click="selectRun(d.runs.length - 1 - ri)"
            >#{{ ri + 1 }}</div>
            <!-- Data rows -->
            <template v-for="row in rankHeatmap.rows" :key="row.name">
              <div
                class="heatmap__label"
                :title="row.name + (row.failScore > 0 ? ' — fail history' : '')"
                @click="d.navigateToTestFromCov(row.name)"
              >
                <span v-if="row.failScore > 0" style="color:var(--red);margin-right:2px;font-size:.55rem">✕</span>{{ dn(row.name) }}
              </div>
              <div
                v-for="(rank, ri) in row.ranks"
                :key="ri"
                class="heatmap__cell"
                :style="rank !== null ? {
                  background: `hsl(${Math.round(120 - (rank - 1) / Math.max(rankHeatmap.runs[ri].totalTests - 1, 1) * 120)}, 65%, ${(rank - 1) / Math.max(rankHeatmap.runs[ri].totalTests, 1) < 0.1 ? 45 : 28}%)`,
                  opacity: 0.9,
                } : { background: 'var(--bg-base)', opacity: 0.5 }"
                :title="row.name + ' — Run #' + (ri + 1) + ': rank ' + (rank ?? 'absent') + ' of ' + rankHeatmap.runs[ri].totalTests"
                @click="d.navigateToTestFromCov(row.name)"
              >
                <span v-if="rank !== null && rank <= 5" style="font-size:.48rem;color:rgba(255,255,255,.7);line-height:1">{{ rank }}</span>
              </div>
            </template>
          </div>
          <div style="margin-top:6px;display:flex;align-items:center;gap:8px;font-size:.58rem;color:var(--text-muted)">
            <span>Rank 1 (earliest)</span>
            <div style="width:80px;height:6px;border-radius:3px;background:linear-gradient(to right,hsl(120,65%,35%),hsl(60,65%,28%),hsl(0,65%,28%))"></div>
            <span>Last (latest)</span>
            <span style="margin-left:auto">Showing {{ rankHeatmap.rows.length }} of {{ rankHeatmap.totalTests }} tests sorted by rank variance</span>
          </div>
        </div>
      </div>

      <!-- Rising risk tests panel -->
      <div v-if="risingRiskTests.length > 0" style="margin-bottom:12px">
        <h3 style="font-size:.82rem;color:var(--text-sec);margin-bottom:8px">
          ⚠ Rising Risk Tests
          <span style="font-size:.62rem;color:var(--text-muted);font-weight:400"> — failure rate trending upward · click to inspect</span>
        </h3>
        <div style="display:flex;flex-wrap:wrap;gap:6px">
          <div
            v-for="r in risingRiskTests" :key="r.name"
            class="risk-card"
            @click="d.selectTest(d.tests.find(t => t.name === r.name)!, null); d.setTab('tests')"
            :title="r.name + '\nSlope: +' + (r.slope * 100).toFixed(1) + '% per run\nFails: ' + r.total + ' total, ' + r.recentFails + ' in last 3 runs'"
          >
            <div class="risk-card__name" :title="r.name">{{ sn(r.name) }}</div>
            <div class="risk-card__bars">
              <div v-for="(v, i) in r.series.slice(-8)" :key="i"
                class="risk-card__bar"
                :style="{ height: (v ? 12 : 4) + 'px', background: v ? '#ef4444' : 'rgba(74,222,128,.4)' }"
              ></div>
            </div>
            <div class="risk-card__slope">+{{ (r.slope * 100).toFixed(0) }}%/run</div>
          </div>
        </div>
      </div>

      <!-- Test reliability table (if we have multi-run data with failures) -->
      <div v-if="testFailRates.length > 0" style="margin-bottom:12px">
        <h3 style="font-size:.82rem;color:var(--text-sec);margin-bottom:8px">
          Most Unreliable Tests
          <span style="font-size:.62rem;color:var(--text-muted);font-weight:400"> — fail rate across all recorded runs · <strong style="color:var(--accent-light)">click to inspect</strong></span>
        </h3>
        <div style="overflow-x:auto">
          <table>
            <thead style="position:sticky;top:0;background:var(--bg-base);z-index:1">
              <tr>
                <th style="padding:3px 8px;text-align:left;font-size:.68rem;color:var(--text-sec)">Test</th>
                <th style="padding:3px 8px;text-align:right;font-size:.68rem;color:var(--text-sec)">Fail Rate</th>
                <th style="padding:3px 8px;text-align:right;font-size:.68rem;color:var(--text-sec)">Failures</th>
                <th style="padding:3px 8px;text-align:right;font-size:.68rem;color:var(--text-sec)">Seen in</th>
                <th style="padding:3px 8px;text-align:left;font-size:.68rem;color:var(--text-sec)" title="Pass/fail history across last 8 runs (oldest → newest). Red = fail, green = pass.">History</th>
                <th style="padding:3px 8px;text-align:right;font-size:.68rem;color:var(--text-sec)" title="Current rank in the prioritized test order">Rank</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="r in testFailRates"
                :key="r.name"
                @click="d.navigateToTestFromCov(r.name)"
                style="cursor:pointer"
                :title="r.name + ' — ' + r.failCount + ' failures in ' + r.seenCount + ' runs · click to inspect'"
              >
                <td style="padding:3px 8px;font-size:.7rem;max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" :title="r.name"
                  @mouseenter="classHover.show(r.name, $event)" @mousemove="classHover.move($event)" @mouseleave="classHover.hide()">{{ dn(r.name) }}</td>
                <td style="padding:3px 8px;text-align:right;font-size:.72rem;font-weight:700" :style="{ color: r.failRate >= 0.5 ? 'var(--red)' : r.failRate >= 0.2 ? 'var(--orange)' : 'var(--yellow)' }">{{ (r.failRate * 100).toFixed(0) }}%</td>
                <td style="padding:3px 8px;text-align:right;font-size:.68rem;color:var(--text-sec)">{{ r.failCount }}</td>
                <td style="padding:3px 8px;text-align:right;font-size:.68rem;color:var(--text-sec)">{{ r.seenCount }} runs</td>
                <td style="padding:3px 8px">
                  <div style="display:flex;gap:2px;align-items:center">
                    <template v-if="d.testHistoryMap.value.get(r.name)">
                      <div
                        v-for="(failed, hi) in d.testHistoryMap.value.get(r.name)!.last8"
                        :key="hi"
                        :style="{ width: '6px', height: '6px', borderRadius: '50%', flexShrink: '0', background: failed ? '#ef4444' : 'rgba(74,222,128,.6)' }"
                        :title="(failed ? 'FAILED' : 'passed') + ' in run #' + (Math.min(8, d.testHistoryMap.value.get(r.name)!.last8.length) - hi)"
                      ></div>
                    </template>
                    <template v-else>
                      <div style="width:48px;height:6px;background:var(--border);border-radius:3px;overflow:hidden">
                        <div :style="{ width: (r.failRate * 100) + '%', height: '100%', borderRadius: '3px', background: r.failRate >= 0.5 ? 'var(--red)' : r.failRate >= 0.2 ? 'var(--orange)' : 'var(--yellow)' }"></div>
                      </div>
                    </template>
                  </div>
                </td>
                <td style="padding:3px 8px;text-align:right;font-size:.68rem">
                  <template v-if="d.testsByName.value.get(r.name)">
                    <span style="color:var(--accent-light)">#{{ d.testsByName.value.get(r.name)!.rank }}</span>
                  </template>
                  <span v-else style="color:var(--text-muted)">—</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- Co-failure pairs -->
      <div v-if="coFailPairs.length > 0" style="margin-bottom:12px">
        <h3 style="font-size:.82rem;color:var(--text-sec);margin-bottom:6px">
          Failure Co-occurrence
          <span style="font-size:.62rem;color:var(--text-muted);font-weight:400"> — test pairs that tend to fail together · shared dependencies or env issues</span>
        </h3>
        <div style="display:flex;flex-direction:column;gap:3px">
          <div
            v-for="p in coFailPairs"
            :key="p.a + '|||' + p.b"
            class="cofail-row"
          >
            <span class="cofail-count" :title="p.count + ' runs where both failed simultaneously'">{{ p.count }}×</span>
            <span
              class="cofail-name"
              :title="p.a + ' — click to inspect'"
              @click="d.navigateToTestFromCov(p.a)"
              @mouseenter="classHover.show(p.a, $event)" @mousemove="classHover.move($event)" @mouseleave="classHover.hide()"
            >{{ dn(p.a) }}</span>
            <span class="cofail-sep">+</span>
            <span
              class="cofail-name"
              :title="p.b + ' — click to inspect'"
              @click="d.navigateToTestFromCov(p.b)"
              @mouseenter="classHover.show(p.b, $event)" @mousemove="classHover.move($event)" @mouseleave="classHover.hide()"
            >{{ dn(p.b) }}</span>
          </div>
        </div>
      </div>

      <!-- Failure burst detection -->
      <div v-if="failureBursts.length > 0" style="margin-bottom:12px">
        <h3 style="font-size:.82rem;color:var(--text-sec);margin-bottom:6px">
          Failure Bursts
          <span style="font-size:.62rem;color:var(--text-muted);font-weight:400"> — runs with unusually many failures (1.5σ above mean) · likely regressions or env issues</span>
        </h3>
        <div style="display:flex;flex-direction:column;gap:5px">
          <div
            v-for="burst in failureBursts"
            :key="burst.ts"
            class="burst-row"
          >
            <div class="burst-row__header">
              <span class="burst-row__count">{{ burst.count }} failures</span>
              <span class="burst-row__sigma" :title="'Z-score: ' + burst.zscore.toFixed(1) + 'σ above mean'">+{{ burst.zscore.toFixed(1) }}σ</span>
              <span class="burst-row__ts">{{ burst.ts ? new Date(burst.ts).toLocaleDateString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '' }}</span>
            </div>
            <div class="burst-row__chips">
              <span
                v-for="name in burst.failures.slice(0, 8)"
                :key="name"
                class="burst-row__chip"
                :title="name + ' — click to inspect'"
                @click="d.navigateToTestFromCov(name)"
              >{{ sn(name) }}</span>
              <span v-if="burst.failures.length > 8" class="burst-row__chip burst-row__chip--more">+{{ burst.failures.length - 8 }} more</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Run history browser -->
      <div v-if="d.runs.length" style="margin-bottom:12px">
        <h3 style="font-size:.82rem;color:var(--text-sec);margin-bottom:6px">
          Run History Browser
          <span style="font-size:.62rem;color:var(--text-muted);font-weight:400"> — click a run to inspect · <kbd style="font-size:.55rem;padding:1px 4px;border:1px solid var(--border);border-radius:2px;background:var(--bg-card)">⇧</kbd>+click to compare two runs</span>
        </h3>
        <!-- Compact health timeline bar — oldest-left, newest-right -->
        <div class="run-health-bar" :title="'Pass/fail timeline across all ' + d.runs.length + ' runs. Bar height = APFD. Red = failures. Oldest left, newest right.'">
          <div
            v-for="(r, i) in d.runs"
            :key="r.timestamp"
            class="run-health-bar__seg"
            :class="{
              'run-health-bar__seg--fail': r.totalFailures > 0,
              'run-health-bar__seg--pass': r.totalFailures === 0,
              'run-health-bar__seg--selected': selectedRunIdx === i,
            }"
            :style="{ height: Math.max(3, Math.round(r.apfd * 20)) + 'px' }"
            :title="'Run #' + (d.runs.length - i) + ': ' + (r.totalFailures > 0 ? r.totalFailures + ' failures' : 'all passed') + ' · APFD ' + (r.apfd * 100).toFixed(0) + '%'"
            @click="selectRun(i)"
          ></div>
        </div>
        <div style="display:flex;flex-wrap:wrap;gap:4px;margin-bottom:8px">
          <button
            v-for="(r, i) in [...d.runs].reverse()"
            :key="r.timestamp"
            class="analytics__run-chip"
            :class="{
              'analytics__run-chip--fail': r.totalFailures > 0,
              'analytics__run-chip--pass': r.totalFailures === 0,
              'analytics__run-chip--selected': selectedRunIdx === (d.runs.length - 1 - i),
              'analytics__run-chip--compare': compareRunIdx === (d.runs.length - 1 - i),
            }"
            @click="handleRunChipClick(d.runs.length - 1 - i, $event)"
            :title="fmtTime(r.timestamp) + ' — ' + r.totalTests + ' tests, ' + r.totalFailures + ' failures, APFD ' + (r.apfd * 100).toFixed(1) + '%\n⇧+click to compare with selected run'"
          >
            <div class="analytics__run-chip-row">
              <span style="font-size:.56rem;opacity:.6">#{{ i + 1 }}</span>
              <span v-if="r.totalFailures > 0" style="color:#f87171;font-weight:700;font-size:.6rem">{{ r.totalFailures }}✕</span>
              <span v-else style="color:#4ade80;font-size:.6rem">✓</span>
            </div>
            <div style="font-size:.56rem;color:var(--text-muted);line-height:1;margin-bottom:1px">{{ (r.apfd * 100).toFixed(0) }}%</div>
            <div style="font-size:.52rem;color:var(--text-sec);line-height:1;margin-bottom:1px;white-space:nowrap">{{ fmtRunChipTime(r.timestamp) }}</div>
            <div class="analytics__run-chip-apfd" :style="{ background: r.apfd >= 0.7 ? 'var(--green)' : r.apfd >= 0.5 ? 'var(--yellow)' : 'var(--red)', width: Math.round(r.apfd * 100) + '%' }"></div>
          </button>
        </div>

        <!-- Run detail drill-down -->
        <div v-if="selectedRun" class="detail-panel">
          <div style="display:flex;align-items:center;gap:10px;margin-bottom:8px;flex-wrap:wrap">
            <button @click="selectRun(Math.max(0, selectedRunIdx! - 1))" :disabled="selectedRunIdx === 0" class="analytics__nav-btn" title="Previous (older) run (‹)">‹</button>
            <span style="font-size:.82rem;font-weight:600;color:var(--text)">Run #{{ d.runs.length - selectedRunIdx! }} <span style="font-size:.62rem;color:var(--text-muted)">of {{ d.runs.length }}</span></span>
            <button @click="selectRun(Math.min(d.runs.length - 1, selectedRunIdx! + 1))" :disabled="selectedRunIdx === d.runs.length - 1" class="analytics__nav-btn" title="Next (newer) run (›)">›</button>
            <span style="font-size:.72rem;color:var(--text-sec)">{{ fmtTime(selectedRun.timestamp) }}</span>
            <span class="badge" :style="{ background: selectedRun.totalFailures > 0 ? 'rgba(127,29,29,.4)' : 'rgba(20,83,45,.4)', color: selectedRun.totalFailures > 0 ? 'var(--red)' : 'var(--green)' }">
              {{ selectedRun.totalFailures > 0 ? selectedRun.totalFailures + ' failures' : 'all passed' }}
            </span>
            <span style="font-size:.72rem;color:var(--text-sec)">APFD: <strong :style="{ color: selectedRun.apfd >= 0.7 ? 'var(--green)' : selectedRun.apfd >= 0.5 ? 'var(--yellow)' : 'var(--red)' }">{{ (selectedRun.apfd * 100).toFixed(1) }}%</strong>
              <template v-if="selectedRunIdx! > 0">
                <span style="font-size:.6rem;margin-left:3px" :style="{ color: (selectedRun.apfd - d.runs[selectedRunIdx! - 1].apfd) > 0.01 ? 'var(--green)' : (selectedRun.apfd - d.runs[selectedRunIdx! - 1].apfd) < -0.01 ? 'var(--red)' : 'var(--text-muted)' }">
                  ({{ (selectedRun.apfd - d.runs[selectedRunIdx! - 1].apfd) > 0 ? '+' : '' }}{{ ((selectedRun.apfd - d.runs[selectedRunIdx! - 1].apfd) * 100).toFixed(1) }}pp vs prev)
                </span>
              </template>
            </span>
            <span style="font-size:.72rem;color:var(--text-sec)">{{ selectedRun.totalTests }} tests</span>
            <span v-if="selectedRun.firstFailurePosition >= 0" style="font-size:.72rem;color:var(--text-sec)">First failure at position <strong style="color:var(--orange)">{{ selectedRun.firstFailurePosition + 1 }}</strong></span>
            <button @click="selectedRunIdx = null" style="margin-left:auto;padding:2px 8px;font-size:.65rem;background:var(--border);color:var(--text-sec);border:1px solid var(--text-muted);border-radius:3px;cursor:pointer">✕ Close</button>
          </div>
          <!-- Run composition summary -->
          <div v-if="runComposition" class="run-comp-bar">
            <span class="run-comp-bar__label">Score drivers:</span>
            <span
              v-if="runComposition.failed > 0"
              class="run-comp-bar__chip run-comp-bar__chip--fail"
              :title="runComposition.failed + ' tests failed in this run'"
            >✕ {{ runComposition.failed }} failed</span>
            <span
              v-if="runComposition.withFail > 0"
              class="run-comp-bar__chip run-comp-bar__chip--failhist"
              :title="runComposition.withFail + ' tests have fail history (EMA-weighted)'"
            >⚠ {{ runComposition.withFail }} fail hist</span>
            <span
              v-if="runComposition.withDep > 0"
              class="run-comp-bar__chip run-comp-bar__chip--dep"
              :title="runComposition.withDep + ' tests have dependency overlap with changed source classes'"
            >◈ {{ runComposition.withDep }} dep overlap</span>
            <span
              v-if="runComposition.withChanged > 0"
              class="run-comp-bar__chip run-comp-bar__chip--chg"
              :title="runComposition.withChanged + ' test sources were changed in this run'"
            >✎ {{ runComposition.withChanged }} changed</span>
            <span
              v-if="runComposition.withStatic > 0"
              class="run-comp-bar__chip run-comp-bar__chip--static"
              :title="runComposition.withStatic + ' tests have static field overlap with changed classes'"
            >⚡ {{ runComposition.withStatic }} static</span>
            <span class="run-comp-bar__total">{{ runComposition.total }} total</span>
          </div>
          <!-- Run diff vs previous -->
          <div v-if="runDiff && (runDiff.newFailures.length || runDiff.recoveries.length || runDiff.newTests.length || runDiff.rankChanges.length)" class="run-diff">
            <div class="run-diff__title">Changes vs Run #{{ d.runs.length - selectedRunIdx! + 1 }} (previous)</div>
            <!-- New failures -->
            <div v-if="runDiff.newFailures.length" class="run-diff__group">
              <div class="run-diff__group-hdr run-diff__group-hdr--fail" @click="runDiffOpen.failures = !runDiffOpen.failures">
                <span>✕ {{ runDiff.newFailures.length }} new failure{{ runDiff.newFailures.length > 1 ? 's' : '' }}</span>
                <span class="run-diff__toggle">{{ runDiffOpen.failures ? '▲' : '▼' }}</span>
              </div>
              <div v-if="runDiffOpen.failures" class="run-diff__items">
                <span
                  v-for="o in runDiff.newFailures" :key="o.testClass"
                  class="run-diff__item run-diff__item--fail"
                  :title="o.testClass + ' — passed last run, failed this run'"
                  @click="d.navigateToTestFromCov(o.testClass)"
                >{{ dn(o.testClass) }}</span>
              </div>
            </div>
            <!-- Recoveries -->
            <div v-if="runDiff.recoveries.length" class="run-diff__group">
              <div class="run-diff__group-hdr run-diff__group-hdr--pass" @click="runDiffOpen.recoveries = !runDiffOpen.recoveries">
                <span>✓ {{ runDiff.recoveries.length }} recover{{ runDiff.recoveries.length > 1 ? 'ies' : 'y' }}</span>
                <span class="run-diff__toggle">{{ runDiffOpen.recoveries ? '▲' : '▼' }}</span>
              </div>
              <div v-if="runDiffOpen.recoveries" class="run-diff__items">
                <span
                  v-for="o in runDiff.recoveries" :key="o.testClass"
                  class="run-diff__item run-diff__item--pass"
                  :title="o.testClass + ' — failed last run, passed this run'"
                  @click="d.navigateToTestFromCov(o.testClass)"
                >{{ dn(o.testClass) }}</span>
              </div>
            </div>
            <!-- New tests -->
            <div v-if="runDiff.newTests.length" class="run-diff__group">
              <div class="run-diff__group-hdr run-diff__group-hdr--new" @click="runDiffOpen.new = !runDiffOpen.new">
                <span>+ {{ runDiff.newTests.length }} new test{{ runDiff.newTests.length > 1 ? 's' : '' }}</span>
                <span class="run-diff__toggle">{{ runDiffOpen.new ? '▲' : '▼' }}</span>
              </div>
              <div v-if="runDiffOpen.new" class="run-diff__items">
                <span
                  v-for="o in runDiff.newTests" :key="o.testClass"
                  class="run-diff__item run-diff__item--new"
                  :title="o.testClass + ' — not in previous run'"
                  @click="d.navigateToTestFromCov(o.testClass)"
                >{{ dn(o.testClass) }}</span>
              </div>
            </div>
            <!-- Rank changes -->
            <div v-if="runDiff.rankChanges.length" class="run-diff__group">
              <div class="run-diff__group-hdr run-diff__group-hdr--rank" @click="runDiffOpen.ranks = !runDiffOpen.ranks">
                <span>↕ {{ runDiff.rankChanges.length }} rank shift{{ runDiff.rankChanges.length > 1 ? 's' : '' }} ≥5</span>
                <span class="run-diff__toggle">{{ runDiffOpen.ranks ? '▲' : '▼' }}</span>
              </div>
              <div v-if="runDiffOpen.ranks" class="run-diff__items run-diff__items--rank">
                <div
                  v-for="r in runDiff.rankChanges" :key="r.name"
                  class="run-diff__rank-row"
                  :title="r.name + ' — rank ' + r.prevRank + ' → ' + r.curRank"
                  @click="d.navigateToTestFromCov(r.name)"
                >
                  <span class="run-diff__rank-delta" :style="{ color: r.delta > 0 ? 'var(--green)' : 'var(--red)' }">{{ r.delta > 0 ? '↑' : '↓' }}{{ Math.abs(r.delta) }}</span>
                  <span class="run-diff__rank-name" :title="r.name">{{ dn(r.name) }}</span>
                  <span class="run-diff__rank-pos">#{{ r.prevRank }} → #{{ r.curRank }}</span>
                </div>
              </div>
            </div>
          </div>
          <!-- Filter controls -->
          <div style="display:flex;align-items:center;gap:8px;margin-bottom:8px;flex-wrap:wrap">
            <button
              class="analytics__toggle-btn"
              :class="{ 'analytics__toggle-btn--active': showFailuresOnly }"
              @click="showFailuresOnly = !showFailuresOnly"
              :title="showFailuresOnly ? 'Showing only failed tests — click to show all' : 'Click to show only failed tests'"
            >{{ showFailuresOnly ? '✕ failures only (' + selectedRun.totalFailures + ')' : 'Show failures only' }}</button>
            <input
              v-model="runDetailSearch"
              placeholder="Filter tests…"
              style="padding:2px 8px;font-size:.65rem;background:var(--bg-base);color:var(--text);border:1px solid var(--border);border-radius:4px;outline:none;width:160px"
              @focus="($event.target as HTMLInputElement).style.borderColor='var(--accent)'"
              @blur="($event.target as HTMLInputElement).style.borderColor='var(--border)'"
            />
            <span style="font-size:.62rem;color:var(--text-muted)">{{ filteredRunOutcomes.length }} of {{ selectedRun.totalTests }} shown</span>
            <!-- Page size selector -->
            <div style="display:flex;align-items:center;gap:3px;margin-left:auto">
              <span style="font-size:.58rem;color:var(--text-muted)">Per page:</span>
              <button v-for="sz in RUN_DETAIL_PAGE_SIZES" :key="sz"
                class="analytics__page-sz-btn"
                :class="{ 'analytics__page-sz-btn--active': runDetailPageSize === sz }"
                @click="runDetailPageSize = sz; runDetailPage = 0"
              >{{ sz === 0 ? 'All' : sz }}</button>
            </div>
          </div>
          <!-- Pagination controls -->
          <div v-if="runDetailPageCount > 1" style="display:flex;align-items:center;gap:6px;margin-bottom:6px">
            <button class="analytics__page-btn" @click="runDetailPage = 0" :disabled="runDetailPage === 0">«</button>
            <button class="analytics__page-btn" @click="runDetailPage--" :disabled="runDetailPage === 0">‹</button>
            <span style="font-size:.62rem;color:var(--text-sec)">{{ runDetailPage + 1 }} / {{ runDetailPageCount }}</span>
            <button class="analytics__page-btn" @click="runDetailPage++" :disabled="runDetailPage >= runDetailPageCount - 1">›</button>
            <button class="analytics__page-btn" @click="runDetailPage = runDetailPageCount - 1" :disabled="runDetailPage >= runDetailPageCount - 1">»</button>
          </div>
          <div style="overflow-x:auto;max-height:300px;overflow-y:auto">
            <table>
              <thead style="position:sticky;top:0;background:var(--bg-card);z-index:1">
                <tr>
                  <th style="padding:3px 8px;text-align:right;font-size:.68rem;color:var(--text-sec)">Rank</th>
                  <th style="padding:3px 8px;text-align:left;font-size:.68rem;color:var(--text-sec)">Test</th>
                  <th style="padding:3px 8px;text-align:right;font-size:.68rem;color:var(--text-sec)" title="Score at time of this run">Score</th>
                  <th style="padding:3px 8px;text-align:left;font-size:.68rem;color:var(--text-sec);min-width:50px" title="Score composition — fail(red) dep(blue) change(yellow) speed(green) static(purple)">Composition</th>
                  <th v-if="d.runs.length >= 3" style="padding:3px 8px;text-align:left;font-size:.68rem;color:var(--text-sec);min-width:40px" title="Speed ratio trend across runs — line above midpoint = faster than median, below = slower. Orange = getting slower, green = getting faster.">Speed</th>
                  <th v-if="selectedRunIdx! > 0" style="padding:3px 8px;text-align:right;font-size:.68rem;color:var(--text-sec)" title="Score change vs previous (older) run">Δ Score</th>
                  <th style="padding:3px 8px;text-align:center;font-size:.68rem;color:var(--text-sec)">Result</th>
                  <th style="padding:3px 8px;text-align:left;font-size:.68rem;color:var(--text-sec)">Flags</th>
                  <th style="padding:3px 8px;text-align:right;font-size:.68rem;color:var(--text-sec)" title="Compare to current rank">vs Current</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="(o, idx) in pagedRunOutcomes"
                  :key="o.testClass"
                  :class="{ 'analytics__run-row--fail': o.failed }"
                  style="cursor:pointer"
                  @click="d.navigateToTestFromCov(o.testClass)"
                  :title="o.testClass + ' — click to go to Tests tab'"
                >
                  <td style="padding:2px 8px;text-align:right;font-size:.68rem;color:var(--text-sec)">#{{ runDetailPage * (runDetailPageSize || filteredRunOutcomes.length) + idx + 1 }}</td>
                  <td style="padding:2px 8px;font-size:.7rem;max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" :title="o.testClass">{{ dn(o.testClass) }}</td>
                  <td style="padding:2px 8px;text-align:right;font-size:.7rem;font-weight:700;color:var(--accent-light)">{{ o.score }}</td>
                  <td style="padding:2px 8px">
                    <div style="display:flex;height:6px;width:50px;border-radius:2px;overflow:hidden;background:rgba(51,65,85,.4)">
                      <div v-for="(seg, si) in scoreBar(o)" :key="si" :style="{ width: seg.w + '%', background: seg.c, flexShrink: 0 }"></div>
                    </div>
                  </td>
                  <td v-if="d.runs.length >= 3" style="padding:2px 8px" :title="speedRatioHistoryMap.get(o.testClass)?.length ? 'Speed trend across ' + speedRatioHistoryMap.get(o.testClass)!.length + ' runs. Center line = median. Above = faster, below = slower.' : 'Not enough data'"
                    v-html="speedTrendSvg(o.testClass) ?? ''"></td>
                  <td v-if="selectedRunIdx! > 0" style="padding:2px 8px;text-align:right;font-size:.68rem">
                    <template v-if="prevRunScoreMap.has(o.testClass)">
                      <span :style="{ color: (o.score - prevRunScoreMap.get(o.testClass)!) > 0 ? 'var(--green)' : (o.score - prevRunScoreMap.get(o.testClass)!) < 0 ? 'var(--red)' : 'var(--text-muted)' }" :title="'Score change: ' + prevRunScoreMap.get(o.testClass) + ' → ' + o.score">
                        {{ (o.score - prevRunScoreMap.get(o.testClass)!) > 0 ? '+' : '' }}{{ o.score - prevRunScoreMap.get(o.testClass)! }}
                      </span>
                    </template>
                    <span v-else style="color:var(--text-muted);font-size:.6rem" title="Not in previous run">new</span>
                  </td>
                  <td style="padding:2px 8px;text-align:center">
                    <span v-if="o.failed" class="badge" style="background:rgba(127,29,29,.4);color:var(--red)">FAIL</span>
                    <span v-else class="badge" style="background:rgba(20,83,45,.3);color:var(--green)">pass</span>
                  </td>
                  <td style="padding:2px 8px;display:flex;gap:2px;align-items:center;flex-wrap:wrap">
                    <span v-if="o.isNew" class="badge" style="background:rgba(34,197,94,.18);color:var(--green);font-size:.45rem" title="New test in this run">NEW</span>
                    <span v-if="o.isChanged" class="badge" style="background:rgba(234,179,8,.18);color:var(--yellow);font-size:.45rem" title="Test source was changed">CHG</span>
                    <span v-if="o.isFast" class="badge" style="background:rgba(6,182,212,.12);color:var(--cyan);font-size:.45rem" title="Fast test (below median duration)">fast</span>
                    <span v-if="o.isSlow" class="badge" style="background:rgba(249,115,22,.12);color:var(--orange);font-size:.45rem" title="Slow test (above median duration)">slow</span>
                    <span v-if="o.hasStaticFieldOverlap" class="badge" style="background:rgba(168,85,247,.12);color:var(--purple);font-size:.45rem" title="Static field overlap with changed classes">SFO</span>
                    <span v-if="o.depOverlap > 0" style="font-size:.58rem;color:var(--cyan)" :title="o.depOverlap + ' deps overlap with changed classes'">◈{{ o.depOverlap }}</span>
                  </td>
                  <td style="padding:2px 8px;text-align:right;font-size:.68rem">
                  <template v-if="d.testsByName.value.get(o.testClass)">
                      <span :style="{ color: d.testsByName.value.get(o.testClass)!.rank - (idx + 1) < -3 ? 'var(--green)' : d.testsByName.value.get(o.testClass)!.rank - (idx + 1) > 3 ? 'var(--red)' : 'var(--text-muted)' }">
                        {{ d.testsByName.value.get(o.testClass)!.rank - (idx + 1) > 0 ? '↓' + (d.testsByName.value.get(o.testClass)!.rank - (idx + 1)) : d.testsByName.value.get(o.testClass)!.rank - (idx + 1) < 0 ? '↑' + Math.abs(d.testsByName.value.get(o.testClass)!.rank - (idx + 1)) : '=' }}
                      </span>
                    </template>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- Two-run comparison panel -->
        <div v-if="twoRunComparison" class="two-run-cmp card" style="margin-top:8px">
          <div class="two-run-cmp__header">
            <span class="two-run-cmp__title">Comparing {{ twoRunComparison.labelA }} vs {{ twoRunComparison.labelB }}</span>
            <div class="two-run-cmp__kpis">
              <div class="kpi two-run-cmp__kpi">
                <div class="two-run-cmp__kpi-label">APFD Δ</div>
                <div class="two-run-cmp__kpi-val" :style="{ color: twoRunComparison.apfdDelta > 0.5 ? 'var(--green)' : twoRunComparison.apfdDelta < -0.5 ? 'var(--red)' : 'var(--text-muted)' }">
                  {{ twoRunComparison.apfdDelta > 0 ? '+' : '' }}{{ twoRunComparison.apfdDelta.toFixed(1) }}pp
                </div>
              </div>
              <div class="kpi two-run-cmp__kpi" v-if="twoRunComparison.newFails > 0">
                <div class="two-run-cmp__kpi-label">New Failures</div>
                <div class="two-run-cmp__kpi-val" style="color:var(--red)">{{ twoRunComparison.newFails }}</div>
              </div>
              <div class="kpi two-run-cmp__kpi" v-if="twoRunComparison.recovered > 0">
                <div class="two-run-cmp__kpi-label">Recovered</div>
                <div class="two-run-cmp__kpi-val" style="color:var(--green)">{{ twoRunComparison.recovered }}</div>
              </div>
              <div class="kpi two-run-cmp__kpi">
                <div class="two-run-cmp__kpi-label">{{ twoRunComparison.labelA }} APFD</div>
                <div class="two-run-cmp__kpi-val">{{ (twoRunComparison.runA.apfd * 100).toFixed(1) }}%</div>
              </div>
              <div class="kpi two-run-cmp__kpi">
                <div class="two-run-cmp__kpi-label">{{ twoRunComparison.labelB }} APFD</div>
                <div class="two-run-cmp__kpi-val">{{ (twoRunComparison.runB.apfd * 100).toFixed(1) }}%</div>
              </div>
            </div>
            <button class="two-run-cmp__close" @click="compareRunIdx = null" title="Close comparison">✕</button>
          </div>
          <div style="overflow-x:auto;max-height:260px;overflow-y:auto;margin-top:6px">
            <table style="width:100%;border-collapse:collapse">
              <thead style="position:sticky;top:0;background:var(--bg-card);z-index:1">
                <tr>
                  <th class="two-run-cmp__th">Status</th>
                  <th class="two-run-cmp__th two-run-cmp__th--name">Test</th>
                  <th class="two-run-cmp__th two-run-cmp__th--r" :title="twoRunComparison.labelA + ' rank'">Rank {{ twoRunComparison.labelA }}</th>
                  <th class="two-run-cmp__th two-run-cmp__th--r" :title="twoRunComparison.labelB + ' rank'">Rank {{ twoRunComparison.labelB }}</th>
                  <th class="two-run-cmp__th two-run-cmp__th--r" title="Rank change (positive = moved earlier in run order)">Δ Rank</th>
                  <th class="two-run-cmp__th two-run-cmp__th--r" title="Score delta">Δ Score</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="row in twoRunComparison.rows.slice(0, 80)"
                  :key="row.name"
                  class="two-run-cmp__row"
                  :class="'two-run-cmp__row--' + row.status"
                  @click="d.navigateToTestFromCov(row.name)"
                  :title="row.name + '\n\nClick to inspect test'"
                >
                  <td class="two-run-cmp__td">
                    <span class="two-run-cmp__status-badge" :class="'two-run-cmp__status-badge--' + row.status">
                      {{ row.status === 'new-fail' ? '✕ new fail' : row.status === 'recovered' ? '✓ recovered' : row.status === 'still-fail' ? '✕ fail' : row.status === 'new' ? '+ new' : row.status === 'absent' ? '− absent' : '' }}
                    </span>
                  </td>
                  <td class="two-run-cmp__td two-run-cmp__td--name">{{ dn(row.name) }}</td>
                  <td class="two-run-cmp__td two-run-cmp__td--r">{{ row.rankA !== null ? '#' + row.rankA : '—' }}</td>
                  <td class="two-run-cmp__td two-run-cmp__td--r">{{ row.rankB !== null ? '#' + row.rankB : '—' }}</td>
                  <td class="two-run-cmp__td two-run-cmp__td--r" :style="{ color: row.rankDelta < 0 ? 'var(--green)' : row.rankDelta > 0 ? 'var(--red)' : 'var(--text-muted)' }">
                    {{ row.rankDelta !== 0 ? (row.rankDelta < 0 ? '↑' : '↓') + Math.abs(row.rankDelta) : '—' }}
                  </td>
                  <td class="two-run-cmp__td two-run-cmp__td--r" :style="{ color: row.scoreDelta > 0.5 ? 'var(--green)' : row.scoreDelta < -0.5 ? 'var(--red)' : 'var(--text-muted)' }">
                    {{ row.scoreA !== null && row.scoreB !== null ? (row.scoreDelta > 0 ? '+' : '') + row.scoreDelta.toFixed(1) : '—' }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-if="twoRunComparison.rows.length > 80" style="font-size:.6rem;color:var(--text-muted);padding-top:4px">Showing top 80 of {{ twoRunComparison.rows.length }} tests (failures and largest rank changes first)</div>
        </div>
      </div>

      <!-- Run comparison -->
      <div v-if="d.runDiff.value.length" style="margin-bottom:14px">
        <h3 style="font-size:.82rem;color:var(--text-sec);margin-bottom:8px;display:flex;align-items:center;gap:8px;flex-wrap:wrap">
          Last Run Comparison <span style="font-size:.65rem;color:var(--text-muted);font-weight:400">— vs previous run</span>
          <button
            v-if="d.runDiff.value.filter(e => e.status === 'absent').length > 0"
            class="analytics__toggle-btn"
            :class="{ 'analytics__toggle-btn--active': showAbsent }"
            @click="showAbsent = !showAbsent"
            title="Tests that ran in the previous run but were absent from the latest — click to toggle"
          >{{ showAbsent ? '✕ hide absent' : '+ show absent (' + d.runDiff.value.filter(e => e.status === 'absent').length + ')' }}</button>
        </h3>
        <div style="display:flex;gap:8px;margin-bottom:8px;flex-wrap:wrap">
          <div class="kpi" style="padding:5px 10px">
            <div style="font-size:.58rem;color:var(--text-sec)">Newly Failed</div>
            <div style="font-size:.9rem;font-weight:700;color:var(--red)">{{ d.runDiff.value.filter(e => e.status === 'newly-failed').length }}</div>
          </div>
          <div class="kpi" style="padding:5px 10px">
            <div style="font-size:.58rem;color:var(--text-sec)">Recovered</div>
            <div style="font-size:.9rem;font-weight:700;color:var(--green)">{{ d.runDiff.value.filter(e => e.status === 'recovered').length }}</div>
          </div>
          <div class="kpi" style="padding:5px 10px">
            <div style="font-size:.58rem;color:var(--text-sec)">New Tests</div>
            <div style="font-size:.9rem;font-weight:700;color:var(--cyan)">{{ d.runDiff.value.filter(e => e.status === 'new').length }}</div>
          </div>
          <div class="kpi" style="padding:5px 10px">
            <div style="font-size:.58rem;color:var(--text-sec)">Improved</div>
            <div style="font-size:.9rem;font-weight:700;color:var(--green)">{{ d.runDiff.value.filter(e => e.status === 'improved').length }}</div>
          </div>
          <div class="kpi" style="padding:5px 10px">
            <div style="font-size:.58rem;color:var(--text-sec)">Regressed</div>
            <div style="font-size:.9rem;font-weight:700;color:var(--orange)">{{ d.runDiff.value.filter(e => e.status === 'regressed').length }}</div>
          </div>
        </div>
        <div style="overflow-x:auto;max-height:260px;overflow-y:auto">
          <table>
            <thead style="position:sticky;top:0;background:var(--bg-base);z-index:1">
              <tr>
                <th style="padding:4px 8px;text-align:left">Test</th>
                <th style="padding:4px 8px;text-align:left">Status</th>
                <th style="padding:4px 8px;text-align:right">Prev Rank</th>
                <th style="padding:4px 8px;text-align:right">Curr Rank</th>
                <th style="padding:4px 8px;text-align:right">Shift</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="e in d.runDiff.value.filter(e => e.status !== 'unchanged' && (showAbsent || e.status !== 'absent'))" :key="e.name"
                style="cursor:pointer"
                @click="d.navigateToTestFromCov(e.name)"
                :title="e.name + ' — click to go to Tests tab'"
              >
                <td style="padding:3px 8px;max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" :title="e.name"
                  @mouseenter="classHover.show(e.name, $event)" @mousemove="classHover.move($event)" @mouseleave="classHover.hide()">{{ dn(e.name) }}</td>
                <td style="padding:3px 8px">
                  <span class="badge" :class="{
                    'analytics__diff--fail': e.status === 'newly-failed',
                    'analytics__diff--recover': e.status === 'recovered',
                    'analytics__diff--new': e.status === 'new',
                    'analytics__diff--removed': e.status === 'absent',
                    'analytics__diff--improved': e.status === 'improved',
                    'analytics__diff--regressed': e.status === 'regressed',
                  }">{{ e.status }}</span>
                </td>
                <td style="padding:3px 8px;text-align:right;color:var(--text-sec)">{{ e.prevRank ?? '—' }}</td>
                <td style="padding:3px 8px;text-align:right;color:var(--text-sec)">{{ e.currRank ?? '—' }}</td>
                <td style="padding:3px 8px;text-align:right;font-weight:700" :style="{ color: e.rankDelta < 0 ? 'var(--green)' : e.rankDelta > 0 ? 'var(--red)' : 'var(--text-muted)' }">
                  {{ e.rankDelta === 0 ? '–' : e.rankDelta > 0 ? '+' + e.rankDelta : e.rankDelta }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- Coverage -->
      <div v-if="d.hasCoverage" id="analytics-coverage">
        <h3 style="font-size:.82rem;color:var(--text-sec);margin-bottom:8px">Coverage</h3>
        <div style="display:flex;gap:8px;margin-bottom:10px;flex-wrap:wrap;align-items:center">
          <div class="kpi analytics__cov-kpi">
            <div class="analytics__cov-kpi-label">Source Classes</div>
            <div class="analytics__cov-kpi-value analytics__cov-kpi-value--accent">{{ d.dd.coverage?.totalSourceClasses }}</div>
          </div>
          <div class="kpi analytics__cov-kpi">
            <div class="analytics__cov-kpi-label">Packages</div>
            <div class="analytics__cov-kpi-value analytics__cov-kpi-value--accent">{{ d.covPackages.value.length }}</div>
          </div>
          <div class="kpi analytics__cov-kpi">
            <div class="analytics__cov-kpi-label">Avg Tests/Class</div>
            <div class="analytics__cov-kpi-value" style="color:var(--green)">{{ d.covAvgTests.value }}</div>
          </div>
          <div class="kpi analytics__cov-kpi" style="min-width:160px">
            <div class="analytics__cov-kpi-label">Overall Coverage</div>
            <div style="display:flex;align-items:center;gap:6px">
              <div class="analytics__progress-bar">
                <div class="analytics__progress-fill" :class="d.covPercent.value >= 80 ? 'analytics__progress-fill--green' : d.covPercent.value >= 50 ? 'analytics__progress-fill--yellow' : 'analytics__progress-fill--red'" :style="{ width: d.covPercent.value + '%' }"></div>
              </div>
              <span class="analytics__pct" :class="d.covPercent.value >= 80 ? 'analytics__pct--green' : d.covPercent.value >= 50 ? 'analytics__pct--yellow' : 'analytics__pct--red'">{{ d.covPercent.value }}%</span>
            </div>
          </div>
          <div v-if="d.hasMethodCoverage.value" class="kpi analytics__cov-kpi" style="min-width:160px" title="Fraction of tracked methods exercised by at least one test">
            <div class="analytics__cov-kpi-label">Method Coverage</div>
            <div style="display:flex;align-items:center;gap:6px">
              <div class="analytics__progress-bar">
                <div class="analytics__progress-fill" :class="(d.covMethodPercent.value??0) >= 80 ? 'analytics__progress-fill--green' : (d.covMethodPercent.value??0) >= 50 ? 'analytics__progress-fill--yellow' : 'analytics__progress-fill--red'" :style="{ width: (d.covMethodPercent.value??0) + '%' }"></div>
              </div>
              <span class="analytics__pct" :class="(d.covMethodPercent.value??0) >= 80 ? 'analytics__pct--green' : (d.covMethodPercent.value??0) >= 50 ? 'analytics__pct--yellow' : 'analytics__pct--red'">{{ d.covMethodPercent.value }}%</span>
            </div>
          </div>
        </div>

        <!-- Selection coverage -->
        <div v-if="d.selectionCoverage.value" class="card" style="margin-bottom:10px;padding:8px 12px">
          <div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap">
            <span style="font-size:.72rem;color:var(--text-sec);font-weight:600">Selection Coverage</span>
            <span style="font-size:.68rem;color:var(--text-muted)">{{ d.selectedTests.value.size }} test{{ d.selectedTests.value.size === 1 ? '' : 's' }}<span v-if="d.selectedMethods.value.size"> · {{ d.selectedMethods.value.size }} method{{ d.selectedMethods.value.size === 1 ? '' : 's' }}</span></span>
            <div class="analytics__progress-bar" style="flex:1;min-width:100px">
              <div class="analytics__progress-fill" :style="{ width: d.selectionCoverage.value.percent + '%', background: 'var(--accent)' }"></div>
            </div>
            <span style="font-size:.72rem;font-weight:700;color:var(--accent-light)">{{ d.selectionCoverage.value.percent }}%</span>
            <span style="font-size:.68rem;color:var(--text-sec)">({{ d.selectionCoverage.value.covered }}/{{ d.selectionCoverage.value.total }} classes)</span>
            <span v-if="selectionExclusiveLoss && selectionExclusiveLoss > 0"
                  style="font-size:.68rem;color:var(--red);font-weight:600"
                  :title="'These classes have no other tests covering them. Removing the selection would leave them with zero coverage.'">
              · removing this selection would orphan {{ selectionExclusiveLoss }} class{{ selectionExclusiveLoss === 1 ? '' : 'es' }}
            </span>
          </div>
        </div>

        <!-- Treemap -->
        <div style="display:flex;align-items:center;gap:8px;margin-bottom:6px;flex-wrap:wrap">
          <div class="analytics__search-wrap">
            <input
              :value="d.covSearchQ.value"
              @input="d.covSearchQ.value = ($event.target as HTMLInputElement).value"
              placeholder="Search classes…"
              class="analytics__search"
            />
            <button v-if="d.covSearchQ.value" class="analytics__search-clear" @click="d.covSearchQ.value = ''" title="Clear search">×</button>
          </div>
          <button
            v-if="covUncoveredCount > 0"
            class="analytics__cov-filter-btn"
            :class="{ 'analytics__cov-filter-btn--active': d.covSearchQ.value === '__uncovered__' }"
            @click="d.covSearchQ.value = d.covSearchQ.value === '__uncovered__' ? '' : '__uncovered__'"
            :title="covUncoveredCount + ' classes with zero test coverage'"
          >⚠ {{ covUncoveredCount }} uncovered</button>
          <span v-if="d.covSearchQ.value && d.covSearchQ.value !== '__uncovered__'" style="font-size:.65rem;color:var(--text-muted)">{{ d.filteredCovClasses.value.length }} matches</span>
          <!-- Color legend -->
          <div style="display:flex;align-items:center;gap:5px;margin-left:auto;flex-wrap:wrap">
            <template v-if="d.hasMethodCoverage.value">
              <span style="font-size:.58rem;color:var(--text-muted)">0% methods</span>
              <div class="analytics__treemap-legend"></div>
              <span style="font-size:.58rem;color:var(--text-muted)">100% covered</span>
              <span style="font-size:.55rem;color:var(--text-muted);margin-left:4px">· brightness = test count</span>
            </template>
            <template v-else>
              <span style="font-size:.58rem;color:var(--text-muted)">0 tests</span>
              <div class="analytics__treemap-legend analytics__treemap-legend--count"></div>
              <span style="font-size:.58rem;color:var(--text-muted)">many tests</span>
            </template>
          </div>
        </div>
        <div id="cov-treemap" style="background:var(--bg-card);border-radius:var(--radius);overflow:hidden;height:420px;position:relative"></div>

        <!-- Top Impact Classes -->
        <div v-if="topImpactClasses.length" id="analytics-analysis" style="margin-top:10px;margin-bottom:10px">
          <div style="font-size:.72rem;font-weight:700;color:var(--text-sec);margin-bottom:6px" title="Source classes covered by the most tests. Changing these classes could trigger the most re-runs.">
            🎯 Top Impact Source Classes
            <span style="font-size:.6rem;font-weight:400;color:var(--text-muted);margin-left:4px">— highest test blast radius if changed</span>
          </div>
          <div style="display:flex;flex-direction:column;gap:3px">
            <div
              v-for="(cls, ci) in topImpactClasses"
              :key="cls.name"
              class="top-impact__row"
              @click="d.navigateToCovClass(cls.name)"
              :title="cls.name + '\nCovered by ' + cls.testCount + ' tests — click to view in Coverage'"
            >
              <span class="top-impact__rank">{{ ci + 1 }}</span>
              <span class="top-impact__bar-wrap">
                <span class="top-impact__bar" :style="{ width: Math.round((cls.testCount / topImpactClasses[0].testCount) * 100) + '%', background: cls.testCount >= topImpactClasses[0].testCount * 0.7 ? '#ef4444' : cls.testCount >= topImpactClasses[0].testCount * 0.4 ? '#fb923c' : '#6366f1' }"></span>
              </span>
              <span class="top-impact__name" :title="cls.name">{{ dn(cls.name) }}</span>
              <span class="top-impact__count">{{ cls.testCount }} tests</span>
            </div>
          </div>
        </div>

        <!-- Coverage Efficiency -->
        <div v-if="covEfficiency" style="margin-top:10px;margin-bottom:10px">
          <div style="font-size:.72rem;font-weight:700;color:var(--text-sec);margin-bottom:6px" title="Coverage distribution: over-tested classes have many tests covering them (redundant?), under-tested have few. Uncovered have no tests at all.">
            ⚖ Coverage Efficiency
            <span style="font-size:.6rem;font-weight:400;color:var(--text-muted);margin-left:4px">— median {{ covEfficiency.medianTests }} tests/class · {{ covEfficiency.uncovered.length }} uncovered</span>
          </div>
          <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px">
            <div v-if="covEfficiency.overTested.length" class="cov-eff__col cov-eff__col--over">
              <div class="cov-eff__col-title">Over-tested <span class="cov-eff__col-hint">&gt;3× median</span></div>
              <div
                v-for="cls in covEfficiency.overTested"
                :key="cls.name"
                class="cov-eff__row"
                @click="d.navigateToCovClass(cls.name)"
                :title="cls.name + '\n' + cls.testCount + ' tests cover this class — click to inspect'"
              >
                <span class="cov-eff__name" :title="cls.name">{{ dn(cls.name) }}</span>
                <span class="cov-eff__count cov-eff__count--over">{{ cls.testCount }}</span>
              </div>
            </div>
            <div v-if="covEfficiency.underTested.length" class="cov-eff__col cov-eff__col--under">
              <div class="cov-eff__col-title">Under-tested <span class="cov-eff__col-hint">&lt;0.3× median</span></div>
              <div
                v-for="cls in covEfficiency.underTested"
                :key="cls.name"
                class="cov-eff__row"
                @click="d.navigateToCovClass(cls.name)"
                :title="cls.name + '\nOnly ' + cls.testCount + ' test(s) cover this class — consider adding more'"
              >
                <span class="cov-eff__name" :title="cls.name">{{ dn(cls.name) }}</span>
                <span class="cov-eff__count cov-eff__count--under">{{ cls.testCount }}</span>
              </div>
            </div>
          </div>
          <div v-if="covEfficiency.uncovered.length" style="margin-top:6px;font-size:.62rem;color:var(--text-muted)">
            <span style="color:var(--red);font-weight:600">{{ covEfficiency.uncovered.length }}</span> source class{{ covEfficiency.uncovered.length === 1 ? '' : 'es' }} have no test coverage
            <span style="font-size:.58rem">({{ Math.round(covEfficiency.uncovered.length / covEfficiency.total * 100) }}% of all tracked classes)</span>
          </div>
        </div>

        <!-- Single-Point-of-Failure -->
        <div v-if="singleTestClasses.length || exclusiveCoverageByTest.length" style="margin-top:10px;margin-bottom:10px">
          <div style="font-size:.72rem;font-weight:700;color:var(--text-sec);margin-bottom:6px" title="Classes covered by exactly one test (orphan risk) and tests that exclusively cover the most classes (high-leverage tests).">
            ⚠ Single-Point-of-Failure
            <span style="font-size:.6rem;font-weight:400;color:var(--text-muted);margin-left:4px">— {{ singleTestClasses.length }} class{{ singleTestClasses.length === 1 ? '' : 'es' }} with one covering test</span>
          </div>
          <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px">
            <div v-if="singleTestClasses.length" class="cov-eff__col cov-eff__col--under">
              <div class="cov-eff__col-title">Singly-covered classes <span class="cov-eff__col-hint">orphan if their one test is dropped</span></div>
              <div
                v-for="cls in singleTestClasses.slice(0, 10)"
                :key="cls.name"
                class="cov-eff__row"
                @click="d.navigateToCovClass(cls.name)"
                @mouseenter="classHover.show(cls.name, $event)" @mousemove="classHover.move($event)" @mouseleave="classHover.hide()"
                :title="cls.name + '\nOnly ' + cls.tests[0] + ' covers this class'"
              >
                <span class="cov-eff__name">{{ dn(cls.name) }}</span>
                <span class="cov-eff__count cov-eff__count--under">1</span>
              </div>
              <div v-if="singleTestClasses.length > 10" style="font-size:.58rem;color:var(--text-muted);margin-top:3px;padding:0 4px">+{{ singleTestClasses.length - 10 }} more…</div>
            </div>
            <div v-if="exclusiveCoverageByTest.length" class="cov-eff__col cov-eff__col--over">
              <div class="cov-eff__col-title">High-leverage tests <span class="cov-eff__col-hint">exclusive coverage count</span></div>
              <div
                v-for="t in exclusiveCoverageByTest"
                :key="t.test"
                class="cov-eff__row"
                @click="d.navigateToTestFromCov(t.test)"
                @mouseenter="testHover.show(t.test, $event)" @mousemove="testHover.move($event)" @mouseleave="testHover.hide()"
                :title="t.test + '\nExclusively covers ' + t.count + ' class(es): ' + t.classes.slice(0, 5).join(', ') + (t.classes.length > 5 ? '…' : '')"
              >
                <span class="cov-eff__name">{{ dn(t.test) }}</span>
                <span class="cov-eff__count cov-eff__count--over">{{ t.count }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- Dep fan-out distribution -->
        <div v-if="fanOutDistribution" style="margin-top:10px;margin-bottom:10px">
          <div style="font-size:.72rem;font-weight:700;color:var(--text-sec);margin-bottom:6px" title="Distribution of how many production classes each test depends on. Tests far above the mean (god-tests / integration tests) often signal poor isolation.">
            📊 Dep Fan-out Distribution
            <span style="font-size:.6rem;font-weight:400;color:var(--text-muted);margin-left:4px">— mean {{ fanOutDistribution.mean }} ± {{ fanOutDistribution.sd }} · max {{ fanOutDistribution.max }}</span>
          </div>
          <div id="fanout-hist" style="width:100%;min-height:120px"></div>
          <div v-if="fanOutDistribution.outliers.length" style="margin-top:6px">
            <div class="card-label" style="font-size:.6rem;margin-bottom:3px">God-test outliers <span style="color:var(--text-muted);font-weight:400">(&gt; mean + 2σ)</span></div>
            <div style="display:flex;flex-wrap:wrap;gap:4px">
              <span
                v-for="o in fanOutDistribution.outliers"
                :key="o.test.name"
                class="analytics__class-test-tag analytics__class-test-tag--clickable"
                @click="d.navigateToTestFromCov(o.test.name)"
                @mouseenter="testHover.show(o.test.name, $event)" @mousemove="testHover.move($event)" @mouseleave="testHover.hide()"
                :title="o.test.name + '\n' + o.count + ' deps (' + (o.count / fanOutDistribution!.mean).toFixed(1) + '× mean)'"
              >{{ dn(o.test.name) }} <span style="color:var(--red);font-weight:700">{{ o.count }}</span></span>
            </div>
          </div>
        </div>

        <!-- Rank vs Coverage scatter -->
        <div v-if="rankCoverageScatter.length" style="margin-top:10px;margin-bottom:10px">
          <div style="font-size:.72rem;font-weight:700;color:var(--text-sec);margin-bottom:6px" title="Each dot is a test. X = priority rank (lower = earlier). Y = total source classes covered. Red dots have exclusive coverage. The dashed line marks the top-decile rank — high-leverage tests should sit left of it.">
            🎯 Rank vs Coverage
            <span style="font-size:.6rem;font-weight:400;color:var(--text-muted);margin-left:4px">— validates that coverage-rich tests run early</span>
          </div>
          <div id="rank-cov-scatter" style="width:100%;min-height:300px"></div>
        </div>

        <!-- Package breakdown -->
        <div v-if="covPackageStats.length > 1" style="margin-top:10px">
          <div style="display:flex;align-items:center;gap:6px;margin-bottom:6px;flex-wrap:wrap">
            <div class="card-label">Package Breakdown
              <span style="color:var(--text-muted);font-size:.55rem"> — click to filter treemap</span>
            </div>
            <div style="margin-left:auto;display:flex;gap:4px">
              <button
                v-for="s in ([['coverage','% covered'],['tests','avg tests'],['alpha','A–Z']] as const)"
                :key="s[0]"
                class="analytics__sort-btn"
                :class="{ 'analytics__sort-btn--active': covPkgSort === s[0] }"
                @click="covPkgSort = s[0]"
              >{{ s[1] }}</button>
            </div>
          </div>
          <div style="overflow-x:auto;max-height:200px;overflow-y:auto">
            <table>
              <thead style="position:sticky;top:0;background:var(--bg-card);z-index:1">
                <tr>
                  <th style="padding:3px 8px;text-align:left;font-size:.65rem;color:var(--text-sec)">Package</th>
                  <th style="padding:3px 8px;text-align:right;font-size:.65rem;color:var(--text-sec)" title="Source classes tracked in this package">Classes</th>
                  <th style="padding:3px 8px;text-align:right;font-size:.65rem;color:var(--text-sec)" title="Classes with at least one test">Covered</th>
                  <th style="padding:3px 8px;text-align:right;font-size:.65rem;color:var(--text-sec)" title="Highest test count for a single class in this package">Max tests</th>
                  <th style="padding:3px 8px;text-align:right;font-size:.65rem;color:var(--text-sec)" title="Average tests per class">Avg tests</th>
                  <th style="padding:3px 8px;text-align:left;font-size:.65rem;color:var(--text-sec)">Coverage</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="p in covPkgStatsSorted" :key="p.name"
                  style="cursor:pointer"
                  @click="d.covSearchQ.value = p.name.split('.').slice(-2).join('.')"
                  :title="p.name + ' — click to filter classes'"
                >
                  <td style="padding:2px 8px;font-size:.68rem;max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" :title="p.name">{{ sn(p.name) }}</td>
                  <td style="padding:2px 8px;text-align:right;font-size:.68rem;color:var(--text-sec)">{{ p.total }}</td>
                  <td style="padding:2px 8px;text-align:right;font-size:.68rem" :style="{ color: p.covered === p.total ? 'var(--green)' : p.covered > p.total * 0.5 ? 'var(--yellow)' : 'var(--red)' }">{{ p.covered }}</td>
                  <td style="padding:2px 8px;text-align:right;font-size:.68rem;color:var(--accent-light)">{{ p.maxTests }}</td>
                  <td style="padding:2px 8px;text-align:right;font-size:.68rem;color:var(--text-sec)">{{ p.avgTests }}</td>
                  <td style="padding:2px 8px">
                    <div style="height:4px;width:70px;background:var(--border);border-radius:2px;overflow:hidden">
                      <div :style="{ width: Math.round(p.covered / p.total * 100) + '%', height:'100%', borderRadius:'2px', background: p.covered === p.total ? 'var(--green)' : p.covered > p.total * 0.5 ? 'var(--yellow)' : 'var(--red)' }"></div>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- Redundancy clusters (suite-wide) -->
        <div v-if="redundancyClusters.length" style="margin-top:10px;margin-bottom:10px">
          <div style="font-size:.72rem;font-weight:700;color:var(--text-sec);margin-bottom:6px" title="Tests grouped by ≥80% Jaccard overlap of their covered source classes. Members of a group are candidates for merging or deletion.">
            ♻ Redundancy Clusters
            <span style="font-size:.6rem;font-weight:400;color:var(--text-muted);margin-left:4px">— {{ redundancyClusters.length }} group{{ redundancyClusters.length === 1 ? '' : 's' }} of near-duplicate tests (Jaccard ≥ 0.8)</span>
          </div>
          <div style="display:flex;flex-direction:column;gap:6px">
            <div v-for="(g, i) in redundancyClusters" :key="i" class="cov-eff__col" style="padding:6px 8px;cursor:pointer" @click="toggleRedundancy(i)">
              <div style="display:flex;align-items:center;gap:6px;font-size:.65rem;color:var(--text-sec)">
                <span style="font-weight:700;color:var(--accent-light)">{{ g.tests.length }} tests</span>
                <span>·</span>
                <span>{{ g.shared.length }} shared class{{ g.shared.length === 1 ? '' : 'es' }}</span>
                <span style="margin-left:auto;font-size:.58rem;color:var(--text-muted)">{{ redundancyExpanded.has(i) ? '▾ collapse' : '▸ expand' }}</span>
              </div>
              <div v-if="redundancyExpanded.has(i)" style="margin-top:6px;display:flex;flex-direction:column;gap:3px" @click.stop>
                <div style="display:flex;flex-wrap:wrap;gap:4px">
                  <span
                    v-for="tn in g.tests" :key="tn"
                    class="analytics__class-test-tag analytics__class-test-tag--clickable"
                    @click="d.navigateToTestFromCov(tn)"
                    @mouseenter="testHover.show(tn, $event)" @mousemove="testHover.move($event)" @mouseleave="testHover.hide()"
                  >{{ dn(tn) }}</span>
                </div>
                <div v-if="g.shared.length" style="font-size:.58rem;color:var(--text-muted);margin-top:3px">
                  Shared: <span v-for="(sc, si) in g.shared.slice(0, 8)" :key="sc" style="color:var(--text-sec)">{{ dn(sc) }}<span v-if="si < Math.min(g.shared.length, 8) - 1">, </span></span><span v-if="g.shared.length > 8"> …+{{ g.shared.length - 8 }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- Cross-package coupling matrix -->
        <div v-if="pkgCouplingMatrix" style="margin-top:10px;margin-bottom:10px">
          <div style="font-size:.72rem;font-weight:700;color:var(--text-sec);margin-bottom:6px" title="Heatmap of dep edges from tests in package A (rows) to production classes in package B (columns). Concentrated cells reveal cross-package coupling and architectural seams.">
            🔗 Cross-Package Coupling
            <span style="font-size:.6rem;font-weight:400;color:var(--text-muted);margin-left:4px">— {{ pkgCouplingMatrix.order.length }} packages · log-scaled edge counts</span>
          </div>
          <div id="pkg-coupling-matrix" style="width:100%;overflow:auto"></div>
        </div>

        <!-- Class detail panel -->
        <div v-if="d.covSelectedClass.value" class="detail-panel" style="margin-top:10px">
          <div style="display:flex;align-items:center;gap:8px;margin-bottom:6px">
            <span style="font-size:.82rem;font-weight:600;color:var(--text)"
              :title="d.covSelectedClass.value!.name"
              @mouseenter="classHover.show(d.covSelectedClass.value!.name, $event)" @mousemove="classHover.move($event)" @mouseleave="classHover.hide()"
            >{{ d.covSelectedClass.value.name.split('.').pop() }}</span>
            <span style="font-size:.72rem;color:var(--text-sec)">tested by {{ d.covSelectedClass.value.testCount }} test{{ d.covSelectedClass.value.testCount === 1 ? '' : 's' }}</span>
            <button @click="d.covSelectedClass.value = null" style="margin-left:auto;padding:2px 8px;font-size:.65rem;background:var(--border);color:var(--text-sec);border:1px solid var(--text-muted);border-radius:3px;cursor:pointer">✕</button>
          </div>
          <!-- Method coverage bar in detail panel -->
          <div v-if="d.covSelectedClass.value.totalMembers > 0" style="margin-bottom:8px">
            <span style="font-size:.65rem;color:var(--text-sec)">
              {{ d.covSelectedClass.value.coveredMembers }} / {{ d.covSelectedClass.value.totalMembers }} methods covered
              ({{ Math.round(d.covSelectedClass.value.coveredMembers/d.covSelectedClass.value.totalMembers*100) }}%)
            </span>
            <div class="analytics__progress-bar" style="margin-top:3px">
              <div class="analytics__progress-fill"
                :class="(d.covSelectedClass.value.coveredMembers/d.covSelectedClass.value.totalMembers)>=0.8?'analytics__progress-fill--green':(d.covSelectedClass.value.coveredMembers/d.covSelectedClass.value.totalMembers)>=0.5?'analytics__progress-fill--yellow':'analytics__progress-fill--red'"
                :style="{ width: Math.round(d.covSelectedClass.value.coveredMembers/d.covSelectedClass.value.totalMembers*100)+'%' }">
              </div>
            </div>
          </div>
          <div style="display:flex;flex-wrap:wrap;gap:4px;margin-bottom:8px">
            <span
              v-for="tn in d.covSelectedClass.value.tests" :key="tn"
              class="analytics__class-test-tag analytics__class-test-tag--clickable"
              @click="d.navigateToTestFromCov(tn)"
              @mouseenter="testHover.show(tn, $event)" @mousemove="testHover.move($event)" @mouseleave="testHover.hide()"
              :title="'Go to ' + tn + ' in Tests tab'"
            >{{ dn(tn) }} →</span>
          </div>
          <div v-if="d.covSelectedClass.value.members?.length">
            <div class="card-label" style="margin-bottom:4px">
              Members ({{ d.covSelectedClass.value.members.length }})
              <span style="color:var(--text-muted);font-weight:400">·
                <span style="color:var(--green)">{{ d.covSelectedClass.value.members.filter(mb => mb.testCount > 0).length }}</span>
                covered ·
                <span style="color:var(--red)">{{ d.covSelectedClass.value.members.filter(mb => mb.testCount === 0).length }}</span>
                untouched
              </span>
            </div>
            <div :title="d.covSelectedClass.value.members.filter(mb => mb.testCount === 0).length + ' untouched method(s) — likely dead code or candidates for tests'" style="display:flex;gap:1px;height:10px;margin:6px 0">
              <div
                v-for="mb in d.covSelectedClass.value.members" :key="mb.name"
                :title="mb.name + ' · ' + mb.testCount + ' test(s)'"
                :style="{ flex: 1, minWidth: '2px', background: mb.testCount > 0 ? 'var(--green)' : 'rgba(239,68,68,.45)', borderRadius: '1px' }"
              ></div>
            </div>
            <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:4px">
              <div v-for="mb in d.covSelectedClass.value.members" :key="mb.name" class="analytics__member-card">
                <span class="analytics__member-name" :title="mb.name">{{ mb.name }}</span>
                <span class="analytics__member-tests">{{ mb.testCount }} test{{ mb.testCount === 1 ? '' : 's' }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
  <ClassInfoCard v-if="classHover.visible.value" :info="classHover.info.value" :x="classHover.x.value" :y="classHover.y.value" />
</template>

<style scoped>
.analytics__canvas { position: relative; }
.analytics__canvas--sm { height: 72px; }
.analytics__canvas--dist { height: 140px; }
.analytics__cov-kpi { padding: 6px 10px; }
.analytics__cov-kpi-label { color: var(--text-sec); font-size: .6rem; font-weight: 600; text-transform: uppercase; letter-spacing: .4px; }
.analytics__cov-kpi-value { font-size: 1rem; font-weight: 700; }
.analytics__cov-kpi-value--accent { color: var(--accent-light); }
.analytics__progress-bar { flex: 1; height: 8px; background: var(--bg-base); border-radius: 4px; overflow: hidden; }
.analytics__progress-fill { height: 100%; border-radius: 4px; transition: width .3s; }
.analytics__progress-fill--green { background: var(--green); }
.analytics__progress-fill--yellow { background: var(--yellow); }
.analytics__progress-fill--red { background: var(--red); }
.analytics__pct { font-size: .85rem; font-weight: 700; }
.analytics__pct--green { color: var(--green); }
.analytics__pct--yellow { color: var(--yellow); }
.analytics__pct--red { color: var(--red); }
.analytics__class-test-tag { font-size: .68rem; padding: 2px 6px; background: rgba(99, 102, 241, .15); color: var(--accent-light); border-radius: 3px; }
.analytics__class-test-tag--clickable { cursor: pointer; transition: background var(--tr-fast); }
.analytics__class-test-tag--clickable:hover { background: rgba(99, 102, 241, .35); }
.analytics__diff--fail { background: rgba(127, 29, 29, .45); color: var(--red); }
.analytics__diff--recover { background: rgba(20, 83, 45, .45); color: var(--green); }
.analytics__diff--new { background: rgba(8, 51, 68, .45); color: var(--cyan); }
.analytics__diff--removed { background: rgba(71, 85, 105, .45); color: var(--text-muted); }
.analytics__toggle-btn {
  padding: 1px 6px; font-size: .55rem; border-radius: 10px;
  border: 1px solid var(--border); background: none; cursor: pointer;
  color: var(--text-muted); transition: all var(--tr-fast);
}
.analytics__toggle-btn:hover { color: var(--accent-light); border-color: var(--accent); }
.analytics__toggle-btn--active { color: var(--accent-light); border-color: var(--accent); background: var(--accent-bg); }
.analytics__diff--improved { background: rgba(20, 83, 45, .25); color: var(--green); }
.analytics__diff--regressed { background: rgba(124, 45, 18, .25); color: var(--orange); }
.analytics__member-card { background: var(--bg-base); border: 1px solid var(--border); border-radius: 4px; padding: 4px 8px; font-size: .68rem; display: flex; justify-content: space-between; align-items: center; }
.analytics__member-name { color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.analytics__member-tests { color: var(--green); font-weight: 600; flex-shrink: 0; margin-left: 8px; }

.analytics__search-wrap { position: relative; display: flex; align-items: center; }
.analytics__search {
  background: var(--bg-base); color: var(--text); font-size: .72rem; padding: 3px 24px 3px 8px;
  border: 1px solid var(--border); border-radius: 4px; outline: none; width: 180px;
  transition: border-color var(--tr-fast);
}
.analytics__search:focus { border-color: var(--accent); }
.analytics__search-clear {
  position: absolute; right: 2px; top: 50%; transform: translateY(-50%);
  background: none; border: none; color: var(--text-muted); cursor: pointer;
  font-size: .85rem; line-height: 1; padding: 2px 5px; border-radius: 3px;
  transition: color var(--tr-fast);
}
.analytics__search-clear:hover { color: var(--text); }

/* Stats KPIs */
.analytics__stat-kpi { padding: 5px 10px; min-width: 80px; }
.analytics__stat-label { color: var(--text-sec); font-size: .58rem; margin-bottom: 2px; font-weight: 600; text-transform: uppercase; letter-spacing: .3px; }
.analytics__stat-value { font-size: .95rem; font-weight: 700; }

/* Time range buttons */
.analytics__range-btn {
  padding: 2px 8px; font-size: .65rem; border-radius: 3px;
  border: 1px solid var(--border); background: none; cursor: pointer;
  color: var(--text-sec); transition: all var(--tr-fast);
}
.analytics__range-btn:hover { color: var(--text-sec); border-color: var(--text-sec); }
.analytics__range-btn--active { color: var(--accent-light); border-color: var(--accent); background: var(--accent-bg); }

/* Run history browser */
.run-health-bar {
  display: flex; align-items: flex-end; gap: 2px;
  height: 24px; margin-bottom: 6px; padding: 2px 0;
  overflow-x: auto; overflow-y: hidden;
}
.run-health-bar__seg {
  flex: 1; min-width: 4px; max-width: 20px; border-radius: 2px 2px 0 0;
  cursor: pointer; transition: opacity var(--tr-fast), transform var(--tr-fast);
  opacity: .65;
}
.run-health-bar__seg:hover { opacity: 1; transform: scaleY(1.08); transform-origin: bottom; }
.run-health-bar__seg--fail { background: var(--red); }
.run-health-bar__seg--pass { background: var(--green); }
.run-health-bar__seg--selected { opacity: 1; outline: 2px solid var(--accent); outline-offset: 1px; }
.analytics__run-chip {
  display: inline-flex; align-items: center; gap: 3px;
  padding: 3px 6px 2px; font-size: .68rem; border-radius: 4px;
  border: 1px solid var(--border); background: var(--bg-card);
  cursor: pointer; transition: all var(--tr-fast);
  flex-direction: column; min-width: 44px;
}
.analytics__run-chip-row { display: flex; align-items: center; gap: 3px; width: 100%; justify-content: center; }
.analytics__run-chip-apfd {
  height: 3px; border-radius: 2px; opacity: .75; width: 100%; align-self: stretch;
}
.analytics__run-chip:hover { border-color: rgba(99,102,241,.4); background: rgba(99,102,241,.08); }
.analytics__run-chip--fail { border-color: rgba(239,68,68,.3); background: rgba(127,29,29,.2); }
.analytics__run-chip--fail:hover { border-color: rgba(239,68,68,.6); background: rgba(127,29,29,.35); }
.analytics__run-chip--pass { border-color: rgba(34,197,94,.2); }
.analytics__run-chip--selected { border-color: var(--accent) !important; background: var(--accent-bg) !important; box-shadow: 0 0 0 2px rgba(99,102,241,.2); }
.analytics__run-chip--compare { border-color: rgba(34,211,238,.5) !important; background: rgba(34,211,238,.08) !important; box-shadow: 0 0 0 2px rgba(34,211,238,.2); }

/* Two-run comparison panel */
.two-run-cmp { padding: 10px 12px; }
.two-run-cmp__header { display: flex; align-items: flex-start; gap: 10px; flex-wrap: wrap; }
.two-run-cmp__title { font-size: .78rem; font-weight: 700; color: var(--text); flex-shrink: 0; margin-top: 3px; }
.two-run-cmp__kpis { display: flex; gap: 6px; flex-wrap: wrap; flex: 1; }
.two-run-cmp__kpi { padding: 4px 8px; }
.two-run-cmp__kpi-label { font-size: .56rem; color: var(--text-sec); text-transform: uppercase; letter-spacing: .3px; }
.two-run-cmp__kpi-val { font-size: .82rem; font-weight: 700; color: var(--text); }
.two-run-cmp__close { background: none; border: 1px solid var(--border); border-radius: 3px; padding: 2px 7px; font-size: .65rem; color: var(--text-sec); cursor: pointer; flex-shrink: 0; }
.two-run-cmp__close:hover { color: var(--text); border-color: var(--text-muted); }
.two-run-cmp__th { padding: 3px 6px; font-size: .62rem; color: var(--text-sec); white-space: nowrap; border-bottom: 1px solid var(--border); }
.two-run-cmp__th--name { text-align: left; }
.two-run-cmp__th--r { text-align: right; }
.two-run-cmp__row { cursor: pointer; transition: background var(--tr-fast); }
.two-run-cmp__row:hover { background: rgba(99,102,241,.06); }
.two-run-cmp__row--new-fail { background: rgba(127,29,29,.15); }
.two-run-cmp__row--new-fail:hover { background: rgba(127,29,29,.25); }
.two-run-cmp__row--still-fail { background: rgba(127,29,29,.08); }
.two-run-cmp__row--recovered { background: rgba(20,83,45,.12); }
.two-run-cmp__row--absent { opacity: .55; }
.two-run-cmp__td { padding: 2px 6px; font-size: .65rem; color: var(--text-sec); white-space: nowrap; border-bottom: 1px solid rgba(51,65,85,.3); }
.two-run-cmp__td--name { color: var(--text); max-width: 200px; overflow: hidden; text-overflow: ellipsis; }
.two-run-cmp__td--r { text-align: right; }
.two-run-cmp__status-badge { font-size: .58rem; font-weight: 700; padding: 1px 5px; border-radius: 3px; white-space: nowrap; }
.two-run-cmp__status-badge--new-fail { background: rgba(239,68,68,.2); color: #f87171; }
.two-run-cmp__status-badge--recovered { background: rgba(74,222,128,.15); color: #4ade80; }
.two-run-cmp__status-badge--still-fail { background: rgba(239,68,68,.12); color: #f87171; }
.two-run-cmp__status-badge--new { background: rgba(34,211,238,.12); color: #22d3ee; }
.two-run-cmp__status-badge--absent { color: var(--text-muted); }
.two-run-cmp__status-badge--ok { }
.analytics__run-row--fail { background: rgba(127,29,29,.15) !important; }
.analytics__run-row--fail:hover { background: rgba(127,29,29,.25) !important; }
.analytics__page-btn {
  background: none; border: 1px solid var(--border); border-radius: 3px;
  color: var(--text-sec); cursor: pointer; padding: 1px 5px; font-size: .68rem;
  line-height: 1.3; transition: all var(--tr-fast);
}
.analytics__page-btn:hover:not(:disabled) { color: var(--text); border-color: var(--accent); }
.analytics__page-btn:disabled { opacity: .3; cursor: default; }
.analytics__page-sz-btn {
  background: none; border: 1px solid var(--border); border-radius: 3px;
  color: var(--text-sec); cursor: pointer; padding: 1px 5px; font-size: .6rem;
  line-height: 1.3; transition: all var(--tr-fast);
}
.analytics__page-sz-btn:hover { color: var(--text); border-color: var(--accent); }
.analytics__page-sz-btn--active { color: var(--accent-light); border-color: var(--accent); background: var(--accent-bg); }

/* Run composition summary bar */
.run-comp-bar {
  display: flex; align-items: center; gap: 5px; flex-wrap: wrap;
  margin-bottom: 8px; padding: 4px 0;
}
.run-comp-bar__label { font-size: .6rem; color: var(--text-muted); flex-shrink: 0; }
.run-comp-bar__chip {
  font-size: .58rem; padding: 1px 6px; border-radius: 10px;
  border: 1px solid transparent; white-space: nowrap;
}
.run-comp-bar__chip--fail { color: var(--red); background: rgba(239,68,68,.12); border-color: rgba(239,68,68,.25); }
.run-comp-bar__chip--failhist { color: var(--orange); background: rgba(249,115,22,.1); border-color: rgba(249,115,22,.2); }
.run-comp-bar__chip--dep { color: var(--cyan); background: rgba(6,182,212,.1); border-color: rgba(6,182,212,.2); }
.run-comp-bar__chip--chg { color: var(--yellow); background: rgba(234,179,8,.1); border-color: rgba(234,179,8,.2); }
.run-comp-bar__chip--static { color: var(--purple); background: rgba(168,85,247,.1); border-color: rgba(168,85,247,.2); }
.run-comp-bar__total { font-size: .58rem; color: var(--text-muted); margin-left: auto; }
.analytics__nav-btn {
  background: none; border: 1px solid var(--border); border-radius: 3px;
  color: var(--text-sec); cursor: pointer; padding: 1px 7px; font-size: .82rem;
  line-height: 1.4; transition: all var(--tr-fast);
}
.analytics__nav-btn:hover:not(:disabled) { color: var(--text); border-color: var(--accent); }
.analytics__nav-btn:disabled { opacity: .3; cursor: default; }

/* Run diff panel */
.run-diff {
  margin-bottom: 8px; border: 1px solid var(--border); border-radius: 5px; overflow: hidden;
}
.run-diff__title {
  font-size: .6rem; color: var(--text-muted); padding: 3px 8px;
  background: rgba(15,23,42,.5); border-bottom: 1px solid var(--border);
  font-weight: 600; text-transform: uppercase; letter-spacing: .3px;
}
.run-diff__group { border-bottom: 1px solid rgba(51,65,85,.4); }
.run-diff__group:last-child { border-bottom: none; }
.run-diff__group-hdr {
  display: flex; align-items: center; justify-content: space-between;
  padding: 4px 8px; font-size: .65rem; font-weight: 600; cursor: pointer;
  user-select: none; transition: background var(--tr-fast);
}
.run-diff__group-hdr:hover { background: rgba(99,102,241,.06); }
.run-diff__group-hdr--fail { color: var(--red); background: rgba(127,29,29,.1); }
.run-diff__group-hdr--fail:hover { background: rgba(127,29,29,.2); }
.run-diff__group-hdr--pass { color: var(--green); background: rgba(20,83,45,.1); }
.run-diff__group-hdr--pass:hover { background: rgba(20,83,45,.2); }
.run-diff__group-hdr--new { color: var(--accent-light); background: rgba(99,102,241,.07); }
.run-diff__group-hdr--rank { color: var(--text-sec); }
.run-diff__toggle { font-size: .55rem; opacity: .6; }
.run-diff__items {
  display: flex; flex-wrap: wrap; gap: 4px; padding: 5px 8px;
  background: rgba(15,23,42,.3);
}
.run-diff__item {
  font-size: .62rem; padding: 1px 7px; border-radius: 10px;
  cursor: pointer; white-space: nowrap; transition: opacity var(--tr-fast);
}
.run-diff__item:hover { opacity: .8; }
.run-diff__item--fail { background: rgba(127,29,29,.3); color: var(--red); border: 1px solid rgba(239,68,68,.3); }
.run-diff__item--pass { background: rgba(20,83,45,.3); color: var(--green); border: 1px solid rgba(34,197,94,.3); }
.run-diff__item--new { background: rgba(99,102,241,.15); color: var(--accent-light); border: 1px solid rgba(99,102,241,.3); }
.run-diff__items--rank { flex-direction: column; gap: 2px; }
.run-diff__rank-row {
  display: flex; align-items: center; gap: 6px; cursor: pointer;
  padding: 2px 4px; border-radius: 3px; transition: background var(--tr-fast);
}
.run-diff__rank-row:hover { background: rgba(99,102,241,.08); }
.run-diff__rank-delta { font-size: .65rem; font-weight: 700; width: 28px; flex-shrink: 0; text-align: right; }
.run-diff__rank-name { font-size: .65rem; color: var(--text-sec); flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.run-diff__rank-pos { font-size: .58rem; color: var(--text-muted); flex-shrink: 0; white-space: nowrap; }

.analytics__health-arc {
  display: flex; align-items: center; gap: 8px; padding: 7px 12px;
  background: rgba(15,23,42,.6); border: 1px solid rgba(99,102,241,.18);
  border-radius: 6px; margin-bottom: 8px;
}
.analytics__health-arc-icon { font-size: 1rem; flex-shrink: 0; }
.analytics__health-arc-text { font-size: .7rem; color: var(--text-sec); flex: 1; }
.analytics__health-arc-badge {
  flex-shrink: 0; font-size: .58rem; font-weight: 700; letter-spacing: .04em;
  text-transform: uppercase; padding: 2px 7px; border-radius: 10px; border: 1px solid;
}

.analytics__insight {
  display: flex; align-items: flex-start; gap: 8px;
  padding: 5px 10px; border-left: 3px solid var(--border);
  background: rgba(15,23,42,.5); border-radius: 0 4px 4px 0;
}
.analytics__insight-action {
  font-size: .62rem; color: var(--accent-light); cursor: pointer; white-space: nowrap;
  border-bottom: 1px dotted var(--accent); padding-bottom: 1px; flex-shrink: 0;
}
.analytics__insight-action:hover { color: var(--text); }

.apfd-rec {
  display: flex; align-items: flex-start; gap: 8px;
  padding: 7px 10px; border-left: 3px solid var(--border);
  background: rgba(15,23,42,.5); border-radius: 0 4px 4px 0;
  transition: border-color var(--tr-fast);
}
.apfd-rec--high { border-left-color: var(--red); }
.apfd-rec--medium { border-left-color: var(--yellow); }
.apfd-rec--low { border-left-color: var(--green); }
.apfd-rec__icon { font-size: .75rem; flex-shrink: 0; margin-top: 1px; line-height: 1; }
.apfd-rec__body { flex: 1; min-width: 0; }
.apfd-rec__title { font-size: .7rem; color: var(--text); font-weight: 600; margin-bottom: 2px; }
.apfd-rec__detail { font-size: .64rem; color: var(--text-sec); line-height: 1.4; }
.apfd-rec__tests { display: flex; gap: 4px; flex-wrap: wrap; margin-top: 4px; }
.apfd-rec__test-chip {
  font-size: .58rem; padding: 1px 5px; background: rgba(99,102,241,.12);
  border: 1px solid rgba(99,102,241,.25); border-radius: 3px;
  color: var(--accent-light); cursor: pointer; white-space: nowrap;
}
.apfd-rec__test-chip:hover { background: rgba(99,102,241,.25); }
.apfd-rec__action {
  flex-shrink: 0; font-size: .62rem; color: var(--accent-light); cursor: pointer;
  border: none; background: none; padding: 0; border-bottom: 1px dotted var(--accent);
  white-space: nowrap; margin-top: 2px;
}
.apfd-rec__action:hover { color: var(--text); }

/* Test longevity cohorts */
.longevity-cohort {
  flex: 1; min-width: 140px; padding: 8px 10px;
  border-left: 3px solid var(--border); background: rgba(15,23,42,.4);
  border-radius: 0 4px 4px 0;
}
.longevity-cohort__header { display: flex; align-items: center; gap: 6px; margin-bottom: 3px; }
.longevity-cohort__label { font-size: .72rem; font-weight: 700; }
.longevity-cohort__desc { font-size: .58rem; color: var(--text-muted); }
.longevity-cohort__count { font-size: .62rem; color: var(--text-sec); margin-bottom: 5px; }
.longevity-cohort__fail-row { display: flex; align-items: center; gap: 6px; }
.longevity-cohort__fail-bar-bg { flex: 1; height: 5px; background: rgba(51,65,85,.5); border-radius: 3px; overflow: hidden; }
.longevity-cohort__fail-bar { height: 100%; border-radius: 3px; transition: width .3s; }
.longevity-cohort__fail-pct { font-size: .6rem; white-space: nowrap; flex-shrink: 0; }

.analytics__treemap-legend {
  width: 80px; height: 8px; border-radius: 4px;
  background: linear-gradient(to right, #ef4444, #f97316, #f59e0b, #22c55e);
  opacity: .8;
}
.analytics__treemap-legend--count {
  background: linear-gradient(to right, #1e293b, #1e3a5f, #6366f1);
}

.analytics__cov-filter-btn {
  padding: 3px 8px; font-size: .65rem; border-radius: 4px;
  border: 1px solid var(--orange); color: var(--orange);
  background: transparent; cursor: pointer; transition: all var(--tr-fast);
}
.analytics__cov-filter-btn:hover { background: rgba(249,115,22,.1); }
.analytics__cov-filter-btn--active { background: rgba(249,115,22,.15); }

.analytics__sort-btn {
  padding: 2px 7px; font-size: .6rem; border-radius: 3px;
  border: 1px solid var(--border); color: var(--text-sec);
  background: transparent; cursor: pointer; transition: all var(--tr-fast);
}
.analytics__sort-btn:hover { color: var(--text); border-color: var(--text-sec); }
.analytics__sort-btn--active { background: var(--accent-bg); border-color: var(--accent); color: var(--accent-light); }

/* Rank heatmap */
.heatmap__label {
  font-size: .62rem; color: var(--text-sec); overflow: hidden; text-overflow: ellipsis;
  white-space: nowrap; padding: 1px 4px 1px 0; cursor: pointer; line-height: 16px;
  transition: color var(--tr-fast);
}
.heatmap__label:hover { color: var(--accent-light); }
.heatmap__cell {
  height: 16px; border-radius: 1px; cursor: pointer; transition: opacity .1s;
  display: flex; align-items: center; justify-content: center;
}
.heatmap__cell:hover { opacity: 1 !important; outline: 1px solid rgba(255,255,255,.3); }

/* Co-failure pairs */
.cofail-row {
  display: flex; align-items: center; gap: 6px; padding: 3px 8px;
  background: rgba(239,68,68,.06); border: 1px solid rgba(239,68,68,.15);
  border-radius: 4px; cursor: default;
}
.cofail-count { font-size: .65rem; font-weight: 700; color: var(--red); flex-shrink: 0; min-width: 20px; }
.cofail-name {
  font-size: .68rem; color: var(--text-sec); overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  flex: 1; cursor: pointer; transition: color var(--tr-fast); min-width: 0;
}
.cofail-name:hover { color: var(--accent-light); text-decoration: underline dotted; }
.cofail-sep { font-size: .6rem; color: var(--text-muted); flex-shrink: 0; }

/* Execution Gantt */
.gantt-wrap { padding: 6px 8px; max-height: 420px; overflow-y: auto; }
.gantt-row {
  display: flex; align-items: center; gap: 5px; padding: 2px 2px;
  border-radius: 3px; cursor: pointer; transition: background var(--tr-fast); min-width: 0;
}
.gantt-row:hover { background: rgba(99,102,241,.08); }
.gantt-rank { font-size: .55rem; color: var(--text-muted); width: 22px; text-align: right; flex-shrink: 0; }
.gantt-name { font-size: .65rem; color: var(--text-sec); width: 160px; flex-shrink: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.gantt-bar { display: inline-block; height: 7px; border-radius: 2px; opacity: .8; flex-shrink: 0; min-width: 2px; }
.gantt-row:hover .gantt-bar { opacity: 1; }
.gantt-dur { font-size: .58rem; color: var(--text-muted); flex-shrink: 0; }

/* Top Impact Classes panel */
.top-impact__row {
  display: flex; align-items: center; gap: 6px; padding: 2px 4px; border-radius: 3px;
  cursor: pointer; transition: background var(--tr-fast);
}
.top-impact__row:hover { background: rgba(99,102,241,.1); }
.top-impact__rank { font-size: .58rem; color: var(--text-muted); width: 16px; text-align: right; flex-shrink: 0; }
.top-impact__bar-wrap { width: 80px; flex-shrink: 0; background: rgba(51,65,85,.5); border-radius: 2px; height: 6px; overflow: hidden; }
.top-impact__bar { display: block; height: 100%; border-radius: 2px; transition: width .3s; }
.top-impact__name { font-size: .65rem; color: var(--text-sec); flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.top-impact__count { font-size: .6rem; color: var(--text-muted); flex-shrink: 0; white-space: nowrap; }
.top-impact__row:hover .top-impact__name { color: var(--accent-light); }
.risk-card {
  background: rgba(239,68,68,.07); border: 1px solid rgba(239,68,68,.25); border-radius: 6px;
  padding: 6px 10px; cursor: pointer; min-width: 130px; max-width: 180px;
  transition: all var(--tr-fast);
}
.risk-card:hover { background: rgba(239,68,68,.14); border-color: rgba(239,68,68,.5); }
.risk-card__name { font-size: .65rem; font-weight: 600; color: var(--text-sec); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; margin-bottom: 4px; }
.risk-card__bars { display: flex; align-items: flex-end; gap: 2px; height: 14px; margin-bottom: 4px; }
.risk-card__bar { width: 6px; border-radius: 1px; flex-shrink: 0; }
.risk-card__slope { font-size: .6rem; color: #ef4444; font-weight: 700; }
.budget-card { padding: 10px 12px; }
.budget-card__controls { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
.budget-card__label { font-size: .68rem; color: var(--text-sec); white-space: nowrap; }
.budget-card__input {
  width: 70px; padding: 2px 6px; border-radius: 4px;
  border: 1px solid var(--border); background: var(--bg-base);
  color: var(--text); font-size: .72rem; text-align: right;
}
.budget-card__input:focus { outline: none; border-color: var(--accent); }
.budget-card__hint { font-size: .66rem; color: var(--text-sec); }
.budget-card__body { display: flex; flex-direction: column; gap: 6px; }
.budget-card__bar-row { display: flex; align-items: center; gap: 6px; }
.budget-card__bar-bg { flex: 1; height: 6px; background: rgba(51,65,85,.5); border-radius: 3px; overflow: hidden; }
.budget-card__bar-fill { height: 100%; background: var(--accent); border-radius: 3px; transition: width .3s ease; }
.budget-card__bar-label { font-size: .62rem; color: var(--text-sec); white-space: nowrap; }
.budget-card__tests { display: flex; flex-wrap: wrap; gap: 4px; }
.budget-card__test-chip {
  font-size: .62rem; padding: 2px 6px; border-radius: 3px;
  background: rgba(99,102,241,.12); color: var(--text-sec);
  border: 1px solid rgba(99,102,241,.2); cursor: pointer; white-space: nowrap;
  transition: all var(--tr-fast);
}
.budget-card__test-chip:hover { background: rgba(99,102,241,.25); color: var(--accent-light); }
.budget-card__test-chip--fail { background: rgba(248,113,113,.12); border-color: rgba(248,113,113,.3); color: var(--red); }
.budget-card__test-chip--fail:hover { background: rgba(248,113,113,.22); }
.budget-card__test-chip--more { background: transparent; border-style: dashed; cursor: default; color: var(--text-muted); }
.budget-card__test-chip--more:hover { background: transparent; color: var(--text-muted); }
.budget-card__missed { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; padding: 5px 7px; background: rgba(248,113,113,.08); border: 1px solid rgba(248,113,113,.2); border-radius: 4px; }
.budget-card__missed-label { font-size: .62rem; color: var(--red); white-space: nowrap; font-weight: 600; }
.budget-card__missed-chip { font-size: .6rem; padding: 1px 5px; border-radius: 3px; background: rgba(248,113,113,.15); color: var(--red); white-space: nowrap; }
.flaky-table { display: flex; flex-direction: column; gap: 2px; }
.flaky-table__header {
  display: grid; grid-template-columns: 2fr 70px 1fr 50px;
  gap: 8px; padding: 0 4px 4px; border-bottom: 1px solid var(--border);
  font-size: .6rem; color: var(--text-muted); text-transform: uppercase; letter-spacing: .04em;
}
.flaky-table__row {
  display: grid; grid-template-columns: 2fr 70px 1fr 50px;
  gap: 8px; align-items: center; padding: 3px 4px; border-radius: 4px; cursor: pointer;
  transition: background var(--tr-fast);
}
.flaky-table__row:hover { background: rgba(99,102,241,.08); }
.flaky-table__name { font-size: .66rem; color: var(--text-sec); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.flaky-table__trend { font-size: .62rem; font-weight: 600; white-space: nowrap; }
.flaky-table__trend--rising { color: var(--red); }
.flaky-table__trend--falling { color: var(--green); }
.flaky-table__trend--stable { color: var(--yellow); }
.flaky-table__dots { display: flex; align-items: center; gap: 2px; flex-wrap: nowrap; overflow: hidden; }
.flaky-table__dot {
  width: 7px; height: 7px; border-radius: 50%; flex-shrink: 0;
}
.flaky-table__dot--fail { background: var(--red); }
.flaky-table__dot--pass { background: var(--green); opacity: .6; }
.flaky-table__dot--none { background: rgba(100,116,139,.3); }
.flaky-table__rate { font-size: .66rem; font-weight: 700; text-align: right; }
.cov-eff__cols { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 6px; }
.cov-eff__col { background: rgba(30,41,59,.5); border: 1px solid var(--border); border-radius: 5px; padding: 6px 8px; }
.cov-eff__col--over { border-color: rgba(251,146,60,.3); }
.cov-eff__col--under { border-color: rgba(99,102,241,.3); }
.cov-eff__col-title { font-size: .65rem; font-weight: 700; color: var(--text-sec); margin-bottom: 4px; }
.cov-eff__col-hint { font-size: .58rem; font-weight: 400; color: var(--text-muted); }
.cov-eff__row { display: flex; align-items: center; justify-content: space-between; gap: 6px; padding: 2px 0; cursor: pointer; border-radius: 3px; transition: background var(--tr-fast); }
.cov-eff__row:hover { background: rgba(99,102,241,.08); }
.cov-eff__name { font-size: .62rem; color: var(--text-sec); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; }
.cov-eff__count { font-size: .65rem; font-weight: 700; flex-shrink: 0; }
.cov-eff__count--over { color: var(--orange); }
.cov-eff__count--under { color: var(--accent-light); }
.cov-eff__uncovered { margin-top: 6px; font-size: .65rem; color: var(--text-muted); }
.cov-eff__uncovered strong { color: var(--red); }
.ffh-wrap { padding: 6px 8px; overflow-x: auto; }
.ffh-header {
  display: flex; align-items: center; gap: 6px;
  font-size: .58rem; color: var(--text-muted); text-transform: uppercase; letter-spacing: .04em;
  padding: 0 2px 4px; border-bottom: 1px solid var(--border); margin-bottom: 3px;
}
.ffh-row {
  display: flex; align-items: center; gap: 6px;
  padding: 3px 2px; border-radius: 4px; cursor: pointer;
  transition: background var(--tr-fast);
}
.ffh-row:hover { background: rgba(99,102,241,.08); }
.ffh-row-name { font-size: .63rem; color: var(--text-sec); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; width: 140px; flex-shrink: 0; }
.ffh-rank-col { font-size: .6rem; color: var(--text-muted); width: 32px; flex-shrink: 0; text-align: right; }
.ffh-first-col { width: 36px; flex-shrink: 0; text-align: center; }
.ffh-first-badge { font-size: .62rem; font-weight: 700; }
.ffh-first-badge--has { color: #fbbf24; }
.ffh-first-badge--none { color: var(--text-muted); }
.ffh-cells { display: flex; gap: 2px; flex-shrink: 0; }
.ffh-cell-hdr { width: 20px; height: 16px; text-align: center; font-size: .55rem; color: var(--text-muted); flex-shrink: 0; }
.ffh-cell {
  width: 20px; height: 16px; border-radius: 2px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: .6rem; font-weight: 700; transition: opacity var(--tr-fast);
}
.ffh-cell--first { background: rgba(251,191,36,.25); color: #fbbf24; border: 1px solid rgba(251,191,36,.5); }
.ffh-cell--fail { background: rgba(248,113,113,.2); color: var(--red); border: 1px solid rgba(248,113,113,.3); }
.ffh-cell--pass { background: rgba(30,41,59,.4); border: 1px solid transparent; color: transparent; }
.burst-row { background: rgba(239,68,68,.06); border: 1px solid rgba(239,68,68,.2); border-radius: 5px; padding: 6px 10px; }
.burst-row__header { display: flex; align-items: center; gap: 8px; margin-bottom: 5px; }
.burst-row__count { font-size: .72rem; font-weight: 700; color: var(--red); }
.burst-row__sigma { font-size: .62rem; font-weight: 700; color: var(--orange); background: rgba(251,146,60,.15); border: 1px solid rgba(251,146,60,.3); border-radius: 10px; padding: 0 5px; }
.burst-row__ts { font-size: .6rem; color: var(--text-muted); margin-left: auto; }
.burst-row__chips { display: flex; flex-wrap: wrap; gap: 3px; }
.burst-row__chip { font-size: .6rem; padding: 1px 6px; border-radius: 3px; background: rgba(239,68,68,.12); color: var(--red); border: 1px solid rgba(239,68,68,.25); cursor: pointer; white-space: nowrap; transition: all var(--tr-fast); }
.burst-row__chip:hover { background: rgba(239,68,68,.22); border-color: rgba(239,68,68,.5); }
.burst-row__chip--more { background: transparent; border-style: dashed; cursor: default; color: var(--text-muted); }
.burst-row__chip--more:hover { background: transparent; }
.retire-header {
  display: grid; grid-template-columns: 1fr 40px 54px 36px;
  gap: 8px; padding: 0 2px 4px; border-bottom: 1px solid var(--border);
  font-size: .58rem; color: var(--text-muted); text-transform: uppercase; letter-spacing: .04em; margin-bottom: 3px;
}
.retire-row {
  display: grid; grid-template-columns: 1fr 40px 54px 36px;
  gap: 8px; align-items: center; padding: 3px 2px;
  border-radius: 3px; cursor: pointer; transition: background var(--tr-fast);
}
.retire-row:hover { background: rgba(99,102,241,.07); }
.retire__name { font-size: .63rem; color: var(--text-sec); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.retire__rank { font-size: .6rem; text-align: right; }
.retire__seen { font-size: .63rem; font-weight: 700; text-align: right; }
.retire__dur { font-size: .6rem; text-align: right; }
.retire__deps { font-size: .6rem; text-align: right; }

/* Subnav */
.analytics__subnav {
  position: sticky; top: 0; z-index: 4; background: var(--bg-base);
  border-bottom: 1px solid var(--border); display: flex; gap: 0;
  overflow-x: auto; margin-bottom: 8px;
}
.analytics__subnav-link {
  padding: 4px 12px; font-size: .68rem; color: var(--text-muted);
  text-decoration: none; border-right: 1px solid var(--border); white-space: nowrap;
  flex-shrink: 0;
}
.analytics__subnav-link:hover { color: var(--accent-light); background: rgba(99,102,241,.06); }
</style>
