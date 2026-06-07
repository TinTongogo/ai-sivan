<script setup lang="ts">
/** Agent 输出面板 — 选中阶段的 Agent 产出展示与合同列表。 */
import { computed } from 'vue'
import type { Contract } from '../../api/squad'
import AgentMessageCard from './AgentMessageCard.vue'
import { useI18n } from '../../utils/i18n'

export interface PhaseDetail {
  phase: number; name: string; mode: string; agents: string[]
  description: string
}

const props = defineProps<{
  phase: PhaseDetail | null
  contracts: Contract[]
  loading: boolean
}>()

const { t } = useI18n()

/** 从 contracts 中提取不重复的 sourceAgent 列表。 */
const uniqueAgents = computed(() => {
  const agents = new Set<string>()
  for (const c of props.contracts) {
    if (c.sourceAgent) agents.add(c.sourceAgent)
  }
  return Array.from(agents)
})

function scrollToAgent(agentName: string) {
  const c = props.contracts.find(c => c.sourceAgent === agentName)
  if (c) {
    const el = document.getElementById(`agent-card-${c.contractId}`)
    el?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}
</script>

<template>
  <div class="pt-root">
    <div v-if="!phase" class="pt-empty">{{ t('noData') }}</div>
    <template v-else>
      <div class="pt-header">
        <div class="pt-title">阶段 {{ phase.phase + 1 }}：{{ phase.name }}</div>
        <div class="pt-meta">
          <span class="pt-mode">{{ phase.mode }}</span>
          <span class="pt-meta__sep">·</span>
          <template v-for="(agent, idx) in uniqueAgents" :key="agent">
            <span class="pt-agent" @click="scrollToAgent(agent)">{{ agent }}</span>
            <span v-if="idx < uniqueAgents.length - 1" class="pt-meta__sep">,</span>
          </template>
        </div>
        <div v-if="phase.description" class="pt-desc">{{ phase.description }}</div>
      </div>
      <div class="pt-section">
        <div v-if="loading" class="pt-loading">{{ t('loading') }}</div>
        <div v-else-if="contracts.length === 0" class="pt-empty">{{ t('noOutputData') }}</div>
        <div v-for="c in contracts" :key="c.contractId" :id="`agent-card-${c.contractId}`">
          <AgentMessageCard :contract="c" />
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.pt-root { flex: 1; padding: 0; display:flex; flex-direction:column; min-height:0; }
.pt-header {
  position: sticky; top: 0; z-index: 1;
  background: var(--clr-bg); padding: 8px 0 4px;
  border-bottom: 1px solid var(--clr-hairline); margin-bottom: 6px;
}
.pt-title { font-size: var(--fs-title-3); font-weight: var(--fw-semibold); color: var(--clr-label); margin-bottom: 3px; }
.pt-meta { display: flex; flex-wrap:wrap; gap: 4px; font-size: var(--fs-caption); color: var(--clr-tertiary); align-items: center; }
.pt-meta__sep { color: var(--clr-quaternary); }
.pt-mode { text-transform: uppercase; font-size: 10px; padding: 0 5px; border-radius: 3px; background: var(--clr-fill-hover); }
.pt-agent {
  cursor:pointer; padding:1px 5px; border-radius:var(--rad-xs);
  color:var(--clr-accent); font-weight:var(--fw-medium);
  background:var(--clr-fill-hover);
}
.pt-agent:hover { background:var(--clr-hairline); }
.pt-desc { font-size: var(--fs-footnote); color: var(--clr-secondary); }
.pt-section { flex:1; min-height:0; overflow-y:auto; }
.pt-empty { font-size: var(--fs-caption); color: var(--clr-quaternary); padding: 12px 0; }
.pt-loading { font-size: var(--fs-caption); color: var(--clr-tertiary); padding: 12px 0; }
</style>
