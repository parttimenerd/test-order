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

const SIGNALS = [
  { label: 'Changed test',      value: 9,  clr: '#60a5fa', desc: 'CartTest.java also edited' },
  { label: 'Package proximity', value: 2,  clr: '#34d399', desc: 'same package' },
  { label: 'Dep overlap',       value: 2,  clr: '#a78bfa', desc: '⌈1 / √8 × 5⌉ = 2' },
  { label: 'Speed bonus',       value: 1,  clr: '#fbbf24', desc: 'fast test, below median' },
]
const TOTAL = SIGNALS.reduce((s, x) => s + x.value, 0)

onMounted(() => {
  const svg = d3.select(container.value)
    .append('svg')
    .attr('viewBox', `0 0 ${W} ${H}`)
    .attr('width', W).attr('height', H)
    .attr('font-family', "'Inter','Helvetica Neue',sans-serif")

  const g = svg.append('g').attr('transform', `translate(${MARGIN.left},${MARGIN.top})`)

  const barH   = Math.min(36, (IH - (SIGNALS.length - 1) * 8) / SIGNALS.length)
  const gap    = 10
  const totalH = SIGNALS.length * (barH + gap) - gap
  const startY = (IH - totalH) / 2
  const xScale = d3.scaleLinear().domain([0, TOTAL]).range([0, IW])

  SIGNALS.forEach((sig, i) => {
    const y     = startY + i * (barH + gap)
    const delay = 100 + i * 120

    // Label fades in
    g.append('text').attr('x', -10).attr('y', y + barH / 2 + 4)
      .attr('text-anchor', 'end').attr('font-size', 12).attr('font-weight', 600)
      .attr('fill', sig.clr).attr('opacity', 0).text(sig.label)
      .transition().delay(delay).duration(250).attr('opacity', 1)

    // Track fades in
    g.append('rect').attr('x', 0).attr('y', y).attr('width', IW).attr('height', barH).attr('rx', 5)
      .attr('fill', 'rgba(255,255,255,0.04)').attr('stroke', 'rgba(255,255,255,0.07)')
      .attr('opacity', 0).transition().delay(delay).duration(250).attr('opacity', 1)

    // Bar grows from left
    g.append('rect').attr('x', 0).attr('y', y).attr('width', 0).attr('height', barH).attr('rx', 5)
      .attr('fill', sig.clr).attr('opacity', 0.85)
      .transition().delay(delay + 80).duration(450).ease(d3.easeCubicOut)
      .attr('width', xScale(sig.value))

    // Value label appears after bar settles
    g.append('text')
      .attr('x', xScale(sig.value) - 8).attr('y', y + barH / 2 + 4)
      .attr('text-anchor', 'end').attr('font-size', 13).attr('font-weight', 700)
      .attr('fill', '#fff').attr('opacity', 0).text(`+${sig.value}`)
      .transition().delay(delay + 500).duration(200).attr('opacity', 1)

    // Description to the right
    g.append('text').attr('x', IW + 10).attr('y', y + barH / 2 + 4)
      .attr('font-size', 10).attr('fill', '#64748b').attr('opacity', 0).text(sig.desc)
      .transition().delay(delay + 200).duration(300).attr('opacity', 1)
  })

  // Total bar — plays last with a glow pulse
  const totalY     = startY + SIGNALS.length * (barH + gap)
  const totalDelay = 100 + SIGNALS.length * 120 + 200

  g.append('line').attr('x1', 0).attr('x2', IW).attr('y1', totalY - 2).attr('y2', totalY - 2)
    .attr('stroke', 'rgba(255,255,255,0.15)').attr('stroke-width', 1)
    .attr('opacity', 0).transition().delay(totalDelay).duration(300).attr('opacity', 1)

  const totalRect = g.append('rect').attr('x', 0).attr('y', totalY + 2)
    .attr('width', 0).attr('height', barH * 0.8).attr('rx', 5)
    .attr('fill', 'rgba(251,191,36,0.12)').attr('stroke', 'rgba(251,191,36,0.4)').attr('stroke-width', 1.5)
  totalRect.transition().delay(totalDelay + 80).duration(500).ease(d3.easeCubicOut)
    .attr('width', IW)

  g.append('text').attr('x', -10).attr('y', totalY + barH * 0.4 + 4)
    .attr('text-anchor', 'end').attr('font-size', 13).attr('font-weight', 700)
    .attr('fill', '#fbbf24').attr('opacity', 0).text('Total')
    .transition().delay(totalDelay).duration(300).attr('opacity', 1)

  // Score number counts up 0 → 14
  const scoreText = g.append('text').attr('x', IW / 2).attr('y', totalY + barH * 0.4 + 4)
    .attr('text-anchor', 'middle').attr('font-size', 16).attr('font-weight', 800)
    .attr('fill', '#fbbf24').attr('opacity', 0).text('0')
  scoreText.transition().delay(totalDelay + 80).duration(200).attr('opacity', 1)

  // Count-up via custom tween
  const countDelay = totalDelay + 300
  const countDuration = 500
  scoreText.transition().delay(countDelay).duration(countDuration)
    .tween('text', function() {
      const interp = d3.interpolateNumber(0, TOTAL)
      return function(t) { d3.select(this).text(Math.round(interp(t))) }
    })

  // Glow pulse on total rect after count finishes
  totalRect.transition().delay(countDelay + countDuration + 50).duration(300)
    .attr('stroke', 'rgba(251,191,36,0.9)').attr('fill', 'rgba(251,191,36,0.22)')
    .transition().duration(400)
    .attr('stroke', 'rgba(251,191,36,0.4)').attr('fill', 'rgba(251,191,36,0.12)')

  g.append('text').attr('x', IW + 10).attr('y', totalY + barH * 0.4 + 4)
    .attr('font-size', 10).attr('fill', '#64748b').attr('opacity', 0).text('CartTest ranks #1')
    .transition().delay(countDelay + countDuration).duration(300).attr('opacity', 1)
})
</script>

<style scoped>
.scoring-breakdown { display: block; width: 100%; }
</style>
