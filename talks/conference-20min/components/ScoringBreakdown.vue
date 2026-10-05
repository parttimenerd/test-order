<template>
  <div ref="container" class="scoring-breakdown" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'

const container = ref(null)

const W = 820, H = 270
const MARGIN = { top: 20, right: 190, bottom: 24, left: 168 }
const IW = W - MARGIN.left - MARGIN.right
const IH = H - MARGIN.top - MARGIN.bottom

const SIGNALS = [
  { label: 'Changed test',      value: 9,  clr: '#60a5fa', desc: 'CartTest.java was also edited' },
  { label: 'Package proximity', value: 2,  clr: '#34d399', desc: 'same package as Cart.java' },
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

  const defs = svg.append('defs')
  const glow = defs.append('filter').attr('id', 'sb-glow')
  glow.append('feGaussianBlur').attr('stdDeviation', 3).attr('result', 'blur')
  const merge = glow.append('feMerge')
  merge.append('feMergeNode').attr('in', 'blur')
  merge.append('feMergeNode').attr('in', 'SourceGraphic')

  const g = svg.append('g').attr('transform', `translate(${MARGIN.left},${MARGIN.top})`)

  const barH   = Math.min(40, (IH - (SIGNALS.length - 1) * 10) / SIGNALS.length)
  const gap    = 12
  const totalH = SIGNALS.length * (barH + gap) - gap
  const startY = (IH - totalH - barH - 16) / 2
  const xScale = d3.scaleLinear().domain([0, TOTAL]).range([0, IW])

  SIGNALS.forEach((sig, i) => {
    const y     = startY + i * (barH + gap)
    const delay = 100 + i * 130

    // Label
    g.append('text').attr('x', -12).attr('y', y + barH / 2 + 5)
      .attr('text-anchor', 'end').attr('font-size', 13).attr('font-weight', 600)
      .attr('fill', sig.clr).attr('opacity', 0).text(sig.label)
      .transition().delay(delay).duration(250).attr('opacity', 1)

    // Track
    g.append('rect').attr('x', 0).attr('y', y).attr('width', IW).attr('height', barH).attr('rx', 6)
      .attr('fill', 'rgba(255,255,255,0.03)').attr('stroke', 'rgba(255,255,255,0.08)').attr('stroke-width', 1)
      .attr('opacity', 0).transition().delay(delay).duration(250).attr('opacity', 1)

    // Bar
    g.append('rect').attr('x', 0).attr('y', y).attr('width', 0).attr('height', barH).attr('rx', 6)
      .attr('fill', sig.clr).attr('opacity', 0.8)
      .transition().delay(delay + 80).duration(480).ease(d3.easeCubicOut)
      .attr('width', xScale(sig.value))

    // Value label — always outside bar to avoid clipping
    g.append('text')
      .attr('x', xScale(sig.value) + 8).attr('y', y + barH / 2 + 5)
      .attr('text-anchor', 'start').attr('font-size', 14).attr('font-weight', 800)
      .attr('fill', sig.clr).attr('opacity', 0).text(`+${sig.value}`)
      .transition().delay(delay + 520).duration(200).attr('opacity', 1)

    // Description
    g.append('text').attr('x', IW + 14).attr('y', y + barH / 2 + 5)
      .attr('font-size', 11).attr('fill', '#94a3b8').attr('opacity', 0).text(sig.desc)
      .transition().delay(delay + 220).duration(300).attr('opacity', 1)
  })

  // ── Total row ──────────────────────────────────────────────────────────
  const totalY     = startY + SIGNALS.length * (barH + gap) + 8
  const totalDelay = 100 + SIGNALS.length * 130 + 180

  // Separator
  g.append('line').attr('x1', -MARGIN.left * 0.6).attr('x2', IW + MARGIN.right * 0.6)
    .attr('y1', totalY - 6).attr('y2', totalY - 6)
    .attr('stroke', 'rgba(255,255,255,0.12)').attr('stroke-width', 1)
    .attr('opacity', 0).transition().delay(totalDelay - 80).duration(300).attr('opacity', 1)

  // Total track
  const tH = barH + 4
  const totalTrack = g.append('rect').attr('x', 0).attr('y', totalY)
    .attr('width', 0).attr('height', tH).attr('rx', 7)
    .attr('fill', 'rgba(251,191,36,0.10)').attr('stroke', 'rgba(251,191,36,0.35)').attr('stroke-width', 1.5)
  totalTrack.transition().delay(totalDelay + 60).duration(520).ease(d3.easeCubicOut)
    .attr('width', IW)

  // "Total" label
  g.append('text').attr('x', -12).attr('y', totalY + tH / 2 + 5)
    .attr('text-anchor', 'end').attr('font-size', 14).attr('font-weight', 700)
    .attr('fill', '#fbbf24').attr('opacity', 0).text('Total')
    .transition().delay(totalDelay).duration(300).attr('opacity', 1)

  // Score badge — circle with large number
  const badgeX = IW / 2
  const badgeDelay = totalDelay + 400

  g.append('circle').attr('cx', badgeX).attr('cy', totalY + tH / 2)
    .attr('r', 0).attr('fill', 'rgba(251,191,36,0.15)')
    .attr('stroke', '#fbbf24').attr('stroke-width', 2)
    .transition().delay(badgeDelay).duration(300).ease(d3.easeBackOut.overshoot(1.3))
    .attr('r', tH / 2 + 2)

  const scoreText = g.append('text').attr('x', badgeX).attr('y', totalY + tH / 2 + 6)
    .attr('text-anchor', 'middle').attr('font-size', 17).attr('font-weight', 900)
    .attr('fill', '#fbbf24').attr('opacity', 0).text('0')
  scoreText.transition().delay(badgeDelay).duration(200).attr('opacity', 1)
  scoreText.transition().delay(badgeDelay + 200).duration(500)
    .tween('text', function() {
      const interp = d3.interpolateNumber(0, TOTAL)
      return function(t) { d3.select(this).text(Math.round(interp(t))) }
    })

  // Glow pulse after count
  totalTrack.transition().delay(badgeDelay + 700).duration(320)
    .attr('stroke', 'rgba(251,191,36,0.9)').attr('fill', 'rgba(251,191,36,0.22)')
    .attr('filter', 'url(#sb-glow)')
    .transition().duration(500)
    .attr('stroke', 'rgba(251,191,36,0.4)').attr('fill', 'rgba(251,191,36,0.10)')
    .attr('filter', null)

  // "CartTest ranks #1" label
  g.append('text').attr('x', IW + 14).attr('y', totalY + tH / 2 + 5)
    .attr('font-size', 12).attr('font-weight', 600).attr('fill', '#fbbf24').attr('opacity', 0)
    .text('→ CartTest ranks #1')
    .transition().delay(badgeDelay + 700).duration(300).attr('opacity', 1)
})
</script>

<style scoped>
.scoring-breakdown { display: block; width: 100%; }
</style>
