<template>
  <svg ref="svg" class="socket-batch-diagram" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'

const svg = ref(null)

const W = 700, H = 160
// Three nodes: Test JVM, IndexCollectorServer, test-dependencies.lz4
const NODES = [
  { id: 'jvm',    x: 100, y: 80, w: 150, h: 56, label: 'Test JVM',             sub: '(Surefire fork)',               clr: '#60a5fa', bg: 'rgba(30,58,138,0.85)' },
  { id: 'server', x: 350, y: 80, w: 170, h: 56, label: 'IndexCollectorServer', sub: 'plugin-side socket',             clr: '#4ade80', bg: 'rgba(20,83,45,0.85)' },
  { id: 'lz4',   x: 590, y: 80, w: 150, h: 54, label: 'test-dependencies',    sub: '.lz4',                           clr: '#a78bfa', bg: 'rgba(46,16,101,0.85)', cylinder: true },
]

const EDGES = [
  { s: 'jvm',    t: 'server', label: 'single binary batch\n(end of session)' },
  { s: 'server', t: 'lz4',   label: '' },
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
  mkArr('sb-arr-b','#60a5fa')
  mkArr('sb-arr-g','#4ade80')
  mkArr('sb-arr-p','#a78bfa')

  const arrowFor = id => ({ jvm: 'sb-arr-b', server: 'sb-arr-g' }[id] ?? 'sb-arr-g')
  const strokeFor = id => ({ jvm: '#60a5fa', server: '#4ade80' }[id] ?? '#4ade80')

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
      const lx = mx, ly = sy - 10
      lines.forEach((line, i) => {
        root.append('text')
          .attr('x', lx).attr('y', ly + i * 14)
          .attr('text-anchor', 'middle').attr('font-size', 10)
          .attr('fill', '#94a3b8').text(line)
      })
    }
  })

  NODES.forEach(n => {
    const g = root.append('g')
    if (n.cylinder) {
      const rx = n.w / 2, ry = 9, cx = n.x, cy = n.y
      const top = cy - n.h / 2, bot = cy + n.h / 2
      g.append('ellipse').attr('cx', cx).attr('cy', top).attr('rx', rx).attr('ry', ry)
        .attr('fill', '#4c1d95').attr('stroke', n.clr).attr('stroke-width', 1.5)
      g.append('rect').attr('x', cx - rx).attr('y', top).attr('width', n.w).attr('height', n.h - ry)
        .attr('fill', n.bg).attr('stroke', n.clr).attr('stroke-width', 0)
      g.append('line').attr('x1', cx - rx).attr('y1', top).attr('x2', cx - rx).attr('y2', bot - ry)
        .attr('stroke', n.clr).attr('stroke-width', 1.5)
      g.append('line').attr('x1', cx + rx).attr('y1', top).attr('x2', cx + rx).attr('y2', bot - ry)
        .attr('stroke', n.clr).attr('stroke-width', 1.5)
      g.append('ellipse').attr('cx', cx).attr('cy', bot - ry).attr('rx', rx).attr('ry', ry)
        .attr('fill', n.bg).attr('stroke', n.clr).attr('stroke-width', 1.5)
      g.append('text').attr('x', cx).attr('y', cy - 4)
        .attr('text-anchor','middle').attr('font-size', 12).attr('font-weight', 700)
        .attr('fill', n.clr).text(n.label)
      g.append('text').attr('x', cx).attr('y', cy + 10)
        .attr('text-anchor','middle').attr('font-size', 9)
        .attr('fill','#c4b5fd').attr('opacity', 0.85).text(n.sub)
    } else {
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
    }
  })
})
</script>

<style scoped>
.socket-batch-diagram { display: block; max-width: 100%; }
</style>
