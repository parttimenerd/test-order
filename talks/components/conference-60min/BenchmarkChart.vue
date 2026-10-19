<template>
  <div ref="container" class="benchmark-chart" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'
import { isDark } from '../../conference-60min/dark'

const container = ref(null)

const W = 820, H = 300
const MARGIN = { top: 20, right: 110, bottom: 50, left: 190 }
const IW = W - MARGIN.left - MARGIN.right
const IH = H - MARGIN.top - MARGIN.bottom

const DATA = [
  { repo: 'spring-ai',          alpha: 82.1, alpha_se: 4.5,  order: 34.9, order_se: 5.2 },
  { repo: 'gson',               alpha: 61.2, alpha_se: 7.1,  order: 70.5, order_se: 5.2 },
  { repo: 'commons-io',         alpha: 56.7, alpha_se: 0.5,  order: 58.1, order_se: 0.8 },
  { repo: 'spring-petclinic',   alpha: 42.5, alpha_se: 0.7,  order: 27.0, order_se: 7.1 },
  { repo: 'commons-collections',alpha: 41.0, alpha_se: 6.5,  order:  6.4, order_se: 3.2 },
  { repo: 'guava',              alpha: 31.8, alpha_se: 3.9,  order: 11.8, order_se: 1.1 },
  { repo: 'jackson-core',       alpha: 20.2, alpha_se: 1.7,  order:  3.4, order_se: 1.0 },
  { repo: 'logbook',            alpha:  1.6, alpha_se: 0.0,  order:  1.1, order_se: 0.0 },
]

onMounted(() => {
  const dark = isDark.value
  const textMid = dark ? '#94a3b8' : '#475569'
  const textDim = dark ? '#64748b' : '#374151'
  const gridLine = dark ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.08)'
  const errLine  = dark ? 'rgba(255,255,255,0.35)' : 'rgba(0,0,0,0.35)'

  const svg = d3.select(container.value)
    .append('svg')
    .attr('viewBox', `0 0 ${W} ${H}`)
    .attr('width', W).attr('height', H)
    .attr('font-family', "'Inter','Helvetica Neue',sans-serif")
    .style('font-size', '1px')

  const g = svg.append('g').attr('transform', `translate(${MARGIN.left},${MARGIN.top})`)

  const y = d3.scaleBand().domain(DATA.map(d => d.repo)).range([0, IH]).padding(0.35)
  const x = d3.scaleLinear().domain([0, 100]).range([0, IW])

  const chrome = g.append('g').attr('opacity', 0)
  chrome.transition().duration(400).attr('opacity', 1)

  ;[25, 50, 75, 100].forEach(v => {
    chrome.append('line').attr('x1', x(v)).attr('x2', x(v)).attr('y1', 0).attr('y2', IH)
      .attr('stroke', gridLine).attr('stroke-width', 1).attr('stroke-dasharray', '2,3')
  })

  const axisG = chrome.append('g').attr('transform', `translate(0,${IH + 6})`)
  ;[0, 25, 50, 75, 100].forEach(v => {
    axisG.append('text').attr('x', x(v)).attr('y', 16)
      .attr('text-anchor', 'middle').style('font-size', '14px').attr('fill', textDim).text(`${v}%`)
  })
  axisG.append('text').attr('x', IW / 2).attr('y', 36)
    .attr('text-anchor', 'middle').style('font-size', '13px').attr('fill', textDim)
    .text('% of suite time elapsed when first failure surfaces  (lower = better)')

  const leg = chrome.append('g').attr('transform', `translate(${IW + 14}, 6)`)
  ;[
    { col: 'rgba(239,68,68,0.5)', label: 'Alphabetical' },
    { col: '#4ade80',             label: 'test-order'   },
  ].forEach((item, i) => {
    leg.append('rect').attr('x', 0).attr('y', i * 24).attr('width', 14).attr('height', 12).attr('rx', 2).attr('fill', item.col)
    leg.append('text').attr('x', 20).attr('y', i * 24 + 11).style('font-size', '14px').attr('fill', textMid).text(item.label)
  })
  leg.append('line').attr('x1', 3).attr('x2', 11).attr('y1', 58).attr('y2', 58)
    .attr('stroke', errLine).attr('stroke-width', 1.5)
  leg.append('text').attr('x', 20).attr('y', 62).style('font-size', '13px').attr('fill', textDim).text('± std err')

  DATA.forEach((d, ri) => {
    const rowDelay = 200 + ri * 130
    const yPos = y(d.repo)
    const bH   = y.bandwidth()
    const topH = bH * 0.42
    const botH = bH * 0.42
    const gap  = bH * 0.16
    const aMid = yPos + topH / 2
    const oMid = yPos + topH + gap + botH / 2

    g.append('text').attr('x', -10).attr('y', yPos + bH / 2 + 5)
      .attr('text-anchor', 'end').style('font-size', '15px').attr('font-weight', 500)
      .attr('fill', textMid).attr('opacity', 0)
      .transition().delay(rowDelay).duration(250).attr('opacity', 1)
      .selection().text(d.repo)

    g.append('rect').attr('x', 0).attr('y', yPos).attr('height', topH).attr('rx', 3)
      .attr('fill', 'rgba(239,68,68,0.22)').attr('stroke', 'rgba(239,68,68,0.5)').attr('stroke-width', 1)
      .attr('width', 0)
      .transition().delay(rowDelay).duration(380).ease(d3.easeCubicOut)
      .attr('width', x(d.alpha))

    if (d.alpha_se > 0.2) {
      const alphaErrG = g.append('g').attr('opacity', 0)
      alphaErrG.transition().delay(rowDelay + 430).duration(200).attr('opacity', 1)
      alphaErrG.append('line')
        .attr('x1', x(Math.max(0, d.alpha - d.alpha_se))).attr('x2', x(d.alpha + d.alpha_se))
        .attr('y1', aMid).attr('y2', aMid)
        .attr('stroke', 'rgba(239,68,68,0.8)').attr('stroke-width', 1.5)
      ;[-1, 1].forEach(dir => {
        alphaErrG.append('line')
          .attr('x1', x(d.alpha + dir * d.alpha_se)).attr('x2', x(d.alpha + dir * d.alpha_se))
          .attr('y1', aMid - 4).attr('y2', aMid + 4)
          .attr('stroke', 'rgba(239,68,68,0.8)').attr('stroke-width', 1.5)
      })
    }

    g.append('rect')
      .attr('x', 0).attr('y', yPos + topH + gap).attr('height', botH).attr('rx', 3)
      .attr('fill', 'rgba(74,222,128,0.18)').attr('stroke', 'rgba(74,222,128,0.6)').attr('stroke-width', 1)
      .attr('width', 0)
      .transition().delay(rowDelay + 150).duration(520).ease(d3.easeCubicOut)
      .attr('width', x(d.order))

    if (d.order_se > 0.2) {
      const orderErrG = g.append('g').attr('opacity', 0)
      orderErrG.transition().delay(rowDelay + 720).duration(200).attr('opacity', 1)
      orderErrG.append('line')
        .attr('x1', x(Math.max(0, d.order - d.order_se))).attr('x2', x(d.order + d.order_se))
        .attr('y1', oMid).attr('y2', oMid)
        .attr('stroke', 'rgba(74,222,128,0.8)').attr('stroke-width', 1.5)
      ;[-1, 1].forEach(dir => {
        const xv = d.order + dir * d.order_se
        if (xv < 0) return
        orderErrG.append('line')
          .attr('x1', x(xv)).attr('x2', x(xv))
          .attr('y1', oMid - 4).attr('y2', oMid + 4)
          .attr('stroke', 'rgba(74,222,128,0.8)').attr('stroke-width', 1.5)
      })
    }

    g.append('circle').attr('cx', x(d.order)).attr('cy', oMid)
      .attr('r', 0).attr('fill', '#4ade80')
      .transition().delay(rowDelay + 700).duration(200).ease(d3.easeBackOut.overshoot(2))
      .attr('r', 4)
  })
})
</script>

<style scoped>
.benchmark-chart { display: block; width: 100%; }
</style>
