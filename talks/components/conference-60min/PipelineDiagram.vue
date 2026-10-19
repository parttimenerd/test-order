<template>
  <svg ref="svg" class="pipeline-diagram" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'
import { isDark } from '../../conference-60min/dark'

const svg = ref(null)

const W = 1040, H = 380
const COL = [100, 340, 570, 810]
const MID = 190

const NODES_DARK = [
  { id: 'learn',  x: COL[0], y: MID,      w: 160, h: 106, label: 'mvn test',     sub: 'learn run',              clr: '#60a5fa', bg: 'rgba(30,58,138,0.90)' },
  { id: 'index',  x: COL[1], y: MID - 88, w: 170, h: 92,  label: 'index',        sub: 'test → classes',         clr: '#a78bfa', bg: 'rgba(46,16,101,0.90)', cylinder: true },
  { id: 'diff',   x: COL[1], y: MID + 88, w: 170, h: 92,  label: 'git diff',     sub: 'DestinationRetrieval…',  clr: '#f87171', bg: 'rgba(127,29,29,0.90)' },
  { id: 'score',  x: COL[2], y: MID,      w: 180, h: 106, label: 'score',        sub: 'overlap + history',      clr: '#fbbf24', bg: 'rgba(113,63,18,0.90)' },
  { id: 'ranked', x: COL[3], y: MID,      w: 170, h: 106, label: 'ranked order', sub: 'ResolverTest #1 …',      clr: '#4ade80', bg: 'rgba(20,83,45,0.90)' },
]
const NODES_LIGHT = [
  { id: 'learn',  x: COL[0], y: MID,      w: 160, h: 106, label: 'mvn test',     sub: 'learn run',              clr: '#1d4ed8', bg: 'rgba(219,234,254,0.95)' },
  { id: 'index',  x: COL[1], y: MID - 88, w: 170, h: 92,  label: 'index',        sub: 'test → classes',         clr: '#7c3aed', bg: 'rgba(237,233,254,0.95)', cylinder: true },
  { id: 'diff',   x: COL[1], y: MID + 88, w: 170, h: 92,  label: 'git diff',     sub: 'DestinationRetrieval…',  clr: '#dc2626', bg: 'rgba(254,226,226,0.95)' },
  { id: 'score',  x: COL[2], y: MID,      w: 180, h: 106, label: 'score',        sub: 'overlap + history',      clr: '#b45309', bg: 'rgba(254,249,195,0.95)' },
  { id: 'ranked', x: COL[3], y: MID,      w: 170, h: 106, label: 'ranked order', sub: 'ResolverTest #1 …',      clr: '#15803d', bg: 'rgba(220,252,231,0.95)' },
]

const EDGES = [
  { s: 'learn',  t: 'index',  label: 'records' },
  { s: 'learn',  t: 'diff',   label: '' },
  { s: 'index',  t: 'score',  label: '' },
  { s: 'diff',   t: 'score',  label: '' },
  { s: 'score',  t: 'ranked', label: '' },
]

const NODE_ORDER = { learn: 0, index: 1, diff: 2, score: 3, ranked: 4 }

onMounted(() => {
  const dark = isDark.value
  const NODES = dark ? NODES_DARK : NODES_LIGHT
  const ghostTrack = dark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.06)'
  const subColor   = dark ? '#cbd5e1' : '#1e293b'
  const edgeLbl   = dark ? '#94a3b8' : '#475569'
  const cylTopFill = dark ? '#4c1d95' : 'rgba(237,233,254,0.95)'

  const nm = Object.fromEntries(NODES.map(n => [n.id, n]))

  const root = d3.select(svg.value)
    .attr('viewBox', `0 0 ${W} ${H}`)
    .attr('width', '100%')
    .attr('preserveAspectRatio', 'xMidYMid meet')
    .attr('font-family', "'Inter','Helvetica Neue',sans-serif")

  const defs = root.append('defs')

  const shadow = defs.append('filter').attr('id', 'pd-shadow').attr('x', '-20%').attr('y', '-20%').attr('width', '140%').attr('height', '140%')
  shadow.append('feDropShadow').attr('dx', 0).attr('dy', 2).attr('stdDeviation', 4).attr('flood-color', 'rgba(0,0,0,0.5)')

  const mkArr = (id, col) => defs.append('marker').attr('id', id)
    .attr('viewBox','0 0 10 10').attr('refX',8).attr('refY',5)
    .attr('markerWidth',6).attr('markerHeight',6).attr('orient','auto')
    .append('path').attr('d','M0,1 L9,5 L0,9 z').attr('fill', col)
  NODES.forEach(n => mkArr('arr-' + n.id, n.clr))

  const rightOf  = n => ({ x: n.x + n.w / 2, y: n.y })
  const leftOf   = n => ({ x: n.x - n.w / 2, y: n.y })

  const arrowFor  = id => 'arr-' + id
  const strokeFor = id => nm[id]?.clr ?? '#4ade80'

  EDGES.forEach(e => {
    const sn = nm[e.s], tn = nm[e.t]
    let src, dst, pathD
    src = rightOf(sn); dst = leftOf(tn)
    const mx = (src.x + dst.x) / 2
    pathD = `M${src.x},${src.y} C${mx},${src.y} ${mx},${dst.y} ${dst.x},${dst.y}`

    const edgeDelay = 180 + NODE_ORDER[e.s] * 160 + 80

    const phantom = document.createElementNS('http://www.w3.org/2000/svg', 'path')
    phantom.setAttribute('d', pathD)
    const len = phantom.getTotalLength ? phantom.getTotalLength() : 200

    root.append('path').attr('d', pathD)
      .attr('stroke', ghostTrack).attr('stroke-width', 3).attr('fill', 'none')

    root.append('path').attr('d', pathD)
      .attr('stroke', strokeFor(e.s)).attr('stroke-width', 2.5).attr('fill', 'none').attr('opacity', 0.8)
      .attr('marker-end', `url(#${arrowFor(e.s)})`)
      .attr('stroke-dasharray', len).attr('stroke-dashoffset', len)
      .transition().delay(edgeDelay).duration(420).ease(d3.easeLinear)
      .attr('stroke-dashoffset', 0)

    if (e.label) {
      const mx2 = (src.x + dst.x) / 2
      root.append('text')
        .attr('x', mx2).attr('y', Math.min(src.y, dst.y) - 8)
        .attr('text-anchor', 'middle').attr('font-size', 15)
        .attr('fill', edgeLbl).attr('opacity', 0).text(e.label)
        .transition().delay(edgeDelay + 380).duration(200).attr('opacity', 1)
    }
  })

  NODES.forEach(n => {
    const nodeDelay = 60 + NODE_ORDER[n.id] * 160
    const g = root.append('g')
      .attr('transform', `translate(${n.x},${n.y}) scale(0)`)
      .attr('opacity', 0)

    g.transition().delay(nodeDelay).duration(360).ease(d3.easeBackOut.overshoot(1.4))
      .attr('transform', `translate(${n.x},${n.y}) scale(1)`)
      .attr('opacity', 1)

    if (n.cylinder) {
      const rx = n.w / 2, ry = 11
      const top = -n.h / 2, bot = n.h / 2
      g.append('ellipse').attr('rx', rx).attr('ry', ry).attr('cy', top)
        .attr('fill', cylTopFill).attr('stroke', n.clr).attr('stroke-width', 1.5)
      g.append('rect').attr('x', -rx).attr('y', top).attr('width', n.w).attr('height', n.h - ry)
        .attr('fill', n.bg).attr('stroke', n.clr).attr('stroke-width', 0)
        .attr('filter', 'url(#pd-shadow)')
      g.append('line').attr('x1', -rx).attr('y1', top).attr('x2', -rx).attr('y2', bot - ry)
        .attr('stroke', n.clr).attr('stroke-width', 1.5)
      g.append('line').attr('x1', rx).attr('y1', top).attr('x2', rx).attr('y2', bot - ry)
        .attr('stroke', n.clr).attr('stroke-width', 1.5)
      g.append('ellipse').attr('rx', rx).attr('ry', ry).attr('cy', bot - ry)
        .attr('fill', n.bg).attr('stroke', n.clr).attr('stroke-width', 1.5)
      g.append('text').attr('y', -8)
        .attr('text-anchor','middle').attr('font-size', 18).attr('font-weight', 700)
        .attr('fill', n.clr).text(n.label)
      g.append('text').attr('y', 14)
        .attr('text-anchor','middle').attr('font-size', 14)
        .attr('fill', subColor).attr('opacity', 0.9).text(n.sub)
    } else {
      g.append('rect').attr('x', -n.w / 2).attr('y', -n.h / 2)
        .attr('width', n.w).attr('height', n.h).attr('rx', 10)
        .attr('fill', n.bg).attr('stroke', n.clr).attr('stroke-width', 2)
        .attr('filter', 'url(#pd-shadow)')
      g.append('rect').attr('x', -n.w/2 + 5).attr('y', -n.h/2 + 2)
        .attr('width', n.w - 10).attr('height', 4).attr('rx', 2)
        .attr('fill', n.clr).attr('opacity', 0.25)
      g.append('text').attr('y', -8)
        .attr('text-anchor','middle').attr('font-size', 18).attr('font-weight', 700)
        .attr('fill', n.clr).text(n.label)
      g.append('text').attr('y', 14)
        .attr('text-anchor','middle').attr('font-size', 14)
        .attr('fill', subColor).attr('opacity', 0.9).text(n.sub)
    }
  })
})
</script>

<style scoped>
.pipeline-diagram { display: block; max-width: 100%; width: 100%; }
</style>
