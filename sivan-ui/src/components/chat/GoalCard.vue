<template>
  <div class="goal-card" :class="{ 'goal-card--expanded': expanded }" @click="expanded = !expanded">
    <div class="goal-card__header">
      <span class="goal-card__icon">{{ goal.status === 'COMPLETED' ? '✅' : '🔄' }}</span>
      <span class="goal-card__title">{{ goal.title || '任务' }}</span>
      <span class="goal-card__stats">{{ goal.completedTasks }}/{{ goal.totalTasks }} 任务</span>
      <span class="goal-card__expand">{{ expanded ? '收起' : '展开' }}</span>
    </div>
    <div v-if="expanded && goal.tree" class="goal-card__detail">
      <ForestTree :node="goal.tree" :depth="0" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import ForestTree from '../forest/ForestTree.vue'

interface GoalCardData {
  goalId: string
  title?: string
  status: string
  completedTasks: number
  totalTasks: number
  tree?: any
}

defineProps<{ goal: GoalCardData }>()
const expanded = ref(false)
</script>

<style scoped>
.goal-card {
  border: 1px solid var(--border-color, #e0e0e0);
  border-radius: 8px;
  overflow: hidden;
  cursor: pointer;
  transition: box-shadow .2s;
}
.goal-card:hover { box-shadow: 0 2px 8px rgba(0,0,0,.08); }
.goal-card__header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  background: var(--bg-secondary, #f8f9fa);
}
.goal-card__icon { font-size: 16px; }
.goal-card__title { flex: 1; font-weight: 600; font-size: 14px; }
.goal-card__stats { font-size: 12px; color: var(--text-secondary, #666); }
.goal-card__expand { font-size: 12px; color: var(--accent-color, #409eff); }
.goal-card__detail { padding: 8px 14px 14px; }
</style>
