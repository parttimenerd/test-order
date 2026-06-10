<script setup lang="ts">
import { inject, computed } from 'vue'
import type { DashboardState } from '../composables/useDashboard'
import { sn } from '../utils'

const d = inject<DashboardState>('dashboard')!

const mutation = computed(() => d.dd.mutation)
const tests = computed(() => mutation.value?.tests ?? [])
const summary = computed(() => mutation.value?.summary ?? { high: 0, medium: 0, low: 0, none: 0 })

// Overall mutation score = totalKilled / totalMutants (from PIT run)
const overallKillRate = computed(() => mutation.value?.overallKillRate ?? 0)
const totalMutants = computed(() => mutation.value?.totalMutants ?? 0)
const totalKilled = computed(() => mutation.value?.totalKilled ?? 0)

function tierColor(rate: number): string {
  if (rate >= 0.15) return 'var(--green, #22c55e)'
  if (rate >= 0.05) return 'var(--yellow, orange)'
  if (rate > 0) return 'var(--text-sec)'
  return 'var(--text-muted)'
}

function tierLabel(rate: number): string {
  if (rate >= 0.15) return 'high'
  if (rate >= 0.05) return 'medium'
  if (rate > 0) return 'low'
  return 'none'
}
</script>

<template>
  <div v-show="d.activeTab.value === 'mutation'" style="animation:fadeIn .15s ease-out">
    <h3 style="font-size:.82rem;color:var(--text-sec);margin-bottom:10px"
        title="PIT mutation testing results — PIT inserts small code mutations (flip comparisons, remove calls, etc.) and checks if your tests detect them. Mutation score = killed / total mutants. 80%+ = strong test suite. Low-killing tests lack assertions or coverage.">Mutation Testing (PIT)</h3>

    <!-- Summary cards -->
    <div style="display:flex;gap:12px;flex-wrap:wrap;margin-bottom:16px">
      <div class="mut-card mut-card--overall"
        :title="`Mutation score: ${totalMutants > 0 ? totalKilled + '/' + totalMutants + ' mutants killed. ' : ''}PIT inserts code mutations (e.g. flipping comparisons, removing calls) and measures how many your tests detect. Higher is better — 80 %+ is considered strong, <50 % suggests tests lack assertions or coverage.`">
        <div class="mut-card__value">{{ (overallKillRate * 100).toFixed(1) }}%</div>
        <div class="mut-card__label">Mutation score</div>
        <div v-if="totalMutants > 0" style="font-size:.62rem;color:var(--text-muted);margin-top:2px">{{ totalKilled }}/{{ totalMutants }} killed</div>
      </div>
      <div class="mut-card mut-card--high"
        title="High kill share (≥15%): these tests each kill at least 15% of all mutants — they cover critical, well-tested production code paths.">
        <div class="mut-card__value">{{ summary.high }}</div>
        <div class="mut-card__label">High (&ge;15%)</div>
      </div>
      <div class="mut-card mut-card--medium"
        title="Medium kill share (5–15%): these tests kill a moderate share of mutants — good coverage but room to strengthen assertions.">
        <div class="mut-card__value">{{ summary.medium }}</div>
        <div class="mut-card__label">Medium (5–15%)</div>
      </div>
      <div class="mut-card mut-card--low"
        title="Low kill share (&lt;5%): these tests kill very few mutants — they may exercise code but lack specific assertions that would catch bugs.">
        <div class="mut-card__value">{{ summary.low }}</div>
        <div class="mut-card__label">Low (&lt;5%)</div>
      </div>
      <div class="mut-card"
        title="Zero kills: these tests kill no mutants at all — they run but never assert anything that a mutation would break. Consider adding meaningful assertions or removing them if redundant.">
        <div class="mut-card__value">{{ summary.none }}</div>
        <div class="mut-card__label">Zero kills</div>
      </div>
    </div>

    <!-- Kill-rate bar chart -->
    <div style="margin-bottom:16px">
      <div style="font-size:.62rem;color:var(--text-muted);margin-bottom:6px" title="Kill share = fraction of all mutants killed by this test class. Bar is scaled so 25% kill share fills the full bar width (×4 scale), making per-test differences easier to see.">Kill share per test (bar scale: 25% = full width)</div>
      <div
        v-for="t in tests"
        :key="t.testClass"
        style="display:flex;align-items:center;gap:8px;margin-bottom:4px;min-width:0;cursor:pointer"
        :title="t.testClass + ' — ' + (t.killRate * 100).toFixed(2) + '% kill share. Click to inspect in Tests tab'"
        @click="d.navigateToTestFromCov(t.testClass)"
      >
        <span class="bar-label" :title="t.testClass">{{ sn(t.testClass) }}</span>
        <div class="bar-track">
          <div
            class="bar-fill"
            :style="{
              width: Math.min(100, t.killRate * 400) + '%',
              background: tierColor(t.killRate)
            }"
          ></div>
        </div>
        <span class="bar-pct" :style="{ color: tierColor(t.killRate) }">
          {{ (t.killRate * 100).toFixed(1) }}%
        </span>
      </div>
    </div>

    <!-- Detail table -->
    <div style="overflow-x:auto;max-height:420px;overflow-y:auto">
      <table>
        <thead class="tests-overview__thead">
          <tr>
            <th class="th--left" title="Test class name — click to navigate to test detail">Test</th>
            <th class="th--right" title="Fraction of killed mutants that this test kills">Kill share</th>
            <th class="th--left" title="Tier based on kill share">Tier</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in tests" :key="t.testClass" class="tests-overview__row" style="cursor:pointer" :title="t.testClass + ' — click to inspect in Tests tab'" @click="d.navigateToTestFromCov(t.testClass)">
            <td class="td--name" :title="t.testClass">{{ sn(t.testClass) }}</td>
            <td class="td--right" :style="{ color: tierColor(t.killRate), fontVariantNumeric: 'tabular-nums' }">
              {{ (t.killRate * 100).toFixed(2) }}%
            </td>
            <td style="font-size:.72rem;text-transform:uppercase;letter-spacing:.4px" :style="{ color: tierColor(t.killRate) }">
              {{ tierLabel(t.killRate) }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <p v-if="!tests.length" style="color:var(--text-muted);font-size:.8rem;margin-top:12px">
      No mutation data available. Run <code>mvn test-order:analyze-mutations</code> to collect kill rates.
    </p>
  </div>
</template>

<style scoped>
.mut-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 10px 16px;
  min-width: 80px;
  text-align: center;
}
.mut-card__value { font-size: 1.4rem; font-weight: 700; color: var(--text); }
.mut-card__label { font-size: .65rem; color: var(--text-muted); text-transform: uppercase; letter-spacing: .5px; }
.mut-card--overall .mut-card__value { color: var(--accent, #6366f1); }
.mut-card--high .mut-card__value { color: var(--green, #22c55e); }
.mut-card--medium .mut-card__value { color: var(--yellow, orange); }
.mut-card--low .mut-card__value { color: var(--text-sec); }

.bar-label {
  font-size: .72rem;
  color: var(--text-sec);
  width: 160px;
  flex-shrink: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: right;
}
.bar-track {
  flex: 1;
  height: 8px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 4px;
  overflow: hidden;
  min-width: 60px;
}
.bar-fill {
  height: 100%;
  border-radius: 4px;
  transition: width .3s ease;
}
.bar-pct {
  font-size: .72rem;
  width: 42px;
  text-align: right;
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}
</style>
