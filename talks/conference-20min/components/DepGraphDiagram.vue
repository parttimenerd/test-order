<template>
  <svg ref="svg" class="dep-graph-diagram" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'

const svg = ref(null)

const W = 320, H = 300

const NODES = [
  { id: 'cart',   x: 160, y: 30,  w: 140, h: 36, label: '🔴 Cart.java',    sub: '(edited)',  clr: '#f87171', bg: 'rgba(127,29,29,0.85)',  depth: 0 },
  { id: 'cli',    x: 70,  y: 105, w: 130, h: 36, label: '🟠 CartLineItem', sub: 'depth 1',   clr: '#fb923c', bg: 'rgba(124,45,18,0.85)',  depth: 1 },
  { id: 'money',  x: 250, y: 105, w: 110, h: 36, label: '🟠 Money',        sub: 'depth 1',   clr: '#fb923c', bg: 'rgba(124,45,18,0.85)',  depth: 1 },
  { id: 'disc',   x: 70,  y: 185, w: 120, h: 36, label: '🟡 Discount',     sub: 'depth 2',   clr: '#fbbf24', bg: 'rgba(113,63,18,0.85)', depth: 2 },
  { id: 'curr',   x: 250, y: 185, w: 120, h: 36, label: '🟡 Currency',     sub: 'depth 2',   clr: '#fbbf24', bg: 'rgba(113,63,18,0.85)', depth: 2 },
  { id: 'coupon', x: 160, y: 262, w: 140, h: 36, label: '🟢 CouponEngine', sub: 'depth 3',   clr: '#4ade80', bg: 'rgba(20,83,45,0.85)',  depth: 3 },
]
const EDGES = [
  { s: 'cart',  t: 'cli',    clr: '#f87171' },
  { s: 'cart',  t: 'money',  clr: '#f87171' },
  { s: 'cli',   t: 'disc',   clr: '#fb923c' },
  { s: 'money', t: 'curr',   clr: '#fb923c' },
  { s: 'disc',  t: 'coupon', clr: '#fbbf24' },
]
const arrowId = clr => ({ '#f87171': 'dg-arr-r', '#fb923c': 'dg-arr-o', '#fbbf24': 'dg-arr-y' }[clr] ?? 'dg-arr-y')

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
  mkArr('dg-arr-r','#f87171')
  mkArr('dg-arr-o','#fb923c')
  mkArr('dg-arr-y','#fbbf24')

  const bottomOf = n => ({ x: n.x, y: n.y + n.h / 2 })
  const topOf    = n => ({ x: n.x, y: n.y - n.h / 2 })

  // Source node depth → edge draw delay
  const depthDelay = d => 100 + d * 240

  EDGES.forEach(e => {
    const sn = nm[e.s], tn = nm[e.t]
    const sb = bottomOf(sn), tt = topOf(tn)
    const sx = sb.x, sy = sb.y, tx = tt.x, ty = tt.y
    const my = (sy + ty) / 2
    const pathD = `M${sx},${sy} C${sx},${my} ${tx},${my} ${tx},${ty}`

    const phantom = document.createElementNS('http://www.w3.org/2000/svg', 'path')
    phantom.setAttribute('d', pathD)
    const len = phantom.getTotalLength ? phantom.getTotalLength() : 100

    const edgeDelay = depthDelay(sn.depth) + 140
    root.append('path').attr('d', pathD)
      .attr('stroke', e.clr).attr('stroke-width', 1.8).attr('fill', 'none').attr('opacity', 0.8)
      .attr('marker-end', `url(#${arrowId(e.clr)})`)
      .attr('stroke-dasharray', len).attr('stroke-dashoffset', len)
      .transition().delay(edgeDelay).duration(300).ease(d3.easeLinear)
      .attr('stroke-dashoffset', 0)
  })

  NODES.forEach(n => {
    const g = root.append('g')
      .attr('transform', `translate(${n.x},${n.y}) scale(0)`).attr('opacity', 0)
    g.transition().delay(depthDelay(n.depth)).duration(320).ease(d3.easeBackOut.overshoot(1.6))
      .attr('transform', `translate(${n.x},${n.y}) scale(1)`).attr('opacity', 1)

    g.append('rect').attr('x', -n.w/2).attr('y', -n.h/2).attr('width', n.w).attr('height', n.h).attr('rx', 7)
      .attr('fill', n.bg).attr('stroke', n.clr).attr('stroke-width', 1.8)
    g.append('text').attr('y', -3).attr('text-anchor','middle')
      .attr('font-size', 11).attr('font-weight', 700).attr('fill', n.clr).text(n.label)
    g.append('text').attr('y', 11).attr('text-anchor','middle')
      .attr('font-size', 9).attr('fill','#cbd5e1').attr('opacity', 0.75).text(n.sub)
  })
})
</script>

<style scoped>
.dep-graph-diagram { display: block; max-width: 100%; }
</style>
