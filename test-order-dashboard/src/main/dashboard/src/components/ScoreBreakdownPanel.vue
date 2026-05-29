<template>
  <div class="sbp">
    <!-- Stacked contribution bar -->
    <div class="sbp__bar" title="Score contributions (green = positive, red = penalty)">
      <div
        v-for="c in props.data.components"
        :key="c.label"
        class="sbp__bar-seg"
        :style="{
          width: barWidth(c) + '%',
          background: c.contribution >= 0 ? 'var(--green)' : 'var(--red)',
          opacity: 0.7 + 0.3 * (Math.abs(c.contribution) / maxContrib),
        }"
        :title="`${c.label}: ${c.contribution >= 0 ? '+' : ''}${c.contribution} (${c.rawDetail})`"
      ></div>
    </div>

    <!-- Component rows -->
    <div class="sbp__rows">
      <div
        v-for="c in props.data.components"
        :key="c.label"
        class="sbp__row"
        :class="{ 'sbp__row--expandable': hasDeps(c) }"
        @click="hasDeps(c) ? toggleExpand(c.label) : undefined"
      >
        <span
          class="sbp__dot"
          :style="{ background: c.contribution >= 0 ? 'var(--green)' : 'var(--red)' }"
        ></span>
        <span class="sbp__label">{{ c.label }}</span>
        <span class="sbp__pts" :class="c.contribution >= 0 ? 'sbp__pts--pos' : 'sbp__pts--neg'">
          {{ c.contribution >= 0 ? '+' : '' }}{{ c.contribution }}
        </span>
        <span class="sbp__detail">{{ c.rawDetail }}</span>
        <span v-if="hasDeps(c)" class="sbp__expand-icon">{{ expanded === c.label ? '▲' : '▼' }}</span>

        <!-- Expanded dep tree (for dep overlap row) -->
        <div v-if="expanded === c.label && props.data.changedDeps.length" class="sbp__dep-tree">
          <div v-for="dep in props.data.changedDeps" :key="dep.className" class="sbp__dep-row">
            <span class="sbp__dep-name">{{ shortCls(dep.className) }}</span>
            <span v-if="dep.members.length" class="sbp__dep-members"> [{{ dep.members.slice(0,5).join(', ') }}{{ dep.members.length > 5 ? ` +${dep.members.length-5}` : '' }}]</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Flags -->
    <div v-if="anyFlag" class="sbp__flags">
      <span v-if="props.data.flags.newTest"           class="badge badge--accent">new</span>
      <span v-if="props.data.flags.changedTest"        class="badge badge--cyan">changed</span>
      <span v-if="props.data.flags.isFast"             class="badge badge--green">fast</span>
      <span v-if="props.data.flags.isSlow"             class="badge badge--orange">slow</span>
      <span v-if="props.data.flags.staticFieldOverlap" class="badge badge--yellow">static-field</span>
    </div>

    <!-- Method touches (collapsible) -->
    <div v-if="props.data.methodTouches.length" class="sbp__methods">
      <div class="sbp__methods-hdr" @click="showMethods = !showMethods">
        <span>Test methods touching changed code ({{ props.data.methodTouches.length }})</span>
        <span class="sbp__expand-icon">{{ showMethods ? '▲' : '▼' }}</span>
      </div>
      <div v-if="showMethods" class="sbp__method-rows">
        <div v-for="m in props.data.methodTouches" :key="m.method" class="sbp__method-row">
          <span class="sbp__method-name">{{ m.method }}()</span>
          <span class="sbp__method-deps"> → {{ m.changedDeps.map(shortMem).join(', ') }}</span>
        </div>
      </div>
    </div>

    <!-- Non-overlapping deps count -->
    <div v-if="props.data.nonOverlappingCount > 0" class="sbp__other-deps">
      Other dependencies: {{ props.data.nonOverlappingCount }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import type { ScoreBreakdown } from '../utils'

const props = defineProps<{ data: ScoreBreakdown }>()

const expanded = ref<string | null>(null)
const showMethods = ref(false)

const maxContrib = computed(() =>
  props.data.components.reduce((m, c) => Math.max(m, Math.abs(c.contribution)), 1)
)
const totalAbs = computed(() =>
  props.data.components.reduce((s, c) => s + Math.abs(c.contribution), 0) || 1
)

function barWidth(c: { contribution: number }): number {
  return Math.max((Math.abs(c.contribution) / totalAbs.value) * 100, 2)
}

function hasDeps(c: { label: string }): boolean {
  return c.label === 'Dep overlap' && props.data.changedDeps.length > 0
}

function toggleExpand(label: string) {
  expanded.value = expanded.value === label ? null : label
}

const anyFlag = computed(() =>
  props.data.flags.newTest || props.data.flags.changedTest || props.data.flags.isFast ||
  props.data.flags.isSlow || props.data.flags.staticFieldOverlap
)

function shortCls(fqcn: string): string {
  const p = fqcn.split('.')
  return p.length <= 2 ? fqcn : p.slice(-2).join('.')
}

function shortMem(ref: string): string {
  const h = ref.indexOf('#')
  if (h < 0) return shortCls(ref)
  return shortCls(ref.substring(0, h)) + '#' + ref.substring(h + 1)
}
</script>

<style scoped>
.sbp { font-size: .72rem; color: var(--text); }

.sbp__bar {
  display: flex; height: 8px; border-radius: 4px; overflow: hidden;
  background: var(--bg-base); margin-bottom: 10px; gap: 1px;
}
.sbp__bar-seg { border-radius: 2px; transition: opacity .15s; cursor: default; }

.sbp__rows { display: flex; flex-direction: column; gap: 1px; }
.sbp__row {
  display: flex; align-items: baseline; gap: 5px;
  padding: 3px 4px; border-radius: 4px; flex-wrap: wrap;
}
.sbp__row--expandable { cursor: pointer; }
.sbp__row--expandable:hover { background: rgba(255,255,255,.05); }

.sbp__dot {
  width: 7px; height: 7px; border-radius: 50%; flex-shrink: 0;
  display: inline-block; margin-top: 2px;
}
.sbp__label { flex: 0 0 130px; color: var(--text-sec); }
.sbp__pts { font-weight: 700; min-width: 34px; text-align: right; }
.sbp__pts--pos { color: var(--green); }
.sbp__pts--neg { color: var(--red); }
.sbp__detail { color: var(--text-muted); font-size: .66rem; flex: 1; }
.sbp__expand-icon { color: var(--text-muted); font-size: .6rem; }

.sbp__dep-tree {
  flex-basis: 100%; padding: 4px 0 2px 18px;
  display: flex; flex-direction: column; gap: 2px;
}
.sbp__dep-row { display: flex; align-items: baseline; gap: 4px; }
.sbp__dep-name { color: var(--text-sec); font-family: monospace; font-size: .67rem; }
.sbp__dep-members { color: var(--text-muted); font-size: .62rem; }

.sbp__flags { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 8px; }

.sbp__methods { margin-top: 8px; }
.sbp__methods-hdr {
  display: flex; justify-content: space-between; cursor: pointer;
  color: var(--text-sec); padding: 3px 4px; border-radius: 4px;
  font-size: .68rem;
}
.sbp__methods-hdr:hover { background: rgba(255,255,255,.05); }
.sbp__method-rows { padding: 2px 0 0 10px; display: flex; flex-direction: column; gap: 2px; }
.sbp__method-row { display: flex; gap: 4px; flex-wrap: wrap; }
.sbp__method-name { color: var(--accent-light); font-family: monospace; font-size: .66rem; }
.sbp__method-deps { color: var(--text-muted); font-size: .62rem; }

.sbp__other-deps { color: var(--text-muted); margin-top: 6px; padding: 0 4px; }

/* Badge variants */
.badge { display: inline-block; padding: 1px 5px; border-radius: 10px; font-size: .62rem; font-weight: 600; }
.badge--accent  { background: var(--accent);  color: #fff; }
.badge--cyan    { background: #0e7490;         color: var(--cyan); }
.badge--green   { background: #166534;         color: var(--green); }
.badge--orange  { background: #9a3412;         color: var(--orange); }
.badge--yellow  { background: #713f12;         color: var(--yellow); }
</style>
