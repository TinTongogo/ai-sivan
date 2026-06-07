<script setup lang="ts">
import {computed, onMounted, ref, watch} from 'vue'
import {useI18n} from '../../utils/i18n'
import {useMessage} from '../../utils/message'
import api from '../../api'
import PaginatedTable from '../../components/common/PaginatedTable.vue'

const { t } = useI18n()

interface Skill {
  skillId: string
  skillCode: string
  name: string
  displayName: string
  description: string
  content: string
  category: string
  skillType: string
  tags: string[]
  usageCount: number
  lastUsedAt: string | null
  status: string
  createdAt: string
  updatedAt: string
}

const message = useMessage()
const skills = ref<Skill[]>([])
const loading = ref(false)
const showModal = ref(false)
const editing = ref(false)
const viewOnly = ref(false)
const searchQuery = ref('')
const categoryFilter = ref('')
const statusFilter = ref('')
const sourceFilter = ref('')
const saving = ref(false)
const page = ref(1)
const pageSize = ref(10)
const selectedIds = ref(new Set<string>())

const allPageSelected = computed(() =>
  pagedSkills.value.length > 0 && pagedSkills.value.every(s => selectedIds.value.has(s.skillId)))

function toggleAll() {
  if (allPageSelected.value) { selectedIds.value = new Set() }
  else { pagedSkills.value.forEach(s => selectedIds.value.add(s.skillId)) }
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
    await api.post('/skills/batch-delete', ids)
    message.success(t('skillBatchDeleted', { n: ids.length }))
    selectedIds.value = new Set()
    await fetchSkills()
  } catch (e: any) { message.error(e.response?.data?.message || t('skillBatchDeleteFailed')) }
}

const form = ref<Partial<Skill>>({
  skillCode: '',
  name: '',
  displayName: '',
  description: '',
  content: '',
  category: '',
  tags: [],
  status: 'ACTIVE',
})

// 标签输入辅助
const newTag = ref('')

function addTag() {
  const tVal = newTag.value.trim()
  if (tVal && !form.value.tags?.includes(tVal)) {
    form.value.tags = [...(form.value.tags || []), tVal]
  }
  newTag.value = ''
}

function removeTag(tag: string) {
  form.value.tags = form.value.tags?.filter(t => t !== tag) || []
}

const pagedSkills = computed(() => {
  const total = filteredSkills.value.length
  const maxPage = Math.max(1, Math.ceil(total / pageSize.value))
  if (page.value > maxPage) page.value = maxPage
  const start = (page.value - 1) * pageSize.value
  return filteredSkills.value.slice(start, start + pageSize.value)
})

const categories = computed(() => {
  const set = new Set(skills.value.map(s => s.category).filter(Boolean))
  return [...set].sort()
})

const filteredSkills = computed(() => {
  let list = skills.value
  if (searchQuery.value) {
    const q = searchQuery.value.toLowerCase()
    list = list.filter(s =>
      s.displayName?.toLowerCase().includes(q) ||
      s.skillCode?.toLowerCase().includes(q) ||
      s.description?.toLowerCase().includes(q)
    )
  }
  if (categoryFilter.value) {
    list = list.filter(s => s.category === categoryFilter.value)
  }
  if (statusFilter.value) {
    list = list.filter(s => s.status === statusFilter.value)
  }
  if (sourceFilter.value) {
    list = list.filter(s => s.skillType === sourceFilter.value)
  }
  return list
})

const totalPages = computed(() => Math.max(1, Math.ceil(filteredSkills.value.length / pageSize.value)))

watch([searchQuery, categoryFilter, statusFilter, sourceFilter], () => { page.value = 1 })

onMounted(() => fetchSkills())

async function fetchSkills() {
  loading.value = true
  try {
    const params = categoryFilter.value ? { category: categoryFilter.value } : {}
    const res: any = await api.get('/skills', { params })
    skills.value = res.data || []
  } catch { /* noop */ } finally {
    loading.value = false
  }
}

function openCreateModal() {
  editing.value = false
  viewOnly.value = false
  form.value = {
    skillCode: '', name: '', displayName: '', description: '', content: '',
    category: '',
    tags: [], status: 'ACTIVE',
  }
  showModal.value = true
}

function viewSkill(skill: Skill) {
  editing.value = false
  viewOnly.value = true
  form.value = {
    skillId: skill.skillId,
    skillCode: skill.skillCode,
    name: skill.name,
    displayName: skill.displayName,
    description: skill.description,
    content: skill.content,
    category: skill.category,
    tags: skill.tags || [],
    status: skill.status,
  }
  showModal.value = true
}

function editSkill(skill: Skill) {
  editing.value = true
  viewOnly.value = false
  form.value = {
    skillId: skill.skillId,
    skillCode: skill.skillCode,
    name: skill.name,
    displayName: skill.displayName,
    description: skill.description,
    content: skill.content,
    category: skill.category,
    tags: skill.tags || [],
    status: skill.status,
  }
  showModal.value = true
}

async function saveSkill() {
  if (!form.value.skillCode || !form.value.name) {
    message.warning(t('skillCodeAndNameRequired'))
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await api.put(`/skills/${form.value.skillId}`, form.value)
      message.success(t('skillUpdateSuccess'))
    } else {
      await api.post('/skills', form.value)
      message.success(t('skillCreateSuccess'))
    }
    showModal.value = false
    await fetchSkills()
  } catch (e: any) {
    message.error(e.response?.data?.message || t('operationFailed'))
  } finally {
    saving.value = false
  }
}

async function deleteSkill(id: string) {
  try {
    await api.delete(`/skills/${id}`)
    message.success(t('skillDeleteSuccess'))
    await fetchSkills()
  } catch (e: any) {
    message.error(e.response?.data?.message || t('skillDeleteFailed'))
  }
}

async function toggleStatus(skill: Skill) {
  const newStatus = skill.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  try {
    await api.put(`/skills/${skill.skillId}`, { status: newStatus })
    message.success(newStatus === 'ACTIVE' ? t('skillToggleEnabled') : t('skillToggleDisabled'))
    await fetchSkills()
  } catch (e: any) {
    message.error(e.response?.data?.message || t('operationFailed'))
  }
}
</script>

<template>
  <div class="page-layout">
    <!-- 工具栏 -->
    <PaginatedTable
      :current-page="page"
      :total-pages="totalPages"
      :total="filteredSkills.length"
      :page-size="pageSize"
      :page-size-options="[10, 20, 50, 100]"
      @page-change="page = $event"
      @page-size-change="pageSize = $event; page = 1"
    >
      <template #header>
        <span>{{ t('skillManagement') }}</span>
        <div class="header-extra">
          <button v-if="selectedIds.size > 0" class="btn btn-sm btn-danger" @click="deleteSelected">
            {{ t('deleteSelectedCount', { n: selectedIds.size }) }}
          </button>
          <input v-model="searchQuery" class="input" style="width:200px" :placeholder="t('searchSkill')" />
          <select v-model="sourceFilter" class="select" style="width:100px">
            <option value="">{{ t('allSources') }}</option>
            <option value="SYSTEM">{{ t('system') }}</option>
            <option value="USER">{{ t('user') }}</option>
          </select>
          <select v-model="statusFilter" class="select" style="width:100px">
            <option value="">{{ t('allStatuses') }}</option>
            <option value="ACTIVE">{{ t('enabled') }}</option>
            <option value="INACTIVE">{{ t('disabled') }}</option>
          </select>
          <select v-model="categoryFilter" class="select" style="width:120px" @change="fetchSkills">
            <option value="">{{ t('allCategories') }}</option>
            <option v-for="c in categories" :key="c" :value="c">{{ c }}</option>
          </select>
          <button class="btn btn-primary" @click="openCreateModal">{{ t('createSkill') }}</button>
        </div>
      </template>
      <template #thead>
        <tr>
          <th style="width:30px"><input type="checkbox" :checked="allPageSelected" @change="toggleAll"></th>
          <th>{{ t('nameCol') }}</th>
          <th>{{ t('codeCol') }}</th>
          <th>{{ t('category') }}</th>
          <th>{{ t('sourceCol') }}</th>
          <th>{{ t('tagsCol') }}</th>
          <th>{{ t('usageCount') }}</th>
          <th>{{ t('status') }}</th>
          <th>{{ t('actionsCol') }}</th>
        </tr>
      </template>
      <template #tbody>
        <tr v-if="loading">
          <td colspan="9" class="td-empty">{{ t('loading') }}</td>
        </tr>
        <tr v-for="skill in pagedSkills" :key="skill.skillId">
          <td @click.stop><input type="checkbox" :checked="selectedIds.has(skill.skillId)" @change="toggleOne(skill.skillId)"></td>
          <td>
            <div class="td-primary td-link" @click="viewSkill(skill)">{{ skill.displayName || skill.name }}</div>
            <div class="td-secondary" v-if="skill.description">{{ skill.description }}</div>
          </td>
          <td><code class="inline-code">{{ skill.skillCode }}</code></td>
          <td><span v-if="skill.category" class="tag tag-info">{{ skill.category }}</span></td>
          <td>
            <span class="tag" :class="skill.skillType === 'SYSTEM' ? 'tag-info' : 'tag-success'">
              {{ skill.skillType === 'SYSTEM' ? t('system') : t('user') }}
            </span>
          </td>
          <td>
            <span v-if="skill.tags?.length" class="tag-list">
              <span v-for="tag in skill.tags.slice(0, 3)" :key="tag" class="tag tag-ghost">{{ tag }}</span>
              <span v-if="skill.tags.length > 3" class="tag-more">+{{ skill.tags.length - 3 }}</span>
            </span>
          </td>
          <td>{{ skill.usageCount }}</td>
          <td>
            <button class="tag" :class="skill.status === 'ACTIVE' ? 'tag-success' : 'tag-warning'"
              style="cursor:pointer;border:none;font-family:inherit;" @click="toggleStatus(skill)">
              {{ skill.status === 'ACTIVE' ? t('enabled') : t('disabled') }}
            </button>
          </td>
          <td class="td-actions">
            <button class="btn btn-sm btn-danger" @click="deleteSkill(skill.skillId)">{{ t('delete') }}</button>
            <button class="btn btn-sm" @click="editSkill(skill)">{{ t('edit') }}</button>
          </td>
        </tr>
        <tr v-if="!loading && !filteredSkills.length">
          <td colspan="9" class="td-empty">{{ t('noData') }}</td>
        </tr>
      </template>
    </PaginatedTable>

    <!-- 创建/编辑弹窗 -->
    <Teleport to="body">
      <div v-if="showModal" class="modal-overlay" @click.self="showModal = false">
        <div class="modal" style="max-width:640px;">
          <div class="modal__header">
            <span>{{ viewOnly ? t('viewSkill') : editing ? t('editSkill') : t('createSkillTitle') }}</span>
            <button class="modal__close" @click="showModal = false">&times;</button>
          </div>
          <div class="modal__body">
            <div class="form-row">
              <div class="form-group" style="flex:1;">
                <label class="form-label">{{ t('skillCodeRequired') }}</label>
                <input v-model="form.skillCode" class="input" :placeholder="t('skillCodePlaceholder')" :disabled="editing || viewOnly" />
              </div>
              <div class="form-group" style="flex:1;">
                <label class="form-label">{{ t('skillNameRequired') }}</label>
                <input v-model="form.name" class="input" :placeholder="t('skillNamePlaceholder')" :disabled="viewOnly" />
              </div>
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('skillDisplayName') }}</label>
              <input v-model="form.displayName" class="input" :placeholder="t('skillDisplayNamePlaceholder')" :disabled="viewOnly" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('skillDescription') }}</label>
              <textarea v-model="form.description" class="input textarea" style="min-height:60px;" :placeholder="t('skillDescPlaceholder')" :disabled="viewOnly"></textarea>
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('skillContent') }}</label>
              <textarea v-model="form.content" class="input textarea" rows="5" :placeholder="t('skillContentPlaceholder')" :disabled="viewOnly"></textarea>
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('skillCategory') }}</label>
              <input v-model="form.category" class="input" :placeholder="t('skillCategoryPlaceholder')" :disabled="viewOnly" />
            </div>
            <!-- 标签 -->
            <div class="form-group" v-if="!viewOnly">
              <label class="form-label">{{ t('skillTags') }}</label>
              <div class="tag-input-wrap">
                <span v-for="tag in form.tags" :key="tag" class="tag-input-item">
                  <span>{{ tag }}</span>
                  <button class="tag-input-remove" @click="removeTag(tag)">&times;</button>
                </span>
                <input v-model="newTag" class="tag-input-field" :placeholder="t('tagInputPlaceholder')" @keydown.enter.prevent="addTag" @keydown.,.prevent="addTag" />
              </div>
            </div>
            <!-- 标签（查看模式） -->
            <div class="form-group" v-else>
              <label class="form-label">{{ t('skillTags') }}</label>
              <div class="tag-list">
                <span v-for="tag in form.tags" :key="tag" class="tag tag-ghost">{{ tag }}</span>
                <span v-if="!form.tags?.length" class="form-hint-inline">{{ t('noTags') }}</span>
              </div>
            </div>
          </div>
          <div class="modal__footer">
            <template v-if="viewOnly">
              <button class="btn btn-primary" @click="showModal = false">{{ t('close') }}</button>
            </template>
            <template v-else>
              <button class="btn btn-ghost" @click="showModal = false">{{ t('cancel') }}</button>
              <button class="btn btn-primary" :disabled="saving" @click="saveSkill">{{ saving ? t('saving') : t('save') }}</button>
            </template>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.header-extra { display: flex; gap: 8px; align-items: center; }
.form-row { display: flex; gap: 12px; }
.td-primary { font-weight: var(--fw-medium); color: var(--clr-label); }
.td-link { cursor: pointer; transition: color var(--dur-fast); }
.td-link:hover { color: var(--clr-accent); }
.td-secondary { font-size: var(--fs-footnote); color: var(--clr-tertiary); margin-top: 2px; max-width: 250px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.form-hint-inline { font-size: var(--fs-footnote); color: var(--clr-quaternary); }
.inline-code { font-family: var(--ff-mono); font-size: 0.9em; padding: 1px 6px; border-radius: var(--rad-xs); background: var(--clr-bg-secondary); color: var(--clr-label); }
.tag-list { display: flex; gap: 3px; flex-wrap: wrap; align-items: center; }
.tag-ghost { background: var(--clr-bg-secondary); color: var(--clr-secondary); }
.tag-more { font-size: var(--fs-footnote); color: var(--clr-tertiary); }
.tag-input-wrap { display: flex; flex-wrap: wrap; gap: 4px; padding: 6px 8px; border: 1px solid var(--clr-separator); border-radius: var(--rad-md); background: var(--clr-bg); min-height: 36px; }
.tag-input-wrap:focus-within { border-color: var(--clr-accent); box-shadow: 0 0 0 2px var(--clr-accent-soft); }
.tag-input-item { display: inline-flex; align-items: center; gap: 3px; padding: 1px 6px; background: var(--clr-accent-soft); color: var(--clr-accent); border-radius: var(--rad-xs); font-size: var(--fs-footnote); }
.tag-input-remove { display: inline-flex; align-items: center; justify-content: center; width: 14px; height: 14px; border: none; background: transparent; color: var(--clr-accent); cursor: pointer; font-size: 14px; padding: 0; line-height: 1; }
.tag-input-remove:hover { color: var(--clr-red); }
.tag-input-field { flex: 1; min-width: 80px; border: none; outline: none; font-size: var(--fs-footnote); font-family: inherit; background: transparent; color: var(--clr-label); padding: 2px 0; }
</style>
