<template>
  <div ref="container" class="overhead-chart" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'

const container = ref(null)

const W = 820, H = 420
const MARGIN = { top: 20, right: 100, bottom: 48, left: 190 }
const IW = W - MARGIN.left - MARGIN.right
const IH = H - MARGIN.top - MARGIN.bottom

// Ratios relative to baseline (1.00 = same as no-plugin baseline)
// 7-run trimmed mean (drop min+max → 5 interior values); SE = stdev/sqrt(n)
const DATA = [
  { repo: 'spring-petclinic',    class_r: 1.020, class_se: 0.022, method_r: 1.069, method_se: 0.017, member_r: 1.063, member_se: 0.023 },
  { repo: 'commons-collections', class_r: 1.036, class_se: 0.003, method_r: 1.039, method_se: 0.005, member_r: 1.059, member_se: 0.004 },
  { repo: 'commons-io',          class_r: 1.118, class_se: 0.010, method_r: 1.140, method_se: 0.021, member_r: 1.123, member_se: 0.000 },
  { repo: 'guava',               class_r: 1.175, class_se: 0.011, method_r: 1.162, method_se: 0.012, member_r: 1.150, member_se: 0.012 },
  { repo: 'jackson-core',        class_r: 1.311, class_se: 0.048, method_r: 1.310, method_se: 0.030, member_r: 1.439, member_se: 0.046 },
  { repo: 'logbook',             class_r: 1.498, class_se: 0.037, method_r: 1.516, method_se: 0.047, member_r: 1.556, member_se: 0.056 },
  { repo: 'spring-ai',           class_r: 2.901, class_se: 0.038, method_r: 2.853, method_se: 0.032, member_r: 2.956, member_se: 0.051 },
]

// 4 sub-bars per repo: baseline ref + CLASS + METHOD + MEMBER
const MODES = [
  { key: 'class',  label: 'CLASS',  col: '#60a5fa', strokeCol: '#93c5fd' },
  { key: 'method', label: 'METHOD', col: '#a78bfa', strokeCol: '#c4b5fd' },
  { key: 'member', label: 'MEMBER*', col: '#f472b6', strokeCol: '#f9a8d4' },
]

onMounted(() => {
  const svg = d3.select(container.value)
    .append('svg')
    .attr('viewBox', `0 0 ${W} ${H}`)
    .attr('width', W).attr('height', H)
    .attr('font-family', "'Inter','Helvetica Neue',sans-serif")
    .style('font-size', '1px')

  const g = svg.append('g').attr('transform', `translate(${MARGIN.left},${MARGIN.top})`)

  const xDomain = [0.95, 3.5]
  const x = d3.scaleLog().base(2).domain(xDomain).range([0, IW])
  const y = d3.scaleBand().domain(DATA.map(d => d.repo)).range([0, IH]).padding(0.28)

  // Vertical reference line at 1.0
  g.append('line')
    .attr('x1', x(1.0)).attr('x2', x(1.0)).attr('y1', 0).attr('y2', IH)
    .attr('stroke', 'rgba(255,255,255,0.25)').attr('stroke-width', 1.5)

  // Grid lines
  const chrome = g.append('g').attr('opacity', 0)
  chrome.transition().duration(400).attr('opacity', 1)

  ;[1.25, 1.5, 2.0, 3.0].forEach(v => {
    chrome.append('line').attr('x1', x(v)).attr('x2', x(v)).attr('y1', 0).attr('y2', IH)
      .attr('stroke', 'rgba(255,255,255,0.05)').attr('stroke-width', 1).attr('stroke-dasharray', '2,3')
  })

  // X axis
  const axisG = chrome.append('g').attr('transform', `translate(0,${IH + 6})`)
  ;[1.0, 1.25, 1.5, 2.0, 3.0].forEach(v => {
    axisG.append('text').attr('x', x(v)).attr('y', 16)
      .attr('text-anchor', 'middle').style('font-size', '14px').attr('fill', '#64748b')
      .text(`${v.toFixed(v === 1.0 ? 1 : (v < 2 ? 2 : 1))}×`)
  })
  axisG.append('text').attr('x', IW / 2).attr('y', 36)
    .attr('text-anchor', 'middle').style('font-size', '13px').attr('fill', '#64748b')
    .text('wall-clock time relative to baseline (log scale, 1.0× = no plugin)')

  // Legend
  const leg = chrome.append('g').attr('transform', `translate(${IW + 14}, 6)`)
  MODES.forEach((m, i) => {
    leg.append('rect').attr('x', 0).attr('y', i * 24).attr('width', 14).attr('height', 12).attr('rx', 2)
      .attr('fill', m.col).attr('opacity', 0.7)
    leg.append('text').attr('x', 20).attr('y', i * 24 + 11).style('font-size', '13px').attr('fill', '#94a3b8').text(m.label)
  })
  leg.append('line').attr('x1', 3).attr('x2', 11).attr('y1', 3 * 24 + 6).attr('y2', 3 * 24 + 6)
    .attr('stroke', 'rgba(255,255,255,0.35)').attr('stroke-width', 1.5)
  leg.append('text').attr('x', 20).attr('y', 3 * 24 + 10).style('font-size', '12px').attr('fill', '#64748b').text('± std err')

  // Bars
  DATA.forEach((d, ri) => {
    const rowDelay = 200 + ri * 130
    const yBand = y(d.repo)
    const bH = y.bandwidth()
    const nModes = MODES.length
    const subH = bH / (nModes + 0.5) * 0.82
    const gap = (bH - nModes * subH) / (nModes + 1)

    // Repo label
    g.append('text').attr('x', -10).attr('y', yBand + bH / 2 + 5)
      .attr('text-anchor', 'end').style('font-size', '15px').attr('font-weight', 500)
      .attr('fill', '#94a3b8').attr('opacity', 0)
      .transition().delay(rowDelay).duration(250).attr('opacity', 1)
      .selection().text(d.repo)

    MODES.forEach((mode, mi) => {
      const barDelay = rowDelay + mi * 90
      const yPos = yBand + gap + mi * (subH + gap)
      const midY = yPos + subH / 2
      const mean = d[mode.key + '_r']
      const se   = d[mode.key + '_se']

      if (mean === null) {
        // placeholder — faint dashed line at 1.0
        g.append('line').attr('x1', x(1.0) - 6).attr('x2', x(1.0) + 6)
          .attr('y1', midY).attr('y2', midY)
          .attr('stroke', 'rgba(255,255,255,0.12)').attr('stroke-width', 1).attr('stroke-dasharray', '2,2')
        return
      }

      // Bar from 1.0 to mean (overhead portion)
      g.append('rect')
        .attr('x', x(1.0)).attr('y', yPos).attr('height', subH).attr('rx', 3)
        .attr('fill', mode.col).attr('opacity', 0.22)
        .attr('stroke', mode.strokeCol).attr('stroke-width', 1)
        .attr('width', 0)
        .transition().delay(barDelay).duration(420).ease(d3.easeCubicOut)
        .attr('width', Math.max(0, x(mean) - x(1.0)))

      // Error bar — clamp to domain minimum so log scale stays valid
      if (se > 0) {
        const loVal = Math.max(xDomain[0], mean - se)
        const hiVal = Math.min(xDomain[1], mean + se)
        const errG = g.append('g').attr('opacity', 0)
        errG.transition().delay(barDelay + 470).duration(200).attr('opacity', 1)
        errG.append('line')
          .attr('x1', x(loVal)).attr('x2', x(hiVal))
          .attr('y1', midY).attr('y2', midY)
          .attr('stroke', mode.strokeCol).attr('stroke-width', 1.5)
        ;[[loVal, -1], [hiVal, 1]].forEach(([xv]) => {
          errG.append('line')
            .attr('x1', x(xv)).attr('x2', x(xv))
            .attr('y1', midY - 4).attr('y2', midY + 4)
            .attr('stroke', mode.strokeCol).attr('stroke-width', 1.5)
        })
      }

      // Dot at tip
      g.append('circle').attr('cx', x(mean)).attr('cy', midY)
        .attr('r', 0).attr('fill', mode.col)
        .transition().delay(barDelay + 440).duration(200).ease(d3.easeBackOut.overshoot(2))
        .attr('r', 3.5)
    })
  })
})
</script>

<style scoped>
.overhead-chart { display: block; width: 100%; }
</style>
