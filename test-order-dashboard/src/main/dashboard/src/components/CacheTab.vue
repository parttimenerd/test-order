<script setup lang="ts">
import { inject, computed } from 'vue'
import type { DashboardState } from '../composables/useDashboard'
import { sn } from '../utils'

const d = inject<DashboardState>('dashboard')!

const cache = computed(() => d.dd.cache ?? null)
const tests = computed(() => cache.value?.tests ?? [])

function formatDuration(ms: number): string {
  if (!ms || ms <= 0) return '—'
  if (ms < 1000) return `${ms}ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`
  const mins = Math.floor(ms / 60_000)
  const secs = Math.floor((ms % 60_000) / 1000)
  return `${mins}m${secs.toString().padStart(2, '0')}s`
}
</script>

<template>
  <div v-show="d.activeTab.value === 'cache'" style="animation:fadeIn .15s ease-out">
    <h3 style="font-size:.82rem;color:var(--text-sec);margin-bottom:10px"
        title="Tests skipped by the skip-if-unchanged cache this run. A test is skippable when none of its covered source classes have changed AND it has passed the last N runs (default N=3).">
      Skip-if-Unchanged Cache
    </h3>

    <div v-if="!cache || !cache.enabled" style="color:var(--text-muted);font-size:.8rem;padding:24px 0">
      The skip-if-unchanged cache is disabled. Enable it with
      <code>-Dtestorder.cache.skipUnchanged=true</code> to skip tests whose
      dependencies haven't changed and that have a green streak of
      <code>testorder.cache.minPassStreak</code> runs (default 3).
    </div>

    <div v-else>
      <!-- Summary cards -->
      <div style="display:flex;gap:12px;flex-wrap:wrap;margin-bottom:16px">
        <div class="cache-card cache-card--skipped" title="Number of tests skipped this run by the cache. They had no dep changes and a sufficient pass streak.">
          <div class="cache-card__value">{{ cache.skippedCount }}</div>
          <div class="cache-card__label">Tests skipped</div>
        </div>
        <div class="cache-card cache-card--saved" title="Estimated wall-clock time saved this run — sum of EMA durations across all skipped tests.">
          <div class="cache-card__value">{{ formatDuration(cache.timeSavedMs) }}</div>
          <div class="cache-card__label">Time saved</div>
        </div>
      </div>

      <!-- Per-test cache table -->
      <div style="overflow-x:auto;max-height:500px;overflow-y:auto">
        <table>
          <thead class="tests-overview__thead">
            <tr>
              <th class="th--left" title="Test class name — click to navigate to test detail">Test</th>
              <th class="th--right" title="Number of consecutive passing runs for this test (its 'green streak'). Must meet testorder.cache.minPassStreak before the test is eligible to be skipped.">Pass streak</th>
              <th class="th--right" title="EMA-smoothed duration of this test — represents the wall-clock time that was saved by skipping it.">Duration (saved)</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in tests" :key="t.testClass" class="tests-overview__row" @click="d.navigateToTestFromCov(t.testClass)" style="cursor:pointer" :title="t.testClass + ' — click to inspect in Tests tab'">
              <td class="td--name" :title="t.testClass">{{ sn(t.testClass) }}</td>
              <td class="td--right" :title="t.passStreak + ' consecutive passing runs'">{{ t.passStreak }}</td>
              <td class="td--right" :title="t.durationMs + 'ms (EMA)'">{{ formatDuration(t.durationMs) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <p v-if="!tests.length" style="color:var(--text-muted);font-size:.8rem;margin-top:12px">
        Cache is enabled but no tests were skipped this run (no eligible tests with a long-enough pass streak).
      </p>
    </div>
  </div>
</template>

<style scoped>
.cache-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 10px 16px;
  min-width: 110px;
  text-align: center;
}
.cache-card__value { font-size: 1.4rem; font-weight: 700; color: var(--text); }
.cache-card__label { font-size: .65rem; color: var(--text-muted); text-transform: uppercase; letter-spacing: .5px; }
.cache-card--skipped .cache-card__value { color: var(--green, #22c55e); }
.cache-card--saved .cache-card__value { color: var(--blue, #3b82f6); }
</style>
