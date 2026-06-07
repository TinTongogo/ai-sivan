<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ status: 'connected' | 'disconnected' | 'reconnecting' }>()

const dotClass = computed(() => ({
  'sse-dot--connected': props.status === 'connected',
  'sse-dot--disconnected': props.status === 'disconnected',
  'sse-dot--reconnecting': props.status === 'reconnecting',
}))

const label = computed(() => {
  switch (props.status) {
    case 'connected': return 'SSE 已连接'
    case 'reconnecting': return '重连中...'
    default: return 'SSE 未连接'
  }
})
</script>

<template>
  <span class="sse-indicator" :title="label">
    <span class="sse-dot" :class="dotClass" />
    <span class="sse-label">{{ label }}</span>
  </span>
</template>

<style scoped>
.sse-indicator {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-caption);
  color: var(--clr-tertiary);
}
.sse-dot {
  width: 8px; height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.sse-dot--connected {
  background: var(--clr-green);
  animation: sse-pulse 2s infinite;
}
.sse-dot--disconnected {
  background: var(--clr-separator);
}
.sse-dot--reconnecting {
  background: var(--clr-amber);
  animation: sse-blink 0.6s infinite;
}
@keyframes sse-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}
@keyframes sse-blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.2; }
}
</style>
