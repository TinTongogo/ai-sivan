<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useI18n } from '../../utils/i18n'
import { useMessage } from '../../utils/message'
import { formatTime } from '../../utils/time'
import api from '../../api'
import PaginatedTable from '../../components/common/PaginatedTable.vue'

const { t } = useI18n()

interface Memory {
  memoryId: string
  projectId: string
  projectName: string
  level: string
  scopeId: string
  scopeName: string
  content: string
  retention: number
  accessCount: number
  archived: boolean
  important: boolean
  summary: string
  createdAt: string
  lastAccessedAt: string
}

const message = useMessage()
const data = ref<Memory[]>([])
const loading = ref(false)
const showModal = ref(false)
const editing = ref(false)
const levelFilter = ref('')
const archivedFilter = ref('')
const importantFilter = ref('')
const keyword = ref('')
const page = ref(1)
const pageSize = ref(15)
const total = ref(0)
const selectedIds = ref(new Set<string>())

const allPageSelected = computed(() =>
  filteredData.value.length > 0 && filteredData.value.every(m => selectedIds.value.has(m.memoryId)))

function toggleAll() {
  if (allPageSelected.value) { selectedIds.value = new Set() }
  else { filteredData.value.forEach(m => selectedIds.value.add(m.memoryId)) }
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
    await api.post('/memories/batch-delete', ids)
    message.success(t('memoryBatchDeleted', { n: ids.length }))
    selectedIds.value = new Set()
    await fetch()
  } catch { message.error(t('memoryBatchDeleteFailed')) }
}

const stats = ref({ totalCount: 0, importantCount: 0, archivedCount: 0 })

const form = ref<Partial<Memory>>({
  content: '', summary: '', level: 'USER', important: false,
})

const levelOptions = computed(() => [
  { label: t('levelSession'), value: 'SESSION' },
  { label: t('levelUser'), value: 'USER' },
  { label: t('levelTeam'), value: 'TEAM' },
  { label: t('levelProject'), value: 'PROJECT' },
])

const levelClsMap: Record<string, string> = {
  SESSION: 'tag-info', USER: 'tag-success', TEAM: 'tag-warning', PROJECT: 'tag-error',
}

const levelNameMap = computed<Record<string, string>>(() => ({
  SESSION: t('levelSession'),
  USER: t('levelUser'),
  TEAM: t('levelTeam'),
  PROJECT: t('levelProject'),
}))

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

const filteredData = computed(() => {
  return data.value.filter(row => {
    if (archivedFilter.value === 'hide') return !row.archived
    if (archivedFilter.value === 'only') return row.archived
    return true
  }).filter(row => {
    if (importantFilter.value === 'important') return row.important
    if (importantFilter.value === 'normal') return !row.important
    return true
  })
})

onMounted(() => { fetch(); fetchStats() })

watch(page, () => { fetch() })

watch(levelFilter, () => {
  page.value = 1
  fetch()
})

watch(archivedFilter, () => { page.value = 1 })
watch(importantFilter, () => { page.value = 1 })

let keywordTimer: ReturnType<typeof setTimeout>
watch(keyword, () => {
  clearTimeout(keywordTimer)
  keywordTimer = setTimeout(() => { page.value = 1; fetch() }, 300)
})

async function fetch() {
  loading.value = true
  try {
    const params: any = { page: page.value - 1, size: pageSize.value }
    if (levelFilter.value) params.level = levelFilter.value
    if (keyword.value) params.keyword = keyword.value
    const res: any = await api.get('/memories/page', { params })
    const pageData = res.data || {}
    data.value = pageData.items || []
    total.value = pageData.total || 0
  } catch { /* noop */ } finally { loading.value = false }
}

async function fetchStats() {
  try {
    const res: any = await api.get('/memories/stats')
    stats.value = res.data || { totalCount: 0, importantCount: 0, archivedCount: 0 }
  } catch { /* noop */ }
}

function openCreateModal() {
  editing.value = false
  form.value = { content: '', summary: '', level: 'USER', important: false }
  showModal.value = true
}

function editMemory(m: Memory) {
  editing.value = true
  form.value = {
    memoryId: m.memoryId,
    content: m.content,
    summary: m.summary,
    level: m.level,
    important: m.important,
  }
  showModal.value = true
}

async function saveMemory() {
  if (!form.value.content) { message.warning(t('memoryContentRequiredError')); return }
  try {
    if (editing.value) {
      await api.put(`/memories/${form.value.memoryId}`, {
        content: form.value.content,
        summary: form.value.summary,
        level: form.value.level,
      })
      message.success(t('memoryUpdateSuccess'))
    } else {
      await api.post('/memories', form.value)
      message.success(t('memoryCreateSuccess'))
    }
    showModal.value = false
    await fetch(); await fetchStats()
  } catch (e: any) {
    message.error(e.response?.data?.message || t('operationFailed'))
  }
}

async function toggleImportant(row: Memory) {
  try {
    const res: any = await api.patch(`/memories/${row.memoryId}/important`)
    const updated = res.data as Memory
    message.success(updated.important ? t('markedImportant') : t('unmarkedImportant'))
    await fetch(); await fetchStats()
  } catch { message.error(t('operationFailed')) }
}

async function deleteMemory(id: string) {
  try {
    await api.delete(`/memories/${id}`)
    message.success(t('memoryDeleteSuccess'))
    await fetch(); await fetchStats()
  } catch { message.error(t('memoryDeleteFailed')) }
}

function retentionColor(retention: number): string {
  if (retention >= 0.7) return 'var(--clr-accent)'
  if (retention >= 0.4) return 'var(--clr-orange)'
  return 'var(--clr-red)'
}

function onPageChange(p: number) {
  page.value = p
  fetch()
}

function onPageSizeChange(size: number) {
  pageSize.value = size
  page.value = 1
  fetch()
}
</script>

<template>
  <div class="page-layout">
    <!-- 统计卡片 -->
    <div class="stats-row">
      <div class="stat-card">
        <span class="stat-value">{{ stats.totalCount }}</span>
        <span class="stat-label">{{ t('total') }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-value stat-value--important">{{ stats.importantCount }}</span>
        <span class="stat-label">{{ t('important') }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-value stat-value--archived">{{ stats.archivedCount }}</span>
        <span class="stat-label">{{ t('archived') }}</span>
      </div>
    </div>

    <PaginatedTable
      :current-page="page"
      :total-pages="totalPages"
      :total="total"
      :page-size="pageSize"
      :page-size-options="[15, 30, 50]"
      @page-change="onPageChange"
      @page-size-change="onPageSizeChange"
    >
      <template #header>
        <span>{{ t('memoryManagement') }}</span>
        <div class="header-extra">
          <button v-if="selectedIds.size > 0" class="btn btn-sm btn-danger" @click="deleteSelected">
            {{ t('deleteSelectedCount', { n: selectedIds.size }) }}
          </button>
          <input v-model="keyword" class="input" style="width:180px" :placeholder="t('searchMemory')" />
          <select v-model="archivedFilter" class="select" style="width:90px">
            <option value="">{{ t('allStatuses') }}</option>
            <option value="hide">{{ t('notArchived') }}</option>
            <option value="only">{{ t('archived') }}</option>
          </select>
          <select v-model="importantFilter" class="select" style="width:90px">
            <option value="">{{ t('allStatuses') }}</option>
            <option value="important">{{ t('important') }}</option>
            <option value="normal">{{ t('normalImportant') }}</option>
          </select>
          <select v-model="levelFilter" class="select" style="width:100px">
            <option value="">{{ t('allLevels') }}</option>
            <option v-for="opt in levelOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
          </select>
          <button class="btn btn-primary" @click="openCreateModal">{{ t('createMemory') }}</button>
        </div>
      </template>
      <template #thead>
        <tr>
          <th style="width:30px"><input type="checkbox" :checked="allPageSelected" @change="toggleAll"></th>
          <th>{{ t('summaryCol') }}</th>
          <th>{{ t('levelCol') }}</th>
          <th style="max-width:140px">{{ t('sourceCol') }}</th>
          <th>{{ t('retentionCol') }}</th>
          <th>{{ t('accessCol') }}</th>
          <th>{{ t('importantCol') }}</th>
          <th>{{ t('archivedCol') }}</th>
          <th>{{ t('createTimeCol') }}</th>
          <th>{{ t('actionsCol') }}</th>
        </tr>
      </template>
      <template #tbody>
        <tr v-if="loading">
          <td colspan="10" class="td-empty">{{ t('loading') }}</td>
        </tr>
        <tr v-for="row in filteredData" :key="row.memoryId">
          <td @click.stop><input type="checkbox" :checked="selectedIds.has(row.memoryId)" @change="toggleOne(row.memoryId)"></td>
          <td style="max-width:260px">
            <div class="td-primary" v-if="row.summary">{{ row.summary }}</div>
            <div class="td-secondary" :title="row.content">{{ row.content }}</div>
          </td>
          <td><span class="tag" :class="levelClsMap[row.level] || ''">{{ levelNameMap[row.level] || row.level }}</span></td>
          <td style="max-width:140px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:var(--fs-caption)" :title="row.scopeName">
            {{ row.scopeName || row.scopeId?.slice(0, 8) || '--' }}
          </td>
          <td>
            <div v-if="row.retention != null" class="retention-bar-wrap">
              <div class="retention-bar-track">
                <div class="retention-bar-fill" :style="{ width: (row.retention * 100) + '%', background: retentionColor(row.retention) }"></div>
              </div>
              <span class="retention-text">{{ (row.retention * 100).toFixed(0) + '%' }}</span>
            </div>
            <span v-else class="retention-na">-</span>
          </td>
          <td>{{ row.accessCount }}</td>
          <td>
            <span :class="'tag ' + (row.important ? 'tag-error' : '')" style="cursor:pointer" @click="toggleImportant(row)">
              {{ row.important ? t('important') : t('markImportant') }}
            </span>
          </td>
          <td><span class="tag">{{ row.archived ? t('archived') : t('notArchived') }}</span></td>
          <td style="white-space:nowrap;font-size:var(--fs-footnote)">{{ formatTime(row.createdAt) }}</td>
          <td class="td-actions">
            <button class="btn btn-sm btn-danger" @click="deleteMemory(row.memoryId)">{{ t('delete') }}</button>
            <button class="btn btn-sm" @click="editMemory(row)">{{ t('edit') }}</button>
          </td>
        </tr>
        <tr v-if="!loading && !data.length">
          <td colspan="10" class="td-empty">{{ levelFilter || archivedFilter || importantFilter ? t('noDataFiltered') : t('noData') }}</td>
        </tr>
      </template>
    </PaginatedTable>

    <!-- 创建/编辑弹窗 -->
    <Teleport to="body">
      <div v-if="showModal" class="modal-overlay" @click.self="showModal = false">
        <div class="modal" style="max-width:560px;">
          <div class="modal__header">
            <span>{{ editing ? t('editMemory') : t('createMemoryTitle') }}</span>
            <button class="modal__close" @click="showModal = false">&times;</button>
          </div>
          <div class="modal__body">
            <div class="form-group">
              <label class="form-label">{{ t('memoryContentRequired') }}</label>
              <textarea v-model="form.content" class="input textarea" rows="4" :placeholder="t('memoryContentPlaceholder')"></textarea>
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('summary') }}</label>
              <input v-model="form.summary" class="input" :placeholder="t('summaryPlaceholder')" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('level') }}</label>
              <select v-model="form.level" class="select" :disabled="editing">
                <option v-for="opt in levelOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
              </select>
            </div>
          </div>
          <div class="modal__footer">
            <button class="btn btn-ghost" @click="showModal = false">{{ t('cancel') }}</button>
            <button class="btn btn-primary" @click="saveMemory">{{ t('save') }}</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.header-extra { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }

.stats-row {
  display: flex;
  gap: var(--sp-unit-2);
}
.stat-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: var(--sp-unit-2);
  background: var(--clr-bg);
  border: 1px solid var(--clr-separator-light);
  border-radius: var(--rad-lg);
}
.stat-value {
  font-size: var(--fs-title-2);
  font-weight: var(--fw-bold);
  color: var(--clr-label);
}
.stat-value--important { color: var(--clr-red); }
.stat-value--archived { color: var(--clr-tertiary); }
.stat-label {
  font-size: var(--fs-footnote);
  color: var(--clr-secondary);
  margin-top: 2px;
}

.td-primary {
  font-weight: var(--fw-medium);
  color: var(--clr-label);
  font-size: var(--fs-footnote);
  margin-bottom: 2px;
}
.td-secondary {
  font-size: var(--fs-footnote);
  color: var(--clr-tertiary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.retention-bar-wrap { display: flex; align-items: center; gap: 6px; }
.retention-bar-track {
  width: 60px;
  height: 6px;
  border-radius: 3px;
  background: var(--clr-bg-tertiary);
  overflow: hidden;
}
.retention-bar-fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.2s ease;
}
.retention-text { font-size: var(--fs-footnote); min-width: 32px; color: var(--clr-secondary); }
.retention-na { color: var(--clr-tertiary); }
</style>
