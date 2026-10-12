<template>
  <div ref="container" class="staleness-flow" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'

const container = ref(null)

const W = 820, H = 320
const ROW_H = 72, GAP = 18
const LBL_W = 130, BOX_W = 168, BOX_R = 8
const ARROW_W = 36

// Three rows: every-run, dep-change, degrade
const ROWS = [
  {
    tag: 'EVERY RUN',
    tagClr: '#60a5fa',
    bg: 'rgba(30,58,138,0.50)',
    border: '#60a5fa',
    nodes: [
      { label: '.class files',     sub: 'opcode hash' },
      { label: 'diff hashes',      sub: 'vs. learn' },
      { label: 'widen changed',    sub: 'no re-learn' },
    ],
    arrowClr: '#60a5fa',
    outcome: { text: 'auto-detected', clr: '#60a5fa' },
  },
  {
    tag: 'DEP CHANGE',
    tagClr: '#fbbf24',
    bg: 'rgba(92,52,14,0.50)',
    border: '#fbbf24',
    nodes: [
      { label: 'pom.xml /\nbuild.gradle', sub: 'fingerprint' },
      { label: 'fingerprint\nmismatch',   sub: 'detected' },
      { label: 'auto learn\nmode',         sub: 'next run' },
    ],
    arrowClr: '#fbbf24',
    outcome: { text: 're-learns once', clr: '#fbbf24' },
  },
  {
    tag: 'UNKNOWN',
    tagClr: '#4ade80',
    bg: 'rgba(6,78,59,0.50)',
    border: '#4ade80',
    nodes: [
      { label: 'new class\nnot in index', sub: 'gap' },
      { label: 'include all\nits tests',  sub: 'safe fallback' },
      { label: 'never skip\na test',       sub: 'guaranteed' },
    ],
    arrowClr: '#4ade80',
    outcome: { text: 'over-approximate', clr: '#4ade80' },
  },
]

const INNER_W = W - LBL_W - 16   // available for nodes + arrows
const NODE_STRIDE = (INNER_W - 24) / 3   // stride between node centres
const NODE_X = (i) => LBL_W + 24 + i * NODE_STRIDE + NODE_STRIDE / 2

onMounted(() => {
  const svg = d3.select(container.value)
    .append('svg')
    .attr('viewBox', `0 0 ${W} ${H}`)
    .attr('width', W).attr('height', H)
    .attr('font-family', "'Inter','Helvetica Neue',sans-serif")

  const defs = svg.append('defs')

  ROWS.forEach((row, ri) => {
    const y = ri * (ROW_H + GAP)
    const cy = y + ROW_H / 2
    const delay = 80 + ri * 180

    // Tag pill
    svg.append('rect')
      .attr('x', 0).attr('y', y + 6).attr('width', LBL_W - 8).attr('height', ROW_H - 12)
      .attr('rx', 6).attr('fill', row.bg).attr('stroke', row.border).attr('stroke-width', 1.5)
      .attr('opacity', 0).transition().delay(delay).duration(200).attr('opacity', 1)

    svg.append('text')
      .attr('x', (LBL_W - 8) / 2).attr('y', cy + 5)
      .attr('text-anchor', 'middle').attr('font-size', 12).attr('font-weight', 700)
      .attr('fill', row.tagClr).attr('opacity', 0)
      .text(row.tag)
      .transition().delay(delay).duration(200).attr('opacity', 1)

    // Nodes + arrows
    row.nodes.forEach((node, ni) => {
      const nx = NODE_X(ni)
      const nodeDelay = delay + 60 + ni * 120

      // Arrow between nodes
      if (ni < row.nodes.length - 1) {
        const x1 = nx + BOX_W / 2 + 3
        const x2 = NODE_X(ni + 1) - BOX_W / 2 - 3
        const arrowDelay = nodeDelay + 80

        // marker
        const markId = `arr-s-${ri}-${ni}`
        defs.append('marker').attr('id', markId)
          .attr('viewBox', '0 0 8 8').attr('refX', 7).attr('refY', 4)
          .attr('markerWidth', 5).attr('markerHeight', 5).attr('orient', 'auto')
          .append('path').attr('d', 'M0,1 L7,4 L0,7 z').attr('fill', row.arrowClr)

        svg.append('line')
          .attr('x1', x1).attr('y1', cy).attr('x2', x1).attr('y2', cy)
          .attr('stroke', row.arrowClr).attr('stroke-width', 2).attr('opacity', 0.7)
          .attr('marker-end', `url(#${markId})`)
          .transition().delay(arrowDelay).duration(250).ease(d3.easeLinear)
          .attr('x2', x2)
      }

      // Box
      const g = svg.append('g')
        .attr('transform', `translate(${nx},${cy}) scale(0)`)
        .attr('opacity', 0)
      g.transition().delay(nodeDelay).duration(260).ease(d3.easeBackOut.overshoot(1.3))
        .attr('transform', `translate(${nx},${cy}) scale(1)`)
        .attr('opacity', 1)

      g.append('rect')
        .attr('x', -BOX_W / 2).attr('y', -ROW_H / 2 + 6)
        .attr('width', BOX_W).attr('height', ROW_H - 12).attr('rx', BOX_R)
        .attr('fill', row.bg).attr('stroke', row.border).attr('stroke-width', 1.5)

      // label (may contain \n)
      const lines = node.label.split('\n')
      const lineH = 14
      const totalH = lines.length * lineH
      lines.forEach((ln, li) => {
        g.append('text')
          .attr('y', -totalH / 2 + li * lineH + 2)
          .attr('text-anchor', 'middle').attr('font-size', 12).attr('font-weight', 700)
          .attr('fill', row.tagClr).text(ln)
      })

      g.append('text')
        .attr('y', ROW_H / 2 - 14)
        .attr('text-anchor', 'middle').attr('font-size', 9.5)
        .attr('fill', '#64748b').text(node.sub)
    })

    // Outcome badge on right
    const outcomeX = W - 4
    const outcomeDelay = delay + 60 + row.nodes.length * 120 + 100
    svg.append('text')
      .attr('x', outcomeX).attr('y', cy + 5)
      .attr('text-anchor', 'end').attr('font-size', 11).attr('font-weight', 600)
      .attr('fill', row.outcome.clr).attr('opacity', 0)
      .text(`→ ${row.outcome.text}`)
      .transition().delay(outcomeDelay).duration(200).attr('opacity', 1)
  })
})
</script>

<style scoped>
.staleness-flow { display: block; width: 100%; }
</style>
