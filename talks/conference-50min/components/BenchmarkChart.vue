<template>
  <div ref="container" class="benchmark-chart" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'

const container = ref(null)

const W = 820, H = 240
const MARGIN = { top: 20, right: 140, bottom: 40, left: 160 }
const IW = W - MARGIN.left - MARGIN.right
const IH = H - MARGIN.top - MARGIN.bottom

const DATA = [
  { repo: 'javaparser',          tests: 568, alpha: 50, order: 87 },
  { repo: 'commons-collections', tests: 244, alpha: 50, order: 88 },
  { repo: 'commons-lang',        tests: 293, alpha: 50, order: 88 },
  { repo: 'jackson-core',        tests: 218, alpha: 50, order: 88 },
  { repo: 'spring-ai',           tests: 102, alpha: 50, order: 88 },
  { repo: 'logbook',             tests:  75, alpha: 50, order: 90 },
  { repo: 'spring-petclinic',    tests:  15, alpha: 50, order: 91 },
]

onMounted(() => {
  const svg = d3.select(container.value)
    .append('svg')
    .attr('viewBox', `0 0 ${W} ${H}`)
    .attr('width', W).attr('height', H)
    .attr('font-family', "'Inter','Helvetica Neue',sans-serif")

  const g = svg.append('g').attr('transform', `translate(${MARGIN.left},${MARGIN.top})`)

  const y = d3.scaleBand().domain(DATA.map(d => d.repo))
    .range([0, IH]).padding(0.35)
  const x = d3.scaleLinear().domain([0, 100]).range([0, IW])

  // grid lines at 25, 50, 75, 100
  ;[25, 50, 75, 100].forEach(v => {
    g.append('line').attr('x1', x(v)).attr('x2', x(v))
      .attr('y1', 0).attr('y2', IH)
      .attr('stroke', v === 50 ? 'rgba(239,68,68,0.3)' : 'rgba(255,255,255,0.06)')
      .attr('stroke-width', v === 50 ? 1.5 : 1)
      .attr('stroke-dasharray', v === 50 ? '4,3' : '2,3')
  })

  // "baseline" label at 50
  g.append('text').attr('x', x(50)).attr('y', -6)
    .attr('text-anchor', 'middle').attr('font-size', 10).attr('fill', '#f87171')
    .text('baseline ~50%')

  DATA.forEach(d => {
    const yPos = y(d.repo)
    const bH = y.bandwidth()
    const topH = bH * 0.42
    const botH = bH * 0.42
    const gap = bH * 0.16

    // ─ alpha bar (top, red) ─────────────────────────────────────────
    g.append('rect').attr('x', 0).attr('y', yPos)
      .attr('width', x(d.alpha)).attr('height', topH).attr('rx', 3)
      .attr('fill', 'rgba(239,68,68,0.25)').attr('stroke', 'rgba(239,68,68,0.5)').attr('stroke-width', 1)

    // ─ test-order bar (bottom, green) ──────────────────────────────
    g.append('rect').attr('x', 0).attr('y', yPos + topH + gap)
      .attr('width', x(d.order)).attr('height', botH).attr('rx', 3)
      .attr('fill', 'rgba(74,222,128,0.22)').attr('stroke', 'rgba(74,222,128,0.6)').attr('stroke-width', 1)

    // connector line showing improvement
    g.append('line').attr('x1', x(d.alpha)).attr('x2', x(d.order))
      .attr('y1', yPos + topH / 2).attr('y2', yPos + topH + gap + botH / 2)
      .attr('stroke', 'rgba(255,255,255,0.12)').attr('stroke-width', 1).attr('stroke-dasharray', '2,2')

    // dot at end of green bar
    g.append('circle').attr('cx', x(d.order)).attr('cy', yPos + topH + gap + botH / 2)
      .attr('r', 4).attr('fill', '#4ade80')

    // APFD value label after green bar
    g.append('text').attr('x', x(d.order) + 7).attr('y', yPos + topH + gap + botH / 2 + 4)
      .attr('font-size', 11).attr('font-weight', 700).attr('fill', '#4ade80')
      .text(`~${d.order}%`)

    // test count on right
    g.append('text').attr('x', IW + 8).attr('y', yPos + bH / 2 + 4)
      .attr('font-size', 10).attr('fill', '#64748b')
      .text(`${d.tests} tests`)

    // repo label on left
    g.append('text').attr('x', -8).attr('y', yPos + bH / 2 + 4)
      .attr('text-anchor', 'end').attr('font-size', 11).attr('font-weight', 500)
      .attr('fill', '#94a3b8').text(d.repo)
  })

  // x-axis labels
  const axisG = g.append('g').attr('transform', `translate(0,${IH + 5})`)
  ;[0, 25, 50, 75, 100].forEach(v => {
    axisG.append('text').attr('x', x(v)).attr('y', 14)
      .attr('text-anchor', 'middle').attr('font-size', 10).attr('fill', '#475569')
      .text(`${v}%`)
  })
  axisG.append('text').attr('x', IW / 2).attr('y', 28)
    .attr('text-anchor', 'middle').attr('font-size', 10).attr('fill', '#334155')
    .text('APFD — Average Percentage of Faults Detected')

  // Legend
  const leg = g.append('g').attr('transform', `translate(${IW - 10}, 0)`)
  ;[
    { col: 'rgba(239,68,68,0.5)', label: 'Alphabetical' },
    { col: '#4ade80',             label: 'test-order'   },
  ].forEach((item, i) => {
    leg.append('rect').attr('x', 10).attr('y', i * 18)
      .attr('width', 12).attr('height', 10).attr('rx', 2).attr('fill', item.col)
    leg.append('text').attr('x', 28).attr('y', i * 18 + 9)
      .attr('font-size', 10).attr('fill', '#94a3b8').text(item.label)
  })
})
</script>

<style scoped>
.benchmark-chart { display: block; width: 100%; }
</style>
