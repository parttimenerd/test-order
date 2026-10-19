<template>
  <button
    class="light-mode-toggle"
    :title="isDark ? 'Switch to light mode' : 'Switch to dark mode'"
    @click="toggleDark()"
  >
    <span v-if="isDark">☀️</span>
    <span v-else>🌙</span>
  </button>
</template>

<script setup>
import { isDark, toggleDark } from '../../conference-60min/dark'
</script>

<style scoped>
.light-mode-toggle {
  position: fixed;
  bottom: 1.1rem;
  right: 1.1rem;
  z-index: 9999;
  width: 2rem;
  height: 2rem;
  border-radius: 50%;
  border: 1px solid rgba(128, 128, 128, 0.35);
  background: rgba(128, 128, 128, 0.12);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.95rem;
  line-height: 1;
  padding: 0;
  transition: background 0.15s, border-color 0.15s;
  backdrop-filter: blur(4px);
}
.light-mode-toggle:hover {
  background: rgba(128, 128, 128, 0.25);
  border-color: rgba(128, 128, 128, 0.55);
}
</style>
