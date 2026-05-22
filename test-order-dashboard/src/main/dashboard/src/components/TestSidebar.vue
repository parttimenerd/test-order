<script setup lang="ts">
import { inject, watch, nextTick } from 'vue'
import type { DashboardState } from '../composables/useDashboard'
import { sn, fmtDur } from '../utils'
import TestBadges from './TestBadges.vue'

const d = inject<DashboardState>('dashboard')!
const showToast = inject<(msg: string) => void>('showToast')!

function doExport() {
  d.exportCsv()
  showToast(`Exported ${d.filteredTests.value.length} tests as CSV`)
}

// Auto-scroll focused test into view
watch(() => d.focusedTestIndex.value, (idx: number) => {
  if (idx < 0) return
  nextTick(() => {
    const el = document.querySelector(`[data-test-idx="${idx}"]`)
    el?.scrollIntoView({ block: 'nearest' })
  })
})
</script>

<template>
  <aside style="width:230px;flex-shrink:0;border-right:1px solid var(--border);display:flex;flex-direction:column;background:var(--bg-card);overflow:hidden">
    <div style="padding:6px 8px;border-bottom:1px solid var(--border);display:flex;flex-direction:column;gap:4px">
      <div class="sidebar__search-wrap">
        <input
          :value="d.searchQ.value"
          @input="d.searchQ.value = ($event.target as HTMLInputElement).value"
          placeholder="Filter tests…  ( / )"
          data-search-main
          class="sidebar__search"
        >
        <button v-if="d.searchQ.value" class="sidebar__search-clear" @click="d.searchQ.value = ''" title="Clear search (Esc)">×</button>
      </div>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span style="font-size:.65rem;color:var(--text-muted)">
          {{ d.filteredTests.value.length }}/{{ d.tests.length }}
          <span v-if="d.selectedTests.value.size > 1" style="color:var(--accent-light)"> · {{ d.selectedTests.value.size }} selected</span>
        </span>
        <span v-if="d.hasMethodData.value" style="font-size:.6rem;color:var(--accent-light)" title="Method-level data available">⚙ methods</span>
        <span v-else style="font-size:.6rem;color:var(--text-muted);cursor:help;border-bottom:1px dotted var(--text-muted)" title="Method-level data requires METHOD or MEMBER instrumentation mode.">no methods</span>
      </div>
      <div style="display:flex;gap:3px;flex-wrap:wrap">
        <button
          v-for="col in d.SIDEBAR_SORT_COLS"
          :key="col.key"
          class="th-sort sidebar__sort-btn"
          :class="{ 'sidebar__sort-btn--active': d.sortKey.value === col.key }"
          @click="d.sortBy(col.key)"
          :aria-pressed="d.sortKey.value === col.key"
        >
          {{ col.label }}<span v-if="d.sortKey.value === col.key">{{ d.sortDir.value === 'asc' ? ' ↑' : ' ↓' }}</span>
        </button>
        <button class="sidebar__sort-btn sidebar__export-btn" @click="doExport()" title="Export test list as CSV">⤓ CSV</button>
      </div>
    </div>
    <div style="flex:1;overflow-y:auto" role="listbox" aria-label="Test list">
      <div v-for="(t, idx) in d.filteredTests.value" :key="t.name" :data-test-idx="idx">
        <div
          @click="d.selectTest(t, $event)"
          @dblclick="d.drillDown(t)"
          class="sidebar__test-row"
          role="option"
          :aria-selected="d.selectedTests.value.has(t.name)"
          :class="{ 'sidebar__test-row--dimmed': t.score === 0, 'sidebar__test-row--selected': d.selectedTests.value.has(t.name), 'sidebar__test-row--focused': d.focusedTestIndex.value === idx }"
        >
          <div class="sidebar__test-main">
            <span class="sidebar__test-rank">#{{ t.rank }}</span>
            <span class="sidebar__test-name" :title="t.name">{{ sn(t.name) }}</span>
            <button
              class="sidebar__test-score"
              type="button"
              :title="d.getScoreBreakdown(t.name, 'orig') + '\n\nClick to open detailed score modal'"
              @click.stop="d.openScoreModal(t.name, 'orig', 'Sidebar')"
            >{{ t.score }}</button>
          </div>
          <div class="sidebar__test-badges">
            <TestBadges :test="t" />
            <span class="sidebar__test-dur">{{ t.duration >= 0 ? fmtDur(t.duration) : '' }}</span>
            <span v-if="t.methods && t.methods.length" class="sidebar__test-methods">{{ t.methods.length }}m</span>
          </div>
        </div>
        <!-- Method sub-list -->
        <div
          v-if="d.selectedTests.value.has(t.name) && t.methods && t.methods.length"
          class="sidebar__method-list"
        >
          <div
            v-for="m in t.methods"
            :key="m.name"
            @click.stop="d.selectMethod(m, $event)"
            class="sidebar__method-row"
            :class="{ 'sidebar__method-row--selected': d.selectedMethods.value.has(m.name) }"
          >
            <span class="sidebar__method-name" :title="m.name">{{ m.name }}</span>
            <span class="sidebar__method-deps">{{ m.depCount }}</span>
          </div>
        </div>
      </div>
    </div>
    <!-- Keyboard hint -->
    <div class="sidebar__shortcut-bar">
      <span title="Navigate tests"><kbd>↑</kbd><kbd>↓</kbd> nav</span>
      <span title="Select focused test"><kbd>⏎</kbd> select</span>
      <span title="Clear selection"><kbd>esc</kbd> clear</span>
    </div>
  </aside>
</template>

<style scoped>
.sidebar__search-wrap { position: relative; display: flex; align-items: center; }
.sidebar__search {
  background: var(--bg-base); color: var(--text); font-size: .72rem; padding: 3px 24px 3px 8px;
  border: 1px solid var(--border); border-radius: 4px; outline: none; width: 100%;
  transition: border-color var(--tr-fast);
}
.sidebar__search:focus { border-color: var(--accent); }
.sidebar__search-clear {
  position: absolute; right: 2px; top: 50%; transform: translateY(-50%);
  background: none; border: none; color: var(--text-muted); cursor: pointer;
  font-size: .85rem; line-height: 1; padding: 2px 5px; border-radius: 3px;
  transition: color var(--tr-fast);
}
.sidebar__search-clear:hover { color: var(--text); background: rgba(255,255,255,.06); }

.sidebar__sort-btn {
  padding: 1px 5px; font-size: .6rem; border-radius: 3px; border: 1px solid var(--border);
  background: none; cursor: pointer; color: var(--text-dim); transition: all var(--tr-fast);
}
.sidebar__sort-btn:hover { color: var(--text-sec); border-color: var(--text-sec); }
.sidebar__sort-btn--active { color: var(--accent-light); border-color: var(--accent); }

.sidebar__test-row {
  padding: 4px 8px; cursor: pointer; border-bottom: 1px solid rgba(51, 65, 85, .25);
  transition: background .12s, border-left-color .12s; user-select: none; background: transparent;
  border-left: 2px solid transparent;
}
.sidebar__test-row:hover { background: rgba(99, 102, 241, .06); }
.sidebar__test-row--dimmed { opacity: .45; }
.sidebar__test-row--selected { background: rgba(99, 102, 241, .15); border-left-color: var(--accent); }
.sidebar__test-row--focused { outline: 1px solid var(--accent); outline-offset: -1px; }
.sidebar__test-main { display: flex; align-items: center; gap: 4px; }
.sidebar__test-rank { font-size: .62rem; color: var(--text-dim); width: 18px; flex-shrink: 0; text-align: right; }
.sidebar__test-name { font-size: .7rem; color: var(--text); flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sidebar__test-score {
  font-size: .68rem;
  font-weight: 700;
  color: var(--accent-light);
  flex-shrink: 0;
  background: none;
  border: none;
  padding: 0;
  cursor: pointer;
  text-decoration: underline dotted;
  text-underline-offset: 2px;
}
.sidebar__test-badges { display: flex; gap: 2px; flex-wrap: wrap; margin-top: 2px; margin-left: 22px; }
.sidebar__test-dur { font-size: .58rem; color: var(--text-dim); margin-left: auto; }
.sidebar__test-methods { font-size: .58rem; color: var(--accent-light); }

.sidebar__method-list { background: var(--bg-base); border-bottom: 1px solid rgba(51, 65, 85, .25); }
.sidebar__method-row {
  padding: 2px 8px 2px 30px; cursor: pointer; transition: background .1s;
  display: flex; align-items: center; gap: 4px; user-select: none; background: transparent;
}
.sidebar__method-row:hover { background: rgba(99, 102, 241, .06); }
.sidebar__method-row--selected { background: rgba(99, 102, 241, .12); }
.sidebar__method-name { font-size: .62rem; color: var(--text-sec); flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sidebar__method-deps { font-size: .58rem; color: var(--accent-light); flex-shrink: 0; }

.sidebar__export-btn { margin-left: auto; color: var(--text-muted); }
.sidebar__export-btn:hover { color: var(--accent-light); }

.sidebar__shortcut-bar {
  flex-shrink: 0; padding: 3px 8px; border-top: 1px solid var(--border);
  display: flex; gap: 8px; align-items: center; justify-content: center;
  font-size: .55rem; color: var(--text-muted); user-select: none;
}
.sidebar__shortcut-bar kbd {
  display: inline-block; padding: 0 3px; border: 1px solid var(--border);
  border-radius: 2px; font-family: inherit; font-size: .52rem; color: var(--text-dim);
  background: var(--bg-base); line-height: 1.5;
}
</style>
