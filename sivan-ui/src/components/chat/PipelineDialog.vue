<script setup lang="ts">
import { ref, computed, watch, onUnmounted } from 'vue'
import { fetchForestProgress } from '../../api/goals'
import type { ForestProgress } from '../../api/goals'
import { useI18n } from '../../utils/i18n'

const { t } = useI18n()

const props = defineProps<{
  messageId: string
  conversationId?: string
  /** V2 Forest 目标 ID，传入时可轮询进度。 */
  goalId?: string
}>()

const emit = defineEmits<{ close: [] }>()

// 目标进度
const goalProgress = ref<ForestProgress | null>(null)
const loadingGoal = ref(false)

async function fetchGoalProgress() {
  if (!props.goalId) { goalProgress.value = null; return }
  loadingGoal.value = true
  try {
    goalProgress.value = await fetchForestProgress(props.goalId)
  } catch {
    goalProgress.value = null
  } finally {
    loadingGoal.value = false
  }
}

// 自动刷新：活跃目标时每 2s 轮询
let refreshTimer: ReturnType<typeof setInterval> | null = null

/** 根据进度值推断目标状态。 */
function deriveStatus(p: ForestProgress): string {
  if (p.total === 0) return 'PENDING'
  if (p.completed >= p.total) return 'COMPLETED'
  return 'ACTIVE'
}

function shouldAutoRefresh(): boolean {
  if (!props.goalId || !goalProgress.value) return false
  const st = deriveStatus(goalProgress.value)
  return st === 'ACTIVE' || st === 'PAUSED'
}

function startAutoRefresh() {
  stopAutoRefresh()
  if (!props.goalId) return
  refreshTimer = setInterval(async () => {
    if (!shouldAutoRefresh()) {
      stopAutoRefresh()
      return
    }
    fetchGoalProgress()
  }, 2000)
}

function stopAutoRefresh() {
  if (refreshTimer !== null) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
}

onUnmounted(stopAutoRefresh)

// 目标进度计算
const goalProgressPercent = computed(() => {
  if (!goalProgress.value || goalProgress.value.total === 0) return 0
  return Math.round(goalProgress.value.completed / goalProgress.value.total * 100)
})

const goalStatusLabel = computed(() => {
  if (!goalProgress.value) return ''
  const st = deriveStatus(goalProgress.value)
  const map: Record<string, string> = {
    PENDING: t('statusWaiting'),
    ACTIVE: t('statusRunning'),
    PAUSED: t('goalPaused'),
    COMPLETED: t('statusCompleted'),
    FAILED: t('statusFailed'),
    CANCELLED: t('goalCancelled'),
  }
  return map[st] || st
})

watch(() => props.messageId, async (id) => {
  if (!id) return
  stopAutoRefresh()
  await fetchGoalProgress()
  if (shouldAutoRefresh()) startAutoRefresh()
}, { immediate: true })

watch(() => props.goalId, () => {
  stopAutoRefresh()
  fetchGoalProgress().then(() => {
    if (shouldAutoRefresh()) startAutoRefresh()
  })
})
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
          <!-- 目标进度 -->
          <div v-if="goalProgress" class="goal-card">
            <div class="goal-card__title">{{ goalProgress.title }}</div>
            <div class="goal-card__bar">
              <div class="goal-card__progress" :style="{ width: goalProgressPercent + '%' }"></div>
            </div>
            <div class="goal-card__stats">
              <span>{{ goalProgress.completed }}/{{ goalProgress.total }} 任务</span>
              <span class="goal-card__pct">{{ goalProgressPercent }}%</span>
              <span :class="'goal-card__status goal-card__status--' + deriveStatus(goalProgress).toLowerCase()">{{ goalStatusLabel }}</span>
            </div>
          </div>

          <!-- 空状态 -->
          <div v-if="!goalProgress && !loadingGoal" class="pipeline-empty">
            {{ t('pipelineEmpty') }}
          </div>
          <div v-if="loadingGoal" class="pipeline-loading">{{ t('loading') }}</div>
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
  width: 420px;
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
.pipeline-empty {
  padding: 24px;
  text-align: center;
  font-size: var(--fs-callout);
  color: var(--clr-tertiary);
}

/* 目标进度卡片 */
.goal-card {
  padding: 12px 14px;
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
</style>
