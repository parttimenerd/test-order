<template>
  <!-- Large decorative open-double-quote mark, top-left corner -->
  <svg
    class="absolute top-0 left-0 select-none pointer-events-none"
    width="220"
    height="220"
    viewBox="0 0 220 220"
    aria-hidden="true"
    xmlns="http://www.w3.org/2000/svg"
  >
    <!-- Left quotation mark: circle + tail -->
    <circle cx="42" cy="42" r="32" :fill="color" />
    <path d="M 22 66 Q 6 110 20 148" :stroke="color" stroke-width="22" stroke-linecap="round" fill="none" />
    <!-- Right quotation mark -->
    <circle cx="130" cy="42" r="32" :fill="color" />
    <path d="M 110 66 Q 94 110 108 148" :stroke="color" stroke-width="22" stroke-linecap="round" fill="none" />
  </svg>
</template>

<script setup lang="ts">
withDefaults(defineProps<{
  color?: string
}>(), {
  color: 'rgba(251,146,60,0.12)'
})
</script>
