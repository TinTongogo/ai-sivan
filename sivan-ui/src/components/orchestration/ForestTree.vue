<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from '../../utils/i18n'
import { renderMarkdown } from '../../utils/markdown'

defineOptions({ name: 'ForestTree' })

const emit = defineEmits<{
  feedback: [node: ForestTreeNode, rating: 'like' | 'dislike']
}>()

export interface ToolCallInfo {
  name: string
  count: number
  status: string
}

export interface ForestTreeNode {
  name: string
  status: 'COMPLETED' | 'RUNNING' | 'PENDING' | 'FAILED' | 'CANCELLED' | 'SKIPPED' | 'PAUSED'
  mode?: 'SEQUENTIAL' | 'PARALLEL' | 'CONDITIONAL' | 'CONSENSUS' | 'HIERARCHICAL'
  children?: ForestTreeNode[]
  agent?: string
  reasoning?: string
  isLeaf?: boolean
  routeTier?: number
  routeConfidence?: number
  toolCalls?: ToolCallInfo[]
  output?: string
  feedback?: 'like' | 'dislike'
  durationMs?: number
  tokens?: number
}

const { t } = useI18n()

const props = defineProps<{
  node: ForestTreeNode
  depth?: number
  /** 兄弟节点中是否为最后一个（用于控制 ├─ vs └─） */
  isLast?: boolean
  /** 祖先节点的 isLast 链（用于垂直连线渲染） */
  ancestorLine?: boolean[]
  defaultCollapsedDepth?: number
}>()

const depth = computed(() => props.depth ?? 0)
const isCollapsed = computed(() => depth.value > (props.defaultCollapsedDepth ?? 2))
const isExpandable = computed(() => props.node.children && props.node.children.length > 0)
const collapsed = ref(isCollapsed.value)
const detailExpanded = ref(false)

/** 有额外信息可展示：reasoning、agent、toolCalls 等 */
const hasDetail = computed(() =>
  props.node.output != null ||
  props.node.reasoning != null ||
  (props.node.toolCalls != null && props.node.toolCalls.length > 0)
)

const treeLine = computed(() => {
  const lines: string[] = []
  if (props.ancestorLine) {
    for (let i = 0; i < props.ancestorLine.length; i++) {
      lines.push(props.ancestorLine[i] ? '  ' : '│ ')
    }
  }
  lines.push(props.isLast ? '└─' : '├─')
  return lines.join('')
})

const statusLabel = computed(() => {
  switch (props.node.status) {
    case 'COMPLETED': return t('statusCompleted')
    case 'RUNNING': return t('statusRunning')
    case 'PENDING': return t('statusWaiting')
    case 'FAILED': return t('statusFailed')
    case 'CANCELLED': return t('statusCancelled')
    case 'SKIPPED': return t('statusSkipped')
    default: return props.node.status
  }
})

const routeLabel = computed(() => {
  if (props.node.routeTier === undefined || props.node.routeTier === null) return ''
  switch (props.node.routeTier) {
    case 0: return t('routeTier0')
    case 1: return t('routeTier1')
    case 2: return t('routeTier2')
    case 3: return t('routeTier3')
    default: return ''
  }
})

const routeTitle = computed(() => {
  if (props.node.routeTier === undefined || props.node.routeTier === null) return ''
  const tier = [t('routeTier0'), t('routeTier1'), t('routeTier2'), t('routeTier3')][props.node.routeTier]
  const conf = props.node.routeConfidence !== undefined && props.node.routeConfidence !== null
    ? ` · ${t('routeConfidence')} ${(props.node.routeConfidence * 100).toFixed(0)}%`
    : ''
  return `${tier}${conf}`
})

const modeIcon = computed(() => {
  if (!props.node.mode) return ''
  switch (props.node.mode) {
    case 'SEQUENTIAL': return '→'
    case 'PARALLEL': return '↔'
    case 'CONDITIONAL': return '⚡'
    case 'CONSENSUS': return '⊕'
    case 'HIERARCHICAL': return '⊞'
  }
})

const modeShortLabel = computed(() => {
  if (!props.node.mode) return ''
  switch (props.node.mode) {
    case 'SEQUENTIAL': return '顺序'
    case 'PARALLEL': return '并发'
    case 'CONDITIONAL': return '条件'
    case 'CONSENSUS': return '合成'
    case 'HIERARCHICAL': return '层级'
  }
})

const modeTooltip = computed(() => {
  if (!props.node.mode) return ''
  switch (props.node.mode) {
    case 'SEQUENTIAL': return t('modeSequential')
    case 'PARALLEL': return t('modeParallel')
    case 'CONDITIONAL': return t('modeConditional')
    case 'CONSENSUS': return t('modeConsensus')
    case 'HIERARCHICAL': return t('modeHierarchical')
    default: return props.node.mode
  }
})

const progressSummary = computed(() => {
  if (!props.node.children || props.node.children.length === 0) return ''
  const total = props.node.children.length
  const done = props.node.children.filter(c => c.status === 'COMPLETED').length
  return `[${done}/${total}]`
})

const nodeName = computed(() => {
  const n = props.node.name || ''
  return n.length > 50 ? n.substring(0, 50) + '…' : n
})

const rowBg = computed(() => {
  if (depth.value === 0) return 'var(--clr-bg-secondary, #f5f5f5)'
  return 'transparent'
})

function toggle() {
  collapsed.value = !collapsed.value
}
</script>

<template>
  <div class="forest-tree-node">
    <div
      class="forest-tree-row"
      :class="[
        'forest-tree-row--' + node.status.toLowerCase(),
        { 'forest-tree-row--expandable': isExpandable }
      ]"
      :style="{ background: rowBg }"
      @click="isExpandable ? toggle() : undefined"
    >
      <!-- Tree line (├── / └──) -->
      <span class="forest-tree-line" v-text="treeLine"></span>

      <!-- Expand/Collapse icon for inner nodes -->
      <span v-if="isExpandable" class="forest-tree-toggle">
        {{ collapsed ? '▶' : '▼' }}
      </span>
      <span v-else class="forest-tree-toggle forest-tree-toggle--spacer">●</span>

      <!-- Status badge -->
      <span class="forest-tree-status" :class="'forest-tree-status--' + node.status.toLowerCase()">
        {{ statusLabel }}
      </span>

      <!-- Node name -->
      <span class="forest-tree-name" :title="node.reasoning">{{ nodeName }}</span>

      <!-- Agent badge -->
      <span v-if="node.agent" class="forest-tree-agent">{{ node.agent }}</span>

      <!-- Tool call badges -->
      <span v-for="(tc, idx) in node.toolCalls" :key="idx" class="forest-tree-toolcall" :title="`${tc.name} × ${tc.count}`">
        {{ tc.name }}
      </span>

      <!-- Mode label (inner_goal 节点) -->
      <span v-if="modeIcon" class="forest-tree-mode" :title="modeTooltip">{{ modeShortLabel }} {{ modeIcon }}</span>

      <!-- Route label -->
      <span v-if="routeLabel" class="forest-tree-route" :title="routeTitle">{{ routeLabel }}</span>

      <!-- Reasoning (inner_goal 节点的 LLM 推理解释) — 非叶子节点直接显示 -->
      <span v-if="node.reasoning && isExpandable" class="forest-tree-reasoning-text" :title="node.reasoning">{{ node.reasoning }}</span>
      <!-- Reasoning (叶子节点) — 作为图标，点击查看详情展开 -->
      <span v-if="node.reasoning && !isExpandable" class="forest-tree-reasoning" :title="node.reasoning">💡</span>

      <!-- Progress summary -->
      <span v-if="isExpandable && node.status !== 'COMPLETED'" class="forest-tree-progress">
        {{ progressSummary }}
      </span>

      <!-- Node-level feedback -->
      <span v-if="node.status === 'COMPLETED'" class="forest-tree-feedback" @click.stop>
        <button class="feedback-btn" :class="{ 'feedback-btn--on': node.feedback === 'like' }"
                :title="t('like')" @click="emit('feedback', node, 'like')">
          <svg viewBox="0 0 20 20" width="11" height="11" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M14 9h3a1 1 0 011 1v7a1 1 0 01-1 1h-3M7 11V5a2 2 0 012-2l3 1v7l-3 3M7 11H4a1 1 0 01-1-1V9a1 1 0 011-1h3"/>
          </svg>
        </button>
        <button class="feedback-btn" :class="{ 'feedback-btn--on': node.feedback === 'dislike' }"
                :title="t('dislike')" @click="emit('feedback', node, 'dislike')">
          <svg viewBox="0 0 20 20" width="11" height="11" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M14 11h3a1 1 0 001-1V3a1 1 0 00-1-1h-3M7 9v6a2 2 0 002 2l3-1v-7l-3-3M7 9H4a1 1 0 01-1-1V6a1 1 0 011-1h3"/>
          </svg>
        </button>
      </span>
    </div>

    <!-- Detail expand/collapse (leaf nodes with extra info) -->
    <div v-if="hasDetail && !isExpandable" class="forest-tree-detail-row" @click="detailExpanded = !detailExpanded">
      <span class="forest-tree-detail-toggle">{{ detailExpanded ? '▼' : '▶' }}</span>
      <span class="forest-tree-detail-hint">{{ detailExpanded ? '收起详情' : '查看详情' }}</span>
      <span v-if="node.durationMs != null" class="forest-tree-metric">⏱ {{ (node.durationMs / 1000).toFixed(1) }}s</span>
      <span v-if="node.tokens != null" class="forest-tree-metric">⚡ {{ node.tokens.toLocaleString() }} tokens</span>
    </div>
    <div v-if="!isExpandable && detailExpanded && hasDetail" class="forest-tree-detail">
      <div v-if="node.output" class="forest-tree-detail-output">
        <div v-html="renderMarkdown(node.output)"></div>
      </div>
      <div v-if="node.reasoning" class="forest-tree-detail-item">
        <span class="forest-tree-detail-label">推理</span>
        <span class="forest-tree-detail-text">{{ node.reasoning }}</span>
      </div>
      <div v-if="node.toolCalls && node.toolCalls.length > 0" class="forest-tree-detail-item">
        <span class="forest-tree-detail-label">工具</span>
        <span class="forest-tree-detail-text">
          <span v-for="(tc, idx) in node.toolCalls" :key="idx">
            {{ tc.name }}<span v-if="tc.count > 1">(×{{ tc.count }})</span>{{ idx < node.toolCalls.length - 1 ? ', ' : '' }}
          </span>
        </span>
      </div>
    </div>

    <!-- Children (recursive) -->
    <div v-if="!collapsed && isExpandable" class="forest-tree-children">
      <ForestTree
        v-for="(child, idx) in node.children"
        :key="idx"
        :node="child"
        :depth="depth + 1"
        :is-last="idx === (node.children?.length ?? 0) - 1"
        :ancestor-line="[...(ancestorLine || []), isLast ?? true]"
        :defaultCollapsedDepth="defaultCollapsedDepth"
        @feedback="(n, r) => emit('feedback', n, r)"
      />
    </div>
  </div>
</template>

<style scoped>
.forest-tree-node {
  font-size: var(--fs-caption1, 12px);
  line-height: 1.8;
  font-family: var(--ff-mono, 'SF Mono', 'Cascadia Code', 'Consolas', monospace);
}

.forest-tree-row {
  display: flex;
  align-items: center;
  gap: 3px;
  padding: 2px 4px;
  border-radius: 3px;
  cursor: default;
  transition: background 0.12s;
  white-space: nowrap;
}

.forest-tree-row:hover {
  background: var(--clr-bg-tertiary, rgba(0,0,0,0.04));
}

.forest-tree-row--expandable {
  cursor: pointer;
}

.forest-tree-line {
  color: var(--clr-quaternary, #ccc);
  font-size: 11px;
  flex-shrink: 0;
  min-width: 1em;
}

.forest-tree-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 12px;
  font-size: 9px;
  color: var(--clr-tertiary, #999);
  flex-shrink: 0;
}

.forest-tree-toggle--spacer {
  font-size: 6px;
  color: var(--clr-quaternary, #ddd);
}

/* Status badges */
.forest-tree-status {
  flex-shrink: 0;
  font-size: 9px;
  padding: 0 5px;
  border-radius: 3px;
  font-weight: var(--fw-medium, 500);
  font-family: var(--ff-sans);
  letter-spacing: 0.02em;
}
.forest-tree-status--completed { color: #fff; background: var(--clr-success, #52c41a); }
.forest-tree-status--running { color: #fff; background: var(--clr-accent, #1677ff); }
.forest-tree-status--pending { color: var(--clr-tertiary, #999); background: var(--clr-bg-tertiary, #f0f0f0); }
.forest-tree-status--failed { color: #fff; background: var(--clr-danger, #ff4d4f); }
.forest-tree-status--cancelled { color: var(--clr-tertiary, #999); background: var(--clr-bg-tertiary, #f0f0f0); }
.forest-tree-status--skipped { color: var(--clr-tertiary, #999); background: var(--clr-bg-tertiary, #f0f0f0); }

.forest-tree-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--clr-label);
  font-family: var(--ff-sans);
  font-size: 12px;
}

.forest-tree-agent {
  font-size: 9px;
  padding: 0 5px;
  background: var(--clr-bg-tertiary, #f0f0f0);
  border-radius: 3px;
  color: var(--clr-secondary, #666);
  flex-shrink: 0;
  font-family: var(--ff-sans);
}

.forest-tree-toolcall {
  font-size: 8px;
  padding: 0 4px;
  background: #e8f5e9;
  border-radius: 3px;
  color: #2e7d32;
  flex-shrink: 0;
  font-family: var(--ff-mono);
  cursor: help;
}

.forest-tree-mode {
  font-size: 10px;
  color: var(--clr-tertiary, #999);
  flex-shrink: 0;
  cursor: help;
  font-family: var(--ff-sans);
}

.forest-tree-route {
  font-size: 9px;
  padding: 0 4px;
  background: var(--clr-accent-soft);
  border-radius: 3px;
  color: var(--clr-accent, #1677ff);
  flex-shrink: 0;
  cursor: help;
  font-family: var(--ff-sans);
}

.forest-tree-reasoning {
  font-size: 10px;
  color: var(--clr-accent, #1677ff);
  flex-shrink: 0;
  cursor: help;
}
.forest-tree-reasoning-text {
  font-size: 10px;
  color: var(--clr-tertiary, #888);
  flex-shrink: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: help;
}

.forest-tree-progress {
  font-size: 9px;
  color: var(--clr-accent, #1677ff);
  flex-shrink: 0;
  font-family: var(--ff-sans);
}

.forest-tree-feedback {
  display: none;
  gap: 1px;
  flex-shrink: 0;
  margin-left: 3px;
}
.forest-tree-row:hover .forest-tree-feedback {
  display: inline-flex;
}
.feedback-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border: none;
  border-radius: 3px;
  background: transparent;
  color: var(--clr-quaternary, #bbb);
  cursor: pointer;
  transition: all 0.12s ease;
  padding: 0;
}
.feedback-btn:hover {
  color: var(--clr-accent, #1677ff);
  background: var(--clr-accent-soft, #e6f4ff);
}
.feedback-btn--on {
  color: #fff !important;
  background: var(--clr-accent, #1677ff) !important;
}

.forest-tree-children {
  /* children are rendered directly without extra indentation — tree lines handle it */
}

.forest-tree-detail-row {
  display: flex;
  align-items: center;
  gap: 3px;
  padding: 1px 4px 1px calc(4px + 1em);
  cursor: pointer;
  font-family: var(--ff-sans);
  font-size: 10px;
  color: var(--clr-tertiary, #999);
}
.forest-tree-detail-row:hover {
  color: var(--clr-accent, #1677ff);
}
.forest-tree-detail-toggle {
  width: 10px;
  font-size: 8px;
  flex-shrink: 0;
}
.forest-tree-detail-hint {
  flex-shrink: 0;
}
.forest-tree-metric {
  font-size: 10px;
  color: var(--clr-tertiary, #888);
  font-family: var(--ff-sans);
  flex-shrink: 0;
  margin-left: auto;
}
.forest-tree-metric + .forest-tree-metric {
  margin-left: 8px;
}
.forest-tree-detail {
  margin: 0 0 2px calc(4px + 1em);
  padding: 4px 8px;
  background: var(--clr-bg-secondary, #f8f9fa);
  border-radius: 4px;
  font-family: var(--ff-sans);
  line-height: 1.5;
}
.forest-tree-detail-item {
  display: flex;
  gap: 6px;
  font-size: 11px;
  margin-bottom: 2px;
}
.forest-tree-detail-item:last-child {
  margin-bottom: 0;
}
.forest-tree-detail-label {
  flex-shrink: 0;
  color: var(--clr-secondary, #666);
  font-weight: var(--fw-medium, 500);
  min-width: 4em;
}
.forest-tree-detail-text {
  color: var(--clr-label);
  word-break: break-word;
}
.forest-tree-detail-output {
  padding: 8px 12px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--clr-label);
  overflow-x: auto;
}
.forest-tree-detail-output :deep(p) { margin: 0 0 6px; }
.forest-tree-detail-output :deep(p:last-child) { margin-bottom: 0; }
.forest-tree-detail-output :deep(code) {
  font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
  font-size: 0.85em;
  background: var(--clr-fill);
  padding: 1px 4px;
  border-radius: 3px;
}
.forest-tree-detail-output :deep(pre) {
  margin: 6px 0;
  padding: 8px 10px;
  border-radius: 4px;
  background: #1e1e2e;
  overflow-x: auto;
  font-size: 0.85em;
}
.forest-tree-detail-output :deep(pre code) {
  background: none;
  padding: 0;
  border-radius: 0;
  color: #cdd6f4;
}
.forest-tree-detail-output :deep(hr) {
  border: none;
  border-top: 1px solid var(--clr-hairline);
  margin: 8px 0;
}
.forest-tree-detail-tool {
  display: inline;
}
</style>
