<script setup lang="ts">
import { inject, watch, nextTick, ref, computed, type Ref } from 'vue'
import type { DashboardState } from '../composables/useDashboard'
import type { TestHoverState } from '../composables/useTestHover'
import { sn, snStrip, fmtDur } from '../utils'
import TestBadges from './TestBadges.vue'

const d = inject<DashboardState>('dashboard')!
const showToast = inject<(msg: string) => void>('showToast')!
const testHover = inject<TestHoverState>('testHover')!

function dn(name: string): string {
  const mode = d.nameMode.value
  if (mode === 'full') return name
  if (mode === 'strip') return snStrip(name, d.commonPrefix.value)
  return sn(name)
}

function doExport() {
  d.exportCsv()
  showToast(`Exported ${d.filteredTests.value.length} tests as CSV`)
}

// Watchlist: up to 10 pinned tests persisted to localStorage
const MAX_WATCHLIST = 10
const watchlistNames = ref<string[]>(
  JSON.parse(localStorage.getItem('watchlist') ?? '[]')
)

function saveWatchlist() {
  localStorage.setItem('watchlist', JSON.stringify(watchlistNames.value))
}

function isWatched(name: string): boolean {
  return watchlistNames.value.includes(name)
}

function toggleWatch(name: string, e: MouseEvent) {
  e.stopPropagation()
  if (isWatched(name)) {
    watchlistNames.value = watchlistNames.value.filter(n => n !== name)
    saveWatchlist()
  } else if (watchlistNames.value.length < MAX_WATCHLIST) {
    watchlistNames.value = [...watchlistNames.value, name]
    saveWatchlist()
  } else {
    showToast(`Watchlist full (${MAX_WATCHLIST} max) — remove a test first`)
  }
}

const watchlistTests = computed(() =>
  watchlistNames.value
    .map(name => d.tests.find(t => t.name === name))
    .filter((t): t is NonNullable<typeof t> => t != null)
)

// Auto-scroll focused test into view
watch(() => d.focusedTestIndex.value, (idx: number) => {
  if (idx < 0) return
  nextTick(() => {
    const el = document.querySelector(`[data-test-idx="${idx}"]`)
    el?.scrollIntoView({ block: 'nearest' })
  })
})

// Auto-scroll to selected test (e.g. after command palette navigation)
watch(() => d.selectedTest.value, (t) => {
  if (!t) return
  const idx = d.filteredTests.value.findIndex(ft => ft.name === t.name)
  if (idx < 0) return
  nextTick(() => {
    const el = document.querySelector(`[data-test-idx="${idx}"]`)
    el?.scrollIntoView({ block: 'nearest' })
  })
})
</script>

<template>
  <aside style="width:100%;height:100%;flex-shrink:0;border-right:1px solid var(--border);display:flex;flex-direction:column;background:var(--bg-card);overflow:hidden">
    <div style="padding:6px 8px;border-bottom:1px solid var(--border);display:flex;flex-direction:column;gap:4px">
      <div class="sidebar__search-wrap">
        <input
          :value="d.searchQ.value"
          @input="d.searchQ.value = ($event.target as HTMLInputElement).value"
          placeholder="Filter…  is:failing  score>5  ( / )"
          data-search-main
          class="sidebar__search"
          title="Search tests. Examples: is:failing  is:slow  score>5  duration<500ms  method:setUp  -is:flaky"
        >
        <button v-if="d.searchQ.value" class="sidebar__search-clear" @click="d.searchQ.value = ''" title="Clear search (Esc)">×</button>
        <span
          class="sidebar__search-hint"
          title="Syntax: is:failing  is:flaky  is:new  is:changed  is:slow  is:fast&#10;score>N  duration<Nms  duration>Ns  failures>=N  deps>N&#10;method:name  plain text (substring)&#10;Prefix with - to negate: -is:flaky"
          style="cursor:help;color:var(--text-muted);font-size:.7rem;padding:0 4px"
        >?</span>
      </div>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span style="font-size:.65rem;color:var(--text-muted)">
          {{ d.filteredTests.value.length }}/{{ d.tests.length }}
          <span v-if="d.selectedTests.value.size > 1" style="color:var(--accent-light)"> · {{ d.selectedTests.value.size }} selected</span>
          <span v-if="d.tests.filter(t => t.failScore > 0).length > 0" style="color:var(--red);margin-left:4px" :title="d.tests.filter(t => t.failScore > 0).length + ' tests with fail history'"> · {{ d.tests.filter(t => t.failScore > 0).length }}✕</span>
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
      <!-- Quick filter chips -->
      <div v-if="d.tests.filter(t => t.failScore > 0).length > 0 || d.flakyTests.value.size > 0 || d.tests.filter(t => t.isNew || t.isChanged).length > 0" style="display:flex;gap:3px;flex-wrap:wrap;margin-top:2px">
        <button
          v-if="d.tests.filter(t => t.failScore > 0).length > 0"
          class="sidebar__filter-chip"
          :class="{ 'sidebar__filter-chip--active': d.badgeFilter.value === 'failing' }"
          @click="d.setBadgeFilter(d.badgeFilter.value === 'failing' ? null : 'failing')"
          :title="d.tests.filter(t => t.failScore > 0).length + ' tests with fail history'"
        >✕ fail ({{ d.tests.filter(t => t.failScore > 0).length }})</button>
        <button
          v-if="d.flakyTests.value.size > 0"
          class="sidebar__filter-chip sidebar__filter-chip--flaky"
          :class="{ 'sidebar__filter-chip--active': d.badgeFilter.value === 'flaky' }"
          @click="d.setBadgeFilter(d.badgeFilter.value === 'flaky' ? null : 'flaky')"
          :title="d.flakyTests.value.size + ' flaky tests (sometimes pass, sometimes fail)'"
        >~ flaky ({{ d.flakyTests.value.size }})</button>
        <button
          v-if="d.tests.filter(t => t.isNew).length > 0"
          class="sidebar__filter-chip sidebar__filter-chip--new"
          :class="{ 'sidebar__filter-chip--active': d.badgeFilter.value === 'new' }"
          @click="d.setBadgeFilter(d.badgeFilter.value === 'new' ? null : 'new')"
          :title="d.tests.filter(t => t.isNew).length + ' new tests'"
        >+ new ({{ d.tests.filter(t => t.isNew).length }})</button>
        <button
          v-if="d.tests.filter(t => t.isChanged).length > 0"
          class="sidebar__filter-chip sidebar__filter-chip--changed"
          :class="{ 'sidebar__filter-chip--active': d.badgeFilter.value === 'changed' }"
          @click="d.setBadgeFilter(d.badgeFilter.value === 'changed' ? null : 'changed')"
          :title="d.tests.filter(t => t.isChanged).length + ' tests with changed source'"
        >✎ chg ({{ d.tests.filter(t => t.isChanged).length }})</button>
      </div>
    </div>
    <div style="flex:1;overflow-y:auto" role="listbox" aria-label="Test list">
      <!-- Watchlist pinned section -->
      <div v-if="watchlistTests.length > 0" class="sidebar__watchlist">
        <div class="sidebar__watchlist-header">
          <span class="sidebar__watchlist-title">★ Watchlist</span>
          <span class="sidebar__watchlist-count">{{ watchlistTests.length }}/{{ MAX_WATCHLIST }}</span>
        </div>
        <div v-for="t in watchlistTests" :key="'wl-' + t.name">
          <div
            @click="d.selectTest(t, $event)"
            class="sidebar__test-row sidebar__test-row--watched"
            role="option"
            :aria-selected="d.selectedTests.value.has(t.name)"
            :class="{ 'sidebar__test-row--selected': d.selectedTests.value.has(t.name) }"
            :style="{ borderLeftColor: t.failScore > 0 ? 'rgba(248,113,113,.6)' : t.isNew ? 'rgba(74,222,128,.6)' : t.isChanged ? 'rgba(251,191,36,.6)' : 'rgba(251,191,36,.3)' }"
          >
            <div class="sidebar__test-main">
              <span class="sidebar__test-rank">#{{ t.rank }}</span>
              <span class="sidebar__test-name" :title="t.name"
                @mouseenter="testHover.show(t.name, $event)"
                @mousemove="testHover.move($event)"
                @mouseleave="testHover.hide()"
              >{{ dn(t.name) }}</span>
              <button class="sidebar__watch-btn sidebar__watch-btn--on" @click.stop="toggleWatch(t.name, $event)" title="Remove from watchlist">★</button>
            </div>
            <div class="sidebar__test-badges">
              <TestBadges :test="t" :flaky="d.flakyTests.value.has(t.name)" />
              <span class="sidebar__test-dur">{{ t.duration >= 0 ? fmtDur(t.duration) : '' }}</span>
            </div>
          </div>
        </div>
        <div class="sidebar__watchlist-divider"></div>
      </div>
      <!-- Main test list -->
      <div v-for="(t, idx) in d.filteredTests.value" :key="t.name" :data-test-idx="idx">
        <div
          @click="d.selectTest(t, $event)"
          @dblclick="d.drillDown(t)"
          class="sidebar__test-row"
          role="option"
          :aria-selected="d.selectedTests.value.has(t.name)"
          :class="{ 'sidebar__test-row--dimmed': t.score === 0 && !t.failScore && !t.isChanged && !t.isNew, 'sidebar__test-row--selected': d.selectedTests.value.has(t.name), 'sidebar__test-row--focused': d.focusedTestIndex.value === idx }"
          :style="{ borderLeftColor: t.failScore > 0 ? 'rgba(248,113,113,.6)' : t.isNew ? 'rgba(74,222,128,.6)' : t.isChanged ? 'rgba(251,191,36,.6)' : 'transparent' }"
        >
          <div class="sidebar__test-main">
            <span class="sidebar__test-rank">#{{ t.rank }}</span>
            <span class="sidebar__test-name" :title="t.name"
              @mouseenter="testHover.show(t.name, $event)"
              @mousemove="testHover.move($event)"
              @mouseleave="testHover.hide()"
            >{{ dn(t.name) }}</span>
            <button
              class="sidebar__test-score"
              type="button"
              :title="d.getScoreBreakdown(t.name, 'orig') + '\n\nClick to open detailed score modal'"
              @click.stop="d.openScoreModal(t.name, 'orig', 'Sidebar')"
            >{{ t.score }}</button>
            <button
              class="sidebar__watch-btn"
              :class="{ 'sidebar__watch-btn--on': isWatched(t.name) }"
              @click.stop="toggleWatch(t.name, $event)"
              :title="isWatched(t.name) ? 'Remove from watchlist' : 'Pin to watchlist (max ' + MAX_WATCHLIST + ')'"
            >{{ isWatched(t.name) ? '★' : '☆' }}</button>
          </div>
          <div class="sidebar__test-badges">
            <TestBadges :test="t" :flaky="d.flakyTests.value.has(t.name)" />
            <span class="sidebar__test-dur">{{ t.duration >= 0 ? fmtDur(t.duration) : '' }}</span>
            <span v-if="t.methods && t.methods.length" class="sidebar__test-methods">{{ t.methods.length }}m</span>
            <!-- Last 5 run dots -->
            <template v-if="d.testHistoryMap.value.get(t.name)">
              <span
                v-for="(failed, hi) in d.testHistoryMap.value.get(t.name)!.last8.slice(-5)"
                :key="hi"
                class="sidebar__hist-dot"
                :class="failed ? 'sidebar__hist-dot--fail' : 'sidebar__hist-dot--pass'"
                :title="(failed ? 'FAILED' : 'passed') + ' in run #' + (Math.max(0, d.runs.length - Math.min(5, d.testHistoryMap.value.get(t.name)!.last8.length)) + hi + 1)"
              ></span>
            </template>
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
      <span title="Navigate tests (overview) or prev/next (detail)"><kbd>↑</kbd><kbd>↓</kbd> nav</span>
      <span title="Select focused test"><kbd>⏎</kbd> select</span>
      <span title="Clear selection"><kbd>esc</kbd> clear</span>
      <span title="Toggle fail/flaky/changed filter"><kbd>f</kbd><kbd>y</kbd><kbd>c</kbd> filter</span>
      <span title="Previous/next test when viewing detail"><kbd>←</kbd><kbd>→</kbd> browse</span>
      <span title="Focus mode — hide sidebar for full-width detail"><kbd>z</kbd> focus</span>
      <span title="Blame mode — highlight tests linked to changed classes"><kbd>b</kbd> blame</span>
      <span title="Focus search (also Ctrl/Cmd+F)"><kbd>/</kbd> search</span>
      <span title="Switch to dependency graph view"><kbd>g</kbd> graph</span>
      <span v-if="d.hasMethodData" title="Toggle method mode in dep graph (select/deselect first method)"><kbd>m</kbd> method</span>
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
  background: none; cursor: pointer; color: var(--text-sec); transition: all var(--tr-fast);
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
.sidebar__test-rank { font-size: .62rem; color: var(--text-sec); width: 18px; flex-shrink: 0; text-align: right; }
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
.sidebar__test-badges { display: flex; gap: 2px; flex-wrap: wrap; margin-top: 2px; margin-left: 22px; align-items: center; }
.sidebar__test-dur { font-size: .58rem; color: var(--text-sec); margin-left: auto; }
.sidebar__test-methods { font-size: .58rem; color: var(--accent-light); }
.sidebar__hist-dot { width: 5px; height: 5px; border-radius: 50%; flex-shrink: 0; }
.sidebar__hist-dot--pass { background: rgba(74, 222, 128, .6); }
.sidebar__hist-dot--fail { background: #ef4444; }

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

.sidebar__filter-chip {
  padding: 1px 5px; font-size: .55rem; border-radius: 10px;
  border: 1px solid var(--border); background: none; cursor: pointer;
  color: var(--red); transition: all var(--tr-fast); white-space: nowrap;
}
.sidebar__filter-chip:hover { border-color: var(--red); background: rgba(248,113,113,.08); }
.sidebar__filter-chip--active { background: rgba(248,113,113,.15); border-color: rgba(248,113,113,.5); }
.sidebar__filter-chip--flaky { color: var(--orange); }
.sidebar__filter-chip--flaky:hover { border-color: var(--orange); background: rgba(251,146,60,.08); }
.sidebar__filter-chip--flaky.sidebar__filter-chip--active { background: rgba(251,146,60,.15); border-color: rgba(251,146,60,.5); }
.sidebar__filter-chip--new { color: var(--green); }
.sidebar__filter-chip--new:hover { border-color: var(--green); background: rgba(74,222,128,.08); }
.sidebar__filter-chip--new.sidebar__filter-chip--active { background: rgba(74,222,128,.15); border-color: rgba(74,222,128,.5); }
.sidebar__filter-chip--changed { color: var(--yellow); }
.sidebar__filter-chip--changed:hover { border-color: var(--yellow); background: rgba(251,191,36,.08); }
.sidebar__filter-chip--changed.sidebar__filter-chip--active { background: rgba(251,191,36,.15); border-color: rgba(251,191,36,.5); }

/* Watchlist */
.sidebar__watchlist { border-bottom: 1px solid rgba(251,191,36,.25); }
.sidebar__watchlist-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 3px 8px 2px; background: rgba(251,191,36,.07);
}
.sidebar__watchlist-title { font-size: .58rem; font-weight: 700; color: #fbbf24; letter-spacing: .03em; }
.sidebar__watchlist-count { font-size: .55rem; color: var(--text-muted); }
.sidebar__watchlist-divider { height: 3px; background: rgba(251,191,36,.15); }
.sidebar__test-row--watched { background: rgba(251,191,36,.04); }
.sidebar__test-row--watched:hover { background: rgba(251,191,36,.1); }

.sidebar__watch-btn {
  flex-shrink: 0; background: none; border: none; cursor: pointer;
  font-size: .65rem; color: var(--text-muted); padding: 0 1px; line-height: 1;
  opacity: 0; transition: opacity .1s, color .1s;
}
.sidebar__test-row:hover .sidebar__watch-btn { opacity: 1; }
.sidebar__watch-btn--on { opacity: 1 !important; color: #fbbf24; }
.sidebar__watch-btn:hover { color: #fbbf24; }

.sidebar__shortcut-bar {
  flex-shrink: 0; padding: 3px 8px; border-top: 1px solid var(--border);
  display: flex; gap: 8px; align-items: center; justify-content: center;
  font-size: .55rem; color: var(--text-sec); user-select: none;
}
.sidebar__shortcut-bar kbd {
  display: inline-block; padding: 0 3px; border: 1px solid var(--border);
  border-radius: 2px; font-family: inherit; font-size: .52rem; color: var(--text-sec);
  background: var(--bg-base); line-height: 1.5;
}
</style>
