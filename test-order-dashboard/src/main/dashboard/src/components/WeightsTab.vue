<script setup lang="ts">
import { inject } from 'vue'
import type { DashboardState } from '../composables/useDashboard'
import { sn } from '../utils'

const d = inject<DashboardState>('dashboard')!

function origTooltip(name: string): string {
  return d.getScoreBreakdown(name, 'orig') + '\n\nClick to open detailed score modal'
}

function simTooltip(name: string): string {
  return d.getScoreBreakdown(name, 'sim') + '\n\nClick to open detailed score modal'
}

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
}
</script>

<template>
  <div v-if="d.activeTab.value === 'weights'">
    <div style="display:flex;align-items:center;gap:10px;margin-bottom:10px">
      <h3 style="font-size:.82rem;color:var(--text-sec)">Weight Sliders</h3>      <div v-if="d.simApfd.value !== null" class="kpi" style="padding:4px 10px;margin-left:12px">
        <div style="font-size:.55rem;color:var(--text-dim)">Simulated APFD</div>
        <div style="font-size:.95rem;font-weight:700" :style="{ color: d.simApfd.value >= 0.7 ? 'var(--green)' : d.simApfd.value >= 0.5 ? 'var(--yellow)' : 'var(--red)' }">
          {{ (d.simApfd.value * 100).toFixed(1) }}%
        </div>
      </div>
      <div v-if="d.avgApfd.value !== null" style="font-size:.65rem;color:var(--text-muted);margin-left:4px" title="Original average APFD from saved runs">orig avg: {{ (d.avgApfd.value * 100).toFixed(1) }}%</div>      <button @click="d.resetWeights()" style="margin-left:auto;padding:3px 10px;font-size:.7rem;background:var(--border);color:var(--text);border:1px solid var(--text-muted);border-radius:4px;cursor:pointer">Reset to defaults</button>
      <button v-if="d.serverConnected.value" @click="d.optimizeWeights()" :disabled="d.optimizing.value" class="weights__optimize-btn" :title="d.optimizing.value ? 'Running genetic algorithm…' : 'Run genetic algorithm to find optimal weights from run history'">
        {{ d.optimizing.value ? 'Optimizing…' : '⚡ Optimize' }}
      </button>
    </div>
    <div v-if="d.optimizeError.value" class="weights__opt-msg weights__opt-msg--err">{{ d.optimizeError.value }}</div>
    <div v-if="d.optimizeResult.value && !d.optimizeError.value" class="weights__opt-msg weights__opt-msg--ok">
      Optimized{{ d.optimizeResult.value.overfit ? ' (overfit → defaults)' : '' }}
      — train APFDc: {{ (d.optimizeResult.value.trainScore * 100).toFixed(1) }}%
      <span v-if="d.optimizeResult.value.folds > 0">, validation: {{ (d.optimizeResult.value.validationScore * 100).toFixed(1) }}%</span>
    </div>
    <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(320px,1fr));gap:5px;margin-bottom:14px">
      <div v-for="wd in d.dd.weightDefs" :key="wd.name" class="weights__slider-row" :title="WEIGHT_DESC[wd.name] || wd.name">
        <div class="weights__slider-label-wrap">
          <span class="weights__slider-label">{{ wd.name }}</span>
          <span v-if="WEIGHT_DESC[wd.name]" class="weights__slider-desc">{{ WEIGHT_DESC[wd.name] }}</span>
        </div>
        <input type="range" :min="wd.min" :max="wd.max" step="1" v-model.number="d.lw[wd.name]" class="weights__range">
        <span class="weights__slider-val">{{ d.lw[wd.name] }}</span>
        <span class="weights__slider-default">({{ wd.defaultValue }})</span>
      </div>
    </div>

    <h3 style="margin-bottom:6px;font-size:.82rem;color:var(--text-sec)">Rank comparison <span style="font-size:.65rem;color:var(--text-muted);font-weight:400">— click headers to sort</span></h3>
    <div style="overflow-x:auto;max-height:400px;overflow-y:auto">
      <table>
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
          <tr v-for="r in d.simResults.value" :key="r.name" :class="{ 'weights__row--big-delta': Math.abs(r.delta) > 5 }">
            <td class="weights__td weights__td--name" :title="r.name">{{ sn(r.name) }}</td>
            <td class="weights__td weights__td--right weights__td--dim">{{ r.origRank }}</td>
            <td class="weights__td weights__td--right weights__td--dim">{{ r.simRank }}</td>
            <td class="weights__td weights__td--right weights__td--delta" :class="{ 'weights__td--delta-up': r.delta < -5, 'weights__td--delta-down': r.delta > 5 }" :title="r.delta < 0 ? 'Moves earlier (better)' : r.delta > 0 ? 'Moves later (worse)' : 'No change'">{{ r.delta === 0 ? '–' : r.delta > 0 ? '+' + r.delta : r.delta }}</td>
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
.weights__td--dim { color: var(--text-dim); }
.weights__td--name { color: var(--text); max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
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
.weights__optimize-btn { padding: 3px 10px; font-size: .7rem; background: var(--accent); color: #fff; border: none; border-radius: 4px; cursor: pointer; font-weight: 600; }
.weights__optimize-btn:disabled { opacity: .5; cursor: wait; }
.weights__optimize-btn:hover:not(:disabled) { filter: brightness(1.15); }
.weights__opt-msg { font-size: .65rem; padding: 4px 8px; border-radius: 4px; margin-bottom: 8px; }
.weights__opt-msg--err { background: rgba(239, 68, 68, .15); color: var(--red); }
.weights__opt-msg--ok { background: rgba(34, 197, 94, .12); color: var(--green); }
</style>
