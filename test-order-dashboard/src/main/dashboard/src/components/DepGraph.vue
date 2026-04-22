<script setup lang="ts">
import { inject, watch, onMounted, nextTick, ref as vueRef } from 'vue'
import * as d3 from 'd3'
import type { DashboardState } from '../composables/useDashboard'
import { sn } from '../utils'
import type { TestEntry, MethodEntry } from '../types'

const props = defineProps<{
  label?: string
}>()

const d = inject<DashboardState>('dashboard')!

interface GNode extends d3.SimulationNodeDatum {
  id: string; type: string; changed: boolean; shortLabel: string; depCount: number
  mass?: number; pkg?: string
}
interface GLink extends d3.SimulationLinkDatum<GNode> { changed: boolean }

const searchText = vueRef('')
let liveNodeSel: d3.Selection<SVGGElement, GNode, SVGGElement, unknown> | null = null

function applySearch(q: string) {
  if (!liveNodeSel) return
  const lower = q.toLowerCase()
  liveNodeSel.selectAll<SVGCircleElement, GNode>('circle')
    .attr('opacity', (n: GNode) => !q || n.id.toLowerCase().includes(lower) ? 1 : 0.12)
  liveNodeSel.selectAll<SVGTextElement, GNode>('text')
    .attr('opacity', (n: GNode) => !q || n.id.toLowerCase().includes(lower) ? 1 : 0.12)
}

watch(searchText, q => applySearch(q))

interface GNode extends d3.SimulationNodeDatum {
  id: string; type: string; changed: boolean; shortLabel: string; depCount: number
  mass?: number; pkg?: string
}
interface GLink extends d3.SimulationLinkDatum<GNode> { changed: boolean }

function buildGraphData(): { nodes: GNode[]; links: GLink[] } {
  const mode = d.graphMode.value
  const nodeMap: Record<string, GNode> = {}
  const links: GLink[] = []
  function addNode(id: string, type: string, changed: boolean) {
    if (!nodeMap[id]) nodeMap[id] = { id, type, changed: !!changed, shortLabel: sn(id), depCount: 0 }
  }

  if (d.selectedTest.value && d.selectedMethod.value) {
    const m = d.selectedMethod.value
    const testName = d.selectedTest.value.name
    const nodeId = testName + '#' + m.name
    addNode(nodeId, 'method', false)
    nodeMap[nodeId].depCount = (m.deps || []).length
    ;(m.deps || []).forEach(dep => { addNode(dep, 'dep', d.changedSet.has(dep)); links.push({ source: nodeId, target: dep, changed: d.changedSet.has(dep) }) })
    return { nodes: Object.values(nodeMap), links }
  }

  if (mode === 'focus') {
    const selNames = d.selectedTests.value
    const focusTests = selNames.size > 0
      ? d.tests.filter(t => selNames.has(t.name))
      : (d.selectedTest.value ? [d.selectedTest.value] : [])
    if (!focusTests.length) return { nodes: [], links: [] }
    for (const t of focusTests) {
      addNode(t.name, 'test', false)
      nodeMap[t.name].depCount = (t.deps || []).length
      ;(t.deps || []).forEach(dep => { addNode(dep, 'dep', d.changedSet.has(dep)); links.push({ source: t.name, target: dep, changed: d.changedSet.has(dep) }) })
    }
  } else if (mode === 'changed') {
    d.tests.forEach(t => {
      const touched = (t.deps || []).some(dep => d.changedSet.has(dep))
      if (!touched && !d.changedSet.has(t.name)) return
      addNode(t.name, 'test', false); nodeMap[t.name].depCount = (t.deps || []).length
      ;(t.deps || []).filter(dep => d.changedSet.has(dep)).forEach(dep => { addNode(dep, 'dep', true); links.push({ source: t.name, target: dep, changed: true }) })
    })
  } else {
    d.tests.forEach(t => {
      addNode(t.name, 'test', false); nodeMap[t.name].depCount = (t.deps || []).length
      ;(t.deps || []).forEach(dep => { addNode(dep, 'dep', d.changedSet.has(dep)); links.push({ source: t.name, target: dep, changed: d.changedSet.has(dep) }) })
    })
  }
  return { nodes: Object.values(nodeMap), links }
}

function initGraph() {
  const container = document.getElementById('dg-wrap')
  if (!container) return
  d3.select(container).selectAll('*').remove()
  const { nodes, links } = buildGraphData()
  if (!nodes.length) {
    container.innerHTML = '<div class="dep-graph__empty">No dependency data for current selection</div>'
    return
  }
  const W = container.clientWidth || 700, H = container.clientHeight || 400
  const svg = d3.select(container).append('svg').attr('width', W).attr('height', H)
  const g = svg.append('g')
  svg.call(d3.zoom<SVGSVGElement, unknown>().scaleExtent([0.1, 4]).on('zoom', e => g.attr('transform', e.transform)))

  const maxDeps = Math.max(...nodes.map(n => n.depCount || 0), 1)
  nodes.forEach(n => {
    n.mass = n.type === 'test' || n.type === 'method' ? 2 + 3 * (n.depCount || 0) / maxDeps : n.changed ? 0.5 : 1
    const dotIdx = n.id.lastIndexOf('.')
    n.pkg = dotIdx > 0 ? n.id.substring(0, dotIdx) : '(default)'
  })

  const pkgMap: Record<string, GNode[]> = {}
  nodes.forEach(n => { if (!pkgMap[n.pkg!]) pkgMap[n.pkg!] = []; pkgMap[n.pkg!].push(n) })
  const pkgNames = Object.keys(pkgMap).sort()
  const pkgColors: Record<string, string> = {}
  const hues = ['210,80%', '330,70%', '120,60%', '45,75%', '270,65%', '0,70%', '180,65%', '60,70%', '300,60%', '150,70%']
  pkgNames.forEach((p, i) => pkgColors[p] = `hsla(${hues[i % hues.length].split(',')[0]},${hues[i % hues.length].split(',')[1]},0.08)`)

  const bubbleGroup = g.insert('g', 'g').attr('class', 'pkg-bubbles')
  const sim = d3.forceSimulation(nodes)
    .force('link', d3.forceLink<GNode, GLink>(links).id(n => n.id).distance(70))
    .force('charge', d3.forceManyBody().strength(nodes.length > 80 ? -80 : -150))
    .force('center', d3.forceCenter(W / 2, H / 2))
    .force('collision', d3.forceCollide(16))
    .velocityDecay(0.4)

  const link = g.append('g').selectAll('line').data(links).join('line')
    .attr('stroke', l => l.changed ? '#f59e0b' : '#334155').attr('stroke-width', 1.5).attr('stroke-opacity', 0.6)

  const node = g.append('g').selectAll<SVGGElement, GNode>('g').data(nodes).join('g')
    .call(d3.drag<SVGGElement, GNode>()
      .on('start', (e, n) => { if (!e.active) sim.alphaTarget(0.3).restart(); n.fx = n.x; n.fy = n.y })
      .on('drag', (e, n) => { n.fx = e.x; n.fy = e.y })
      .on('end', (e, n) => { if (!e.active) sim.alphaTarget(0); n.fx = null; n.fy = null }))

  liveNodeSel = node as unknown as d3.Selection<SVGGElement, GNode, SVGGElement, unknown>

  node.append('circle')
    .attr('r', n => { const base = n.type === 'test' || n.type === 'method' ? 9 : 6; return base + 1.5 * (n.mass || 1) })
    .attr('fill', n => n.type === 'test' || n.type === 'method' ? '#3b82f6' : n.changed ? '#ef4444' : '#64748b')
    .attr('stroke', '#0f172a').attr('stroke-width', 1.5)

  node.append('text').attr('text-anchor', 'middle').attr('dy', n => n.type === 'test' ? 24 : 18)
    .attr('font-size', '9px').attr('fill', '#94a3b8').text(n => n.shortLabel)

  const tip = d3.select(container).append('div').attr('class', 'dep-graph__tooltip')
  node.on('mouseover', (e, n) => {
    tip.style('opacity', '1').html(`<strong>${n.id}</strong><br><span style="color:#64748b">${n.type === 'test' ? n.depCount + ' deps' : 'dep · ' + (n.changed ? '<span style=color:#ef4444>changed</span>' : 'unchanged')}</span><br><span style="color:#475569;font-size:9px">pkg: ${n.pkg}</span>`)
  }).on('mousemove', e => tip.style('left', (e.offsetX + 12) + 'px').style('top', (e.offsetY - 10) + 'px'))
    .on('mouseout', () => tip.style('opacity', '0'))

  function updateBubbles() {
    bubbleGroup.selectAll('*').remove()
    for (const [pkg, members] of Object.entries(pkgMap)) {
      if (members.length < 2) continue
      const xs = members.map(n => n.x!), ys = members.map(n => n.y!)
      const pad = 22
      const x0 = Math.min(...xs) - pad, y0 = Math.min(...ys) - pad
      const x1 = Math.max(...xs) + pad, y1 = Math.max(...ys) + pad
      bubbleGroup.append('rect').attr('x', x0).attr('y', y0).attr('width', x1 - x0).attr('height', y1 - y0)
        .attr('rx', 12).attr('ry', 12)
        .attr('fill', pkgColors[pkg] || 'rgba(100,100,100,.06)')
        .attr('stroke', (pkgColors[pkg] || 'rgba(100,100,100,.06)').replace('0.08', '0.2'))
        .attr('stroke-width', 1)
      bubbleGroup.append('text').attr('x', x0 + 6).attr('y', y0 + 12)
        .attr('font-size', '8px').attr('fill', '#64748b').attr('font-weight', '600')
        .text(pkg.split('.').pop()!)
    }
  }

  sim.on('tick', () => {
    link.attr('x1', l => (l.source as GNode).x!).attr('y1', l => (l.source as GNode).y!)
      .attr('x2', l => (l.target as GNode).x!).attr('y2', l => (l.target as GNode).y!)
    node.attr('transform', n => `translate(${n.x},${n.y})`)
    updateBubbles()
  }).on('end', () => applySearch(searchText.value))
}

watch([() => d.selectedTest.value, () => d.selectedMethod.value, () => d.graphMode.value, () => d.selectedTests.value], () => {
  if (d.activeTab.value === 'tests') nextTick(initGraph)
})

onMounted(() => nextTick(initGraph))

defineExpose({ initGraph })
</script>

<template>
  <div style="margin-top:12px">
    <div style="display:flex;align-items:center;gap:8px;margin-bottom:8px;flex-wrap:wrap">
      <span style="font-size:.73rem;color:var(--text-dim)">
        {{ label || 'Dependency Graph' }}<span v-if="d.selectedMethod.value" style="color:var(--accent-light)"> ({{ d.selectedMethod.value.name }})</span>:
      </span>
      <button
        v-for="m in d.GMODES"
        :key="m.id"
        :disabled="m.id === 'full' && d.totalNodes.value > 300"
        @click="d.setGraphMode(m.id)"
        class="dep-graph__btn"
        :class="{ 'dep-graph__btn--active': d.graphMode.value === m.id, 'dep-graph__btn--disabled': m.id === 'full' && d.totalNodes.value > 300 }"
      >
        {{ m.label }}<span v-if="m.id === 'full' && d.totalNodes.value > 300" style="color:var(--orange)"> ({{ d.totalNodes.value }})</span>
      </button>
      <input
        v-model="searchText"
        class="dep-graph__search"
        placeholder="Search nodes…"
        title="Filter visible nodes by class or method name"
      />
      <span style="margin-left:auto;font-size:.68rem;color:var(--text-muted);display:flex;gap:10px">
        <span><span class="dep-graph__dot" style="background:#3b82f6"></span>test</span>
        <span><span class="dep-graph__dot" style="background:#ef4444"></span>changed</span>
        <span><span class="dep-graph__dot" style="background:var(--text-dim)"></span>dep</span>
      </span>
    </div>
    <div id="dg-wrap" style="height:350px;background:var(--bg-card);border-radius:var(--radius);overflow:hidden;position:relative"></div>
  </div>
</template>

<style scoped>
.dep-graph__btn {
  padding: 3px 10px; font-size: .7rem; border-radius: 4px; border: 1px solid var(--border);
  cursor: pointer; background: transparent; color: var(--text-sec); transition: all var(--tr-fast);
}
.dep-graph__btn--active { background: #4338ca; border-color: var(--accent); color: var(--text); }
.dep-graph__btn--disabled { cursor: not-allowed; opacity: .4; }
.dep-graph__dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; vertical-align: middle; margin-right: 3px; }
.dep-graph__search {
  padding: 3px 8px; font-size: .7rem; border: 1px solid var(--border); border-radius: 4px;
  background: var(--bg-card); color: var(--text); outline: none; width: 140px;
  transition: border-color var(--tr-fast);
}
.dep-graph__search:focus { border-color: var(--accent); }
</style>

<style>
/* Tooltip inside dep graph — not scoped because it's created by D3 */
.dep-graph__tooltip {
  position: absolute; background: #1e293b; border: 1px solid #334155;
  padding: 5px 9px; border-radius: 4px; font-size: 11px;
  color: #e2e8f0; pointer-events: none; opacity: 0; z-index: 10;
}
.dep-graph__empty {
  display: flex; align-items: center; justify-content: center;
  height: 100%; color: #475569; font-size: .8rem;
}
</style>
