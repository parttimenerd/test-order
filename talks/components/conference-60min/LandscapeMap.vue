<template>
  <div ref="container" class="landscape-map" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'
import { isDark } from '../../conference-60min/dark'

const container = ref(null)

const W = 900, H = 520
const M = { top: 28, right: 40, bottom: 72, left: 104 }
const IW = W - M.left - M.right
const IH = H - M.top - M.bottom

const TOOLS = [
  { name: 'Alphabetical\n(default)',              x: 0.2, y: 0.5, col: '#64748b', anchor: 'right' },
  { name: 'Random / shuffle',                     x: 0.4, y: 1.0, col: '#94a3b8', anchor: 'right' },
  { name: 'Manual @Order',                        x: 3.5, y: 2.5, col: '#fde68a', anchor: 'right' },
  { name: 'Coverage-based\n(re-instrument/run)',  x: 2.0, y: 4.8, col: '#86efac', anchor: 'right' },
  { name: 'Coverage-based\n(stored index/Skippy)', x: 2.5, y: 5.6, col: '#6ee7b7', anchor: 'right' },
  { name: 'Cloud TIA\n(Launchable, Develocity)',  x: 7.0, y: 8.0, col: '#93c5fd', anchor: 'left'  },
  { name: 'test-order',                           x: 1.0, y: 7.5, col: '#fbbf24', anchor: 'right', highlight: true },
]

onMounted(() => {
  const dark = isDark.value
  const textHi   = dark ? '#cbd5e1' : '#1e293b'
  const textMid  = dark ? '#94a3b8' : '#475569'
  const gridLine = dark ? 'rgba(255,255,255,0.04)' : 'rgba(0,0,0,0.06)'
  const midDiv   = dark ? 'rgba(255,255,255,0.18)' : 'rgba(0,0,0,0.18)'
  const axisClr  = dark ? '#475569' : '#94a3b8'
  const r1Label  = dark ? 'rgba(251,191,36,0.55)' : 'rgba(180,83,9,0.65)'
  const r2Label  = dark ? 'rgba(147,197,253,0.50)' : 'rgba(37,99,235,0.55)'

  const svg = d3.select(container.value)
    .append('svg')
    .attr('viewBox', `0 0 ${W} ${H}`)
    .attr('width', W).attr('height', H)
    .attr('font-family', "'Inter','Helvetica Neue',sans-serif")
    .style('font-size', '1px')

  const g = svg.append('g').attr('transform', `translate(${M.left},${M.top})`)

  const x = d3.scaleLinear().domain([0, 10]).range([0, IW])
  const y = d3.scaleLinear().domain([0, 10]).range([IH, 0])

  const regions = [
    { x1: 0, y1: 5, x2: 5, y2: 10, col: dark ? 'rgba(251,191,36,0.05)' : 'rgba(251,191,36,0.06)' },
    { x1: 5, y1: 5, x2: 10, y2: 10, col: dark ? 'rgba(147,197,253,0.04)' : 'rgba(147,197,253,0.06)' },
    { x1: 0, y1: 0, x2: 5, y2: 5,  col: dark ? 'rgba(148,163,184,0.03)' : 'rgba(148,163,184,0.04)' },
    { x1: 5, y1: 0, x2: 10, y2: 5, col: dark ? 'rgba(148,163,184,0.03)' : 'rgba(148,163,184,0.04)' },
  ]
  regions.forEach(r => {
    g.append('rect')
      .attr('x', x(r.x1)).attr('y', y(r.y2))
      .attr('width', x(r.x2) - x(r.x1))
      .attr('height', y(r.y1) - y(r.y2))
      .attr('fill', r.col)
  })

  ;[2, 4, 6, 8].forEach(v => {
    g.append('line').attr('x1', x(v)).attr('x2', x(v)).attr('y1', 0).attr('y2', IH)
      .attr('stroke', gridLine).attr('stroke-dasharray', '2,4')
    g.append('line').attr('x1', 0).attr('x2', IW).attr('y1', y(v)).attr('y2', y(v))
      .attr('stroke', gridLine).attr('stroke-dasharray', '2,4')
  })

  g.append('line').attr('x1', x(5)).attr('x2', x(5)).attr('y1', 0).attr('y2', IH)
    .attr('stroke', midDiv).attr('stroke-dasharray', '4,5')
  g.append('line').attr('x1', 0).attr('x2', IW).attr('y1', y(5)).attr('y2', y(5))
    .attr('stroke', midDiv).attr('stroke-dasharray', '4,5')

  g.append('line').attr('x1', 0).attr('x2', IW).attr('y1', IH).attr('y2', IH)
    .attr('stroke', axisClr).attr('stroke-width', 1.5)
  g.append('line').attr('x1', 0).attr('x2', 0).attr('y1', 0).attr('y2', IH)
    .attr('stroke', axisClr).attr('stroke-width', 1.5)

  g.append('text').attr('x', IW / 2).attr('y', IH + 58)
    .attr('text-anchor', 'middle').style('font-size', '18px').attr('fill', textHi)
    .text('Time to first value  →')

  g.append('text')
    .attr('transform', `translate(-76, ${IH / 2}) rotate(-90)`)
    .attr('text-anchor', 'middle').style('font-size', '18px').attr('fill', textHi)
    .text('Prioritization intelligence')

  g.append('text')
    .attr('x', -76).attr('y', -10)
    .attr('text-anchor', 'middle').style('font-size', '18px').attr('fill', textHi)
    .text('↑')

  ;[['Immediate', 0], ['Days', 3.3], ['Weeks', 6.6], ['Months', 10]].forEach(([label, v]) => {
    g.append('text').attr('x', x(v)).attr('y', IH + 24)
      .attr('text-anchor', 'middle').style('font-size', '14px').attr('fill', textMid)
      .text(label)
  })

  g.append('text').attr('x', x(0.3)).attr('y', y(9.6))
    .style('font-size', '14px').attr('fill', r1Label)
    .attr('font-style', 'italic')
    .text('low friction, intelligent')

  g.append('text').attr('x', x(5.4)).attr('y', y(9.6))
    .style('font-size', '14px').attr('fill', r2Label)
    .attr('font-style', 'italic')
    .text('powerful at scale')

  TOOLS.forEach((d, i) => {
    const cx = x(d.x), cy = y(d.y)
    const r = d.highlight ? 12 : 9
    const delay = 250 + i * 130

    if (d.highlight) {
      g.append('circle').attr('cx', cx).attr('cy', cy).attr('r', 0)
        .attr('fill', 'none').attr('stroke', d.col).attr('stroke-width', 1.5).attr('opacity', 0.25)
        .transition().delay(delay).duration(450).attr('r', 26)
    }

    g.append('circle').attr('cx', cx).attr('cy', cy).attr('r', 0)
      .attr('fill', d.highlight ? d.col : d.col + '80')
      .attr('stroke', d.col).attr('stroke-width', d.highlight ? 1.5 : 1)
      .transition().delay(delay).duration(300).attr('r', r)

    const lines = d.name.split('\n')
    const lx = d.anchor === 'left' ? cx - r - 10 : cx + r + 10
    const lineH = 19
    const baseY = cy - ((lines.length - 1) * lineH) / 2 + 5
    const textAnchor = d.anchor === 'left' ? 'end' : 'start'

    const lbl = g.append('text')
      .attr('x', lx).attr('y', baseY)
      .attr('text-anchor', textAnchor)
      .attr('font-size', d.highlight ? 18 : 15)
      .attr('font-weight', d.highlight ? 700 : 400)
      .attr('fill', d.col)
      .attr('opacity', 0)

    lines.forEach((line, li) => {
      lbl.append('tspan').attr('x', lx).attr('dy', li === 0 ? 0 : lineH).text(line)
    })

    lbl.transition().delay(delay + 220).duration(250).attr('opacity', 1)
  })
})
</script>

<style scoped>
.landscape-map { display: block; width: 100%; }
</style>
