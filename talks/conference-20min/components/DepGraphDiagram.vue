<template>
  <svg ref="svg" class="dep-graph-diagram" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'

const svg = ref(null)

const W = 320, H = 300

// Vertical BFS tree layout
// depth 0: Cart.java (red) — centre
// depth 1: CartLineItem, Money (orange) — left/right of centre
// depth 2: Discount (under CartLineItem), Currency (under Money) (yellow)
// depth 3: CouponEngine (under Discount, centred) (green)
const NODES = [
  { id: 'cart',    x: 160, y: 30,  w: 140, h: 36, label: '🔴 Cart.java',    sub: '(edited)',     clr: '#f87171', bg: 'rgba(127,29,29,0.85)' },
  { id: 'cli',     x: 70,  y: 105, w: 130, h: 36, label: '🟠 CartLineItem', sub: 'depth 1',      clr: '#fb923c', bg: 'rgba(124,45,18,0.85)' },
  { id: 'money',   x: 250, y: 105, w: 110, h: 36, label: '🟠 Money',        sub: 'depth 1',      clr: '#fb923c', bg: 'rgba(124,45,18,0.85)' },
  { id: 'disc',    x: 70,  y: 185, w: 120, h: 36, label: '🟡 Discount',     sub: 'depth 2',      clr: '#fbbf24', bg: 'rgba(113,63,18,0.85)' },
  { id: 'curr',    x: 250, y: 185, w: 120, h: 36, label: '🟡 Currency',     sub: 'depth 2',      clr: '#fbbf24', bg: 'rgba(113,63,18,0.85)' },
  { id: 'coupon',  x: 160, y: 262, w: 140, h: 36, label: '🟢 CouponEngine', sub: 'depth 3',      clr: '#4ade80', bg: 'rgba(20,83,45,0.85)' },
]

const EDGES = [
  { s: 'cart',  t: 'cli' },
  { s: 'cart',  t: 'money' },
  { s: 'cli',   t: 'disc' },
  { s: 'money', t: 'curr' },
  { s: 'disc',  t: 'coupon' },
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
  mkArr('dg-arr-r','#f87171')
  mkArr('dg-arr-o','#fb923c')
  mkArr('dg-arr-y','#fbbf24')

  const strokeFor = id => {
    if (id === 'cart') return '#f87171'
    if (id === 'cli' || id === 'money') return '#fb923c'
    return '#fbbf24'
  }
  const arrowFor = id => {
    if (id === 'cart') return 'dg-arr-r'
    if (id === 'cli' || id === 'money') return 'dg-arr-o'
    return 'dg-arr-y'
  }

  const bottomOf = n => ({ x: n.x, y: n.y + n.h / 2 })
  const topOf    = n => ({ x: n.x, y: n.y - n.h / 2 })

  EDGES.forEach(e => {
    const sn = nm[e.s], tn = nm[e.t]
    const sb = bottomOf(sn), tt = topOf(tn)
    const sx = sb.x, sy = sb.y, tx = tt.x, ty = tt.y
    const my = (sy + ty) / 2
    const path = `M${sx},${sy} C${sx},${my} ${tx},${my} ${tx},${ty}`

    root.append('path')
      .attr('d', path)
      .attr('stroke', strokeFor(e.s))
      .attr('stroke-width', 1.8)
      .attr('fill', 'none')
      .attr('opacity', 0.8)
      .attr('marker-end', `url(#${arrowFor(e.s)})`)
  })

  NODES.forEach(n => {
    const g = root.append('g')
    g.append('rect')
      .attr('x', n.x - n.w / 2).attr('y', n.y - n.h / 2)
      .attr('width', n.w).attr('height', n.h).attr('rx', 7)
      .attr('fill', n.bg).attr('stroke', n.clr).attr('stroke-width', 1.8)
    g.append('text').attr('x', n.x).attr('y', n.y - 3)
      .attr('text-anchor','middle').attr('font-size', 11).attr('font-weight', 700)
      .attr('fill', n.clr).text(n.label)
    g.append('text').attr('x', n.x).attr('y', n.y + 11)
      .attr('text-anchor','middle').attr('font-size', 9)
      .attr('fill','#cbd5e1').attr('opacity', 0.75).text(n.sub)
  })
})
</script>

<style scoped>
.dep-graph-diagram { display: block; max-width: 100%; }
</style>
