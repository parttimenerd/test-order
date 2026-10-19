<template>
  <svg ref="svg" class="dep-graph-diagram" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'
import { isDark } from '../../conference-60min/dark'

const svg = ref(null)

const W = 320, H = 360

const NODES_DARK = [
  { id: 'cart',   x: 160, y: 38,  w: 148, h: 44, label: 'Cart.java',    sub: 'edited',        clr: '#f87171', bg: 'rgba(127,29,29,0.92)',  depth: 0 },
  { id: 'cli',    x: 72,  y: 124, w: 134, h: 44, label: 'CartLineItem', sub: 'used by Cart',   clr: '#fb923c', bg: 'rgba(124,45,18,0.92)',  depth: 1 },
  { id: 'money',  x: 250, y: 124, w: 112, h: 44, label: 'Money',        sub: 'used by Cart',   clr: '#fb923c', bg: 'rgba(124,45,18,0.92)',  depth: 1 },
  { id: 'disc',   x: 72,  y: 214, w: 120, h: 44, label: 'Discount',     sub: 'used by Item',   clr: '#fbbf24', bg: 'rgba(113,63,18,0.92)', depth: 2 },
  { id: 'curr',   x: 250, y: 214, w: 120, h: 44, label: 'Currency',     sub: 'used by Money',  clr: '#fbbf24', bg: 'rgba(113,63,18,0.92)', depth: 2 },
  { id: 'coupon', x: 160, y: 304, w: 148, h: 44, label: 'CouponEngine', sub: 'used by Disc.',  clr: '#4ade80', bg: 'rgba(20,83,45,0.92)',  depth: 3 },
]
const NODES_LIGHT = [
  { id: 'cart',   x: 160, y: 38,  w: 148, h: 44, label: 'Cart.java',    sub: 'edited',        clr: '#dc2626', bg: 'rgba(254,226,226,0.95)', depth: 0 },
  { id: 'cli',    x: 72,  y: 124, w: 134, h: 44, label: 'CartLineItem', sub: 'used by Cart',   clr: '#ea580c', bg: 'rgba(255,237,213,0.95)', depth: 1 },
  { id: 'money',  x: 250, y: 124, w: 112, h: 44, label: 'Money',        sub: 'used by Cart',   clr: '#ea580c', bg: 'rgba(255,237,213,0.95)', depth: 1 },
  { id: 'disc',   x: 72,  y: 214, w: 120, h: 44, label: 'Discount',     sub: 'used by Item',   clr: '#d97706', bg: 'rgba(254,249,195,0.95)', depth: 2 },
  { id: 'curr',   x: 250, y: 214, w: 120, h: 44, label: 'Currency',     sub: 'used by Money',  clr: '#d97706', bg: 'rgba(254,249,195,0.95)', depth: 2 },
  { id: 'coupon', x: 160, y: 304, w: 148, h: 44, label: 'CouponEngine', sub: 'used by Disc.',  clr: '#15803d', bg: 'rgba(220,252,231,0.95)', depth: 3 },
]
const EDGES = [
  { s: 'cart',  t: 'cli',    clr: '#f87171' },
  { s: 'cart',  t: 'money',  clr: '#f87171' },
  { s: 'cli',   t: 'disc',   clr: '#fb923c' },
  { s: 'money', t: 'curr',   clr: '#fb923c' },
  { s: 'disc',  t: 'coupon', clr: '#fbbf24' },
]
const depthDelay = d => 80 + d * 220

onMounted(() => {
  const dark = isDark.value
  const NODES = dark ? NODES_DARK : NODES_LIGHT
  const subColor = dark ? '#94a3b8' : '#475569'
  const ghostTrack = dark ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.06)'

  const arrowId = clr => {
    if (dark) return ({ '#f87171': 'dg-arr-r', '#fb923c': 'dg-arr-o', '#fbbf24': 'dg-arr-y' }[clr] ?? 'dg-arr-y')
    return ({ '#dc2626': 'dg-arr-r', '#ea580c': 'dg-arr-o', '#d97706': 'dg-arr-y', '#f87171': 'dg-arr-r', '#fb923c': 'dg-arr-o', '#fbbf24': 'dg-arr-y' }[clr] ?? 'dg-arr-y')
  }

  const nm = Object.fromEntries(NODES.map(n => [n.id, n]))

  const root = d3.select(svg.value)
    .attr('viewBox', `0 0 ${W} ${H}`)
    .attr('width', '100%')
    .attr('preserveAspectRatio', 'xMidYMin meet')
    .attr('font-family', "'Inter','Helvetica Neue',sans-serif")

  const defs = root.append('defs')

  const filt = defs.append('filter').attr('id', 'dg-shadow').attr('x', '-20%').attr('y', '-20%').attr('width', '140%').attr('height', '140%')
  filt.append('feDropShadow').attr('dx', 0).attr('dy', 2).attr('stdDeviation', 4).attr('flood-color', 'rgba(0,0,0,0.45)')

  const nodeForId = id => NODES.find(n => n.id === id)
  const mkArr = (id, col) => defs.append('marker').attr('id', id)
    .attr('viewBox','0 0 10 10').attr('refX',8).attr('refY',5)
    .attr('markerWidth',5).attr('markerHeight',5).attr('orient','auto')
    .append('path').attr('d','M0,1 L9,5 L0,9 z').attr('fill', col)
  if (dark) {
    mkArr('dg-arr-r','#f87171')
    mkArr('dg-arr-o','#fb923c')
    mkArr('dg-arr-y','#fbbf24')
  } else {
    mkArr('dg-arr-r','#dc2626')
    mkArr('dg-arr-o','#ea580c')
    mkArr('dg-arr-y','#d97706')
  }

  const bottomOf = n => ({ x: n.x, y: n.y + n.h / 2 })
  const topOf    = n => ({ x: n.x, y: n.y - n.h / 2 })

  EDGES.forEach(e => {
    const sn = nm[e.s], tn = nm[e.t]
    const sb = bottomOf(sn), tt = topOf(tn)
    const sx = sb.x, sy = sb.y, tx = tt.x, ty = tt.y
    const my = (sy + ty) / 2
    const pathD = `M${sx},${sy} C${sx},${my} ${tx},${my} ${tx},${ty}`

    const phantom = document.createElementNS('http://www.w3.org/2000/svg', 'path')
    phantom.setAttribute('d', pathD)
    const len = phantom.getTotalLength ? phantom.getTotalLength() : 100

    root.append('path').attr('d', pathD)
      .attr('stroke', ghostTrack).attr('stroke-width', 2.5).attr('fill', 'none')

    const edgeClr = dark ? e.clr : (nodeForId(e.s)?.clr ?? e.clr)
    root.append('path').attr('d', pathD)
      .attr('stroke', edgeClr).attr('stroke-width', 2).attr('fill', 'none').attr('opacity', 0.8)
      .attr('marker-end', `url(#${arrowId(e.clr)})`)
      .attr('stroke-dasharray', len).attr('stroke-dashoffset', len)
      .transition().delay(depthDelay(nm[e.s].depth) + 150).duration(300).ease(d3.easeLinear)
      .attr('stroke-dashoffset', 0)
  })

  NODES.forEach(n => {
    const g = root.append('g')
      .attr('transform', `translate(${n.x},${n.y}) scale(0)`).attr('opacity', 0)
    g.transition().delay(depthDelay(n.depth)).duration(330).ease(d3.easeBackOut.overshoot(1.5))
      .attr('transform', `translate(${n.x},${n.y}) scale(1)`).attr('opacity', 1)

    g.append('rect').attr('x', -n.w/2).attr('y', -n.h/2)
      .attr('width', n.w).attr('height', n.h).attr('rx', 8)
      .attr('fill', n.bg).attr('stroke', n.clr).attr('stroke-width', 2)
      .attr('filter', 'url(#dg-shadow)')

    g.append('rect').attr('x', -n.w/2 + 5).attr('y', -n.h/2 + 2)
      .attr('width', n.w - 10).attr('height', 3).attr('rx', 2)
      .attr('fill', n.clr).attr('opacity', 0.3)

    g.append('circle').attr('cx', -n.w/2 + 13).attr('cy', 0).attr('r', 5)
      .attr('fill', n.clr).attr('opacity', 0.9)

    g.append('text').attr('x', -n.w/2 + 24).attr('y', -4)
      .attr('text-anchor','start')
      .attr('font-size', 12.5).attr('font-weight', 700).attr('fill', n.clr)
      .text(n.label)

    g.append('text').attr('x', -n.w/2 + 24).attr('y', 11)
      .attr('text-anchor','start')
      .attr('font-size', 9.5).attr('fill', subColor).attr('opacity', 0.85)
      .text(n.sub)
  })
})
</script>

<style scoped>
.dep-graph-diagram { display: block; width: 100%; max-height: 430px; }
</style>
