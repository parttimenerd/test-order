<template>
  <div ref="container" class="instrumentation-overhead" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'

const container = ref(null)

const W = 640, H = 160
const MARGIN = { top: 24, right: 60, bottom: 36, left: 130 }
const IW = W - MARGIN.left - MARGIN.right
const IH = H - MARGIN.top - MARGIN.bottom

const DATA = [
  { mode: 'CLASS',              learn: 13, order: 0 },
  { mode: 'METHOD',             learn: 11, order: 0 },
  { mode: 'MEMBER (default)',   learn: 13, order: 0 },
]

onMounted(() => {
  const svg = d3.select(container.value)
    .append('svg')
    .attr('viewBox', `0 0 ${W} ${H}`)
    .attr('width', W).attr('height', H)
    .attr('font-family', "'Inter','Helvetica Neue',sans-serif")

  const g = svg.append('g').attr('transform', `translate(${MARGIN.left},${MARGIN.top})`)

  const y = d3.scaleBand().domain(DATA.map(d => d.mode)).range([0, IH]).padding(0.3)
  const x = d3.scaleLinear().domain([0, 20]).range([0, IW])

  // Chrome
  const chrome = g.append('g').attr('opacity', 0)
  chrome.transition().duration(300).attr('opacity', 1)
  ;[5, 10, 15, 20].forEach(v => {
    chrome.append('line').attr('x1', x(v)).attr('x2', x(v)).attr('y1', 0).attr('y2', IH)
      .attr('stroke', 'rgba(255,255,255,0.07)').attr('stroke-dasharray', '2,3')
  })
  const axisG = chrome.append('g').attr('transform', `translate(0,${IH + 5})`)
  ;[0, 5, 10, 15, 20].forEach(v => {
    axisG.append('text').attr('x', x(v)).attr('y', 14)
      .attr('text-anchor', 'middle').attr('font-size', 9).attr('fill', '#475569').text(`${v}%`)
  })
  axisG.append('text').attr('x', IW / 2).attr('y', 28)
    .attr('text-anchor', 'middle').attr('font-size', 10).attr('fill', '#334155')
    .text('overhead vs. uninstrumented suite')

  const leg = chrome.append('g').attr('transform', `translate(${IW - 70}, 0)`)
  ;[
    { col: 'rgba(96,165,250,0.6)',  label: 'Learn run' },
    { col: 'rgba(74,222,128,0.5)', label: 'Order run' },
  ].forEach((item, i) => {
    leg.append('rect').attr('x', 10).attr('y', i * 17).attr('width', 11).attr('height', 9).attr('rx', 2).attr('fill', item.col)
    leg.append('text').attr('x', 27).attr('y', i * 17 + 8).attr('font-size', 10).attr('fill', '#94a3b8').text(item.label)
  })

  DATA.forEach((d, i) => {
    const rowDelay = 200 + i * 160
    const yPos = y(d.mode)
    const bH   = y.bandwidth()
    const topH = bH * 0.44
    const botH = bH * 0.44
    const gap  = bH * 0.12
    const isDefault = d.mode.includes('default')

    // Label
    g.append('text').attr('x', -8).attr('y', yPos + bH / 2 + 4)
      .attr('text-anchor', 'end').attr('font-size', 11)
      .attr('font-weight', isDefault ? 700 : 400)
      .attr('fill', isDefault ? '#fbbf24' : '#94a3b8').attr('opacity', 0).text(d.mode)
      .transition().delay(rowDelay).duration(250).attr('opacity', 1)

    // Blue (learn) bar grows
    g.append('rect').attr('x', 0).attr('y', yPos).attr('height', topH).attr('rx', 3)
      .attr('fill', 'rgba(96,165,250,0.25)').attr('stroke', 'rgba(96,165,250,0.6)').attr('stroke-width', 1)
      .attr('width', 0)
      .transition().delay(rowDelay + 60).duration(400).ease(d3.easeCubicOut)
      .attr('width', x(d.learn))

    g.append('text').attr('x', x(d.learn) + 5).attr('y', yPos + topH / 2 + 4)
      .attr('font-size', 11).attr('font-weight', 700).attr('fill', '#60a5fa').attr('opacity', 0)
      .text(`+${d.learn}%`)
      .transition().delay(rowDelay + 440).duration(200).attr('opacity', 1)

    // Green (order) zero bar — minimal stub, then label
    g.append('rect').attr('x', 0).attr('y', yPos + topH + gap).attr('height', botH).attr('rx', 3)
      .attr('fill', 'rgba(74,222,128,0.12)').attr('stroke', 'rgba(74,222,128,0.4)').attr('stroke-width', 1)
      .attr('width', 0)
      .transition().delay(rowDelay + 180).duration(200).ease(d3.easeCubicOut)
      .attr('width', x(0.5))

    g.append('text').attr('x', x(0.5) + 5).attr('y', yPos + topH + gap + botH / 2 + 4)
      .attr('font-size', 11).attr('font-weight', 700).attr('fill', '#4ade80').attr('opacity', 0).text('0%')
      .transition().delay(rowDelay + 360).duration(200).attr('opacity', 1)
  })
})
</script>

<style scoped>
.instrumentation-overhead { display: block; width: 100%; }
</style>
