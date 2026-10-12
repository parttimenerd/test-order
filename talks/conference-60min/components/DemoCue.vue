<template>
  <div class="demo-cue">
    <span class="dot"></span>
    <span class="text">
      <slot>demo running — return at next slide</slot>
    </span>
  </div>
</template>

<style scoped>
.demo-cue {
  position: absolute;
  top: 1rem;
  right: 1rem;
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.4rem 0.85rem;
  border-radius: 999px;
  background: rgba(34, 197, 94, 0.12);
  border: 1px solid rgba(34, 197, 94, 0.45);
  font-size: 0.85rem;
  font-family: 'JetBrains Mono', monospace;
  color: #16a34a;
  z-index: 50;
}
.dot {
  display: inline-block;
  width: 0.55rem;
  height: 0.55rem;
  border-radius: 50%;
  background: #22c55e;
  box-shadow: 0 0 0 0 rgba(34, 197, 94, 0.7);
  animation: pulse 1.6s infinite;
}
@keyframes pulse {
  0%   { box-shadow: 0 0 0 0 rgba(34, 197, 94, 0.55); }
  70%  { box-shadow: 0 0 0 12px rgba(34, 197, 94, 0); }
  100% { box-shadow: 0 0 0 0 rgba(34, 197, 94, 0); }
}
</style>
