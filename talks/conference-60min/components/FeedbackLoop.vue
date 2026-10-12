<template>
  <svg ref="svg" class="feedback-loop" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'

const svg = ref(null)

const W = 1000, H = 320
const steps = [
  { id: 'edit',    label: 'Edit',           color: '#60a5fa', bg: 'rgba(30,58,138,0.85)',   y: H/2 },
  { id: 'commit',  label: 'Commit',         color: '#94a3b8', bg: 'rgba(30,41,59,0.85)',    y: H/2 },
  { id: 'push',    label: 'Push',           color: '#94a3b8', bg: 'rgba(30,41,59,0.85)',    y: H/2 },
  { id: 'wait',    label: '⏳ Wait…',       color: '#f97316', bg: 'rgba(124,45,18,0.90)',   y: H/2, highlight: true },
  { id: 'switch',  label: 'Context switch', color: '#f87171', bg: 'rgba(127,29,29,0.85)',   y: H/2 },
  { id: 'red',     label: '🔴 Finally red', color: '#f87171', bg: 'rgba(127,29,29,0.85)',   y: H/2 },
  { id: 'fix',     label: 'Fix → repeat',   color: '#4ade80', bg: 'rgba(20,83,45,0.85)',    y: H/2 },
]

const BOX_W = 118, BOX_H = 72, GAP = 22
const totalW = steps.length * BOX_W + (steps.length - 1) * GAP
const startX = (W - totalW) / 2 + BOX_W / 2

onMounted(() => {
  const root = d3.select(svg.value)
    .attr('viewBox', `0 0 ${W} ${H}`)
    .attr('width', '100%')
    .attr('preserveAspectRatio', 'xMidYMid meet')

  const defs = root.append('defs')
  const mkArr = (id, col) => defs.append('marker').attr('id', id)
    .attr('viewBox','0 0 10 10').attr('refX',9).attr('refY',5)
    .attr('markerWidth',5).attr('markerHeight',5).attr('orient','auto')
    .append('path').attr('d','M0,1 L9,5 L0,9 z').attr('fill', col)

  steps.forEach(s => mkArr('arr-' + s.id, s.color))

  // Draw arrows between boxes
  steps.forEach((s, i) => {
    if (i === steps.length - 1) return
    const x1 = startX + i * (BOX_W + GAP) + BOX_W / 2
    const x2 = startX + (i + 1) * (BOX_W + GAP) - BOX_W / 2
    const y = H / 2
    root.append('line')
      .attr('x1', x1).attr('y1', y)
      .attr('x2', x2 - 4).attr('y2', y)
      .attr('stroke', steps[i+1].color).attr('stroke-width', 2)
      .attr('marker-end', `url(#arr-${steps[i+1].id})`)
      .attr('opacity', 0.7)
  })

  // Draw boxes
  steps.forEach((s, i) => {
    const cx = startX + i * (BOX_W + GAP)
    const g = root.append('g')
      .attr('transform', `translate(${cx}, ${H/2})`)
      .attr('opacity', 0)

    g.transition().delay(i * 120).duration(300).attr('opacity', 1)

    const strokeW = s.highlight ? 2.5 : 1.5
    g.append('rect')
      .attr('x', -BOX_W/2).attr('y', -BOX_H/2)
      .attr('width', BOX_W).attr('height', BOX_H).attr('rx', 10)
      .attr('fill', s.bg).attr('stroke', s.color).attr('stroke-width', strokeW)

    if (s.highlight) {
      g.append('rect')
        .attr('x', -BOX_W/2 + 4).attr('y', -BOX_H/2 + 3)
        .attr('width', BOX_W - 8).attr('height', 4).attr('rx', 2)
        .attr('fill', s.color).attr('opacity', 0.5)
    }

    const lines = s.label.split('\n')
    lines.forEach((line, li) => {
      g.append('text')
        .attr('y', li * 20 - (lines.length - 1) * 10)
        .attr('text-anchor', 'middle')
        .attr('font-size', s.label.length > 12 ? 13 : 15)
        .attr('font-weight', s.highlight ? 700 : 500)
        .attr('fill', s.color)
        .text(line)
    })
  })

  // Return arrow at bottom
  const rx1 = startX + (steps.length - 1) * (BOX_W + GAP) + BOX_W / 2
  const rx2 = startX - BOX_W / 2
  const ry = H / 2 + BOX_H / 2 + 28
  root.append('path')
    .attr('d', `M${rx1},${H/2 + BOX_H/2} L${rx1},${ry} L${rx2},${ry} L${rx2},${H/2 + BOX_H/2 - 4}`)
    .attr('stroke', '#60a5fa').attr('stroke-width', 1.5).attr('fill', 'none')
    .attr('stroke-dasharray', '5,4').attr('opacity', 0.45)
    .attr('marker-end', 'url(#arr-edit)')
})
</script>

<style scoped>
.feedback-loop { display: block; width: 100%; }
</style>
