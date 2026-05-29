<script setup lang="ts">
import { inject, computed, ref, onMounted, type Ref } from 'vue'
import type { DashboardState } from '../composables/useDashboard'
import type { TestHoverState } from '../composables/useTestHover'
import { sn, computeScore } from '../utils'
import type { ScoringWeights } from '../types'

const d = inject<DashboardState>('dashboard')!
const shortNames = inject<Ref<boolean>>('shortNames', { value: true } as any)
const testHover = inject<TestHoverState>('testHover')!
function dn(name: string): string { return shortNames.value ? sn(name) : name }

const showChangedOnly = ref(false)

function origTooltip(name: string): string {
  return d.getScoreBreakdown(name, 'orig') + '\n\nClick to open detailed score modal'
}

function simTooltip(name: string): string {
  return d.getScoreBreakdown(name, 'sim') + '\n\nClick to open detailed score modal'
}

const TOP_MOVERS_N = 5

const topMovers = computed(() => {
  const changed = d.simResults.value.filter(r => r.delta !== 0)
  if (!changed.length) return null
  const moversUp = [...changed].filter(r => r.delta < 0).sort((a, b) => a.delta - b.delta).slice(0, TOP_MOVERS_N)
  const moversDown = [...changed].filter(r => r.delta > 0).sort((a, b) => b.delta - a.delta).slice(0, TOP_MOVERS_N)
  return { up: moversUp, down: moversDown, total: changed.length }
})

const apfdDelta = computed(() => {
  if (d.simApfd.value === null || d.avgApfd.value === null) return null
  return d.simApfd.value - d.avgApfd.value
})

const changedWeights = computed(() => {
  return d.dd.weightDefs.filter(wd => d.lw[wd.name] !== wd.defaultValue)
})

// Share weights via URL hash — encode/decode as #weights=key:val,key:val,...
const showToast = inject<(msg: string) => void>('showToast')!
const shareCopied = ref(false)

function encodeWeights(): string {
  return d.dd.weightDefs
    .map(wd => `${wd.name}:${d.lw[wd.name]}`)
    .join(',')
}

function shareWeights() {
  const encoded = encodeWeights()
  const url = window.location.href.split('#')[0] + '#tab=weights&weights=' + encoded
  navigator.clipboard?.writeText(url)
  shareCopied.value = true
  showToast('Share URL copied to clipboard!')
  setTimeout(() => { shareCopied.value = false }, 2000)
}

function loadWeightsFromHash() {
  const hash = window.location.hash
  const match = hash.match(/(?:^#|&)weights=([^&]+)/)
  if (!match) return
  const parts = match[1].split(',')
  let loaded = 0
  for (const part of parts) {
    const [name, val] = part.split(':')
    const wd = d.dd.weightDefs.find(w => w.name === name)
    if (wd && val !== undefined) {
      const n = parseInt(val, 10)
      if (!isNaN(n)) { d.lw[name] = Math.max(wd.min, Math.min(wd.max, n)); loaded++ }
    }
  }
  if (loaded > 0) showToast(`Loaded ${loaded} weights from shared URL`)
}

onMounted(loadWeightsFromHash)

const maxAbsDelta = computed(() => {
  const deltas = d.simResults.value.map(r => Math.abs(r.delta)).filter(v => v > 0)
  return deltas.length ? Math.max(...deltas) : 1
})

const displayedResults = computed(() => {
  if (!showChangedOnly.value) return d.simResults.value
  return d.simResults.value.filter(r => r.delta !== 0)
})

const WEIGHT_DESC: Record<string, string> = {
  newTest:          'Boost for test classes that did not exist in the previous run — ensures new tests run early',
  changedTest:      'Boost for test classes whose source was modified since the last run',
  maxFailure:       'Boost scaled by historical failure rate — tests that fail often get higher priority',
  speed:            'Bonus for fast tests — short-running tests receive a higher raw bonus',
  speedPenalty:     'Penalty for slow tests — long-running tests are pushed later in the order',
  depOverlap:       'Boost for tests whose class-level dependencies overlap with changed classes',
  changeComplexity: 'Scales the dep-overlap bonus by the number of changed dependencies touched',
  staticFieldBonus: 'Boost for tests that read static fields of changed classes',
  coverageBonus:    'Boost based on line coverage of changed classes (requires JaCoCo coverage data)',
  killRateBonus:    'Bonus scaled by mutation kill rate — tests that kill more mutants get higher priority (requires mutation data)',
}

const PRESETS: { label: string; desc: string; values: Record<string, number> }[] = [
  {
    label: 'Fail-focused',
    desc: 'Max weight on failure history and dep overlap — prioritises tests that historically fail and touch changed code',
    values: { maxFailure: 100, depOverlap: 60, changeComplexity: 30, staticFieldBonus: 20, coverageBonus: 20, newTest: 20, changedTest: 20, speed: 5, speedPenalty: 5 },
  },
  {
    label: 'Change-focused',
    desc: 'Heavy weight on changed/new tests and dep overlap — best when you want to catch regressions from the current diff',
    values: { changedTest: 80, newTest: 60, depOverlap: 80, changeComplexity: 40, staticFieldBonus: 30, coverageBonus: 30, maxFailure: 20, speed: 5, speedPenalty: 5 },
  },
  {
    label: 'Speed-smart',
    desc: 'Maximise speed signal — fast tests run first, slow tests pushed to end, neutral on history and deps',
    values: { speed: 80, speedPenalty: 80, maxFailure: 20, depOverlap: 20, changeComplexity: 10, newTest: 20, changedTest: 20, staticFieldBonus: 5, coverageBonus: 5 },
  },
]

function applyPreset(preset: typeof PRESETS[0]) {
  for (const wd of d.dd.weightDefs) {
    if (preset.values[wd.name] !== undefined) {
      d.lw[wd.name] = Math.max(wd.min, Math.min(wd.max, preset.values[wd.name]))
    }
  }
}

// Optimize: snapshot weights before run, enable revert
const showOptHelp = ref(false)
const preOptWeights = ref<Record<string, number> | null>(null)

async function runOptimize() {
  preOptWeights.value = { ...d.lw }
  await d.optimizeWeights()
}

function revertOptimize() {
  if (preOptWeights.value) {
    for (const key of Object.keys(preOptWeights.value)) {
      d.lw[key] = preOptWeights.value[key]
    }
  }
  d.optimizeResult.value = null
  preOptWeights.value = null
}

// Sensitivity curves: for the currently selected test, sweep each weight and track rank
const SENS_STEPS = 20
interface SensCurve {
  weightName: string
  label: string
  min: number
  max: number
  current: number
  ranks: number[]        // rank at each step (index 0 = min, N-1 = max)
  scores: number[]       // score at each step
  minRank: number
  maxRank: number
  currentStep: number    // which step index corresponds to current value
  isFlat: boolean        // rank doesn't change across sweep
}

const sensitivityCurves = computed((): SensCurve[] => {
  const t = d.selectedTest.value
  if (!t) return []
  const tests = d.tests
  if (!tests.length) return []

  return d.dd.weightDefs.map(wd => {
    const steps: number[] = []
    const step = (wd.max - wd.min) / (SENS_STEPS - 1)
    for (let i = 0; i < SENS_STEPS; i++) {
      steps.push(Math.round(wd.min + step * i))
    }

    const ranks: number[] = []
    const scores: number[] = []
    const baseW = { ...d.lw } as Record<string, number>

    for (const val of steps) {
      const w = { ...baseW, [wd.name]: val } as unknown as ScoringWeights
      const tScore = computeScore(t, w, d.origSCB)
      // Count how many tests score higher (rank = 1 + count of tests with higher score)
      let rank = 1
      for (const other of tests) {
        if (other.name === t.name) continue
        if (computeScore(other, w, d.origSCB) > tScore) rank++
      }
      ranks.push(rank)
      scores.push(tScore)
    }

    const currentStep = steps.findIndex(v => v === Math.round(d.lw[wd.name] as number))
    const safeCurrentStep = currentStep >= 0 ? currentStep : Math.round((SENS_STEPS - 1) * ((d.lw[wd.name] as number - wd.min) / (wd.max - wd.min)))

    return {
      weightName: wd.name,
      label: wd.name.replace(/([A-Z])/g, ' $1').replace(/^./, s => s.toUpperCase()),
      min: wd.min,
      max: wd.max,
      current: d.lw[wd.name] as number,
      ranks,
      scores,
      minRank: Math.min(...ranks),
      maxRank: Math.max(...ranks),
      currentStep: safeCurrentStep,
      isFlat: new Set(ranks).size <= 1,
    }
  }).filter(c => !c.isFlat)  // only show curves where weight actually affects rank
})

// SVG path helper: encode ranks as an SVG polyline
function sensSvgPath(curve: SensCurve, W: number, H: number): string {
  const n = curve.ranks.length
  const rankSpan = curve.maxRank - curve.minRank || 1
  return curve.ranks.map((r, i) => {
    const x = (i / (n - 1)) * W
    const y = H - ((r - curve.minRank) / rankSpan) * H  // lower rank = higher y position = better
    return `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`
  }).join(' ')
}
</script>

<template>
  <div v-if="d.activeTab.value === 'weights'">
    <div style="display:flex;align-items:center;gap:10px;margin-bottom:6px;flex-wrap:wrap">
      <h3 style="font-size:.82rem;color:var(--text-sec)">Weight Sliders</h3>
      <!-- APFD comparison: orig vs sim -->
      <div v-if="d.avgApfd.value !== null || d.simApfd.value !== null" class="kpi weights__apfd-kpi" title="Comparison of original historical APFD vs simulated APFD with current weights. Higher is better; 50% = random ordering.">
        <div style="font-size:.52rem;color:var(--text-sec);margin-bottom:2px;text-transform:uppercase;letter-spacing:.3px">APFD</div>
        <div style="display:flex;align-items:center;gap:8px">
          <span v-if="d.avgApfd.value !== null" style="font-size:.78rem;color:var(--text-muted)">orig {{ (d.avgApfd.value * 100).toFixed(1) }}%</span>
          <span v-if="d.simApfd.value !== null && d.avgApfd.value !== null" style="color:var(--border)">→</span>
          <span v-if="d.simApfd.value !== null" style="font-size:.95rem;font-weight:700" :style="{ color: d.simApfd.value >= 0.7 ? 'var(--green)' : d.simApfd.value >= 0.5 ? 'var(--yellow)' : 'var(--red)' }">sim {{ (d.simApfd.value * 100).toFixed(1) }}%</span>
          <span v-if="apfdDelta !== null" style="font-size:.72rem;font-weight:700" :style="{ color: apfdDelta > 0.005 ? 'var(--green)' : apfdDelta < -0.005 ? 'var(--red)' : 'var(--text-muted)' }">
            {{ apfdDelta > 0.005 ? '▲+' : apfdDelta < -0.005 ? '▼' : '=' }}{{ apfdDelta !== 0 ? (apfdDelta * 100).toFixed(1) + '%' : '' }}
          </span>
        </div>
      </div>
      <!-- Changed weights summary -->
      <div v-if="changedWeights.length > 0" class="weights__changed-summary" :title="changedWeights.map(w => w.name + ': ' + d.lw[w.name] + ' (default: ' + w.defaultValue + ')').join(', ')">
        <span style="font-size:.6rem;color:var(--yellow)">✎ {{ changedWeights.length }} weight{{ changedWeights.length > 1 ? 's' : '' }} modified:</span>
        <span v-for="w in changedWeights" :key="w.name" style="font-size:.6rem;color:var(--text-sec)">
          {{ w.name }}: <strong style="color:var(--accent-light)">{{ d.lw[w.name] }}</strong>
        </span>
      </div>
      <button @click="d.resetWeights()" style="margin-left:auto;padding:3px 10px;font-size:.7rem;background:var(--border);color:var(--text);border:1px solid var(--text-muted);border-radius:4px;cursor:pointer" :title="changedWeights.length > 0 ? 'Reset all weights to defaults (' + changedWeights.length + ' modified)' : 'All weights are at defaults'">Reset to defaults</button>
      <button
        @click="shareWeights()"
        class="weights__share-btn"
        :class="{ 'weights__share-btn--copied': shareCopied }"
        title="Copy a shareable URL with the current weight configuration to clipboard"
      >{{ shareCopied ? '✓ Copied!' : '⤴ Share' }}</button>
      <button
        @click="d.serverConnected.value ? runOptimize() : (showOptHelp = !showOptHelp)"
        :disabled="d.optimizing.value"
        class="weights__optimize-btn"
        :class="{ 'weights__optimize-btn--offline': !d.serverConnected.value }"
        :title="d.serverConnected.value
          ? (d.optimizing.value ? 'Running genetic algorithm…' : 'Run GA to find optimal weights')
          : 'Live server required — click for instructions'"
      >
        <span v-if="d.optimizing.value" class="weights__spin">⟳</span>
        {{ d.optimizing.value ? 'Optimizing…' : '⚡ Optimize' }}
        <span v-if="!d.serverConnected.value" style="font-size:.6rem;opacity:.6"> (offline)</span>
      </button>
    </div>
    <!-- Offline help panel -->
    <div v-if="showOptHelp && !d.serverConnected.value" class="weights__help-panel">
      <strong>Live server required</strong>
      <p>The Optimize feature runs a genetic algorithm against recorded run history to find weights that maximise APFDc.</p>
      <p>To use it, run the dashboard with a live server:</p>
      <code>mvn test-order:serve</code>
      <p style="margin-top:4px">Then open the URL printed in the Maven output (e.g. <code>http://localhost:8080</code>).</p>
    </div>
    <!-- Empty state: no run history -->
    <div v-if="!d.runs.length" class="weights__empty-state">
      <div style="font-size:1.2rem;margin-bottom:6px">📊</div>
      <div style="font-weight:700;margin-bottom:4px">No run history yet</div>
      <div style="color:var(--text-muted);font-size:.72rem;margin-bottom:8px">
        APFD simulation and rank comparison require at least one recorded run with failures.
      </div>
      <code style="font-size:.7rem;background:var(--bg-base);padding:4px 8px;border-radius:4px">mvn test</code>
    </div>
    <div v-if="d.optimizeError.value" class="weights__opt-msg weights__opt-msg--err">{{ d.optimizeError.value }}</div>
    <div v-if="d.optimizeResult.value && !d.optimizeError.value" class="weights__opt-result">
      <div class="weights__opt-result-header">
        ⚡ Optimized{{ d.optimizeResult.value.overfit ? ' (overfit → defaults)' : '' }}
        — train APFDc: {{ (d.optimizeResult.value.trainScore * 100).toFixed(1) }}%
        <span v-if="d.optimizeResult.value.folds > 0" style="color:var(--text-muted)">  · validation: {{ (d.optimizeResult.value.validationScore * 100).toFixed(1) }}%</span>
        <span v-if="d.optimizeResult.value.overfit" style="color:var(--orange)"> (overfit → defaults used)</span>
        <button @click="revertOptimize()" class="weights__revert-btn">↩ Revert</button>
      </div>
      <div v-if="preOptWeights" class="weights__opt-diffs">
        <template v-for="wd in d.dd.weightDefs" :key="wd.name">
          <div v-if="d.optimizeResult.value.weights[wd.name] !== undefined && d.optimizeResult.value.weights[wd.name] !== preOptWeights![wd.name]" class="weights__opt-diff-row">
            <span class="weights__opt-diff-name">{{ wd.name }}</span>
            <span style="color:var(--text-muted)">{{ preOptWeights![wd.name] }}</span>
            <span style="color:var(--text-sec)">→</span>
            <span :style="{ color: d.optimizeResult.value.weights[wd.name] > preOptWeights![wd.name] ? 'var(--green)' : 'var(--red)' }">
              {{ d.optimizeResult.value.weights[wd.name] }}
            </span>
          </div>
        </template>
      </div>
    </div>
    <!-- Preset buttons -->
    <div style="display:flex;align-items:center;gap:6px;margin-bottom:8px;flex-wrap:wrap">
      <span style="font-size:.6rem;color:var(--text-muted);flex-shrink:0">Presets:</span>
      <button
        v-for="preset in PRESETS"
        :key="preset.label"
        class="weights__preset-btn"
        :title="preset.desc"
        @click="applyPreset(preset)"
      >{{ preset.label }}</button>
    </div>
    <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(320px,1fr));gap:5px;margin-bottom:14px">
      <div v-for="wd in d.dd.weightDefs" :key="wd.name" class="weights__slider-row">
        <div class="weights__slider-label-wrap">
          <span class="weights__slider-label">{{ wd.name }}</span>
        </div>
        <span v-if="WEIGHT_DESC[wd.name]" class="weights__slider-info" :title="WEIGHT_DESC[wd.name]">?</span>
        <div class="weights__range-wrap">
          <input type="range" :min="wd.min" :max="wd.max" step="1" v-model.number="d.lw[wd.name]" class="weights__range">
          <div
            class="weights__default-tick"
            :style="{ left: ((wd.defaultValue - wd.min) / (wd.max - wd.min) * 100) + '%' }"
            :title="`Default: ${wd.defaultValue}`"
          ></div>
        </div>
        <span class="weights__slider-val">{{ d.lw[wd.name] }}</span>
        <button
          v-if="d.lw[wd.name] !== wd.defaultValue"
          class="weights__slider-reset"
          @click.stop="d.lw[wd.name] = wd.defaultValue"
          :title="`Reset ${wd.name} to default (${wd.defaultValue})`"
        >↺</button>
        <span v-else class="weights__slider-default">({{ wd.defaultValue }})</span>
      </div>
    </div>

    <!-- Top movers summary -->
    <div v-if="topMovers" class="weights__movers">
      <div class="weights__movers-title">
        Top rank changes
        <span style="font-size:.58rem;color:var(--text-muted);font-weight:400;margin-left:4px">{{ topMovers.total }} test{{ topMovers.total > 1 ? 's' : '' }} affected</span>
      </div>
      <div class="weights__movers-cols">
        <div v-if="topMovers.up.length" class="weights__movers-col">
          <div class="weights__movers-col-hdr" style="color:var(--green)">↑ Moved earlier</div>
          <div
            v-for="r in topMovers.up" :key="r.name"
            class="weights__mover-row"
            :title="r.name + ' — rank ' + r.origRank + ' → ' + r.simRank"
            @click="d.navigateToTestFromCov(r.name)"
          >
            <span class="weights__mover-delta" style="color:var(--green)">↑{{ Math.abs(r.delta) }}</span>
            <span class="weights__mover-name" :title="r.name"
              @mouseenter="testHover.show(r.name, $event)"
              @mousemove="testHover.move($event)"
              @mouseleave="testHover.hide()"
            >{{ dn(r.name) }}</span>
            <span class="weights__mover-pos">#{{ r.origRank }}→#{{ r.simRank }}</span>
          </div>
        </div>
        <div v-if="topMovers.down.length" class="weights__movers-col">
          <div class="weights__movers-col-hdr" style="color:var(--red)">↓ Moved later</div>
          <div
            v-for="r in topMovers.down" :key="r.name"
            class="weights__mover-row"
            :title="r.name + ' — rank ' + r.origRank + ' → ' + r.simRank"
            @click="d.navigateToTestFromCov(r.name)"
          >
            <span class="weights__mover-delta" style="color:var(--red)">↓{{ r.delta }}</span>
            <span class="weights__mover-name" :title="r.name"
              @mouseenter="testHover.show(r.name, $event)"
              @mousemove="testHover.move($event)"
              @mouseleave="testHover.hide()"
            >{{ dn(r.name) }}</span>
            <span class="weights__mover-pos">#{{ r.origRank }}→#{{ r.simRank }}</span>
          </div>
        </div>
      </div>
    </div>

    <div v-if="d.runs.length" style="display:flex;align-items:center;gap:8px;margin-bottom:6px;flex-wrap:wrap">
      <h3 style="font-size:.82rem;color:var(--text-sec);margin:0">Rank comparison <span style="font-size:.65rem;color:var(--text-muted);font-weight:400">— click headers to sort</span></h3>
      <button
        class="weights__toggle-btn"
        :class="{ 'weights__toggle-btn--active': showChangedOnly }"
        @click="showChangedOnly = !showChangedOnly"
        :title="showChangedOnly ? 'Showing only tests with rank changes — click to show all' : 'Click to show only tests whose rank changed'"
      >{{ showChangedOnly ? '✕ changed only (' + displayedResults.length + ')' : 'Show changed only' }}</button>
    </div>
    <div v-if="d.runs.length" style="overflow-x:auto;max-height:400px;overflow-y:auto">
      <div v-if="showChangedOnly && displayedResults.length === 0" style="padding:20px;text-align:center;color:var(--text-muted);font-size:.75rem">No rank changes — all tests have the same order with current weights.</div>
      <table v-else>
        <thead style="position:sticky;top:0;background:var(--bg-base);z-index:1">
          <tr>
            <th @click="d.simSortBy('name')" class="weights__th weights__th--left" :class="{ 'weights__th--active': d.simSortKey.value === 'name' }">
              Test<span v-if="d.simSortKey.value === 'name'">{{ d.simSortDir.value === 'asc' ? ' ↑' : ' ↓' }}</span>
            </th>
            <th @click="d.simSortBy('origRank')" class="weights__th weights__th--right" :class="{ 'weights__th--active': d.simSortKey.value === 'origRank' }">
              Orig rank<span v-if="d.simSortKey.value === 'origRank'">{{ d.simSortDir.value === 'asc' ? ' ↑' : ' ↓' }}</span>
            </th>
            <th @click="d.simSortBy('simRank')" class="weights__th weights__th--right" :class="{ 'weights__th--active': d.simSortKey.value === 'simRank' }">
              Sim rank<span v-if="d.simSortKey.value === 'simRank'">{{ d.simSortDir.value === 'asc' ? ' ↑' : ' ↓' }}</span>
            </th>
            <th @click="d.simSortBy('delta')" class="weights__th weights__th--right" :class="{ 'weights__th--active': d.simSortKey.value === 'delta' }" title="Rank shift: negative = test moves earlier (better), positive = moves later (worse)">
              Rank shift<span v-if="d.simSortKey.value === 'delta'">{{ d.simSortDir.value === 'asc' ? ' ↑' : ' ↓' }}</span>
            </th>
            <th @click="d.simSortBy('origScore')" class="weights__th weights__th--right" :class="{ 'weights__th--active': d.simSortKey.value === 'origScore' }">
              Orig score<span v-if="d.simSortKey.value === 'origScore'">{{ d.simSortDir.value === 'asc' ? ' ↑' : ' ↓' }}</span>
            </th>
            <th @click="d.simSortBy('simScore')" class="weights__th weights__th--right" :class="{ 'weights__th--active': d.simSortKey.value === 'simScore' }">
              Sim score<span v-if="d.simSortKey.value === 'simScore'">{{ d.simSortDir.value === 'asc' ? ' ↑' : ' ↓' }}</span>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in displayedResults" :key="r.name" :class="{ 'weights__row--big-delta': Math.abs(r.delta) > 5 }">
            <td class="weights__td weights__td--name weights__td--name-link" :title="r.name + ' — click to view in Tests tab'"
              @click="d.navigateToTestFromCov(r.name)"
              @mouseenter="testHover.show(r.name, $event)"
              @mousemove="testHover.move($event)"
              @mouseleave="testHover.hide()"
            >{{ dn(r.name) }}</td>
            <td class="weights__td weights__td--right weights__td--dim">{{ r.origRank }}</td>
            <td class="weights__td weights__td--right weights__td--dim">{{ r.simRank }}</td>
            <td class="weights__td weights__td--right" :title="r.delta < 0 ? 'Moves earlier (better)' : r.delta > 0 ? 'Moves later (worse)' : 'No change'">
              <div style="display:flex;align-items:center;gap:4px;justify-content:flex-end">
                <div v-if="r.delta !== 0" style="width:40px;height:5px;background:var(--border);border-radius:3px;overflow:hidden;flex-shrink:0">
                  <div :style="{ width: (Math.abs(r.delta) / maxAbsDelta * 100) + '%', height: '100%', background: r.delta < 0 ? 'var(--green)' : 'var(--red)', borderRadius: '3px' }"></div>
                </div>
                <span class="weights__td--delta" :class="{ 'weights__td--delta-up': r.delta < -5, 'weights__td--delta-down': r.delta > 5 }" :style="{ color: r.delta < 0 ? 'var(--green)' : r.delta > 0 ? 'var(--red)' : undefined }">{{ r.delta === 0 ? '–' : r.delta > 0 ? '+' + r.delta : r.delta }}</span>
              </div>
            </td>
            <td class="weights__td weights__td--right weights__td--dim weights__td--score">
              <button
                type="button"
                class="weights__score-btn"
                :title="origTooltip(r.name)"
                @click.stop="d.openScoreModal(r.name, 'orig', 'Weights Original')"
              >{{ r.origScore }}</button>
            </td>
            <td class="weights__td weights__td--right weights__td--accent weights__td--score">
              <button
                type="button"
                class="weights__score-btn"
                :title="simTooltip(r.name)"
                @click.stop="d.openScoreModal(r.name, 'sim', 'Weights Simulated')"
              >{{ r.simScore }}</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <!-- Sensitivity curves for selected test -->
    <div v-if="d.selectedTest.value && sensitivityCurves.length" style="margin-top:12px">
      <h3 style="font-size:.78rem;color:var(--text-sec);margin-bottom:6px">
        Score sensitivity for <span style="color:var(--accent-light)">{{ sn(d.selectedTest.value.name) }}</span>
        <span style="font-size:.62rem;color:var(--text-muted);font-weight:400"> — how rank changes as each weight is swept (others held fixed)</span>
      </h3>
      <div class="weights__sens-grid">
        <div v-for="c in sensitivityCurves" :key="c.weightName" class="weights__sens-card" :title="c.label + ': sweep from ' + c.min + ' to ' + c.max + '. Current=' + c.current + '. Rank range: #' + c.minRank + '–#' + c.maxRank">
          <div class="weights__sens-label">{{ c.label }}</div>
          <svg class="weights__sens-svg" viewBox="0 0 100 40" preserveAspectRatio="none">
            <!-- Y-axis guide: lower rank = top = green zone -->
            <rect x="0" y="0" width="100" height="40" fill="rgba(0,0,0,0)" />
            <!-- Rank curve: lower rank value = better = top of SVG -->
            <path :d="sensSvgPath(c, 100, 40)" fill="none" stroke="#6366f1" stroke-width="1.5" vector-effect="non-scaling-stroke"/>
            <!-- Current weight position marker -->
            <line
              :x1="(c.currentStep / (c.ranks.length - 1)) * 100"
              y1="0"
              :x2="(c.currentStep / (c.ranks.length - 1)) * 100"
              y2="40"
              stroke="rgba(251,191,36,.6)"
              stroke-width="1"
              vector-effect="non-scaling-stroke"
            />
          </svg>
          <div class="weights__sens-meta">
            <span>rank #{{ c.minRank }}</span>
            <span style="color:var(--accent-light)">now {{ c.current }}</span>
            <span>rank #{{ c.maxRank }}</span>
          </div>
          <div class="weights__sens-axis">
            <span>{{ c.min }}</span>
            <span>{{ c.max }}</span>
          </div>
        </div>
      </div>
    </div>
    <div v-else-if="d.selectedTest.value && !sensitivityCurves.length" style="margin-top:10px;font-size:.65rem;color:var(--text-muted)">
      All weights are non-sensitive for <em>{{ sn(d.selectedTest.value.name) }}</em> — rank stays constant across all weight sweeps.
    </div>
    <div v-else-if="!d.selectedTest.value" style="margin-top:10px;font-size:.65rem;color:var(--text-muted)">
      Select a test in the Tests tab to see score sensitivity curves here.
    </div>
  </div>
</template>

<style scoped>
.weights__slider-row { display: flex; align-items: center; gap: 8px; padding: 5px 8px; background: var(--bg-card); border-radius: 5px; }
.weights__slider-label-wrap { display: flex; flex-direction: column; width: 145px; flex-shrink: 0; overflow: hidden; }
.weights__slider-label { font-size: .7rem; color: var(--text-sec); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.weights__slider-desc { font-size: .58rem; color: var(--text-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.weights__range { flex: 1; accent-color: var(--accent); }
.weights__slider-val { font-size: .7rem; color: var(--accent-light); width: 24px; text-align: right; }
.weights__slider-default { font-size: .65rem; color: var(--border); width: 24px; text-align: right; }
.weights__th { padding: 4px 8px; cursor: pointer; }
.weights__th--left { text-align: left; }
.weights__th--right { text-align: right; }
.weights__th--active { color: var(--accent-light); }
.weights__td { padding: 3px 8px; }
.weights__td--right { text-align: right; }
.weights__td--dim { color: var(--text-sec); }
.weights__td--name { color: var(--text); max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.weights__td--name-link { cursor: pointer; }
.weights__td--name-link:hover { color: var(--accent-light); text-decoration: underline dotted; }
.weights__td--accent { color: var(--accent-light); }
.weights__td--delta { font-weight: 700; color: var(--text-muted); }
.weights__td--delta-up { color: var(--green); }
.weights__td--delta-down { color: var(--red); }
.weights__td--score { cursor: help; text-decoration: underline dotted; text-underline-offset: 2px; }
.weights__score-btn {
  border: none;
  background: none;
  color: inherit;
  font: inherit;
  cursor: pointer;
  padding: 0;
  text-decoration: underline dotted;
  text-underline-offset: 2px;
}
.weights__row--big-delta { background: rgba(120, 53, 15, .12); }
.weights__toggle-btn {
  padding: 2px 8px; font-size: .62rem; border-radius: 10px;
  border: 1px solid var(--border); background: none; cursor: pointer;
  color: var(--text-muted); transition: all var(--tr-fast);
}
.weights__toggle-btn:hover { color: var(--accent-light); border-color: var(--accent); }
.weights__toggle-btn--active { color: var(--accent-light); border-color: var(--accent); background: var(--accent-bg); }
.weights__optimize-btn { padding: 3px 10px; font-size: .7rem; background: var(--accent); color: #fff; border: none; border-radius: 4px; cursor: pointer; font-weight: 600; }
.weights__optimize-btn:disabled { opacity: .5; cursor: wait; }
.weights__optimize-btn:hover:not(:disabled) { filter: brightness(1.15); }
.weights__preset-btn {
  padding: 2px 9px; font-size: .65rem; border-radius: 10px;
  border: 1px solid var(--border); background: none; cursor: pointer;
  color: var(--text-sec); transition: all var(--tr-fast);
}
.weights__preset-btn:hover { color: var(--accent-light); border-color: var(--accent); background: var(--accent-bg); }
.weights__opt-msg { font-size: .65rem; padding: 4px 8px; border-radius: 4px; margin-bottom: 8px; }
.weights__opt-msg--err { background: rgba(239, 68, 68, .15); color: var(--red); }
.weights__opt-msg--ok { background: rgba(34, 197, 94, .12); color: var(--green); }
.weights__apfd-kpi { padding: 5px 12px; cursor: help; }
.weights__changed-summary { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; padding: 3px 8px; background: rgba(251,191,36,.08); border: 1px solid rgba(251,191,36,.25); border-radius: 4px; cursor: help; }

/* Top movers summary */
.weights__movers {
  margin-bottom: 12px; border: 1px solid var(--border); border-radius: 5px; overflow: hidden;
}
.weights__movers-title {
  font-size: .65rem; font-weight: 700; color: var(--text-sec);
  padding: 4px 10px; background: rgba(15,23,42,.5);
  border-bottom: 1px solid var(--border);
  text-transform: uppercase; letter-spacing: .3px;
}
.weights__movers-cols {
  display: grid; grid-template-columns: 1fr 1fr; gap: 0;
}
.weights__movers-col {
  padding: 6px 8px;
}
.weights__movers-col:first-child { border-right: 1px solid rgba(51,65,85,.4); }
.weights__movers-col-hdr {
  font-size: .6rem; font-weight: 700; margin-bottom: 4px;
  text-transform: uppercase; letter-spacing: .3px; opacity: .8;
}
.weights__mover-row {
  display: flex; align-items: center; gap: 5px; cursor: pointer;
  padding: 2px 2px; border-radius: 3px; transition: background var(--tr-fast);
}
.weights__mover-row:hover { background: rgba(99,102,241,.08); }
.weights__mover-delta { font-size: .65rem; font-weight: 700; width: 28px; flex-shrink: 0; text-align: right; }
.weights__mover-name { font-size: .65rem; color: var(--text-sec); flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.weights__mover-pos { font-size: .58rem; color: var(--text-muted); flex-shrink: 0; white-space: nowrap; }

/* Sensitivity curves */
.weights__sens-grid {
  display: flex; flex-wrap: wrap; gap: 8px;
}
.weights__sens-card {
  background: var(--bg-card); border: 1px solid var(--border); border-radius: 5px;
  padding: 6px 8px; width: 140px; flex-shrink: 0;
}
.weights__sens-label {
  font-size: .6rem; color: var(--text-sec); margin-bottom: 3px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.weights__sens-svg {
  display: block; width: 100%; height: 40px; overflow: visible;
  background: rgba(0,0,0,.2); border-radius: 2px;
}
.weights__sens-meta {
  display: flex; justify-content: space-between;
  font-size: .52rem; color: var(--text-muted); margin-top: 2px;
}
.weights__sens-axis {
  display: flex; justify-content: space-between;
  font-size: .5rem; color: var(--border); margin-top: 1px;
}

.weights__share-btn {
  padding: 3px 10px; font-size: .7rem; border-radius: 4px; cursor: pointer;
  border: 1px solid rgba(99,102,241,.4); background: none; color: var(--accent-light);
  transition: all .15s;
}
.weights__share-btn:hover { background: var(--accent-bg); border-color: var(--accent); }
.weights__share-btn--copied { color: var(--green); border-color: rgba(74,222,128,.5); background: rgba(74,222,128,.08); }

/* Optimize offline */
.weights__optimize-btn--offline { background: var(--border); color: var(--text-muted); }
.weights__optimize-btn--offline:hover:not(:disabled) { background: var(--text-muted); color: var(--bg-base); filter: none; }
.weights__spin { display: inline-block; animation: spin .7s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

/* Offline help panel */
.weights__help-panel {
  font-size: .7rem; color: var(--text-sec); background: rgba(99,102,241,.08);
  border: 1px solid rgba(99,102,241,.3); border-radius: 5px;
  padding: 10px 14px; margin-bottom: 10px; line-height: 1.5;
}
.weights__help-panel strong { color: var(--accent-light); }
.weights__help-panel p { margin: 4px 0; }
.weights__help-panel code { background: var(--bg-base); padding: 2px 6px; border-radius: 3px; font-size: .68rem; }

/* Empty state */
.weights__empty-state {
  text-align: center; padding: 24px; color: var(--text-sec);
  background: var(--bg-card); border-radius: var(--radius); margin-bottom: 12px;
  border: 1px solid var(--border);
}

/* Optimize result diff */
.weights__opt-result {
  font-size: .65rem; padding: 8px 10px; border-radius: 5px; margin-bottom: 8px;
  background: rgba(34, 197, 94, .08); border: 1px solid rgba(34, 197, 94, .25);
}
.weights__opt-result-header {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  color: var(--green); margin-bottom: 6px;
}
.weights__revert-btn {
  margin-left: auto; padding: 2px 8px; font-size: .62rem; border-radius: 3px;
  border: 1px solid rgba(248,113,113,.4); background: none; color: var(--red);
  cursor: pointer; transition: all .15s;
}
.weights__revert-btn:hover { background: rgba(248,113,113,.1); }
.weights__opt-diffs {
  display: flex; flex-wrap: wrap; gap: 4px 12px;
}
.weights__opt-diff-row {
  display: flex; align-items: center; gap: 4px; font-size: .62rem;
}
.weights__opt-diff-name { color: var(--text-sec); }

/* Per-slider reset + info */
.weights__slider-info {
  display: inline-flex; align-items: center; justify-content: center;
  width: 14px; height: 14px; border-radius: 50%; flex-shrink: 0;
  background: var(--border); color: var(--text-muted); font-size: .6rem;
  cursor: help;
}
.weights__slider-info:hover { background: var(--accent); color: #fff; }

.weights__range-wrap { position: relative; flex: 1; }
.weights__default-tick {
  position: absolute; top: 50%; transform: translateY(-50%);
  width: 2px; height: 14px; background: var(--text-muted); opacity: .4;
  pointer-events: none; border-radius: 1px;
}

.weights__slider-reset {
  background: none; border: none; color: var(--text-muted); cursor: pointer;
  font-size: .8rem; padding: 0 2px; opacity: 0; flex-shrink: 0;
  transition: opacity .15s, color .15s;
}
.weights__slider-row:hover .weights__slider-reset { opacity: 1; }
.weights__slider-reset:hover { color: var(--accent-light); }
</style>
