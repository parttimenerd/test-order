<template>
  <div ref="container" class="instrumentation-pipeline" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'

const container = ref(null)

// Fit inside ~780px wide content area (Slidev default layout minus padding)
const W = 760, H = 420
const BOX_W = 118, BOX_H = 110, BOX_R = 10

// Evenly spaced: 5 nodes across W with padding
const PAD = 14
const STEP = (W - PAD * 2 - BOX_W) / 4   // distance between box centres

const STAGES = [
  {
    id: 'class',
    label: '.class',
    label2: 'file',
    sub: 'bytecode on disk',
    color: '#94a3b8',
    bg: 'rgba(30,41,59,0.95)',
    emoji: '📄',
    x: PAD + BOX_W / 2,
  },
  {
    id: 'transform',
    label: 'ClassFile',
    label2: 'Transformer',
    sub: 'Surefire hook',
    color: '#60a5fa',
    bg: 'rgba(23,37,84,0.95)',
    emoji: '🔌',
    x: PAD + BOX_W / 2 + STEP,
  },
  {
    id: 'asm',
    label: 'ASM',
    label2: 'MethodVisitor',
    sub: 'streaming · one pass',
    color: '#a78bfa',
    bg: 'rgba(46,16,101,0.95)',
    emoji: '⚙',
    x: PAD + BOX_W / 2 + STEP * 2,
  },
  {
    id: 'inject',
    label: 'invokestatic',
    label2: '(5 bytes)',
    sub: 'recordUsageIdFast(id)',
    color: '#fbbf24',
    bg: 'rgba(92,52,14,0.95)',
    emoji: '💉',
    x: PAD + BOX_W / 2 + STEP * 3,
  },
  {
    id: 'bitset',
    label: 'thread-local',
    label2: 'bitset',
    sub: 'bits[id>>>6] |= 1<<id',
    color: '#4ade80',
    bg: 'rgba(6,78,59,0.95)',
    emoji: '🗂',
    x: PAD + BOX_W / 2 + STEP * 4,
  },
]

onMounted(() => {
  const cy = H / 2 - 50   // shifted up; emojis clear the phase label, annotation fits below

  const svg = d3.select(container.value)
    .append('svg')
    .attr('viewBox', `0 0 ${W} ${H}`)
    .attr('width', '100%')
    .attr('preserveAspectRatio', 'xMidYMid meet')
    .attr('font-family', "'JetBrains Mono','Fira Code','Consolas',monospace")

  // Arrowhead markers
  const defs = svg.append('defs')
  STAGES.slice(0, -1).forEach(s => {
    defs.append('marker')
      .attr('id', `arr-${s.id}`)
      .attr('viewBox', '0 0 10 10').attr('refX', 8).attr('refY', 5)
      .attr('markerWidth', 5).attr('markerHeight', 5).attr('orient', 'auto')
      .append('path').attr('d', 'M0,1 L9,5 L0,9 z').attr('fill', s.color)
  })

  // ── Connectors ──────────────────────────────────────────────────────────
  for (let i = 0; i < STAGES.length - 1; i++) {
    const s = STAGES[i], t = STAGES[i + 1]
    const x1 = s.x + BOX_W / 2 + 2
    const x2 = t.x - BOX_W / 2 - 2

    // ghost line
    svg.append('line')
      .attr('x1', x1).attr('y1', cy)
      .attr('x2', x2).attr('y2', cy)
      .attr('stroke', '#1e293b').attr('stroke-width', 3)

    // animated fill
    const arrow = svg.append('line')
      .attr('x1', x1).attr('y1', cy)
      .attr('x2', x1).attr('y2', cy)
      .attr('stroke', s.color).attr('stroke-width', 2.5).attr('opacity', 0.85)
      .attr('marker-end', `url(#arr-${s.id})`)

    arrow.transition()
      .delay(150 + i * 200).duration(350).ease(d3.easeLinear)
      .attr('x2', x2)
  }

  // ── Nodes ────────────────────────────────────────────────────────────────
  STAGES.forEach((s, i) => {
    const delay = 60 + i * 200
    const g = svg.append('g')
      .attr('transform', `translate(${s.x},${cy}) scale(0)`)
      .attr('opacity', 0)

    g.transition().delay(delay).duration(320).ease(d3.easeBackOut.overshoot(1.4))
      .attr('transform', `translate(${s.x},${cy}) scale(1)`)
      .attr('opacity', 1)

    // Box with subtle inner glow
    g.append('rect')
      .attr('x', -BOX_W / 2).attr('y', -BOX_H / 2)
      .attr('width', BOX_W).attr('height', BOX_H).attr('rx', BOX_R)
      .attr('fill', s.bg)
      .attr('stroke', s.color).attr('stroke-width', 2)

    // Subtle inner highlight top edge
    g.append('rect')
      .attr('x', -BOX_W / 2 + 4).attr('y', -BOX_H / 2 + 2)
      .attr('width', BOX_W - 8).attr('height', 3).attr('rx', 2)
      .attr('fill', s.color).attr('opacity', 0.25)

    // Emoji above box
    g.append('text')
      .attr('y', -BOX_H / 2 - 12)
      .attr('text-anchor', 'middle')
      .attr('font-size', 20)
      .text(s.emoji)

    // Line 1 label
    g.append('text')
      .attr('y', -10)
      .attr('text-anchor', 'middle')
      .attr('font-size', 12.5).attr('font-weight', 700)
      .attr('fill', s.color)
      .attr('letter-spacing', '0.02em')
      .text(s.label)

    // Line 2 label
    g.append('text')
      .attr('y', 7)
      .attr('text-anchor', 'middle')
      .attr('font-size', 12.5).attr('font-weight', 700)
      .attr('fill', s.color)
      .attr('letter-spacing', '0.02em')
      .text(s.label2)

    // Sub-label at bottom of box
    g.append('text')
      .attr('y', BOX_H / 2 - 11)
      .attr('text-anchor', 'middle')
      .attr('font-size', 8.5)
      .attr('fill', '#94a3b8')
      .attr('letter-spacing', '0.01em')
      .text(s.sub)
  })

  // ── Bottom annotation ────────────────────────────────────────────────────
  const lastX = STAGES[4].x
  svg.append('text')
    .attr('x', lastX)
    .attr('y', cy + BOX_H / 2 + 26)
    .attr('text-anchor', 'middle')
    .attr('font-size', 10.5)
    .attr('fill', 'rgba(74,222,128,0.60)')
    .attr('font-style', 'italic')
    .attr('opacity', 0)
    .text('bitset drained once per test → dep set written to index')
    .transition().delay(1200).duration(500).attr('opacity', 1)

  // ── Phase label ───────────────────────────────────────────────────────────
  svg.append('text')
    .attr('x', W / 2).attr('y', 18)
    .attr('text-anchor', 'middle')
    .attr('font-size', 10).attr('fill', 'rgba(148,163,184,0.40)')
    .attr('font-family', "'Inter','Helvetica Neue',sans-serif")
    .attr('font-style', 'italic')
    .attr('letter-spacing', '0.08em')
    .text('learn run only')
})
</script>

<style scoped>
.instrumentation-pipeline {
  display: block;
  width: 100%;
}
</style>
