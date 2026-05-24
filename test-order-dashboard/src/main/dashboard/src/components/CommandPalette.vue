<script setup lang="ts">
import { inject, ref, computed, watch, nextTick, type Ref } from 'vue'
import type { DashboardState } from '../composables/useDashboard'
import { sn, fmtDur } from '../utils'
import TestBadges from './TestBadges.vue'

const d = inject<DashboardState>('dashboard')!
const shortNames = inject<Ref<boolean>>('shortNames', { value: true } as any)
const toggleShortNames = inject<() => void>('toggleShortNames')!
function dn(name: string) { return shortNames.value ? sn(name) : name }

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ (e: 'close'): void }>()

const q = ref('')
const cursor = ref(0)
const inputEl = ref<HTMLInputElement | null>(null)

type ActionItem = { type: 'action'; icon: string; label: string; desc: string; run: () => void; keywords: string[] }
type TestItem = { type: 'test'; test: typeof d.tests[0] }
type Item = ActionItem | TestItem

const ACTIONS: ActionItem[] = [
  { type: 'action', icon: '⬜', label: 'Go to Tests tab', desc: 'Navigate to the Tests overview', keywords: ['go', 'tests', 'tab', 'navigate'], run: () => d.setTab('tests') },
  { type: 'action', icon: '📊', label: 'Go to Analytics tab', desc: 'Navigate to the Analytics overview', keywords: ['go', 'analytics', 'tab', 'navigate', 'chart'], run: () => d.setTab('analytics') },
  { type: 'action', icon: '⚖', label: 'Go to Weights tab', desc: 'Navigate to the Weights editor', keywords: ['go', 'weights', 'tab', 'navigate', 'sim'], run: () => d.setTab('weights') },
  { type: 'action', icon: '🤖', label: 'Go to ML tab', desc: 'Navigate to the ML predictions view', keywords: ['go', 'ml', 'machine', 'learning', 'tab', 'navigate', 'predict'], run: () => d.setTab('ml') },
  { type: 'action', icon: '✕', label: 'Filter: failing', desc: 'Show only tests with fail history', keywords: ['filter', 'fail', 'failing', 'broken'], run: () => d.setBadgeFilter('failing') },
  { type: 'action', icon: '~', label: 'Filter: flaky', desc: 'Show only tests that are intermittently failing', keywords: ['filter', 'flaky', 'intermittent', 'unstable'], run: () => d.setBadgeFilter('flaky') },
  { type: 'action', icon: '✎', label: 'Filter: changed', desc: 'Show only tests with changed source', keywords: ['filter', 'changed', 'modified'], run: () => d.setBadgeFilter('changed') },
  { type: 'action', icon: '+', label: 'Filter: new', desc: 'Show only new tests (first seen in latest run)', keywords: ['filter', 'new', 'added'], run: () => d.setBadgeFilter('new') },
  { type: 'action', icon: '🐢', label: 'Filter: slow', desc: 'Show only slow tests (above median duration)', keywords: ['filter', 'slow', 'duration'], run: () => d.setBadgeFilter('slow') },
  { type: 'action', icon: '⚡', label: 'Filter: fast', desc: 'Show only fast tests (below median duration)', keywords: ['filter', 'fast', 'duration', 'quick'], run: () => d.setBadgeFilter('fast') },
  { type: 'action', icon: '⊘', label: 'Clear filter', desc: 'Remove the active filter', keywords: ['clear', 'filter', 'reset', 'all'], run: () => d.setBadgeFilter(null) },
  { type: 'action', icon: '⊘', label: 'Reset sort', desc: 'Reset table sort to default rank order', keywords: ['reset', 'sort', 'rank', 'order'], run: () => d.sortBy('rank') },
  { type: 'action', icon: '↓', label: 'Export CSV', desc: 'Download visible tests as CSV', keywords: ['export', 'csv', 'download', 'save'], run: () => d.exportCsv() },
  { type: 'action', icon: '⌫', label: 'Clear selection', desc: 'Deselect all tests', keywords: ['clear', 'selection', 'deselect', 'esc'], run: () => d.clearSelection() },
  { type: 'action', icon: '🔤', label: 'Toggle short names', desc: 'Switch between abbreviated and full class names', keywords: ['short', 'names', 'abbreviate', 'full', 'toggle', 'package'], run: () => toggleShortNames() },
  { type: 'action', icon: '⊞', label: 'Select all visible', desc: 'Select all currently visible tests for comparison', keywords: ['select', 'all', 'visible', 'bulk'], run: () => d.selectAllVisible() },
]

const isCommandMode = computed(() => q.value.trimStart().startsWith('>'))

const items = computed<Item[]>(() => {
  const s = isCommandMode.value ? q.value.replace(/^>?\s*/, '').toLowerCase() : q.value.trim().toLowerCase()

  if (isCommandMode.value) {
    const filtered = s ? ACTIONS.filter(a => a.label.toLowerCase().includes(s) || a.keywords.some(k => k.includes(s))) : ACTIONS
    return filtered.slice(0, 20)
  }

  // Normal mode: show actions first if query matches action keywords, then tests
  const testItems: TestItem[] = (s
    ? d.tests.filter(t => t.name.toLowerCase().includes(s) || sn(t.name).toLowerCase().includes(s))
    : d.tests.slice(0, 20)
  ).slice(0, 18).map(t => ({ type: 'test' as const, test: t }))

  // Also show matching actions inline
  if (s) {
    const matchedActions = ACTIONS.filter(a => a.label.toLowerCase().includes(s) || a.keywords.some(k => k.includes(s))).slice(0, 3)
    return [...matchedActions, ...testItems]
  }
  return testItems
})

watch(() => props.open, (v) => {
  if (v) { q.value = ''; cursor.value = 0; nextTick(() => inputEl.value?.focus()) }
})

watch(q, () => { cursor.value = 0 })

function runItem(item: Item) {
  if (item.type === 'action') {
    item.run()
  } else {
    d.selectTest(item.test, null)
    d.setTab('tests')
  }
  emit('close')
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'ArrowDown') { e.preventDefault(); cursor.value = Math.min(cursor.value + 1, items.value.length - 1) }
  else if (e.key === 'ArrowUp') { e.preventDefault(); cursor.value = Math.max(cursor.value - 1, 0) }
  else if (e.key === 'Enter') { if (items.value[cursor.value]) runItem(items.value[cursor.value]) }
  else if (e.key === 'Escape') emit('close')
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="cp-overlay" @click.self="emit('close')">
      <div class="cp-panel" role="dialog" aria-modal="true" aria-label="Command palette">
        <div class="cp-search-wrap">
          <span class="cp-search-icon">{{ isCommandMode ? '>' : '⌕' }}</span>
          <input
            ref="inputEl"
            v-model="q"
            class="cp-input"
            :placeholder="isCommandMode ? 'Type a command…' : 'Search tests or type > for commands…'"
            autocomplete="off"
            spellcheck="false"
            @keydown="onKeydown"
          />
          <kbd class="cp-esc-hint" @click="emit('close')">esc</kbd>
        </div>
        <div class="cp-results" role="listbox">
          <div v-if="!items.length" class="cp-empty">No results for "{{ q }}"</div>
          <template v-for="(item, i) in items" :key="item.type === 'test' ? item.test.name : item.label">
            <!-- Section divider when actions and tests are mixed -->
            <div v-if="!isCommandMode && i > 0 && item.type === 'test' && items[i-1].type === 'action'" class="cp-divider">Tests</div>
            <div v-if="!isCommandMode && i === 0 && item.type === 'action'" class="cp-divider">Actions</div>
            <!-- Action row -->
            <div
              v-if="item.type === 'action'"
              class="cp-row cp-row--action"
              :class="{ 'cp-row--active': i === cursor }"
              role="option"
              :aria-selected="i === cursor"
              @mouseenter="cursor = i"
              @click="runItem(item)"
              :title="item.desc"
            >
              <span class="cp-action-icon">{{ item.icon }}</span>
              <span class="cp-action-label">{{ item.label }}</span>
              <span class="cp-action-desc">{{ item.desc }}</span>
            </div>
            <!-- Test row -->
            <div
              v-else-if="item.type === 'test'"
              class="cp-row"
              :class="{ 'cp-row--active': i === cursor }"
              role="option"
              :aria-selected="i === cursor"
              @mouseenter="cursor = i"
              @click="runItem(item)"
            >
              <span class="cp-rank">#{{ item.test.rank }}</span>
              <span class="cp-name" :title="item.test.name">{{ dn(item.test.name) }}</span>
              <div class="cp-meta">
                <TestBadges :test="item.test" :flaky="d.flakyTests.value.has(item.test.name)" />
                <span class="cp-score">{{ item.test.score }}</span>
                <span v-if="item.test.duration >= 0" class="cp-dur">{{ fmtDur(item.test.duration) }}</span>
              </div>
            </div>
          </template>
        </div>
        <div class="cp-footer">
          <span><kbd>↑</kbd><kbd>↓</kbd> navigate</span>
          <span><kbd>⏎</kbd> select</span>
          <span><kbd>esc</kbd> close</span>
          <span class="cp-footer-hint">type <kbd>></kbd> for commands</span>
          <span class="cp-footer-count">{{ d.tests.length }} tests</span>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.cp-overlay {
  position: fixed; inset: 0; z-index: 8000;
  background: rgba(0, 0, 0, .65);
  display: flex; align-items: flex-start; justify-content: center;
  padding-top: 12vh;
  backdrop-filter: blur(2px);
  animation: cp-fade-in .1s ease-out;
}
@keyframes cp-fade-in { from { opacity: 0 } to { opacity: 1 } }

.cp-panel {
  width: min(640px, 90vw);
  background: #1e293b;
  border: 1px solid #334155;
  border-radius: 10px;
  box-shadow: 0 24px 64px rgba(0, 0, 0, .7);
  overflow: hidden;
  animation: cp-slide-in .12s ease-out;
}
@keyframes cp-slide-in { from { transform: translateY(-10px); opacity: 0 } to { transform: none; opacity: 1 } }

.cp-search-wrap {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 14px;
  border-bottom: 1px solid #334155;
}
.cp-search-icon { font-size: 1rem; color: #6366f1; flex-shrink: 0; font-weight: 700; }
.cp-input {
  flex: 1; background: none; border: none; outline: none;
  font-size: .9rem; color: #f1f5f9; caret-color: #6366f1;
}
.cp-input::placeholder { color: #475569; }
.cp-esc-hint {
  flex-shrink: 0; padding: 1px 5px; font-size: .62rem;
  border: 1px solid #334155; border-radius: 3px;
  background: #0f172a; color: #475569; cursor: pointer;
  font-family: inherit; line-height: 1.6;
}
.cp-esc-hint:hover { border-color: #6366f1; color: #94a3b8; }

.cp-results { max-height: 50vh; overflow-y: auto; padding: 4px 0; }

.cp-empty { padding: 20px; text-align: center; color: #475569; font-size: .78rem; }

.cp-divider {
  padding: 2px 14px; font-size: .58rem; color: #475569; text-transform: uppercase;
  letter-spacing: .6px; background: rgba(15,23,42,.5); border-top: 1px solid rgba(51,65,85,.4);
  margin-top: 2px;
}

.cp-row {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 14px; cursor: pointer;
  transition: background .08s;
}
.cp-row--active { background: rgba(99, 102, 241, .12); }
.cp-row:hover { background: rgba(99, 102, 241, .08); }

.cp-row--action { padding: 7px 14px; }
.cp-action-icon { width: 20px; flex-shrink: 0; font-size: .85rem; text-align: center; }
.cp-action-label { font-size: .8rem; color: #f1f5f9; font-weight: 500; flex-shrink: 0; }
.cp-action-desc { font-size: .68rem; color: #64748b; margin-left: 8px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.cp-rank {
  width: 28px; flex-shrink: 0;
  font-size: .68rem; color: #64748b; text-align: right;
}
.cp-name {
  flex: 1; font-size: .78rem; color: #f1f5f9;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.cp-meta { display: flex; align-items: center; gap: 5px; flex-shrink: 0; }
.cp-score { font-size: .72rem; font-weight: 700; color: #818cf8; min-width: 24px; text-align: right; }
.cp-dur { font-size: .62rem; color: #64748b; }

.cp-footer {
  display: flex; align-items: center; gap: 10px;
  padding: 6px 14px;
  border-top: 1px solid #1e293b;
  background: #0f172a;
  font-size: .58rem; color: #475569;
}
.cp-footer kbd {
  display: inline-block; padding: 0 3px; margin: 0 1px;
  border: 1px solid #334155; border-radius: 2px;
  background: #1e293b; color: #64748b;
  font-family: inherit; font-size: .55rem; line-height: 1.5;
}
.cp-footer-hint { color: #334155; }
.cp-footer-count { margin-left: auto; }
</style>
