<script setup lang="ts">
import { inject, computed, ref } from 'vue'
import type { DashboardState } from '../composables/useDashboard'
import { sn } from '../utils'

const d = inject<DashboardState>('dashboard')!

const sa = computed(() => d.dd.staticAnalysis)
const modules = computed(() => sa.value?.modules ?? [])
const total = computed(() => sa.value?.totalUncertainClasses ?? 0)
const maxModuleCount = computed(() => Math.max(...modules.value.map(m => m.count), 1))

// If coverage data is available, compute the scope ratio
const totalKnownClasses = computed(() => d.dd.coverage?.classes?.length ?? 0)
const scopeRatio = computed(() =>
  totalKnownClasses.value > 0 ? Math.round((total.value / totalKnownClasses.value) * 100) : null
)

const searchQ = ref('')
const selectedModule = ref<string | null>(null)
const viewMode = ref<'tree' | 'flat'>('tree')

// Expanded package nodes in tree view
const expandedPkgs = ref<Set<string>>(new Set())

const activeModule = computed(() => {
  if (selectedModule.value) return modules.value.find(m => m.module === selectedModule.value) ?? null
  return modules.value.length === 1 ? modules.value[0] : null
})

// Build package tree from a flat list of FQCNs
interface PkgNode {
  pkg: string
  label: string
  classes: string[]
  children: PkgNode[]
}

function buildTree(classes: string[]): PkgNode[] {
  const byPkg: Map<string, string[]> = new Map()
  for (const cls of classes) {
    const dot = cls.lastIndexOf('.')
    const pkg = dot > 0 ? cls.substring(0, dot) : '(default)'
    if (!byPkg.has(pkg)) byPkg.set(pkg, [])
    byPkg.get(pkg)!.push(cls)
  }
  // Group packages into a two-level tree: top package + sub-packages
  const topPkgs: Map<string, { classes: string[], subPkgs: Map<string, string[]> }> = new Map()
  for (const [pkg, cls] of byPkg) {
    const parts = pkg.split('.')
    const topKey = parts.length > 1 ? parts.slice(0, 2).join('.') : pkg
    if (!topPkgs.has(topKey)) topPkgs.set(topKey, { classes: [], subPkgs: new Map() })
    const top = topPkgs.get(topKey)!
    if (pkg === topKey) {
      top.classes.push(...cls)
    } else {
      top.subPkgs.set(pkg, cls)
    }
  }
  const result: PkgNode[] = []
  for (const [topKey, { classes: topCls, subPkgs }] of topPkgs) {
    const node: PkgNode = { pkg: topKey, label: topKey, classes: topCls, children: [] }
    for (const [subPkg, subCls] of subPkgs) {
      node.children.push({
        pkg: subPkg,
        label: '.' + subPkg.slice(topKey.length),
        classes: subCls,
        children: []
      })
    }
    node.children.sort((a, b) => a.pkg.localeCompare(b.pkg))
    result.push(node)
  }
  result.sort((a, b) => a.pkg.localeCompare(b.pkg))
  return result
}

const allActiveClasses = computed(() => activeModule.value?.classes ?? [])

const filteredClasses = computed(() => {
  if (!searchQ.value.trim()) return allActiveClasses.value
  const q = searchQ.value.toLowerCase()
  return allActiveClasses.value.filter(c => c.toLowerCase().includes(q))
})

const tree = computed(() => buildTree(filteredClasses.value))

// Total class count after filter (for display)
const filteredCount = computed(() => filteredClasses.value.length)

function togglePkg(pkg: string) {
  if (expandedPkgs.value.has(pkg)) {
    expandedPkgs.value.delete(pkg)
  } else {
    expandedPkgs.value.add(pkg)
  }
  // trigger reactivity
  expandedPkgs.value = new Set(expandedPkgs.value)
}

function expandAll() {
  const all = new Set<string>()
  for (const node of tree.value) {
    all.add(node.pkg)
    for (const child of node.children) all.add(child.pkg)
  }
  expandedPkgs.value = all
}

function collapseAll() {
  expandedPkgs.value = new Set()
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

// Tree node total count (direct + all children)
function nodeTotal(node: PkgNode): number {
  return node.classes.length + node.children.reduce((s, c) => s + c.classes.length, 0)
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
      <!-- Summary cards -->
      <div style="display:flex;gap:12px;flex-wrap:wrap;margin-bottom:14px">
        <div class="sa-card sa-card--total">
          <div class="sa-card__value">{{ total }}</div>
          <div class="sa-card__label">Uncertain classes</div>
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

      <!-- Module bar chart (multi-module builds) -->
      <div v-if="modules.length > 1" style="margin-bottom:14px">
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
            <div
              class="mod-bar-fill"
              :style="{ width: Math.max(4, Math.round(m.count / maxModuleCount * 100)) + '%' }"
            ></div>
          </div>
          <span class="mod-bar-count">{{ m.count }}</span>
        </div>
      </div>

      <!-- Class list (shown when a single module is selected / single-module build) -->
      <div v-if="activeModule">
        <!-- Filter + view toggle toolbar -->
        <div style="display:flex;align-items:center;gap:8px;margin-bottom:8px;flex-wrap:wrap">
          <input
            v-model="searchQ"
            class="sa-search"
            placeholder="Filter classes…"
            type="search"
          />
          <span style="font-size:.72rem;color:var(--text-muted);flex-shrink:0;min-width:48px">
            {{ filteredCount }} / {{ activeModule.count }}
          </span>
          <div class="view-toggle">
            <button :class="['vt-btn', { 'vt-btn--active': viewMode === 'tree' }]" @click="viewMode = 'tree'" title="Package tree">⊞</button>
            <button :class="['vt-btn', { 'vt-btn--active': viewMode === 'flat' }]" @click="viewMode = 'flat'" title="Flat list">☰</button>
          </div>
          <div v-if="viewMode === 'tree'" style="display:flex;gap:4px;margin-left:auto">
            <button class="tree-ctrl" @click="expandAll" title="Expand all">+</button>
            <button class="tree-ctrl" @click="collapseAll" title="Collapse all">−</button>
          </div>
        </div>

        <!-- Tree view -->
        <div v-if="viewMode === 'tree'" style="overflow-y:auto;max-height:480px">
          <template v-for="node in tree" :key="node.pkg">
            <!-- Top-level package node -->
            <div
              class="pkg-row"
              @click="togglePkg(node.pkg)"
              :title="node.pkg"
            >
              <span class="pkg-chevron" :class="{ 'pkg-chevron--open': expandedPkgs.has(node.pkg) }">▶</span>
              <span class="pkg-label">{{ node.label }}</span>
              <span class="pkg-badge">{{ nodeTotal(node) }}</span>
            </div>

            <!-- Expanded content for top-level node -->
            <template v-if="expandedPkgs.has(node.pkg)">
              <!-- Direct classes at this package level -->
              <div
                v-for="cls in node.classes"
                :key="cls"
                class="cls-row cls-row--indent1"
                :title="cls"
              >
                <span class="cls-name">{{ shortClass(cls) }}</span>
                <button
                  v-if="hasCovData(cls)"
                  class="cov-link"
                  @click.stop="goToCoverage(cls)"
                  title="View in Coverage tab"
                >cov</button>
              </div>

              <!-- Sub-package nodes -->
              <template v-for="sub in node.children" :key="sub.pkg">
                <div
                  class="pkg-row pkg-row--sub"
                  @click="togglePkg(sub.pkg)"
                  :title="sub.pkg"
                >
                  <span class="pkg-chevron" :class="{ 'pkg-chevron--open': expandedPkgs.has(sub.pkg) }">▶</span>
                  <span class="pkg-label pkg-label--sub">{{ sub.label }}</span>
                  <span class="pkg-badge">{{ sub.classes.length }}</span>
                </div>
                <template v-if="expandedPkgs.has(sub.pkg)">
                  <div
                    v-for="cls in sub.classes"
                    :key="cls"
                    class="cls-row cls-row--indent2"
                    :title="cls"
                  >
                    <span class="cls-name">{{ shortClass(cls) }}</span>
                    <button
                      v-if="hasCovData(cls)"
                      class="cov-link"
                      @click.stop="goToCoverage(cls)"
                      title="View in Coverage tab"
                    >cov</button>
                  </div>
                </template>
              </template>
            </template>
          </template>

          <div v-if="!tree.length" style="color:var(--text-muted);font-size:.8rem;padding:8px 0">
            No classes match the filter.
          </div>
        </div>

        <!-- Flat view -->
        <div v-else style="overflow-y:auto;max-height:480px">
          <div
            v-for="cls in filteredClasses"
            :key="cls"
            class="cls-row cls-row--flat"
            :title="cls"
          >
            <span class="cls-pkg">{{ pkgOf(cls) ? pkgOf(cls) + '.' : '' }}</span>
            <span class="cls-name">{{ shortClass(cls) }}</span>
            <button
              v-if="hasCovData(cls)"
              class="cov-link"
              @click.stop="goToCoverage(cls)"
              title="View in Coverage tab"
            >cov</button>
          </div>
          <div v-if="!filteredClasses.length" style="color:var(--text-muted);font-size:.8rem;padding:8px 0">
            No classes match the filter.
          </div>
        </div>
      </div>

      <!-- Prompt to select a module (multi-module, nothing selected yet) -->
      <div
        v-else-if="modules.length > 1"
        style="color:var(--text-muted);font-size:.8rem;margin-top:8px"
      >
        Click a module bar above to browse its uncertain classes.
      </div>

      <p style="color:var(--text-muted);font-size:.75rem;margin-top:16px;line-height:1.5">
        These are the classes the static call-graph analysis identified as reachable from the current
        changes (changed classes + transitive callees, up to 4 hops). Only these classes were
        instrumented during the last selective-learn run.
        <template v-if="scopeRatio !== null">
          A lower percentage means the analysis narrowed down instrumentation more precisely.
        </template>
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
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 3px 6px;
  border-radius: 5px;
  cursor: pointer;
  transition: background var(--tr-fast);
  margin-bottom: 2px;
}
.mod-bar-row:hover { background: var(--bg-card); }
.mod-bar-row--active { background: color-mix(in srgb, var(--accent, #6366f1) 10%, transparent); }
.mod-bar-label {
  font-size: .72rem;
  color: var(--text-sec);
  width: 160px;
  flex-shrink: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mod-bar-track {
  flex: 1;
  height: 8px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 4px;
  overflow: hidden;
  min-width: 60px;
}
.mod-bar-fill {
  height: 100%;
  border-radius: 4px;
  background: var(--accent, #6366f1);
  transition: width .3s ease;
}
.mod-bar-count {
  font-size: .72rem;
  color: var(--text-muted);
  width: 32px;
  text-align: right;
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}

/* Search + view toggle */
.sa-search {
  flex: 1;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 4px 10px;
  font-size: .8rem;
  color: var(--text);
  outline: none;
  min-width: 0;
}
.sa-search:focus { border-color: var(--accent, #6366f1); }

.view-toggle { display: flex; border: 1px solid var(--border); border-radius: 6px; overflow: hidden; }
.vt-btn {
  background: var(--bg-card);
  border: none;
  color: var(--text-muted);
  padding: 3px 8px;
  font-size: .78rem;
  cursor: pointer;
  transition: background var(--tr-fast), color var(--tr-fast);
}
.vt-btn:hover { color: var(--text); }
.vt-btn--active { background: var(--accent, #6366f1); color: #fff; }

.tree-ctrl {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 4px;
  color: var(--text-muted);
  padding: 1px 6px;
  font-size: .78rem;
  cursor: pointer;
  line-height: 1.4;
}
.tree-ctrl:hover { border-color: var(--accent, #6366f1); color: var(--text); }

/* Package rows */
.pkg-row {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 3px 4px;
  border-radius: 4px;
  cursor: pointer;
  user-select: none;
  transition: background var(--tr-fast);
}
.pkg-row:hover { background: var(--bg-card); }
.pkg-row--sub { padding-left: 16px; }

.pkg-chevron {
  font-size: .55rem;
  color: var(--text-muted);
  display: inline-block;
  transform: rotate(0deg);
  transition: transform .15s ease;
  width: 10px;
  flex-shrink: 0;
}
.pkg-chevron--open { transform: rotate(90deg); }

.pkg-label {
  font-size: .75rem;
  color: var(--text-sec);
  font-family: ui-monospace, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pkg-label--sub { color: var(--text-muted); }

.pkg-badge {
  font-size: .62rem;
  color: var(--text-muted);
  background: var(--border);
  border-radius: 8px;
  padding: 0 5px;
  margin-left: auto;
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}

/* Class rows */
.cls-row {
  display: flex;
  align-items: baseline;
  gap: 4px;
  padding: 2px 4px;
  border-radius: 4px;
  font-size: .78rem;
  line-height: 1.6;
}
.cls-row:hover { background: color-mix(in srgb, var(--accent, #6366f1) 6%, transparent); }
.cls-row--indent1 { padding-left: 26px; }
.cls-row--indent2 { padding-left: 42px; }
.cls-row--flat {}

.cls-pkg { color: var(--text-muted); font-family: ui-monospace, monospace; font-size: .72rem; }
.cls-name { color: var(--text); font-weight: 500; font-family: ui-monospace, monospace; }

.cov-link {
  margin-left: auto;
  font-size: .6rem;
  color: var(--accent, #6366f1);
  background: color-mix(in srgb, var(--accent, #6366f1) 12%, transparent);
  border: 1px solid color-mix(in srgb, var(--accent, #6366f1) 30%, transparent);
  border-radius: 3px;
  padding: 0 4px;
  cursor: pointer;
  white-space: nowrap;
  flex-shrink: 0;
  transition: background var(--tr-fast);
  line-height: 1.5;
}
.cov-link:hover { background: color-mix(in srgb, var(--accent, #6366f1) 25%, transparent); }
</style>
