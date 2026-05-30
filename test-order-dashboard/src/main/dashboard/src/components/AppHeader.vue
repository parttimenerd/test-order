<script setup lang="ts">
import { inject, computed, ref } from 'vue'
import type { DashboardState } from '../composables/useDashboard'
import { sn, fmtDur } from '../utils'

const d = inject<DashboardState>('dashboard')!
const openPalette = inject<() => void>('openPalette', () => {})

const nameMode = computed(() => d.nameMode.value)
// Label shows what the button currently does (current mode indicator), title explains the action
const nameModeLabel = computed(() => nameMode.value === 'short' ? 'abc' : nameMode.value === 'strip' ? 'pkg…' : 'FQCN')
const nameModeTitle = computed(() => ({
  short:  'Names: abbreviated (e.g. c.m.u.ValidatorTest) — click to strip common prefix',
  strip:  'Names: common prefix stripped (unique part only) — click to show full names',
  full:   'Names: fully qualified — click to abbreviate',
})[nameMode.value])
function cycleNameMode() {
  d.nameMode.value = nameMode.value === 'short' ? 'strip' : nameMode.value === 'strip' ? 'full' : 'short'
}

const helpOpen = ref(false)

const generatedAge = computed(() => {
  if (!d.dd.project.generated) return ''
  const diff = Date.now() - new Date(d.dd.project.generated).getTime()
  const mins = Math.floor(diff / 60000)
  const hrs = Math.floor(mins / 60)
  const days = Math.floor(hrs / 24)
  if (days > 0) return `${days}d ago`
  if (hrs > 0) return `${hrs}h ago`
  if (mins > 0) return `${mins}m ago`
  return 'just now'
})
</script>

<template>
  <header class="app-header">
    <!-- Project name - prominent -->
    <span class="app-header__project" :title="'Project: ' + d.dd.project.name">{{ d.dd.project.name }}</span>
    <span class="app-header__sep">│</span>

    <!-- Test count - clickable to go to tests tab -->
    <span
      class="app-header__chip app-header__chip--tests"
      @click="d.setTab('tests')"
      title="Total tests being prioritized — click to go to Tests tab"
    >
      <span class="app-header__chip-icon">⬡</span>
      {{ d.tests.length }} tests
    </span>

    <!-- Median duration -->
    <span
      class="app-header__chip"
      title="Median test duration (EMA-smoothed)"
    >
      <span class="app-header__chip-icon">⏱</span>
      {{ fmtDur(d.dd.medianDuration) }} median
    </span>

    <!-- Run history count - clickable to analytics -->
    <span
      v-if="d.runs.length"
      class="app-header__chip app-header__chip--clickable"
      @click="d.setTab('analytics')"
      :title="'Number of historical runs recorded — click to go to Analytics tab. Latest run: ' + (d.latestRun.value?.totalFailures ? d.latestRun.value.totalFailures + ' failures' : 'all passed')"
    >
      <span class="app-header__chip-icon">↺</span>
      {{ d.runs.length }} run{{ d.runs.length === 1 ? '' : 's' }}
      <span
        v-if="d.latestRun.value"
        class="app-header__run-status"
        :style="{ color: d.latestRun.value.totalFailures > 0 ? 'var(--red)' : 'var(--green)' }"
      >{{ d.latestRun.value.totalFailures > 0 ? '✕' : '✓' }}</span>
    </span>

    <!-- Avg APFD quick indicator -->
    <span
      v-if="d.avgApfd.value !== null"
      class="app-header__chip app-header__chip--clickable"
      :class="d.avgApfd.value >= 0.7 ? 'app-header__chip--apfd-good' : d.avgApfd.value >= 0.5 ? 'app-header__chip--apfd-mid' : 'app-header__chip--apfd-bad'"
      @click="d.setTab('analytics')"
      :title="'Average APFD across ' + d.runs.length + ' runs. Higher = failures detected earlier. 50% = random. Click to view Analytics.'"
    >
      <span class="app-header__chip-icon">◈</span>
      APFD {{ (d.avgApfd.value * 100).toFixed(0) }}%
    </span>

    <!-- Changed classes - expandable -->
    <span
      v-if="d.dd.changedClasses.length"
      class="app-header__chip app-header__chip--changed"
      @click="d.showChangedPanel.value = !d.showChangedPanel.value"
      :title="'Click to ' + (d.showChangedPanel.value ? 'hide' : 'show') + ' ' + d.dd.changedClasses.length + ' changed source classes'"
    >
      <span class="app-header__chip-icon">✎</span>
      {{ d.dd.changedClasses.length }} changed{{ d.dd.changedTestClasses.length ? ' + ' + d.dd.changedTestClasses.length + ' tests' : '' }}
      <span style="font-size:.65rem;opacity:.7">{{ d.showChangedPanel.value ? '▲' : '▼' }}</span>
    </span>
    <span
      v-else
      class="app-header__chip app-header__chip--no-change"
      title="No source classes detected as changed in this run"
    >
      <span class="app-header__chip-icon">✓</span>
      no changes
    </span>

    <!-- Coverage link - if available -->
    <span
      v-if="d.hasCoverage"
      class="app-header__chip app-header__chip--clickable"
      @click="d.setTab('analytics')"
      title="Coverage tracking available — click to view in Analytics tab"
    >
      <span class="app-header__chip-icon">◉</span>
      {{ d.dd.coverage?.totalSourceClasses }} src classes
    </span>

    <!-- Spacer -->
    <span style="margin-left:auto"></span>

    <!-- Generated time -->
    <span
      class="app-header__meta"
      :title="'Generated: ' + d.dd.project.generated + '\nIndex: ' + d.dd.project.indexFilePath + '\nState: ' + d.dd.project.stateFilePath"
    >
      {{ generatedAge }} · v{{ d.dd.project.pluginVersion }}
    </span>

    <!-- Command palette trigger -->
    <button
      class="app-header__palette-btn"
      @click="openPalette()"
      title="Command palette — search tests, jump to tab, apply filters (⌘K / Ctrl+K)"
    ><kbd>⌘K</kbd> Jump to…</button>

    <!-- Name mode cycle toggle -->
    <button
      class="app-header__toggle"
      @click="cycleNameMode()"
      :title="nameModeTitle"
    >{{ nameModeLabel }}</button>

    <!-- Help button -->
    <button class="app-header__help-btn" @click="helpOpen = true" title="Quick reference — scores, shortcuts, APFD">?</button>
  </header>

  <!-- Changed classes expandable panel -->
  <div v-if="d.showChangedPanel.value && d.dd.changedClasses.length" class="changed-panel">
    <div style="font-size:.6rem;color:var(--text-muted);margin-bottom:4px">{{ d.dd.changedClasses.length }} changed source class{{ d.dd.changedClasses.length === 1 ? '' : 'es' }}:</div>
    <div style="display:flex;flex-wrap:wrap;gap:4px">
      <span v-for="c in d.dd.changedClasses" :key="c" class="changed-panel__tag" :title="c">{{ sn(c) }}</span>
    </div>
    <div v-if="d.dd.changedTestClasses.length" style="margin-top:6px">
      <div style="font-size:.6rem;color:var(--text-muted);margin-bottom:4px">{{ d.dd.changedTestClasses.length }} changed test class{{ d.dd.changedTestClasses.length === 1 ? '' : 'es' }}:</div>
      <div style="display:flex;flex-wrap:wrap;gap:4px">
        <span v-for="c in d.dd.changedTestClasses" :key="c" class="changed-panel__tag changed-panel__tag--test" :title="c">{{ sn(c) }}</span>
      </div>
    </div>
  </div>

  <!-- Help modal -->
  <Teleport to="body">
    <div v-if="helpOpen" class="help-overlay" @click.self="helpOpen = false">
      <div class="help-modal" role="dialog" aria-modal="true" aria-label="Quick reference">
        <header class="help-modal__header">
          <h2 class="help-modal__title">Quick Reference</h2>
          <button class="help-modal__close" @click="helpOpen = false" aria-label="Close">×</button>
        </header>
        <div class="help-modal__body">
          <section class="help-section">
            <h3 class="help-section__title">📊 Score</h3>
            <p>Each test gets a <strong>priority score</strong> combining: fail history, dep overlap with changed classes, speed, static-field overlap, and coverage set-cover bonus. Higher score = run earlier. Click any score to see the full breakdown.</p>
          </section>
          <section class="help-section">
            <h3 class="help-section__title">📈 APFD</h3>
            <p><strong>Average Percentage of Faults Detected</strong> — measures how early failing tests appear in the run order. 100% = all failures detected in first test. 50% = random. Higher is better.</p>
          </section>
          <section class="help-section">
            <h3 class="help-section__title">🔍 Filters</h3>
            <p>Use the chip buttons below the stats (or sidebar chips) to filter by: <strong>failing</strong> (fail history), <strong>flaky</strong> (intermittent), <strong>changed</strong> (modified source), <strong>new</strong> (first run), <strong>slow/fast</strong> (duration). Duration histogram bars are also clickable.</p>
          </section>
          <section class="help-section">
            <h3 class="help-section__title">⚖ Weights</h3>
            <p>The <strong>Weights tab</strong> lets you adjust how much each score factor contributes. Move sliders and the <em>Sim rank</em> column shows the new order instantly — no re-run needed.</p>
          </section>
          <section class="help-section">
            <h3 class="help-section__title">⌨ Keyboard shortcuts</h3>
            <div class="help-keys">
              <span><kbd>j</kbd><kbd>k</kbd> or <kbd>↑</kbd><kbd>↓</kbd></span><span>Navigate tests</span>
              <span><kbd>⏎</kbd></span><span>Select focused test</span>
              <span><kbd>←</kbd><kbd>→</kbd></span><span>Prev / next test in detail view (Tests tab) or run (Analytics tab)</span>
              <span><kbd>/</kbd></span><span>Focus search box</span>
              <span><kbd>⌘K</kbd></span><span>Command palette (search + actions)</span>
              <span><kbd>f</kbd></span><span>Toggle failing filter</span>
              <span><kbd>y</kbd></span><span>Toggle flaky filter</span>
              <span><kbd>c</kbd></span><span>Toggle changed filter</span>
              <span><kbd>z</kbd></span><span>Focus mode — hide sidebar for full-width detail (when test is selected)</span>
              <span><kbd>b</kbd></span><span>Blame mode — highlight tests linked to changed source classes (when changes exist)</span>
              <span><kbd>1</kbd><kbd>2</kbd><kbd>3</kbd><kbd>4</kbd></span><span>Switch tabs</span>
              <span><kbd>Esc</kbd></span><span>Clear selection / filter / close</span>
            </div>
          </section>
          <section class="help-section">
            <h3 class="help-section__title">✨ Features</h3>
            <ul style="margin:0;padding-left:16px;line-height:1.7;font-size:.78rem;color:var(--text-sec)">
              <li><strong style="color:var(--text)">Watchlist</strong> — pin up to 10 tests to the top of the sidebar by clicking the ☆ star on any test row. Persisted across sessions.</li>
              <li><strong style="color:var(--text)">Focus mode</strong> — press <kbd style="font-size:.65rem;border:1px solid var(--border);border-radius:2px;padding:0 3px;background:var(--bg-base)">z</kbd> or click ⊡ in the detail header to collapse the sidebar for distraction-free test inspection.</li>
              <li><strong style="color:var(--text)">Minimap</strong> — the thin color strip to the right of the Tests overview table is a scroll navigator. Click to jump, colors encode test status (red=fail, green=new, yellow=changed).</li>
              <li><strong style="color:var(--text)">Sensitivity curves</strong> — in the Weights tab, selecting a test reveals SVG graphs showing how each weight affects that test's rank across its full range.</li>
            </ul>
          </section>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.app-header {
  background: var(--bg-card); border-bottom: 1px solid var(--border);
  padding: 5px 14px; display: flex; align-items: center; gap: 6px;
  flex-shrink: 0; flex-wrap: wrap;
}
.app-header__project {
  font-weight: 700; font-size: .95rem; color: var(--accent-light);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 200px;
}
.app-header__sep { color: var(--border); font-size: .8rem; }
.app-header__chip {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: .72rem; color: var(--text-sec);
  background: rgba(51,65,85,.4); border: 1px solid var(--border);
  border-radius: 12px; padding: 2px 8px; white-space: nowrap;
  transition: all var(--tr-fast);
}
.app-header__chip-icon { font-size: .7rem; opacity: .7; }
.app-header__chip--tests { color: var(--accent-light); border-color: rgba(99,102,241,.3); cursor: pointer; }
.app-header__chip--tests:hover { background: rgba(99,102,241,.15); border-color: var(--accent); }
.app-header__chip--clickable { cursor: pointer; }
.app-header__chip--clickable:hover { color: var(--text); border-color: rgba(99,102,241,.4); background: rgba(99,102,241,.1); }
.app-header__chip--changed { color: var(--yellow); border-color: rgba(251,191,36,.3); cursor: pointer; }
.app-header__chip--changed:hover { background: rgba(251,191,36,.1); border-color: rgba(251,191,36,.5); }
.app-header__chip--no-change { color: var(--text-muted); border-color: transparent; background: transparent; }
.app-header__meta { font-size: .65rem; color: var(--text-muted); cursor: help; white-space: nowrap; }
.app-header__toggle {
  font-size: .62rem; padding: 1px 6px; border-radius: 10px;
  border: 1px solid var(--border); background: var(--bg-base);
  color: var(--text-sec); cursor: pointer; white-space: nowrap;
  transition: all var(--tr-fast);
}
.app-header__toggle:hover { border-color: var(--accent); color: var(--accent-light); }
.app-header__palette-btn {
  font-size: .62rem; padding: 2px 8px; border-radius: 10px;
  border: 1px solid var(--border); background: var(--bg-base);
  color: var(--text-muted); cursor: pointer; white-space: nowrap;
  display: inline-flex; align-items: center; gap: 4px;
  transition: all var(--tr-fast);
}
.app-header__palette-btn:hover { border-color: var(--accent); color: var(--accent-light); background: var(--accent-bg); }
.app-header__palette-btn kbd { font-size: .58rem; font-family: inherit; }
.app-header__run-status { font-weight: 700; font-size: .75rem; }
.app-header__chip--apfd-good { color: var(--green); border-color: rgba(74,222,128,.3); }
.app-header__chip--apfd-good:hover { background: rgba(74,222,128,.1); border-color: rgba(74,222,128,.5); }
.app-header__chip--apfd-mid { color: var(--yellow); border-color: rgba(251,191,36,.3); }
.app-header__chip--apfd-mid:hover { background: rgba(251,191,36,.1); border-color: rgba(251,191,36,.5); }
.app-header__chip--apfd-bad { color: var(--red); border-color: rgba(248,113,113,.3); }
.app-header__chip--apfd-bad:hover { background: rgba(248,113,113,.1); border-color: rgba(248,113,113,.5); }

.changed-panel {
  background: rgba(30,41,59,.95); border-bottom: 1px solid var(--border);
  padding: 6px 14px; max-height: 140px; overflow-y: auto;
}
.changed-panel__tag {
  font-size: .65rem; padding: 1px 6px;
  background: rgba(234,179,8,.12); color: var(--yellow);
  border-radius: 3px; white-space: nowrap; cursor: default;
}
.changed-panel__tag--test { background: rgba(234,179,8,.25); }

/* Help button */
.app-header__help-btn {
  width: 22px; height: 22px; border-radius: 50%;
  border: 1px solid var(--border); background: var(--bg-base);
  color: var(--text-muted); cursor: pointer; font-size: .72rem; font-weight: 700;
  display: inline-flex; align-items: center; justify-content: center;
  flex-shrink: 0; transition: all var(--tr-fast);
}
.app-header__help-btn:hover { border-color: var(--accent); color: var(--accent-light); background: var(--accent-bg); }

/* Help modal */
.help-overlay {
  position: fixed; inset: 0; z-index: 9000;
  background: rgba(0,0,0,.6); display: flex; align-items: center; justify-content: center;
  padding: 20px; backdrop-filter: blur(2px);
  animation: help-fade .12s ease-out;
}
@keyframes help-fade { from { opacity: 0 } to { opacity: 1 } }
.help-modal {
  width: min(600px, 100%); max-height: 80vh; display: flex; flex-direction: column;
  background: #111827; border: 1px solid #334155; border-radius: 10px;
  box-shadow: 0 20px 60px rgba(0,0,0,.6); animation: help-slide .14s ease-out;
}
@keyframes help-slide { from { transform: translateY(-12px); opacity: 0 } to { transform: none; opacity: 1 } }
.help-modal__header {
  display: flex; align-items: center; padding: 12px 16px;
  border-bottom: 1px solid rgba(51,65,85,.6); flex-shrink: 0;
}
.help-modal__title { margin: 0; font-size: .9rem; color: var(--text); font-weight: 700; }
.help-modal__close {
  margin-left: auto; border: 1px solid var(--border); background: var(--bg-base);
  color: var(--text-sec); width: 26px; height: 26px; border-radius: 6px;
  cursor: pointer; font-size: 1rem; line-height: 1; transition: all var(--tr-fast);
}
.help-modal__close:hover { color: var(--text); border-color: var(--accent); }
.help-modal__body { overflow-y: auto; padding: 14px 16px; display: flex; flex-direction: column; gap: 14px; }
.help-section h3.help-section__title { font-size: .78rem; font-weight: 700; color: var(--accent-light); margin: 0 0 5px; }
.help-section p { font-size: .72rem; color: var(--text-sec); line-height: 1.55; margin: 0; }
.help-section strong { color: var(--text); }
.help-section em { color: var(--yellow); font-style: normal; }
.help-keys {
  display: grid; grid-template-columns: auto 1fr; gap: 4px 12px; font-size: .7rem;
}
.help-keys span:nth-child(odd) { color: var(--text); display: flex; gap: 3px; align-items: center; justify-content: flex-end; white-space: nowrap; }
.help-keys span:nth-child(even) { color: var(--text-sec); }
.help-keys kbd {
  display: inline-block; padding: 1px 5px; border: 1px solid var(--border);
  border-radius: 3px; background: var(--bg-card); font-family: inherit;
  font-size: .65rem; color: var(--text-sec); line-height: 1.5;
}
</style>
