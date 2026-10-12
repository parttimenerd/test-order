<template>
  <svg ref="svg" class="pipeline-diagram" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'

const svg = ref(null)

const W = 1040, H = 380
const COL = [100, 340, 570, 810]
const MID = 190

const NODES = [
  { id: 'learn',  x: COL[0], y: MID,      w: 160, h: 106, label: 'mvn test',     sub: 'learn run',              clr: '#60a5fa', bg: 'rgba(30,58,138,0.90)' },
  { id: 'index',  x: COL[1], y: MID - 88, w: 170, h: 92,  label: 'index',        sub: 'test → classes',         clr: '#a78bfa', bg: 'rgba(46,16,101,0.90)', cylinder: true },
  { id: 'diff',   x: COL[1], y: MID + 88, w: 170, h: 92,  label: 'git diff',     sub: 'DestinationRetrieval…',  clr: '#f87171', bg: 'rgba(127,29,29,0.90)' },
  { id: 'score',  x: COL[2], y: MID,      w: 180, h: 106, label: 'score',        sub: 'overlap + history',      clr: '#fbbf24', bg: 'rgba(113,63,18,0.90)' },
  { id: 'ranked', x: COL[3], y: MID,      w: 170, h: 106, label: 'ranked order', sub: 'ResolverTest #1 …',      clr: '#4ade80', bg: 'rgba(20,83,45,0.90)' },
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
  mkArr('arr-b','#60a5fa')
  mkArr('arr-r','#f87171')
  mkArr('arr-p','#a78bfa')
  mkArr('arr-y','#fbbf24')
  mkArr('arr-g','#4ade80')

  const rightOf  = n => ({ x: n.x + n.w / 2, y: n.y })
  const leftOf   = n => ({ x: n.x - n.w / 2, y: n.y })
  const topOf    = n => ({ x: n.x, y: n.y - n.h / 2 })
  const bottomOf = n => ({ x: n.x, y: n.y + n.h / 2 })

  const arrowFor  = id => ({ learn: 'arr-b', diff: 'arr-r', index: 'arr-p', score: 'arr-y' }[id] ?? 'arr-g')
  const strokeFor = id => ({ learn: '#60a5fa', diff: '#f87171', index: '#a78bfa', score: '#fbbf24' }[id] ?? '#4ade80')

  EDGES.forEach(e => {
    const sn = nm[e.s], tn = nm[e.t]

    let src, dst, pathD
    if (e.s === 'learn' && e.t === 'index') {
      // exit right of learn, S-curve up to left of index
      src = rightOf(sn); dst = leftOf(tn)
      const mx = (src.x + dst.x) / 2
      pathD = `M${src.x},${src.y} C${mx},${src.y} ${mx},${dst.y} ${dst.x},${dst.y}`
    } else if (e.s === 'learn' && e.t === 'diff') {
      // exit right of learn, S-curve down to left of diff
      src = rightOf(sn); dst = leftOf(tn)
      const mx = (src.x + dst.x) / 2
      pathD = `M${src.x},${src.y} C${mx},${src.y} ${mx},${dst.y} ${dst.x},${dst.y}`
    } else {
      // straight horizontal bezier
      src = rightOf(sn); dst = leftOf(tn)
      const mx = (src.x + dst.x) / 2
      pathD = `M${src.x},${src.y} C${mx},${src.y} ${mx},${dst.y} ${dst.x},${dst.y}`
    }

    const edgeDelay = 180 + NODE_ORDER[e.s] * 160 + 80

    const phantom = document.createElementNS('http://www.w3.org/2000/svg', 'path')
    phantom.setAttribute('d', pathD)
    const len = phantom.getTotalLength ? phantom.getTotalLength() : 200

    root.append('path').attr('d', pathD)
      .attr('stroke', 'rgba(255,255,255,0.05)').attr('stroke-width', 3).attr('fill', 'none')

    root.append('path').attr('d', pathD)
      .attr('stroke', strokeFor(e.s)).attr('stroke-width', 2.5).attr('fill', 'none').attr('opacity', 0.8)
      .attr('marker-end', `url(#${arrowFor(e.s)})`)
      .attr('stroke-dasharray', len).attr('stroke-dashoffset', len)
      .transition().delay(edgeDelay).duration(420).ease(d3.easeLinear)
      .attr('stroke-dashoffset', 0)

    if (e.label) {
      const mx = (src.x + dst.x) / 2
      root.append('text')
        .attr('x', mx).attr('y', Math.min(src.y, dst.y) - 8)
        .attr('text-anchor', 'middle').attr('font-size', 15)
        .attr('fill', '#94a3b8').attr('opacity', 0).text(e.label)
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
        .attr('fill', '#4c1d95').attr('stroke', n.clr).attr('stroke-width', 1.5)
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
        .attr('fill','#c4b5fd').attr('opacity', 0.9).text(n.sub)
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
        .attr('fill','#cbd5e1').attr('opacity', 0.9).text(n.sub)
    }
  })
})
</script>

<style scoped>
.pipeline-diagram { display: block; max-width: 100%; width: 100%; }
</style>
