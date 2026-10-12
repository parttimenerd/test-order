<template>
  <div ref="container" class="benchmark-chart" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'

const container = ref(null)

const W = 820, H = 390
const MARGIN = { top: 32, right: 155, bottom: 50, left: 175 }
const IW = W - MARGIN.left - MARGIN.right
const IH = H - MARGIN.top - MARGIN.bottom

// time-to-first-fail % mean ± stderr; order_time_pct is how far into the
// suite (chronologically) the first failure surfaces when test-order runs.
// alpha_time_pct is the same metric for alphabetical ordering.
// Placeholder values — will be replaced by bench_summarise.py chart.json output.
const DATA = [
  { repo: 'commons-collections',         tests:  321, alpha:  26.9, alpha_se:  9.1,  order:  0.9, order_se:  0.7 },  // 11S+6R
  { repo: 'commons-io',                  tests:  252, alpha:  53.6, alpha_se: 10.9,  order: 54.6, order_se: 11.0 },  // 4S+2R
  { repo: 'guava',                       tests:  522, alpha:  24.5, alpha_se:  4.9,  order: 12.2, order_se:  1.1 },  // 2S+6R
  { repo: 'jackson-core',                tests:  249, alpha:  14.1, alpha_se:  2.1,  order:  4.9, order_se:  0.7 },  // 9S+3R
  { repo: 'logbook',                     tests:  238, alpha:   1.6, alpha_se:  0.0,  order:  1.1, order_se:  0.1 },  // 9S+3R
  { repo: 'spring-ai',                   tests:  105, alpha:  64.0, alpha_se:  7.3,  order: 44.8, order_se:  3.8 },  // 8S+2R
  { repo: 'spring-petclinic',            tests:   15, alpha:  43.3, alpha_se:  0.0,  order: 34.5, order_se:  0.0 },  // 1 real
]

onMounted(() => {
  const svg = d3.select(container.value)
    .append('svg')
    .attr('viewBox', `0 0 ${W} ${H}`)
    .attr('width', W).attr('height', H)
    .attr('font-family', "'Inter','Helvetica Neue',sans-serif")

  const g = svg.append('g').attr('transform', `translate(${MARGIN.left},${MARGIN.top})`)

  const y = d3.scaleBand().domain(DATA.map(d => d.repo)).range([0, IH]).padding(0.35)
  const x = d3.scaleLinear().domain([0, 100]).range([0, IW])

  // Chrome: grid lines, axis labels — fade in immediately
  const chrome = g.append('g').attr('opacity', 0)
  chrome.transition().duration(400).attr('opacity', 1)

  ;[25, 50, 75, 100].forEach(v => {
    chrome.append('line').attr('x1', x(v)).attr('x2', x(v)).attr('y1', 0).attr('y2', IH)
      .attr('stroke', 'rgba(255,255,255,0.06)')
      .attr('stroke-width', 1).attr('stroke-dasharray', '2,3')
  })

  // x-axis ticks
  const axisG = chrome.append('g').attr('transform', `translate(0,${IH + 5})`)
  ;[0, 25, 50, 75, 100].forEach(v => {
    axisG.append('text').attr('x', x(v)).attr('y', 14)
      .attr('text-anchor', 'middle').attr('font-size', 10).attr('fill', '#475569').text(`${v}%`)
  })
  axisG.append('text').attr('x', IW / 2).attr('y', 30)
    .attr('text-anchor', 'middle').attr('font-size', 11).attr('fill', '#334155')
    .text('Time elapsed when first failure appears (% of total suite time)')

  // Legend
  const leg = chrome.append('g').attr('transform', `translate(${IW + 12}, 8)`)
  ;[
    { col: 'rgba(239,68,68,0.5)', label: 'Alphabetical' },
    { col: '#4ade80',             label: 'test-order'   },
  ].forEach((item, i) => {
    leg.append('rect').attr('x', 0).attr('y', i * 20).attr('width', 12).attr('height', 10).attr('rx', 2).attr('fill', item.col)
    leg.append('text').attr('x', 17).attr('y', i * 20 + 9).attr('font-size', 10).attr('fill', '#94a3b8').text(item.label)
  })
  // Error bar legend
  leg.append('line').attr('x1', 3).attr('x2', 9).attr('y1', 50).attr('y2', 50)
    .attr('stroke', 'rgba(255,255,255,0.35)').attr('stroke-width', 1.5)
  leg.append('text').attr('x', 17).attr('y', 54).attr('font-size', 10).attr('fill', '#64748b').text('± std err')

  // Per-row bars + error bars
  DATA.forEach((d, ri) => {
    const rowDelay = 200 + ri * 130
    const yPos = y(d.repo)
    const bH   = y.bandwidth()
    const topH = bH * 0.42
    const botH = bH * 0.42
    const gap  = bH * 0.16

    // Repo label (left side)
    g.append('text').attr('x', -8).attr('y', yPos + bH / 2 + 4)
      .attr('text-anchor', 'end').attr('font-size', 11).attr('font-weight', 500)
      .attr('fill', '#94a3b8').attr('opacity', 0)
      .transition().delay(rowDelay).duration(250).attr('opacity', 1)
      .selection().text(d.repo)

    // ── Alpha bar (red) ───────────────────────────────────────────────────
    g.append('rect').attr('x', 0).attr('y', yPos).attr('height', topH).attr('rx', 3)
      .attr('fill', 'rgba(239,68,68,0.22)').attr('stroke', 'rgba(239,68,68,0.5)').attr('stroke-width', 1)
      .attr('width', 0)
      .transition().delay(rowDelay).duration(380).ease(d3.easeCubicOut)
      .attr('width', x(d.alpha))

    // Alpha error bar (whisker + cap)
    const alphaErrG = g.append('g').attr('opacity', 0)
    alphaErrG.transition().delay(rowDelay + 430).duration(200).attr('opacity', 1)
    const aMid = yPos + topH / 2
    alphaErrG.append('line')
      .attr('x1', x(d.alpha - d.alpha_se)).attr('x2', x(d.alpha + d.alpha_se))
      .attr('y1', aMid).attr('y2', aMid)
      .attr('stroke', 'rgba(239,68,68,0.8)').attr('stroke-width', 1.5)
    ;[-1, 1].forEach(dir => {
      alphaErrG.append('line')
        .attr('x1', x(d.alpha + dir * d.alpha_se)).attr('x2', x(d.alpha + dir * d.alpha_se))
        .attr('y1', aMid - 4).attr('y2', aMid + 4)
        .attr('stroke', 'rgba(239,68,68,0.8)').attr('stroke-width', 1.5)
    })

    // Alpha value label
    g.append('text').attr('x', x(d.alpha) + 6).attr('y', yPos + topH / 2 + 4)
      .attr('font-size', 10).attr('fill', 'rgba(239,68,68,0.7)').attr('opacity', 0)
      .text(`~${d.alpha}%`)
      .transition().delay(rowDelay + 550).duration(200).attr('opacity', 1)

    // ── Order bar (green) ─────────────────────────────────────────────────
    g.append('rect')
      .attr('x', 0).attr('y', yPos + topH + gap).attr('height', botH).attr('rx', 3)
      .attr('fill', 'rgba(74,222,128,0.18)').attr('stroke', 'rgba(74,222,128,0.6)').attr('stroke-width', 1)
      .attr('width', 0)
      .transition().delay(rowDelay + 150).duration(520).ease(d3.easeCubicOut)
      .attr('width', x(d.order))

    // Order error bar
    const orderErrG = g.append('g').attr('opacity', 0)
    orderErrG.transition().delay(rowDelay + 720).duration(200).attr('opacity', 1)
    const oMid = yPos + topH + gap + botH / 2
    orderErrG.append('line')
      .attr('x1', x(Math.max(0, d.order - d.order_se))).attr('x2', x(d.order + d.order_se))
      .attr('y1', oMid).attr('y2', oMid)
      .attr('stroke', 'rgba(74,222,128,0.8)').attr('stroke-width', 1.5)
    ;[-1, 1].forEach(dir => {
      const xv = d.order + dir * d.order_se
      if (xv < 0) return
      orderErrG.append('line')
        .attr('x1', x(xv)).attr('x2', x(xv))
        .attr('y1', oMid - 4).attr('y2', oMid + 4)
        .attr('stroke', 'rgba(74,222,128,0.8)').attr('stroke-width', 1.5)
    })

    // Dot at tip of green bar
    g.append('circle').attr('cx', x(d.order)).attr('cy', oMid)
      .attr('r', 0).attr('fill', '#4ade80')
      .transition().delay(rowDelay + 700).duration(200).ease(d3.easeBackOut.overshoot(2))
      .attr('r', 4)

    // Order value label
    g.append('text').attr('x', x(d.order) + 8).attr('y', oMid + 4)
      .attr('font-size', 11).attr('font-weight', 700).attr('fill', '#4ade80').attr('opacity', 0)
      .text(`~${d.order}%`)
      .transition().delay(rowDelay + 800).duration(200).attr('opacity', 1)

    // Test count (right margin)
    g.append('text').attr('x', IW + 8).attr('y', yPos + bH / 2 + 4)
      .attr('font-size', 10).attr('fill', '#64748b').attr('opacity', 0)
      .text(`${d.tests} tests`)
      .transition().delay(rowDelay + 200).duration(250).attr('opacity', 1)
  })
})
</script>

<style scoped>
.benchmark-chart { display: block; width: 100%; }
</style>
