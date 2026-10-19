<template>
  <svg ref="svg" class="detect-deps-flow" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'
import { isDark } from '../../conference-60min/dark'

const svg = ref(null)

const W = 960, H = 260
const BOX_W = 150, BOX_H = 62, GAP = 14
const MID_Y = H / 2 + 10
const PHASE_GAP = 64

const P1_X = [0, 1, 2].map(i => 20 + BOX_W / 2 + i * (BOX_W + GAP))
const P2_X0 = P1_X[2] + BOX_W / 2 + PHASE_GAP + BOX_W / 2
const P2_X = [0, 1].map(i => P2_X0 + i * (BOX_W + GAP))

const PHASE1_DARK = [
  { label: 'Tuscan square',     sub: 'N orderings for N tests',       clr: '#a78bfa', bg: 'rgba(46,16,101,0.85)' },
  { label: 'Run each ordering', sub: 'record pass / fail per test',    clr: '#60a5fa', bg: 'rgba(30,58,138,0.85)' },
  { label: 'Compare outcomes',  sub: 'T fails in X, passes in Y?',     clr: '#fbbf24', bg: 'rgba(113,63,18,0.85)' },
]
const PHASE2_DARK = [
  { label: 'Exclusion probes',  sub: 'T alone, then with each pred.', clr: '#f97316', bg: 'rgba(124,45,18,0.85)' },
  { label: 'Classify',          sub: 'victim or brittle',              clr: '#4ade80', bg: 'rgba(20,83,45,0.85)' },
]
const PHASE1_LIGHT = [
  { label: 'Tuscan square',     sub: 'N orderings for N tests',       clr: '#7c3aed', bg: 'rgba(237,233,254,0.95)' },
  { label: 'Run each ordering', sub: 'record pass / fail per test',    clr: '#1d4ed8', bg: 'rgba(219,234,254,0.95)' },
  { label: 'Compare outcomes',  sub: 'T fails in X, passes in Y?',     clr: '#b45309', bg: 'rgba(254,249,195,0.95)' },
]
const PHASE2_LIGHT = [
  { label: 'Exclusion probes',  sub: 'T alone, then with each pred.', clr: '#c2410c', bg: 'rgba(255,237,213,0.95)' },
  { label: 'Classify',          sub: 'victim or brittle',              clr: '#15803d', bg: 'rgba(220,252,231,0.95)' },
]

onMounted(() => {
  const dark = isDark.value
  const PHASE1 = dark ? PHASE1_DARK : PHASE1_LIGHT
  const PHASE2 = dark ? PHASE2_DARK : PHASE2_LIGHT
  const subColor = dark ? '#94a3b8' : '#475569'

  const root = d3.select(svg.value)
    .attr('viewBox', `0 0 ${W} ${H}`)
    .attr('width', '100%')
    .attr('preserveAspectRatio', 'xMidYMid meet')
    .attr('font-family', "'Inter','Helvetica Neue',sans-serif")

  const defs = root.append('defs')
  const mkArr = (id, col) => defs.append('marker').attr('id', id)
    .attr('viewBox', '0 0 10 10').attr('refX', 9).attr('refY', 5)
    .attr('markerWidth', 5).attr('markerHeight', 5).attr('orient', 'auto')
    .append('path').attr('d', 'M0,1 L9,5 L0,9 z').attr('fill', col)
  ;[...PHASE1, ...PHASE2].forEach((s, i) => mkArr('da' + i, s.clr))
  mkArr('da-phase', PHASE2[0].clr)

  const PAD = 12
  const bandY = MID_Y - BOX_H / 2 - 22

  const p1BandFill   = dark ? 'rgba(139,92,246,0.07)' : 'rgba(139,92,246,0.05)'
  const p1BandBorder = dark ? 'rgba(139,92,246,0.2)' : 'rgba(139,92,246,0.25)'
  const p2BandFill   = dark ? 'rgba(249,115,22,0.07)' : 'rgba(249,115,22,0.05)'
  const p2BandBorder = dark ? 'rgba(249,115,22,0.2)' : 'rgba(249,115,22,0.25)'

  const p1x = P1_X[0] - BOX_W / 2 - PAD
  const p1w = P1_X[2] + BOX_W / 2 + PAD - p1x
  root.append('rect').attr('x', p1x).attr('y', bandY).attr('width', p1w).attr('height', BOX_H + 44).attr('rx', 12)
    .attr('fill', p1BandFill).attr('stroke', p1BandBorder).attr('stroke-width', 1)
  root.append('text').attr('x', p1x + p1w / 2).attr('y', bandY + 14)
    .attr('text-anchor', 'middle').attr('font-size', 10.5).attr('font-weight', 600)
    .attr('fill', PHASE1[0].clr).attr('opacity', 0.75).text('Phase 1 — Systematic coverage')

  const p2x = P2_X[0] - BOX_W / 2 - PAD
  const p2w = P2_X[1] + BOX_W / 2 + PAD - p2x
  root.append('rect').attr('x', p2x).attr('y', bandY).attr('width', p2w).attr('height', BOX_H + 44).attr('rx', 12)
    .attr('fill', p2BandFill).attr('stroke', p2BandBorder).attr('stroke-width', 1)
  root.append('text').attr('x', p2x + p2w / 2).attr('y', bandY + 14)
    .attr('text-anchor', 'middle').attr('font-size', 10.5).attr('font-weight', 600)
    .attr('fill', PHASE2[0].clr).attr('opacity', 0.75).text('Phase 2 — Targeted probes')

  const drawBox = (node, cx, delay) => {
    const g = root.append('g').attr('transform', `translate(${cx},${MID_Y})`).attr('opacity', 0)
    g.transition().delay(delay).duration(280).attr('opacity', 1)
    g.append('rect').attr('x', -BOX_W / 2).attr('y', -BOX_H / 2)
      .attr('width', BOX_W).attr('height', BOX_H).attr('rx', 9)
      .attr('fill', node.bg).attr('stroke', node.clr).attr('stroke-width', 1.8)
    g.append('text').attr('y', -8).attr('text-anchor', 'middle')
      .attr('font-size', 13.5).attr('font-weight', 700).attr('fill', node.clr).text(node.label)
    g.append('text').attr('y', 10).attr('text-anchor', 'middle')
      .attr('font-size', 11).attr('fill', subColor).attr('opacity', 0.9).text(node.sub)
  }

  const drawArrow = (x1, x2, col, idx, delay, dashed = false) => {
    const line = root.append('line')
      .attr('x1', x1).attr('y1', MID_Y).attr('x2', x2 - 4).attr('y2', MID_Y)
      .attr('stroke', col).attr('stroke-width', dashed ? 2.5 : 2)
      .attr('marker-end', `url(#da${idx})`).attr('opacity', 0)
    if (dashed) line.attr('stroke-dasharray', '6,4')
    line.transition().delay(delay).duration(200).attr('opacity', dashed ? 0.65 : 0.8)
  }

  PHASE1.forEach((node, i) => {
    drawBox(node, P1_X[i], i * 110)
    if (i < PHASE1.length - 1) {
      drawArrow(P1_X[i] + BOX_W / 2, P1_X[i + 1] - BOX_W / 2, PHASE1[i + 1].clr, i + 1, i * 110 + 70)
    }
  })

  const gapMidX = (P1_X[2] + BOX_W / 2 + P2_X[0] - BOX_W / 2) / 2
  root.append('line')
    .attr('x1', P1_X[2] + BOX_W / 2).attr('y1', MID_Y)
    .attr('x2', P2_X[0] - BOX_W / 2 - 5).attr('y2', MID_Y)
    .attr('stroke', PHASE2[0].clr).attr('stroke-width', 2.5).attr('stroke-dasharray', '6,4')
    .attr('marker-end', 'url(#da-phase)').attr('opacity', 0)
    .transition().delay(PHASE1.length * 110).duration(250).attr('opacity', 0.6)
  root.append('text').attr('x', gapMidX).attr('y', MID_Y - BOX_H / 2 - 28)
    .attr('text-anchor', 'middle').attr('font-size', 10).attr('fill', PHASE1[2].clr).attr('opacity', 0)
    .text('potential victim →')
    .transition().delay(PHASE1.length * 110 + 180).duration(200).attr('opacity', 0.8)

  PHASE2.forEach((node, i) => {
    drawBox(node, P2_X[i], PHASE1.length * 110 + 80 + i * 110)
    if (i < PHASE2.length - 1) {
      drawArrow(P2_X[i] + BOX_W / 2, P2_X[i + 1] - BOX_W / 2, PHASE2[i + 1].clr, PHASE1.length + i + 1, PHASE1.length * 110 + 150 + i * 110)
    }
  })
})
</script>

<style scoped>
.detect-deps-flow { display: block; width: 100%; }
</style>
