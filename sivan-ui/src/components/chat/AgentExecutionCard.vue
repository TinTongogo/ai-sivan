<script setup lang="ts">
import { ref } from 'vue'

const props = defineProps<{
  agentName: string
  status: 'running' | 'completed' | 'paused'
  durationMs?: number
  content?: string
}>()

const expanded = ref(false)

function fmtMs(ms?: number) {
  if (!ms) return ''
  return `${(ms / 1000).toFixed(1)}s`
}
</script>

<template>
  <div class="aec-root">
    <div class="aec-header">
      <div class="aec-header__l">
        <span class="aec-icon">🤖</span>
        <span class="aec-name">{{ agentName }}</span>
        <span v-if="status === 'running'" class="aec-badge aec-badge--running"><span class="aec-live" /> 执行中</span>
        <span v-else-if="status === 'completed'" class="aec-badge aec-badge--done">✓ 完成</span>
        <span v-else class="aec-badge aec-badge--paused">⏸ 暂停</span>
      </div>
      <div class="aec-header__r">
        <span v-if="durationMs" class="aec-time">{{ fmtMs(durationMs) }}</span>
        <button v-if="content" class="aec-toggle" @click="expanded = !expanded">{{ expanded ? '收起' : '展开详情' }}</button>
      </div>
    </div>
    <div v-if="expanded && content" class="aec-body">{{ content }}</div>
  </div>
</template>

<style scoped>
.aec-root { border:1.5px solid var(--clr-hairline); border-radius:var(--rad-md); overflow:hidden; background:var(--clr-bg); }
.aec-header { display:flex; align-items:center; justify-content:space-between; padding:8px 12px; background:var(--clr-fill-hover); }
.aec-header__l { display:flex; align-items:center; gap:8px; }
.aec-icon { font-size:14px; }
.aec-name { font-size:var(--fs-callout); font-weight:var(--fw-semibold); color:var(--clr-label); }
.aec-badge { font-size:10px; padding:1px 6px; border-radius:10px; }
.aec-badge--running { background:rgba(0,122,255,.1); color:var(--clr-accent); }
.aec-badge--done { background:rgba(52,199,89,.1); color:var(--clr-green); }
.aec-live { display:inline-block; width:6px; height:6px; border-radius:50%; background:var(--clr-accent); animation:aec-pulse 1.2s infinite; vertical-align:middle; margin-right:2px; }
.aec-header__r { display:flex; align-items:center; gap:8px; }
.aec-time { font-size:var(--fs-caption); color:var(--clr-quaternary); font-family:var(--ff-mono); }
.aec-toggle { font-size:var(--fs-caption); color:var(--clr-accent); background:none; border:none; cursor:pointer; }
.aec-body { padding:8px 12px; font-size:var(--fs-footnote); color:var(--clr-secondary); white-space:pre-wrap; max-height:200px; overflow-y:auto; }
@keyframes aec-pulse { 0%,100%{opacity:1} 50%{opacity:.4} }
</style>
