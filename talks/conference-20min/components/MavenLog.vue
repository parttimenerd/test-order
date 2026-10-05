<template>
  <div class="maven-log">
    <div v-for="(line, i) in parsedLines" :key="i" class="log-line">
      <span v-if="line.tag" :class="tagClass(line.tag)">[{{ line.tag }}]</span>
      <span v-if="line.tag" class="log-rest" :class="restClass(line.tag)"> {{ line.rest }}</span>
      <span v-else :class="fullLineClass(line.raw)">{{ line.raw }}</span>
    </div>
  </div>
</template>

<script setup>
const props = defineProps({ log: { type: String, default: '' } })

const TAG_RE = /^\[(INFO|WARNING|ERROR|DEBUG|SUCCESS)\]\s*(.*)/

const parsedLines = props.log.trim().split('\n').map(raw => {
  raw = raw.trim()
  const m = raw.match(TAG_RE)
  if (m) return { tag: m[1], rest: m[2], raw }
  return { tag: null, rest: null, raw }
})

const tagClass = tag => ({
  INFO:    'tag tag-info',
  WARNING: 'tag tag-warn',
  ERROR:   'tag tag-error',
  DEBUG:   'tag tag-debug',
  SUCCESS: 'tag tag-success',
}[tag] ?? 'tag tag-info')

const restClass = tag => ({
  INFO:    'rest-info',
  WARNING: 'rest-warn',
  ERROR:   'rest-error',
  DEBUG:   'rest-debug',
  SUCCESS: 'rest-success',
}[tag] ?? '')

const fullLineClass = raw => {
  if (raw.startsWith('BUILD SUCCESS')) return 'build-success'
  if (raw.startsWith('BUILD FAILURE')) return 'build-failure'
  return ''
}
</script>

<style scoped>
.maven-log {
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
  font-size: 1rem;
  line-height: 1.7;
  background: rgba(0, 0, 0, 0.55);
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 8px;
  padding: 1rem 1.25rem;
  color: #cbd5e1;
}

.log-line { display: flex; align-items: baseline; gap: 0; }

.tag { font-weight: 700; font-size: 0.85em; letter-spacing: 0.03em; }
.tag-info    { color: #64748b; }
.tag-warn    { color: #f59e0b; }
.tag-error   { color: #f87171; }
.tag-debug   { color: #4b5563; }
.tag-success { color: #4ade80; }

.rest-info    { color: #94a3b8; }
.rest-warn    { color: #fbbf24; }
.rest-error   { color: #fca5a5; }
.rest-debug   { color: #6b7280; }
.rest-success { color: #86efac; font-weight: 600; }

.build-success {
  color: #4ade80;
  font-weight: 700;
  font-size: 1.05em;
  letter-spacing: 0.02em;
  margin-top: 0.25rem;
}

.build-failure {
  color: #f87171;
  font-weight: 700;
  font-size: 1.05em;
  letter-spacing: 0.02em;
  margin-top: 0.25rem;
}
</style>
