<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from '../../utils/i18n'
import { renderMarkdown } from '../../utils/markdown'
import { useSquadExecutionStore } from '../../stores/squadExecution'
import type { ContractEdge } from '../../stores/squadExecution'
import type { Contract } from '../../api/squad'
import PhaseFlowView from '../../components/squad-execution/PhaseFlowView.vue'
import AgentOutputPanel from '../../components/squad-execution/AgentOutputPanel.vue'
import AgentMessageCard from '../../components/squad-execution/AgentMessageCard.vue'
import HitlDialog from '../../components/squad-execution/HitlDialog.vue'
import ExecutionStatusBadge from '../../components/squad-execution/ExecutionStatusBadge.vue'
import SseStatusIndicator from '../../components/squad-execution/SseStatusIndicator.vue'

const route = useRoute()
const router = useRouter()
const store = useSquadExecutionStore()
const { t } = useI18n()

const execId = computed(() => route.params.execId as string)
const retrying = ref(false)
const hoveredEdge = ref<ContractEdge | null>(null)
const selectedEdge = ref<ContractEdge | null>(null)
const descriptionExpanded = ref(false)

const renderedDescription = computed(() => {
  if (!store.currentExecution?.taskDescription) return ''
  return renderMarkdown(store.currentExecution.taskDescription)
})

/** 从 store 的 contracts Map 中取出选中阶段的契约列表。 */
const selectedContracts = computed(() => {
  if (store.selectedPhase == null) return []
  return store.contracts.get(store.selectedPhase) || []
})

/** 选中阶段的详情（用于 AgentOutputPanel）。 */
const selectedPhaseInfo = computed(() => {
  if (store.selectedPhase == null) return null
  const p = store.phases.find(ph => ph.phase === store.selectedPhase)
  if (!p) return null
  return {
    phase: p.phase,
    name: p.name || `阶段 ${p.phase}`,
    mode: p.mode || store.currentExecution?.squadMode || 'SEQUENTIAL',
    agents: p.agents || [],
    description: '',
  }
})

/** 流入选中阶段的边（含契约）。 */
const incomingEdges = computed(() => {
  if (store.selectedPhase == null) return []
  return store.contractEdges.filter(e => e.toPhase === store.selectedPhase)
})

/** 从选中阶段流出的边（含契约）。 */
const outgoingEdges = computed(() => {
  if (store.selectedPhase == null) return []
  return store.contractEdges.filter(e => e.fromPhase === store.selectedPhase)
})

/** 激活的边（hover > selected），用于右侧契约面板展示。 */
const activeEdge = computed(() => hoveredEdge.value || selectedEdge.value)
const activeEdgeContracts = computed<Contract[]>(() => {
  return activeEdge.value?.contracts || []
})

const currentPhaseInfo = computed(() => {
  if (!store.currentExecution) return null
  const cp = store.currentExecution.currentPhase
  if (cp == null) return null
  const p = store.phases.find(ph => ph.phase === cp)
  if (!p) return null
  return { name: p.name || `阶段 ${cp}`, mode: p.mode || store.currentExecution?.squadMode || 'SEQUENTIAL', agents: p.agents || [] }
})

const hitlResolved = ref(false)
const hitlActionText = ref('')
watch(() => store.hitlReview, (r) => {
  if (!r) return
  hitlResolved.value = false; hitlActionText.value = ''
})
function onHitlResolved(action: string) {
  hitlResolved.value = true
  hitlActionText.value = action
  store.hitlReview = null
  store.startMonitor(execId.value)
}

async function handleRetry() {
  retrying.value = true
  await store.retry(execId.value)
  retrying.value = false
}

onMounted(() => store.startMonitor(execId.value))
onUnmounted(() => store.stopMonitor())
</script>

<template>
  <div class="em-root">
    <div class="em-top">
      <div class="em-top__l">
        <button class="em-back" @click="router.push('/squads')">← {{ t('squadHome') }}</button>
        <span v-if="store.currentExecution" class="em-title">{{ store.currentExecution.squadName || 'Squad' }} · #{{ execId.slice(0,8) }}</span>
        <ExecutionStatusBadge v-if="store.currentExecution" :status="store.currentExecution.status" />
      </div>
      <div class="em-top__r">
        <SseStatusIndicator :status="store.sseStatus" />
        <button v-if="store.currentExecution?.status==='FAILED'" class="btn btn-primary btn-sm" :disabled="retrying" @click="handleRetry">
          {{ retrying ? t('retrying') : t('reExecute') }}
        </button>
      </div>
    </div>

    <div v-if="store.currentExecution" class="em-body">
      <div class="em-left">
          <div class="em-left__label">{{ t('dagPhaseLabel') }}</div>
          <div class="em-left__body">
            <PhaseFlowView
              :phases="store.phases"
              :current-phase="store.currentExecution.currentPhase"
              :phase-statuses="store.phaseStatuses"
              :contract-edges="store.contractEdges"
              :selected-phase="store.selectedPhase"
              :selected-edge="selectedEdge"
              :squad-mode="store.currentExecution.squadMode"
              :readonly="true"
              @select-phase="store.selectedPhase = $event; selectedEdge = null"
              @select-edge="selectedEdge = $event"
              @hover-edge="hoveredEdge = $event"
            />
          </div>
        </div>
        <div class="em-right">
          <!-- 任务描述 — 标题吸顶，点击切换展开/折叠，内容区域可滚动 -->
          <div class="em-desc" :class="{ 'em-desc--open': descriptionExpanded }">
            <div class="em-desc__bar" @click="descriptionExpanded = !descriptionExpanded">
              <span class="em-desc__label">{{ t('taskDescription') }}</span>
              <span class="em-desc__toggle">{{ descriptionExpanded ? t('collapse') : t('expand') }}</span>
            </div>
            <div v-show="descriptionExpanded" class="em-desc__body"><div class="em-desc__content" v-html="renderedDescription"></div></div>
          </div>
          <div class="em-separator"></div>
          <!-- 当前阶段 -->
          <div v-if="currentPhaseInfo" class="em-phase-section">
            <div class="em-phase-section__row">
              <span class="em-phase-section__key">{{ t('currentPhaseCol') }}:</span>
              <span class="em-phase-section__val">{{ currentPhaseInfo.name }}</span>
            </div>
            <div class="em-phase-section__row">
              <span class="em-phase-section__key">{{ t('modeCol') }}:</span>
              <span class="em-phase-section__val">{{ currentPhaseInfo.mode }} · {{ currentPhaseInfo.agents.length }} Agent</span>
            </div>
          </div>
          <!-- 下方 1:1 分栏：Agent 输出 | 阶段间契约 -->
          <div class="em-split">
            <div class="em-split__top">
              <!-- 选中阶段的 Agent 输出 -->
              <AgentOutputPanel
                v-if="selectedPhaseInfo"
                :phase="selectedPhaseInfo"
                :contracts="selectedContracts"
                :loading="store.monitorLoading"
              />
              <div v-else class="em-split__empty">{{ t('noData') }}</div>
            </div>
            <div class="em-split__divider"></div>
            <div class="em-split__bottom">
              <!-- 阶段间契约 -->
              <div v-if="store.selectedPhase != null && (incomingEdges.length || outgoingEdges.length || activeEdgeContracts.length)" class="em-contracts">
                <div class="em-contracts__label">{{ t('phaseContracts') }}</div>
                <template v-if="activeEdge">
                  <div class="em-contracts__group">
                    <div class="em-contracts__dir">阶段 {{ activeEdge.fromPhase }} → 阶段 {{ activeEdge.toPhase }}</div>
                    <AgentMessageCard v-for="c in activeEdgeContracts" :key="c.contractId" :contract="c" />
                  </div>
                </template>
                <template v-else>
                  <div v-for="edge in incomingEdges" :key="`in-${edge.fromPhase}-${edge.toPhase}`" class="em-contracts__group">
                    <div class="em-contracts__dir">← {{ t('fromPhase') }} {{ edge.fromPhase }}</div>
                    <AgentMessageCard v-for="c in edge.contracts" :key="c.contractId" :contract="c" />
                  </div>
                  <div v-for="edge in outgoingEdges" :key="`out-${edge.fromPhase}-${edge.toPhase}`" class="em-contracts__group">
                    <div class="em-contracts__dir">→ {{ t('toPhase') }} {{ edge.toPhase }}</div>
                    <AgentMessageCard v-for="c in edge.contracts" :key="c.contractId" :contract="c" />
                  </div>
                </template>
              </div>
              <div v-else class="em-split__empty">{{ t('noData') }}</div>
            </div>
          </div>
          <!-- HITL 审核（内联，非弹窗） -->
          <div v-if="store.hitlReview && store.currentExecution?.status === 'HITL_PENDING'" class="em-hitl-section">
            <div class="em-hitl-section__label">{{ t('hitlInlineReview') }}</div>
            <HitlDialog
              :review="store.hitlReview" :exec-id="execId"
              @resolved="onHitlResolved('approved')"
            />
          </div>
          <div v-else-if="hitlResolved" class="em-hitl-resolved">
            <span class="em-hitl-resolved__icon">{{ hitlActionText === 'approved' ? '✓' : '✗' }}</span>
            <span>{{ hitlActionText === 'approved' ? t('reviewApproved') : t('reviewRejected') }}</span>
          </div>
        </div>
      </div>
    </div>
    <div v-if="store.monitorLoading && !store.currentExecution" class="em-loading">{{ t('loading') }}</div>
</template>

<style scoped>
.em-root { display:flex; flex-direction:column; height:100%; background:var(--clr-bg-page); }
.em-top { display:flex; align-items:center; justify-content:space-between; padding:var(--sp-unit-1_5) var(--sp-unit-2); border-bottom:1px solid var(--clr-hairline); background:var(--clr-bg); }
.em-top__l { display:flex; align-items:center; gap:10px; }
.em-top__r { display:flex; align-items:center; gap:10px; }
.em-back { font-size:var(--fs-callout); color:var(--clr-accent); background:none; border:none; cursor:pointer; padding:4px 0; }
.em-title { font-size:var(--fs-callout); font-weight:var(--fw-semibold); color:var(--clr-label); }
.em-body { display:flex; flex:1; overflow:hidden; }

/* 左侧 DAG */
.em-left { width:30%; min-width:220px; max-width:320px; border-right:1px solid var(--clr-hairline); overflow-y:auto; padding:var(--sp-unit-1_5); background:var(--clr-bg); }
.em-left__label { font-size:var(--fs-caption); font-weight:var(--fw-semibold); color:var(--clr-secondary); text-transform:uppercase; letter-spacing:0.04em; margin-bottom:8px; }
.em-left__body { border:1px solid var(--clr-hairline); border-radius:var(--rad-md); overflow:hidden; }

/* 右侧 */
.em-right { flex:1; display:flex; flex-direction:column; padding:var(--sp-unit-1_5) var(--sp-unit-2) var(--sp-unit-2); gap:0; min-height:0; background:var(--clr-bg); }

/* 任务描述 — 标题吸顶，可折叠 */
.em-desc {
  display:flex; flex-direction:column; margin-bottom:4px;
  border:1px solid var(--clr-hairline); border-radius:var(--rad-md);
  background:var(--clr-bg-secondary);
}
.em-desc--open { flex:1; min-height:0; }
.em-desc__bar {
  display:flex; align-items:center; justify-content:space-between;
  padding:6px 10px; cursor:pointer; user-select:none;
  background:var(--clr-bg-secondary); border-radius:var(--rad-md);
  flex-shrink:0;
}
.em-desc--open .em-desc__bar {
  border-radius:var(--rad-md) var(--rad-md) 0 0;
  border-bottom:1px solid var(--clr-hairline);
}
.em-desc__label { font-size:var(--fs-callout); font-weight:var(--fw-semibold); color:var(--clr-label); }
.em-desc__toggle { font-size:var(--fs-footnote); font-weight:var(--fw-medium); color:var(--clr-accent); }
.em-desc__body { flex:1; min-height:0; overflow-y:auto; padding:8px 10px; }
.em-desc__content { font-size:var(--fs-callout); line-height:1.65; color:var(--clr-label); }
.em-desc__content p { margin:0; }
.em-desc__content p + p { margin-top:0.35em; }
.em-desc__content code {
  font-size:0.85em; padding:1px 5px; border-radius:var(--rad-xs);
  background:var(--clr-fill-hover); color:var(--clr-label);
}
.em-desc__content pre {
  margin:4px 0; padding:6px 8px; border-radius:var(--rad-sm);
  background:var(--clr-fill); overflow-x:auto;
}
.em-separator { height:1px; background:var(--clr-hairline); margin:var(--sp-unit-1_5) 0; }

/* 当前阶段 — 信息卡 */
.em-phase-section {
  padding:8px 10px; margin-bottom:2px;
  border-radius:var(--rad-md); border:1px solid var(--clr-hairline);
  background:var(--clr-bg);
  border-left:3px solid var(--clr-accent);
}
.em-phase-section__row { display:flex; gap:8px; font-size:var(--fs-callout); padding:1px 0; }
.em-phase-section__key { color:var(--clr-tertiary); min-width:72px; white-space:nowrap; }
.em-phase-section__val { color:var(--clr-label); }

/* 左右 1:1 分栏 */
.em-split { flex:1; display:flex; flex-direction:row; min-height:0; margin-top:var(--sp-unit-1); }
.em-split__top { flex:1; min-width:0; display:flex; flex-direction:column; }
.em-split__bottom { flex:1; min-width:0; overflow-y:auto; }
.em-split__divider { width:1px; background:var(--clr-hairline); margin:0 var(--sp-unit-1); flex-shrink:0; }
.em-split__empty { padding:12px 0; font-size:var(--fs-caption); color:var(--clr-quaternary); text-align:center; }

/* 阶段间契约 */
.em-contracts__label {
  display:inline-block; margin-bottom:8px; padding:2px 8px;
  font-size:var(--fs-caption); font-weight:var(--fw-semibold);
  color:var(--clr-label); background:var(--clr-fill-hover);
  border-radius:var(--rad-sm);
}
.em-contracts__group { margin-bottom:10px; }
.em-contracts__dir {
  display:inline-flex; align-items:center; gap:4px;
  font-size:var(--fs-caption); font-weight:var(--fw-medium);
  color:var(--clr-secondary); margin-bottom:5px; padding:2px 6px;
  background:var(--clr-fill); border-radius:var(--rad-xs);
}

/* HITL 审核区域 */
.em-hitl-section { margin-top:10px; padding-top:8px; border-top:1px solid var(--clr-hairline); }
.em-hitl-section__label {
  display:inline-block; margin-bottom:8px; padding:2px 8px;
  font-size:var(--fs-caption); font-weight:var(--fw-semibold);
  color:var(--clr-label); background:var(--clr-fill-hover);
  border-radius:var(--rad-sm);
}
.em-hitl-resolved { margin-top:8px; padding:8px 12px; border-radius:var(--rad-sm); background:var(--clr-fill); font-size:var(--fs-callout); color:var(--clr-secondary); display:flex; gap:6px; align-items:center; }
.em-hitl-resolved__icon { font-weight:var(--fw-bold); color:var(--clr-green); }

.em-loading { display:flex; align-items:center; justify-content:center; flex:1; color:var(--clr-tertiary); }
</style>
