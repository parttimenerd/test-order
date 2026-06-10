<script setup lang="ts">
import { computed, inject } from 'vue'
import type { DashboardState } from '../composables/useDashboard'
import { sn, fmtDur } from '../utils'

const props = defineProps<{
  visible: boolean
  x: number
  y: number
  testName: string | null
}>()

const d = inject<DashboardState>('dashboard')!

const t = computed(() => props.testName ? d.tests.find(x => x.name === props.testName) ?? null : null)
const hist = computed(() => props.testName ? d.testHistoryMap.value.get(props.testName) ?? null : null)
const isFlaky = computed(() => props.testName ? d.flakyTests.value.has(props.testName) : false)
const lastOutcome = computed(() => {
  if (!d.latestRun.value || !props.testName) return null
  return d.latestRun.value.outcomes.find(o => o.testClass === props.testName) ?? null
})

const totalRuns = computed(() => {
  if (!hist.value) return 0
  return hist.value.pass + hist.value.fail
})

const statusLabel = computed(() => {
  if (!lastOutcome.value) return 'NO DATA'
  return lastOutcome.value.failed ? 'FAIL' : 'PASS'
})

const statusColor = computed(() => {
  if (!lastOutcome.value) return 'var(--border)'
  return lastOutcome.value.failed ? 'var(--red)' : 'var(--green)'
})

function dotColor(failed: boolean | null): string {
  if (failed === null) return 'var(--border)'
  return failed ? 'var(--red)' : 'var(--green)'
}

function shortModule(mod: string): string {
  const dot = mod.lastIndexOf('.')
  if (dot < 0) return mod
  const dash = mod.indexOf('-', dot)
  if (dash < 0) return mod
  return mod.substring(dash + 1)
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="visible && t"
      class="tc-card"
      :style="{ left: x + 'px', top: y + 'px' }"
    >
      <!-- Header: status pill + class name -->
      <div class="tc-card__header">
        <span v-if="lastOutcome" class="tc-card__pill" :style="{ background: statusColor }">{{ statusLabel }}</span>
        <span class="tc-card__name">{{ t.name.split('.').pop() }}</span>
      </div>

      <!-- Rank · score · duration -->
      <div class="tc-card__meta">
        #{{ t.rank }} &middot; score {{ t.score }} &middot; {{ t.duration >= 0 ? fmtDur(t.duration) : '?' }}
        <span v-if="t.durationVariance > 0 && t.duration > 0" class="tc-card__var">&plusmn;{{ fmtDur(Math.sqrt(t.durationVariance)) }}</span>
      </div>

      <!-- Badges -->
      <div class="tc-card__badges">
        <span v-if="t.isNew"                class="badge badge--accent">new</span>
        <span v-if="t.isChanged"             class="badge badge--cyan">changed</span>
        <span v-if="isFlaky"                 class="badge badge--yellow">flaky</span>
        <span v-if="t.isFast"               class="badge badge--green">fast</span>
        <span v-if="t.isSlow"               class="badge badge--orange">slow</span>
        <span v-if="t.hasStaticFieldOverlap" class="badge badge--purple">static-field</span>
        <span v-if="t.mlPFail !== null && t.mlPFail > 0.5" class="badge badge--red">ml-risk</span>
        <span v-if="t.module" class="badge badge--module" :title="t.module">{{ shortModule(t.module) }}</span>
        <span v-if="t.suspectHomeModule" class="badge badge--suspect"
          :title="`${Math.round((t.crossModuleDepCount / (t.deps?.length || 1)) * 100)}% of deps are in ${t.dominantDepModule} — this test may belong there`"
        >⚠ foreign deps</span>
      </div>

      <!-- Sparkline (last 8 runs) -->
      <div v-if="hist && hist.last8.length" class="tc-card__spark">
        <span
          v-for="(failed, i) in hist.last8"
          :key="i"
          class="tc-card__dot"
          :style="{ background: dotColor(failed) }"
          :title="failed ? 'fail' : 'pass'"
        ></span>
      </div>

      <!-- Run stats -->
      <div v-if="hist" class="tc-card__runs">
        {{ hist.pass }}P &middot; {{ hist.fail }}F across {{ totalRuns }} run{{ totalRuns !== 1 ? 's' : '' }}
      </div>

      <!-- Method count -->
      <div v-if="t.methods && t.methods.length" class="tc-card__methods">
        {{ t.methods.length }} test method{{ t.methods.length !== 1 ? 's' : '' }}
      </div>

      <!-- FQCN -->
      <div class="tc-card__fqn">{{ t.name }}</div>
    </div>
  </Teleport>
</template>

<style scoped>
.tc-card {
  position: fixed;
  z-index: 9000;
  background: var(--bg-card, #1e293b);
  border: 1px solid var(--border, #334155);
  border-radius: 7px;
  padding: 8px 12px;
  min-width: 220px;
  max-width: 340px;
  box-shadow: 0 8px 24px rgba(0,0,0,.7);
  pointer-events: none;
  font-size: .72rem;
  color: var(--text, #f1f5f9);
}

.tc-card__header {
  display: flex; align-items: center; gap: 7px; margin-bottom: 3px;
}
.tc-card__pill {
  display: inline-block; padding: 1px 6px; border-radius: 8px;
  font-size: .6rem; font-weight: 700; color: #0f172a; flex-shrink: 0;
}
.tc-card__name {
  font-size: .82rem; font-weight: 700; color: #e2e8f0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

.tc-card__meta {
  color: var(--text-muted, #64748b); font-size: .66rem; margin-bottom: 5px;
}
.tc-card__var { color: var(--text-muted, #64748b); }

.tc-card__badges {
  display: flex; flex-wrap: wrap; gap: 3px; margin-bottom: 5px;
}

.tc-card__spark {
  display: flex; gap: 3px; margin-bottom: 4px; align-items: center;
}
.tc-card__dot {
  width: 7px; height: 7px; border-radius: 50%; flex-shrink: 0;
}

.tc-card__runs {
  font-size: .63rem; color: var(--text-sec, #94a3b8); margin-bottom: 2px;
}
.tc-card__methods {
  font-size: .63rem; color: var(--text-muted, #64748b); margin-bottom: 2px;
}
.tc-card__fqn {
  font-size: .55rem; color: var(--text-muted, #64748b);
  word-break: break-all; margin-top: 4px; border-top: 1px solid var(--border, #334155);
  padding-top: 4px;
}

/* Badge variants */
.badge { display: inline-block; padding: 1px 5px; border-radius: 10px; font-size: .6rem; font-weight: 600; }
.badge--accent  { background: var(--accent, #6366f1);  color: #fff; }
.badge--cyan    { background: #0e7490; color: var(--cyan, #67e8f9); }
.badge--green   { background: #166534; color: var(--green, #4ade80); }
.badge--orange  { background: #9a3412; color: var(--orange, #fb923c); }
.badge--yellow  { background: #713f12; color: var(--yellow, #fbbf24); }
.badge--purple  { background: #581c87; color: var(--purple, #c084fc); }
.badge--red     { background: #7f1d1d; color: var(--red, #f87171); }
.badge--module  { background: #0c4a6e; color: #7dd3fc; font-size: .55rem; }
.badge--suspect { background: #451a03; color: #fbbf24; cursor: help; }
</style>
