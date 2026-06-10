<script setup lang="ts">
import { inject, watch, nextTick, ref, computed, type Ref } from 'vue'
import type { DashboardState } from '../composables/useDashboard'
import type { TestHoverState } from '../composables/useTestHover'
import { sn, snStrip, fmtDur } from '../utils'
import TestBadges from './TestBadges.vue'
import ModuleFilter from './ModuleFilter.vue'

const d = inject<DashboardState>('dashboard')!
const showToast = inject<(msg: string) => void>('showToast')!
const testHover = inject<TestHoverState>('testHover')!

// ── Autocomplete ─────────────────────────────────────────────────────────
const FILTER_TOKENS = [
  // is: flags
  { token: 'is:failing',   desc: 'has fail history (failScore > 0)' },
  { token: 'is:flaky',     desc: 'intermittently fails (pass + fail)' },
  { token: 'is:new',       desc: 'not seen in previous run' },
  { token: 'is:changed',   desc: 'test source was modified' },
  { token: 'is:slow',      desc: 'above median duration' },
  { token: 'is:fast',      desc: 'below median duration' },
  { token: 'is:affected',  desc: 'dep-overlap, changed, or new' },
  { token: 'is:dep',       desc: 'has dep overlap with changed classes' },
  { token: 'is:stat',      desc: 'reads static fields of changed classes' },
  { token: 'is:variance',  desc: 'high duration variance (CV≥50%)' },
  { token: 'is:method',   desc: 'has method-level dependency data' },
  { token: 'is:ml',       desc: 'has ML failure prediction' },
  { token: 'is:risk',     desc: 'ML predicts >50% failure probability' },
  // negations
  { token: '-is:failing',  desc: 'not: exclude tests with fail history' },
  { token: '-is:flaky',    desc: 'not: exclude flaky tests' },
  { token: '-is:slow',     desc: 'not: exclude slow tests' },
  { token: '-is:new',      desc: 'not: exclude new tests' },
  // numeric comparisons
  { token: 'score>',       desc: 'score above N  e.g. score>50' },
  { token: 'score<',       desc: 'score below N  e.g. score<20' },
  { token: 'rank>',        desc: 'rank position above N  e.g. rank>10' },
  { token: 'rank<',        desc: 'rank position below N  e.g. rank<5' },
  { token: 'pfail>',       desc: 'ML fail probability above N  e.g. pfail>0.3' },
  { token: 'pfail<',       desc: 'ML fail probability below N  e.g. pfail<0.5' },
  { token: 'duration>',    desc: 'slower than N  e.g. duration>1s' },
  { token: 'duration<',    desc: 'faster than N  e.g. duration<200ms' },
  { token: 'failures>=',   desc: 'failed N+ times  e.g. failures>=2' },
  { token: 'failures>',    desc: 'failed more than N times' },
  { token: 'deps>',        desc: 'more than N total deps' },
  { token: 'overlap>',     desc: 'more than N dep-overlap hits' },
  // text/glob
  { token: 'method:',      desc: 'method name contains  e.g. method:setUp' },
  { token: 'pkg:',         desc: 'package substring  e.g. pkg:service' },
  // sort
  { token: 'sort:rank',    desc: 'sort by priority rank (default)' },
  { token: 'sort:score',   desc: 'sort by score descending' },
  { token: 'sort:duration', desc: 'sort by duration' },
  { token: 'sort:duration:asc', desc: 'sort by duration ascending' },
  { token: 'sort:fail',    desc: 'sort by fail score descending' },
  { token: 'sort:name',    desc: 'sort alphabetically by name' },
  { token: 'sort:pfail',   desc: 'sort by ML fail probability (highest first)' },
  { token: 'sort:variance', desc: 'sort by duration variance' },
  { token: 'sort:overlap', desc: 'sort by dep overlap count' },
  { token: 'sort:deps',    desc: 'sort by total dep count' },
  { token: 'sort:stability', desc: 'sort by stability score (100=most stable)' },
  { token: 'sort:confidence', desc: 'sort by score confidence (% of runs with data)' },
  // logic
  { token: 'or',           desc: 'logical OR  e.g. is:failing or is:new' },
]

const searchInputEl = ref<HTMLInputElement | null>(null)
const acOpen = ref(false)
const acCursor = ref(0)
const acSuggestions = computed(() => {
  const q = d.searchQ.value
  const lastTok = q ? (q.split(/\s+/).at(-1) ?? '') : ''
  if (!lastTok) return FILTER_TOKENS  // show all when empty or space-ended
  const lower = lastTok.toLowerCase()
  const filtered = FILTER_TOKENS.filter(s => s.token.startsWith(lower) && s.token !== lower)
  return filtered.length ? filtered : FILTER_TOKENS
})

watch(acSuggestions, (s) => {
  if (s.length === 0) { acOpen.value = false; acCursor.value = 0 }
})

function onSearchInput(e: Event) {
  d.searchQ.value = (e.target as HTMLInputElement).value
  // Keep dropdown open if it was open; open it if we have a partial token
  const lastTok = d.searchQ.value.split(/\s+/).at(-1) ?? ''
  if (lastTok) acOpen.value = true
}

function applySuggestion(suggestion: string) {
  const q = d.searchQ.value
  const parts = q ? q.split(/\s+/) : ['']
  // If last part is empty (trailing space), append; otherwise replace last token
  if (q === '' || q.endsWith(' ')) {
    parts.push(suggestion)
  } else {
    parts[parts.length - 1] = suggestion
  }
  // Add trailing space for all except operators that expect a value suffix
  const needsTrailingSpace = suggestion === 'or' || (!suggestion.endsWith(':') && !suggestion.endsWith('>') && !suggestion.endsWith('<') && !suggestion.endsWith('=') && !suggestion.endsWith('>=') && !suggestion.endsWith('<='))
  d.searchQ.value = parts.filter(Boolean).join(' ') + (needsTrailingSpace ? ' ' : '')
  acOpen.value = false
  acCursor.value = 0
  nextTick(() => searchInputEl.value?.focus())
}

let acCloseTimer: ReturnType<typeof setTimeout> | null = null
function closeAcDelayed() {
  acCloseTimer && clearTimeout(acCloseTimer)
  acCloseTimer = window.setTimeout(() => { acOpen.value = false }, 120)
}

function onSearchKeydown(e: KeyboardEvent) {
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    if (!acOpen.value) { acOpen.value = true; acCursor.value = 0; return }
    acCursor.value = Math.min(acCursor.value + 1, acSuggestions.value.length - 1)
    return
  }
  if (!acOpen.value) return
  if (e.key === 'ArrowUp') { e.preventDefault(); acCursor.value = Math.max(acCursor.value - 1, 0) }
  else if (e.key === 'Tab' || e.key === 'Enter') {
    if (acSuggestions.value[acCursor.value]) { e.preventDefault(); applySuggestion(acSuggestions.value[acCursor.value].token) }
  } else if (e.key === 'Escape') { acOpen.value = false }
}

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

// Filter help panel
const helpOpen = ref(false)
function toggleHelp() { helpOpen.value = !helpOpen.value }
function closeHelp() { helpOpen.value = false }

// Saved filter presets
interface FilterPreset { name: string; query: string }
const presets = ref<FilterPreset[]>(
  JSON.parse(localStorage.getItem('filterPresets') ?? '[]')
)

function savePresets() {
  localStorage.setItem('filterPresets', JSON.stringify(presets.value))
}

const savePresetName = ref('')
const showSaveInput = ref(false)

function applyPreset(query: string) {
  d.searchQ.value = query
  helpOpen.value = false
}

function saveCurrentAsPreset() {
  const name = savePresetName.value.trim()
  const query = d.searchQ.value.trim()
  if (!name || !query) return
  if (presets.value.length >= 8) {
    showToast('Max 8 saved filters — remove one first')
    return
  }
  presets.value = [...presets.value, { name, query }]
  savePresets()
  savePresetName.value = ''
  showSaveInput.value = false
  showToast(`Filter "${name}" saved`)
}

function removePreset(idx: number) {
  presets.value = presets.value.filter((_, i) => i !== idx)
  savePresets()
}
</script>

<template>
  <aside style="width:100%;height:100%;flex-shrink:0;border-right:1px solid var(--border);display:flex;flex-direction:column;background:var(--bg-card);overflow:hidden">
    <div style="padding:6px 8px;border-bottom:1px solid var(--border);display:flex;flex-direction:column;gap:4px">
      <div class="sidebar__search-wrap" @click.stop>
        <input
          ref="searchInputEl"
          :value="d.searchQ.value"
          @input="onSearchInput"
          @keydown="onSearchKeydown"
          @blur="closeAcDelayed()"
          placeholder="Filter…  is:failing  score>5  *Service*"
          data-search-main
          class="sidebar__search"
          title="Filter tests. Type tokens, use Tab/↑↓ for autocomplete. Examples: is:failing  is:affected  score>5  duration<500ms  *Service*  is:failing or is:new  sort:duration"
          autocomplete="off"
          spellcheck="false"
        >
        <button v-if="d.searchQ.value" class="sidebar__search-clear" @click="d.searchQ.value = ''; acOpen = false" title="Clear search (Esc)">×</button>
        <button
          class="sidebar__help-btn"
          :class="{ 'sidebar__help-btn--active': helpOpen }"
          @click.stop="toggleHelp()"
          title="Filter syntax reference, saved filters &amp; keyboard shortcuts"
        >?</button>
        <!-- Autocomplete dropdown -->
        <div v-if="acOpen && acSuggestions.length" class="sidebar__ac-dropdown">
          <div
            v-for="(s, i) in acSuggestions"
            :key="s.token"
            class="sidebar__ac-item"
            :class="{ 'sidebar__ac-item--active': i === acCursor }"
            @mousedown.prevent="applySuggestion(s.token)"
            @mouseenter="acCursor = i"
          >
            <code class="sidebar__ac-token">{{ s.token }}</code>
            <span class="sidebar__ac-desc">{{ s.desc }}</span>
          </div>
          <div class="sidebar__ac-hint"><kbd>Tab</kbd>/<kbd>↵</kbd> accept · <kbd>↑↓</kbd> navigate · <kbd>Esc</kbd> close</div>
        </div>
      </div>

      <!-- Filter help panel -->
      <div v-if="helpOpen" class="sidebar__help-panel" @click.stop>
        <div class="sidebar__help-header">
          <span class="sidebar__help-title">Filter Syntax &amp; Shortcuts</span>
          <button class="sidebar__help-close" @click="closeHelp()">×</button>
        </div>
        <div class="sidebar__help-body">
          <div class="sidebar__help-section">
            <div class="sidebar__help-row"><code>is:failing</code><span>has fail history</span></div>
            <div class="sidebar__help-row"><code>is:flaky</code><span>intermittently fails</span></div>
            <div class="sidebar__help-row"><code>is:new</code><span>not seen before</span></div>
            <div class="sidebar__help-row"><code>is:changed</code><span>source modified</span></div>
            <div class="sidebar__help-row"><code>is:slow</code><span>above median duration</span></div>
            <div class="sidebar__help-row"><code>is:fast</code><span>below median duration</span></div>
            <div class="sidebar__help-row"><code>is:affected</code><span>dep-overlap, changed, or new</span></div>
            <div class="sidebar__help-row"><code>is:dep</code><span>has dep overlap with changes</span></div>
            <div class="sidebar__help-row"><code>is:stat</code><span>reads static fields of changed classes</span></div>
            <div class="sidebar__help-row"><code>is:variance</code><span>high duration variance (CV≥50%)</span></div>
            <div class="sidebar__help-row"><code>is:method</code><span>has method-level dep data</span></div>
            <div class="sidebar__help-row"><code>is:ml</code><span>has ML failure prediction</span></div>
            <div class="sidebar__help-row"><code>is:risk</code><span>ML predicts &gt;50% fail probability</span></div>
          </div>
          <div class="sidebar__help-section">
            <div class="sidebar__help-row"><code>score&gt;N</code><span>score above N</span></div>
            <div class="sidebar__help-row"><code>rank&lt;N</code><span>rank position lower than N</span></div>
            <div class="sidebar__help-row"><code>duration&lt;500ms</code><span>faster than 500ms</span></div>
            <div class="sidebar__help-row"><code>duration&gt;2s</code><span>slower than 2s</span></div>
            <div class="sidebar__help-row"><code>failures&gt;=2</code><span>failed 2+ times</span></div>
            <div class="sidebar__help-row"><code>deps&gt;5</code><span>more than 5 total deps</span></div>
            <div class="sidebar__help-row"><code>overlap&gt;2</code><span>more than 2 dep-overlap hits</span></div>
            <div class="sidebar__help-row"><code>pfail&gt;0.3</code><span>ML fail probability above 30%</span></div>
            <div class="sidebar__help-row"><code>method:setUp</code><span>method name match</span></div>
            <div class="sidebar__help-row"><code>pkg:service</code><span>package substring match</span></div>
          </div>
          <div class="sidebar__help-section sidebar__help-section--advanced">
            <div class="sidebar__help-row"><code>*Service*</code><span>glob — any test matching pattern</span></div>
            <div class="sidebar__help-row"><code>com.example.*</code><span>glob — prefix wildcard</span></div>
            <div class="sidebar__help-row"><code>A or B</code><span>OR — match A or match B</span></div>
            <div class="sidebar__help-row"><code>sort:duration</code><span>sort by duration via search</span></div>
            <div class="sidebar__help-row"><code>sort:stability</code><span>sort by stability score</span></div>
            <div class="sidebar__help-row"><code>sort:confidence</code><span>sort by score confidence</span></div>
            <div class="sidebar__help-row"><code>sort:score:asc</code><span>sort ascending</span></div>
            <div class="sidebar__help-row"><code>-is:flaky</code><span>negate — prefix any token with -</span></div>
          </div>
          <div class="sidebar__help-note">
            AND: <code>is:failing is:slow</code>
            &nbsp;·&nbsp;
            OR: <code>is:failing or is:new</code>
            &nbsp;·&nbsp;
            Negate: <code>-is:flaky</code>
            &nbsp;·&nbsp;
            Glob: <code>*Cart*</code>
            &nbsp;·&nbsp;
            Tab = autocomplete
          </div>
          <div class="sidebar__help-presets">
            <div class="sidebar__help-presets-title">Quick Presets</div>
            <div class="sidebar__help-preset-row">
              <button class="sidebar__preset-chip" @click="applyPreset('is:failing is:slow')">failing &amp; slow</button>
              <button class="sidebar__preset-chip" @click="applyPreset('is:failing -is:flaky')">failing, not flaky</button>
              <button class="sidebar__preset-chip" @click="applyPreset('score>50')">score &gt; 50</button>
              <button class="sidebar__preset-chip" @click="applyPreset('is:affected')">affected by changes</button>
              <button class="sidebar__preset-chip" @click="applyPreset('duration>1s')">slow (&gt;1s)</button>
              <button class="sidebar__preset-chip" @click="applyPreset('is:flaky or is:variance')">flaky or variance</button>
              <button class="sidebar__preset-chip" @click="applyPreset('is:variance')">high variance</button>
              <button class="sidebar__preset-chip" @click="applyPreset('is:dep is:slow')">dep-overlap &amp; slow</button>
              <button v-if="d.tests.some(t => t.mlPFail != null)" class="sidebar__preset-chip" @click="applyPreset('is:risk sort:pfail')">ML high-risk</button>
              <button v-if="d.tests.some(t => t.methods?.length)" class="sidebar__preset-chip" @click="applyPreset('is:method is:affected')">method data + affected</button>
            </div>
          </div>
          <div v-if="presets.length > 0" class="sidebar__help-presets">
            <div class="sidebar__help-presets-title">Saved Filters</div>
            <div class="sidebar__help-preset-row">
              <span v-for="(p, i) in presets" :key="i" class="sidebar__saved-preset">
                <button class="sidebar__preset-chip sidebar__preset-chip--saved" @click="applyPreset(p.query)" :title="p.query">{{ p.name }}</button>
                <button class="sidebar__preset-remove" @click="removePreset(i)" title="Remove saved filter">×</button>
              </span>
            </div>
          </div>
          <div class="sidebar__help-save">
            <div v-if="!showSaveInput" style="display:flex;align-items:center;gap:4px">
              <button
                class="sidebar__save-btn"
                :disabled="!d.searchQ.value.trim()"
                @click="showSaveInput = true"
                title="Save current filter as a named preset"
              >+ Save current filter</button>
            </div>
            <div v-else style="display:flex;align-items:center;gap:4px">
              <input
                v-model="savePresetName"
                class="sidebar__save-input"
                placeholder="Filter name…"
                @keydown.enter="saveCurrentAsPreset()"
                @keydown.escape="showSaveInput = false; savePresetName = ''"
                autofocus
              >
              <button class="sidebar__save-btn" @click="saveCurrentAsPreset()">Save</button>
              <button class="sidebar__save-btn" @click="showSaveInput = false; savePresetName = ''">Cancel</button>
            </div>
          </div>
        </div>
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
          :title="col.tip"
        >
          {{ col.label }}<span v-if="d.sortKey.value === col.key">{{ d.sortDir.value === 'asc' ? ' ↑' : ' ↓' }}</span>
        </button>
        <button class="sidebar__sort-btn sidebar__export-btn" @click="doExport()" title="Export test list as CSV">⤓ CSV</button>
      </div>
      <!-- Module filter (multi-module projects only) -->
      <ModuleFilter
        v-if="d.modules.value.length > 1"
        :modules="d.modules.value"
        :model-value="d.selectedModule.value"
        :count="d.filteredTests.value.length"
        @update:model-value="d.setModule($event)"
        style="margin-top:4px"
      />
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
                :title="(failed ? 'FAILED' : 'passed') + ' in run #' + (Math.min(5, d.testHistoryMap.value.get(t.name)!.last8.length) - hi)"
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
      <!-- Empty-state message when filter yields no results -->
      <div
        v-if="d.filteredTests.value.length === 0 && d.tests.length > 0"
        style="padding:16px 12px;text-align:center;color:var(--text-muted);font-size:.68rem;line-height:1.5"
      >
        <div style="font-size:1.1rem;margin-bottom:6px">∅</div>
        <div>No tests match <code style="color:var(--accent-light)">{{ d.searchQ.value }}</code></div>
        <div v-if="/pfail|mlpfail|is:ml|is:risk/.test(d.searchQ.value) && !d.tests.some(t => t.mlPFail != null)"
             style="margin-top:6px;color:var(--yellow)">
          No ML failure predictions in this dataset.<br>
          <code>pfail</code> and <code>is:ml</code> filters require ML model data.
        </div>
        <div v-else style="margin-top:4px;font-size:.62rem">
          Try clearing the filter (Esc) or using a different token.
        </div>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.sidebar__search-wrap { position: relative; display: flex; align-items: center; }
.sidebar__search {
  background: var(--bg-base); color: var(--text); font-size: .72rem; padding: 3px 44px 3px 8px;
  border: 1px solid var(--border); border-radius: 4px; outline: none; width: 100%;
  transition: border-color var(--tr-fast);
}
.sidebar__search:focus { border-color: var(--accent); }
.sidebar__search-clear {
  position: absolute; right: 22px; top: 50%; transform: translateY(-50%);
  background: none; border: none; color: var(--text-muted); cursor: pointer;
  font-size: .85rem; line-height: 1; padding: 2px 5px; border-radius: 3px;
  transition: color var(--tr-fast);
}
.sidebar__search-clear:hover { color: var(--text); background: rgba(255,255,255,.06); }
.sidebar__help-btn {
  position: absolute; right: 2px; top: 50%; transform: translateY(-50%);
  background: none; border: 1px solid var(--border); color: var(--text-muted); cursor: pointer;
  font-size: .65rem; font-weight: 700; line-height: 1; padding: 1px 5px; border-radius: 3px;
  transition: all var(--tr-fast);
}
.sidebar__help-btn:hover { color: var(--accent-light); border-color: var(--accent); }
.sidebar__help-btn--active { color: var(--accent-light); border-color: var(--accent); background: rgba(99,102,241,.12); }

/* Help panel */
.sidebar__help-panel {
  background: var(--bg-base); border: 1px solid var(--border); border-radius: 6px;
  padding: 8px; font-size: .65rem; box-shadow: 0 4px 16px rgba(0,0,0,.4);
  max-height: 60vh; display: flex; flex-direction: column; overflow: hidden;
}
.sidebar__help-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; flex-shrink: 0; }
.sidebar__help-title { font-size: .68rem; font-weight: 700; color: var(--accent-light); }
.sidebar__help-close {
  background: none; border: none; color: var(--text-muted); cursor: pointer;
  font-size: .8rem; padding: 0 3px; line-height: 1; border-radius: 2px;
}
.sidebar__help-close:hover { color: var(--text); background: rgba(255,255,255,.06); }
.sidebar__help-body { display: flex; flex-direction: column; gap: 6px; overflow-y: auto; flex: 1; min-height: 0; }
.sidebar__help-section { display: grid; grid-template-columns: 1fr 1fr; gap: 2px 8px; }
.sidebar__help-row { display: contents; }
.sidebar__help-row code {
  font-family: monospace; font-size: .6rem; color: var(--cyan);
  background: rgba(6,182,212,.1); border-radius: 2px; padding: 0 3px;
}
.sidebar__help-row span { color: var(--text-sec); font-size: .6rem; }
.sidebar__help-note { color: var(--text-muted); font-size: .6rem; }
.sidebar__help-note code {
  font-family: monospace; color: var(--cyan); background: rgba(6,182,212,.1);
  border-radius: 2px; padding: 0 3px;
}
.sidebar__help-presets { display: flex; flex-direction: column; gap: 3px; }
.sidebar__help-presets-title { font-size: .6rem; font-weight: 700; color: var(--text-sec); margin-bottom: 1px; }
.sidebar__help-preset-row { display: flex; flex-wrap: wrap; gap: 3px; }
.sidebar__preset-chip {
  padding: 2px 6px; font-size: .58rem; border-radius: 10px;
  border: 1px solid var(--border); background: none; cursor: pointer;
  color: var(--accent-light); transition: all var(--tr-fast); white-space: nowrap;
}
.sidebar__preset-chip:hover { border-color: var(--accent); background: rgba(99,102,241,.12); }
.sidebar__preset-chip--saved { color: var(--green); }
.sidebar__preset-chip--saved:hover { border-color: var(--green); background: rgba(74,222,128,.1); }
.sidebar__saved-preset { display: inline-flex; align-items: center; gap: 1px; }
.sidebar__preset-remove {
  padding: 1px 3px; font-size: .55rem; background: none; border: none;
  color: var(--text-muted); cursor: pointer; line-height: 1; border-radius: 2px;
}
.sidebar__preset-remove:hover { color: var(--red); }
.sidebar__help-save { padding-top: 4px; border-top: 1px solid var(--border); display: flex; flex-direction: column; gap: 3px; }
.sidebar__save-btn {
  padding: 2px 7px; font-size: .58rem; border-radius: 3px;
  border: 1px solid var(--border); background: none; cursor: pointer;
  color: var(--text-sec); transition: all var(--tr-fast);
}
.sidebar__save-btn:hover:not(:disabled) { color: var(--accent-light); border-color: var(--accent); }
.sidebar__save-btn:disabled { opacity: .4; cursor: default; }
.sidebar__save-input {
  flex: 1; background: var(--bg-base); color: var(--text); font-size: .62rem;
  padding: 2px 6px; border: 1px solid var(--border); border-radius: 3px; outline: none;
  transition: border-color var(--tr-fast);
}
.sidebar__save-input:focus { border-color: var(--accent); }

.sidebar__help-section--advanced { margin-top: 2px; border-top: 1px solid rgba(51,65,85,.5); padding-top: 4px; }
.sidebar__help-keys {
  display: grid; grid-template-columns: auto 1fr; gap: 2px 8px; font-size: .6rem;
}
.sidebar__help-keys span:nth-child(odd) {
  display: flex; gap: 2px; align-items: center; justify-content: flex-end;
  white-space: nowrap; color: var(--text);
}
.sidebar__help-keys span:nth-child(even) { color: var(--text-sec); }
.sidebar__help-keys kbd {
  display: inline-block; padding: 0 3px; border: 1px solid var(--border);
  border-radius: 2px; background: var(--bg-card); font-family: inherit;
  font-size: .55rem; color: var(--text-sec); line-height: 1.5;
}

/* Autocomplete dropdown */
.sidebar__ac-dropdown {
  position: absolute; top: calc(100% + 2px); left: 0; right: 0; z-index: 200;
  background: #1e293b; border: 1px solid #334155;
  border-radius: 6px; box-shadow: 0 8px 24px rgba(0,0,0,.5);
  overflow: hidden; max-height: 220px; overflow-y: auto;
}
.sidebar__ac-item {
  display: flex; align-items: center; gap: 6px;
  padding: 4px 8px; cursor: pointer; transition: background .08s;
}
.sidebar__ac-item:hover { background: rgba(99,102,241,.1); }
.sidebar__ac-item--active { background: rgba(99,102,241,.15); }
.sidebar__ac-token {
  font-family: monospace; font-size: .62rem; color: var(--cyan);
  background: rgba(6,182,212,.12); border-radius: 2px; padding: 0 4px; flex-shrink: 0;
}
.sidebar__ac-desc { font-size: .6rem; color: #64748b; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sidebar__ac-hint {
  padding: 3px 8px; font-size: .55rem; color: #475569; background: rgba(15,23,42,.5);
  border-top: 1px solid rgba(51,65,85,.4);
}
.sidebar__ac-hint kbd {
  padding: 0 3px; border: 1px solid #334155; border-radius: 2px;
  background: #1e293b; font-family: inherit; font-size: .5rem; color: #64748b;
}

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
.sidebar__method-name { font-size: .68rem; color: var(--text-sec); flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
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

</style>
