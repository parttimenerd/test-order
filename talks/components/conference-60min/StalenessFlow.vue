<template>
  <div ref="container" class="staleness-flow" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'
import { isDark } from '../../conference-60min/dark'

const container = ref(null)

const W = 860, H = 290
const ROW_H = 68, GAP = 16
const TAG_W = 112, TAG_PAD = 10
const BOX_W = 152, BOX_H = 52, BOX_R = 8
const OUTCOME_W = 130

const INNER_W = W - TAG_W - TAG_PAD - OUTCOME_W - 8
const NODE_COLS = 3
const NODE_STRIDE = INNER_W / NODE_COLS
const NODE_X = (i) => TAG_W + TAG_PAD + (i + 0.5) * NODE_STRIDE

const ROWS_DARK = [
  {
    tag: 'EVERY RUN',
    tagClr: '#60a5fa',
    bg: 'rgba(30,58,138,0.50)',
    border: '#60a5fa',
    nodes: [
      { label: '.class files',    sub: 'opcode hash' },
      { label: 'diff hashes',     sub: 'vs. learn' },
      { label: 'widen changed',   sub: 'no re-learn' },
    ],
    arrowClr: '#60a5fa',
    outcome: { text: 'auto-detected', clr: '#60a5fa' },
    subClr: '#64748b',
  },
  {
    tag: 'DEP CHANGE',
    tagClr: '#fbbf24',
    bg: 'rgba(92,52,14,0.50)',
    border: '#fbbf24',
    nodes: [
      { label: 'pom.xml /\nbuild.gradle', sub: 'fingerprint' },
      { label: 'fingerprint\nmismatch',   sub: 'detected' },
      { label: 'auto learn\nnext run',    sub: 'transparent' },
    ],
    arrowClr: '#fbbf24',
    outcome: { text: 're-learns once', clr: '#fbbf24' },
    subClr: '#64748b',
  },
  {
    tag: 'UNKNOWN',
    tagClr: '#4ade80',
    bg: 'rgba(6,78,59,0.50)',
    border: '#4ade80',
    nodes: [
      { label: 'new class\nnot in index', sub: 'gap' },
      { label: 'include all\npossible tests', sub: 'safe fallback' },
      { label: 'never skip\na test',      sub: 'guaranteed' },
    ],
    arrowClr: '#4ade80',
    outcome: { text: 'over-approximate', clr: '#4ade80' },
    subClr: '#64748b',
  },
]
const ROWS_LIGHT = [
  {
    tag: 'EVERY RUN',
    tagClr: '#1d4ed8',
    bg: 'rgba(219,234,254,0.60)',
    border: '#1d4ed8',
    nodes: [
      { label: '.class files',    sub: 'opcode hash' },
      { label: 'diff hashes',     sub: 'vs. learn' },
      { label: 'widen changed',   sub: 'no re-learn' },
    ],
    arrowClr: '#1d4ed8',
    outcome: { text: 'auto-detected', clr: '#1d4ed8' },
    subClr: '#374151',
  },
  {
    tag: 'DEP CHANGE',
    tagClr: '#b45309',
    bg: 'rgba(254,249,195,0.60)',
    border: '#b45309',
    nodes: [
      { label: 'pom.xml /\nbuild.gradle', sub: 'fingerprint' },
      { label: 'fingerprint\nmismatch',   sub: 'detected' },
      { label: 'auto learn\nnext run',    sub: 'transparent' },
    ],
    arrowClr: '#b45309',
    outcome: { text: 're-learns once', clr: '#b45309' },
    subClr: '#374151',
  },
  {
    tag: 'UNKNOWN',
    tagClr: '#15803d',
    bg: 'rgba(220,252,231,0.60)',
    border: '#15803d',
    nodes: [
      { label: 'new class\nnot in index', sub: 'gap' },
      { label: 'include all\npossible tests', sub: 'safe fallback' },
      { label: 'never skip\na test',      sub: 'guaranteed' },
    ],
    arrowClr: '#15803d',
    outcome: { text: 'over-approximate', clr: '#15803d' },
    subClr: '#374151',
  },
]

onMounted(() => {
  const dark = isDark.value
  const ROWS = dark ? ROWS_DARK : ROWS_LIGHT

  const svg = d3.select(container.value)
    .append('svg')
    .attr('viewBox', `0 0 ${W} ${H}`)
    .attr('width', W).attr('height', H)
    .attr('font-family', "'Inter','Helvetica Neue',sans-serif")

  const defs = svg.append('defs')

  ROWS.forEach((row, ri) => {
    const rowY = ri * (ROW_H + GAP)
    const cy = rowY + ROW_H / 2
    const delay = 80 + ri * 180

    svg.append('rect')
      .attr('x', 0).attr('y', rowY + 6)
      .attr('width', TAG_W - 4).attr('height', ROW_H - 12)
      .attr('rx', 6).attr('fill', row.bg).attr('stroke', row.border).attr('stroke-width', 1.5)
      .attr('opacity', 0).transition().delay(delay).duration(200).attr('opacity', 1)

    svg.append('text')
      .attr('x', (TAG_W - 4) / 2).attr('y', cy + 5)
      .attr('text-anchor', 'middle').attr('font-size', 12).attr('font-weight', 700)
      .attr('fill', row.tagClr).attr('opacity', 0)
      .text(row.tag)
      .transition().delay(delay).duration(200).attr('opacity', 1)

    row.nodes.forEach((node, ni) => {
      const nx = NODE_X(ni)
      const nodeDelay = delay + 60 + ni * 110

      if (ni < row.nodes.length - 1) {
        const x1 = nx + BOX_W / 2 + 3
        const x2 = NODE_X(ni + 1) - BOX_W / 2 - 3
        const arrowDelay = nodeDelay + 70
        const markId = `arr-sf-${ri}-${ni}`

        defs.append('marker').attr('id', markId)
          .attr('viewBox', '0 0 8 8').attr('refX', 7).attr('refY', 4)
          .attr('markerWidth', 5).attr('markerHeight', 5).attr('orient', 'auto')
          .append('path').attr('d', 'M0,1 L7,4 L0,7 z').attr('fill', row.arrowClr)

        svg.append('line')
          .attr('x1', x1).attr('y1', cy).attr('x2', x1).attr('y2', cy)
          .attr('stroke', row.arrowClr).attr('stroke-width', 1.8).attr('opacity', 0.7)
          .attr('marker-end', `url(#${markId})`)
          .transition().delay(arrowDelay).duration(220).ease(d3.easeLinear)
          .attr('x2', x2)
      }

      const g = svg.append('g')
        .attr('transform', `translate(${nx},${cy}) scale(0)`)
        .attr('opacity', 0)
      g.transition().delay(nodeDelay).duration(240).ease(d3.easeBackOut.overshoot(1.3))
        .attr('transform', `translate(${nx},${cy}) scale(1)`)
        .attr('opacity', 1)

      g.append('rect')
        .attr('x', -BOX_W / 2).attr('y', -BOX_H / 2)
        .attr('width', BOX_W).attr('height', BOX_H).attr('rx', BOX_R)
        .attr('fill', row.bg).attr('stroke', row.border).attr('stroke-width', 1.5)

      const lines = node.label.split('\n')
      const lineH = 14
      const totalTextH = lines.length * lineH
      lines.forEach((ln, li) => {
        g.append('text')
          .attr('y', -totalTextH / 2 + li * lineH + 5)
          .attr('text-anchor', 'middle').attr('font-size', 12).attr('font-weight', 700)
          .attr('fill', row.tagClr).text(ln)
      })

      g.append('text')
        .attr('y', BOX_H / 2 - 6)
        .attr('text-anchor', 'middle').attr('font-size', 10)
        .attr('fill', row.subClr).text(node.sub)
    })

    const lastNx = NODE_X(row.nodes.length - 1)
    const arrowX1 = lastNx + BOX_W / 2 + 3
    const arrowX2 = W - OUTCOME_W - 2
    const outcomeDelay = delay + 60 + row.nodes.length * 110 + 80
    const markIdOut = `arr-sf-out-${ri}`
    defs.append('marker').attr('id', markIdOut)
      .attr('viewBox', '0 0 8 8').attr('refX', 7).attr('refY', 4)
      .attr('markerWidth', 5).attr('markerHeight', 5).attr('orient', 'auto')
      .append('path').attr('d', 'M0,1 L7,4 L0,7 z').attr('fill', row.arrowClr)

    svg.append('line')
      .attr('x1', arrowX1).attr('y1', cy).attr('x2', arrowX1).attr('y2', cy)
      .attr('stroke', row.arrowClr).attr('stroke-width', 1.8).attr('opacity', 0.6)
      .attr('marker-end', `url(#${markIdOut})`)
      .transition().delay(outcomeDelay).duration(180).ease(d3.easeLinear)
      .attr('x2', arrowX2)

    svg.append('text')
      .attr('x', W - OUTCOME_W + 6).attr('y', cy + 5)
      .attr('text-anchor', 'start').attr('font-size', 13).attr('font-weight', 700)
      .attr('fill', row.outcome.clr).attr('opacity', 0)
      .text(row.outcome.text)
      .transition().delay(outcomeDelay + 180).duration(200).attr('opacity', 1)
  })
})
</script>

<style scoped>
.staleness-flow { display: block; width: 100%; }
</style>
