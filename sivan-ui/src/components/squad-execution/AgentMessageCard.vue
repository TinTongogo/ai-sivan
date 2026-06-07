<script setup lang="ts">
/** Agent 消息卡片 — 展示单条契约记录，sourceAgent → targetAgent 的消息流向。 */
import type { Contract } from '../../api/squad'

defineProps<{ contract: Contract }>()
</script>

<template>
  <div class="agent-message-card">
    <div class="agent-message-card__header">
      <span class="agent-message-card__source">{{ contract.sourceAgent }}</span>
      <span class="agent-message-card__arrow">→</span>
      <span class="agent-message-card__target">{{ contract.targetAgent || '(广播)' }}</span>
      <span class="agent-message-card__time">{{ contract.createdAt ? new Date(contract.createdAt).toLocaleTimeString() : '' }}</span>
    </div>
    <div class="agent-message-card__content">{{ contract.content }}</div>
  </div>
</template>

<style scoped>
.agent-message-card {
  padding: 8px 10px;
  border: 1px solid var(--clr-hairline);
  border-radius: var(--rad-md);
  background: var(--clr-bg);
  margin-bottom: 6px;
}
.agent-message-card__header {
  display: flex; align-items: center; gap: 6px;
  font-size: var(--fs-caption); margin-bottom: 4px;
}
.agent-message-card__source { color: var(--clr-accent); font-weight: var(--fw-medium); }
.agent-message-card__arrow { color: var(--clr-quaternary); }
.agent-message-card__target { color: var(--clr-green); }
.agent-message-card__time { margin-left: auto; color: var(--clr-quaternary); }
.agent-message-card__content {
  font-size: var(--fs-footnote); color: var(--clr-secondary);
  max-height: 120px; overflow-y: auto; white-space: pre-wrap; word-break: break-all;
}
</style>
