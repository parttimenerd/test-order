<script setup lang="ts">
import { provide } from 'vue'
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
import AppFooter from './components/AppFooter.vue'

const { data, error } = parseDashboardData()
const dashboard = useDashboard(data, error)
provide('dashboard', dashboard)
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
      </main>
    </div>
    <AppFooter />
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
</style>


