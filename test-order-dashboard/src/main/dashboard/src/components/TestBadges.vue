<script setup lang="ts">
import type { TestEntry } from '../types'

defineProps<{
  test: TestEntry
  size?: 'sm' | 'md'
}>()
</script>

<template>
  <span v-if="test.isChanged" class="badge badge--changed" :class="{ 'badge--md': size === 'md' }" title="Test class source was modified">{{ size === 'md' ? 'CHANGED' : 'CHG' }}</span>
  <span v-if="test.isNew" class="badge badge--new" :class="{ 'badge--md': size === 'md' }" title="New test class — not seen in previous run">{{ size === 'md' ? 'NEW' : 'NEW' }}</span>
  <span v-if="test.failScore > 0" class="badge badge--fail" :class="{ 'badge--md': size === 'md' }" title="Has recent failure history (failScore={{ test.failScore.toFixed(2) }})">{{ size === 'md' ? 'FAILING' : 'FAIL' }}</span>
  <span v-if="test.isFast" class="badge badge--fast" :class="{ 'badge--md': size === 'md' }" title="Faster than median duration">{{ size === 'md' ? 'FAST' : '⚡' }}</span>
  <span v-if="test.isSlow" class="badge badge--slow" :class="{ 'badge--md': size === 'md' }" title="Slower than median duration">{{ size === 'md' ? 'SLOW' : '🐢' }}</span>
  <span v-if="test.hasStaticFieldOverlap" class="badge badge--static" :class="{ 'badge--md': size === 'md' }" title="Reads static fields of changed classes">{{ size === 'md' ? 'STATIC' : 'STAT' }}</span>
</template>

<style scoped>
.badge--changed { background: rgba(146, 64, 14, .45); color: var(--yellow); }
.badge--new { background: rgba(20, 83, 45, .45); color: var(--green); }
.badge--fail { background: rgba(127, 29, 29, .45); color: var(--red); }
.badge--fast { background: rgba(8, 51, 68, .45); color: var(--cyan); }
.badge--slow { background: rgba(124, 45, 18, .45); color: var(--orange); }
.badge--static { background: rgba(88, 28, 135, .45); color: var(--purple); }
.badge:not(.badge--md) { font-size: .5rem; padding: 0 4px; }
</style>
