<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { fetchRoutingDecision, type RoutingDecision } from '../../api/routing'
import { useI18n } from '../../utils/i18n'
import { relativeTime } from '../../utils/time'

const props = defineProps<{
  routingDecisionId: string
}>()

const emit = defineEmits<{
  close: []
}>()

const { t } = useI18n()

const loading = ref(true)
const error = ref<string | null>(null)
const decision = ref<RoutingDecision | null>(null)

const strategyMap: Record<string, { label: string; cls: string }> = {
  CHAT: { label: 'CHAT', cls: 'tag-info' },
  SINGLE_AGENT: { label: 'SINGLE_AGENT', cls: 'tag-info' },
  SQUAD: { label: 'SQUAD', cls: 'tag-accent' },
  INTENT_CLASSIFICATION: { label: 'INTENT_CLASSIFICATION', cls: 'tag-info' },
  FORCE_INTENT: { label: 'FORCE_INTENT', cls: 'tag-warning' },
  TEMPLATE_MATCH: { label: 'TEMPLATE_MATCH', cls: 'tag-info' },
  SQUAD_MATCH: { label: 'SQUAD_MATCH', cls: 'tag-accent' },
  AUTO_CREATE: { label: 'AUTO_CREATE', cls: 'tag-warning' },
  SQUAD_AUTO_CREATE: { label: 'SQUAD_AUTO_CREATE', cls: 'tag-accent' },
}

/** 将 strategy 归类为三种最终策略之一 */
const displayStrategy = computed(() => {
  if (!decision.value?.strategy) return '—'
  const s = decision.value.strategy
  if (s === 'CHAT' || s === 'SINGLE_AGENT' || s === 'SQUAD') return s
  const entry = strategyMap[s]
  return entry?.label || s
})

const strategyTagCls = computed(() => {
  if (!decision.value?.strategy) return ''
  const s = decision.value.strategy
  if (s === 'CHAT' || s === 'SINGLE_AGENT' || s === 'SQUAD') {
    return s === 'SQUAD' ? 'tag-accent' : 'tag-info'
  }
  const entry = strategyMap[s]
  return entry?.cls || ''
})

/** 解析 agent 列表：优先从 context.agentNames 取，否则取 selectedAgentName */
const agentList = computed<string[]>(() => {
  if (!decision.value) return []
  const ctx = decision.value.context
  // SQUAD 模式：优先从上下文取 agentNames 数组
  if (ctx && Array.isArray(ctx.agentNames) && ctx.agentNames.length > 0) {
    return ctx.agentNames.filter(Boolean)
  }
  // SINGLE_AGENT / CHAT 模式：单 agent 或无
  if (decision.value.selectedAgentName) {
    return [decision.value.selectedAgentName]
  }
  return []
})

watch(() => props.routingDecisionId, async (id) => {
  if (!id) return
  loading.value = true
  error.value = null
  try {
    const res: any = await fetchRoutingDecision(id)
    decision.value = res.data as RoutingDecision
  } catch (e: any) {
    error.value = e?.response?.data?.message || e.message || t('routingFetchFailed')
  } finally {
    loading.value = false
  }
}, { immediate: true })
</script>

<template>
  <div class="backdrop" @click.self="emit('close')">
    <div class="dialog">
      <div class="dialog__header">
        <span class="dialog__title">{{ t('routingDetail') }}</span>
        <button class="dialog__close" @click="emit('close')">&times;</button>
      </div>
      <div class="dialog__body">
        <div v-if="loading" class="loading">{{ t('loading') }}</div>
        <div v-else-if="error" class="error">{{ error }}</div>
        <div v-else-if="decision" class="detail">
          <!-- 策略 -->
          <div class="detail__row">
            <span class="detail__label">{{ t('strategy') }}</span>
            <span class="detail__value">
              <span class="tag" :class="strategyTagCls">{{ displayStrategy }}</span>
            </span>
          </div>
          <!-- 任务 -->
          <div class="detail__row">
            <span class="detail__label">{{ t('task') }}</span>
            <span class="detail__value detail__value--text">{{ decision.taskDescription }}</span>
          </div>
          <!-- 选中智能体（标签展示） -->
          <div class="detail__row" v-if="agentList.length > 0">
            <span class="detail__label">{{ t('selectedAgent') }}</span>
            <span class="detail__value detail__agents">
              <span class="agent-tag" v-for="(name, i) in agentList" :key="i">{{ name }}</span>
            </span>
          </div>
          <div class="detail__row" v-else>
            <span class="detail__label">{{ t('selectedAgent') }}</span>
            <span class="detail__value detail__no-agent">{{ t('none') }}</span>
          </div>
          <!-- 状态 -->
          <div class="detail__row">
            <span class="detail__label">{{ t('status') }}</span>
            <span class="detail__value" :class="decision.success ? 'success' : 'failed'">
              {{ decision.success ? t('success') : t('failed') }}
            </span>
          </div>
          <!-- 置信度 -->
          <div class="detail__row" v-if="decision.confidence != null">
            <span class="detail__label">{{ t('confidence') }}</span>
            <span class="detail__value">{{ (decision.confidence * 100).toFixed(0) }}%</span>
          </div>
          <!-- 理由 -->
          <div class="detail__row" v-if="decision.reasoning">
            <span class="detail__label">{{ t('reason') }}</span>
            <span class="detail__value detail__value--text">{{ decision.reasoning }}</span>
          </div>
          <!-- 创建时间 -->
          <div class="detail__row" v-if="decision.createdAt">
            <span class="detail__label">{{ t('createTime') }}</span>
            <span class="detail__value">{{ relativeTime(decision.createdAt) }}</span>
          </div>
        </div>
        <div v-else class="empty">{{ t('noData') }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.2);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: var(--z-drawer, 1000);
  animation: fade-in 0.15s ease;
}
.dialog {
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
.dialog__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid var(--clr-hairline);
  font-size: var(--fs-headline);
  font-weight: var(--fw-semibold);
}
.dialog__title {
  font-weight: var(--fw-semibold, 600);
  font-size: var(--fs-body, 15px);
}
.dialog__close {
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
.dialog__close:hover {
  background: var(--clr-fill);
  color: var(--clr-label);
}
.dialog__body { padding: 20px; overflow-y: auto; }
.loading, .empty {
  text-align: center;
  padding: 24px;
  color: var(--clr-tertiary, #999);
}
.error { text-align: center; padding: 24px; color: var(--clr-danger, #e74c3c); }
.detail { display: flex; flex-direction: column; gap: 12px; }
.detail__row { display: flex; gap: 12px; }
.detail__label {
  flex-shrink: 0;
  width: 80px;
  color: var(--clr-tertiary, #888);
  font-size: var(--fs-caption, 13px);
  line-height: 1.5;
}
.detail__value {
  flex: 1;
  font-size: var(--fs-body, 14px);
  line-height: 1.5;
  word-break: break-word;
}
.detail__value--text {
  background: var(--clr-bg-secondary, #f5f5f5);
  padding: 6px 10px;
  border-radius: 6px;
  font-size: var(--fs-caption, 13px);
  max-height: 200px;
  overflow-y: auto;
  white-space: pre-wrap;
}
.success { color: var(--clr-success, #27ae60); }
.failed { color: var(--clr-danger, #e74c3c); }

/* 智能体标签 */
.detail__agents {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.agent-tag {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 12px;
  background: var(--clr-accent-soft, #e8f0fe);
  color: var(--clr-accent, #1a73e8);
  font-size: var(--fs-footnote, 12px);
  font-weight: var(--fw-medium, 500);
  white-space: nowrap;
  border: 1px solid transparent;
}
.detail__no-agent {
  color: var(--clr-tertiary, #999);
  font-style: italic;
}
</style>
