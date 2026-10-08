<template>
  <div ref="container" class="tuscan-viz" />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as d3 from 'd3'

const container = ref(null)

// A valid 5×5 Tuscan square — every ordered pair (a→b) and (b→a) appears at least once
const SQUARE = [
  ['A','B','C','D','E'],
  ['C','A','E','B','D'],
  ['E','D','A','C','B'],
  ['B','E','D','A','C'],
  ['D','C','B','E','A'],
]
const LABELS = ['A','B','C','D','E']
const N = SQUARE.length

const COLORS = {
  cell: '#1e293b',
  cellBorder: 'rgba(255,255,255,0.10)',
  highlight: '#0ea5e9',
  highlightBg: 'rgba(14,165,233,0.18)',
  labelColor: '#94a3b8',
  pairColor: '#4ade80',
  pairBg: 'rgba(74,222,128,0.15)',
  dimmed: 'rgba(255,255,255,0.08)',
  text: '#f1f5f9',
}

const W = 460, H = 310
const CELL = 40
const PAD = 5
const LEFT_PAD = 36  // for row labels
const TOP_PAD = 28   // for col labels
const GRID_W = N * (CELL + PAD) - PAD  // total grid width
const COUNTER_Y = TOP_PAD + N * (CELL + PAD) + 18  // below grid
const TOTAL_PAIRS = N * (N - 1)

onMounted(() => {
  const svg = d3.select(container.value)
    .append('svg')
    .attr('viewBox', `0 0 ${W} ${H}`)
    .attr('width', W).attr('height', H)
    .attr('font-family', "'Inter','Helvetica Neue',sans-serif")

  const g = svg.append('g').attr('transform', `translate(${PAD}, ${PAD})`)

  // Column labels
  LABELS.forEach((lbl, col) => {
    g.append('text')
      .attr('x', LEFT_PAD + col * (CELL + PAD) + CELL / 2)
      .attr('y', TOP_PAD - 8)
      .attr('text-anchor', 'middle')
      .attr('font-size', 13).attr('font-weight', 600)
      .attr('fill', COLORS.labelColor)
      .text(lbl)
  })

  // Row label prefix + grid cells (all invisible initially)
  const rowGroups = SQUARE.map((row, rowIdx) => {
    const ry = TOP_PAD + rowIdx * (CELL + PAD)
    const rg = g.append('g').attr('opacity', 0)

    // Row label
    rg.append('text')
      .attr('x', LEFT_PAD - 8).attr('y', ry + CELL / 2 + 5)
      .attr('text-anchor', 'end').attr('font-size', 11).attr('font-weight', 600)
      .attr('fill', COLORS.labelColor)
      .text(`Row ${rowIdx + 1}`)

    // Cells
    row.forEach((lbl, col) => {
      const cx = LEFT_PAD + col * (CELL + PAD)
      rg.append('rect')
        .attr('class', `cell-${rowIdx}-${col}`)
        .attr('x', cx).attr('y', ry)
        .attr('width', CELL).attr('height', CELL).attr('rx', 5)
        .attr('fill', COLORS.cell).attr('stroke', COLORS.cellBorder)

      rg.append('text')
        .attr('class', `label-${rowIdx}-${col}`)
        .attr('x', cx + CELL / 2).attr('y', ry + CELL / 2 + 5)
        .attr('text-anchor', 'middle').attr('font-size', 15).attr('font-weight', 700)
        .attr('fill', COLORS.text)
        .text(lbl)
    })

    return rg
  })

  // Pair counter display — centered below the grid
  const CX = LEFT_PAD + GRID_W / 2   // horizontal center of grid
  const counterG = g.append('g').attr('opacity', 0)
  counterG.append('text')
    .attr('class', 'counter-label')
    .attr('x', CX - 50).attr('y', COUNTER_Y + 4)
    .attr('text-anchor', 'middle').attr('font-size', 11).attr('fill', COLORS.labelColor)
    .text('pairs covered:')

  const counterNum = counterG.append('text')
    .attr('x', CX + 10).attr('y', COUNTER_Y + 4)
    .attr('text-anchor', 'middle').attr('font-size', 18).attr('font-weight', 800)
    .attr('fill', COLORS.pairColor)
    .text('0')

  counterG.append('text')
    .attr('x', CX + 30).attr('y', COUNTER_Y + 4)
    .attr('text-anchor', 'start').attr('font-size', 11).attr('fill', COLORS.labelColor)
    .text(`/ ${TOTAL_PAIRS}`)

  // Done badge (hidden initially)
  const doneG = g.append('g').attr('opacity', 0)
  doneG.append('rect')
    .attr('x', CX + 52).attr('y', COUNTER_Y - 10)
    .attr('width', 74).attr('height', 22).attr('rx', 11)
    .attr('fill', COLORS.pairBg).attr('stroke', COLORS.pairColor)
  doneG.append('text')
    .attr('x', CX + 89).attr('y', COUNTER_Y + 6)
    .attr('text-anchor', 'middle').attr('font-size', 11).attr('font-weight', 700)
    .attr('fill', COLORS.pairColor)
    .text('✓ all pairs')

  // Animate rows one at a time
  const coveredPairs = new Set()
  let pairCount = 0
  const ROW_DELAY = 600  // ms between rows

  SQUARE.forEach((row, rowIdx) => {
    const rowDelay = rowIdx * ROW_DELAY

    // Fade in the whole row
    rowGroups[rowIdx].transition()
      .delay(rowDelay).duration(250)
      .attr('opacity', 1)

    // After row appears, highlight cells and count new pairs
    setTimeout(() => {
      // Extract consecutive pairs from this row
      const newPairs = []
      for (let i = 0; i < row.length - 1; i++) {
        const pair = `${row[i]}→${row[i + 1]}`
        if (!coveredPairs.has(pair)) {
          coveredPairs.add(pair)
          newPairs.push({ col: i, colNext: i + 1 })
          pairCount++
        }
      }

      // Flash-highlight cells that contribute new pairs
      if (newPairs.length > 0) {
        newPairs.forEach(({ col, colNext }) => {
          [col, colNext].forEach(c => {
            g.select(`.cell-${rowIdx}-${c}`)
              .transition().duration(180).attr('fill', COLORS.highlightBg).attr('stroke', COLORS.highlight)
              .transition().delay(400).duration(300).attr('fill', COLORS.pairBg).attr('stroke', COLORS.pairColor)
            g.select(`.label-${rowIdx}-${c}`)
              .transition().duration(180).attr('fill', COLORS.highlight)
              .transition().delay(400).duration(300).attr('fill', COLORS.pairColor)
          })
        })
      }

      // Increment counter
      counterNum.text(pairCount)

      // Show done badge on last row
      if (rowIdx === SQUARE.length - 1) {
        doneG.transition().delay(400).duration(400).attr('opacity', 1)
      }
    }, rowDelay + 270)
  })

  // Show counter after first row
  counterG.transition().delay(300).duration(300).attr('opacity', 1)
})
</script>

<style scoped>
.tuscan-viz { display: block; width: 100%; }
</style>
