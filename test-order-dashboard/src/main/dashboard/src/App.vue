<script setup lang="ts">
import { provide, onMounted, onUnmounted, ref, watch, computed, type Ref } from 'vue'
import { useDashboard } from './composables/useDashboard'
import { parseDashboardData } from './data'
import { preloadClassInfo } from './composables/useClassInfo'
import NoDataSplash from './components/NoDataSplash.vue'
import AppHeader from './components/AppHeader.vue'
import KpiRow from './components/KpiRow.vue'
import TabBar from './components/TabBar.vue'
import TestSidebar from './components/TestSidebar.vue'
import TestsTab from './components/TestsTab.vue'
import AnalyticsTab from './components/AnalyticsTab.vue'
import WeightsTab from './components/WeightsTab.vue'
import MLTab from './components/MLTab.vue'
import MutationTab from './components/MutationTab.vue'
import StaticAnalysisTab from './components/StaticAnalysisTab.vue'
import AppFooter from './components/AppFooter.vue'
import CommandPalette from './components/CommandPalette.vue'
import ScoreBreakdownPanel from './components/ScoreBreakdownPanel.vue'
import TestInfoCard from './components/TestInfoCard.vue'
import { useTestHover } from './composables/useTestHover'

const { data, error } = parseDashboardData()
const dashboard = useDashboard(data, error)
provide('dashboard', dashboard)

const testHover = useTestHover()
provide('testHover', testHover)

// Bulk-preload class info for all known test + source class names
const allClassNames = new Set<string>()
for (const t of data.tests ?? []) allClassNames.add(t.name)
for (const cls of data.coverage?.classes ?? []) {
  allClassNames.add(cls.name)
  for (const tn of cls.tests ?? []) allClassNames.add(tn)
}
preloadClassInfo([...allClassNames])

// Focus mode — hides sidebar for full-width test detail view
const focusMode = ref(false)
provide('focusMode', focusMode)
provide('toggleFocusMode', () => { focusMode.value = !focusMode.value })

// Blame mode — highlights tests linked to changed source classes
const blameMode = ref(false)
provide('blameMode', blameMode)
provide('toggleBlameMode', () => { blameMode.value = !blameMode.value })

// Short names toggle — backward compat for CommandPalette; derived from nameMode
const shortNames = computed(() => dashboard.nameMode.value !== 'full')
function toggleShortNames() {
  dashboard.nameMode.value = dashboard.nameMode.value === 'full' ? 'short' : 'full'
}
provide('shortNames', shortNames)
provide('toggleShortNames', toggleShortNames)

const toastMsg = ref('')
let toastTimer = 0
function showToast(msg: string) {
  toastMsg.value = msg
  clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => { toastMsg.value = '' }, 2200)
}
provide('showToast', showToast)

const paletteOpen = ref(false)
provide('openPalette', () => { paletteOpen.value = true })

function onKeydown(e: KeyboardEvent) {
  const tag = (e.target as HTMLElement)?.tagName
  const isInput = tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT'

  // ⌘K / Ctrl+K opens command palette
  if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
    e.preventDefault()
    paletteOpen.value = !paletteOpen.value
    return
  }

  // Escape: close palette → blur input → clear search → clear selection
  if (e.key === 'Escape') {
    if (paletteOpen.value) {
      e.preventDefault()
      paletteOpen.value = false
      return
    }
    if (dashboard.scoreModalOpen.value) {
      e.preventDefault()
      dashboard.closeScoreModal()
      return
    }
    e.preventDefault()
    if (isInput) {
      ;(e.target as HTMLElement).blur()
      return
    }
    if (dashboard.searchQ.value) { dashboard.searchQ.value = ''; return }
    dashboard.clearSelection()
    return
  }

  // "/" or Ctrl/Cmd+F focuses sidebar search
  if ((e.key === '/' && !isInput) || ((e.metaKey || e.ctrlKey) && e.key === 'f')) {
    e.preventDefault()
    const el = document.querySelector<HTMLInputElement>('[data-search-main]')
    el?.focus()
    el?.select()
    return
  }

  if (isInput) return

  switch (e.key) {
    case 'ArrowDown':
    case 'j':
      e.preventDefault()
      dashboard.navigateTest('down')
      break
    case 'ArrowUp':
    case 'k':
      e.preventDefault()
      dashboard.navigateTest('up')
      break
    case 'ArrowRight':
    case 'l':
      if (dashboard.activeTab.value === 'analytics' && dashboard.analyticsSelectedRunIdx.value !== null) {
        e.preventDefault()
        dashboard.analyticsSelectedRunIdx.value = Math.min(dashboard.runs.length - 1, dashboard.analyticsSelectedRunIdx.value + 1)
      } else if (dashboard.selectedTest.value && dashboard.selectedTests.value.size === 1 && dashboard.activeTab.value === 'tests') {
        e.preventDefault()
        dashboard.navigateTestDetail('next')
      }
      break
    case 'ArrowLeft':
    case 'h':
      if (dashboard.activeTab.value === 'analytics' && dashboard.analyticsSelectedRunIdx.value !== null) {
        e.preventDefault()
        dashboard.analyticsSelectedRunIdx.value = Math.max(0, dashboard.analyticsSelectedRunIdx.value - 1)
      } else if (dashboard.selectedTest.value && dashboard.selectedTests.value.size === 1 && dashboard.activeTab.value === 'tests') {
        e.preventDefault()
        dashboard.navigateTestDetail('prev')
      }
      break
    case 'Enter':
      e.preventDefault()
      dashboard.activateFocusedTest()
      break
    case '1': dashboard.setTab('tests'); break
    case '2': dashboard.setTab('analytics'); break
    case '3': dashboard.setTab('weights'); break
    case '4': dashboard.setTab('ml'); break
    case '5': dashboard.setTab('mutation'); break
    case 'g':
      dashboard.setTab('tests')
      dashboard.setGraphMode('focus')
      break
    case 'm':
      if (dashboard.selectedTest.value && dashboard.hasMethodData.value) {
        const methods = dashboard.selectedTest.value.methods
        if (methods && methods.length > 0) {
          if (dashboard.selectedMethod.value) {
            dashboard.selectMethod(dashboard.selectedMethod.value, null)
          } else {
            dashboard.selectMethod(methods[0], null)
          }
        }
      }
      break
    case 'f':
      dashboard.setBadgeFilter(dashboard.badgeFilter.value === 'failing' ? null : 'failing')
      break
    case 'y':
      dashboard.setBadgeFilter(dashboard.badgeFilter.value === 'flaky' ? null : 'flaky')
      break
    case 'c':
      dashboard.setBadgeFilter(dashboard.badgeFilter.value === 'changed' ? null : 'changed')
      break
    case 'z':
      if (dashboard.selectedTest.value) {
        focusMode.value = !focusMode.value
      }
      break
    case 'b':
      if (dashboard.dd.changedClasses.length || dashboard.dd.changedTestClasses.length) {
        blameMode.value = !blameMode.value
      }
      break
  }
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onUnmounted(() => document.removeEventListener('keydown', onKeydown))

// Auto-exit focus mode when selection is cleared
watch(() => dashboard.selectedTest.value, (t) => {
  if (!t) focusMode.value = false
})

// Resizable sidebar
const SIDEBAR_MIN = 160
const SIDEBAR_MAX = 480
const sidebarWidth = ref(parseInt(localStorage.getItem('sidebarWidth') || '230', 10))
let isResizing = false

function startResize(e: MouseEvent) {
  isResizing = true
  const startX = e.clientX
  const startW = sidebarWidth.value
  const onMove = (ev: MouseEvent) => {
    if (!isResizing) return
    sidebarWidth.value = Math.max(SIDEBAR_MIN, Math.min(SIDEBAR_MAX, startW + ev.clientX - startX))
  }
  const onUp = () => {
    isResizing = false
    localStorage.setItem('sidebarWidth', String(sidebarWidth.value))
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', onUp)
  }
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
}
</script>

<template>
  <div v-if="error" style="display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;gap:12px">
    <h1 style="color:var(--red)">Dashboard Error</h1>
    <pre style="color:var(--text-sec);font-size:.8rem;max-width:600px;white-space:pre-wrap">{{ error }}</pre>
  </div>

  <div v-else-if="!dashboard.hasData" style="display:flex;align-items:center;justify-content:center;height:100vh">
    <NoDataSplash />
  </div>

  <div v-else style="display:flex;flex-direction:column;height:100vh;overflow:hidden">
    <AppHeader />
    <KpiRow />
    <TabBar />
    <div style="display:flex;flex:1;overflow:hidden">
      <Transition name="sidebar-collapse">
        <div v-show="!focusMode" :style="{ width: sidebarWidth + 'px', flexShrink: 0 }">
          <TestSidebar />
        </div>
      </Transition>
      <div
        v-show="!focusMode"
        class="app-splitter"
        @mousedown.prevent="startResize($event)"
        title="Drag to resize sidebar"
      ></div>
      <main style="flex:1;overflow-y:auto;padding:10px;min-width:0">
        <TestsTab />
        <AnalyticsTab />
        <WeightsTab />
        <MLTab />
        <MutationTab />
        <StaticAnalysisTab />
      </main>
    </div>
    <AppFooter />

    <!-- Score details modal -->
    <Transition name="modal-fade">
      <div
        v-if="dashboard.scoreModalOpen.value"
        class="score-modal__overlay"
        role="presentation"
        @click="dashboard.closeScoreModal()"
      >
        <section
          class="score-modal"
          role="dialog"
          aria-modal="true"
          aria-labelledby="score-modal-title"
          @click.stop
        >
          <header class="score-modal__header">
            <h2 id="score-modal-title" class="score-modal__title">{{ dashboard.scoreModalTitle.value }}</h2>
            <button class="score-modal__close" type="button" @click="dashboard.closeScoreModal()" aria-label="Close score details">×</button>
          </header>
          <p class="score-modal__hint">Score breakdown</p>
          <ScoreBreakdownPanel
            v-if="dashboard.scoreModalData.value"
            :data="dashboard.scoreModalData.value"
          />
          <pre v-else class="score-modal__body">{{ dashboard.scoreModalBody.value }}</pre>
        </section>
      </div>
    </Transition>

    <!-- Toast -->
    <Transition name="toast">
      <div v-if="toastMsg" class="toast">{{ toastMsg }}</div>
    </Transition>

    <!-- Command palette -->
    <CommandPalette :open="paletteOpen" @close="paletteOpen = false" />

    <!-- Test hover card (global, rendered via Teleport) -->
    <TestInfoCard
      :visible="testHover.visible.value"
      :x="testHover.x.value"
      :y="testHover.y.value"
      :testName="testHover.testName.value"
    />
  </div>
</template>

<style>
/* ── CSS variables and global resets (from dashboard.css) ── */
:root {
  --bg-base: #0f172a; --bg-card: #1e293b; --border: #334155;
  --border-accent: rgba(99, 102, 241, .2);
  --text: #f1f5f9; --text-sec: #94a3b8; --text-dim: #8899aa; --text-muted: #64748b;
  --accent: #6366f1; --accent-light: #818cf8; --accent-bg: rgba(99, 102, 241, .1);
  --green: #4ade80; --red: #f87171; --yellow: #fbbf24; --cyan: #67e8f9; --orange: #fb923c; --purple: #c084fc;
  --radius: 6px; --tr-fast: .15s; --tr-norm: .2s;
}
* { box-sizing: border-box; margin: 0; }
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  -webkit-font-smoothing: antialiased; background: var(--bg-base); color: var(--text);
  height: 100vh; overflow: hidden;
}

/* Shared utility classes used across components */
.badge { display: inline-flex; align-items: center; padding: 1px 6px; border-radius: 10px; font-size: .6rem; font-weight: 700; line-height: 1.4; letter-spacing: .4px; }
.kpi { background: linear-gradient(135deg, var(--bg-card) 0%, var(--bg-base) 100%); border: 1px solid var(--border-accent); border-radius: 8px; padding: 8px 12px; min-width: 110px; flex-shrink: 0; transition: all var(--tr-norm); cursor: default; }
.kpi:hover { border-color: rgba(99, 102, 241, .4); transform: translateY(-1px); box-shadow: 0 4px 12px rgba(99, 102, 241, .08); }
.card { background: var(--bg-card); border-radius: var(--radius); padding: 10px; }
.card-label { font-size: .72rem; color: var(--text-sec); margin-bottom: 6px; font-weight: 600; letter-spacing: .4px; text-transform: uppercase; font-size: .62rem; }
.detail-panel { background: var(--bg-card); border: 1px solid var(--border); border-radius: var(--radius); margin-top: 8px; padding: 12px; animation: slideDown var(--tr-norm) ease-out; }
@keyframes slideDown { from { opacity: 0; transform: translateY(-6px); } to { opacity: 1; transform: none; } }
.tree-node { cursor: pointer; transition: opacity var(--tr-fast); }
.tree-node:hover { opacity: .85; }
table { width: 100%; border-collapse: collapse; font-size: .75rem; }
thead tr { color: var(--text-sec); border-bottom: 1px solid var(--border); font-size: .7rem; text-transform: uppercase; letter-spacing: .5px; }
tbody tr { border-bottom: 1px solid rgba(51, 65, 85, .35); cursor: pointer; transition: background var(--tr-fast); }
tbody tr:hover { background: rgba(99, 102, 241, .07); }
input:focus { box-shadow: 0 0 0 2px rgba(99, 102, 241, .3); background: var(--bg-card) !important; }
.th-sort { cursor: pointer; user-select: none; white-space: nowrap; transition: color var(--tr-fast); font-weight: 600; }
.th-sort:hover { color: var(--accent-light); }
.tab-btn { padding: 7px 14px; font-size: .8rem; white-space: nowrap; border: none; border-bottom: 2px solid transparent; cursor: pointer; color: var(--text-sec); background: none; transition: all var(--tr-norm); font-weight: 500; }
.tab-btn:hover { color: var(--text); background: var(--accent-bg); }
.tab-btn.active { color: var(--accent); border-bottom-color: var(--accent); background: var(--accent-bg); }

/* Dark scrollbars */
::-webkit-scrollbar { width: 7px; height: 7px; }
::-webkit-scrollbar-track { background: var(--bg-base); }
::-webkit-scrollbar-thumb { background: #334155; border-radius: 4px; }
::-webkit-scrollbar-thumb:hover { background: #475569; }

/* Resizable sidebar splitter */
.app-splitter {
  width: 5px; flex-shrink: 0; cursor: col-resize;
  background: var(--border); transition: background var(--tr-fast);
  position: relative; z-index: 5;
}
.app-splitter:hover { background: var(--accent); }

/* Toast */
.toast {
  position: fixed; bottom: 24px; left: 50%; transform: translateX(-50%);
  background: #1e293b; color: var(--text); border: 1px solid var(--accent);
  padding: 8px 20px; border-radius: 8px; font-size: .78rem; font-weight: 500;
  box-shadow: 0 8px 24px rgba(0,0,0,.4); z-index: 100; pointer-events: none;
}
.toast-enter-active { transition: all .25s ease-out; }
.toast-leave-active { transition: all .2s ease-in; }
.toast-enter-from { opacity: 0; transform: translateX(-50%) translateY(12px); }
.toast-leave-to { opacity: 0; transform: translateX(-50%) translateY(-8px); }

/* Smooth content transition */
.fade-enter-active, .fade-leave-active { transition: opacity .15s ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

/* Score modal */
.score-modal__overlay {
  position: fixed;
  inset: 0;
  background: rgba(2, 6, 23, .72);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 160;
  padding: 20px;
}
.score-modal {
  width: min(900px, 100%);
  max-height: min(86vh, 820px);
  display: flex;
  flex-direction: column;
  background: #111827;
  border: 1px solid #334155;
  border-radius: 10px;
  box-shadow: 0 18px 60px rgba(0, 0, 0, .55);
}
.score-modal__header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border-bottom: 1px solid rgba(51, 65, 85, .6);
}
.score-modal__title {
  margin: 0;
  font-size: .86rem;
  color: var(--text);
  font-weight: 700;
  line-height: 1.25;
}
.score-modal__close {
  margin-left: auto;
  border: 1px solid var(--border);
  background: var(--bg-base);
  color: var(--text-sec);
  width: 26px;
  height: 26px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 1rem;
  line-height: 1;
}
.score-modal__close:hover {
  color: var(--text);
  border-color: var(--accent);
}
.score-modal__hint {
  margin: 10px 14px 6px;
  color: var(--text-muted);
  font-size: .66rem;
}
.score-modal__body {
  margin: 0 14px 14px;
  padding: 12px;
  border-radius: 8px;
  border: 1px solid rgba(51, 65, 85, .55);
  background: #020617;
  color: #cbd5e1;
  font-size: .69rem;
  line-height: 1.45;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
.modal-fade-enter-active, .modal-fade-leave-active { transition: opacity .16s ease; }
.modal-fade-enter-from, .modal-fade-leave-to { opacity: 0; }

/* Focus mode sidebar collapse */
.sidebar-collapse-enter-active, .sidebar-collapse-leave-active { transition: width .2s ease, opacity .2s ease; overflow: hidden; }
.sidebar-collapse-enter-from, .sidebar-collapse-leave-to { width: 0 !important; opacity: 0; }
</style>


