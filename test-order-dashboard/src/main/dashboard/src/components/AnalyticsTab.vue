<script setup lang="ts">
import { inject, watch, nextTick, onMounted } from 'vue'
import * as d3 from 'd3'
import type { DashboardState } from '../composables/useDashboard'
import { sn, DIST } from '../utils'
import { mkChart, destroyCharts, chartOpts } from '../composables/useCharts'

const d = inject<DashboardState>('dashboard')!

const TL_IDS = ['tl-apfd', 'tl-fail', 'tl-ffp', 'tl-cnt'] as const
const DIST_IDS = ['d-score', 'd-dur', 'd-deps', 'd-fail'] as const

function baseOpts() {
  const o = chartOpts() as Record<string, unknown>
  return {
    ...o,
    plugins: {
      ...(o.plugins as Record<string, unknown> ?? {}),
      tooltip: { ...((o.plugins as Record<string, unknown>)?.tooltip ?? {}), mode: 'index' as const, intersect: false },
    },
    scales: {
      x: { ticks: { color: '#475569', font: { size: 9 }, maxRotation: 30 }, grid: { color: 'rgba(71,85,105,.25)' } },
      y: { ticks: { color: '#475569', font: { size: 10 } }, grid: { color: 'rgba(71,85,105,.25)' } },
    },
  }
}

function initTimeline() {
  if (!d.runs.length) return
  destroyCharts(...TL_IDS)
  const labels = d.runs.map(r => {
    const dt = new Date(r.timestamp)
    return `${dt.getMonth() + 1}/${dt.getDate()} ${dt.getHours()}:${String(dt.getMinutes()).padStart(2, '0')}`
  })

  mkChart('tl-apfd', {
    type: 'line', data: { labels, datasets: [
      { label: 'APFD %', data: d.runs.map(r => +(r.apfd * 100).toFixed(1)), borderColor: '#22c55e', backgroundColor: 'rgba(34,197,94,.1)', fill: true, tension: .3, pointRadius: 3, pointHoverRadius: 5 },
      { label: 'Random (50%)', data: labels.map(() => 50), borderColor: '#475569', borderDash: [6, 4], borderWidth: 1.5, pointRadius: 0, tension: 0 },
    ] }, options: {
      ...baseOpts(),
      plugins: { ...baseOpts().plugins, legend: { display: true, labels: { color: '#64748b', font: { size: 9 }, boxWidth: 10, padding: 6 } } },
      scales: { ...baseOpts().scales, y: { ...baseOpts().scales.y, min: 0, max: 100 } },
    },
  })
  mkChart('tl-fail', {
    type: 'bar', data: { labels, datasets: [{ label: 'Failures', data: d.runs.map(r => r.totalFailures), backgroundColor: d.runs.map(r => r.totalFailures > 0 ? 'rgba(239,68,68,.7)' : 'rgba(34,197,94,.5)') }] },
    options: baseOpts(),
  })
  mkChart('tl-ffp', {
    type: 'line', data: { labels, datasets: [{
      label: 'First failure position', data: d.runs.map(r => r.firstFailurePosition >= 0 ? r.firstFailurePosition : 0),
      pointBackgroundColor: d.runs.map(r => r.firstFailurePosition >= 0 ? 'rgba(239,68,68,.8)' : 'transparent'),
      pointBorderColor: d.runs.map(r => r.firstFailurePosition >= 0 ? 'rgba(239,68,68,.8)' : 'rgba(71,85,105,.7)'),
      pointBorderWidth: 1.5, pointRadius: 5, showLine: false, borderColor: 'transparent',
    }] }, options: baseOpts(),
  })
  mkChart('tl-cnt', {
    type: 'line', data: { labels, datasets: [{ label: 'Test count', data: d.runs.map(r => r.totalTests), borderColor: '#6366f1', backgroundColor: 'rgba(99,102,241,.12)', fill: true, stepped: 'before' as const, tension: 0 }] },
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
    options: { ...chartOpts(), scales: { x: { ticks: { color: '#475569', font: { size: 9 } }, grid: { color: 'rgba(71,85,105,.2)' } }, y: { ticks: { color: '#475569', font: { size: 9 } }, grid: { color: 'rgba(71,85,105,.2)' } } } },
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
    options: { ...chartOpts(), scales: { x: { ticks: { color: '#475569', font: { size: 9 } }, grid: { color: 'rgba(71,85,105,.2)' } }, y: { ticks: { color: '#475569', font: { size: 9 } }, grid: { color: 'rgba(71,85,105,.2)' } } } },
  })

  const top20 = [...d.tests].filter(t => t.failScore > 0).sort((a, b) => b.failScore - a.failScore).slice(0, DIST.TOP_FAIL_COUNT)
  mkChart('d-fail', {
    type: 'bar', data: { labels: top20.map(t => sn(t.name)), datasets: [{ label: 'Fail score', data: top20.map(t => +t.failScore.toFixed(2)), backgroundColor: 'rgba(239,68,68,.7)', borderRadius: 2 }] },
    options: { ...chartOpts(), indexAxis: 'y' as const, scales: { x: { ticks: { color: '#475569', font: { size: 9 } }, grid: { color: 'rgba(71,85,105,.2)' } }, y: { ticks: { color: '#475569', font: { size: 8 }, maxTicksLimit: DIST.TOP_FAIL_COUNT }, grid: { display: false } } } },
  })
  } catch (e) { console.error('[dashboard] Distribution charts failed:', e) }
}

// Treemap: separate layout build from color updates
let treemapLeaves: d3.Selection<SVGRectElement, d3.HierarchyRectangularNode<unknown>, SVGSVGElement, unknown> | null = null
let treemapColorScale: ((t: number) => string) | null = null
let treemapMaxTests = 1

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
  treemapColorScale = d3.scaleSequential(t => d3.interpolateRgb('#ef4444', '#22c55e')(t)).domain([0, treemapMaxTests])

  const svg = d3.select(container).append('svg').attr('width', W).attr('height', H)

  const pkgNodes = svg.selectAll('g.pkg').data((hierarchy as any).children || []).join('g').attr('class', 'pkg')
  pkgNodes.append('text')
    .attr('x', (nd: any) => nd.x0 + 3).attr('y', (nd: any) => nd.y0 + 11)
    .attr('font-size', '9px').attr('fill', '#94a3b8').attr('font-weight', '600')
    .text((nd: any) => { const nm = nd.data.name.split('.').pop(); return (nd.x1 - nd.x0) > 40 ? nm : '' })

  const leaves = svg.selectAll('rect.leaf').data(hierarchy.leaves()).join('rect').attr('class', 'leaf tree-node')
    .attr('x', (nd: any) => nd.x0).attr('y', (nd: any) => nd.y0)
    .attr('width', (nd: any) => Math.max(nd.x1 - nd.x0, 0))
    .attr('height', (nd: any) => Math.max(nd.y1 - nd.y0, 0))
    .attr('fill', (nd: any) => {
      if (!nd.data.data) return '#334155'
      if (highlightSources) return highlightSources.has(nd.data.data.name) ? '#22c55e' : '#1e293b'
      return treemapColorScale!(nd.data.data.testCount)
    })
    .attr('stroke', (nd: any) => highlightSources && nd.data.data && highlightSources.has(nd.data.data.name) ? '#16a34a' : '#0f172a')
    .attr('stroke-width', (nd: any) => highlightSources && nd.data.data && highlightSources.has(nd.data.data.name) ? 2 : 1)
    .attr('rx', 2)

  svg.selectAll('text.leaf-label').data(hierarchy.leaves()).join('text').attr('class', 'leaf-label')
    .attr('x', (nd: any) => nd.x0 + 3).attr('y', (nd: any) => nd.y0 + ((nd.y1 - nd.y0) / 2) + 3)
    .attr('font-size', '8px').attr('fill', '#0f172a').attr('font-weight', '600')
    .text((nd: any) => { const w = nd.x1 - nd.x0, h = nd.y1 - nd.y0; if (w < 30 || h < 14) return ''; const nm = nd.data.name.split('.').pop(); return nm.length > w / 5 ? nm.substring(0, Math.floor(w / 5)) + '…' : nm })

  const tip = d3.select(container).append('div')
    .style('position', 'absolute').style('background', '#1e293b').style('border', '1px solid #334155')
    .style('padding', '6px 10px').style('border-radius', '4px').style('font-size', '11px')
    .style('color', '#e2e8f0').style('pointer-events', 'none').style('opacity', '0').style('z-index', '10')

  leaves.on('mouseover', (e: MouseEvent, nd: any) => {
    if (!nd.data.data) return
    const c = nd.data.data
    const selHit = highlightSources ? (highlightSources.has(c.name) ? ' · <span style="color:#22c55e">covered by selection</span>' : ' · <span style="color:#64748b">not in selection</span>') : ''
    tip.style('opacity', '1').html(`<strong>${sn(c.name)}</strong><br><span style="color:#64748b">${c.testCount} test${c.testCount === 1 ? '' : 's'}${selHit}</span>`)
  }).on('mousemove', (e: MouseEvent) => tip.style('left', (e.offsetX + 12) + 'px').style('top', (e.offsetY - 10) + 'px'))
    .on('mouseout', () => tip.style('opacity', '0'))
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
    .attr('fill', (nd: any) => {
      if (!nd.data.data) return '#334155'
      if (highlightSources) return highlightSources.has(nd.data.data.name) ? '#22c55e' : '#1e293b'
      return treemapColorScale!(nd.data.data.testCount)
    })
    .attr('stroke', (nd: any) => highlightSources && nd.data.data && highlightSources.has(nd.data.data.name) ? '#16a34a' : '#0f172a')
    .attr('stroke-width', (nd: any) => highlightSources && nd.data.data && highlightSources.has(nd.data.data.name) ? 2 : 1)
}

function initAll() {
  if (d.activeTab.value !== 'analytics') return
  nextTick(() => { try { initTimeline() } catch (e) { console.error('[dashboard] Timeline failed:', e) }; initDistributions(); buildCoverageTreemap() })
}

watch(() => d.activeTab.value, (tab) => { if (tab === 'analytics') initAll() })
watch(() => d.selectedTests.value, () => { if (d.activeTab.value === 'analytics' && d.hasCoverage) nextTick(updateTreemapColors) })
watch(() => d.selectedMethods.value, () => { if (d.activeTab.value === 'analytics' && d.hasCoverage) nextTick(updateTreemapColors) })
onMounted(initAll)
</script>

<template>
  <div v-if="d.activeTab.value === 'analytics'">
    <div v-if="d.runs.length === 0 && !d.hasCoverage" style="height:180px;display:flex;align-items:center;justify-content:center;color:var(--text-muted)">No run history yet</div>
    <div v-else>
      <!-- Timeline -->
      <div v-if="d.runs.length" style="margin-bottom:12px">
        <div class="card" style="margin-bottom:8px">
          <div class="card-label">APFD over time <span style="color:var(--border)">(dashed = 0.5 random baseline)</span></div>
          <div class="analytics__canvas" style="height:100px"><canvas id="tl-apfd"></canvas></div>
        </div>
        <div style="display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px">
          <div class="card"><div class="card-label">Failures per run</div><div class="analytics__canvas analytics__canvas--sm"><canvas id="tl-fail"></canvas></div></div>
          <div class="card"><div class="card-label">First failure position</div><div class="analytics__canvas analytics__canvas--sm"><canvas id="tl-ffp"></canvas></div></div>
          <div class="card"><div class="card-label">Test count per run</div><div class="analytics__canvas analytics__canvas--sm"><canvas id="tl-cnt"></canvas></div></div>
        </div>
      </div>

      <!-- Distributions -->
      <div v-if="d.runs.length" style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:12px">
        <div class="card"><div class="card-label">Score distribution</div><div class="analytics__canvas analytics__canvas--dist"><canvas id="d-score"></canvas></div></div>
        <div class="card"><div class="card-label">Duration distribution (log buckets)</div><div class="analytics__canvas analytics__canvas--dist"><canvas id="d-dur"></canvas></div></div>
        <div class="card"><div class="card-label">Dependency count distribution</div><div class="analytics__canvas analytics__canvas--dist"><canvas id="d-deps"></canvas></div></div>
        <div class="card"><div class="card-label">Top 20 by fail score</div><div class="analytics__canvas analytics__canvas--dist"><canvas id="d-fail"></canvas></div></div>
      </div>

      <!-- Run comparison -->
      <div v-if="d.runDiff.value.length" style="margin-bottom:14px">
        <h3 style="font-size:.82rem;color:var(--text-sec);margin-bottom:8px">Last Run Comparison <span style="font-size:.65rem;color:var(--text-muted);font-weight:400">— vs previous run</span></h3>
        <div style="display:flex;gap:8px;margin-bottom:8px;flex-wrap:wrap">
          <div class="kpi" style="padding:5px 10px">
            <div style="font-size:.58rem;color:var(--text-dim)">Newly Failed</div>
            <div style="font-size:.9rem;font-weight:700;color:var(--red)">{{ d.runDiff.value.filter(e => e.status === 'newly-failed').length }}</div>
          </div>
          <div class="kpi" style="padding:5px 10px">
            <div style="font-size:.58rem;color:var(--text-dim)">Recovered</div>
            <div style="font-size:.9rem;font-weight:700;color:var(--green)">{{ d.runDiff.value.filter(e => e.status === 'recovered').length }}</div>
          </div>
          <div class="kpi" style="padding:5px 10px">
            <div style="font-size:.58rem;color:var(--text-dim)">New Tests</div>
            <div style="font-size:.9rem;font-weight:700;color:var(--cyan)">{{ d.runDiff.value.filter(e => e.status === 'new').length }}</div>
          </div>
          <div class="kpi" style="padding:5px 10px">
            <div style="font-size:.58rem;color:var(--text-dim)">Improved</div>
            <div style="font-size:.9rem;font-weight:700;color:var(--green)">{{ d.runDiff.value.filter(e => e.status === 'improved').length }}</div>
          </div>
          <div class="kpi" style="padding:5px 10px">
            <div style="font-size:.58rem;color:var(--text-dim)">Regressed</div>
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
              <tr v-for="e in d.runDiff.value.filter(e => e.status !== 'unchanged')" :key="e.name">
                <td style="padding:3px 8px;max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" :title="e.name">{{ sn(e.name) }}</td>
                <td style="padding:3px 8px">
                  <span class="badge" :class="{
                    'analytics__diff--fail': e.status === 'newly-failed',
                    'analytics__diff--recover': e.status === 'recovered',
                    'analytics__diff--new': e.status === 'new',
                    'analytics__diff--removed': e.status === 'removed',
                    'analytics__diff--improved': e.status === 'improved',
                    'analytics__diff--regressed': e.status === 'regressed',
                  }">{{ e.status }}</span>
                </td>
                <td style="padding:3px 8px;text-align:right;color:var(--text-dim)">{{ e.prevRank ?? '—' }}</td>
                <td style="padding:3px 8px;text-align:right;color:var(--text-dim)">{{ e.currRank ?? '—' }}</td>
                <td style="padding:3px 8px;text-align:right;font-weight:700" :style="{ color: e.rankDelta < 0 ? 'var(--green)' : e.rankDelta > 0 ? 'var(--red)' : 'var(--text-muted)' }">
                  {{ e.rankDelta === 0 ? '–' : e.rankDelta > 0 ? '+' + e.rankDelta : e.rankDelta }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- Coverage -->
      <div v-if="d.hasCoverage">
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
        </div>

        <!-- Selection coverage -->
        <div v-if="d.selectionCoverage.value" class="card" style="margin-bottom:10px;padding:8px 12px">
          <div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap">
            <span style="font-size:.72rem;color:var(--text-sec);font-weight:600">Selection Coverage</span>
            <span style="font-size:.68rem;color:var(--text-muted)">{{ d.selectedTests.value.size }} test{{ d.selectedTests.value.size === 1 ? '' : 's' }}<span v-if="d.selectedMethods.value.size"> · {{ d.selectedMethods.value.size }} method{{ d.selectedMethods.value.size === 1 ? '' : 's' }}</span></span>
            <div class="analytics__progress-bar" style="flex:1;min-width:100px">
              <div class="analytics__progress-fill" :class="d.selectionCoverage.value.percent >= 80 ? 'analytics__progress-fill--green' : d.selectionCoverage.value.percent >= 50 ? 'analytics__progress-fill--yellow' : 'analytics__progress-fill--red'" :style="{ width: d.selectionCoverage.value.percent + '%' }"></div>
            </div>
            <span class="analytics__pct" :class="d.selectionCoverage.value.percent >= 80 ? 'analytics__pct--green' : d.selectionCoverage.value.percent >= 50 ? 'analytics__pct--yellow' : 'analytics__pct--red'">{{ d.selectionCoverage.value.percent }}%</span>
            <span style="font-size:.68rem;color:var(--text-dim)">({{ d.selectionCoverage.value.covered }}/{{ d.selectionCoverage.value.total }} classes)</span>
          </div>
        </div>

        <!-- Treemap -->
        <div style="display:flex;align-items:center;gap:8px;margin-bottom:6px">
          <div class="analytics__search-wrap">
            <input
              :value="d.covSearchQ.value"
              @input="d.covSearchQ.value = ($event.target as HTMLInputElement).value"
              placeholder="Search classes…"
              class="analytics__search"
            />
            <button v-if="d.covSearchQ.value" class="analytics__search-clear" @click="d.covSearchQ.value = ''" title="Clear search">×</button>
          </div>
          <span v-if="d.covSearchQ.value" style="font-size:.65rem;color:var(--text-muted)">{{ d.filteredCovClasses.value.length }} matches</span>
        </div>
        <div id="cov-treemap" style="background:var(--bg-card);border-radius:var(--radius);overflow:hidden;height:350px;position:relative"></div>

        <!-- Class detail panel -->
        <div v-if="d.covSelectedClass.value" class="detail-panel" style="margin-top:10px">
          <div style="display:flex;align-items:center;gap:8px;margin-bottom:6px">
            <span style="font-size:.82rem;font-weight:600;color:var(--text)">{{ d.covSelectedClass.value.name }}</span>
            <span style="font-size:.72rem;color:var(--text-sec)">tested by {{ d.covSelectedClass.value.testCount }} test{{ d.covSelectedClass.value.testCount === 1 ? '' : 's' }}</span>
            <button @click="d.covSelectedClass.value = null" style="margin-left:auto;padding:2px 8px;font-size:.65rem;background:var(--border);color:var(--text-sec);border:1px solid var(--text-muted);border-radius:3px;cursor:pointer">✕</button>
          </div>
          <div style="display:flex;flex-wrap:wrap;gap:4px;margin-bottom:8px">
            <span
              v-for="tn in d.covSelectedClass.value.tests" :key="tn"
              class="analytics__class-test-tag analytics__class-test-tag--clickable"
              @click="d.navigateToTestFromCov(tn)"
              :title="'Go to ' + tn + ' in Tests tab'"
            >{{ sn(tn) }} →</span>
          </div>
          <div v-if="d.covSelectedClass.value.members?.length">
            <div class="card-label" style="margin-bottom:4px">Members ({{ d.covSelectedClass.value.members.length }})</div>
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
</template>

<style scoped>
.analytics__canvas { position: relative; }
.analytics__canvas--sm { height: 72px; }
.analytics__canvas--dist { height: 140px; }
.analytics__cov-kpi { padding: 6px 10px; }
.analytics__cov-kpi-label { color: var(--text-dim); font-size: .6rem; }
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
</style>
