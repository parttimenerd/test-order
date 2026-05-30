<script setup lang="ts">
import { inject, computed, ref } from 'vue'
import type { DashboardState } from '../composables/useDashboard'
import type { StaticAnalysisClass, StaticAnalysisMember, MemberChangeKind } from '../types'

const d = inject<DashboardState>('dashboard')!

const sa = computed(() => d.dd.staticAnalysis)
const modules = computed(() => sa.value?.modules ?? [])
const total = computed(() => sa.value?.totalUncertainClasses ?? 0)
const maxModuleCount = computed(() => Math.max(...modules.value.map(m => m.count), 1))

const totalKnownClasses = computed(() => d.dd.coverage?.classes?.length ?? 0)
const scopeRatio = computed(() =>
  totalKnownClasses.value > 0 ? Math.round((total.value / totalKnownClasses.value) * 100) : null
)

const changedClassSet = computed(() => new Set(d.dd.changedClasses ?? []))

const DEPTH_COLORS: Record<number, string> = {
  0: 'var(--yellow, #f59e0b)',
  1: '#ef4444',
  2: '#f97316',
  3: '#a78bfa',
  4: 'var(--text-sec)',
}

function hopDepth(entry: StaticAnalysisClass): number | null {
  if (entry.depth !== null && entry.depth !== undefined) return entry.depth
  return changedClassSet.value.has(entry.name) ? 0 : null
}
function depthColor(depth: number | null): string {
  return depth !== null ? (DEPTH_COLORS[depth] ?? 'var(--text-sec)') : 'var(--text)'
}
function depthLabel(depth: number | null): string | null {
  if (depth === null) return null
  return depth === 0 ? 'Δ' : String(depth)
}

const KIND_META: Record<MemberChangeKind, { sym: string; color: string; label: string }> = {
  ADDED:     { sym: '+', color: 'var(--green, #10b981)',  label: 'added' },
  REMOVED:   { sym: '−', color: '#ef4444',                label: 'removed' },
  SIGNATURE: { sym: '⟂', color: '#f97316',                label: 'signature' },
  BODY:      { sym: '~', color: 'var(--accent, #6366f1)', label: 'body' },
}

const searchQ = ref('')
const selectedModule = ref<string | null>(null)
const showWithTestsOnly = ref(false)
const viewMode = ref<'tree' | 'flat'>('tree')
const showTransitive = ref(false)
const showAllFiles = ref(false)
const expandedSeeds = ref<Set<string>>(new Set())
const expandedPkgs = ref<Set<string>>(new Set())

const activeModule = computed(() => {
  if (selectedModule.value) return modules.value.find(m => m.module === selectedModule.value) ?? null
  return modules.value.length === 1 ? modules.value[0] : null
})

const allActiveEntries = computed(() => activeModule.value?.classes ?? [])

const seedEntries = computed(() => allActiveEntries.value.filter(e => hopDepth(e) === 0))
const transitiveEntries = computed(() => allActiveEntries.value.filter(e => {
  const d = hopDepth(e); return d !== null && d > 0
}))
const directlyChangedCount = computed(() => seedEntries.value.length)

function entryMatches(e: StaticAnalysisClass, q: string): boolean {
  if (e.name.toLowerCase().includes(q)) return true
  if (e.members && e.members.some(m => m.name.toLowerCase().includes(q))) return true
  if (e.tests && e.tests.some(t => t.toLowerCase().includes(q))) return true
  return false
}

const filteredSeeds = computed(() => {
  let list = seedEntries.value
  if (showWithTestsOnly.value) list = list.filter(e => (e.tests?.length ?? 0) > 0)
  const q = searchQ.value.trim().toLowerCase()
  if (!q) return list
  return list.filter(e => entryMatches(e, q))
})

const filteredTransitive = computed(() => {
  let list = transitiveEntries.value
  if (showWithTestsOnly.value) list = list.filter(e => (e.tests?.length ?? 0) > 0)
  const q = searchQ.value.trim().toLowerCase()
  if (!q) return list
  return list.filter(e => entryMatches(e, q))
})

interface PkgNode {
  pkg: string
  label: string
  classes: StaticAnalysisClass[]
  children: PkgNode[]
}
function buildTree(entries: StaticAnalysisClass[]): PkgNode[] {
  const byPkg: Map<string, StaticAnalysisClass[]> = new Map()
  for (const entry of entries) {
    const dot = entry.name.lastIndexOf('.')
    const pkg = dot > 0 ? entry.name.substring(0, dot) : '(default)'
    if (!byPkg.has(pkg)) byPkg.set(pkg, [])
    byPkg.get(pkg)!.push(entry)
  }
  const topPkgs: Map<string, { classes: StaticAnalysisClass[], subPkgs: Map<string, StaticAnalysisClass[]> }> = new Map()
  for (const [pkg, cls] of byPkg) {
    const parts = pkg.split('.')
    const topKey = parts.length > 1 ? parts.slice(0, 2).join('.') : pkg
    if (!topPkgs.has(topKey)) topPkgs.set(topKey, { classes: [], subPkgs: new Map() })
    const top = topPkgs.get(topKey)!
    if (pkg === topKey) top.classes.push(...cls)
    else top.subPkgs.set(pkg, cls)
  }
  const result: PkgNode[] = []
  for (const [topKey, { classes: topCls, subPkgs }] of topPkgs) {
    const node: PkgNode = { pkg: topKey, label: topKey, classes: topCls, children: [] }
    for (const [subPkg, subCls] of subPkgs) {
      node.children.push({ pkg: subPkg, label: '.' + subPkg.slice(topKey.length), classes: subCls, children: [] })
    }
    node.children.sort((a, b) => a.pkg.localeCompare(b.pkg))
    result.push(node)
  }
  result.sort((a, b) => a.pkg.localeCompare(b.pkg))
  return result
}
const transitiveTree = computed(() => buildTree(filteredTransitive.value))

function toggleSeed(name: string) {
  if (expandedSeeds.value.has(name)) expandedSeeds.value.delete(name)
  else expandedSeeds.value.add(name)
  expandedSeeds.value = new Set(expandedSeeds.value)
}
function expandAllSeeds() {
  expandedSeeds.value = new Set(filteredSeeds.value.map(e => e.name))
}
function collapseAllSeeds() {
  expandedSeeds.value = new Set()
}
function togglePkg(pkg: string) {
  if (expandedPkgs.value.has(pkg)) expandedPkgs.value.delete(pkg)
  else expandedPkgs.value.add(pkg)
  expandedPkgs.value = new Set(expandedPkgs.value)
}

function shortClass(fqcn: string): string {
  const dot = fqcn.lastIndexOf('.')
  return dot > 0 ? fqcn.substring(dot + 1) : fqcn
}
function pkgOf(fqcn: string): string {
  const dot = fqcn.lastIndexOf('.')
  return dot > 0 ? fqcn.substring(0, dot) : ''
}
function hasCovData(cls: string): boolean {
  return !!d.dd.coverage?.classes?.some(c => c.name === cls)
}
function goToCoverage(cls: string) {
  d.navigateToCovClass(cls)
}
function nodeTotal(node: PkgNode): number {
  return node.classes.length + node.children.reduce((s, c) => s + c.classes.length, 0)
}

function buildCallChain(entry: StaticAnalysisClass): string {
  const byName = new Map(allActiveEntries.value.map(e => [e.name, e]))
  const chain: string[] = []
  let cur: StaticAnalysisClass | undefined = entry
  while (cur) {
    chain.push(shortClass(cur.name))
    if (!cur.parent) break
    const next = byName.get(cur.parent)
    if (!next || chain.length > 6) { if (next) chain.push('…'); break }
    cur = next
  }
  const depth = hopDepth(entry)
  const suffix = depth !== null ? ` (depth ${depth})` : ''
  return chain.join(' ← ') + suffix
}

// Member kind counts on a single seed entry (for the inline "+1 ⟂1 ~1" chip)
function memberKindCounts(members: StaticAnalysisMember[] | undefined) {
  const c: Record<MemberChangeKind, number> = { ADDED: 0, REMOVED: 0, SIGNATURE: 0, BODY: 0 }
  if (!members) return c
  for (const m of members) c[m.kind]++
  return c
}
</script>

<template>
  <div v-show="d.activeTab.value === 'staticanalysis'" style="animation:fadeIn .15s ease-out">
    <h3 style="font-size:.82rem;color:var(--text-sec);margin-bottom:10px">Static Analysis — Selective Learn Scope</h3>

    <div v-if="!sa || !sa.enabled" style="color:var(--text-muted);font-size:.8rem;margin-top:12px">
      No selective-learn data available. Run with
      <code>-Dtestorder.learn.selective=true</code> to record the instrumentation scope.
    </div>

    <template v-else>
      <!-- ─────────── Top summary cards ─────────── -->
      <div style="display:flex;gap:12px;flex-wrap:wrap;margin-bottom:12px">
        <div class="sa-card sa-card--total">
          <div class="sa-card__value">{{ total }}</div>
          <div class="sa-card__label">Uncertain classes</div>
        </div>
        <div v-if="directlyChangedCount > 0" class="sa-card" :title="`${directlyChangedCount} of ${total} uncertain classes are directly changed (depth 0); the rest are transitive callees`">
          <div class="sa-card__value" style="color:var(--yellow)">{{ directlyChangedCount }}</div>
          <div class="sa-card__label">Directly changed</div>
        </div>
        <div class="sa-card">
          <div class="sa-card__value">{{ modules.length }}</div>
          <div class="sa-card__label">Module{{ modules.length !== 1 ? 's' : '' }}</div>
        </div>
        <div v-if="scopeRatio !== null" class="sa-card" :title="`${total} uncertain / ${totalKnownClasses} total tracked classes`">
          <div class="sa-card__value" :style="{ color: scopeRatio > 60 ? 'var(--yellow)' : scopeRatio > 30 ? 'var(--text)' : 'var(--green)' }">
            {{ scopeRatio }}%
          </div>
          <div class="sa-card__label">of tracked scope</div>
        </div>
      </div>

      <!-- Module bar chart (multi-module) -->
      <div v-if="modules.length > 1" style="margin-bottom:12px">
        <div style="font-size:.68rem;color:var(--text-muted);text-transform:uppercase;letter-spacing:.5px;margin-bottom:6px">Classes per module</div>
        <div
          v-for="m in modules"
          :key="m.module"
          class="mod-bar-row"
          :class="{ 'mod-bar-row--active': selectedModule === m.module }"
          @click="selectedModule = selectedModule === m.module ? null : m.module"
        >
          <span class="mod-bar-label" :title="m.module">{{ m.module }}</span>
          <div class="mod-bar-track">
            <div class="mod-bar-fill" :style="{ width: Math.max(4, Math.round(m.count / maxModuleCount * 100)) + '%' }"></div>
          </div>
          <span class="mod-bar-count">{{ m.count }}</span>
        </div>
      </div>

      <!-- Module placeholder -->
      <div v-if="!activeModule && modules.length > 1" style="color:var(--text-muted);font-size:.8rem;margin-top:8px">
        Click a module bar above to browse its uncertain classes.
      </div>

      <template v-if="activeModule">
        <!-- ─────────── Changes Summary panel ─────────── -->
        <div class="changes-summary">
          <div class="cs-header">
            <span class="cs-title">What changed</span>
            <span v-if="!activeModule.summary" class="cs-note">Run learn again to see change details.</span>
          </div>

          <div v-if="activeModule.summary" class="cs-chips">
            <span class="cs-chip">
              <strong>{{ activeModule.summary.filesChanged }}</strong>&nbsp;file{{ activeModule.summary.filesChanged !== 1 ? 's' : '' }}
            </span>
            <span class="cs-chip">
              <strong>{{ activeModule.summary.classesChanged }}</strong>&nbsp;class{{ activeModule.summary.classesChanged !== 1 ? 'es' : '' }}
            </span>
            <span class="cs-chip">
              <strong>{{ activeModule.summary.membersChanged }}</strong>&nbsp;member{{ activeModule.summary.membersChanged !== 1 ? 's' : '' }}
            </span>
            <span v-if="activeModule.summary.added > 0"
                  class="cs-chip cs-chip--kind"
                  :style="{ color: KIND_META.ADDED.color, borderColor: 'color-mix(in srgb,' + KIND_META.ADDED.color + ' 35%, transparent)' }">
              + {{ activeModule.summary.added }} added
            </span>
            <span v-if="activeModule.summary.removed > 0"
                  class="cs-chip cs-chip--kind"
                  :style="{ color: KIND_META.REMOVED.color, borderColor: 'color-mix(in srgb,' + KIND_META.REMOVED.color + ' 35%, transparent)' }">
              − {{ activeModule.summary.removed }} removed
            </span>
            <span v-if="activeModule.summary.signature > 0"
                  class="cs-chip cs-chip--kind"
                  :style="{ color: KIND_META.SIGNATURE.color, borderColor: 'color-mix(in srgb,' + KIND_META.SIGNATURE.color + ' 35%, transparent)' }">
              ⟂ {{ activeModule.summary.signature }} signature
            </span>
            <span v-if="activeModule.summary.body > 0"
                  class="cs-chip cs-chip--kind"
                  :style="{ color: KIND_META.BODY.color, borderColor: 'color-mix(in srgb,' + KIND_META.BODY.color + ' 35%, transparent)' }">
              ~ {{ activeModule.summary.body }} body
            </span>
            <span v-if="activeModule.summary.staticFieldChanges > 0" class="cs-chip" title="Static field changes">
              <strong>{{ activeModule.summary.staticFieldChanges }}</strong>&nbsp;static field{{ activeModule.summary.staticFieldChanges !== 1 ? 's' : '' }}
            </span>
            <span v-if="activeModule.summary.totalChangedLines > 0" class="cs-chip" :title="`${activeModule.summary.totalChangedLines} line(s) changed across all method bodies`">
              <strong>{{ activeModule.summary.totalChangedLines }}</strong>&nbsp;line{{ activeModule.summary.totalChangedLines !== 1 ? 's' : '' }}
            </span>
          </div>

          <!-- Files changed list -->
          <div v-if="activeModule.fileSummaries && activeModule.fileSummaries.length > 0" class="cs-files">
            <button
              v-if="activeModule.fileSummaries.length > 5"
              class="cs-files-toggle"
              @click="showAllFiles = !showAllFiles"
            >{{ showAllFiles ? '▾' : '▸' }} {{ activeModule.fileSummaries.length }} files</button>
            <div v-if="showAllFiles || activeModule.fileSummaries.length <= 5" class="cs-files-list">
              <div v-for="fs in activeModule.fileSummaries" :key="fs.path" class="cs-file-row" :title="fs.path">
                <span class="cs-file-path">{{ fs.path }}</span>
                <span v-if="fs.added > 0" :style="{ color: KIND_META.ADDED.color }">+{{ fs.added }}</span>
                <span v-if="fs.removed > 0" :style="{ color: KIND_META.REMOVED.color }">−{{ fs.removed }}</span>
                <span v-if="fs.signature > 0" :style="{ color: KIND_META.SIGNATURE.color }">⟂{{ fs.signature }}</span>
                <span v-if="fs.body > 0" :style="{ color: KIND_META.BODY.color }">~{{ fs.body }}</span>
                <span v-if="fs.totalLines > 0" class="cs-file-lines">{{ fs.totalLines }}L</span>
              </div>
            </div>
          </div>

          <!-- Degraded warning -->
          <div v-if="activeModule.degraded" class="cs-degraded">
            ⚠ Call-graph analysis was degraded (too many changes). Instrumentation scope may be wider than optimal.
          </div>
        </div>

        <!-- ─────────── Toolbar (search + filters) ─────────── -->
        <div style="display:flex;align-items:center;gap:8px;margin:14px 0 8px;flex-wrap:wrap">
          <input
            v-model="searchQ"
            class="sa-search"
            placeholder="Filter by class, member, or test…"
            type="search"
          />
          <button
            :class="['tree-ctrl', { 'tree-ctrl--active': showWithTestsOnly }]"
            @click="showWithTestsOnly = !showWithTestsOnly"
            :title="showWithTestsOnly ? 'Show all classes' : 'Filter to classes that have coverage-linked tests'"
          >has tests</button>
        </div>

        <!-- ─────────── Changed Classes (seeds) ─────────── -->
        <div v-if="seedEntries.length > 0" class="section-head">
          <span class="section-title">Changed classes ({{ filteredSeeds.length }}/{{ seedEntries.length }})</span>
          <span style="display:flex;gap:4px;margin-left:auto">
            <button class="tree-ctrl" @click="expandAllSeeds" title="Expand all">+</button>
            <button class="tree-ctrl" @click="collapseAllSeeds" title="Collapse all">−</button>
          </span>
        </div>
        <div v-if="seedEntries.length > 0" class="seeds-list">
          <div v-for="entry in filteredSeeds" :key="entry.name" class="seed-block">
            <div class="seed-row" @click="toggleSeed(entry.name)">
              <span class="pkg-chevron" :class="{ 'pkg-chevron--open': expandedSeeds.has(entry.name) }">▶</span>
              <span class="cls-pkg">{{ pkgOf(entry.name) ? pkgOf(entry.name) + '.' : '' }}</span>
              <span class="cls-name" :style="{ color: depthColor(0) }">{{ shortClass(entry.name) }}</span>
              <span v-if="entry.hasTypeChange" class="type-badge" title="Type-level change (added/removed/signature-changed type)">TYPE</span>
              <span class="kind-summary">
                <template v-for="(meta, kind) in KIND_META" :key="kind">
                  <span
                    v-if="memberKindCounts(entry.members)[kind] > 0"
                    :style="{ color: meta.color }"
                    :title="`${memberKindCounts(entry.members)[kind]} ${meta.label}`"
                  >{{ meta.sym }}{{ memberKindCounts(entry.members)[kind] }}</span>
                </template>
              </span>
              <span v-if="(entry.tests?.length ?? 0) > 0" class="tests-pill" :title="`${entry.tests!.length} test class(es) cover this`">
                {{ entry.tests!.length }} test{{ entry.tests!.length !== 1 ? 's' : '' }}
              </span>
              <button
                v-if="hasCovData(entry.name)"
                class="cov-link"
                @click.stop="goToCoverage(entry.name)"
                title="View class coverage in Analytics tab"
              >cov ↗</button>
            </div>

            <div v-if="expandedSeeds.has(entry.name)" class="seed-detail">
              <div v-if="entry.members && entry.members.length > 0" class="seed-members">
                <div class="seed-section-label">Members</div>
                <div v-for="m in entry.members" :key="m.name" class="member-row">
                  <span class="member-kind" :style="{ color: KIND_META[m.kind].color }" :title="KIND_META[m.kind].label">{{ KIND_META[m.kind].sym }}</span>
                  <span class="member-name">{{ m.name }}</span>
                  <span v-if="m.isStaticField" class="member-tag">static field</span>
                  <span class="member-kind-text" :style="{ color: KIND_META[m.kind].color }">{{ KIND_META[m.kind].label }}</span>
                </div>
              </div>
              <div v-else class="seed-detail-note">
                No member-level details available (older sidecar or class-only change).
              </div>

              <div v-if="entry.tests && entry.tests.length > 0" class="seed-tests">
                <div class="seed-section-label">Tests covering this class</div>
                <div class="tests-chips">
                  <button
                    v-for="t in entry.tests"
                    :key="t"
                    class="test-chip"
                    @click="d.navigateToTestFromCov(t)"
                    :title="`Navigate to ${t} in Tests tab`"
                  >{{ shortClass(t) }}</button>
                </div>
              </div>
              <div v-else class="seed-detail-note">
                No tests in coverage data exercise this class.
              </div>
            </div>
          </div>
          <div v-if="!filteredSeeds.length" style="color:var(--text-muted);font-size:.8rem;padding:8px 0">
            No changed classes match the filter.
          </div>
        </div>
        <div v-else style="color:var(--text-muted);font-size:.8rem;margin:8px 0 14px">
          No directly-changed classes recorded.
        </div>

        <!-- ─────────── Transitively Uncertain (depth 1-4) ─────────── -->
        <div v-if="transitiveEntries.length > 0" class="section-head">
          <button class="section-toggle" @click="showTransitive = !showTransitive">
            <span class="pkg-chevron" :class="{ 'pkg-chevron--open': showTransitive }">▶</span>
            <span class="section-title">Transitively uncertain ({{ filteredTransitive.length }}/{{ transitiveEntries.length }})</span>
          </button>
          <div v-if="showTransitive" style="display:flex;gap:6px;margin-left:auto">
            <div class="view-toggle">
              <button :class="['vt-btn', { 'vt-btn--active': viewMode === 'tree' }]" @click="viewMode = 'tree'" title="Package tree">⊞</button>
              <button :class="['vt-btn', { 'vt-btn--active': viewMode === 'flat' }]" @click="viewMode = 'flat'" title="Flat list">☰</button>
            </div>
          </div>
        </div>

        <div v-if="transitiveEntries.length > 0 && showTransitive">
          <!-- Depth legend -->
          <div style="display:flex;gap:10px;flex-wrap:wrap;margin:6px 0 8px;font-size:.68rem;color:var(--text-muted)">
            <span>Depth:</span>
            <template v-for="(color, d) in DEPTH_COLORS" :key="d">
              <span v-if="Number(d) > 0" :style="{ color }">{{ d }} hop</span>
            </template>
          </div>

          <!-- Tree view -->
          <div v-if="viewMode === 'tree'" style="overflow-y:auto;max-height:480px">
            <template v-for="node in transitiveTree" :key="node.pkg">
              <div class="pkg-row" @click="togglePkg(node.pkg)" :title="node.pkg">
                <span class="pkg-chevron" :class="{ 'pkg-chevron--open': expandedPkgs.has(node.pkg) }">▶</span>
                <span class="pkg-label">{{ node.label }}</span>
                <span class="pkg-badge">{{ nodeTotal(node) }}</span>
              </div>
              <template v-if="expandedPkgs.has(node.pkg)">
                <div v-for="entry in node.classes" :key="entry.name" class="cls-row cls-row--indent1" :title="entry.name">
                  <span class="cls-name" :style="{ color: depthColor(hopDepth(entry)) }">{{ shortClass(entry.name) }}</span>
                  <span
                    v-if="depthLabel(hopDepth(entry)) !== null"
                    class="depth-badge"
                    :style="{ color: depthColor(hopDepth(entry)), background: 'color-mix(in srgb,' + depthColor(hopDepth(entry)) + ' 14%, transparent)', borderColor: 'color-mix(in srgb,' + depthColor(hopDepth(entry)) + ' 35%, transparent)' }"
                    :title="buildCallChain(entry)"
                  >{{ depthLabel(hopDepth(entry)) }}</span>
                  <span v-if="(entry.tests?.length ?? 0) > 0" class="tests-pill tests-pill--small" :title="`${entry.tests!.length} test(s)`">{{ entry.tests!.length }}t</span>
                  <button v-if="hasCovData(entry.name)" class="cov-link" @click.stop="goToCoverage(entry.name)" title="View class coverage in Analytics tab">cov ↗</button>
                </div>
                <template v-for="sub in node.children" :key="sub.pkg">
                  <div class="pkg-row pkg-row--sub" @click="togglePkg(sub.pkg)" :title="sub.pkg">
                    <span class="pkg-chevron" :class="{ 'pkg-chevron--open': expandedPkgs.has(sub.pkg) }">▶</span>
                    <span class="pkg-label pkg-label--sub">{{ sub.label }}</span>
                    <span class="pkg-badge">{{ sub.classes.length }}</span>
                  </div>
                  <template v-if="expandedPkgs.has(sub.pkg)">
                    <div v-for="entry in sub.classes" :key="entry.name" class="cls-row cls-row--indent2" :title="entry.name">
                      <span class="cls-name" :style="{ color: depthColor(hopDepth(entry)) }">{{ shortClass(entry.name) }}</span>
                      <span
                        v-if="depthLabel(hopDepth(entry)) !== null"
                        class="depth-badge"
                        :style="{ color: depthColor(hopDepth(entry)), background: 'color-mix(in srgb,' + depthColor(hopDepth(entry)) + ' 14%, transparent)', borderColor: 'color-mix(in srgb,' + depthColor(hopDepth(entry)) + ' 35%, transparent)' }"
                        :title="buildCallChain(entry)"
                      >{{ depthLabel(hopDepth(entry)) }}</span>
                      <span v-if="(entry.tests?.length ?? 0) > 0" class="tests-pill tests-pill--small" :title="`${entry.tests!.length} test(s)`">{{ entry.tests!.length }}t</span>
                      <button v-if="hasCovData(entry.name)" class="cov-link" @click.stop="goToCoverage(entry.name)" title="View class coverage in Analytics tab">cov ↗</button>
                    </div>
                  </template>
                </template>
              </template>
            </template>
            <div v-if="!transitiveTree.length" style="color:var(--text-muted);font-size:.8rem;padding:8px 0">
              No transitive classes match the filter.
            </div>
          </div>

          <!-- Flat view -->
          <div v-else style="overflow-y:auto;max-height:480px">
            <div v-for="entry in filteredTransitive" :key="entry.name" class="cls-row cls-row--flat" :title="entry.name">
              <span class="cls-pkg">{{ pkgOf(entry.name) ? pkgOf(entry.name) + '.' : '' }}</span>
              <span class="cls-name" :style="{ color: depthColor(hopDepth(entry)) }">{{ shortClass(entry.name) }}</span>
              <span
                v-if="depthLabel(hopDepth(entry)) !== null"
                class="depth-badge"
                :style="{ color: depthColor(hopDepth(entry)), background: 'color-mix(in srgb,' + depthColor(hopDepth(entry)) + ' 14%, transparent)', borderColor: 'color-mix(in srgb,' + depthColor(hopDepth(entry)) + ' 35%, transparent)' }"
                :title="buildCallChain(entry)"
              >{{ depthLabel(hopDepth(entry)) }}</span>
              <span v-if="(entry.tests?.length ?? 0) > 0" class="tests-pill tests-pill--small" :title="`${entry.tests!.length} test(s)`">{{ entry.tests!.length }}t</span>
              <button v-if="hasCovData(entry.name)" class="cov-link" @click.stop="goToCoverage(entry.name)" title="View class coverage in Analytics tab">cov ↗</button>
            </div>
            <div v-if="!filteredTransitive.length" style="color:var(--text-muted);font-size:.8rem;padding:8px 0">
              No transitive classes match the filter.
            </div>
          </div>
        </div>
      </template>

      <p style="color:var(--text-muted);font-size:.75rem;margin-top:16px;line-height:1.5">
        Changed classes are the seeds detected by structural diff against the index baseline. Transitively
        uncertain classes are reachable from those seeds via the static call/field graph (up to 4 hops).
        Hover depth badges to see the call chain.
      </p>
    </template>
  </div>
</template>

<style scoped>
.sa-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 10px 16px;
  min-width: 80px;
  text-align: center;
}
.sa-card__value { font-size: 1.4rem; font-weight: 700; color: var(--text); }
.sa-card__label { font-size: .65rem; color: var(--text-muted); text-transform: uppercase; letter-spacing: .5px; }
.sa-card--total .sa-card__value { color: var(--accent, #6366f1); }

/* Module bar chart */
.mod-bar-row {
  display: flex; align-items: center; gap: 8px;
  padding: 3px 6px; border-radius: 5px; cursor: pointer;
  transition: background var(--tr-fast); margin-bottom: 2px;
}
.mod-bar-row:hover { background: var(--bg-card); }
.mod-bar-row--active { background: color-mix(in srgb, var(--accent, #6366f1) 10%, transparent); }
.mod-bar-label { font-size: .72rem; color: var(--text-sec); width: 160px; flex-shrink: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mod-bar-track { flex: 1; height: 8px; background: var(--bg-card); border: 1px solid var(--border); border-radius: 4px; overflow: hidden; min-width: 60px; }
.mod-bar-fill { height: 100%; border-radius: 4px; background: var(--accent, #6366f1); transition: width .3s ease; }
.mod-bar-count { font-size: .72rem; color: var(--text-muted); width: 32px; text-align: right; flex-shrink: 0; font-variant-numeric: tabular-nums; }

/* Changes Summary panel */
.changes-summary {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 10px 14px;
  margin-bottom: 4px;
}
.cs-header { display: flex; align-items: baseline; gap: 8px; margin-bottom: 8px; }
.cs-title { font-size: .72rem; color: var(--text-sec); text-transform: uppercase; letter-spacing: .5px; font-weight: 600; }
.cs-note { font-size: .7rem; color: var(--text-muted); font-style: italic; }
.cs-chips { display: flex; gap: 6px; flex-wrap: wrap; }
.cs-chip {
  font-size: .72rem; color: var(--text-sec);
  background: color-mix(in srgb, var(--text-muted) 8%, transparent);
  border: 1px solid var(--border); border-radius: 12px; padding: 1px 9px;
  font-variant-numeric: tabular-nums;
}
.cs-chip strong { color: var(--text); font-weight: 600; }
.cs-chip--kind { font-weight: 600; background: transparent; }

.cs-files { margin-top: 10px; }
.cs-files-toggle {
  background: none; border: none; color: var(--text-muted);
  font-size: .72rem; cursor: pointer; padding: 0 0 4px 0;
}
.cs-files-toggle:hover { color: var(--text); }
.cs-files-list { display: flex; flex-direction: column; gap: 2px; }
.cs-file-row {
  display: flex; align-items: center; gap: 8px; font-size: .72rem;
  font-variant-numeric: tabular-nums;
}
.cs-file-path {
  font-family: ui-monospace, monospace; color: var(--text-sec);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; min-width: 0;
}
.cs-file-lines { color: var(--text-muted); font-size: .68rem; }

.cs-degraded {
  margin-top: 10px;
  background: color-mix(in srgb, var(--yellow, #f59e0b) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--yellow, #f59e0b) 30%, transparent);
  border-radius: 6px; padding: 6px 10px;
  font-size: .75rem; color: var(--yellow, #f59e0b);
}

/* Section headers */
.section-head {
  display: flex; align-items: center; gap: 8px;
  margin: 14px 0 6px; padding-bottom: 4px;
  border-bottom: 1px solid var(--border);
}
.section-title { font-size: .75rem; color: var(--text-sec); font-weight: 600; }
.section-toggle {
  display: flex; align-items: center; gap: 6px;
  background: none; border: none; cursor: pointer; padding: 0; color: inherit;
}

/* Search */
.sa-search {
  flex: 1; background: var(--bg-card); border: 1px solid var(--border);
  border-radius: 6px; padding: 4px 10px; font-size: .8rem; color: var(--text);
  outline: none; min-width: 0;
}
.sa-search:focus { border-color: var(--accent, #6366f1); }

.view-toggle { display: flex; border: 1px solid var(--border); border-radius: 6px; overflow: hidden; }
.vt-btn {
  background: var(--bg-card); border: none; color: var(--text-muted);
  padding: 3px 8px; font-size: .78rem; cursor: pointer;
  transition: background var(--tr-fast), color var(--tr-fast);
}
.vt-btn:hover { color: var(--text); }
.vt-btn--active { background: var(--accent, #6366f1); color: #fff; }

.tree-ctrl {
  background: var(--bg-card); border: 1px solid var(--border);
  border-radius: 4px; color: var(--text-muted);
  padding: 1px 6px; font-size: .78rem; cursor: pointer; line-height: 1.4;
}
.tree-ctrl:hover { border-color: var(--accent, #6366f1); color: var(--text); }
.tree-ctrl--active {
  background: color-mix(in srgb, var(--yellow, #f59e0b) 15%, transparent);
  border-color: var(--yellow, #f59e0b); color: var(--yellow, #f59e0b);
}

/* Seeds list */
.seeds-list { display: flex; flex-direction: column; gap: 2px; max-height: 480px; overflow-y: auto; }
.seed-block { border-bottom: 1px dashed color-mix(in srgb, var(--border) 60%, transparent); }
.seed-block:last-child { border-bottom: none; }
.seed-row {
  display: flex; align-items: baseline; gap: 6px;
  padding: 4px 4px; cursor: pointer; user-select: none;
  font-size: .8rem;
}
.seed-row:hover { background: color-mix(in srgb, var(--accent, #6366f1) 6%, transparent); }
.kind-summary { display: flex; gap: 5px; font-size: .7rem; font-variant-numeric: tabular-nums; }
.type-badge {
  font-size: .58rem; font-weight: 700;
  color: #a78bfa; border: 1px solid color-mix(in srgb, #a78bfa 35%, transparent);
  background: color-mix(in srgb, #a78bfa 14%, transparent);
  border-radius: 3px; padding: 0 4px; line-height: 1.5;
}
.tests-pill {
  font-size: .65rem; color: var(--accent, #6366f1);
  background: color-mix(in srgb, var(--accent, #6366f1) 12%, transparent);
  border: 1px solid color-mix(in srgb, var(--accent, #6366f1) 30%, transparent);
  border-radius: 8px; padding: 0 6px; flex-shrink: 0; line-height: 1.6;
}
.tests-pill--small { font-size: .6rem; padding: 0 4px; line-height: 1.5; border-radius: 6px; }

.seed-detail {
  padding: 4px 10px 8px 24px;
  background: color-mix(in srgb, var(--text-muted) 4%, transparent);
  border-radius: 4px;
  margin-bottom: 4px;
}
.seed-section-label {
  font-size: .65rem; color: var(--text-muted); text-transform: uppercase;
  letter-spacing: .5px; margin: 4px 0 3px;
}
.seed-detail-note { font-size: .7rem; color: var(--text-muted); font-style: italic; padding: 4px 0; }
.seed-members { margin-bottom: 6px; }
.member-row {
  display: flex; align-items: baseline; gap: 6px;
  font-size: .76rem; padding: 1px 0;
}
.member-kind { font-weight: 700; width: 12px; text-align: center; flex-shrink: 0; }
.member-name { font-family: ui-monospace, monospace; color: var(--text); }
.member-tag {
  font-size: .6rem; color: var(--text-muted);
  border: 1px solid var(--border); border-radius: 3px; padding: 0 4px;
}
.member-kind-text { font-size: .65rem; margin-left: auto; text-transform: lowercase; }
.tests-chips { display: flex; gap: 4px; flex-wrap: wrap; }
.test-chip {
  background: color-mix(in srgb, var(--accent, #6366f1) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--accent, #6366f1) 25%, transparent);
  color: var(--accent, #6366f1); border-radius: 10px;
  font-size: .68rem; padding: 1px 8px; cursor: pointer;
  font-family: ui-monospace, monospace;
}
.test-chip:hover { background: color-mix(in srgb, var(--accent, #6366f1) 22%, transparent); }

/* Package rows (transitive list) */
.pkg-row {
  display: flex; align-items: center; gap: 5px;
  padding: 3px 4px; border-radius: 4px; cursor: pointer; user-select: none;
  transition: background var(--tr-fast);
}
.pkg-row:hover { background: var(--bg-card); }
.pkg-row--sub { padding-left: 16px; }
.pkg-chevron {
  font-size: .55rem; color: var(--text-muted);
  display: inline-block; transform: rotate(0deg);
  transition: transform .15s ease; width: 10px; flex-shrink: 0;
}
.pkg-chevron--open { transform: rotate(90deg); }
.pkg-label {
  font-size: .75rem; color: var(--text-sec);
  font-family: ui-monospace, monospace;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.pkg-label--sub { color: var(--text-muted); }
.pkg-badge {
  font-size: .62rem; color: var(--text-muted);
  background: var(--border); border-radius: 8px; padding: 0 5px;
  margin-left: auto; flex-shrink: 0; font-variant-numeric: tabular-nums;
}

/* Class rows (transitive) */
.cls-row {
  display: flex; align-items: baseline; gap: 4px;
  padding: 2px 4px; border-radius: 4px;
  font-size: .78rem; line-height: 1.6;
}
.cls-row:hover { background: color-mix(in srgb, var(--accent, #6366f1) 6%, transparent); }
.cls-row--indent1 { padding-left: 26px; }
.cls-row--indent2 { padding-left: 42px; }
.cls-pkg { color: var(--text-muted); font-family: ui-monospace, monospace; font-size: .72rem; }
.cls-name { font-weight: 500; font-family: ui-monospace, monospace; }

.depth-badge {
  font-size: .6rem; border: 1px solid; border-radius: 3px;
  padding: 0 4px; flex-shrink: 0; font-weight: 600; line-height: 1.5; cursor: default;
}

.cov-link {
  margin-left: auto; font-size: .6rem; color: var(--accent, #6366f1);
  background: color-mix(in srgb, var(--accent, #6366f1) 12%, transparent);
  border: 1px solid color-mix(in srgb, var(--accent, #6366f1) 30%, transparent);
  border-radius: 3px; padding: 0 4px; cursor: pointer;
  white-space: nowrap; flex-shrink: 0;
  transition: background var(--tr-fast); line-height: 1.5;
}
.cov-link:hover { background: color-mix(in srgb, var(--accent, #6366f1) 25%, transparent); }
</style>
