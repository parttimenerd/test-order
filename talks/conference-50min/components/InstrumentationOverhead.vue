<template>
  <div ref="container" class="instrumentation-overhead" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'

const container = ref(null)

const W = 640, H = 160
const MARGIN = { top: 24, right: 60, bottom: 36, left: 130 }
const IW = W - MARGIN.left - MARGIN.right
const IH = H - MARGIN.top - MARGIN.bottom

const DATA = [
  { mode: 'CLASS',         learn: 13, order: 0 },
  { mode: 'METHOD',        learn: 11, order: 0 },
  { mode: 'MEMBER (default)', learn: 13, order: 0 },
]

onMounted(() => {
  const svg = d3.select(container.value)
    .append('svg')
    .attr('viewBox', `0 0 ${W} ${H}`)
    .attr('width', W).attr('height', H)
    .attr('font-family', "'Inter','Helvetica Neue',sans-serif")

  const g = svg.append('g').attr('transform', `translate(${MARGIN.left},${MARGIN.top})`)

  const y = d3.scaleBand().domain(DATA.map(d => d.mode))
    .range([0, IH]).padding(0.3)
  const x = d3.scaleLinear().domain([0, 20]).range([0, IW])

  // gridlines
  ;[5, 10, 15, 20].forEach(v => {
    g.append('line').attr('x1', x(v)).attr('x2', x(v)).attr('y1', 0).attr('y2', IH)
      .attr('stroke', 'rgba(255,255,255,0.07)').attr('stroke-dasharray', '2,3')
  })

  DATA.forEach(d => {
    const yPos = y(d.mode)
    const bH = y.bandwidth()
    const topH = bH * 0.44
    const botH = bH * 0.44
    const gap = bH * 0.12

    // Learn bar (blue)
    g.append('rect').attr('x', 0).attr('y', yPos)
      .attr('width', x(d.learn)).attr('height', topH).attr('rx', 3)
      .attr('fill', 'rgba(96,165,250,0.25)').attr('stroke', 'rgba(96,165,250,0.6)').attr('stroke-width', 1)
    g.append('text').attr('x', x(d.learn) + 5).attr('y', yPos + topH / 2 + 4)
      .attr('font-size', 11).attr('font-weight', 700).attr('fill', '#60a5fa')
      .text(`+${d.learn}%`)

    // Order bar (green — 0%)
    g.append('rect').attr('x', 0).attr('y', yPos + topH + gap)
      .attr('width', x(0.5)).attr('height', botH).attr('rx', 3)
      .attr('fill', 'rgba(74,222,128,0.12)').attr('stroke', 'rgba(74,222,128,0.4)').attr('stroke-width', 1)
    g.append('text').attr('x', x(0.5) + 5).attr('y', yPos + topH + gap + botH / 2 + 4)
      .attr('font-size', 11).attr('font-weight', 700).attr('fill', '#4ade80').text('0%')

    // Row label
    g.append('text').attr('x', -8).attr('y', yPos + bH / 2 + 4)
      .attr('text-anchor', 'end').attr('font-size', 11).attr('font-weight', d.mode.includes('default') ? 700 : 400)
      .attr('fill', d.mode.includes('default') ? '#fbbf24' : '#94a3b8').text(d.mode)
  })

  // x-axis
  const axisG = g.append('g').attr('transform', `translate(0,${IH + 5})`)
  ;[0, 5, 10, 15, 20].forEach(v => {
    axisG.append('text').attr('x', x(v)).attr('y', 14)
      .attr('text-anchor', 'middle').attr('font-size', 9).attr('fill', '#475569').text(`${v}%`)
  })
  axisG.append('text').attr('x', IW / 2).attr('y', 28)
    .attr('text-anchor', 'middle').attr('font-size', 10).attr('fill', '#334155')
    .text('overhead vs. uninstrumented suite')

  // Legend top-right
  const leg = g.append('g').attr('transform', `translate(${IW - 5}, 0)`)
  ;[
    { col: 'rgba(96,165,250,0.6)',  label: 'Learn run' },
    { col: 'rgba(74,222,128,0.5)', label: 'Order run' },
  ].forEach((item, i) => {
    leg.append('rect').attr('x', 10).attr('y', i * 17)
      .attr('width', 11).attr('height', 9).attr('rx', 2).attr('fill', item.col)
    leg.append('text').attr('x', 27).attr('y', i * 17 + 8)
      .attr('font-size', 10).attr('fill', '#94a3b8').text(item.label)
  })
})
</script>

<style scoped>
.instrumentation-overhead { display: block; width: 100%; }
</style>
