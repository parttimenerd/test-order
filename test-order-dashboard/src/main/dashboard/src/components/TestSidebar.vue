<script setup lang="ts">
import { inject } from 'vue'
import type { DashboardState } from '../composables/useDashboard'
import { sn, fmtDur } from '../utils'
import TestBadges from './TestBadges.vue'

const d = inject<DashboardState>('dashboard')!
</script>

<template>
  <aside style="width:230px;flex-shrink:0;border-right:1px solid var(--border);display:flex;flex-direction:column;background:var(--bg-card);overflow:hidden">
    <div style="padding:6px 8px;border-bottom:1px solid var(--border);display:flex;flex-direction:column;gap:4px">
      <input
        :value="d.searchQ.value"
        @input="d.searchQ.value = ($event.target as HTMLInputElement).value"
        placeholder="Filter tests…"
        style="background:var(--bg-base);color:var(--text);font-size:.72rem;padding:3px 8px;border:1px solid var(--border);border-radius:4px;outline:none;width:100%"
      >
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span style="font-size:.65rem;color:var(--text-muted)">
          {{ d.filteredTests.value.length }}/{{ d.tests.length }}
          <span v-if="d.selectedTests.value.size > 1" style="color:var(--accent-light)"> · {{ d.selectedTests.value.size }} selected</span>
        </span>
        <span v-if="d.hasMethodData.value" style="font-size:.6rem;color:var(--accent-light)" title="Method-level data available">⚙ methods</span>
        <span v-else style="font-size:.6rem;color:var(--text-muted);cursor:help;border-bottom:1px dotted var(--text-muted)" title="Method-level data requires FULL_METHOD or FULL_MEMBER instrumentation mode.">no methods</span>
      </div>
      <div style="display:flex;gap:3px;flex-wrap:wrap">
        <span
          v-for="col in d.SIDEBAR_SORT_COLS"
          :key="col.key"
          class="th-sort sidebar__sort-btn"
          :class="{ 'sidebar__sort-btn--active': d.sortKey.value === col.key }"
          @click="d.sortBy(col.key)"
        >
          {{ col.label }}<span v-if="d.sortKey.value === col.key">{{ d.sortDir.value === 'asc' ? ' ↑' : ' ↓' }}</span>
        </span>
      </div>
    </div>
    <div style="flex:1;overflow-y:auto">
      <div v-for="t in d.filteredTests.value" :key="t.name">
        <div
          @click="d.selectTest(t, $event)"
          @dblclick="d.drillDown(t)"
          class="sidebar__test-row"
          :class="{ 'sidebar__test-row--dimmed': t.score === 0, 'sidebar__test-row--selected': d.selectedTests.value.has(t.name) }"
        >
          <div class="sidebar__test-main">
            <span class="sidebar__test-rank">#{{ t.rank }}</span>
            <span class="sidebar__test-name" :title="t.name">{{ sn(t.name) }}</span>
            <span class="sidebar__test-score">{{ t.score }}</span>
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
  </aside>
</template>

<style scoped>
.sidebar__sort-btn {
  padding: 1px 5px; font-size: .6rem; border-radius: 3px; border: 1px solid var(--border);
  background: none; cursor: pointer; color: var(--text-dim);
}
.sidebar__sort-btn--active { color: var(--accent-light); border-color: var(--accent); }

.sidebar__test-row {
  padding: 4px 8px; cursor: pointer; border-bottom: 1px solid rgba(51, 65, 85, .25);
  transition: background .1s; user-select: none; background: transparent;
}
.sidebar__test-row--dimmed { opacity: .45; }
.sidebar__test-row--selected { background: rgba(99, 102, 241, .15); }
.sidebar__test-main { display: flex; align-items: center; gap: 4px; }
.sidebar__test-rank { font-size: .62rem; color: var(--text-dim); width: 18px; flex-shrink: 0; text-align: right; }
.sidebar__test-name { font-size: .7rem; color: var(--text); flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sidebar__test-score { font-size: .68rem; font-weight: 700; color: var(--accent-light); flex-shrink: 0; }
.sidebar__test-badges { display: flex; gap: 2px; flex-wrap: wrap; margin-top: 2px; margin-left: 22px; }
.sidebar__test-dur { font-size: .58rem; color: var(--text-dim); margin-left: auto; }
.sidebar__test-methods { font-size: .58rem; color: var(--accent-light); }

.sidebar__method-list { background: var(--bg-base); border-bottom: 1px solid rgba(51, 65, 85, .25); }
.sidebar__method-row {
  padding: 2px 8px 2px 30px; cursor: pointer; transition: background .1s;
  display: flex; align-items: center; gap: 4px; user-select: none; background: transparent;
}
.sidebar__method-row--selected { background: rgba(99, 102, 241, .12); }
.sidebar__method-name { font-size: .62rem; color: var(--text-sec); flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sidebar__method-deps { font-size: .58rem; color: var(--accent-light); flex-shrink: 0; }
</style>
