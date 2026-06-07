<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from '../../utils/i18n'
import PhaseFlowView from './PhaseFlowView.vue'
import ExecutionStatusBadge from './ExecutionStatusBadge.vue'
import type { SquadExecution } from '../../api/squad'

const props = defineProps<{
  execution: SquadExecution
}>()

const emit = defineEmits<{ close: [] }>()

const { t } = useI18n()

const phases = computed(() => {
  if (!props.execution.topologySnapshot) return []
  try {
    return JSON.parse(props.execution.topologySnapshot) as { phase: number; name?: string; agents?: string[]; hitl?: boolean }[]
  } catch { return [] }
})

const phaseStatuses = computed(() => {
  const m = new Map<number, 'pending' | 'running' | 'completed' | 'failed' | 'hitl'>()
  const cp = props.execution.currentPhase ?? -1
  const st = props.execution.status
  for (const p of phases.value) {
    if (p.phase < cp) m.set(p.phase, 'completed')
    else if (p.phase === cp) {
      if (st === 'FAILED') m.set(p.phase, 'failed')
      else if (st === 'HITL_PENDING') m.set(p.phase, 'hitl')
      else m.set(p.phase, 'running')
    } else m.set(p.phase, 'pending')
  }
  return m
})
const emptyEdges = computed(() => [])
</script>

<template>
  <Teleport to="body">
    <div class="ssm-overlay" @click.self="emit('close')">
      <div class="ssm-dialog" @click.stop>
        <div class="ssm-header">
          <span>{{ t('snapshotOf', { name: execution.squadName || execution.executionId.slice(0, 8) }) }}</span>
          <button class="ssm-close" @click="emit('close')">&times;</button>
        </div>
        <div class="ssm-meta">
          <span v-if="execution.squadMode">{{ t('snapshotMode') }}: {{ execution.squadMode }}</span>
          <span v-if="phases.length">{{ t('snapshotPhases') }}: {{ phases.length }}</span>
          <span class="ssm-meta__sep"></span>
          <span class="ssm-meta__status">{{ t('snapshotExecStatus') }}: <ExecutionStatusBadge :status="execution.status" /></span>
          <span v-if="execution.createdAt">{{ t('snapshotExecAt') }}: {{ new Date(execution.createdAt).toLocaleString() }}</span>
        </div>
        <div class="ssm-dag-wrap">
          <div class="ssm-dag-label">{{ t('dagTopology') }} <span class="ssm-dag-label__note">(PhaseFlowView, {{ t('readonly') }})</span></div>
          <div class="ssm-dag">
            <PhaseFlowView
              v-if="phases.length"
              :phases="phases"
              :current-phase="null"
              :phase-statuses="phaseStatuses"
              :contract-edges="emptyEdges"
              :selected-phase="null"
              :squad-mode="execution.squadMode"
              :readonly="true"
              :compact="true"
            />
            <div v-else class="ssm-empty">{{ t('noData') }}</div>
          </div>
        </div>
        <div class="ssm-footer">
          <a :href="`/squads/executions/${execution.executionId}`" class="ssm-link" @click="emit('close')">{{ t('viewFullMonitor') }}</a>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.ssm-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.3);
  z-index: var(--z-drawer);
  display: flex;
  align-items: center;
  justify-content: center;
}
.ssm-dialog {
  width: 640px;
  max-width: 92vw;
  height: 80vh;
  max-height: 80vh;
  background: var(--clr-bg);
  border-radius: var(--rad-lg);
  box-shadow: var(--shd-modal);
  display: flex;
  flex-direction: column;
}
.ssm-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid var(--clr-hairline);
  font-size: var(--fs-headline);
  font-weight: var(--fw-semibold);
}
.ssm-close {
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
.ssm-close:hover {
  background: var(--clr-fill);
  color: var(--clr-label);
}
.ssm-meta {
  display: flex;
  gap: 8px;
  padding: 8px 20px;
  font-size: var(--fs-caption);
  color: var(--clr-tertiary);
  border-bottom: 1px solid var(--clr-hairline);
  flex-wrap: wrap;
  align-items: center;
}
.ssm-meta__sep { width:1px; height:14px; background:var(--clr-hairline); margin:0 4px; }
.ssm-meta__status { display:inline-flex; align-items:center; gap:4px; }
.ssm-dag-wrap {
  flex: 1;
  overflow: hidden;
  padding: 12px;
  min-height: 160px;
  display: flex;
  flex-direction: column;
}
.ssm-dag-label {
  font-size: var(--fs-caption);
  font-weight: var(--fw-semibold);
  color: var(--clr-secondary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin-bottom: 8px;
  flex-shrink: 0;
}
.ssm-dag-label__note { font-weight:var(--fw-regular); text-transform:none; color:var(--clr-tertiary); letter-spacing:0; }
.ssm-dag {
  border: 1px solid var(--clr-hairline);
  border-radius: var(--rad-md);
  overflow: hidden;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.ssm-empty {
  padding: 24px;
  text-align: center;
  color: var(--clr-tertiary);
}
.ssm-footer {
  padding: 10px 20px;
  border-top: 1px solid var(--clr-hairline);
  text-align: left;
}
.ssm-link {
  font-size: var(--fs-callout);
  color: var(--clr-accent);
  text-decoration: none;
}
.ssm-link:hover {
  text-decoration: underline;
}
</style>
