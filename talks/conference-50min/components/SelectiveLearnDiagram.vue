<template>
  <svg ref="svg" class="selective-learn-diagram" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'

const svg = ref(null)

const W = 760, H = 200

// Layout: git diff → uncertain set → (empty: agent not attached) or (non-empty: agent instruments)
// Positions: left=120, mid=370, right-top=620/y=70, right-bot=620/y=145
const NODES = [
  { id: 'diff',   x: 120, y: 100, w: 160, h: 58, label: 'git diff',         sub: '→ changed classes', clr: '#f87171', bg: 'rgba(127,29,29,0.85)' },
  { id: 'unc',    x: 370, y: 100, w: 160, h: 58, label: 'uncertain set',     sub: 'classes to instrument', clr: '#fbbf24', bg: 'rgba(113,63,18,0.85)' },
  { id: 'no',     x: 610, y: 55,  w: 150, h: 52, label: 'agent not attached', sub: 'zero overhead',    clr: '#4ade80', bg: 'rgba(20,83,45,0.85)' },
  { id: 'ag',     x: 610, y: 150, w: 150, h: 52, label: 'agent instruments', sub: 'only this set',     clr: '#60a5fa', bg: 'rgba(30,58,138,0.85)' },
]

const EDGES = [
  { s: 'diff', t: 'unc',  label: 'static call graph\nBFS, depth ≤ 4' },
  { s: 'unc',  t: 'no',   label: 'empty?' },
  { s: 'unc',  t: 'ag',   label: 'non-empty' },
]

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
  mkArr('sl-arr-r','#f87171')
  mkArr('sl-arr-y','#fbbf24')
  mkArr('sl-arr-g','#4ade80')
  mkArr('sl-arr-b','#60a5fa')

  const arrowFor = id => ({ diff: 'sl-arr-r', unc: 'sl-arr-y' }[id] ?? 'sl-arr-g')
  const strokeFor = id => ({ diff: '#f87171', unc: '#fbbf24' }[id] ?? '#4ade80')

  const rightOf = n => ({ x: n.x + n.w / 2, y: n.y })
  const leftOf  = n => ({ x: n.x - n.w / 2, y: n.y })

  EDGES.forEach(e => {
    const sn = nm[e.s], tn = nm[e.t]
    const sr = rightOf(sn), tl = leftOf(tn)
    const sx = sr.x, sy = sr.y, tx = tl.x, ty = tl.y
    const mx = (sx + tx) / 2
    const path = `M${sx},${sy} C${mx},${sy} ${mx},${ty} ${tx},${ty}`

    root.append('path')
      .attr('d', path)
      .attr('stroke', strokeFor(e.s))
      .attr('stroke-width', 2)
      .attr('fill', 'none')
      .attr('opacity', 0.8)
      .attr('marker-end', `url(#${arrowFor(e.s)})`)

    if (e.label) {
      const lines = e.label.split('\n')
      const lx = mx
      const ly = Math.min(sy, ty) - 8
      lines.forEach((line, i) => {
        root.append('text')
          .attr('x', lx).attr('y', ly + i * 13)
          .attr('text-anchor', 'middle').attr('font-size', 10)
          .attr('fill', '#94a3b8').text(line)
      })
    }
  })

  NODES.forEach(n => {
    const g = root.append('g')
    g.append('rect')
      .attr('x', n.x - n.w / 2).attr('y', n.y - n.h / 2)
      .attr('width', n.w).attr('height', n.h).attr('rx', 8)
      .attr('fill', n.bg).attr('stroke', n.clr).attr('stroke-width', 2)
    g.append('text').attr('x', n.x).attr('y', n.y - 5)
      .attr('text-anchor','middle').attr('font-size', 12).attr('font-weight', 700)
      .attr('fill', n.clr).text(n.label)
    g.append('text').attr('x', n.x).attr('y', n.y + 11)
      .attr('text-anchor','middle').attr('font-size', 9.5)
      .attr('fill','#cbd5e1').attr('opacity', 0.85).text(n.sub)
  })
})
</script>

<style scoped>
.selective-learn-diagram { display: block; max-width: 100%; }
</style>
