<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from '../../utils/i18n'

const props = defineProps<{
  orchestration: {
    executionId?: string
    squadName?: string
    status: 'running' | 'completed' | 'failed'
    phases: { name: string; status: string; agentCount?: number }[]
    currentStep?: string
    currentMessage?: string
    phaseCount?: number
    agentCount?: number
    errorMessage?: string
  }
  meta?: { duration?: string; tokens?: number; model?: string }
}>()

const emit = defineEmits<{
  showPipeline: []
  viewMonitor: []
  retry: []
}>()

const { t } = useI18n()

const totalPhases = computed(() => props.orchestration.phaseCount || props.orchestration.phases.length || 0)
const completedPhases = computed(() => props.orchestration.phases.filter(p => p.status === 'completed').length)
const progressPct = computed(() => totalPhases.value > 0 ? Math.round((completedPhases.value / totalPhases.value) * 100) : 0)

const label = computed(() => {
  const name = props.orchestration.squadName || ''
  return totalPhases.value > 1 ? `Squad「${name}」` : `«${name}»`
})

const isSingleAgent = computed(() => totalPhases.value <= 1)
</script>

<template>
  <div class="orch-card" :class="`orch-card--${orchestration.status}`">
    <!-- 运行态 -->
    <template v-if="orchestration.status === 'running'">
      <span class="orch-label">{{ label }}</span>
      <span class="orch-status-text">{{ t('statusRunning') }}</span>
      <span v-if="!isSingleAgent" class="orch-progress">
        <span class="orch-progress-bar">
          <span class="orch-progress-fill" :style="{ width: progressPct + '%' }"></span>
        </span>
        <span class="orch-progress-text">{{ t('phase') }} {{ completedPhases }}/{{ totalPhases }}</span>
      </span>
      <span v-if="orchestration.currentStep" class="orch-step">{{ orchestration.currentStep }}</span>
      <span v-if="orchestration.currentMessage" class="orch-msg">{{ orchestration.currentMessage }}</span>
    </template>

    <!-- 完成态 -->
    <template v-else-if="orchestration.status === 'completed'">
      <span class="orch-label">{{ label }}</span>
      <span class="orch-status-text orch-status--done">{{ t('execCompleted') }}</span>
      <span v-if="totalPhases > 1" class="orch-summary">· {{ totalPhases }} {{ t('phase') }} · {{ orchestration.agentCount || orchestration.phases.reduce((s, p) => s + (p.agentCount || 1), 0) }} Agent</span>
      <span v-if="meta?.duration" class="orch-summary">· {{ meta.duration }}</span>
      <span v-if="meta?.tokens" class="orch-summary">· {{ meta.tokens.toLocaleString() }} tokens</span>
      <span v-if="meta?.model" class="orch-summary">· {{ meta.model }}</span>
      <button v-if="orchestration.executionId" class="orch-link" @click="emit('viewMonitor')">{{ t('pipelineViewMonitor') }}</button>
      <button class="orch-link" @click="emit('showPipeline')">{{ t('viewPipeline') }}</button>
    </template>

    <!-- 失败态 -->
    <template v-else>
      <span class="orch-label">{{ label }}</span>
      <span class="orch-status-text orch-status--failed">{{ t('statusFailed') }}</span>
      <span v-if="orchestration.errorMessage" class="orch-error">{{ orchestration.errorMessage }}</span>
      <button class="orch-link" @click="emit('retry')">{{ t('reExecute') }}</button>
      <button class="orch-link" @click="emit('showPipeline')">{{ t('viewPipeline') }}</button>
    </template>
  </div>
</template>

<style scoped>
.orch-card {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding: 6px 12px;
  border-radius: var(--rad-md);
  font-size: var(--fs-caption);
  margin-top: 6px;
  border: 1px solid var(--clr-hairline);
}
.orch-card--running {
  background: var(--clr-bg-secondary);
  border-left: 3px solid var(--clr-accent);
}
.orch-card--completed {
  background: var(--clr-bg-secondary);
  border-left: 3px solid var(--clr-green);
}
.orch-card--failed {
  background: rgba(255, 59, 48, 0.04);
  border-left: 3px solid var(--clr-red);
}

.orch-label {
  font-weight: var(--fw-semibold);
  color: var(--clr-label);
}
.orch-status-text {
  color: var(--clr-accent);
  font-weight: var(--fw-medium);
}
.orch-status--done {
  color: var(--clr-green);
}
.orch-status--failed {
  color: var(--clr-red);
}

.orch-progress {
  display: flex;
  align-items: center;
  gap: 4px;
}
.orch-progress-bar {
  width: 60px;
  height: 4px;
  border-radius: 2px;
  background: var(--clr-hairline);
  overflow: hidden;
}
.orch-progress-fill {
  height: 100%;
  border-radius: 2px;
  background: var(--clr-accent);
  transition: width 0.3s ease;
}
.orch-progress-text {
  font-size: var(--fs-caption);
  color: var(--clr-tertiary);
}

.orch-step {
  color: var(--clr-secondary);
}
.orch-msg {
  color: var(--clr-tertiary);
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.orch-summary {
  color: var(--clr-tertiary);
}
.orch-error {
  color: var(--clr-red);
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.orch-link {
  font-size: var(--fs-caption);
  color: var(--clr-accent);
  background: none;
  border: none;
  cursor: pointer;
  padding: 0;
  font-family: inherit;
}
.orch-link:hover {
  text-decoration: underline;
}
</style>
