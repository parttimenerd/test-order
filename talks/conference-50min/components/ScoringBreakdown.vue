<template>
  <div ref="container" class="scoring-breakdown" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'

const container = ref(null)

const W = 820, H = 200
const MARGIN = { top: 18, right: 180, bottom: 30, left: 160 }
const IW = W - MARGIN.left - MARGIN.right
const IH = H - MARGIN.top - MARGIN.bottom

// Signals for CartTest (the worked example)
const SIGNALS = [
  { label: 'Changed test',      key: 'changed', value: 9,  clr: '#60a5fa', desc: 'CartTest.java also edited' },
  { label: 'Package proximity', key: 'pkg',     value: 2,  clr: '#34d399', desc: 'same package' },
  { label: 'Dep overlap',       key: 'overlap', value: 2,  clr: '#a78bfa', desc: '⌈1 / √8 × 5⌉ = 2' },
  { label: 'Speed bonus',       key: 'speed',   value: 1,  clr: '#fbbf24', desc: 'fast test, below median' },
]
const TOTAL = SIGNALS.reduce((s, x) => s + x.value, 0) // 14

onMounted(() => {
  const svg = d3.select(container.value)
    .append('svg')
    .attr('viewBox', `0 0 ${W} ${H}`)
    .attr('width', W).attr('height', H)
    .attr('font-family', "'Inter','Helvetica Neue',sans-serif")

  const g = svg.append('g').attr('transform', `translate(${MARGIN.left},${MARGIN.top})`)

  const barH = Math.min(36, (IH - (SIGNALS.length - 1) * 8) / SIGNALS.length)
  const gap = 10
  const totalHeight = SIGNALS.length * (barH + gap) - gap

  // Y positions centred
  const startY = (IH - totalHeight) / 2

  // Full width = TOTAL score units → TOTAL * scale = IW
  const xScale = d3.scaleLinear().domain([0, TOTAL]).range([0, IW])

  // background track
  SIGNALS.forEach((sig, i) => {
    const y = startY + i * (barH + gap)

    // label
    g.append('text').attr('x', -10).attr('y', y + barH / 2 + 4)
      .attr('text-anchor', 'end').attr('font-size', 12).attr('font-weight', 600)
      .attr('fill', sig.clr).text(sig.label)

    // track
    g.append('rect').attr('x', 0).attr('y', y)
      .attr('width', IW).attr('height', barH).attr('rx', 5)
      .attr('fill', 'rgba(255,255,255,0.04)').attr('stroke', 'rgba(255,255,255,0.07)')

    // bar
    g.append('rect').attr('x', 0).attr('y', y)
      .attr('width', xScale(sig.value)).attr('height', barH).attr('rx', 5)
      .attr('fill', sig.clr).attr('opacity', 0.85)

    // value label inside bar
    g.append('text')
      .attr('x', xScale(sig.value) - 8).attr('y', y + barH / 2 + 4)
      .attr('text-anchor', 'end').attr('font-size', 13).attr('font-weight', 700)
      .attr('fill', '#fff').text(`+${sig.value}`)

    // description to the right of track
    g.append('text').attr('x', IW + 10).attr('y', y + barH / 2 + 4)
      .attr('font-size', 10).attr('fill', '#64748b').text(sig.desc)
  })

  // Total bar
  const totalY = startY + SIGNALS.length * (barH + gap)
  g.append('line').attr('x1', 0).attr('x2', IW)
    .attr('y1', totalY - 2).attr('y2', totalY - 2)
    .attr('stroke', 'rgba(255,255,255,0.15)').attr('stroke-width', 1)
  g.append('rect').attr('x', 0).attr('y', totalY + 2)
    .attr('width', IW).attr('height', barH * 0.8).attr('rx', 5)
    .attr('fill', 'rgba(251,191,36,0.12)').attr('stroke', 'rgba(251,191,36,0.4)').attr('stroke-width', 1.5)
  g.append('text').attr('x', -10).attr('y', totalY + barH * 0.4 + 4)
    .attr('text-anchor', 'end').attr('font-size', 13).attr('font-weight', 700)
    .attr('fill', '#fbbf24').text('Total')
  g.append('text').attr('x', IW / 2).attr('y', totalY + barH * 0.4 + 4)
    .attr('text-anchor', 'middle').attr('font-size', 16).attr('font-weight', 800)
    .attr('fill', '#fbbf24').text('14')
  g.append('text').attr('x', IW + 10).attr('y', totalY + barH * 0.4 + 4)
    .attr('font-size', 10).attr('fill', '#64748b').text('CartTest ranks #1')
})
</script>

<style scoped>
.scoring-breakdown { display: block; width: 100%; }
</style>
