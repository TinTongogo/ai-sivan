<script setup lang="ts">
import { onMounted } from 'vue'
import { RouterView } from 'vue-router'
import { toasts, closeToast } from './utils/message'
import { usePreferencesStore } from './stores/preferences'

const prefs = usePreferencesStore()
onMounted(() => {
  if (localStorage.getItem('token')) prefs.load()
})
</script>

<template>
  <RouterView />
  <!-- Toast 容器 -->
  <div class="toast-container">
    <div v-for="t in toasts" :key="t.id" class="toast" :class="`toast--${t.type}`" @click="closeToast(t.id)">
      <svg v-if="t.type === 'success'" viewBox="0 0 20 20" width="16" height="16"><path d="M4 10l4 4 8-8" fill="none" stroke="currentColor" stroke-width="1.5"/></svg>
      <svg v-else-if="t.type === 'error'" viewBox="0 0 20 20" width="16" height="16"><path d="M6 6l8 8M14 6l-8 8" fill="none" stroke="currentColor" stroke-width="1.5"/></svg>
      <svg v-else viewBox="0 0 20 20" width="16" height="16"><path d="M10 6v5M10 13.5v.5" fill="none" stroke="currentColor" stroke-width="1.5"/></svg>
      <span>{{ t.text }}</span>
    </div>
  </div>
</template>
