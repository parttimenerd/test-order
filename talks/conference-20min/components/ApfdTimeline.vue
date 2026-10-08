<template>
  <div ref="container" class="apfd-timeline" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'

const container = ref(null)

const W = 920, H = 460
const MARGIN = { top: 30, right: 96, bottom: 60, left: 152 }
const IW = W - MARGIN.left - MARGIN.right
const IH = H - MARGIN.top - MARGIN.bottom

const TESTS_ALPHA = [
  { name: 'AddressTest',  fail: false },
  { name: 'AuthTest',     fail: false },
  { name: 'CacheTest',    fail: false },
  { name: 'CartTest',     fail: false },
  { name: 'CouponTest',   fail: false },
  { name: 'InvoiceTest',  fail: false },
  { name: 'MoneyTest',    fail: false },
  { name: 'OrderTest',    fail: true  },
  { name: 'ProductTest',  fail: false },
  { name: 'ZipTest',      fail: false },
]
const TESTS_RANKED = [
  { name: 'OrderTest',    fail: true  },
  { name: 'CartTest',     fail: false },
  { name: 'CouponTest',   fail: false },
  { name: 'InvoiceTest',  fail: false },
  { name: 'MoneyTest',    fail: false },
  { name: 'ProductTest',  fail: false },
  { name: 'AuthTest',     fail: false },
  { name: 'AddressTest',  fail: false },
  { name: 'CacheTest',    fail: false },
  { name: 'ZipTest',      fail: false },
]

const ROW_H = IH / 2 - 10

function buildRow(g, tests, offsetY, accentClr, badgeBg, badgeBorder, label, labelClr, failLabel) {
  const row = g.append('g').attr('transform', `translate(0,${offsetY})`)
  const segW = IW / tests.length

  row.append('text').attr('x', -12).attr('y', ROW_H / 2 + 6)
    .attr('text-anchor', 'end').attr('font-size', 18).attr('font-weight', 600)
    .attr('fill', labelClr).attr('opacity', 0).text(label)
    .transition().delay(20).duration(300).attr('opacity', 1)

  row.append('rect').attr('x', 0).attr('y', 0)
    .attr('width', IW).attr('height', ROW_H).attr('rx', 4)
    .attr('fill', 'rgba(255,255,255,0.04)').attr('stroke', 'rgba(255,255,255,0.08)')
    .attr('opacity', 0).transition().delay(20).duration(300).attr('opacity', 1)

  const failIdx = tests.findIndex(t => t.fail)

  tests.forEach((t, i) => {
    const col    = t.fail ? accentClr  : (labelClr === '#f87171' ? '#374151' : '#1f2937')
    const border = t.fail ? accentClr  : 'rgba(255,255,255,0.08)'
    const delay  = 80 + i * 55

    row.append('rect')
      .attr('x', i * segW + 1).attr('y', 2)
      .attr('width', segW - 2).attr('height', ROW_H - 4).attr('rx', 3)
      .attr('fill', col).attr('stroke', border).attr('stroke-width', 1)
      .attr('opacity', 0)
      .transition().delay(delay).duration(200).attr('opacity', 1)

    if (t.fail) {
      row.append('text')
        .attr('x', i * segW + segW / 2).attr('y', ROW_H / 2 + 6)
        .attr('text-anchor', 'middle').attr('font-size', 15).attr('font-weight', 700)
        .attr('fill', '#fff').attr('opacity', 0)
        .transition().delay(delay + 100).duration(200).attr('opacity', 1)
        .selection().text('✗')
    }
  })

  const markerDelay = 80 + failIdx * 55 + 280
  const failX = (failIdx + 0.5) * segW

  row.append('line')
    .attr('x1', failX).attr('y1', ROW_H + 2)
    .attr('x2', failX).attr('y2', ROW_H + 2)
    .attr('stroke', accentClr).attr('stroke-width', 1.5).attr('stroke-dasharray', '3,2')
    .transition().delay(markerDelay).duration(200).attr('y2', ROW_H + 16)

  row.append('text').attr('x', failX).attr('y', ROW_H + 30)
    .attr('text-anchor', 'middle').attr('font-size', 14).attr('fill', accentClr)
    .attr('opacity', 0).text(failLabel)
    .transition().delay(markerDelay + 150).duration(200).attr('opacity', 1)
  return markerDelay
}

onMounted(() => {
  const svg = d3.select(container.value)
    .append('svg')
    .attr('viewBox', `0 0 ${W} ${H}`)
    .attr('width', W).attr('height', H)
    .attr('font-family', "'Inter','Helvetica Neue',sans-serif")

  const g = svg.append('g').attr('transform', `translate(${MARGIN.left},${MARGIN.top})`)
  const x = d3.scaleLinear().domain([0, 1]).range([0, IW])

  buildRow(g, TESTS_ALPHA, 0, '#ef4444',
    'rgba(239,68,68,0.15)', 'rgba(239,68,68,0.4)',
    'Alphabetical', '#f87171', 'failure at 80%')

  const rowBOffset = ROW_H + 32
  const rowBDelay = 680
  const rowBGroup = g.append('g').attr('transform', `translate(0,${rowBOffset})`)

  rowBGroup.append('text').attr('x', -12).attr('y', ROW_H / 2 + 6)
    .attr('text-anchor', 'end').attr('font-size', 18).attr('font-weight', 600)
    .attr('fill', '#4ade80').attr('opacity', 0).text('test-order')
    .transition().delay(rowBDelay).duration(300).attr('opacity', 1)

  rowBGroup.append('rect').attr('x', 0).attr('y', 0)
    .attr('width', IW).attr('height', ROW_H).attr('rx', 4)
    .attr('fill', 'rgba(255,255,255,0.04)').attr('stroke', 'rgba(255,255,255,0.08)')
    .attr('opacity', 0).transition().delay(rowBDelay).duration(300).attr('opacity', 1)

  const segW = IW / TESTS_RANKED.length
  const failIdxB = TESTS_RANKED.findIndex(t => t.fail)

  TESTS_RANKED.forEach((t, i) => {
    const col    = t.fail ? '#16a34a' : '#1f2937'
    const border = t.fail ? '#4ade80' : 'rgba(255,255,255,0.08)'
    const delay  = rowBDelay + i * 55

    rowBGroup.append('rect')
      .attr('x', i * segW + 1).attr('y', 2)
      .attr('width', segW - 2).attr('height', ROW_H - 4).attr('rx', 3)
      .attr('fill', col).attr('stroke', border).attr('stroke-width', 1)
      .attr('opacity', 0)
      .transition().delay(delay).duration(200).attr('opacity', 1)

    if (t.fail) {
      rowBGroup.append('text')
        .attr('x', i * segW + segW / 2).attr('y', ROW_H / 2 + 6)
        .attr('text-anchor', 'middle').attr('font-size', 15).attr('font-weight', 700)
        .attr('fill', '#fff').attr('opacity', 0).text('✗')
        .transition().delay(delay + 100).duration(200).attr('opacity', 1)
    }
  })

  const markerBDelay = rowBDelay + failIdxB * 55 + 280
  const failXB = (failIdxB + 0.5) * segW
  rowBGroup.append('line')
    .attr('x1', failXB).attr('y1', ROW_H + 2).attr('x2', failXB).attr('y2', ROW_H + 2)
    .attr('stroke', '#4ade80').attr('stroke-width', 1.5).attr('stroke-dasharray', '3,2')
    .transition().delay(markerBDelay).duration(200).attr('y2', ROW_H + 16)
  rowBGroup.append('text').attr('x', failXB).attr('y', ROW_H + 30)
    .attr('text-anchor', 'middle').attr('font-size', 14).attr('fill', '#4ade80')
    .attr('opacity', 0).text('failure at 10%')
    .transition().delay(markerBDelay + 150).duration(200).attr('opacity', 1)

  const badgeBDelay = rowBDelay + (TESTS_RANKED.length - 1) * 55 + 300

  // x-axis
  const axisDelay = rowBDelay + TESTS_RANKED.length * 55 + 400
  const axisG = g.append('g').attr('transform', `translate(0,${2 * ROW_H + 36})`)
    .attr('opacity', 0)
  axisG.transition().delay(axisDelay).duration(400).attr('opacity', 1)
  axisG.append('line').attr('x1', 0).attr('x2', IW)
    .attr('stroke', 'rgba(255,255,255,0.15)').attr('stroke-width', 1)
  ;[0, 0.25, 0.5, 0.75, 1].forEach(v => {
    axisG.append('line').attr('x1', x(v)).attr('y1', 0).attr('x2', x(v)).attr('y2', 6)
      .attr('stroke', 'rgba(255,255,255,0.2)')
    axisG.append('text').attr('x', x(v)).attr('y', 20)
      .attr('text-anchor', 'middle').attr('font-size', 13).attr('fill', '#94a3b8')
      .text(`${v * 100}%`)
  })
  axisG.append('text').attr('x', IW / 2).attr('y', 38)
    .attr('text-anchor', 'middle').attr('font-size', 14).attr('fill', '#94a3b8')
    .text('← test suite execution time →')
})
</script>

<style scoped>
.apfd-timeline { display: block; width: 100%; }
</style>
