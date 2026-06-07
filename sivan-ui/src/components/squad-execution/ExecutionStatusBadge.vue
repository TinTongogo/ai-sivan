<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from '../../utils/i18n'

const props = defineProps<{ status: string }>()
const { t } = useI18n()

const cls = computed(() => 'badge badge--' + (props.status || 'PENDING').toLowerCase())
const text = computed(() => {
  const key = 'status' + (props.status?.charAt(0) || '') + (props.status?.slice(1).toLowerCase() || '')
  return (t as (k: string) => string)(key) || props.status
})
</script>

<template>
  <span :class="cls">{{ text }}</span>
</template>

<style scoped>
.badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: var(--rad-sm);
  font-size: var(--fs-caption);
  font-weight: var(--fw-semibold);
  white-space: nowrap;
}
.badge--running { background: rgba(0,122,255,0.1); color: var(--clr-accent); }
.badge--completed { background: rgba(52,199,89,0.1); color: var(--clr-green); }
.badge--failed { background: rgba(255,59,48,0.1); color: var(--clr-red); }
.badge--pending { background: rgba(142,142,147,0.1); color: var(--clr-tertiary); }
.badge--hitl_pending { background: rgba(255,149,0,0.1); color: var(--clr-amber); }
.badge--hitl_rejected { background: rgba(255,59,48,0.08); color: var(--clr-red); }
.badge--cancelled, .badge--cancelling { background: rgba(142,142,147,0.08); color: var(--clr-quaternary); }
.badge--paused { background: rgba(255,149,0,0.08); color: var(--clr-amber); }
.badge--timeout { background: rgba(142,142,147,0.08); color: var(--clr-quaternary); }
</style>
