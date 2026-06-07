<script setup lang="ts">
import {computed, onMounted, ref, watch} from 'vue'
import {useRouter} from 'vue-router'
import {useI18n} from '../../utils/i18n'
import {useMessage} from '../../utils/message'
import api from '../../api'
import {
  createSquad,
  deleteSquad,
  fetchSquads,
  generateTopology,
  type PhaseNode,
  type Squad,
  updateSquad,
} from '../../api/squad'
import PhaseFlowView from '../../components/squad-execution/PhaseFlowView.vue'
import PaginatedTable from '../../components/common/PaginatedTable.vue'

const router = useRouter()
const message = useMessage()
const { t } = useI18n()

const squads = ref<Squad[]>([])
const agents = ref<any[]>([])
const loading = ref(false)
const saving = ref(false)
const generating = ref(false)
const showModal = ref(false)
const editing = ref(false)
const searchQuery = ref('')
const modeFilter = ref('')
const sourceFilter = ref('')
const page = ref(1)
const pageSize = ref(10)
const selectedIds = ref(new Set<string>())

const allPageSelected = computed(() =>
  pagedSquads.value.length > 0 && pagedSquads.value.every(s => selectedIds.value.has(s.squadId)))

function toggleAll() {
  if (allPageSelected.value) { selectedIds.value = new Set() }
  else { pagedSquads.value.forEach(s => selectedIds.value.add(s.squadId)) }
}
function toggleOne(id: string) {
  const next = new Set(selectedIds.value)
  if (next.has(id)) { next.delete(id) } else { next.add(id) }
  selectedIds.value = next
}
async function deleteSelected() {
  const ids = [...selectedIds.value]
  if (!ids.length) return
  try {
    await api.post('/squads/batch-delete', ids)
    message.success(t('squadBatchDeleted', { n: ids.length }))
    selectedIds.value = new Set()
    await loadSquads()
  } catch (e: any) { message.error(e.response?.data?.message || t('squadBatchDeleteFailed')) }
}

const emptyPhaseStatuses = computed(() => new Map())
const emptyEdges = computed(() => [])

const form = ref({
  squadId: '',
  name: '',
  description: '',
  mode: 'SEQUENTIAL',
  phases: [] as PhaseNode[],
})

const selectedPhaseIndex = ref<number | null>(null)

function modeLabel(m: string) {
  const m2: Record<string, string> = {
    SEQUENTIAL: t('modeSequential'), PARALLEL: t('modeParallel'),
    CONDITIONAL: t('modeConditional'), HIERARCHICAL: t('modeHierarchical'),
    CONSENSUS: t('modeConsensus'),
  }
  return m2[m] || m
}

const filteredSquads = computed(() => {
  let list = squads.value
  if (searchQuery.value) {
    const q = searchQuery.value.toLowerCase()
    list = list.filter(s => s.name?.toLowerCase().includes(q) || s.description?.toLowerCase().includes(q))
  }
  if (modeFilter.value) list = list.filter(s => s.mode === modeFilter.value)
  if (sourceFilter.value) list = list.filter(s => s.source === sourceFilter.value)
  return list
})

const pagedSquads = computed(() => {
  const total = filteredSquads.value.length
  const maxPage = Math.max(1, Math.ceil(total / pageSize.value))
  if (page.value > maxPage) page.value = maxPage
  const start = (page.value - 1) * pageSize.value
  return filteredSquads.value.slice(start, start + pageSize.value)
})

const totalPages = computed(() => Math.max(1, Math.ceil(filteredSquads.value.length / pageSize.value)))

watch([searchQuery, modeFilter, sourceFilter], () => { page.value = 1 })

onMounted(async () => {
  await Promise.all([loadSquads(), loadAgents()])
})

async function loadSquads() {
  loading.value = true
  try {
    const res: any = await fetchSquads()
    squads.value = res.data?.items || res.data || []
  } catch { /* noop */ } finally { loading.value = false }
}

async function loadAgents() {
  try {
    const res: any = await api.get('/agents')
    agents.value = res.data || []
  } catch { /* noop */ }
}

function openCreateModal() {
  editing.value = false
  selectedPhaseIndex.value = null
  form.value = { squadId: '', name: '', description: '', mode: 'SEQUENTIAL', phases: [] }
  showModal.value = true
}

function editSquad(squad: Squad) {
  editing.value = true
  selectedPhaseIndex.value = null
  form.value = {
    squadId: squad.squadId,
    name: squad.name,
    description: squad.description,
    mode: squad.mode,
    phases: (squad.phases || []).map(p => ({ ...p })),
  }
  showModal.value = true
}

function addPhase() {
  const phases = form.value.phases || []
  form.value.phases = [...phases, {
    phase: phases.length,
    name: '',
    mode: 'SEQUENTIAL',
    agents: [],
    description: '',
    inputFilter: '',
    outputFilter: '',
    hitlMode: '',
    hitlAgents: [],
  }]
}

function removePhase(index: number) {
  const phases = [...(form.value.phases || [])]
  phases.splice(index, 1)
  // Re-index
  form.value.phases = phases.map((p, i) => ({ ...p, phase: i }))
  if (selectedPhaseIndex.value != null && selectedPhaseIndex.value >= phases.length) {
    selectedPhaseIndex.value = null
  }
}

function toggleAgentInPhase(phaseIndex: number, agentName: string) {
  const phases = [...(form.value.phases || [])]
  const p = { ...phases[phaseIndex] }
  const current = p.agents || []
  if (current.includes(agentName)) {
    p.agents = current.filter(a => a !== agentName)
  } else {
    p.agents = [...current, agentName]
  }
  phases[phaseIndex] = p
  form.value.phases = phases
}

function onHitlModeChange(phaseIndex: number, mode: string) {
  if (mode !== 'AGENT_LIST') {
    const phases = [...(form.value.phases || [])]
    const p = { ...phases[phaseIndex] }
    p.hitlAgents = []
    phases[phaseIndex] = p
    form.value.phases = phases
  }
}

function toggleHitlAgent(phaseIndex: number, agentName: string) {
  const phases = [...(form.value.phases || [])]
  const p = { ...phases[phaseIndex] }
  const current = p.hitlAgents || []
  if (current.includes(agentName)) {
    p.hitlAgents = current.filter(a => a !== agentName)
  } else {
    p.hitlAgents = [...current, agentName]
  }
  phases[phaseIndex] = p
  form.value.phases = phases
}

async function handleGenerate() {
  if (!form.value.description.trim()) {
    message.warning(t('squadGenDescRequired'))
    return
  }
  generating.value = true
  try {
    const res: any = await generateTopology(form.value.description.trim())
    const result = res.data || res
    if (result.mode) form.value.mode = result.mode
    if (result.phases?.length) {
      form.value.phases = result.phases.map((p: any, i: number) => ({
        phase: i, name: p.name || '', mode: p.mode || 'SEQUENTIAL',
        agents: p.agents || [], description: p.description || '',
        inputFilter: p.inputFilter || '', outputFilter: p.outputFilter || '',
        hitlMode: p.hitlMode || '', hitlAgents: p.hitlAgents || [],
      }))
      message.success(t('squadGenSuccess', { n: result.phases.length }))
    } else {
      message.warning(t('squadGenEmpty'))
    }
  } catch (e: any) {
    message.error(e.response?.data?.message || t('squadGenFailed'))
  } finally { generating.value = false }
}

async function saveSquad() {
  if (!form.value.name.trim()) {
    message.warning(t('squadNameRequiredError'))
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.value.name.trim(),
      description: form.value.description.trim(),
      mode: form.value.mode,
      phases: form.value.phases.map(p => ({
        phase: p.phase, name: p.name, mode: p.mode, agents: p.agents,
        description: p.description, inputFilter: p.inputFilter,
        outputFilter: p.outputFilter,
        hitlMode: p.hitlMode || undefined,
        hitlAgents: p.hitlAgents?.length ? p.hitlAgents : undefined,
      })),
    }
    if (editing.value) {
      await updateSquad(form.value.squadId, payload)
      message.success(t('squadUpdateSuccess'))
    } else {
      await createSquad(payload)
      message.success(t('squadCreateSuccess'))
    }
    showModal.value = false
    await loadSquads()
  } catch (e: any) {
    message.error(e.response?.data?.message || t('operationFailed'))
  } finally { saving.value = false }
}

async function toggleActive(sq: Squad) {
  try {
    await updateSquad(sq.squadId, { active: !sq.active })
    sq.active = !sq.active
    message.success(sq.active ? t('squadEnabled') : t('squadDisabled'))
  } catch (e: any) {
    message.error(e.response?.data?.message || t('operationFailed'))
  }
}

async function handleDelete(id: string) {
  try {
    await deleteSquad(id)
    message.success(t('squadDeleteSuccess'))
    await loadSquads()
  } catch (e: any) {
    message.error(e.response?.data?.message || t('squadDeleteFailed'))
  }
}
</script>

<template>
  <div class="page-layout">
    <PaginatedTable
      :current-page="page"
      :total-pages="totalPages"
      :total="filteredSquads.length"
      :page-size="pageSize"
      :page-size-options="[10, 20, 50, 100]"
      @page-change="page = $event"
      @page-size-change="pageSize = $event; page = 1"
    >
      <template #header>
        <div style="display:flex;align-items:center;gap:8px;">
          <button class="btn btn-ghost btn-sm" @click="router.push('/squads')">{{ t('squadOrchBack') }}</button>
          <span>{{ t('squadOrch') }}</span>
        </div>
        <div class="header-extra">
          <button v-if="selectedIds.size > 0" class="btn btn-sm btn-danger" style="margin-left:12px" @click="deleteSelected">
            {{ t('deleteSelectedCount', { n: selectedIds.size }) }}
          </button>
          <input v-model="searchQuery" class="input" style="width:180px" :placeholder="t('searchSquad')" />
          <select v-model="modeFilter" class="select" style="width:110px">
            <option value="">{{ t('modeCol') }}</option>
            <option value="SEQUENTIAL">{{ t('modeSequential') }}</option>
            <option value="PARALLEL">{{ t('modeParallel') }}</option>
            <option value="CONDITIONAL">{{ t('modeConditional') }}</option>
            <option value="HIERARCHICAL">{{ t('modeHierarchical') }}</option>
            <option value="CONSENSUS">{{ t('modeConsensus') }}</option>
          </select>
          <select v-model="sourceFilter" class="select" style="width:80px">
            <option value="">{{ t('allSources') }}</option>
            <option value="SYSTEM">{{ t('system') }}</option>
            <option value="USER">{{ t('user') }}</option>
          </select>
          <button class="btn btn-primary" @click="openCreateModal">{{ t('createSquad') }}</button>
        </div>
      </template>
      <template #thead>
        <tr>
          <th style="width:30px"><input type="checkbox" :checked="allPageSelected" @change="toggleAll"></th>
          <th>{{ t('nameCol') }}</th>
          <th>{{ t('squadDescPlaceholder') }}</th>
          <th>{{ t('modeCol') }}</th>
          <th>{{ t('sourceCol') }}</th>
          <th>{{ t('usageCount') }}</th>
          <th>{{ t('successRateCol') }}</th>
          <th style="width:60px">{{ t('enableToggle') }}</th>
          <th>{{ t('actionsCol') }}</th>
        </tr>
      </template>
      <template #tbody>
        <tr v-if="loading">
          <td colspan="9" class="td-empty">{{ t('loading') }}</td>
        </tr>
        <tr v-for="sq in pagedSquads" :key="sq.squadId">
          <td @click.stop><input type="checkbox" :checked="selectedIds.has(sq.squadId)" @change="toggleOne(sq.squadId)"></td>
          <td>
            <div class="td-primary">{{ sq.name }}</div>
          </td>
          <td>
            <div class="td-secondary" v-if="sq.description">{{ sq.description }}</div>
          </td>
          <td><span class="tag tag-info">{{ modeLabel(sq.mode) }}</span></td>
          <td>
            <span class="tag" :class="sq.source === 'SYSTEM' ? 'tag-info' : 'tag-success'">
              {{ sq.source === 'SYSTEM' ? t('system') : t('user') }}
            </span>
          </td>
          <td>{{ sq.usageCount || 0 }}</td>
          <td>{{ sq.successRate ? (sq.successRate * 100).toFixed(1) + '%' : '--' }}</td>
          <td class="td-actions">
            <label class="toggle-row" @click.stop>
              <input type="checkbox" :checked="sq.active" @change="toggleActive(sq)" class="toggle-row__input" />
              <span class="toggle-row__switch"></span>
            </label>
          </td>
          <td class="td-actions">
            <button class="btn btn-sm" @click="editSquad(sq)">{{ t('edit') }}</button>
            <button class="btn btn-sm btn-danger" @click="handleDelete(sq.squadId)">{{ t('delete') }}</button>
          </td>
        </tr>
        <tr v-if="!loading && !filteredSquads.length">
          <td colspan="9" class="td-empty">{{ t('noData') }}</td>
        </tr>
      </template>
    </PaginatedTable>

    <!-- 创建/编辑弹窗：DAG 编排 -->
    <Teleport to="body">
      <div v-if="showModal" class="modal-overlay" @click.self="showModal = false">
        <div class="modal" style="max-width:960px; height:85vh; display:flex; flex-direction:column; overflow:hidden;">
          <div class="modal__header">
            <span>{{ editing ? t('editSquad') : t('createSquadTitle') }}</span>
            <div style="display:flex;gap:8px;">
              <button class="btn btn-sm btn-ghost" :disabled="generating" @click="handleGenerate">
                {{ generating ? t('generating') : t('autoGenerate') }}
              </button>
              <button class="modal__close" @click="showModal = false">&times;</button>
            </div>
          </div>
          <div class="modal__body" style="display:flex;gap:16px;flex:1;min-height:0;overflow:hidden;">
            <!-- 左侧：DAG 预览 -->
            <div style="flex:1;min-width:240px;border-right:1px solid var(--clr-hairline);padding-right:8px;display:flex;flex-direction:column;min-height:0;">
              <div class="form-section-title">DAG 拓扑</div>
              <div v-if="form.phases.length" style="flex:1;min-height:0;display:flex;flex-direction:column;overflow:hidden;">
                <PhaseFlowView
                  :phases="form.phases"
                  :current-phase="null"
                  :phase-statuses="emptyPhaseStatuses"
                  :contract-edges="emptyEdges"
                  :selected-phase="selectedPhaseIndex"
                  :squad-mode="form.mode"
                  @select-phase="selectedPhaseIndex = $event"
                  @delete-phase="removePhase"
                />
              </div>
              <div v-else class="form-hint" style="text-align:center;padding:40px 0;">
                {{ t('addPhaseOrAutoGen') }}
              </div>
            </div>
            <!-- 右侧：表单 -->
            <div style="flex:1;min-width:280px;overflow-y:auto;">
              <div class="form-section-title">{{ t('basicInfo') }}</div>
              <div class="form-group">
                <label class="form-label">{{ t('squadNameRequired') }}</label>
                <input v-model="form.name" class="input" :placeholder="t('squadNamePlaceholder')" />
              </div>
              <div class="form-group">
                <label class="form-label">{{ t('squadDescPlaceholder') }}</label>
                <textarea v-model="form.description" class="input textarea" style="min-height:50px;" :placeholder="t('squadDescPlaceholder')" />
              </div>
              <div class="form-group">
                <label class="form-label">{{ t('executionMode') }}</label>
                <select v-model="form.mode" class="select" style="width:100%">
                  <option value="SEQUENTIAL">{{ t('modeSequential') }}</option>
                  <option value="PARALLEL">{{ t('modeParallel') }}</option>
                  <option value="CONDITIONAL">{{ t('modeConditional') }}</option>
                  <option value="HIERARCHICAL">{{ t('modeHierarchical') }}</option>
                  <option value="CONSENSUS">{{ t('modeConsensus') }}</option>
                </select>
              </div>

              <!-- 选中阶段编辑 -->
              <template v-if="selectedPhaseIndex != null && form.phases[selectedPhaseIndex]">
                <div class="form-section-title">{{ t('phaseConfig') }} {{ selectedPhaseIndex + 1 }}
                  <button class="btn btn-danger btn-sm" @click="removePhase(selectedPhaseIndex!)">{{ t('remove') }}</button>
                </div>
                <div class="form-group">
                  <label class="form-label">{{ t('phaseName') }}</label>
                  <input v-model="form.phases[selectedPhaseIndex].name" class="input" :placeholder="t('phaseNamePlaceholder')" />
                </div>
                <div class="form-group">
                  <label class="form-label">{{ t('phaseDescPlaceholder') }}</label>
                  <input v-model="form.phases[selectedPhaseIndex].description" class="input" :placeholder="t('phaseDescPlaceholder')" />
                </div>
                <div class="form-group">
                  <label class="form-label">{{ t('executionMode') }}</label>
                  <select v-model="form.phases[selectedPhaseIndex].mode" class="select" style="width:100%">
                    <option value="SEQUENTIAL">{{ t('modeSequential') }}</option>
                    <option value="PARALLEL">{{ t('modeParallel') }}</option>
                    <option value="CONDITIONAL">{{ t('modeConditional') }}</option>
                    <option value="HIERARCHICAL">{{ t('modeHierarchical') }}</option>
                    <option value="CONSENSUS">{{ t('modeConsensus') }}</option>
                  </select>
                </div>
                <div class="form-group">
                  <label class="form-label">{{ t('participatingAgents') }}</label>
                  <div v-if="agents.length" class="skill-selector">
                    <label v-for="a in agents" :key="a.agentName" class="skill-checkbox">
                      <input type="checkbox"
                        :checked="(form.phases[selectedPhaseIndex].agents || []).includes(a.agentName)"
                        @change="toggleAgentInPhase(selectedPhaseIndex!, a.agentName)" />
                      <span>{{ a.displayName || a.agentName }}</span>
                    </label>
                  </div>
                  <div v-else class="form-hint">{{ t('noAgentsAvailable') }}</div>
                </div>
                <div class="form-group">
                  <label class="form-label">{{ t('hitlRequired') }}</label>
                  <select v-model="form.phases[selectedPhaseIndex].hitlMode" class="select" style="width:100%"
                    @update:model-value="onHitlModeChange(selectedPhaseIndex!, $event)">
                    <option value="">{{ t('hitlModeNone') }}</option>
                    <option value="POST">{{ t('hitlModePost') }}</option>
                    <option value="PRE">{{ t('hitlModePre') }}</option>
                    <option value="ALL">{{ t('hitlModeAll') }}</option>
                    <option value="AGENT_LIST">{{ t('hitlModeAgentList') }}</option>
                  </select>
                </div>
                <div v-if="form.phases[selectedPhaseIndex].hitlMode === 'AGENT_LIST'" class="form-group">
                  <label class="form-label">{{ t('hitlAgentSelect') }}</label>
                  <div v-if="agents.length" class="skill-selector">
                    <label v-for="a in agents" :key="a.agentName" class="skill-checkbox">
                      <input type="checkbox"
                        :checked="(form.phases[selectedPhaseIndex].hitlAgents || []).includes(a.agentName)"
                        @change="toggleHitlAgent(selectedPhaseIndex!, a.agentName)" />
                      <span>{{ a.displayName || a.agentName }}</span>
                    </label>
                  </div>
                  <div v-else class="form-hint">{{ t('noAgentsAvailable') }}</div>
                </div>
              </template>

              <button class="btn btn-ghost btn-sm" style="width:100%;margin-top:8px;" @click="addPhase">{{ t('addPhase') }}</button>
            </div>
          </div>
          <div class="modal__footer">
            <button class="btn btn-ghost" @click="showModal = false">{{ t('cancel') }}</button>
            <button class="btn btn-primary" :disabled="saving" @click="saveSquad">{{ saving ? t('saving') : t('save') }}</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.header-extra { display: flex; gap: 8px; align-items: center; }
.td-primary { font-weight: var(--fw-medium); color: var(--clr-label); }
.td-secondary { font-size: var(--fs-footnote); color: var(--clr-tertiary); margin-top: 2px; max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.td-empty { text-align: center; padding: 24px; color: var(--clr-tertiary); }
.td-actions { white-space: nowrap; }

.form-section-title {
  font-size: var(--fs-footnote);
  font-weight: var(--fw-semibold);
  color: var(--clr-secondary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin: 12px 0 6px;
  padding-bottom: 4px;
  border-bottom: 1px solid var(--clr-hairline);
}
.form-section-title:first-child { margin-top: 0; }
.form-group { margin-bottom: 8px; }
.form-label { display: block; font-size: var(--fs-caption); font-weight: var(--fw-medium); color: var(--clr-secondary); margin-bottom: 4px; }
.form-hint { font-size: var(--fs-caption); color: var(--clr-tertiary); }
.skill-selector { max-height: 140px; overflow-y: auto; border: 1px solid var(--clr-separator); border-radius: var(--rad-md); padding: 4px; }
.skill-checkbox { display: flex; align-items: center; gap: 6px; padding: 4px 8px; font-size: var(--fs-callout); cursor: pointer; }
.skill-checkbox:hover { background: var(--clr-fill); }
</style>
