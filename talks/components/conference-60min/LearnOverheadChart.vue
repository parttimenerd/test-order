<template>
  <div ref="container" class="learn-overhead-chart">
    <div v-if="loading" class="loading">Loading…</div>
    <div v-else-if="error" class="error">{{ error }}</div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'
import { isDark } from '../../conference-60min/dark'

const container = ref(null)
const loading = ref(true)
const error = ref(null)

const W = 860, H = 420
const MARGIN = { top: 38, right: 155, bottom: 52, left: 160 }

function shortName(repo) {
  return repo
    .replace('commons-collections', 'c-collections')
    .replace('commons-io',          'c-io')
    .replace('spring-petclinic',    'petclinic')
    .replace('spring-ai',           'spring-ai')
    .replace('jackson-core',        'jackson-core')
    .replace('logbook',             'logbook')
    .replace('guava',               'guava')
}
const IW = W - MARGIN.left - MARGIN.right
const IH = H - MARGIN.top - MARGIN.bottom

const MODE_COL = {
  CLASS:  { fill: 'rgba(148,163,184,0.22)', stroke: 'rgba(148,163,184,0.6)', label: '#94a3b8' },
  METHOD: { fill: 'rgba(96,165,250,0.22)',  stroke: 'rgba(96,165,250,0.7)',  label: '#60a5fa' },
  MEMBER: { fill: 'rgba(251,191,36,0.25)',  stroke: 'rgba(251,191,36,0.85)', label: '#fbbf24' },
}
const DEFAULT_MODE = 'MEMBER'

onMounted(async () => {
  const dark = isDark.value
  try {
    const resp = await fetch('./bench-learn-overhead.json')
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
    const payload = await resp.json()
    loading.value = false
    render(payload, dark)
  } catch (e) {
    loading.value = false
    error.value = `Could not load bench-learn-overhead.json: ${e.message}`
  }
})

function render(payload, dark) {
  const { repos, modes, data } = payload
  const textMid  = dark ? '#94a3b8' : '#475569'
  const textDim  = dark ? '#334155' : '#334155'
  const textSub  = dark ? '#475569' : '#334155'
  const gridLine = dark ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.08)'
  const baseline = dark ? 'rgba(255,255,255,0.15)' : 'rgba(0,0,0,0.20)'
  const errLine  = dark ? 'rgba(255,255,255,0.35)' : 'rgba(0,0,0,0.35)'

  const activeRepos = repos.filter(r => modes.some(m => data[r]?.[m]))

  const svg = d3.select(container.value)
    .append('svg')
    .attr('viewBox', `0 0 ${W} ${H}`)
    .attr('width', '100%')
    .attr('font-family', "'Inter','Helvetica Neue',sans-serif")
    .style('font-size', '1px')

  const g = svg.append('g').attr('transform', `translate(${MARGIN.left},${MARGIN.top})`)

  const allMeans = activeRepos.flatMap(r =>
    modes.flatMap(m => data[r]?.[m] ? [data[r][m].mean] : [])
  )
  const xMax = Math.max(30, ...allMeans) * 1.18
  const x = d3.scaleLinear().domain([0, xMax]).range([0, IW]).nice()
  const y = d3.scaleBand().domain(activeRepos).range([0, IH]).padding(0.3)

  const chrome = g.append('g').attr('opacity', 0)
  chrome.transition().duration(350).attr('opacity', 1)

  x.ticks(5).forEach(v => {
    chrome.append('line')
      .attr('x1', x(v)).attr('x2', x(v)).attr('y1', 0).attr('y2', IH)
      .attr('stroke', gridLine).attr('stroke-dasharray', '2,3')
    chrome.append('text').attr('x', x(v)).attr('y', IH + 14)
      .attr('text-anchor', 'middle').style('font-size', '10px').attr('fill', textSub)
      .text(`${v}%`)
  })

  chrome.append('line')
    .attr('x1', x(0)).attr('x2', x(0)).attr('y1', 0).attr('y2', IH)
    .attr('stroke', baseline).attr('stroke-width', 1)

  chrome.append('text').attr('x', IW / 2).attr('y', IH + 30)
    .attr('text-anchor', 'middle').style('font-size', '11px').attr('fill', textDim)
    .text('learn-run overhead vs. baseline (% slower)')

  const leg = chrome.append('g').attr('transform', `translate(${IW + 14}, 4)`)
  modes.forEach((m, i) => {
    const col = MODE_COL[m]
    const isDefault = m === DEFAULT_MODE
    leg.append('rect').attr('x', 0).attr('y', i * 22).attr('width', 12).attr('height', 10)
      .attr('rx', 2).attr('fill', col.fill).attr('stroke', col.stroke).attr('stroke-width', 1)
    leg.append('text').attr('x', 17).attr('y', i * 22 + 9)
      .style('font-size', '10.5px')
      .attr('font-weight', isDefault ? 700 : 400)
      .attr('fill', col.label)
      .text(isDefault ? `${m} ★` : m)
  })
  leg.append('line').attr('x1', 3).attr('x2', 9).attr('y1', modes.length * 22 + 14).attr('y2', modes.length * 22 + 14)
    .attr('stroke', errLine).attr('stroke-width', 1.5)
  leg.append('text').attr('x', 17).attr('y', modes.length * 22 + 18)
    .style('font-size', '10px').attr('fill', textSub).text('± std err')
  leg.append('text').attr('x', 0).attr('y', modes.length * 22 + 34)
    .style('font-size', '9px').attr('fill', textDim).text('★ = default')

  activeRepos.forEach((repo, ri) => {
    const rowDelay = 180 + ri * 110
    const yBand = y(repo)
    const bH = y.bandwidth()
    const slotH = bH / modes.length
    const barH = slotH * 0.68
    const gap  = slotH * 0.32

    const repoLabel = g.append('text')
      .attr('x', -6).attr('y', yBand + bH / 2 + 4)
      .attr('text-anchor', 'end').style('font-size', '10px').attr('font-weight', 500)
      .attr('fill', textMid).attr('opacity', 0)
    repoLabel.text(shortName(repo))
    repoLabel.transition().delay(rowDelay).duration(220).attr('opacity', 1)

    const nt = data[repo]?.n_tests
    if (nt && nt > 0) {
      g.append('text').attr('x', IW + 8).attr('y', yBand + bH / 2 + 4)
        .style('font-size', '9.5px').attr('fill', textSub).attr('opacity', 0)
        .text(`${nt} tests`)
        .transition().delay(rowDelay + 150).duration(200).attr('opacity', 1)
    }

    modes.forEach((mode, mi) => {
      const modeData = data[repo]?.[mode]
      if (!modeData) return

      const col = MODE_COL[mode]
      const isDefault = mode === DEFAULT_MODE
      const barY = yBand + mi * slotH + gap / 2
      const mean = modeData.mean
      const se   = modeData.se ?? 0
      const modeDelay = rowDelay + mi * 60

      g.append('rect')
        .attr('x', 0).attr('y', barY).attr('height', barH).attr('rx', 3)
        .attr('fill', col.fill).attr('stroke', col.stroke)
        .attr('stroke-width', isDefault ? 1.5 : 1)
        .attr('width', 0)
        .transition().delay(modeDelay).duration(380).ease(d3.easeCubicOut)
        .attr('width', x(Math.max(0, mean)))

      if (se > 0) {
        const errG = g.append('g').attr('opacity', 0)
        errG.transition().delay(modeDelay + 420).duration(180).attr('opacity', 1)
        const mid = barY + barH / 2
        errG.append('line')
          .attr('x1', x(Math.max(0, mean - se))).attr('x2', x(mean + se))
          .attr('y1', mid).attr('y2', mid)
          .attr('stroke', col.stroke).attr('stroke-width', 1.5)
        ;[-1, 1].forEach(dir => {
          const xv = mean + dir * se
          if (xv < 0) return
          errG.append('line')
            .attr('x1', x(xv)).attr('x2', x(xv))
            .attr('y1', mid - 3).attr('y2', mid + 3)
            .attr('stroke', col.stroke).attr('stroke-width', 1.5)
        })
      }

      g.append('text')
        .attr('x', x(Math.max(0, mean)) + 6).attr('y', barY + barH / 2 + 4)
        .attr('font-size', isDefault ? 11 : 10)
        .attr('font-weight', isDefault ? 700 : 400)
        .attr('fill', col.label).attr('opacity', 0)
        .text(`${mean > 0 ? '+' : ''}${mean}%`)
        .transition().delay(modeDelay + 460).duration(180).attr('opacity', 1)
    })
  })
}
</script>

<style scoped>
.learn-overhead-chart { display: block; width: 100%; }
.loading, .error { color: #64748b; font-size: 13px; padding: 20px; }
.error { color: #f87171; }
</style>
