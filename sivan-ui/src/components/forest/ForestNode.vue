<script setup lang="ts">
import { computed, ref } from 'vue'
import type { TreeNode, NodeStatus, Mode } from '../../types/forest'
import { MODE_LABELS, STATUS_TEXT } from '../../types/forest'

const props = defineProps<{
  node: TreeNode
  depth: number
  maxDepth?: number
}>()

const emit = defineEmits<{
  hitlApprove: [nodeId: string]
  hitlReject: [nodeId: string]
}>()

const isLeaf = computed(() => !props.node.children || props.node.children.length === 0)
const isFolded = ref(props.depth >= (props.maxDepth ?? Infinity) && props.node.status !== 'RUNNING')
const statusIcon = computed(() => statusIconMap[props.node.status])
const modeInfo = computed(() => props.node.mode ? MODE_LABELS[props.node.mode as Mode] : null)
const hitlRequired = computed(() => props.node.metadata?.hitl === true)
const hitlReason = computed(() =>
  (props.node.metadata?.hitl_reason as string) || '需要人工确认后才能继续'
)

const statusIconMap: Record<NodeStatus, string> = {
  PENDING: '\u25CB',
  RUNNING: '\u25CF',
  COMPLETED: '\u2713',
  FAILED: '\u2717',
  CANCELLED: '\u2014',
}

function shortId(id: string) {
  return id.length > 8 ? id.slice(0, 8) : id
}
</script>

<template>
  <div class="forest-node" :class="[`forest-node--depth-${Math.min(depth, 5)}`, `forest-node--${node.status.toLowerCase()}`]">
    <div class="forest-node__row">
      <!-- 折叠/展开图标（仅非叶子节点） -->
      <span v-if="!isLeaf" class="forest-node__toggle" @click="isFolded = !isFolded">
        {{ isFolded ? '\u25B6' : '\u25BC' }}
      </span>
      <span v-else class="forest-node__spacer" />

      <!-- 状态图标 -->
      <span class="forest-node__status-icon" :class="`forest-node__status-icon--${node.status.toLowerCase()}`">
        {{ statusIcon }}
      </span>

      <!-- Mode 徽标 -->
      <span v-if="modeInfo" class="forest-node__mode-badge" :title="modeInfo.label">
        {{ modeInfo.symbol }}
      </span>

      <!-- 节点内容 -->
      <span class="forest-node__content" :title="node.content">
        {{ node.content || node.nodeType || shortId(node.nodeId) }}
      </span>

      <!-- HITL 待审批标记 -->
      <span v-if="hitlRequired && node.status === 'PENDING'" class="forest-node__hitl-badge" :title="hitlReason">
        HITL
      </span>

      <!-- HITL 审批按钮 -->
      <span v-if="hitlRequired && node.status === 'PENDING'" class="forest-node__hitl-actions">
        <button class="forest-node__hitl-btn forest-node__hitl-btn--approve" @click="emit('hitlApprove', node.nodeId)">
          批准
        </button>
        <button class="forest-node__hitl-btn forest-node__hitl-btn--reject" @click="emit('hitlReject', node.nodeId)">
          拒绝
        </button>
      </span>

      <!-- 状态文本 -->
      <span class="forest-node__status-text">{{ STATUS_TEXT[node.status] }}</span>
    </div>

    <!-- 递归子节点 -->
    <div v-if="!isLeaf && !isFolded" class="forest-node__children">
      <ForestNode
        v-for="(child, i) in node.children"
        :key="child.nodeId || i"
        :node="child"
        :depth="depth + 1"
        :max-depth="maxDepth"
        @hitl-approve="(id) => emit('hitlApprove', id)"
        @hitl-reject="(id) => emit('hitlReject', id)"
      />
    </div>
  </div>
</template>

<style scoped>
.forest-node {
  --node-indent: 16px;
  font-family: var(--ff-sans, system-ui, sans-serif);
  font-size: var(--fs-callout, 13px);
  line-height: 1.5;
}

.forest-node__row {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px 4px;
  border-radius: 4px;
  transition: background-color 0.15s;
}

.forest-node__row:hover {
  background-color: var(--clr-background-hover, rgba(128, 128, 128, 0.08));
}

.forest-node--depth-0 { padding-left: 0; }
.forest-node--depth-1 { padding-left: var(--node-indent); }
.forest-node--depth-2 { padding-left: calc(var(--node-indent) * 2); }
.forest-node--depth-3 { padding-left: calc(var(--node-indent) * 3); }
.forest-node--depth-4 { padding-left: calc(var(--node-indent) * 4); }
.forest-node--depth-5 { padding-left: calc(var(--node-indent) * 5); }

.forest-node__toggle {
  cursor: pointer;
  user-select: none;
  width: 14px;
  text-align: center;
  color: var(--clr-tertiary, #888);
  flex-shrink: 0;
}

.forest-node__spacer {
  width: 14px;
  flex-shrink: 0;
}

.forest-node__status-icon {
  width: 16px;
  text-align: center;
  flex-shrink: 0;
}

.forest-node__status-icon--pending { color: var(--clr-tertiary, #888); }
.forest-node__status-icon--running { color: var(--clr-blue, #4096ff); }
.forest-node__status-icon--completed { color: var(--clr-green, #52c41a); }
.forest-node__status-icon--failed { color: var(--clr-red, #ff4d4f); }
.forest-node__status-icon--cancelled { color: var(--clr-orange, #fa8c16); }

.forest-node__mode-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 4px;
  background-color: var(--clr-background-secondary, #f0f0f0);
  color: var(--clr-secondary, #666);
  font-size: var(--fs-caption, 11px);
  font-weight: 600;
  flex-shrink: 0;
}

.forest-node__content {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--clr-label, #1a1a1a);
  min-width: 0;
}

.forest-node__status-text {
  color: var(--clr-tertiary, #888);
  font-size: var(--fs-caption, 11px);
  white-space: nowrap;
  flex-shrink: 0;
}

.forest-node__hitl-badge {
  display: inline-flex;
  align-items: center;
  padding: 0 6px;
  height: 18px;
  border-radius: 3px;
  background-color: var(--clr-yellow-bg, #fff7e6);
  color: var(--clr-yellow, #d48806);
  font-size: var(--fs-caption, 10px);
  font-weight: 600;
  text-transform: uppercase;
  flex-shrink: 0;
}

.forest-node__hitl-actions {
  display: inline-flex;
  gap: 4px;
  flex-shrink: 0;
}

.forest-node__hitl-btn {
  padding: 0 8px;
  height: 22px;
  border: 1px solid transparent;
  border-radius: 4px;
  cursor: pointer;
  font-size: var(--fs-caption, 11px);
  font-weight: 500;
  transition: opacity 0.15s;
}

.forest-node__hitl-btn:hover {
  opacity: 0.85;
}

.forest-node__hitl-btn--approve {
  background-color: var(--clr-green, #52c41a);
  color: #fff;
  border-color: var(--clr-green, #52c41a);
}

.forest-node__hitl-btn--reject {
  background-color: transparent;
  color: var(--clr-red, #ff4d4f);
  border-color: var(--clr-red, #ff4d4f);
}

.forest-node__children {
  margin-left: 0;
}
</style>
