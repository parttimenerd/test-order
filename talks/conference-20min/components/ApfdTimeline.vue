<template>
  <div ref="container" class="apfd-timeline" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'

const container = ref(null)

const W = 820, H = 240
const MARGIN = { top: 30, right: 20, bottom: 50, left: 120 }
const IW = W - MARGIN.left - MARGIN.right
const IH = H - MARGIN.top - MARGIN.bottom

// 10 tests — alphabetical order vs. test-order order.
// Failure occurs at test #8 alphabetically (80% elapsed) vs test #1 ordered (10% elapsed).
const TESTS_ALPHA = [
  { name: 'AddressTest',  t: 0.10, fail: false },
  { name: 'AuthTest',     t: 0.20, fail: false },
  { name: 'CacheTest',    t: 0.30, fail: false },
  { name: 'CartTest',     t: 0.40, fail: false },
  { name: 'CouponTest',   t: 0.50, fail: false },
  { name: 'InvoiceTest',  t: 0.60, fail: false },
  { name: 'MoneyTest',    t: 0.70, fail: false },
  { name: 'OrderTest',    t: 0.80, fail: true  },
  { name: 'ProductTest',  t: 0.90, fail: false },
  { name: 'ZipTest',      t: 1.00, fail: false },
]
const TESTS_RANKED = [
  { name: 'OrderTest',    t: 0.10, fail: true  },
  { name: 'CartTest',     t: 0.20, fail: false },
  { name: 'CouponTest',   t: 0.30, fail: false },
  { name: 'InvoiceTest',  t: 0.40, fail: false },
  { name: 'MoneyTest',    t: 0.50, fail: false },
  { name: 'ProductTest',  t: 0.60, fail: false },
  { name: 'AuthTest',     t: 0.70, fail: false },
  { name: 'AddressTest',  t: 0.80, fail: false },
  { name: 'CacheTest',    t: 0.90, fail: false },
  { name: 'ZipTest',      t: 1.00, fail: false },
]

const ROW_H = IH / 2 - 8

onMounted(() => {
  const svg = d3.select(container.value)
    .append('svg')
    .attr('viewBox', `0 0 ${W} ${H}`)
    .attr('width', W).attr('height', H)
    .attr('font-family', "'Inter','Helvetica Neue',sans-serif")

  const g = svg.append('g').attr('transform', `translate(${MARGIN.left},${MARGIN.top})`)

  const x = d3.scaleLinear().domain([0, 1]).range([0, IW])

  // ─── Row 1: Alphabetical ───────────────────────────────────────────
  const rowA = g.append('g').attr('transform', 'translate(0,0)')

  rowA.append('text').attr('x', -8).attr('y', ROW_H / 2 + 5)
    .attr('text-anchor', 'end').attr('font-size', 12).attr('font-weight', 600)
    .attr('fill', '#94a3b8').text('Alphabetical')

  // background track
  rowA.append('rect').attr('x', 0).attr('y', 0)
    .attr('width', IW).attr('height', ROW_H).attr('rx', 4)
    .attr('fill', 'rgba(255,255,255,0.04)').attr('stroke', 'rgba(255,255,255,0.08)')

  const segW = IW / TESTS_ALPHA.length
  TESTS_ALPHA.forEach((t, i) => {
    const col = t.fail ? '#ef4444' : '#374151'
    const border = t.fail ? '#f87171' : 'rgba(255,255,255,0.1)'
    rowA.append('rect')
      .attr('x', i * segW + 1).attr('y', 2)
      .attr('width', segW - 2).attr('height', ROW_H - 4).attr('rx', 3)
      .attr('fill', col).attr('stroke', border).attr('stroke-width', 1)
    if (t.fail) {
      rowA.append('text')
        .attr('x', i * segW + segW / 2).attr('y', ROW_H / 2 + 5)
        .attr('text-anchor', 'middle').attr('font-size', 11).attr('font-weight', 700)
        .attr('fill', '#fff').text('✗')
    }
  })

  // failure marker + label
  const failIdxA = TESTS_ALPHA.findIndex(t => t.fail)
  const failXA = (failIdxA + 0.5) * segW
  rowA.append('line')
    .attr('x1', failXA).attr('y1', ROW_H + 2)
    .attr('x2', failXA).attr('y2', ROW_H + 14)
    .attr('stroke', '#f87171').attr('stroke-width', 1.5).attr('stroke-dasharray', '3,2')
  rowA.append('text').attr('x', failXA).attr('y', ROW_H + 24)
    .attr('text-anchor', 'middle').attr('font-size', 10).attr('fill', '#f87171')
    .text('failure at 80%')

  // APFD badge
  rowA.append('rect').attr('x', IW + 6).attr('y', ROW_H / 2 - 14)
    .attr('width', 54).attr('height', 28).attr('rx', 5)
    .attr('fill', 'rgba(239,68,68,0.15)').attr('stroke', 'rgba(239,68,68,0.4)')
  rowA.append('text').attr('x', IW + 33).attr('y', ROW_H / 2 + 5)
    .attr('text-anchor', 'middle').attr('font-size', 12).attr('font-weight', 700)
    .attr('fill', '#f87171').text('~50%')

  // ─── Row 2: test-order ────────────────────────────────────────────
  const rowB = g.append('g').attr('transform', `translate(0,${ROW_H + 28})`)

  rowB.append('text').attr('x', -8).attr('y', ROW_H / 2 + 5)
    .attr('text-anchor', 'end').attr('font-size', 12).attr('font-weight', 600)
    .attr('fill', '#4ade80').text('test-order')

  rowB.append('rect').attr('x', 0).attr('y', 0)
    .attr('width', IW).attr('height', ROW_H).attr('rx', 4)
    .attr('fill', 'rgba(255,255,255,0.04)').attr('stroke', 'rgba(255,255,255,0.08)')

  TESTS_RANKED.forEach((t, i) => {
    const col = t.fail ? '#16a34a' : '#1f2937'
    const border = t.fail ? '#4ade80' : 'rgba(255,255,255,0.08)'
    rowB.append('rect')
      .attr('x', i * segW + 1).attr('y', 2)
      .attr('width', segW - 2).attr('height', ROW_H - 4).attr('rx', 3)
      .attr('fill', col).attr('stroke', border).attr('stroke-width', 1)
    if (t.fail) {
      rowB.append('text')
        .attr('x', i * segW + segW / 2).attr('y', ROW_H / 2 + 5)
        .attr('text-anchor', 'middle').attr('font-size', 11).attr('font-weight', 700)
        .attr('fill', '#fff').text('✗')
    }
  })

  const failIdxB = TESTS_RANKED.findIndex(t => t.fail)
  const failXB = (failIdxB + 0.5) * segW
  rowB.append('line')
    .attr('x1', failXB).attr('y1', ROW_H + 2)
    .attr('x2', failXB).attr('y2', ROW_H + 14)
    .attr('stroke', '#4ade80').attr('stroke-width', 1.5).attr('stroke-dasharray', '3,2')
  rowB.append('text').attr('x', failXB).attr('y', ROW_H + 24)
    .attr('text-anchor', 'middle').attr('font-size', 10).attr('fill', '#4ade80')
    .text('failure at 10%')

  rowB.append('rect').attr('x', IW + 6).attr('y', ROW_H / 2 - 14)
    .attr('width', 54).attr('height', 28).attr('rx', 5)
    .attr('fill', 'rgba(74,222,128,0.12)').attr('stroke', 'rgba(74,222,128,0.4)')
  rowB.append('text').attr('x', IW + 33).attr('y', ROW_H / 2 + 5)
    .attr('text-anchor', 'middle').attr('font-size', 12).attr('font-weight', 700)
    .attr('fill', '#4ade80').text('~90%')

  // ─── x-axis ───────────────────────────────────────────────────────
  const axisG = g.append('g').attr('transform', `translate(0,${2 * ROW_H + 30})`)
  axisG.append('line').attr('x1', 0).attr('x2', IW)
    .attr('stroke', 'rgba(255,255,255,0.15)').attr('stroke-width', 1)
  ;[0, 0.25, 0.5, 0.75, 1].forEach(v => {
    axisG.append('line').attr('x1', x(v)).attr('y1', 0).attr('x2', x(v)).attr('y2', 5)
      .attr('stroke', 'rgba(255,255,255,0.2)')
    axisG.append('text').attr('x', x(v)).attr('y', 16)
      .attr('text-anchor', 'middle').attr('font-size', 10).attr('fill', '#64748b')
      .text(`${v * 100}%`)
  })
  axisG.append('text').attr('x', IW / 2).attr('y', 30)
    .attr('text-anchor', 'middle').attr('font-size', 10).attr('fill', '#475569')
    .text('← test suite execution time →')
})
</script>

<style scoped>
.apfd-timeline { display: block; width: 100%; }
</style>
