<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from '../../utils/i18n'
import { useOrchestrationStore } from '../../stores/orchestration'

const { t } = useI18n()
const store = useOrchestrationStore()

const hasProgress = computed(() => store.isOrchestrating && store.progress !== null)
const phases = computed(() => store.progress?.phases || [])
</script>

<template>
  <div v-if="hasProgress" class="phase-progress-bar">
    <div class="phase-progress-row">
      <div class="phase-progress-track">
        <div class="phase-progress-fill" :style="{ width: store.percentComplete + '%' }"></div>
      </div>
      <span class="phase-progress-text">{{ t('phaseProgress', { pct: store.percentComplete, done: store.progress?.completedPhases ?? 0, total: store.progress?.totalPhases ?? 0 }) }}</span>
    </div>
    <div class="phase-step-bar">
      <span
        v-for="(ph, idx) in phases"
        :key="idx"
        class="phase-step"
        :class="{
          'phase-step--completed': ph.status === 'COMPLETED',
          'phase-step--running': ph.status === 'RUNNING',
          'phase-step--pending': ph.status === 'PENDING',
          'phase-step--failed': ph.status === 'FAILED',
        }"
      >
        {{ ph.name }}
      </span>
    </div>
  </div>
</template>

<style scoped>
.phase-progress-bar {
  padding: 4px 20px 6px;
  border-top: 1px solid var(--clr-hairline);
  background: var(--clr-bg);
  flex-shrink: 0;
}
.phase-progress-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.phase-progress-track {
  flex: 1;
  height: 4px;
  background: var(--clr-bg-tertiary);
  border-radius: 2px;
  overflow: hidden;
}
.phase-progress-fill {
  height: 100%;
  background: var(--clr-accent);
  border-radius: 2px;
  transition: width 0.3s ease;
}
.phase-progress-text {
  font-size: var(--fs-footnote);
  color: var(--clr-tertiary);
  white-space: nowrap;
  flex-shrink: 0;
}
.phase-step-bar {
  display: flex;
  gap: 4px;
  margin-top: 3px;
  flex-wrap: wrap;
}
.phase-step {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 3px;
  background: var(--clr-fill);
  color: var(--clr-quaternary);
  white-space: nowrap;
}
.phase-step--completed { color: var(--clr-success); background: var(--clr-success-soft, #e6f7e6); }
.phase-step--running {
  color: var(--clr-accent);
  background: var(--clr-accent-soft);
  font-weight: var(--fw-medium);
}
.phase-step--failed { color: var(--clr-danger); background: var(--clr-danger-soft, #fce8e8); }
</style>
