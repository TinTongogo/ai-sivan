<script setup lang="ts">
import { ref, computed, watch, onUnmounted } from 'vue'
import api from '../../api'
import { useI18n } from '../../utils/i18n'

const { t } = useI18n()

interface MilestoneProgress {
  name: string
  order: number
  status: 'completed' | 'current' | 'pending'
  taskCount: number
  completedTaskCount: number
}

interface GoalProgress {
  goalId: string
  title: string
  status: string
  totalTasks: number
  completedTasks: number
  currentMilestone: number
  milestones: MilestoneProgress[]
}

const props = defineProps<{
  messageId: string
  conversationId?: string
}>()

const emit = defineEmits<{ close: [] }>()

interface PipelineStepRes {
  stepId: string
  messageId: string
  routingDecisionId?: string
  executionId?: string
  stepType: string
  stepName?: string
  status: string
  sequence: number
  parentStepId?: string
  startedAt?: string
  completedAt?: string
  durationMs?: number
  inputSummary?: string
  outputSummary?: string
  agentName?: string
  modelName?: string
  tokenCount?: number
  metadataJson?: Record<string, any>
  createdAt?: string
  children?: PipelineStepRes[]
}

const loading = ref(true)
const error = ref('')
const steps = ref<PipelineStepRes[]>([])
const expandedStepId = ref<string | null>(null)

// 目标进度
const goalProgress = ref<GoalProgress | null>(null)
const loadingGoal = ref(false)

async function fetchGoalProgress() {
  if (!props.conversationId) { goalProgress.value = null; return }
  loadingGoal.value = true
  try {
    const res: any = await api.get(`/goals/by-conversation/${props.conversationId}`)
    goalProgress.value = res?.data || null
  } catch {
    goalProgress.value = null
  } finally {
    loadingGoal.value = false
  }
}

function toggleExpand(stepId: string) {
  expandedStepId.value = expandedStepId.value === stepId ? null : stepId
}

function stepOutput(step: PipelineStepRes): string | null {
  if (step.outputSummary) return step.outputSummary
  if (step.stepType === 'tool_call' && step.inputSummary) return '> ' + step.inputSummary
  if (step.metadataJson?.output) return step.metadataJson.output
  if (step.metadataJson?.content) return step.metadataJson.content
  return null
}

// 自动刷新：存在 running 步骤或活跃目标时每 2s 轮询
let refreshTimer: ReturnType<typeof setInterval> | null = null

function hasRunningSteps(list: PipelineStepRes[]): boolean {
  return list.some(s => s.status === 'running' || (s.children?.length && hasRunningSteps(s.children)))
}

function shouldAutoRefresh(): boolean {
  if (hasRunningSteps(steps.value)) return true
  if (goalProgress.value && (goalProgress.value.status === 'ACTIVE' || goalProgress.value.status === 'PAUSED')) return true
  return false
}

function startAutoRefresh() {
  stopAutoRefresh()
  if (!props.messageId) return
  refreshTimer = setInterval(async () => {
    if (!shouldAutoRefresh()) {
      stopAutoRefresh()
      return
    }
    try {
      const res: any = await api.get(`/conversations/messages/${props.messageId}/pipeline-steps`)
      const newSteps = res?.data || res || []
      if (newSteps.length) steps.value = newSteps
    } catch { /* 静默失败，下次重试 */ }
    // 同时刷新目标进度
    if (goalProgress.value && (goalProgress.value.status === 'ACTIVE' || goalProgress.value.status === 'PAUSED')) {
      fetchGoalProgress()
    }
  }, 2000)
}

function stopAutoRefresh() {
  if (refreshTimer !== null) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
}

onUnmounted(stopAutoRefresh)

const statusIcon: Record<string, string> = {
  completed: '✓',
  running: '●',
  failed: '✗',
  skipped: '–',
}

const statusClass: Record<string, string> = {
  completed: 'status-completed',
  running: 'status-running',
  failed: 'status-failed',
  skipped: 'status-skipped',
}

const stepTypeLabel: Record<string, string> = {
  classify: t('stepClassify'),
  compress: t('stepCompress'),
  match_agent: t('stepMatchAgent'),
  match_squad: t('stepMatchSquad'),
  create_agent: t('stepCreateAgent'),
  execute_squad: t('stepExecuteSquad'),
  execute_agent: t('stepExecuteAgent'),
  agent_llm: t('stepAgentLlm'),
  tool_call: t('stepToolCall'),
  phase: t('stepPhase'),
  final: t('stepFinal'),
  error: t('stepError'),
  chat: t('stepChat'),
}

function stepTypeDisplay(type: string): string {
  return stepTypeLabel[type] || type
}

function formatDuration(ms?: number): string {
  if (ms == null) return ''
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

function countAllSteps(list: PipelineStepRes[]): number {
  let c = list.length
  for (const s of list) {
    if (s.children?.length) c += countAllSteps(s.children)
  }
  return c
}

function sumTokens(list: PipelineStepRes[]): number {
  let sum = 0
  for (const s of list) {
    if (s.tokenCount) sum += s.tokenCount
    if (s.children?.length) sum += sumTokens(s.children)
  }
  return sum
}

function sumDuration(list: PipelineStepRes[]): number {
  let sum = 0
  for (const s of list) {
    if (s.durationMs) sum += s.durationMs
    if (s.children?.length) sum += sumDuration(s.children)
  }
  return sum
}

const totalSteps = computed(() => countAllSteps(steps.value))
const totalTokens = computed(() => sumTokens(steps.value))
const totalDuration = computed(() => sumDuration(steps.value))

// 目标进度计算
const goalProgressPercent = computed(() => {
  if (!goalProgress.value || goalProgress.value.totalTasks === 0) return 0
  return Math.round(goalProgress.value.completedTasks / goalProgress.value.totalTasks * 100)
})

const goalStatusLabel = computed(() => {
  if (!goalProgress.value) return ''
  const map: Record<string, string> = {
    PENDING: t('statusWaiting'),
    ACTIVE: t('statusRunning'),
    PAUSED: t('goalPaused'),
    COMPLETED: t('statusCompleted'),
    FAILED: t('statusFailed'),
    CANCELLED: t('goalCancelled'),
  }
  return map[goalProgress.value.status] || goalProgress.value.status
})

const milestoneIcon = (ms: MilestoneProgress): string => {
  if (ms.status === 'completed') return '✓'
  if (ms.status === 'current') return '●'
  return '○'
}

const milestoneClass = (ms: MilestoneProgress): string => {
  if (ms.status === 'completed') return 'ms-completed'
  if (ms.status === 'current') return 'ms-current'
  return 'ms-pending'
}

function hasFailed(list: PipelineStepRes[]): boolean {
  return list.some(s => s.status === 'failed' || (s.children?.length && hasFailed(s.children)))
}

watch(() => props.messageId, async (id) => {
  if (!id) return
  stopAutoRefresh()
  loading.value = true
  error.value = ''
  steps.value = []
  try {
    const res: any = await api.get(`/conversations/messages/${id}/pipeline-steps`)
    steps.value = res?.data || res || []
  } catch (e: any) {
    error.value = e?.response?.data?.message || e?.message || t('pipelineLoadFailed')
  } finally {
    loading.value = false
  }
  await fetchGoalProgress()
  if (shouldAutoRefresh()) startAutoRefresh()
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
          <!-- 加载中 -->
          <div v-if="loading" class="pipeline-loading">{{ t('loading') }}</div>

          <!-- 加载失败 -->
          <div v-else-if="error" class="pipeline-error">{{ error }}</div>

          <!-- 空流水线 -->
          <div v-else-if="steps.length === 0" class="pipeline-empty">{{ t('pipelineEmpty') }}</div>

          <!-- 目标进度 -->
          <div v-if="goalProgress" class="goal-card">
            <div class="goal-card__title">{{ goalProgress.title }}</div>
            <div class="goal-card__bar">
              <div class="goal-card__progress" :style="{ width: goalProgressPercent + '%' }"></div>
            </div>
            <div class="goal-card__stats">
              <span>{{ goalProgress.completedTasks }}/{{ goalProgress.totalTasks }} 任务</span>
              <span class="goal-card__pct">{{ goalProgressPercent }}%</span>
              <span :class="'goal-card__status goal-card__status--' + goalProgress.status.toLowerCase()">{{ goalStatusLabel }}</span>
            </div>
            <div v-if="goalProgress.milestones && goalProgress.milestones.length" class="goal-card__milestones">
              <div v-for="ms in goalProgress.milestones" :key="ms.order" :class="['goal-card__ms', milestoneClass(ms)]">
                <span class="goal-card__ms-icon">{{ milestoneIcon(ms) }}</span>
                <span class="goal-card__ms-name">{{ ms.name }}</span>
                <span v-if="ms.taskCount > 0" class="goal-card__ms-count">{{ ms.completedTaskCount }}/{{ ms.taskCount }}</span>
                <span v-if="ms.status === 'current'" class="goal-card__ms-badge">当前</span>
              </div>
            </div>
          </div>

          <!-- 摘要栏 -->
          <div v-if="steps.length" class="pipeline-summary">
            <span v-if="totalSteps">{{ totalSteps }} 步骤</span>
            <span v-if="totalTokens"> · {{ totalTokens.toLocaleString() }} tokens</span>
            <span v-if="totalDuration"> · {{ formatDuration(totalDuration) }}</span>
            <span v-if="hasFailed(steps)" class="pipeline-summary__failed"> · {{ t('stepFailed') }}</span>
          </div>

          <!-- 步骤树 -->
          <div v-if="steps.length" class="pipeline-tree">
            <template v-for="step in steps" :key="step.stepId">
              <div class="step-node step-root">
                <div class="step-line" :class="{ 'step-failed-highlight': step.status === 'failed', 'step-expandable': stepOutput(step) }" @click="stepOutput(step) && toggleExpand(step.stepId)">
                  <span class="step-icon" :class="statusClass[step.status] || ''">{{ statusIcon[step.status] || '○' }}</span>
                  <span class="step-type">{{ stepTypeDisplay(step.stepType) }}</span>
                  <span v-if="step.stepName" class="step-name">{{ step.stepName }}</span>
                  <span v-if="step.agentName" class="step-agent">{{ step.agentName }}</span>
                  <span v-if="step.durationMs != null" class="step-duration">{{ formatDuration(step.durationMs) }}</span>
                  <span v-if="step.tokenCount != null" class="step-tokens">{{ step.tokenCount }} tok</span>
                </div>
                <div v-if="expandedStepId === step.stepId && stepOutput(step)" class="step-detail">
                  <pre class="step-detail__text">{{ stepOutput(step) }}</pre>
                </div>
                <div v-if="step.children && step.children.length > 0" class="step-children">
                  <div v-for="child in step.children" :key="child.stepId" class="step-node step-child">
                    <div class="step-line" :class="{ 'step-failed-highlight': child.status === 'failed', 'step-expandable': stepOutput(child) }" @click="stepOutput(child) && toggleExpand(child.stepId)">
                      <span class="step-icon step-icon-sm" :class="statusClass[child.status] || ''">{{ statusIcon[child.status] || '○' }}</span>
                      <span class="step-type">{{ stepTypeDisplay(child.stepType) }}</span>
                      <span v-if="child.stepName" class="step-name">{{ child.stepName }}</span>
                      <span v-if="child.agentName" class="step-agent">{{ child.agentName }}</span>
                      <span v-if="child.durationMs != null" class="step-duration">{{ formatDuration(child.durationMs) }}</span>
                      <span v-if="child.tokenCount != null" class="step-tokens">{{ child.tokenCount }} tok</span>
                    </div>
                    <div v-if="expandedStepId === child.stepId && stepOutput(child)" class="step-detail">
                      <pre class="step-detail__text">{{ stepOutput(child) }}</pre>
                    </div>
                    <!-- 孙节点 -->
                    <div v-if="child.children && child.children.length > 0" class="step-children">
                      <div v-for="grand in child.children" :key="grand.stepId" class="step-node step-grandchild">
                        <div class="step-line" :class="{ 'step-failed-highlight': grand.status === 'failed', 'step-expandable': stepOutput(grand) }" @click="stepOutput(grand) && toggleExpand(grand.stepId)">
                          <span class="step-icon step-icon-sm" :class="statusClass[grand.status] || ''">{{ statusIcon[grand.status] || '○' }}</span>
                          <span class="step-type">{{ stepTypeDisplay(grand.stepType) }}</span>
                          <span v-if="grand.stepName" class="step-name">{{ grand.stepName }}</span>
                          <span v-if="grand.agentName" class="step-agent">{{ grand.agentName }}</span>
                          <span v-if="grand.durationMs != null" class="step-duration">{{ formatDuration(grand.durationMs) }}</span>
                        </div>
                        <div v-if="expandedStepId === grand.stepId && stepOutput(grand)" class="step-detail">
                          <pre class="step-detail__text">{{ stepOutput(grand) }}</pre>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </template>

            <!-- 关联的编排监控链接 -->
            <div v-if="steps[0]?.executionId" class="pipeline-link">
              <a :href="`/squads/executions/${steps[0].executionId}`" target="_blank" class="link">{{ t('pipelineViewMonitor') }} →</a>
            </div>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.pipeline-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.2);
  z-index: var(--z-drawer);
  animation: fade-in 0.15s ease;
}
.pipeline-dialog {
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 560px;
  max-width: 92vw;
  max-height: 80vh;
  background: var(--clr-bg);
  border-radius: var(--rad-lg);
  box-shadow: var(--shd-modal);
  display: flex;
  flex-direction: column;
  z-index: var(--z-drawer);
}
.pipeline-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid var(--clr-hairline);
  font-size: var(--fs-headline);
  font-weight: var(--fw-semibold);
}
.pipeline-close {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: var(--clr-tertiary);
  cursor: pointer;
  border-radius: var(--rad-md);
  font-size: 18px;
}
.pipeline-close:hover {
  background: var(--clr-fill);
  color: var(--clr-label);
}
.pipeline-body {
  padding: 12px 20px 16px;
  overflow-y: auto;
  flex: 1;
}
.pipeline-loading,
.pipeline-error,
.pipeline-empty {
  padding: 24px;
  text-align: center;
  font-size: var(--fs-callout);
  color: var(--clr-tertiary);
}
.pipeline-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 2px;
  padding: 8px 12px;
  margin-bottom: 8px;
  border-radius: var(--rad-md);
  background: var(--clr-bg-secondary);
  font-size: var(--fs-caption);
  color: var(--clr-tertiary);
}
.pipeline-summary__failed {
  color: var(--clr-red);
  font-weight: var(--fw-medium);
}
.pipeline-error {
  color: var(--clr-danger);
}
.pipeline-tree {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.step-node {
  position: relative;
}
.step-root + .step-root {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--clr-hairline);
}
.step-line {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 8px;
  border-radius: var(--rad-sm);
  font-size: var(--fs-callout);
  transition: background 0.1s;
}
.step-line:hover {
  background: var(--clr-fill);
}
.step-icon {
  flex-shrink: 0;
  width: 18px;
  text-align: center;
  font-size: 12px;
}
.step-icon-sm {
  width: 14px;
  font-size: 10px;
}
.status-completed { color: var(--clr-success); }
.status-running { color: var(--clr-accent); }
.status-failed { color: var(--clr-danger); }
.status-skipped { color: var(--clr-tertiary); }
.step-failed-highlight {
  background: rgba(255, 59, 48, 0.06);
  border-radius: var(--rad-sm);
}
.step-type {
  color: var(--clr-label);
  font-weight: var(--fw-medium);
  min-width: 64px;
}
.step-name {
  color: var(--clr-secondary);
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.step-agent {
  color: var(--clr-accent);
  font-size: var(--fs-footnote);
  background: var(--clr-accent-soft);
  padding: 0 6px;
  border-radius: var(--rad-sm);
}
.step-duration {
  color: var(--clr-tertiary);
  font-size: var(--fs-footnote);
  font-family: var(--ff-mono);
  min-width: 40px;
  text-align: right;
}
.step-tokens {
  color: var(--clr-tertiary);
  font-size: var(--fs-footnote);
  font-family: var(--ff-mono);
  min-width: 40px;
  text-align: right;
}
.step-children {
  margin-left: 18px;
  border-left: 1px solid var(--clr-hairline);
  padding-left: 8px;
}
.step-child + .step-child {
  margin-top: 2px;
}
.step-grandchild {
  margin-left: 0;
}
.step-expandable {
  cursor: pointer;
}
.step-detail {
  margin: 4px 0 4px 8px;
  padding: 8px 12px;
  border-left: 2px solid var(--clr-accent);
  background: var(--clr-bg-secondary);
  border-radius: 0 var(--rad-sm) var(--rad-sm) 0;
}
.step-detail__text {
  margin: 0;
  font-size: var(--fs-footnote);
  color: var(--clr-primary);
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  line-height: 1.5;
  max-height: 200px;
  overflow-y: auto;
}
.pipeline-link {
  margin-top: 12px;
  text-align: center;
}
.pipeline-link .link {
  font-size: var(--fs-callout);
  color: var(--clr-accent);
  text-decoration: none;
}
.pipeline-link .link:hover {
  text-decoration: underline;
}

/* ── 目标进度卡片 ── */
.goal-card {
  padding: 12px 14px;
  margin-bottom: 10px;
  border-radius: var(--rad-md);
  background: var(--clr-bg-secondary);
  border: 1px solid var(--clr-accent-border);
}
.goal-card__title {
  font-size: var(--fs-callout);
  font-weight: var(--fw-semibold);
  color: var(--clr-label);
  margin-bottom: 8px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.goal-card__bar {
  height: 6px;
  border-radius: 3px;
  background: var(--clr-hairline);
  overflow: hidden;
  margin-bottom: 6px;
}
.goal-card__progress {
  height: 100%;
  border-radius: 3px;
  background: var(--clr-accent);
  transition: width var(--dur-normal) var(--ease-apple);
}
.goal-card__stats {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--fs-footnote);
  color: var(--clr-tertiary);
  margin-bottom: 8px;
}
.goal-card__pct {
  font-family: var(--ff-mono);
  color: var(--clr-accent);
  font-weight: var(--fw-medium);
}
.goal-card__status {
  margin-left: auto;
  font-weight: var(--fw-medium);
}
.goal-card__status--active { color: var(--clr-accent); }
.goal-card__status--paused { color: #d97706; }
.goal-card__status--completed { color: var(--clr-success); }
.goal-card__status--failed { color: var(--clr-danger); }
.goal-card__status--cancelled { color: var(--clr-tertiary); }
.goal-card__milestones {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.goal-card__ms {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 6px;
  border-radius: var(--rad-sm);
  font-size: var(--fs-footnote);
}
.goal-card__ms.ms-current {
  background: var(--clr-accent-soft);
}
.goal-card__ms-icon {
  flex-shrink: 0;
  width: 16px;
  text-align: center;
  font-size: 11px;
}
.goal-card__ms.ms-completed .goal-card__ms-icon { color: var(--clr-success); }
.goal-card__ms.ms-current .goal-card__ms-icon { color: var(--clr-accent); }
.goal-card__ms.ms-pending .goal-card__ms-icon { color: var(--clr-quaternary); }
.goal-card__ms-name {
  flex: 1;
  color: var(--clr-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.goal-card__ms.ms-completed .goal-card__ms-name { color: var(--clr-tertiary); }
.goal-card__ms-count {
  font-family: var(--ff-mono);
  color: var(--clr-quaternary);
  font-size: 10px;
}
.goal-card__ms-badge {
  font-size: 10px;
  color: var(--clr-accent);
  font-weight: var(--fw-medium);
  background: var(--clr-accent-soft);
  padding: 0 6px;
  border-radius: var(--rad-sm);
}

</style>
