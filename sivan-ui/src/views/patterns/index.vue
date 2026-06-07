<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useI18n } from '../../utils/i18n'
import { useMessage } from '../../utils/message'
import { formatTime } from '../../utils/time'
import PaginatedTable from '../../components/common/PaginatedTable.vue'
import {
  fetchPatterns, deletePattern, batchDeletePatterns,
  fetchMySharedTemplates, fetchAccessibleTemplates,
  sharePattern, unshareTemplate,
  type InstinctPattern, type SharedTemplate,
} from '../../api/pattern'

const { t } = useI18n()
const message = useMessage()

const loading = ref(false)
const listMode = ref<'my' | 'shared' | 'accessible'>('my')
const page = ref(1)
const pageSize = ref(10)
const selectedIds = ref(new Set<string>())

const patterns = ref<InstinctPattern[]>([])
const myShared = ref<SharedTemplate[]>([])
const accessible = ref<SharedTemplate[]>([])

const searchQuery = ref('')
const filteredMy = computed(() => {
  let list = patterns.value
  if (searchQuery.value) {
    const q = searchQuery.value.toLowerCase()
    list = list.filter(item =>
      
      (item.executionMode || '')?.toLowerCase().includes(q)
    )
  }
  return list
})
const filteredShared = computed(() => {
  let list = myShared.value
  if (searchQuery.value) {
    const q = searchQuery.value.toLowerCase()
    list = list.filter(item =>
      (item.taskDescription || '')?.toLowerCase().includes(q) ||
      (item.executionMode || '')?.toLowerCase().includes(q)
    )
  }
  return list
})
const filteredAccessible = computed(() => {
  let list = accessible.value
  if (searchQuery.value) {
    const q = searchQuery.value.toLowerCase()
    list = list.filter(item =>
      (item.taskDescription || '')?.toLowerCase().includes(q) ||
      (item.executionMode || '')?.toLowerCase().includes(q)
    )
  }
  return list
})

function paginate<T>(list: T[], pageVal: number): T[] {
  const total = list.length
  const maxPage = Math.max(1, Math.ceil(total / pageSize.value))
  if (pageVal > maxPage) page.value = maxPage
  const start = (pageVal - 1) * pageSize.value
  return list.slice(start, start + pageSize.value)
}
const pagedMy = computed(() => paginate(filteredMy.value, page.value))
const pagedShared = computed(() => paginate(filteredShared.value, page.value))
const pagedAccessible = computed(() => paginate(filteredAccessible.value, page.value))

const currentFiltered = computed(() => {
  return listMode.value === 'my' ? filteredMy.value
    : listMode.value === 'shared' ? filteredShared.value
    : filteredAccessible.value
})
const totalPages = computed(() => Math.max(1, Math.ceil(currentFiltered.value.length / pageSize.value)))

const currentList = computed(() => {
  return listMode.value === 'my' ? pagedMy.value
    : listMode.value === 'shared' ? pagedShared.value
    : pagedAccessible.value
})
const allPageSelected = computed(() =>
  currentList.value.length > 0 && currentList.value.every((item: any) => selectedIds.value.has(item.patternId || item.templateId)))

function toggleAll() {
  if (allPageSelected.value) { selectedIds.value = new Set() }
  else { currentList.value.forEach((item: any) => selectedIds.value.add(item.patternId || item.templateId)) }
}
function toggleOne(id: string) {
  const next = new Set(selectedIds.value)
  next.has(id) ? next.delete(id) : next.add(id)
  selectedIds.value = next
}

watch([searchQuery, listMode], () => { page.value = 1 })
watch(listMode, () => loadData())
onMounted(() => loadData())

async function loadData() {
  loading.value = true
  try {
    switch (listMode.value) {
      case 'my': patterns.value = await fetchPatterns(); break
      case 'shared': myShared.value = await fetchMySharedTemplates(); break
      case 'accessible': accessible.value = await fetchAccessibleTemplates(); break
    }
  } catch { /* noop */ } finally { loading.value = false }
}

async function deleteSelected() {
  const ids = [...selectedIds.value]
  if (!ids.length) return
  if (!confirm(t('patternBatchDeleteConfirm', { n: ids.length }))) return
  try {
    await batchDeletePatterns(ids)
    message.success(t('patternBatchDeleted', { n: ids.length }))
    selectedIds.value = new Set()
    await loadData()
  } catch (e: any) { message.error(e.response?.data?.message || t('operationFailed')) }
}

async function handleDelete(id: string) {
  if (!confirm(t('patternDeleteConfirm'))) return
  try {
    await deletePattern(id)
    message.success(t('deleteSuccess'))
    selectedIds.value.delete(id)
    await loadData()
  } catch (e: any) { message.error(e.response?.data?.message || t('operationFailed')) }
}

async function handleUnshare(id: string) {
  if (!confirm(t('patternUnshareConfirm'))) return
  try {
    await unshareTemplate(id)
    message.success(t('patternUnshareSuccess'))
    await loadData()
  } catch (e: any) { message.error(e.response?.data?.message || t('operationFailed')) }
}

// ── 分享弹窗 ──
const showModal = ref(false)
const saving = ref(false)
const shareTargetId = ref('')
const shareTargetMode = ref('')
const shareVisibility = ref<'PUBLIC' | 'TENANT'>('PUBLIC')

function openShare(p: InstinctPattern) {
  shareTargetId.value = p.patternId
  shareTargetMode.value = p.executionMode
  shareVisibility.value = 'PUBLIC'
  showModal.value = true
}

async function confirmShare() {
  saving.value = true
  try {
    await sharePattern(shareTargetId.value, shareVisibility.value)
    message.success(t('patternShareSuccess'))
    showModal.value = false
  } catch (e: any) { message.error(e.response?.data?.message || t('patternShareFailed')) }
  finally { saving.value = false }
}

// ── 辅助 ──
function successRate(p: InstinctPattern): string {
  if (!p.totalCount || p.totalCount === 0) return '—'
  return ((p.successCount / p.totalCount) * 100).toFixed(0) + '%'
}
function modeClass(m: string): string {
  if (m === 'CHAT') return 'tag-info'
  if (m === 'SINGLE_AGENT') return 'tag-success'
  return 'tag-warning'
}
function qualityClass(q: string): string {
  return q === 'LOW_QUALITY' ? 'tag-error' : 'tag-success'
}
</script>

<template>
  <div class="page-layout">
    <PaginatedTable
      :current-page="page"
      :total-pages="totalPages"
      :total="currentFiltered.length"
      :page-size="pageSize"
      :page-size-options="[10, 20, 50, 100]"
      @page-change="page = $event"
      @page-size-change="pageSize = $event; page = 1"
    >
      <template #header>
        <span>{{ t('instinctPatterns') }}</span>
        <div class="header-extra">
          <button v-if="selectedIds.size > 0 && listMode === 'my'" class="btn btn-sm btn-danger" @click="deleteSelected">
            {{ t('deleteSelectedCount', { n: selectedIds.size }) }}
          </button>
          <input v-model="searchQuery" class="input" style="width:180px" :placeholder="t('search')" />
          <select v-model="listMode" class="select" style="width:120px">
            <option value="my">{{ t('myPatterns') }}</option>
            <option value="shared">{{ t('mySharedTemplates') }}</option>
            <option value="accessible">{{ t('accessibleTemplates') }}</option>
          </select>
        </div>
      </template>
      <template #thead>
        <tr>
          <th v-if="listMode === 'my'" style="width:30px"><input type="checkbox" :checked="allPageSelected" @change="toggleAll"></th>
          <th style="width:100px">{{ t('patternMode') }}</th>
          <th v-if="listMode === 'shared'" style="width:80px;text-align:center">{{ t('visibility') }}</th>
          <th style="width:60px;text-align:center">{{ listMode === 'my' ? t('hitCount') : t('useCount') }}</th>
          <th style="width:80px;text-align:center">{{ listMode === 'my' ? t('successRate') : t('quality') }}</th>
          <th style="width:150px">{{ t('createTimeCol') }}</th>
          <th style="width:90px;text-align:center">{{ t('actionsCol') }}</th>
        </tr>
      </template>
      <template #tbody>
        <tr v-if="loading">
          <td :colspan="9" class="td-empty">{{ t('loading') }}</td>
        </tr>

        <!-- 我的模板 -->
        <tr v-for="p in pagedMy" :key="p.patternId">
          <td @click.stop><input type="checkbox" :checked="selectedIds.has(p.patternId)" @change="toggleOne(p.patternId)"></td>
          <td><span :class="['tag', modeClass(p.executionMode)]">{{ p.executionMode }}</span></td>
          <td style="text-align:center">{{ p.hitCount ?? 0 }}</td>
          <td style="text-align:center">{{ successRate(p) }}</td>
          <td style="white-space:nowrap;font-size:var(--fs-caption)">{{ formatTime(p.createdAt) }}</td>
          <td class="td-actions">
            <button class="btn btn-sm" @click="openShare(p)">{{ t('sharePattern') }}</button>
            <button class="btn btn-sm btn-danger" @click="handleDelete(p.patternId)">{{ t('delete') }}</button>
          </td>
        </tr>

        <!-- 我分享的 -->
        <tr v-for="item in pagedShared" :key="item.templateId">
          <td><span :class="['tag', modeClass(item.executionMode)]">{{ item.executionMode || '—' }}</span></td>
          <td><div class="td-secondary" :title="item.taskDescription">{{ item.taskDescription || '—' }}</div></td>
          <td style="text-align:center">v{{ item.patternVersion ?? '—' }}</td>
          <td style="text-align:center"><span class="tag tag-info">{{ item.visibility }}</span></td>
          <td style="text-align:center">{{ item.useCount }}</td>
          <td style="text-align:center"><span :class="['tag', qualityClass(item.quality)]">{{ item.quality }}</span></td>
          <td style="white-space:nowrap;font-size:var(--fs-caption)">{{ formatTime(item.sharedAt) }}</td>
          <td class="td-actions">
            <button class="btn btn-sm btn-danger" @click="handleUnshare(item.templateId)">{{ t('unshare') }}</button>
          </td>
        </tr>

        <!-- 可访问的 -->
        <tr v-for="item in pagedAccessible" :key="item.templateId">
          <td><span :class="['tag', modeClass(item.executionMode)]">{{ item.executionMode || '—' }}</span></td>
          <td><div class="td-secondary" :title="item.taskDescription">{{ item.taskDescription || '—' }}</div></td>
          <td style="text-align:center">{{ item.useCount }}</td>
          <td style="text-align:center"><span :class="['tag', qualityClass(item.quality)]">{{ item.quality }}</span></td>
          <td style="white-space:nowrap;font-size:var(--fs-caption)">{{ formatTime(item.sharedAt) }}</td>
          <td class="td-actions">—</td>
        </tr>

        <tr v-if="!loading && !currentFiltered.length">
          <td :colspan="9" class="td-empty">{{ t('noData') }}</td>
        </tr>
      </template>
    </PaginatedTable>

    <!-- 分享弹窗 -->
    <Teleport to="body">
      <div v-if="showModal" class="modal-overlay" @click.self="showModal = false">
        <div class="modal" style="max-width:400px;">
          <div class="modal__header">
            <span>{{ t('sharePattern') }}</span>
            <button class="modal__close" @click="showModal = false">&times;</button>
          </div>
          <div class="modal__body">
            <div class="form-group">
              <label class="form-label">{{ t('patternMode') }}</label>
              <span :class="['tag', modeClass(shareTargetMode)]">{{ shareTargetMode }}</span>
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('visibility') }}</label>
              <div style="display:flex;gap:16px;margin-top:4px;">
                <label class="radio"><input type="radio" v-model="shareVisibility" value="PUBLIC" /><span>{{ t('public') }}</span></label>
                <label class="radio"><input type="radio" v-model="shareVisibility" value="TENANT" /><span>{{ t('tenant') }}</span></label>
              </div>
            </div>
          </div>
          <div class="modal__footer">
            <button class="btn btn-ghost" @click="showModal = false">{{ t('cancel') }}</button>
            <button class="btn btn-primary" :disabled="saving" @click="confirmShare">{{ saving ? t('sharing') : t('confirm') }}</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.header-extra { display: flex; gap: 8px; align-items: center; }
.td-secondary { font-size: var(--fs-footnote); color: var(--clr-tertiary); margin-top: 2px; max-width: 250px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
