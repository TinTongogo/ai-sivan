<script setup lang="ts">
import { computed } from 'vue'
import type { TreeNode, ForestProgress } from '../../types/forest'
import ForestNode from './ForestNode.vue'

const props = defineProps<{
  root: TreeNode | null
  progress?: ForestProgress | null
  delivery?: 'STREAM' | 'SUMMARY'
}>()

const emit = defineEmits<{
  hitlApprove: [nodeId: string]
  hitlReject: [nodeId: string]
}>()

/**
 * 默认折叠深度：
 * - STREAM 模式下展开到叶子（maxDepth = Infinity，但由 isFoldable 控制）
 * - SUMMARY 模式下折叠到 depth <= 1
 */
const maxDepth = computed(() => (props.delivery === 'SUMMARY' ? 1 : Infinity))

const hasTree = computed(() => props.root && props.root.children && props.root.children.length > 0)

function shortPct(v: number | undefined | null): string {
  if (v == null) return '--'
  return (v * 100).toFixed(0) + '%'
}
</script>

<template>
  <div class="forest-tree">
    <!-- 进度概览 -->
    <div v-if="progress" class="forest-tree__progress">
      <div class="forest-tree__progress-bar">
        <div class="forest-tree__progress-fill" :style="{ width: shortPct(progress.progress) }" />
      </div>
      <span class="forest-tree__progress-text">
        {{ progress.completed }}/{{ progress.total }} ({{ shortPct(progress.progress) }})
      </span>
    </div>

    <!-- 标题 -->
    <div v-if="progress?.title" class="forest-tree__title">
      {{ progress.title }}
    </div>

    <!-- 空状态 -->
    <div v-if="!hasTree" class="forest-tree__empty">
      暂无执行树
    </div>

    <!-- 树渲染 -->
    <ForestNode
      v-if="root"
      :node="root"
      :depth="0"
      :max-depth="maxDepth"
      @hitl-approve="(id) => emit('hitlApprove', id)"
      @hitl-reject="(id) => emit('hitlReject', id)"
    />
  </div>
</template>

<style scoped>
.forest-tree {
  border: 1px solid var(--clr-border, #e8e8e8);
  border-radius: 8px;
  padding: 12px;
  background-color: var(--clr-background, #fff);
}

.forest-tree__progress {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.forest-tree__progress-bar {
  flex: 1;
  height: 6px;
  border-radius: 3px;
  background-color: var(--clr-background-secondary, #f0f0f0);
  overflow: hidden;
}

.forest-tree__progress-fill {
  height: 100%;
  border-radius: 3px;
  background-color: var(--clr-blue, #4096ff);
  transition: width 0.3s ease;
}

.forest-tree__progress-text {
  font-size: var(--fs-caption, 11px);
  color: var(--clr-tertiary, #888);
  white-space: nowrap;
}

.forest-tree__title {
  font-size: var(--fs-body, 14px);
  font-weight: 600;
  color: var(--clr-label, #1a1a1a);
  margin-bottom: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--clr-border, #e8e8e8);
}

.forest-tree__empty {
  padding: 24px 0;
  text-align: center;
  color: var(--clr-tertiary, #888);
  font-size: var(--fs-callout, 13px);
}
</style>
