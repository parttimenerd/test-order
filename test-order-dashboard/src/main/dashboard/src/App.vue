<script setup lang="ts">
import { provide, onMounted, onUnmounted, ref } from 'vue'
import { useDashboard } from './composables/useDashboard'
import { parseDashboardData } from './data'
import NoDataSplash from './components/NoDataSplash.vue'
import AppHeader from './components/AppHeader.vue'
import KpiRow from './components/KpiRow.vue'
import TabBar from './components/TabBar.vue'
import TestSidebar from './components/TestSidebar.vue'
import TestsTab from './components/TestsTab.vue'
import AnalyticsTab from './components/AnalyticsTab.vue'
import WeightsTab from './components/WeightsTab.vue'
import MLTab from './components/MLTab.vue'
import AppFooter from './components/AppFooter.vue'

const { data, error } = parseDashboardData()
const dashboard = useDashboard(data, error)
provide('dashboard', dashboard)

const toastMsg = ref('')
let toastTimer = 0
function showToast(msg: string) {
  toastMsg.value = msg
  clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => { toastMsg.value = '' }, 2200)
}
provide('showToast', showToast)

function onKeydown(e: KeyboardEvent) {
  const tag = (e.target as HTMLElement)?.tagName
  const isInput = tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT'

  // Escape: blur input → clear search → clear selection
  if (e.key === 'Escape') {
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

  // "/" focuses sidebar search
  if (e.key === '/' && !isInput) {
    e.preventDefault()
    const el = document.querySelector<HTMLInputElement>('[data-search-main]')
    el?.focus()
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
    case 'Enter':
      e.preventDefault()
      dashboard.activateFocusedTest()
      break
    case '1': dashboard.setTab('tests'); break
    case '2': dashboard.setTab('analytics'); break
    case '3': dashboard.setTab('weights'); break
    case '4': dashboard.setTab('ml'); break
  }
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onUnmounted(() => document.removeEventListener('keydown', onKeydown))
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
      <TestSidebar />
      <main style="flex:1;overflow-y:auto;padding:10px">
        <TestsTab />
        <AnalyticsTab />
        <WeightsTab />
        <MLTab />
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
          <p class="score-modal__hint">Verbose score breakdown</p>
          <pre class="score-modal__body">{{ dashboard.scoreModalBody.value }}</pre>
        </section>
      </div>
    </Transition>

    <!-- Toast -->
    <Transition name="toast">
      <div v-if="toastMsg" class="toast">{{ toastMsg }}</div>
    </Transition>
  </div>
</template>

<style>
/* ── CSS variables and global resets (from dashboard.css) ── */
:root {
  --bg-base: #0f172a; --bg-card: #1e293b; --border: #334155;
  --border-accent: rgba(99, 102, 241, .2);
  --text: #f1f5f9; --text-sec: #94a3b8; --text-dim: #64748b; --text-muted: #475569;
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
.card-label { font-size: .72rem; color: var(--text-dim); margin-bottom: 6px; }
.detail-panel { background: var(--bg-card); border: 1px solid var(--border); border-radius: var(--radius); margin-top: 8px; padding: 12px; animation: slideDown var(--tr-norm) ease-out; }
@keyframes slideDown { from { opacity: 0; transform: translateY(-6px); } to { opacity: 1; transform: none; } }
.tree-node { cursor: pointer; transition: opacity var(--tr-fast); }
.tree-node:hover { opacity: .85; }
table { width: 100%; border-collapse: collapse; font-size: .75rem; }
thead tr { color: var(--text-dim); border-bottom: 1px solid var(--border); }
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
</style>


