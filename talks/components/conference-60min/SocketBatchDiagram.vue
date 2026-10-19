<template>
  <svg ref="svg" class="socket-batch-diagram" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'
import { isDark } from '../../conference-60min/dark'

const svg = ref(null)

const W = 700, H = 160

const NODES_DARK = [
  { id: 'jvm',    x: 100, y: 80, w: 150, h: 56, label: 'Test JVM',             sub: '(Surefire fork)',       clr: '#60a5fa', bg: 'rgba(30,58,138,0.85)' },
  { id: 'server', x: 350, y: 80, w: 170, h: 56, label: 'IndexCollectorServer', sub: 'plugin-side socket',    clr: '#4ade80', bg: 'rgba(20,83,45,0.85)' },
  { id: 'lz4',    x: 590, y: 80, w: 150, h: 54, label: 'test-dependencies',    sub: '.lz4',                  clr: '#a78bfa', bg: 'rgba(46,16,101,0.85)', cylinder: true },
]
const NODES_LIGHT = [
  { id: 'jvm',    x: 100, y: 80, w: 150, h: 56, label: 'Test JVM',             sub: '(Surefire fork)',       clr: '#1d4ed8', bg: 'rgba(219,234,254,0.95)' },
  { id: 'server', x: 350, y: 80, w: 170, h: 56, label: 'IndexCollectorServer', sub: 'plugin-side socket',    clr: '#15803d', bg: 'rgba(220,252,231,0.95)' },
  { id: 'lz4',    x: 590, y: 80, w: 150, h: 54, label: 'test-dependencies',    sub: '.lz4',                  clr: '#7c3aed', bg: 'rgba(237,233,254,0.95)', cylinder: true },
]

const EDGES = [
  { s: 'jvm',    t: 'server', label: 'single binary batch\n(end of session)' },
  { s: 'server', t: 'lz4',   label: '' },
]
const ORDER = { jvm: 0, server: 1, lz4: 2 }

onMounted(() => {
  const dark = isDark.value
  const NODES = dark ? NODES_DARK : NODES_LIGHT
  const subColor  = dark ? '#cbd5e1' : '#1e293b'
  const edgeLbl   = dark ? '#94a3b8' : '#475569'
  const cylTopFill = dark ? '#4c1d95' : 'rgba(237,233,254,0.95)'

  const nm = Object.fromEntries(NODES.map(n => [n.id, n]))
  const strokeFor = id => nm[id]?.clr ?? '#4ade80'
  const arrowFor  = id => 'sb-arr-' + id

  const root = d3.select(svg.value)
    .attr('viewBox', `0 0 ${W} ${H}`)
    .attr('width', W).attr('height', H)
    .attr('font-family', "'Inter','Helvetica Neue',sans-serif")

  const defs = root.append('defs')
  const mkArr = (id, col) => defs.append('marker').attr('id', id)
    .attr('viewBox','0 0 10 10').attr('refX',9).attr('refY',5)
    .attr('markerWidth',6).attr('markerHeight',6).attr('orient','auto')
    .append('path').attr('d','M0,0 L10,5 L0,10 z').attr('fill', col)
  NODES.forEach(n => mkArr('sb-arr-' + n.id, n.clr))

  const rightOf = n => ({ x: n.x + n.w / 2, y: n.y })
  const leftOf  = n => ({ x: n.x - n.w / 2, y: n.y })

  EDGES.forEach(e => {
    const sn = nm[e.s], tn = nm[e.t]
    const sr = rightOf(sn), tl = leftOf(tn)
    const sx = sr.x, sy = sr.y, tx = tl.x, ty = tl.y
    const mx = (sx + tx) / 2
    const pathD = `M${sx},${sy} C${mx},${sy} ${mx},${ty} ${tx},${ty}`

    const phantom = document.createElementNS('http://www.w3.org/2000/svg', 'path')
    phantom.setAttribute('d', pathD)
    const len = phantom.getTotalLength ? phantom.getTotalLength() : 150

    const edgeDelay = 120 + ORDER[e.s] * 200 + 100
    root.append('path').attr('d', pathD)
      .attr('stroke', strokeFor(e.s)).attr('stroke-width', 2).attr('fill', 'none').attr('opacity', 0.8)
      .attr('marker-end', `url(#${arrowFor(e.s)})`)
      .attr('stroke-dasharray', len).attr('stroke-dashoffset', len)
      .transition().delay(edgeDelay).duration(400).ease(d3.easeLinear)
      .attr('stroke-dashoffset', 0)

    if (e.label) {
      const lines = e.label.split('\n')
      const lx = mx, ly = sy - 10
      lines.forEach((line, i) => {
        root.append('text').attr('x', lx).attr('y', ly + i * 14)
          .attr('text-anchor', 'middle').attr('font-size', 10).attr('fill', edgeLbl)
          .attr('opacity', 0).text(line)
          .transition().delay(edgeDelay + 350).duration(200).attr('opacity', 1)
      })
    }
  })

  NODES.forEach(n => {
    const nodeDelay = 80 + ORDER[n.id] * 200
    const g = root.append('g')
      .attr('transform', `translate(${n.x},${n.y}) scale(0)`).attr('opacity', 0)
    g.transition().delay(nodeDelay).duration(350).ease(d3.easeBackOut.overshoot(1.4))
      .attr('transform', `translate(${n.x},${n.y}) scale(1)`).attr('opacity', 1)

    if (n.cylinder) {
      const rx = n.w / 2, ry = 9
      const top = -n.h / 2, bot = n.h / 2
      g.append('ellipse').attr('rx', rx).attr('ry', ry).attr('cy', top)
        .attr('fill', cylTopFill).attr('stroke', n.clr).attr('stroke-width', 1.5)
      g.append('rect').attr('x', -rx).attr('y', top).attr('width', n.w).attr('height', n.h - ry)
        .attr('fill', n.bg)
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
        .attr('fill', subColor).attr('opacity', 0.85).text(n.sub)
    } else {
      g.append('rect').attr('x', -n.w/2).attr('y', -n.h/2).attr('width', n.w).attr('height', n.h).attr('rx', 8)
        .attr('fill', n.bg).attr('stroke', n.clr).attr('stroke-width', 2)
      g.append('text').attr('y', -5).attr('text-anchor','middle')
        .attr('font-size', 12).attr('font-weight', 700).attr('fill', n.clr).text(n.label)
      g.append('text').attr('y', 11).attr('text-anchor','middle')
        .attr('font-size', 9.5).attr('fill', subColor).attr('opacity', 0.85).text(n.sub)
    }
  })
})
</script>

<style scoped>
.socket-batch-diagram { display: block; max-width: 100%; }
</style>
