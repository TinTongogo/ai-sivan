<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from '../../utils/i18n'

defineOptions({ name: 'ForestTree' })

export interface ForestTreeNode {
  name: string
  status: 'COMPLETED' | 'RUNNING' | 'PENDING' | 'FAILED' | 'CANCELLED' | 'SKIPPED' | 'PAUSED'
  mode?: 'SEQUENTIAL' | 'PARALLEL' | 'CONDITIONAL' | 'CONSENSUS' | 'HIERARCHICAL'
  children?: ForestTreeNode[]
  agent?: string
  isLeaf?: boolean
  routeTier?: number
  routeConfidence?: number
}

const { t } = useI18n()

const props = defineProps<{
  node: ForestTreeNode
  depth?: number
  defaultCollapsedDepth?: number
}>()

const depth = computed(() => props.depth ?? 0)
const isCollapsed = computed(() => depth.value > (props.defaultCollapsedDepth ?? 2))
const isExpandable = computed(() => props.node.children && props.node.children.length > 0)
const collapsed = ref(isCollapsed.value)

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
  // For very long names, truncate
  return n.length > 40 ? n.substring(0, 40) + '…' : n
})

function toggle() {
  collapsed.value = !collapsed.value
}
</script>

<template>
  <div class="forest-tree-node" :style="{ paddingLeft: depth * 20 + 'px' }">
    <div
      class="forest-tree-row"
      :class="[
        'forest-tree-row--' + node.status.toLowerCase(),
        { 'forest-tree-row--expandable': isExpandable }
      ]"
      @click="isExpandable ? toggle() : undefined"
    >
      <!-- Expand/Collapse icon -->
      <span v-if="isExpandable" class="forest-tree-toggle">
        {{ collapsed ? '▶' : '▼' }}
      </span>
      <span v-else class="forest-tree-toggle forest-tree-toggle--spacer">•</span>

      <!-- Status label -->
      <span class="forest-tree-status-text" :class="'forest-tree-status-text--' + node.status.toLowerCase()">{{ statusLabel }}</span>

      <!-- Node name -->
      <span class="forest-tree-name">{{ nodeName }}</span>

      <!-- Agent badge -->
      <span v-if="node.agent" class="forest-tree-agent">{{ node.agent }}</span>

      <!-- Route label -->
      <span v-if="routeLabel" class="forest-tree-route" :title="routeTitle">{{ routeLabel }}</span>

      <!-- Mode label -->
      <span v-if="modeIcon" class="forest-tree-mode" :title="modeTooltip">{{ modeShortLabel }} {{ modeIcon }}</span>

      <!-- Progress summary for inner nodes -->
      <span v-if="isExpandable && node.status !== 'COMPLETED'" class="forest-tree-progress">
        {{ progressSummary }}
      </span>
    </div>

    <!-- Children (recursive) -->
    <div v-if="!collapsed && isExpandable" class="forest-tree-children">
      <ForestTree
        v-for="(child, idx) in node.children"
        :key="idx"
        :node="child"
        :depth="depth + 1"
        :defaultCollapsedDepth="defaultCollapsedDepth"
      />
    </div>
  </div>
</template>

<style scoped>
.forest-tree-node {
  font-size: var(--fs-caption1, 12px);
  line-height: 1.6;
}

.forest-tree-row {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px 4px;
  border-radius: 4px;
  cursor: default;
  transition: background 0.15s;
}

.forest-tree-row:hover {
  background: var(--clr-bg-tertiary, rgba(0,0,0,0.04));
}

.forest-tree-row--expandable {
  cursor: pointer;
}

.forest-tree-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  font-size: 10px;
  color: var(--clr-tertiary, #999);
  flex-shrink: 0;
}

.forest-tree-toggle--spacer {
  color: transparent;
}

.forest-tree-status-text {
  flex-shrink: 0;
  font-size: 10px;
  padding: 0 5px;
  border-radius: 3px;
  font-weight: var(--fw-medium, 500);
}
.forest-tree-status-text--completed { color: var(--clr-success, #52c41a); }
.forest-tree-status-text--running { color: var(--clr-accent, #1677ff); }
.forest-tree-status-text--pending { color: var(--clr-tertiary, #999); }
.forest-tree-status-text--failed { color: var(--clr-danger, #ff4d4f); }
.forest-tree-status-text--cancelled { color: var(--clr-tertiary, #999); }
.forest-tree-status-text--skipped { color: var(--clr-tertiary, #999); }

.forest-tree-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--clr-label);
}

.forest-tree-agent {
  font-size: 10px;
  padding: 0 6px;
  background: var(--clr-bg-tertiary, #f0f0f0);
  border-radius: 3px;
  color: var(--clr-secondary, #666);
  flex-shrink: 0;
}

.forest-tree-route {
  font-size: 10px;
  padding: 0 5px;
  background: var(--clr-accent-soft);
  border-radius: 3px;
  color: var(--clr-accent, #1677ff);
  flex-shrink: 0;
  cursor: help;
}

.forest-tree-mode {
  font-size: 11px;
  color: var(--clr-tertiary, #999);
  flex-shrink: 0;
  cursor: help;
}

.forest-tree-progress {
  font-size: 10px;
  color: var(--clr-accent, #1677ff);
  flex-shrink: 0;
}

.forest-tree-children {
  border-left: 1px solid var(--clr-hairline, #eee);
  margin-left: 7px;
}
</style>
