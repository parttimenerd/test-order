<script setup lang="ts">
import { inject, computed, ref } from 'vue'
import type { DashboardState } from '../composables/useDashboard'

const d = inject<DashboardState>('dashboard')!

// Badge counts for each tab
const testsBadge = computed(() => {
  const failing = d.tests.filter(t => t.failScore > 0).length
  const changed = d.tests.filter(t => t.isChanged || t.isNew).length
  return changed > 0 ? changed : failing > 0 ? failing : null
})
const testsBadgeColor = computed(() => {
  const changed = d.tests.filter(t => t.isChanged || t.isNew).length
  return changed > 0 ? 'var(--yellow)' : 'var(--red)'
})
const analyticsBadge = computed(() => d.runs.length || null)
const runFailures = computed(() => d.latestRun.value?.totalFailures ?? 0)

const showLegend = ref(false)
</script>

<template>
  <nav class="tab-bar">
    <button
      v-for="(tab, i) in d.TABS.value"
      :key="tab.id"
      class="tab-btn"
      :class="{ active: d.activeTab.value === tab.id }"
      @click="d.setTab(tab.id)"
    >
      <span class="tab-btn__num">{{ i + 1 }}</span>
      {{ tab.label }}
      <!-- Badges -->
      <span
        v-if="tab.id === 'tests' && testsBadge"
        class="tab-badge"
        :style="{ background: testsBadgeColor, color: '#000' }"
        :title="d.tests.filter(t => t.isChanged || t.isNew).length + ' changed/new tests'"
      >{{ testsBadge }}</span>
      <span
        v-if="tab.id === 'analytics' && runFailures > 0"
        class="tab-badge tab-badge--fail"
        :title="runFailures + ' failures in latest run'"
      >{{ runFailures }}✕</span>
    </button>
    <span v-if="!d.hasCoverage" class="tab-bar__hint" title="Coverage data requires dependency data from the test-order agent. Run tests with instrumentation to collect it.">coverage N/A</span>
    <span v-else class="tab-bar__hint tab-bar__hint--ok" @click="d.setTab('analytics')" style="cursor:pointer" title="Coverage data available — click to view in Analytics tab">coverage ✓</span>

    <!-- Legend button -->
    <button class="tab-bar__legend-btn" @click="showLegend = !showLegend" :title="showLegend ? 'Hide color legend' : 'Show color legend (what do the colors mean?)'">
      {{ showLegend ? '✕' : '?' }}
    </button>

    <!-- Legend popover -->
    <div v-if="showLegend" class="tab-bar__legend-panel">
      <div class="tab-bar__legend-title">Color Legend & Keyboard Shortcuts</div>
      <div class="tab-bar__legend-grid">
        <div class="tab-bar__legend-section">
          <div class="tab-bar__legend-head">Badge / Score Colors</div>
          <div class="tab-bar__legend-row"><span class="tab-bar__legend-dot" style="background:var(--red)"></span>Failing / High fail score</div>
          <div class="tab-bar__legend-row"><span class="tab-bar__legend-dot" style="background:var(--yellow)"></span>Changed / New test</div>
          <div class="tab-bar__legend-row"><span class="tab-bar__legend-dot" style="background:var(--green)"></span>Passing / Fast test</div>
          <div class="tab-bar__legend-row"><span class="tab-bar__legend-dot" style="background:var(--cyan)"></span>Has dep overlap</div>
          <div class="tab-bar__legend-row"><span class="tab-bar__legend-dot" style="background:var(--orange)"></span>Slow test</div>
          <div class="tab-bar__legend-row"><span class="tab-bar__legend-dot" style="background:var(--purple)"></span>Static field overlap</div>
          <div class="tab-bar__legend-row"><span class="tab-bar__legend-dot" style="background:var(--accent-light)"></span>Score / Accent</div>
          <div class="tab-bar__legend-head" style="margin-top:6px">History Dots (last 8 runs)</div>
          <div class="tab-bar__legend-row"><span class="tab-bar__legend-dot" style="background:var(--green);opacity:.7"></span>passed in that run</div>
          <div class="tab-bar__legend-row"><span class="tab-bar__legend-dot" style="background:var(--red)"></span>FAILED in that run</div>
        </div>
        <div class="tab-bar__legend-section">
          <div class="tab-bar__legend-head">Keyboard Shortcuts</div>
          <div class="tab-bar__legend-row"><kbd class="tab-bar__kbd">1</kbd><kbd class="tab-bar__kbd">2</kbd><kbd class="tab-bar__kbd">3</kbd> Switch tabs</div>
          <div class="tab-bar__legend-row"><kbd class="tab-bar__kbd">j</kbd><kbd class="tab-bar__kbd">k</kbd> Navigate tests</div>
          <div class="tab-bar__legend-row"><kbd class="tab-bar__kbd">⏎</kbd> Select / Open</div>
          <div class="tab-bar__legend-row"><kbd class="tab-bar__kbd">/</kbd> Search tests</div>
          <div class="tab-bar__legend-row"><kbd class="tab-bar__kbd">Esc</kbd> Clear / Back</div>
          <div class="tab-bar__legend-row"><kbd class="tab-bar__kbd">Ctrl</kbd>+click Multi-select</div>
          <div class="tab-bar__legend-row"><kbd class="tab-bar__kbd">Shift</kbd>+click Range select</div>
        </div>
        <div class="tab-bar__legend-section">
          <div class="tab-bar__legend-head">Metrics Explained</div>
          <div class="tab-bar__legend-row"><strong style="color:var(--accent-light)">APFD</strong> — Avg % faults detected early</div>
          <div class="tab-bar__legend-row"><strong style="color:var(--green)">100%</strong> = all failures detected first</div>
          <div class="tab-bar__legend-row"><strong style="color:var(--text-muted)">50%</strong> = random ordering baseline</div>
          <div class="tab-bar__legend-row"><strong style="color:var(--text-sec)">EMA</strong> — Exponential moving avg</div>
          <div class="tab-bar__legend-row"><strong style="color:var(--text-sec)">Score</strong> — Priority: higher = run sooner</div>
          <div class="tab-bar__legend-row"><strong style="color:var(--cyan)">Dep overlap</strong> — Test covers changed src</div>
          <div class="tab-bar__legend-head" style="margin-top:6px">Rank Trend (test detail)</div>
          <div class="tab-bar__legend-row"><span style="color:var(--green);font-weight:700">▲ rank +N</span> — moving earlier over runs</div>
          <div class="tab-bar__legend-row"><span style="color:var(--red);font-weight:700">▼ rank -N</span> — moving later (deprioritized)</div>
          <div class="tab-bar__legend-row"><span style="color:var(--text-muted);font-weight:700">= stable</span> — rank unchanged</div>
          <div class="tab-bar__legend-head" style="margin-top:6px">Dep Graph Search</div>
          <div class="tab-bar__legend-row">Matching nodes bright · neighbors dim · rest faded</div>
        </div>
      </div>
    </div>
  </nav>
</template>

<style scoped>
.tab-bar { background: var(--bg-card); border-bottom: 1px solid var(--border); display: flex; overflow-x: auto; overflow-y: visible; flex-shrink: 0; align-items: center; position: relative; z-index: 10; }
.tab-btn__num {
  display: inline-flex; align-items: center; justify-content: center;
  width: 14px; height: 14px; border-radius: 3px; font-size: .55rem;
  background: rgba(71, 85, 105, .3); color: var(--text-muted);
  margin-right: 4px; font-weight: 700; vertical-align: middle;
}
.tab-btn.active .tab-btn__num { background: rgba(99, 102, 241, .25); color: var(--accent-light); }
.tab-badge {
  display: inline-flex; align-items: center; justify-content: center;
  min-width: 16px; height: 14px; padding: 0 3px; border-radius: 7px;
  font-size: .52rem; font-weight: 700; margin-left: 4px; line-height: 1;
}
.tab-badge--fail { background: rgba(239,68,68,.3); color: var(--red); }
.tab-bar__hint { font-size: .62rem; color: var(--text-muted); margin-left: auto; margin-right: 4px; cursor: help; border-bottom: 1px dotted var(--text-muted); }
.tab-bar__hint--ok { color: var(--green); border-bottom-color: var(--green); }

/* Legend button */
.tab-bar__legend-btn {
  margin-left: 4px; margin-right: 8px; width: 18px; height: 18px;
  border-radius: 50%; border: 1px solid var(--border); background: var(--bg-base);
  color: var(--text-sec); font-size: .65rem; font-weight: 700; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0; transition: all var(--tr-fast);
}
.tab-bar__legend-btn:hover { border-color: var(--accent); color: var(--accent-light); }

/* Legend popover */
.tab-bar__legend-panel {
  position: fixed; top: 64px; right: 8px; z-index: 100;
  background: var(--bg-card); border: 1px solid var(--border);
  border-radius: var(--radius); padding: 12px 14px;
  box-shadow: 0 8px 32px rgba(0,0,0,.6);
  min-width: 540px; max-width: 90vw;
}
.tab-bar__legend-title { font-size: .72rem; font-weight: 700; color: var(--text); margin-bottom: 10px; border-bottom: 1px solid var(--border); padding-bottom: 6px; }
.tab-bar__legend-grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 14px; }
.tab-bar__legend-section { display: flex; flex-direction: column; gap: 4px; }
.tab-bar__legend-head { font-size: .62rem; font-weight: 700; color: var(--accent-light); text-transform: uppercase; letter-spacing: .5px; margin-bottom: 3px; }
.tab-bar__legend-row { display: flex; align-items: center; gap: 6px; font-size: .65rem; color: var(--text-sec); }
.tab-bar__legend-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.tab-bar__kbd {
  display: inline-block; padding: 0 3px; border: 1px solid var(--border); border-radius: 3px;
  font-family: inherit; font-size: .58rem; background: var(--bg-base); color: var(--text-sec);
  line-height: 1.6; margin: 0 1px;
}
</style>
