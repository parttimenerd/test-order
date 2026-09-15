<template>
  <svg ref="svg" class="pipeline-diagram" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'

defineProps({
  width:  { type: Number, default: 820 },
  height: { type: Number, default: 220 },
})

const svg = ref(null)

const W = 820, H = 220
const COL = [80, 250, 440, 600, 760]
const MID = H / 2

const NODES = [
  { id: 'learn',  x: COL[0], y: MID,       w: 130, h: 62, label: 'mvn test #1',    sub: 'LEARN  agent on',       clr: '#60a5fa', bg: 'rgba(30,58,138,0.85)' },
  { id: 'index',  x: COL[1], y: MID - 44,  w: 140, h: 54, label: 'index.lz4',      sub: 'test → {classes}',      clr: '#a78bfa', bg: 'rgba(46,16,101,0.85)', cylinder: true },
  { id: 'diff',   x: COL[1], y: MID + 44,  w: 140, h: 54, label: 'git diff',        sub: 'changed classes',       clr: '#f87171', bg: 'rgba(127,29,29,0.85)' },
  { id: 'score',  x: COL[2], y: MID,        w: 150, h: 62, label: 'score',           sub: 'overlap · history · speed', clr: '#fbbf24', bg: 'rgba(113,63,18,0.85)' },
  { id: 'ranked', x: COL[3], y: MID,        w: 140, h: 62, label: 'ranked order',    sub: 'CartTest #1 …',         clr: '#4ade80', bg: 'rgba(20,83,45,0.85)' },
  { id: 'fast',   x: COL[4], y: MID,        r: 34,          label: '⚡',              sub: 'fast feedback',         clr: '#4ade80', bg: 'rgba(5,46,22,0.90)', circle: true },
]

const EDGES = [
  { s: 'learn',  t: 'index',  label: 'records' },
  { s: 'learn',  t: 'diff',   label: '' },
  { s: 'index',  t: 'score',  label: '' },
  { s: 'diff',   t: 'score',  label: '' },
  { s: 'score',  t: 'ranked', label: '' },
  { s: 'ranked', t: 'fast',   label: '' },
]

// Column order for staggered node reveal
const NODE_ORDER = { learn: 0, index: 1, diff: 2, score: 3, ranked: 4, fast: 5 }

onMounted(() => {
  const nm = Object.fromEntries(NODES.map(n => [n.id, n]))

  const root = d3.select(svg.value)
    .attr('viewBox', `0 0 ${W} ${H}`)
    .attr('width', W).attr('height', H)
    .attr('font-family', "'Inter','Helvetica Neue',sans-serif")

  const defs = root.append('defs')
  const mkArr = (id, col) => defs.append('marker').attr('id', id)
    .attr('viewBox','0 0 10 10').attr('refX',9).attr('refY',5)
    .attr('markerWidth',6).attr('markerHeight',6).attr('orient','auto')
    .append('path').attr('d','M0,0 L10,5 L0,10 z').attr('fill', col)
  mkArr('arr-b','#60a5fa')
  mkArr('arr-r','#f87171')
  mkArr('arr-p','#a78bfa')
  mkArr('arr-y','#fbbf24')
  mkArr('arr-g','#4ade80')

  const rightOf = n => n.circle ? { x: n.x + n.r, y: n.y } : { x: n.x + n.w / 2, y: n.y }
  const leftOf  = n => n.circle ? { x: n.x - n.r, y: n.y } : { x: n.x - n.w / 2, y: n.y }

  const arrowFor  = id => ({ learn: 'arr-b', diff: 'arr-r', index: 'arr-p', score: 'arr-y', ranked: 'arr-g' }[id] ?? 'arr-g')
  const strokeFor = id => ({ learn: '#60a5fa', diff: '#f87171', index: '#a78bfa', score: '#fbbf24', ranked: '#4ade80' }[id] ?? '#4ade80')

  // ─── Edges — draw via stroke-dashoffset, staggered by source column ───
  EDGES.forEach(e => {
    const sn = nm[e.s], tn = nm[e.t]
    const sr = rightOf(sn), tl = leftOf(tn)
    const sx = sr.x, sy = sr.y, tx = tl.x, ty = tl.y
    const mx = (sx + tx) / 2
    const pathD = `M${sx},${sy} C${mx},${sy} ${mx},${ty} ${tx},${ty}`

    const edgeDelay = 200 + NODE_ORDER[e.s] * 160 + 80

    // Measure path length for dashoffset animation
    const phantom = document.createElementNS('http://www.w3.org/2000/svg', 'path')
    phantom.setAttribute('d', pathD)
    const len = phantom.getTotalLength ? phantom.getTotalLength() : 200

    const path = root.append('path')
      .attr('d', pathD)
      .attr('stroke', strokeFor(e.s))
      .attr('stroke-width', 2)
      .attr('fill', 'none')
      .attr('opacity', 0.8)
      .attr('marker-end', `url(#${arrowFor(e.s)})`)
      .attr('stroke-dasharray', len)
      .attr('stroke-dashoffset', len)

    path.transition().delay(edgeDelay).duration(400).ease(d3.easeLinear)
      .attr('stroke-dashoffset', 0)

    if (e.label) {
      root.append('text')
        .attr('x', mx).attr('y', Math.min(sy, ty) - 6)
        .attr('text-anchor', 'middle').attr('font-size', 10)
        .attr('fill', '#94a3b8').attr('opacity', 0).text(e.label)
        .transition().delay(edgeDelay + 350).duration(200).attr('opacity', 1)
    }
  })

  // ─── Nodes — scale+fade in by column order ───────────────────────
  NODES.forEach(n => {
    const nodeDelay = 80 + NODE_ORDER[n.id] * 160
    const g = root.append('g')
      .attr('transform', `translate(${n.x},${n.y}) scale(0)`)
      .attr('opacity', 0)

    g.transition().delay(nodeDelay).duration(350).ease(d3.easeBackOut.overshoot(1.4))
      .attr('transform', `translate(${n.x},${n.y}) scale(1)`)
      .attr('opacity', 1)

    if (n.circle) {
      g.append('circle').attr('r', n.r)
        .attr('fill', n.bg).attr('stroke', n.clr).attr('stroke-width', 2.5)
      g.append('text').attr('y', -2)
        .attr('text-anchor','middle').attr('dominant-baseline','middle')
        .attr('font-size', 20).attr('fill', n.clr).text(n.label)
      g.append('text').attr('y', 16)
        .attr('text-anchor','middle').attr('font-size', 9.5)
        .attr('fill','#86efac').attr('opacity', 0.9).text(n.sub)
    } else if (n.cylinder) {
      const rx = n.w / 2, ry = 10
      const top = -n.h / 2, bot = n.h / 2
      g.append('ellipse').attr('rx', rx).attr('ry', ry).attr('cy', top)
        .attr('fill', '#4c1d95').attr('stroke', n.clr).attr('stroke-width', 1.5)
      g.append('rect').attr('x', -rx).attr('y', top).attr('width', n.w).attr('height', n.h - ry)
        .attr('fill', n.bg).attr('stroke', n.clr).attr('stroke-width', 0)
      g.append('line').attr('x1', -rx).attr('y1', top).attr('x2', -rx).attr('y2', bot - ry)
        .attr('stroke', n.clr).attr('stroke-width', 1.5)
      g.append('line').attr('x1', rx).attr('y1', top).attr('x2', rx).attr('y2', bot - ry)
        .attr('stroke', n.clr).attr('stroke-width', 1.5)
      g.append('ellipse').attr('rx', rx).attr('ry', ry).attr('cy', bot - ry)
        .attr('fill', n.bg).attr('stroke', n.clr).attr('stroke-width', 1.5)
      g.append('text').attr('y', -4)
        .attr('text-anchor','middle').attr('font-size', 12).attr('font-weight', 700)
        .attr('fill', n.clr).text(n.label)
      g.append('text').attr('y', 10)
        .attr('text-anchor','middle').attr('font-size', 9)
        .attr('fill','#c4b5fd').attr('opacity', 0.85).text(n.sub)
    } else {
      g.append('rect').attr('x', -n.w / 2).attr('y', -n.h / 2).attr('width', n.w).attr('height', n.h).attr('rx', 8)
        .attr('fill', n.bg).attr('stroke', n.clr).attr('stroke-width', 2)
      g.append('text').attr('y', -5)
        .attr('text-anchor','middle').attr('font-size', 13).attr('font-weight', 700)
        .attr('fill', n.clr).text(n.label)
      g.append('text').attr('y', 11)
        .attr('text-anchor','middle').attr('font-size', 9.5)
        .attr('fill','#cbd5e1').attr('opacity', 0.85).text(n.sub)
    }
  })
})
</script>

<style scoped>
.pipeline-diagram { display: block; max-width: 100%; }
</style>
