<script setup lang="ts">
import { ref, computed, watch, onUnmounted } from 'vue'
import { fetchMessageForest, approveHitl, rejectHitl, type ForestTreeResponseNode, type ForestTreeResponse } from '../../api/goals'
import { useMessage } from '../../utils/message'
import { useI18n } from '../../utils/i18n'
import type { ForestTreeNode } from '../orchestration/ForestTree.vue'
import ForestTree from '../orchestration/ForestTree.vue'

const { t } = useI18n()

const props = defineProps<{
  messageId: string
  conversationId?: string
}>()

const emit = defineEmits<{ close: [] }>()

const forestData = ref<ForestTreeResponse | null>(null)
const loading = ref(false)
const error = ref('')
const msg = useMessage()

// HITL: 暂停的节点操作
const hitlPausedNode = ref<{ nodeId: string; name: string } | null>(null)
const hitlProcessing = ref(false)

async function handleHitlApprove(forestId: string, nodeId: string) {
  hitlProcessing.value = true
  try {
    await approveHitl(forestId, nodeId)
    msg.success(t('hitlApproved'))
    await loadForest()
  } catch { msg.error(t('hitlFailed')) }
  finally { hitlProcessing.value = false; hitlPausedNode.value = null }
}

async function handleHitlReject(forestId: string, nodeId: string) {
  hitlProcessing.value = true
  try {
    await rejectHitl(forestId, nodeId)
    msg.success(t('hitlRejected'))
    await loadForest()
  } catch { msg.error(t('hitlFailed')) }
  finally { hitlProcessing.value = false; hitlPausedNode.value = null }
}

// 递归查找暂停节点
function findPausedNode(node: ForestTreeResponseNode): ForestTreeResponseNode | null {
  if (node.status === 'PAUSED') return node
  if (node.children) {
    for (const c of node.children) {
      const found = findPausedNode(c)
      if (found) return found
    }
  }
  return null
}

const pausedNode = computed(() => forestData.value?.root ? findPausedNode(forestData.value.root) : null)

function toForestTreeNode(node: ForestTreeResponseNode): ForestTreeNode {
  return {
    name: node.name,
    status: node.status as ForestTreeNode['status'],
    mode: node.mode as ForestTreeNode['mode'] | undefined,
    agent: node.agent,
    isLeaf: node.leaf,
    routeTier: node.routeTier,
    routeConfidence: node.routeConfidence,
    children: node.children?.map(toForestTreeNode),
  }
}

const treeNode = computed(() => {
  if (!forestData.value?.root) return null
  return toForestTreeNode(forestData.value.root)
})

const progressPercent = computed(() => {
  const p = forestData.value?.progress
  if (!p || p.total === 0) return 0
  return Math.round(p.completed / p.total * 100)
})

async function loadForest() {
  if (!props.conversationId || !props.messageId) return
  loading.value = true
  error.value = ''
  try {
    forestData.value = await fetchMessageForest(props.conversationId, props.messageId)
  } catch (e: any) {
    error.value = e.response?.data?.message || t('pipelineLoadFailed')
    forestData.value = null
  } finally {
    loading.value = false
  }
}

let refreshTimer: ReturnType<typeof setInterval> | null = null

function isActive(): boolean {
  if (!forestData.value?.progress) return false
  const p = forestData.value.progress
  return p.completed < p.total
}

function startAutoRefresh() {
  stopAutoRefresh()
  if (!isActive()) return
  refreshTimer = setInterval(async () => {
    if (!isActive()) { stopAutoRefresh(); return }
    await loadForest()
  }, 3000)
}

function stopAutoRefresh() {
  if (refreshTimer !== null) { clearInterval(refreshTimer); refreshTimer = null }
}

onUnmounted(stopAutoRefresh)

watch(() => props.messageId, async () => {
  stopAutoRefresh()
  await loadForest()
  if (isActive()) startAutoRefresh()
}, { immediate: true })
</script>

<template>
  <Teleport to="body">
    <div class="pipeline-overlay" @click.self="emit('close')">
      <div class="pipeline-dialog" @click.stop>
        <div class="pipeline-header">
          <span>{{ t('pipelineTitle') }}</span>
          <button class="pipeline-close" @click="emit('close')">&times;</button>
        </div>
        <div class="pipeline-body">
          <div v-if="forestData" class="pipeline-summary">
            <span class="pipeline-summary__text">
              {{ forestData.progress.completed }}/{{ forestData.progress.total }} {{ t('taskUnit') }}
            </span>
            <span class="pipeline-summary__pct">{{ progressPercent }}%</span>
          </div>
          <div v-if="treeNode" class="pipeline-tree">
            <ForestTree :node="treeNode" :depth="0" :defaultCollapsedDepth="2" />
          </div>
          <!-- HITL: 暂停节点操作 -->
          <div v-if="pausedNode && forestData?.forestId && pausedNode.nodeId" class="pipeline-hitl">
            <div class="pipeline-hitl__banner">
              <span class="pipeline-hitl__text">{{ t('hitlWaiting') }}: {{ pausedNode.name }}</span>
            </div>
            <div class="pipeline-hitl__actions">
              <button class="btn btn-sm btn-primary" :disabled="hitlProcessing" @click="handleHitlApprove(forestData.forestId!, pausedNode.nodeId!)">
                {{ hitlProcessing ? '...' : t('hitlApprove') }}
              </button>
              <button class="btn btn-sm btn-danger" :disabled="hitlProcessing" @click="handleHitlReject(forestData.forestId!, pausedNode.nodeId!)">
                {{ t('hitlReject') }}
              </button>
            </div>
          </div>

          <div v-if="loading" class="pipeline-status">{{ t('loading') }}</div>
          <div v-if="error" class="pipeline-status pipeline-status--error">{{ error }}</div>
          <div v-if="!forestData && !loading && !error" class="pipeline-empty">
            {{ t('pipelineEmpty') }}
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.pipeline-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.2); z-index: var(--z-drawer); animation: fade-in 0.15s ease; }
.pipeline-dialog { position: fixed; top: 50%; left: 50%; transform: translate(-50%,-50%); width: 580px; max-width: 92vw; max-height: 80vh; background: var(--clr-bg); border-radius: var(--rad-lg); box-shadow: var(--shd-modal); display: flex; flex-direction: column; z-index: var(--z-drawer); }
.pipeline-header { display: flex; align-items: center; justify-content: space-between; padding: 14px 20px; border-bottom: 1px solid var(--clr-hairline); font-size: var(--fs-headline); font-weight: var(--fw-semibold); }
.pipeline-close { width: 28px; height: 28px; display: flex; align-items: center; justify-content: center; border: none; background: transparent; color: var(--clr-tertiary); cursor: pointer; border-radius: var(--rad-md); font-size: 18px; }
.pipeline-close:hover { background: var(--clr-fill); color: var(--clr-label); }
.pipeline-body { padding: 12px 20px 16px; overflow-y: auto; flex: 1; min-height: 200px; overflow-anchor: none; }
.pipeline-summary { display: flex; align-items: center; gap: 8px; padding: 8px 10px; margin-bottom: 8px; background: var(--clr-bg-secondary); border-radius: var(--rad-md); font-size: var(--fs-callout); }
.pipeline-summary__text { color: var(--clr-label); }
.pipeline-summary__pct { margin-left: auto; font-family: var(--ff-mono); color: var(--clr-accent); font-weight: var(--fw-medium); }
.pipeline-tree { border: 1px solid var(--clr-hairline); border-radius: var(--rad-md); padding: 8px; max-height: 400px; overflow-y: auto; overflow-anchor: none; }
.pipeline-status { padding: 24px; text-align: center; font-size: var(--fs-callout); color: var(--clr-tertiary); }
.pipeline-status--error { color: var(--clr-danger); }
.pipeline-empty { padding: 24px; text-align: center; font-size: var(--fs-callout); color: var(--clr-tertiary); }
</style>
