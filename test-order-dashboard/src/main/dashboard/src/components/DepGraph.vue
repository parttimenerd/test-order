<script setup lang="ts">
import { inject, watch, onMounted, nextTick, ref as vueRef, computed } from 'vue'
import * as d3 from 'd3'
import type { DashboardState } from '../composables/useDashboard'
import { esc, GRAPH } from '../utils'
import type { TestEntry, MethodEntry } from '../types'
import { useClassHover } from '../composables/useClassInfo'
import ClassInfoCard from './ClassInfoCard.vue'

const props = defineProps<{
  label?: string
}>()

const d = inject<DashboardState>('dashboard')!

const classHover = useClassHover()

interface GNode extends d3.SimulationNodeDatum {
  id: string; type: string; changed: boolean; shortLabel: string; depCount: number
  mass?: number; pkg?: string; outerClass?: string
}
interface GLink extends d3.SimulationLinkDatum<GNode> { changed: boolean }

const searchText = vueRef('')
const colorByCoverage = vueRef(false)
const pkgFilter = vueRef('')
const expandSiblings = vueRef(false)
let liveNodeSel: d3.Selection<SVGGElement, GNode, SVGGElement, unknown> | null = null
let liveLinkSel: d3.Selection<SVGLineElement, GLink, SVGGElement, unknown> | null = null
let liveGraphLinks: GLink[] = []
let liveZoom: d3.ZoomBehavior<SVGSVGElement, unknown> | null = null
let liveSvg: d3.Selection<SVGSVGElement, unknown, null, undefined> | null = null
const nodeCount = vueRef(0)
const edgeCount = vueRef(0)

function depNodeColor(nodeId: string, changed: boolean): string {
  if (colorByCoverage.value) {
    const cov = d.coverageByName.value.get(nodeId)
    if (cov && cov.totalMembers > 0) {
      const pct = cov.coveredMembers / cov.totalMembers
      return d3.interpolateRgb('#ef4444', '#22c55e')(pct)
    }
    return '#64748b'
  }
  return changed ? '#ef4444' : '#64748b'
}

function applyPkgFilter(pkg: string) {
  if (!liveNodeSel) return
  liveNodeSel.attr('display', (n: GNode) => {
    if (!pkg) return null
    return (n.pkg === pkg || n.type === 'test' || n.type === 'method') ? null : 'none'
  })
  if (liveLinkSel) liveLinkSel.attr('display', (l: GLink) => {
    if (!pkg) return null
    const s = l.source as GNode, t = l.target as GNode
    return (s.pkg === pkg || s.type === 'test' || s.type === 'method' || t.pkg === pkg || t.type === 'test' || t.type === 'method') ? null : 'none'
  })
}

watch(pkgFilter, pkg => applyPkgFilter(pkg))

// Recolor dep nodes when coverage toggle changes
watch(colorByCoverage, () => {
  if (!liveNodeSel) return
  liveNodeSel.selectAll<SVGCircleElement, GNode>('circle')
    .attr('fill', (n: GNode) => n.type === 'test' || n.type === 'method' ? '#3b82f6' : depNodeColor(n.id, n.changed))
    .attr('stroke', (n: GNode) => n.type === 'test' || n.type === 'method' ? '#1d4ed8' : n.changed ? '#b91c1c' : '#334155')
})

function resetZoom() {
  if (!liveSvg || !liveZoom) return
  const container = document.getElementById('dg-wrap')
  const cW = container?.clientWidth || 700, cH = container?.clientHeight || 420
  const allNodes = liveGraphLinks.length
    ? [...new Set([...liveGraphLinks.map(l => l.source as GNode), ...liveGraphLinks.map(l => l.target as GNode)])]
    : []
  if (!allNodes.length || !allNodes[0].x) { liveSvg.transition().duration(300).call(liveZoom.transform, d3.zoomIdentity); return }
  const pad = 40
  const xs = allNodes.map(n => n.x!), ys = allNodes.map(n => n.y!)
  const x0 = Math.min(...xs) - pad, y0 = Math.min(...ys) - pad
  const x1 = Math.max(...xs) + pad, y1 = Math.max(...ys) + pad
  const bw = x1 - x0, bh = y1 - y0
  if (bw <= 0 || bh <= 0) { liveSvg.transition().duration(300).call(liveZoom.transform, d3.zoomIdentity); return }
  const scale = Math.min(0.95, Math.min(cW / bw, cH / bh))
  const tx = (cW - bw * scale) / 2 - x0 * scale
  const ty = (cH - bh * scale) / 2 - y0 * scale
  liveSvg.transition().duration(300).call(liveZoom.transform, d3.zoomIdentity.translate(tx, ty).scale(scale))
}

function applySearch(q: string) {
  if (!liveNodeSel) return
  const lower = q.toLowerCase()
  if (!q) {
    liveNodeSel.selectAll<SVGCircleElement, GNode>('circle').attr('opacity', 1)
    liveNodeSel.selectAll<SVGTextElement, GNode>('text').attr('opacity', 1)
    if (liveLinkSel) liveLinkSel.attr('opacity', 0.6)
    return
  }
  const matchingIds = new Set<string>()
  liveGraphLinks.forEach(l => {
    const srcId = (l.source as GNode).id ?? l.source as string
    const tgtId = (l.target as GNode).id ?? l.target as string
    if (srcId.toLowerCase().includes(lower)) { matchingIds.add(srcId); matchingIds.add(tgtId) }
    if (tgtId.toLowerCase().includes(lower)) { matchingIds.add(srcId); matchingIds.add(tgtId) }
  })
  liveNodeSel.selectAll<SVGCircleElement, GNode>('circle')
    .attr('opacity', (n: GNode) => n.id.toLowerCase().includes(lower) ? 1 : matchingIds.has(n.id) ? 0.55 : 0.1)
  liveNodeSel.selectAll<SVGTextElement, GNode>('text')
    .attr('opacity', (n: GNode) => n.id.toLowerCase().includes(lower) ? 1 : matchingIds.has(n.id) ? 0.5 : 0.08)
  if (liveLinkSel) liveLinkSel.attr('opacity', (l: GLink) => {
    const srcId = (l.source as GNode).id ?? l.source as string
    const tgtId = (l.target as GNode).id ?? l.target as string
    return (matchingIds.has(srcId) && matchingIds.has(tgtId)) ? 0.8 : 0.05
  })
}

watch(searchText, q => applySearch(q))

function buildGraphData(): { nodes: GNode[]; links: GLink[] } {
  const mode = d.graphMode.value
  const nodeMap: Record<string, GNode> = {}
  const links: GLink[] = []
  function addNode(id: string, type: string, changed: boolean) {
    if (!nodeMap[id]) {
      const hash = id.indexOf('#')
      const dot = hash >= 0 ? id.lastIndexOf('.', hash) : id.lastIndexOf('.')
      const afterPkg = dot >= 0 ? id.substring(dot + 1) : id
      // For inner classes (OuterClass$Inner), show as "Outer$Inner" after pkg
      const simpleLabel = afterPkg
      nodeMap[id] = { id, type, changed: !!changed, shortLabel: simpleLabel, depCount: 0 }
    }
  }

  if (d.selectedTest.value && d.selectedMethod.value) {
    const m = d.selectedMethod.value
    const testName = d.selectedTest.value.name
    const nodeId = testName + '#' + m.name
    addNode(nodeId, 'method', false)
    if (m.memberDeps && m.memberDeps.length > 0) {
      nodeMap[nodeId].depCount = m.memberDeps.length
      m.memberDeps.forEach(memberKey => {
        const cls = memberKey.includes('#') ? memberKey.substring(0, memberKey.indexOf('#')) : memberKey
        const isChanged = d.changedSet.has(cls)
        addNode(memberKey, 'member', isChanged)
        links.push({ source: nodeId, target: memberKey, changed: isChanged })
      })
    } else {
      nodeMap[nodeId].depCount = (m.deps || []).length
      ;(m.deps || []).forEach(dep => { addNode(dep, 'dep', d.changedSet.has(dep)); links.push({ source: nodeId, target: dep, changed: d.changedSet.has(dep) }) })
    }
    return { nodes: Object.values(nodeMap), links }
  }

  if (mode === 'impact') {
    const cls = d.covSelectedClass.value
    if (!cls) return { nodes: [], links: [] }
    addNode(cls.name, 'dep', d.changedSet.has(cls.name))
    nodeMap[cls.name].depCount = cls.tests.length
    for (const tName of cls.tests) {
      addNode(tName, 'test', false)
      const t = d.tests.find(x => x.name === tName)
      nodeMap[tName].depCount = t ? (t.deps || []).length : 1
      links.push({ source: tName, target: cls.name, changed: d.changedSet.has(cls.name) })
    }
    return { nodes: Object.values(nodeMap), links }
  }

  if (mode === 'focus') {
    const selNames = d.selectedTests.value!
    const focusTests = selNames.size > 0
      ? d.tests.filter(t => selNames.has(t.name))
      : (d.selectedTest.value ? [d.selectedTest.value] : [])
    if (!focusTests.length) return { nodes: [], links: [] }
    for (const t of focusTests) {
      addNode(t.name, 'test', false)
      nodeMap[t.name].depCount = (t.deps || []).length
      ;(t.deps || []).forEach(dep => { addNode(dep, 'dep', d.changedSet.has(dep)); links.push({ source: t.name, target: dep, changed: d.changedSet.has(dep) }) })
    }
    if (expandSiblings.value) {
      const MAX_2HOP = 30
      const reachedDeps = new Set(focusTests.flatMap(t => t.deps || []))
      const seen = new Set(focusTests.map(t => t.name))
      const candidates = d.tests
        .filter(t => !seen.has(t.name) && (t.deps || []).some(dep => reachedDeps.has(dep)))
        .sort((a, b) => a.rank - b.rank)
        .slice(0, MAX_2HOP)
      for (const t of candidates) {
        seen.add(t.name)
        addNode(t.name, 'test', false)
        nodeMap[t.name].depCount = (t.deps || []).length
        for (const dep of (t.deps || [])) {
          if (reachedDeps.has(dep)) {
            links.push({ source: t.name, target: dep, changed: d.changedSet.has(dep) })
          }
        }
      }
    }
  } else if (mode === 'changed') {
    d.tests.forEach(t => {
      const touched = (t.deps || []).some(dep => d.changedSet.has(dep))
      if (!touched && !d.changedSet.has(t.name)) return
      addNode(t.name, 'test', false); nodeMap[t.name].depCount = (t.deps || []).length
      ;(t.deps || []).filter(dep => d.changedSet.has(dep)).forEach(dep => { addNode(dep, 'dep', true); links.push({ source: t.name, target: dep, changed: true }) })
    })
  } else {
    if (d.totalNodes.value > GRAPH.FULL_MODE_NODE_LIMIT) {
      const pkgDeps: Record<string, Set<string>> = {}
      d.tests.forEach(t => {
        const tPkg = t.name.substring(0, t.name.lastIndexOf('.')) || '(default)'
        if (!pkgDeps[tPkg]) pkgDeps[tPkg] = new Set()
        ;(t.deps || []).forEach(dep => {
          const dPkg = dep.substring(0, dep.lastIndexOf('.')) || '(default)'
          if (dPkg !== tPkg) pkgDeps[tPkg].add(dPkg)
        })
      })
      for (const [pkg, deps] of Object.entries(pkgDeps)) {
        addNode(pkg, 'test', false)
        nodeMap[pkg].depCount = deps.size
        nodeMap[pkg].shortLabel = pkg.split('.').pop() || pkg
        deps.forEach(depPkg => {
          addNode(depPkg, 'dep', false)
          nodeMap[depPkg].shortLabel = depPkg.split('.').pop() || depPkg
          links.push({ source: pkg, target: depPkg, changed: false })
        })
      }
    } else {
      d.tests.forEach(t => {
        addNode(t.name, 'test', false); nodeMap[t.name].depCount = (t.deps || []).length
        ;(t.deps || []).forEach(dep => { addNode(dep, 'dep', d.changedSet.has(dep)); links.push({ source: t.name, target: dep, changed: d.changedSet.has(dep) }) })
      })
    }
  }
  return { nodes: Object.values(nodeMap), links }
}

function initGraph() {
  const container = document.getElementById('dg-wrap')
  if (!container) return
  try {
  d3.select(container).selectAll('*').remove()
  const { nodes, links } = buildGraphData()
  liveGraphLinks = links
  nodeCount.value = nodes.length
  edgeCount.value = links.length
  liveZoom = null
  liveSvg = null
  if (!nodes.length) {
    const mode = d.graphMode.value
    const msg = mode === 'impact'
      ? 'No class selected for Impact mode — click a tile in the Coverage tab, or Shift+click a dep node in Focus mode'
      : mode === 'changed'
        ? 'No changed source classes detected — dep graph is empty in Changed subgraph mode'
        : mode === 'focus'
          ? 'No test selected — pick a test in the sidebar to see its dependency graph'
          : 'No dependency data available'
    container.innerHTML = `<div class="dep-graph__empty">${msg}</div>`
    return
  }
  const W = container.clientWidth || 700, H = container.clientHeight || 400
  const svg = d3.select(container).append('svg').attr('width', W).attr('height', H)
  liveSvg = svg as unknown as d3.Selection<SVGSVGElement, unknown, null, undefined>
  const g = svg.append('g')
  liveZoom = d3.zoom<SVGSVGElement, unknown>().scaleExtent([0.1, 4]).on('zoom', e => g.attr('transform', e.transform))
  svg.call(liveZoom)

  const maxDeps = Math.max(...nodes.map(n => n.depCount || 0), 1)
  nodes.forEach((n, i) => {
    n.mass = n.type === 'test' || n.type === 'method' ? 2 + 3 * (n.depCount || 0) / maxDeps : n.changed ? 0.5 : 1
    const dotIdx = n.id.lastIndexOf('.')
    n.pkg = dotIdx > 0 ? n.id.substring(0, dotIdx) : '(default)'
    // Detect inner classes: com.foo.OuterClass$Inner → outerClass = com.foo.OuterClass
    const afterPkg = dotIdx > 0 ? n.id.substring(dotIdx + 1) : n.id
    const dollarIdx = afterPkg.indexOf('$')
    n.outerClass = dollarIdx > 0 ? n.id.substring(0, dotIdx > 0 ? dotIdx + 1 + dollarIdx : dollarIdx) : undefined
    n.x = W / 2 + (Math.random() - 0.5) * Math.min(W, H) * 0.4
    n.y = H / 2 + (Math.random() - 0.5) * Math.min(W, H) * 0.4
  })

  const pkgMap: Record<string, GNode[]> = {}
  nodes.forEach(n => { if (!pkgMap[n.pkg!]) pkgMap[n.pkg!] = []; pkgMap[n.pkg!].push(n) })
  const pkgNames = Object.keys(pkgMap).sort()
  const pkgColors: Record<string, string> = {}
  const hues = ['210,80%', '330,70%', '120,60%', '45,75%', '270,65%', '0,70%', '180,65%', '60,70%', '300,60%', '150,70%']
  pkgNames.forEach((p, i) => pkgColors[p] = `hsla(${hues[i % hues.length].split(',')[0]},${hues[i % hues.length].split(',')[1]},0.08)`)

  // Build outer-class groups: a group exists when ≥2 nodes share the same outerClass
  // (the outer class node itself, if present, counts as part of its own group)
  const outerClassMap: Record<string, GNode[]> = {}
  nodes.forEach(n => {
    const key = n.outerClass ?? (n.id.includes('$') ? undefined : n.id)
    // Only group: inner-class nodes (have outerClass) AND their outer class node (if present)
    if (n.outerClass) {
      if (!outerClassMap[n.outerClass]) outerClassMap[n.outerClass] = []
      outerClassMap[n.outerClass].push(n)
    }
    // If this node IS the outer class (no $), add it to its own group so it gets pulled in
    if (!n.outerClass && nodes.some(other => other.outerClass === n.id)) {
      if (!outerClassMap[n.id]) outerClassMap[n.id] = []
      outerClassMap[n.id].push(n)
    }
  })

  const bubbleGroup = g.append('g').attr('class', 'pkg-bubbles')
  const subBubbleGroup = g.append('g').attr('class', 'class-bubbles')
  const sim = d3.forceSimulation(nodes)
    .force('link', d3.forceLink<GNode, GLink>(links).id(n => n.id).distance(GRAPH.LINK_DISTANCE))
    .force('charge', d3.forceManyBody().strength(nodes.length > GRAPH.CHARGE_THRESHOLD ? GRAPH.CHARGE_LARGE : GRAPH.CHARGE_SMALL))
    .force('center', d3.forceCenter(W / 2, H / 2))
    .force('collision', d3.forceCollide(GRAPH.COLLISION_RADIUS))
    .velocityDecay(0.4)

  // Clustering force: pull inner-class nodes toward their group centroid
  const outerClassGroups = Object.entries(outerClassMap).filter(([, m]) => m.length >= 2)

  // Compute connected components for separation force
  function getComponents(nodes: GNode[], links: GLink[]): GNode[][] {
    const adj = new Map<string, Set<string>>()
    nodes.forEach(n => adj.set(n.id, new Set()))
    links.forEach(l => {
      const sid = (l.source as GNode).id ?? l.source as string
      const tid = (l.target as GNode).id ?? l.target as string
      adj.get(sid)?.add(tid); adj.get(tid)?.add(sid)
    })
    const visited = new Set<string>()
    const components: GNode[][] = []
    const nodeById = new Map(nodes.map(n => [n.id, n]))
    for (const n of nodes) {
      if (visited.has(n.id)) continue
      const comp: GNode[] = []
      const stack = [n.id]
      while (stack.length) {
        const id = stack.pop()!
        if (visited.has(id)) continue
        visited.add(id)
        const node = nodeById.get(id)
        if (node) comp.push(node)
        adj.get(id)?.forEach(nb => { if (!visited.has(nb)) stack.push(nb) })
      }
      if (comp.length) components.push(comp)
    }
    return components
  }

  const components = getComponents(nodes, links)
  if (components.length > 1) {
    // Spread initial positions: place each component in its own region of the canvas
    const cols = Math.ceil(Math.sqrt(components.length))
    components.forEach((comp, i) => {
      const col = i % cols, row = Math.floor(i / cols)
      const cx = W * (col + 0.5) / cols
      const cy = H * (row + 0.5) / Math.ceil(components.length / cols)
      comp.forEach(n => { n.x = cx + (Math.random() - 0.5) * 60; n.y = cy + (Math.random() - 0.5) * 60 })
    })

    // Component repulsion force: push component centroids apart
    sim.force('componentSep', () => {
      if (components.length < 2) return
      const strength = 0.08
      const centroids = components.map(comp => ({
        cx: comp.reduce((s, n) => s + (n.x || 0), 0) / comp.length,
        cy: comp.reduce((s, n) => s + (n.y || 0), 0) / comp.length,
      }))
      for (let i = 0; i < components.length; i++) {
        for (let j = i + 1; j < components.length; j++) {
          const dx = centroids[i].cx - centroids[j].cx
          const dy = centroids[i].cy - centroids[j].cy
          const dist = Math.sqrt(dx * dx + dy * dy) || 1
          const minDist = 200 + Math.sqrt(components[i].length + components[j].length) * 20
          if (dist < minDist) {
            const fx = (dx / dist) * strength * (minDist - dist)
            const fy = (dy / dist) * strength * (minDist - dist)
            components[i].forEach(n => { n.vx = (n.vx || 0) + fx; n.vy = (n.vy || 0) + fy })
            components[j].forEach(n => { n.vx = (n.vx || 0) - fx; n.vy = (n.vy || 0) - fy })
          }
        }
      }
    })
  }

  if (outerClassGroups.length > 0) {
    sim.force('classCluster', () => {
      const strength = 0.15
      for (const [, members] of outerClassGroups) {
        const cx = members.reduce((s, n) => s + (n.x || 0), 0) / members.length
        const cy = members.reduce((s, n) => s + (n.y || 0), 0) / members.length
        for (const n of members) {
          n.vx = (n.vx || 0) + (cx - (n.x || 0)) * strength
          n.vy = (n.vy || 0) + (cy - (n.y || 0)) * strength
        }
      }
    })
  }

  // Package bubble separation force: push packages apart when their bounding boxes overlap
  const pkgEntries = Object.entries(pkgMap).filter(([, m]) => m.length >= 1)
  if (pkgEntries.length >= 2) {
    sim.force('pkgSep', () => {
      const strength = 0.12
      const pad = GRAPH.BUBBLE_PAD + 8
      // Compute bounding box for each package
      const boxes = pkgEntries.map(([, members]) => {
        const xs = members.map(n => n.x || 0)
        const ys = members.map(n => n.y || 0)
        return { x0: Math.min(...xs) - pad, y0: Math.min(...ys) - pad, x1: Math.max(...xs) + pad, y1: Math.max(...ys) + pad, members }
      })
      for (let i = 0; i < boxes.length; i++) {
        for (let j = i + 1; j < boxes.length; j++) {
          const a = boxes[i], b = boxes[j]
          const overlapX = Math.min(a.x1, b.x1) - Math.max(a.x0, b.x0)
          const overlapY = Math.min(a.y1, b.y1) - Math.max(a.y0, b.y0)
          if (overlapX <= 0 || overlapY <= 0) continue
          // Push along the axis of smallest overlap to separate the boxes
          const acx = (a.x0 + a.x1) / 2, acy = (a.y0 + a.y1) / 2
          const bcx = (b.x0 + b.x1) / 2, bcy = (b.y0 + b.y1) / 2
          let fx = 0, fy = 0
          if (overlapX < overlapY) {
            fx = (acx > bcx ? 1 : -1) * overlapX * strength
          } else {
            fy = (acy > bcy ? 1 : -1) * overlapY * strength
          }
          a.members.forEach(n => { n.vx = (n.vx || 0) + fx; n.vy = (n.vy || 0) + fy })
          b.members.forEach(n => { n.vx = (n.vx || 0) - fx; n.vy = (n.vy || 0) - fy })
        }
      }
    })
  }

  const link = g.append('g').selectAll('line').data(links).join('line')
    .attr('stroke', l => l.changed ? '#f59e0b' : '#334155').attr('stroke-width', 1.5).attr('stroke-opacity', 0.6)
  liveLinkSel = link as unknown as d3.Selection<SVGLineElement, GLink, SVGGElement, unknown>

  const node = g.append('g').selectAll<SVGGElement, GNode>('g').data(nodes).join('g')
    .call(d3.drag<SVGGElement, GNode>()
      .on('start', (e, n) => { if (!e.active) sim.alphaTarget(0.3).restart(); n.fx = n.x; n.fy = n.y })
      .on('drag', (e, n) => { n.fx = e.x; n.fy = e.y })
      .on('end', (e, n) => { if (!e.active) sim.alphaTarget(0); n.fx = null; n.fy = null }))

  liveNodeSel = node as unknown as d3.Selection<SVGGElement, GNode, SVGGElement, unknown>

  node.append('circle')
    .attr('r', n => { const base = n.type === 'test' || n.type === 'method' ? 10 : 7; return base + 1.5 * (n.mass || 1) })
    .attr('fill', n => n.type === 'test' || n.type === 'method' ? '#3b82f6' : depNodeColor(n.id, n.changed))
    .attr('stroke', n => n.type === 'test' || n.type === 'method' ? '#1d4ed8' : n.changed ? '#b91c1c' : '#334155')
    .attr('stroke-width', 1.5)

  const nodeLabel = node.append('text').attr('text-anchor', 'middle').attr('dy', n => n.type === 'test' ? 24 : 18)
    .attr('font-size', '9.5px').attr('fill', '#e2e8f0').attr('font-weight', '600')
    .attr('paint-order', 'stroke').attr('stroke', 'rgba(0,0,0,0.85)').attr('stroke-width', '3px').attr('stroke-linejoin', 'round')
    .text(n => n.shortLabel)

  nodeLabel.append('title').text(n => n.id)

  const tip = d3.select(container).append('div').attr('class', 'dep-graph__tooltip')
  const testNames = new Set(d.tests.map(t => t.name))
  node.on('mouseover', (e, n) => {
    const isTest = n.type === 'test' || n.type === 'method' || testNames.has(n.id)
    const isDep = n.type === 'dep'
    const isMember = n.type === 'member'
    const hasCov = isDep && d.coverageByName.value.has(n.id)
    const covStr = hasCov
      ? (() => {
          const c = d.coverageByName.value.get(n.id)!
          return c.totalMembers > 0 ? ` · ${c.coveredMembers}/${c.totalMembers} methods` : ''
        })()
      : ''
    const innerStr = n.outerClass
      ? `<br><span style="color:#818cf8;font-size:9px">inner class of ${esc(n.outerClass)}</span>`
      : ''
    const hint = isTest ? '<br><span style="color:#818cf8;font-size:9px">click → go to test</span>'
      : isDep ? `<br><span style="color:#64748b;font-size:9px">click → inspect coverage</span>`
      : isMember ? `<br><span style="color:#64748b;font-size:9px">click → inspect class coverage</span>` : ''
    const nodeDetail = isMember
      ? `member · ${n.changed ? '<span style=color:#ef4444>class changed</span>' : 'class unchanged'}`
      : n.type === 'test' ? n.depCount + ' deps' : `dep · ${n.changed ? '<span style=color:#ef4444>changed</span>' : 'unchanged'}${covStr}`
    tip.style('opacity', '1').html(
      `<strong>${esc(n.id)}</strong><br>` +
      `<span style="color:#64748b">${nodeDetail}</span>` +
      `<br><span style="color:#475569;font-size:9px">pkg: ${esc(n.pkg)}</span>${innerStr}${hint}`
    )
    classHover.show(n.type === 'member' && n.id.includes('#') ? n.id.substring(0, n.id.indexOf('#')) : n.id, e)
  }).on('mousemove', e => { tip.style('left', (e.offsetX + 12) + 'px').style('top', (e.offsetY - 10) + 'px'); classHover.move(e) })
    .on('mouseout', () => { tip.style('opacity', '0'); classHover.hide() })
    .on('click', (e, n) => {
      if (n.type === 'test' || n.type === 'method' || testNames.has(n.id)) {
        d.navigateToTestFromCov(n.id)
      } else if (n.type === 'member') {
        const cls = n.id.includes('#') ? n.id.substring(0, n.id.indexOf('#')) : n.id
        d.navigateToCovClass(cls)
      } else if (n.type === 'dep') {
        if ((e as MouseEvent).shiftKey) {
          d.setImpactClass(n.id)
        } else {
          d.navigateToCovClass(n.id)
        }
      }
    })

  function updateBubbles() {
    const entries = Object.entries(pkgMap).filter(([, m]) => m.length >= 2)
    const rects = bubbleGroup.selectAll<SVGRectElement, [string, GNode[]]>('rect')
      .data(entries, ([pkg]) => pkg)
    rects.exit().remove()
    const enter = rects.enter().append('rect').attr('rx', 12).attr('ry', 12).attr('stroke-width', 1)
    const all = enter.merge(rects)
    all.each(function([pkg, members]) {
      const xs = members.map(n => n.x!), ys = members.map(n => n.y!)
      const pad = GRAPH.BUBBLE_PAD
      const x0 = Math.min(...xs) - pad, y0 = Math.min(...ys) - pad
      const x1 = Math.max(...xs) + pad, y1 = Math.max(...ys) + pad
      d3.select(this).attr('x', x0).attr('y', y0).attr('width', x1 - x0).attr('height', y1 - y0)
        .attr('fill', pkgColors[pkg] || 'rgba(100,100,100,.06)')
        .attr('stroke', (pkgColors[pkg] || 'rgba(100,100,100,.06)').replace('0.08', '0.2'))
    })
    const labels = bubbleGroup.selectAll<SVGTextElement, [string, GNode[]]>('text')
      .data(entries, ([pkg]) => pkg)
    labels.exit().remove()
    const enterL = labels.enter().append('text').attr('font-size', '8.5px').attr('fill', '#94a3b8').attr('font-weight', '700')
      .attr('paint-order', 'stroke').attr('stroke', 'rgba(0,0,0,0.7)').attr('stroke-width', '2px').attr('stroke-linejoin', 'round')
    enterL.merge(labels).each(function([pkg, members]) {
      const xs = members.map(n => n.x!), ys = members.map(n => n.y!)
      const pad = GRAPH.BUBBLE_PAD
      d3.select(this).attr('x', Math.min(...xs) - pad + 6).attr('y', Math.min(...ys) - pad + 12)
        .text(pkg.split('.').pop()!)
        .selectAll('title')
        .data([pkg])
        .join('title')
        .text(p => p)
    })

    // Sub-bubbles for inner class groups (tighter, dashed border)
    const subEntries = outerClassGroups
    const subRects = subBubbleGroup.selectAll<SVGRectElement, [string, GNode[]]>('rect')
      .data(subEntries, ([outerCls]) => outerCls)
    subRects.exit().remove()
    const subEnter = subRects.enter().append('rect')
      .attr('rx', 7).attr('ry', 7).attr('stroke-width', 1)
      .attr('stroke-dasharray', '3,2')
      .attr('fill', 'rgba(99,102,241,.07)')
      .attr('stroke', 'rgba(99,102,241,.35)')
    subEnter.merge(subRects).each(function([, members]) {
      const xs = members.map(n => n.x!), ys = members.map(n => n.y!)
      const pad = 14
      const x0 = Math.min(...xs) - pad, y0 = Math.min(...ys) - pad
      const x1 = Math.max(...xs) + pad, y1 = Math.max(...ys) + pad
      d3.select(this).attr('x', x0).attr('y', y0).attr('width', x1 - x0).attr('height', y1 - y0)
    })
    // Sub-bubble labels: the outer class simple name
    const subLabels = subBubbleGroup.selectAll<SVGTextElement, [string, GNode[]]>('text')
      .data(subEntries, ([outerCls]) => outerCls)
    subLabels.exit().remove()
    const subEnterL = subLabels.enter().append('text')
      .attr('font-size', '7.5px').attr('fill', '#818cf8').attr('font-weight', '600')
      .attr('paint-order', 'stroke').attr('stroke', 'rgba(0,0,0,0.7)').attr('stroke-width', '2px')
    subEnterL.merge(subLabels).each(function([outerCls, members]) {
      const xs = members.map(n => n.x!), ys = members.map(n => n.y!)
      const pad = 14
      const dot = outerCls.lastIndexOf('.')
      const simpleName = dot >= 0 ? outerCls.substring(dot + 1) : outerCls
      d3.select(this).attr('x', Math.min(...xs) - pad + 4).attr('y', Math.min(...ys) - pad + 10)
        .text(simpleName)
    })
  }

  let tickCount = 0
  sim.on('tick', () => {
    link.attr('x1', l => (l.source as GNode).x!).attr('y1', l => (l.source as GNode).y!)
      .attr('x2', l => (l.target as GNode).x!).attr('y2', l => (l.target as GNode).y!)
    node.attr('transform', n => `translate(${n.x},${n.y})`)
    if (++tickCount % GRAPH.BUBBLE_TICK_INTERVAL === 0) updateBubbles()
  }).on('end', () => {
    updateBubbles()
    applySearch(searchText.value)
    applyPkgFilter(pkgFilter.value)
    if (nodes.length && liveSvg && liveZoom) {
      const pad = 40
      const xs = nodes.map(n => n.x!), ys = nodes.map(n => n.y!)
      const x0 = Math.min(...xs) - pad, y0 = Math.min(...ys) - pad
      const x1 = Math.max(...xs) + pad, y1 = Math.max(...ys) + pad
      const bw = x1 - x0, bh = y1 - y0
      if (bw > 0 && bh > 0) {
        const scale = Math.min(0.95, Math.min(W / bw, H / bh))
        const tx = (W - bw * scale) / 2 - x0 * scale
        const ty = (H - bh * scale) / 2 - y0 * scale
        liveSvg.call(liveZoom.transform, d3.zoomIdentity.translate(tx, ty).scale(scale))
      }
    }
  })
  } catch (e) { console.error('[dashboard] Dep graph failed:', e) }
}

watch([() => d.selectedTest.value, () => d.selectedMethod.value, () => d.graphMode.value, () => d.selectedTests.value, () => d.covSelectedClass.value, expandSiblings], () => {
  if (d.activeTab.value === 'tests') nextTick(initGraph)
})

onMounted(() => nextTick(initGraph))

defineExpose({ initGraph })
</script>

<template>
  <div style="margin-top:12px">
    <!-- Sticky toolbar -->
    <div style="position:sticky;top:0;z-index:5;background:var(--bg-base);padding:4px 0 6px;display:flex;align-items:center;gap:6px;flex-wrap:wrap">
      <span style="font-size:.73rem;color:var(--text-sec);flex-shrink:0">
        {{ label || 'Dependency Graph' }}<span v-if="d.selectedMethod.value" style="color:var(--accent-light)"> ({{ d.selectedMethod.value.name }})</span>:
      </span>
      <button
        v-for="m in d.GMODES"
        :key="m.id"
        @click="d.setGraphMode(m.id)"
        class="dep-graph__btn"
        :class="{ 'dep-graph__btn--active': d.graphMode.value === m.id }"
        :disabled="m.id === 'impact' && !d.covSelectedClass.value"
        :title="m.id === 'impact' ? (d.covSelectedClass.value ? 'Show all tests covering ' + d.covSelectedClass.value.name : 'Pick a class in Coverage or shift-click a dep node first') : m.id === 'focus' ? 'Show the selected test\'s direct source-class dependencies' : m.id === 'changed' ? 'Show only tests and deps that overlap with changed source classes' : m.id === 'full' ? 'Show the complete bipartite dep graph (tests + all deps). Falls back to package view when too many nodes.' : ''"
      >
        {{ m.label }}<span v-if="m.id === 'full' && d.totalNodes.value > GRAPH.FULL_MODE_NODE_LIMIT" style="color:var(--orange)"> ({{ d.totalNodes.value }} → pkg)</span>
      </button>
      <button
        v-if="d.graphMode.value === 'focus'"
        class="dep-graph__btn"
        :class="{ 'dep-graph__btn--active': expandSiblings }"
        title="Add sibling tests that share at least one dep with the focused tests (capped at 30)"
        @click="expandSiblings = !expandSiblings"
      >🔗 +siblings</button>
      <div class="dep-graph__search-wrap">
        <input
          v-model="searchText"
          class="dep-graph__search"
          placeholder="Search nodes…"
          title="Filter visible nodes by class or method name"
        />
        <button v-if="searchText" class="dep-graph__search-clear" @click="searchText = ''" title="Clear search">×</button>
      </div>
      <!-- Coverage color toggle -->
      <button
        class="dep-graph__btn"
        :class="{ 'dep-graph__btn--active': colorByCoverage }"
        :disabled="!d.hasMethodCoverage.value"
        :title="d.hasMethodCoverage.value ? 'Color source-class nodes by method coverage %' : 'Method coverage data not available'"
        @click="colorByCoverage = !colorByCoverage"
      >⬤ by cov</button>
      <!-- Package filter -->
      <select
        v-model="pkgFilter"
        class="dep-graph__select"
        title="Show only nodes from this package"
      >
        <option value="">All packages</option>
        <option v-for="pkg in d.covPackages.value" :key="pkg" :value="pkg">{{ pkg.split('.').slice(-2).join('.') }}</option>
      </select>
      <button class="dep-graph__btn" @click="resetZoom()" title="Reset zoom and pan to default">⊙ Reset</button>
      <span v-if="nodeCount > 0" style="font-size:.62rem;color:var(--text-muted)">{{ nodeCount }} nodes · {{ edgeCount }} edges</span>
      <!-- Inline legend -->
      <span style="margin-left:auto;font-size:.68rem;color:var(--text-muted);display:flex;gap:10px;align-items:center">
        <template v-if="colorByCoverage">
          <span><span class="dep-graph__dot" style="background:#3b82f6"></span>test</span>
          <span><span class="dep-graph__dot" style="background:#ef4444"></span>0% cov</span>
          <span><span class="dep-graph__dot" style="background:#22c55e"></span>100% cov</span>
          <span><span class="dep-graph__dot" style="background:#64748b"></span>no data</span>
        </template>
        <template v-else>
          <span><span class="dep-graph__dot" style="background:#3b82f6"></span>test</span>
          <span><span class="dep-graph__dot" style="background:#ef4444"></span>changed</span>
          <span><span class="dep-graph__dot" style="background:var(--text-sec)"></span>dep</span>
        </template>
      </span>
    </div>
    <div id="dg-wrap" style="height:420px;background:var(--bg-card);border-radius:var(--radius);overflow:hidden;position:relative"></div>
  </div>
  <ClassInfoCard v-if="classHover.visible.value" :info="classHover.info.value" :x="classHover.x.value" :y="classHover.y.value" />
</template>

<style scoped>
.dep-graph__btn {
  padding: 3px 10px; font-size: .7rem; border-radius: 4px; border: 1px solid var(--border);
  cursor: pointer; background: transparent; color: var(--text-sec); transition: all var(--tr-fast);
}
.dep-graph__btn--active { background: #4338ca; border-color: var(--accent); color: var(--text); }
.dep-graph__btn--disabled { cursor: not-allowed; opacity: .4; }
.dep-graph__btn:disabled { cursor: not-allowed; opacity: .4; }
.dep-graph__dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; vertical-align: middle; margin-right: 3px; }
.dep-graph__search {
  padding: 3px 22px 3px 8px; font-size: .7rem; border: 1px solid var(--border); border-radius: 4px;
  background: var(--bg-card); color: var(--text); outline: none; width: 140px;
  transition: border-color var(--tr-fast);
}
.dep-graph__search:focus { border-color: var(--accent); }
.dep-graph__search-wrap { position: relative; display: flex; align-items: center; }
.dep-graph__search-clear {
  position: absolute; right: 2px; top: 50%; transform: translateY(-50%);
  background: none; border: none; color: var(--text-muted); cursor: pointer;
  font-size: .85rem; line-height: 1; padding: 2px 5px; border-radius: 3px;
  transition: color var(--tr-fast);
}
.dep-graph__search-clear:hover { color: var(--text); }
.dep-graph__select {
  font-size: .7rem; background: var(--bg-card); color: var(--text);
  border: 1px solid var(--border); border-radius: 4px; padding: 2px 6px;
  cursor: pointer; outline: none;
}
.dep-graph__select:focus { border-color: var(--accent); }
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
