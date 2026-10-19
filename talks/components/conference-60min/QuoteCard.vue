<template>
  <div class="quote-card">
    <div class="deco-quote" :style="{ color: accentColor }">&#x201C;</div>
    <blockquote class="quote-text">
      <slot />
    </blockquote>
    <div class="quote-attr">{{ attr }}</div>
    <img v-if="qr" :src="qr" class="qr" />
  </div>
</template>

<script setup>
defineProps({
  attr:        { type: String, required: true },
  qr:          { type: String, default: null },
  accentColor: { type: String, default: '#a78bfa' },
})
</script>

<style scoped>
.quote-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 3rem 4rem;
  position: relative;
}
.deco-quote {
  font-size: 9rem;
  font-weight: 900;
  line-height: 0.6;
  opacity: 0.35;
  margin-bottom: 1.2rem;
  font-family: 'DM Serif Display', Georgia, serif;
  user-select: none;
  letter-spacing: -0.05em;
}
.quote-text {
  font-size: 1.85rem;
  line-height: 1.7;
  font-style: italic;
  max-width: 820px;
  font-weight: 500;
  color: #e2e8f0;
  margin: 0 0 1.5rem;
}
.quote-attr {
  font-size: 1.05rem;
  opacity: 0.65;
  font-weight: 500;
  color: #94a3b8;
}
.qr {
  position: absolute;
  bottom: 1.5rem;
  right: 1.5rem;
  width: 6rem;
  height: 6rem;
}

/* light-mode overrides in non-scoped block below */
</style>

<style>
html:not(.dark) .quote-card .quote-text {
  color: #1e293b;
}
html:not(.dark) .quote-card .quote-attr {
  color: #475569;
}
</style>
