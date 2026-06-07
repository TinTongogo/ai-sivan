<script setup lang="ts">
import {computed, onMounted, ref, watch} from 'vue'
import {useI18n} from '../../utils/i18n'
import {useMessage} from '../../utils/message'
import api from '../../api'
import PaginatedTable from '../../components/common/PaginatedTable.vue'

const { t } = useI18n()

interface Agent {
  agentId: string
  agentName: string
  displayName: string
  description: string
  category: string
  systemPrompt: string
  craftDeclaration: string
  skillIds: string[]
  agentType: string
  status: string
  version: number
  usageCount: number
  lastUsedAt: string | null
  createdAt: string
  updatedAt: string
}

interface Skill {
  skillId: string
  displayName: string
  skillCode: string
  category: string
  description: string
}

const message = useMessage()
const agents = ref<Agent[]>([])
const skills = ref<Skill[]>([])
const loading = ref(false)
const showModal = ref(false)
const editing = ref(false)
const searchQuery = ref('')
const categoryFilter = ref('')
const agentTypeFilter = ref('')
const saving = ref(false)
const viewOnly = ref(false)
const page = ref(1)
const pageSize = ref(10)
const selectedIds = ref(new Set<string>())

const allPageSelected = computed(() =>
  pagedAgents.value.length > 0 && pagedAgents.value.every(a => selectedIds.value.has(a.agentId)))

function toggleAll() {
  if (allPageSelected.value) { selectedIds.value = new Set() }
  else { pagedAgents.value.forEach(a => selectedIds.value.add(a.agentId)) }
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
    await api.post('/agents/batch-delete', ids)
    message.success(t('agentBatchDeleted', { n: ids.length }))
    selectedIds.value = new Set()
    await fetchAgents()
  } catch (e: any) { message.error(e.response?.data?.message || t('agentBatchDeleteFailed')) }
}

const form = ref<Partial<Agent>>({
  agentName: '',
  displayName: '',
  description: '',
  category: '',
  systemPrompt: '',
  craftDeclaration: '',
  skillIds: [],
  status: 'ACTIVE',
})

// 技能多选
function toggleSkill(skillId: string) {
  const list = form.value.skillIds || []
  if (list.includes(skillId)) {
    form.value.skillIds = list.filter(id => id !== skillId)
  } else {
    form.value.skillIds = [...list, skillId]
  }
}

const agentTypes = computed(() => {
  const set = new Set(agents.value.map(a => a.agentType).filter(Boolean))
  return [...set].sort()
})

function agentTypeLabel(type: string | null | undefined): string {
  const key = type?.toUpperCase() === 'SYSTEM' ? 'system'
    : type?.toUpperCase() === 'DYNAMIC' ? 'dynamic'
    : 'user'
  return t(key)
}

function agentTypeTagClass(type: string | null | undefined): string {
  const u = type?.toUpperCase()
  if (u === 'SYSTEM') return 'tag-info'
  if (u === 'DYNAMIC') return 'tag-warning'
  return 'tag-success'
}

const agentCategories = computed(() => {
  const set = new Set(agents.value.map(a => a.category).filter(Boolean))
  return [...set].sort()
})

const filteredAgents = computed(() => {
  let list = agents.value
  if (categoryFilter.value) {
    list = list.filter(a => a.category === categoryFilter.value)
  }
  if (agentTypeFilter.value) {
    list = list.filter(a => a.agentType === agentTypeFilter.value)
  }
  if (searchQuery.value) {
    const q = searchQuery.value.toLowerCase()
    list = list.filter(a =>
        a.displayName?.toLowerCase().includes(q) ||
        a.agentName?.toLowerCase().includes(q) ||
        a.description?.toLowerCase().includes(q)
    )
  }
  return list
})

const pagedAgents = computed(() => {
  const total = filteredAgents.value.length
  const maxPage = Math.max(1, Math.ceil(total / pageSize.value))
  if (page.value > maxPage) page.value = maxPage
  const start = (page.value - 1) * pageSize.value
  return filteredAgents.value.slice(start, start + pageSize.value)
})

const totalPages = computed(() => Math.max(1, Math.ceil(filteredAgents.value.length / pageSize.value)))

watch([searchQuery, categoryFilter, agentTypeFilter], () => { page.value = 1 })

onMounted(async () => {
  await Promise.all([fetchAgents(), fetchSkills()])
})

async function fetchAgents() {
  loading.value = true
  try {
    const res: any = await api.get('/agents')
    agents.value = res.data || []
  } catch { /* noop */
  } finally {
    loading.value = false
  }
}

async function fetchSkills() {
  try {
    const res: any = await api.get('/skills')
    skills.value = res.data || []
  } catch { /* noop */
  }
}

function openCreateModal() {
  editing.value = false
  form.value = {
    agentName: '', displayName: '', description: '', category: '',
    systemPrompt: '', craftDeclaration: '', skillIds: [], status: 'ACTIVE',
  }
  showModal.value = true
}

function editAgent(agent: Agent) {
  editing.value = true
  viewOnly.value = false
  form.value = {
    agentId: agent.agentId,
    agentName: agent.agentName,
    displayName: agent.displayName,
    description: agent.description,
    category: agent.category,
    systemPrompt: agent.systemPrompt,
    craftDeclaration: agent.craftDeclaration,
    skillIds: agent.skillIds || [],
    status: agent.status,
  }
  showModal.value = true
}

function viewAgent(agent: Agent) {
  editing.value = false
  viewOnly.value = true
  form.value = {
    agentId: agent.agentId,
    agentName: agent.agentName,
    displayName: agent.displayName,
    description: agent.description,
    category: agent.category,
    systemPrompt: agent.systemPrompt,
    craftDeclaration: agent.craftDeclaration,
    skillIds: agent.skillIds || [],
    status: agent.status,
  }
  showModal.value = true
}

async function saveAgent() {
  if (!form.value.agentName || !form.value.displayName || !form.value.description || !form.value.systemPrompt) {
    message.warning(t('agentNameAndCodeRequired'))
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await api.put(`/agents/${form.value.agentId}`, form.value)
      message.success(t('updateSuccess'))
    } else {
      await api.post('/agents', form.value)
      message.success(t('createSuccess'))
    }
    showModal.value = false
    await fetchAgents()
  } catch (e: any) {
    message.error(e.response?.data?.message || t('operationFailed'))
  } finally {
    saving.value = false
  }
}

async function deleteAgent(id: string) {
  try {
    await api.delete(`/agents/${id}`)
    message.success(t('agentDeleteSuccess'))
    await fetchAgents()
  } catch (e: any) {
    message.error(e.response?.data?.message || t('agentDeleteFailed'))
  }
}

async function toggleStatus(agent: Agent) {
  const newStatus = agent.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  try {
    await api.put(`/agents/${agent.agentId}`, {status: newStatus})
    message.success(newStatus === 'ACTIVE' ? t('enabled') : t('disabled'))
    await fetchAgents()
  } catch (e: any) {
    message.error(e.response?.data?.message || t('operationFailed'))
  }
}
</script>

<template>
  <div style="padding: 24px; display: flex; flex-direction: column; gap: 16px; flex: 1; min-height: 0;">
    <PaginatedTable
      :current-page="page"
      :total-pages="totalPages"
      :total="filteredAgents.length"
      :page-size="pageSize"
      :page-size-options="[10, 20, 50, 100]"
      @page-change="page = $event"
      @page-size-change="pageSize = $event; page = 1"
    >
      <template #header>
        <span>{{ t('agentManagement') }}</span>
        <div class="header-extra">
          <button v-if="selectedIds.size > 0" class="btn btn-sm btn-danger" @click="deleteSelected">
            {{ t('deleteSelectedCount', { n: selectedIds.size }) }}
          </button>
          <input v-model="searchQuery" class="input" style="width:200px" :placeholder="t('searchAgent')"/>
          <select v-model="categoryFilter" class="select" style="width:120px">
            <option value="">{{ t('allCategories') }}</option>
            <option v-for="c in agentCategories" :key="c" :value="c">{{ c }}</option>
          </select>
          <select v-model="agentTypeFilter" class="select" style="width:100px">
            <option value="">{{ t('allTypes') }}</option>
            <option v-for="typeItem in agentTypes" :key="typeItem" :value="typeItem">{{ agentTypeLabel(typeItem) }}</option>
          </select>
          <button class="btn btn-primary" @click="openCreateModal">{{ t('createAgent') }}</button>
        </div>
      </template>
      <template #thead>
        <tr>
          <th style="width:30px"><input type="checkbox" :checked="allPageSelected" @change="toggleAll"></th>
          <th>{{ t('nameCol') }}</th>
          <th>{{ t('identifier') }}</th>
          <th>{{ t('typeCol') }}</th>
          <th>{{ t('skills') }}</th>
          <th>{{ t('version') }}</th>
          <th>{{ t('usageCount') }}</th>
          <th>{{ t('status') }}</th>
          <th>{{ t('actionsCol') }}</th>
        </tr>
      </template>
      <template #tbody>
        <tr v-if="loading">
          <td colspan="9" class="td-empty">{{ t('loading') }}</td>
        </tr>
        <tr v-for="agent in pagedAgents" :key="agent.agentId">
          <td @click.stop><input type="checkbox" :checked="selectedIds.has(agent.agentId)" @change="toggleOne(agent.agentId)"></td>
          <td>
            <div class="td-primary td-link" @click="viewAgent(agent)">{{ agent.displayName }}</div>
            <div class="td-secondary" v-if="agent.description">{{ agent.description }}</div>
          </td>
          <td><code class="inline-code">{{ agent.agentName }}</code></td>
          <td>
              <span class="tag" :class="agentTypeTagClass(agent.agentType)">
                {{ agentTypeLabel(agent.agentType) }}
              </span>
          </td>
          <td>
              <span v-if="agent.skillIds?.length" class="tag-list">
                <span v-for="sid in agent.skillIds.slice(0, 2)" :key="sid" class="tag tag-ghost">
                  {{ skills.find(s => s.skillId === sid)?.displayName || sid.slice(0, 8) }}
                </span>
                <span v-if="agent.skillIds.length > 2" class="tag-more">+{{ agent.skillIds.length - 2 }}</span>
              </span>
          </td>
          <td>v{{ agent.version }}</td>
          <td>{{ agent.usageCount }}</td>
          <td>
            <button class="tag" :class="agent.status === 'ACTIVE' ? 'tag-success' : 'tag-warning'"
                    style="cursor:pointer;border:none;font-family:inherit;" @click="toggleStatus(agent)">
              {{ agent.status === 'ACTIVE' ? t('enabled') : t('disabled') }}
            </button>
          </td>
          <td class="td-actions">
            <button class="btn btn-sm" @click="viewAgent(agent)">{{ t('view') }}</button>
            <button v-if="agent.agentType !== 'SYSTEM'" class="btn btn-sm" @click="editAgent(agent)">{{ t('edit') }}</button>
            <button class="btn btn-sm btn-danger" @click="deleteAgent(agent.agentId)">{{ t('delete') }}</button>
          </td>
        </tr>
        <tr v-if="!loading && !filteredAgents.length">
          <td colspan="9" class="td-empty">{{ t('noData') }}</td>
        </tr>
      </template>
    </PaginatedTable>

    <!-- 创建/编辑弹窗 -->
    <Teleport to="body">
      <div v-if="showModal" class="modal-overlay" @click.self="showModal = false">
        <div class="modal" style="max-width:720px;">
          <div class="modal__header">
            <span>{{ viewOnly ? t('viewAgent') : editing ? t('editAgent') : t('createAgentTitle') }}</span>
            <button class="modal__close" @click="showModal = false">&times;</button>
          </div>
          <div class="modal__body">
            <!-- 基础信息 -->
            <div class="form-section-title">{{ t('basicInfo') }}</div>
            <div class="form-row">
              <div class="form-group" style="flex:1;">
                <label class="form-label">{{ t('identifierRequired') }}</label>
                <input v-model="form.agentName" class="input" :placeholder="t('agentNamePlaceholder')" :disabled="editing || viewOnly"/>
              </div>
              <div class="form-group" style="flex:1;">
                <label class="form-label">{{ t('nameRequired') }}</label>
                <input v-model="form.displayName" class="input" :placeholder="t('displayNamePlaceholder')" :disabled="viewOnly"/>
              </div>
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('craftDeclaration') }}</label>
              <textarea v-model="form.craftDeclaration" class="input textarea" rows="3"
                        :placeholder="t('craftDeclarationPlaceholder')" :disabled="viewOnly"></textarea>
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('descriptionRequired') }}</label>
              <textarea v-model="form.description" class="input textarea" style="min-height:60px;"
                        :placeholder="t('agentDescPlaceholder')" :disabled="viewOnly"></textarea>
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('category') }}</label>
              <input v-model="form.category" class="input" :placeholder="t('categoryPlaceholder')" :disabled="viewOnly"/>
            </div>

            <!-- 提示词 -->
            <div class="form-section-title">{{ t('promptConfig') }}</div>
            <div class="form-group">
              <label class="form-label">{{ t('systemPromptRequired') }}</label>
              <textarea v-model="form.systemPrompt" class="input textarea" rows="5"
                        :placeholder="t('systemPromptPlaceholder')" :disabled="viewOnly"></textarea>
            </div>

            <!-- 能力配置 -->
            <div class="form-section-title">{{ t('capabilityConfig') }}</div>
            <div class="form-group">
              <label class="form-label">{{ t('bindSkills') }} <span class="form-hint-inline">{{ t('bindSkillsHint') }}</span></label>
              <div class="skill-selector" v-if="skills.length">
                <label v-for="skill in skills" :key="skill.skillId" class="skill-checkbox" :class="{ 'is-disabled': viewOnly }">
                  <input type="checkbox" :checked="(form.skillIds || []).includes(skill.skillId)"
                         @change="toggleSkill(skill.skillId)" :disabled="viewOnly"/>
                  <div class="skill-checkbox__info">
                    <span class="skill-checkbox__name">{{ skill.displayName || skill.skillCode }}</span>
                    <span v-if="skill.description" class="skill-checkbox__desc">{{ skill.description }}</span>
                  </div>
                  <span v-if="skill.category" class="tag tag-info"
                        style="margin-left:auto;flex-shrink:0;">{{ skill.category }}</span>
                </label>
              </div>
              <div v-else class="form-hint">{{ t('noSkillsAvailable') }}</div>
            </div>
          </div>
          <div class="modal__footer">
            <template v-if="viewOnly">
              <button class="btn btn-primary" @click="showModal = false">{{ t('close') }}</button>
            </template>
            <template v-else>
              <button class="btn btn-ghost" @click="showModal = false">{{ t('cancel') }}</button>
              <button class="btn btn-primary" :disabled="saving" @click="saveAgent">{{
                  saving ? t('saving') : t('save')
                }}
              </button>
            </template>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.header-extra {
  display: flex;
  gap: 8px;
  align-items: center;
}

.form-row {
  display: flex;
  gap: 12px;
}

.form-section-title {
  font-size: var(--fs-footnote);
  font-weight: var(--fw-semibold);
  color: var(--clr-secondary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin: 16px 0 8px;
  padding-bottom: 4px;
  border-bottom: 1px solid var(--clr-hairline);
}

.form-section-title:first-child {
  margin-top: 0;
}

.td-primary {
  font-weight: var(--fw-medium);
  color: var(--clr-label);
}
.td-link {
  cursor: pointer;
  transition: color var(--dur-fast);
}
.td-link:hover {
  color: var(--clr-accent);
}

.td-secondary {
  font-size: var(--fs-footnote);
  color: var(--clr-tertiary);
  margin-top: 2px;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inline-code {
  font-family: var(--ff-mono);
  font-size: 0.9em;
  padding: 1px 6px;
  border-radius: var(--rad-xs);
  background: var(--clr-bg-secondary);
  color: var(--clr-label);
}

.tag-list {
  display: flex;
  gap: 3px;
  flex-wrap: wrap;
  align-items: center;
}

.tag-ghost {
  background: var(--clr-bg-secondary);
  color: var(--clr-secondary);
}

.tag-more {
  font-size: var(--fs-footnote);
  color: var(--clr-tertiary);
}

/* 技能选择器 */
.skill-selector {
  max-height: 280px;
  overflow-y: auto;
  border: 1px solid var(--clr-separator);
  border-radius: var(--rad-md);
  padding: 4px;
}

.skill-checkbox {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px;
  border-radius: var(--rad-md);
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease-out);
  font-size: var(--fs-callout);
}

.skill-checkbox:hover {
  background: var(--clr-fill);
}
.skill-checkbox.is-disabled {
  cursor: default;
  opacity: 0.75;
}
.skill-checkbox.is-disabled:hover {
  background: transparent;
}

.skill-checkbox input {
  accent-color: var(--clr-accent);
  margin-top: 3px;
}

.skill-checkbox__info {
  flex: 1;
  min-width: 0;
}

.skill-checkbox__name {
  font-weight: var(--fw-medium);
  color: var(--clr-label);
}

.skill-checkbox__desc {
  font-size: var(--fs-footnote);
  color: var(--clr-tertiary);
  margin-top: 1px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.form-hint-inline {
  font-size: var(--fs-footnote);
  color: var(--clr-quaternary);
  font-weight: var(--fw-regular);
}

</style>
