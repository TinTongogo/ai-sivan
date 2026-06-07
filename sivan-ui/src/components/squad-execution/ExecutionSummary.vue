<script setup lang="ts">
import type { SquadExecution } from '../../api/squad'
import ExecutionStatusBadge from './ExecutionStatusBadge.vue'

defineProps<{ execution: SquadExecution; duration: string }>()

function shortId(id: string) { return id?.slice(0, 8) || '--' }
</script>

<template>
  <div class="exec-summary">
    <div class="exec-summary__row">
      <span class="exec-summary__id">#{{ shortId(execution.executionId) }}</span>
      <ExecutionStatusBadge :status="execution.status" />
    </div>
    <div class="exec-summary__task">{{ execution.taskDescription }}</div>
    <div class="exec-summary__meta">
      <span>阶段 {{ (execution.currentPhase ?? 0) + 1 }}</span>
      <span class="exec-summary__sep">·</span>
      <span>{{ duration }}</span>
    </div>
  </div>
</template>

<style scoped>
.exec-summary { padding: 8px 0; }
.exec-summary__row { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.exec-summary__id { font-size: var(--fs-caption); color: var(--clr-quaternary); font-family: var(--ff-mono); }
.exec-summary__task {
  font-size: var(--fs-callout); color: var(--clr-label);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 240px;
}
.exec-summary__meta { font-size: var(--fs-caption); color: var(--clr-tertiary); margin-top: 2px; }
.exec-summary__sep { margin: 0 4px; }
</style>
