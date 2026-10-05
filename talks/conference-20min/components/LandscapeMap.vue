<template>
  <div ref="container" class="landscape-map" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'

const container = ref(null)

const W = 700, H = 440
const M = { top: 24, right: 24, bottom: 56, left: 72 }
const IW = W - M.left - M.right
const IH = H - M.top - M.bottom

// x = time-to-first-value (0=immediate, 10=months)
// y = prioritization intelligence (0=none, 10=high)
const TOOLS = [
  { name: 'Random / shuffle',              x: 0.4, y: 1.2, col: '#94a3b8', anchor: 'right' },
  { name: 'Stop-on-first-failure',         x: 0.6, y: 2.2, col: '#fda4af', anchor: 'right' },
  { name: 'Manual @Order',                 x: 2.2, y: 3.8, col: '#fde68a', anchor: 'above' },
  { name: 'Coverage-based\n(Skippy, OpenClover)', x: 4.2, y: 6.2, col: '#86efac', anchor: 'above' },
  { name: 'Cloud TIA\n(Launchable, Develocity)',  x: 8.2, y: 8.8, col: '#93c5fd', anchor: 'left'  },
  { name: 'test-order',                    x: 1.2, y: 7.2, col: '#fbbf24', anchor: 'right', highlight: true },
]

onMounted(() => {
  const svg = d3.select(container.value)
    .append('svg')
    .attr('viewBox', `0 0 ${W} ${H}`)
    .attr('width', W).attr('height', H)
    .attr('font-family', "'Inter','Helvetica Neue',sans-serif")

  const g = svg.append('g').attr('transform', `translate(${M.left},${M.top})`)

  const x = d3.scaleLinear().domain([0, 10]).range([0, IW])
  const y = d3.scaleLinear().domain([0, 10]).range([IH, 0])

  // Soft region fills
  const regions = [
    { x1: 0, y1: 5, x2: 5, y2: 10, col: 'rgba(251,191,36,0.05)' },  // top-left: low friction, smart
    { x1: 5, y1: 5, x2: 10, y2: 10, col: 'rgba(147,197,253,0.04)' }, // top-right: powerful, high setup
    { x1: 0, y1: 0, x2: 5, y2: 5,  col: 'rgba(148,163,184,0.03)' },  // bottom-left
    { x1: 5, y1: 0, x2: 10, y2: 5, col: 'rgba(148,163,184,0.03)' },  // bottom-right
  ]
  regions.forEach(r => {
    g.append('rect')
      .attr('x', x(r.x1)).attr('y', y(r.y2))
      .attr('width', x(r.x2) - x(r.x1))
      .attr('height', y(r.y1) - y(r.y2))
      .attr('fill', r.col)
  })

  // Subtle grid
  ;[2, 4, 6, 8].forEach(v => {
    g.append('line').attr('x1', x(v)).attr('x2', x(v)).attr('y1', 0).attr('y2', IH)
      .attr('stroke', 'rgba(255,255,255,0.04)').attr('stroke-dasharray', '2,4')
    g.append('line').attr('x1', 0).attr('x2', IW).attr('y1', y(v)).attr('y2', y(v))
      .attr('stroke', 'rgba(255,255,255,0.04)').attr('stroke-dasharray', '2,4')
  })

  // Mid dividers (slightly more visible)
  g.append('line').attr('x1', x(5)).attr('x2', x(5)).attr('y1', 0).attr('y2', IH)
    .attr('stroke', 'rgba(255,255,255,0.10)').attr('stroke-dasharray', '4,5')
  g.append('line').attr('x1', 0).attr('x2', IW).attr('y1', y(5)).attr('y2', y(5))
    .attr('stroke', 'rgba(255,255,255,0.10)').attr('stroke-dasharray', '4,5')

  // Axes
  g.append('line').attr('x1', 0).attr('x2', IW).attr('y1', IH).attr('y2', IH)
    .attr('stroke', '#334155').attr('stroke-width', 1.5)
  g.append('line').attr('x1', 0).attr('x2', 0).attr('y1', 0).attr('y2', IH)
    .attr('stroke', '#334155').attr('stroke-width', 1.5)

  // Axis labels
  g.append('text').attr('x', IW / 2).attr('y', IH + 42)
    .attr('text-anchor', 'middle').attr('font-size', 12).attr('fill', '#475569')
    .text('Time to first value  →')

  g.append('text')
    .attr('transform', `translate(-52, ${IH / 2}) rotate(-90)`)
    .attr('text-anchor', 'middle').attr('font-size', 12).attr('fill', '#475569')
    .text('↑  Prioritization intelligence')

  // X axis tick labels
  ;[['Immediate', 0], ['Days', 3.3], ['Weeks', 6.6], ['Months', 10]].forEach(([label, v]) => {
    g.append('text').attr('x', x(v)).attr('y', IH + 18)
      .attr('text-anchor', 'middle').attr('font-size', 9).attr('fill', '#334155')
      .text(label)
  })

  // Region label: top-left
  g.append('text').attr('x', x(0.3)).attr('y', y(9.6))
    .attr('font-size', 9).attr('fill', 'rgba(251,191,36,0.30)')
    .attr('font-style', 'italic')
    .text('low friction, intelligent')

  // Region label: top-right
  g.append('text').attr('x', x(5.4)).attr('y', y(9.6))
    .attr('font-size', 9).attr('fill', 'rgba(147,197,253,0.25)')
    .attr('font-style', 'italic')
    .text('powerful at scale')

  // Plot tools
  TOOLS.forEach((d, i) => {
    const cx = x(d.x), cy = y(d.y)
    const r = d.highlight ? 9 : 6.5
    const delay = 250 + i * 130

    // Glow ring for highlighted tool
    if (d.highlight) {
      g.append('circle').attr('cx', cx).attr('cy', cy).attr('r', 0)
        .attr('fill', 'none').attr('stroke', d.col).attr('stroke-width', 1.5).attr('opacity', 0.25)
        .transition().delay(delay).duration(450).attr('r', 20)
    }

    // Dot
    g.append('circle').attr('cx', cx).attr('cy', cy).attr('r', 0)
      .attr('fill', d.highlight ? d.col : d.col + '80')
      .attr('stroke', d.col).attr('stroke-width', d.highlight ? 1.5 : 1)
      .transition().delay(delay).duration(300).attr('r', r)

    // Label lines
    const lines = d.name.split('\n')
    const lx = d.anchor === 'left'  ? cx - r - 7  :
               d.anchor === 'right' ? cx + r + 7  : cx
    const baseY = d.anchor === 'above' ? cy - r - 7 - (lines.length - 1) * 13
                : d.anchor === 'below' ? cy + r + 15
                : cy + 4
    const textAnchor = d.anchor === 'left' ? 'end' : d.anchor === 'right' ? 'start' : 'middle'

    const lbl = g.append('text')
      .attr('x', lx).attr('y', baseY)
      .attr('text-anchor', textAnchor)
      .attr('font-size', d.highlight ? 12 : 10)
      .attr('font-weight', d.highlight ? 600 : 400)
      .attr('fill', d.col)
      .attr('opacity', 0)

    lines.forEach((line, li) => {
      lbl.append('tspan').attr('x', lx).attr('dy', li === 0 ? 0 : 13).text(line)
    })

    lbl.transition().delay(delay + 220).duration(250).attr('opacity', 1)
  })
})
</script>

<style scoped>
.landscape-map { display: block; width: 100%; }
</style>
