<template>
  <svg ref="svg" class="agentic-tdd-flow" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'
import { isDark } from '../../conference-60min/dark'

const svg = ref(null)

const W = 980, H = 280

const STEPS = [
  { id: 'agent',    emoji: '🤖', label: 'AI agent',        sub: 'writes implementation',  clr: '#60a5fa', darkBg: 'rgba(30,58,138,0.88)',  lightBg: 'rgba(219,234,254,0.95)',  darkSub: '#94a3b8', lightSub: '#4b5563' },
  { id: 'commit',   emoji: '📦', label: 'commit + PR',      sub: 'pushes to branch',       clr: '#a78bfa', darkBg: 'rgba(46,16,101,0.88)',  lightBg: 'rgba(237,233,254,0.95)',  darkSub: '#94a3b8', lightSub: '#4b5563' },
  { id: 'ci',       emoji: '⚙️', label: 'CI runs',          sub: 'mvn test -Dtestorder.tdd=true', clr: '#fbbf24', darkBg: 'rgba(92,52,14,0.88)', lightBg: 'rgba(254,249,195,0.95)',  darkSub: '#94a3b8', lightSub: '#4b5563' },
  { id: 'block',    emoji: '🚫', label: 'TDD VIOLATION',    sub: 'BUILD FAILURE',          clr: '#f87171', darkBg: 'rgba(127,29,29,0.92)',  lightBg: 'rgba(254,226,226,0.95)',  darkSub: '#fca5a5', lightSub: '#dc2626' },
  { id: 'test',     emoji: '✍️', label: 'write failing test', sub: 'red first, then green', clr: '#4ade80', darkBg: 'rgba(20,83,45,0.88)',  lightBg: 'rgba(220,252,231,0.95)',  darkSub: '#86efac', lightSub: '#15803d' },
]

const BOX_W = 148, BOX_H = 86
const PAD = 14
const totalW = STEPS.length * BOX_W + (STEPS.length - 1) * PAD
const startX = (W - totalW) / 2 + BOX_W / 2

onMounted(() => {
  const dark = isDark.value

  const textColor   = dark ? '#e2e8f0' : '#1e293b'
  const gridStroke  = dark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.08)'

  const root = d3.select(svg.value)
    .attr('viewBox', `0 0 ${W} ${H}`)
    .attr('width', '100%')
    .attr('preserveAspectRatio', 'xMidYMid meet')
    .attr('font-family', "'Inter','Helvetica Neue',sans-serif")

  const defs = root.append('defs')

  STEPS.forEach(s => {
    defs.append('marker').attr('id', `at-arr-${s.id}`)
      .attr('viewBox','0 0 10 10').attr('refX',9).attr('refY',5)
      .attr('markerWidth',5).attr('markerHeight',5).attr('orient','auto')
      .append('path').attr('d','M0,1 L9,5 L0,9 z').attr('fill', s.clr)
  })

  const cy = H / 2 + 10

  // Arrows
  STEPS.forEach((s, i) => {
    if (i === STEPS.length - 1) return
    const x1 = startX + i * (BOX_W + PAD) + BOX_W / 2
    const x2 = startX + (i + 1) * (BOX_W + PAD) - BOX_W / 2 - 4
    root.append('line')
      .attr('x1', x1).attr('y1', cy)
      .attr('x2', x1).attr('y2', cy)
      .attr('stroke', STEPS[i + 1].clr).attr('stroke-width', 2.2).attr('opacity', 0.75)
      .attr('marker-end', `url(#at-arr-${STEPS[i + 1].id})`)
      .transition().delay(80 + i * 160 + 120).duration(200).ease(d3.easeLinear)
      .attr('x2', x2)
  })

  // Feedback arc: block → agent (dashed, below)
  const blockX = startX + 3 * (BOX_W + PAD)
  const agentX = startX
  const arcY   = cy + BOX_H / 2 + 32
  root.append('path')
    .attr('d', `M${blockX},${cy + BOX_H / 2} L${blockX},${arcY} L${agentX},${arcY} L${agentX},${cy + BOX_H / 2 - 4}`)
    .attr('stroke', '#f87171').attr('stroke-width', 1.5).attr('fill', 'none')
    .attr('stroke-dasharray', '5,3').attr('opacity', 0)
    .attr('marker-end', `url(#at-arr-agent)`)
    .transition().delay(700).duration(400).attr('opacity', 0.5)

  root.append('text')
    .attr('x', (blockX + agentX) / 2).attr('y', arcY + 14)
    .attr('text-anchor', 'middle').attr('font-size', 11)
    .attr('fill', '#f87171').attr('opacity', 0)
    .text('must write failing test first')
    .transition().delay(1000).duration(300).attr('opacity', 0.8)

  // Boxes
  STEPS.forEach((s, i) => {
    const cx = startX + i * (BOX_W + PAD)
    const delay = 60 + i * 160
    const bg  = dark ? s.darkBg  : s.lightBg
    const sub = dark ? s.darkSub : s.lightSub

    const g = root.append('g')
      .attr('transform', `translate(${cx},${cy}) scale(0)`)
      .attr('opacity', 0)

    g.transition().delay(delay).duration(300).ease(d3.easeBackOut.overshoot(1.4))
      .attr('transform', `translate(${cx},${cy}) scale(1)`)
      .attr('opacity', 1)

    g.append('rect')
      .attr('x', -BOX_W / 2).attr('y', -BOX_H / 2)
      .attr('width', BOX_W).attr('height', BOX_H).attr('rx', 10)
      .attr('fill', bg).attr('stroke', s.clr).attr('stroke-width', s.id === 'block' ? 2.5 : 1.8)

    if (s.id === 'block') {
      g.append('rect')
        .attr('x', -BOX_W / 2 + 4).attr('y', -BOX_H / 2 + 3)
        .attr('width', BOX_W - 8).attr('height', 3).attr('rx', 2)
        .attr('fill', s.clr).attr('opacity', 0.4)
    }

    g.append('text')
      .attr('y', -BOX_H / 2 - 10)
      .attr('text-anchor', 'middle').attr('font-size', 20)
      .text(s.emoji)

    g.append('text')
      .attr('y', -6).attr('text-anchor', 'middle')
      .attr('font-size', 13.5).attr('font-weight', 700)
      .attr('fill', s.clr).text(s.label)

    // sub — may be long, wrap at 2 lines
    const subLines = s.sub.length > 22 ? [s.sub.slice(0, s.sub.lastIndexOf(' ', 22)), s.sub.slice(s.sub.lastIndexOf(' ', 22) + 1)] : [s.sub]
    subLines.forEach((line, li) => {
      g.append('text')
        .attr('y', 12 + li * 14).attr('text-anchor', 'middle')
        .attr('font-size', 10.5).attr('fill', sub).text(line)
    })
  })
})
</script>

<style scoped>
.agentic-tdd-flow { display: block; width: 100%; }
</style>
