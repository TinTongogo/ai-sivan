<script setup lang="ts">
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import { useMessage } from '../../utils/message'
import { useI18n } from '../../utils/i18n'
import { formatTime } from '../../utils/time'
import api from '../../api'

interface KnowledgeBase {
  kbName: string
  projectId: string | null
  description: string
  createdAt: string
  updatedAt: string
}

interface Document {
  docId: string
  kbName: string
  filename: string
  sourcePath: string
  fileType: string
  charCount: number
  chunkCount: number
  textContent: string
  createdAt: string
}

interface SearchResult {
  chunkId: string
  kbName: string
  text: string
  score: number
  contentType: string
  metadata?: Record<string, unknown>
}

const message = useMessage()
const { t } = useI18n()

const KB_NAME_MAX = 64
const KB_DESC_MAX = 200

// --- KB 列表（左栏） ---
const kbList = ref<KnowledgeBase[]>([])
const kbLoading = ref(false)
const kbSearchQuery = ref('')
const selectedKbName = ref<string | null>(null)

const filteredKbList = computed(() => {
  if (!kbSearchQuery.value) return kbList.value
  const q = kbSearchQuery.value.toLowerCase()
  return kbList.value.filter(kb =>
    kb.kbName.toLowerCase().includes(q) ||
    kb.description?.toLowerCase().includes(q)
  )
})

const selectedKb = computed(() => kbList.value.find(kb => kb.kbName === selectedKbName.value))

// --- 文档（右栏） ---
const documents = ref<Document[]>([])
const docsLoading = ref(false)
const selectedDocIds = ref<string[]>([])
const showMoveModal = ref(false)
const moveTargetKbName = ref('')
const movingDocs = ref(false)
const rebuildingIndex = ref(false)
const docPage = ref(1)
const docPageSize = ref(10)
const docTotal = ref(0)
const docTotalPages = computed(() => Math.max(1, Math.ceil(docTotal.value / docPageSize.value)))

const visibleDocPages = computed(() => {
  const total = docTotalPages.value
  const current = docPage.value
  const maxVisible = 7
  if (total <= maxVisible) return Array.from({ length: total }, (_, i) => i + 1)
  const pages: (number | string)[] = [1]
  let start = Math.max(2, current - 2)
  let end = Math.min(total - 1, current + 2)
  if (current <= 3) { start = 2; end = Math.min(total - 1, maxVisible - 1) }
  if (current >= total - 2) { start = Math.max(2, total - maxVisible + 2); end = total - 1 }
  if (start > 2) pages.push('...')
  for (let i = start; i <= end; i++) pages.push(i)
  if (end < total - 1) pages.push('...')
  if (total > 1) pages.push(total)
  return pages
})

const gotoDocPage = ref(docPage.value)
watch(() => docPage.value, (val) => { gotoDocPage.value = val })

function handleGotoDocPage() {
  let p = gotoDocPage.value
  if (isNaN(p) || p < 1) p = 1
  if (p > docTotalPages.value) p = docTotalPages.value
  onDocPageChange(p)
}

// --- 创建/编辑知识库 ---
const showKbModal = ref(false)
const kbForm = ref<Partial<KnowledgeBase>>({ kbName: '', description: '' })

// --- 描述行内编辑 ---
const editingDescKb = ref('')
const descDraft = ref('')

function startInlineEdit(kbName: string) {
  const kb = kbList.value.find(k => k.kbName === kbName)
  if (!kb) return
  descDraft.value = kb.description || ''
  editingDescKb.value = kbName
  nextTick(() => {
    const el = document.querySelector<HTMLInputElement>('.kb-desc-input')
    el?.focus()
    el?.select()
  })
}

function cancelInlineEdit() {
  editingDescKb.value = ''
  descDraft.value = ''
}

async function saveInlineDesc(kbName: string) {
  if (editingDescKb.value !== kbName) return
  editingDescKb.value = ''
  try {
    await api.put(`/v2/knowledge-bases/${encodeURIComponent(kbName)}`, { description: descDraft.value || '' })
    const kb = kbList.value.find(k => k.kbName === kbName)
    if (kb) kb.description = descDraft.value
    if (selectedKbName.value === kbName) selectedKb.value && (selectedKb.value.description = descDraft.value)
  } catch (e: any) {
    message.error(e.response?.data?.message || t('operationFailed'))
  }
}

// --- 文档编辑 ---
const showEditModal = ref(false)
const saving = ref(false)
const editForm = ref<{ docId: string; filename: string; textContent: string }>({
  docId: '', filename: '', textContent: ''
})

// --- 搜索 ---
const searchForm = ref({ query: '', topK: 10 })
const searchMode = ref<'VECTOR' | 'FULLTEXT'>('VECTOR')
const searchScope = ref<'all' | 'current'>('current')
const expandQuery = ref(true)
const searchResults = ref<SearchResult[]>([])
const searching = ref(false)

// --- 批量上传 ---
const uploadingFiles = ref(false)
const ALLOWED_EXTS = ['.txt', '.md', '.json', '.csv', '.html', '.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp', '.svg']

function isAllowedFile(file: File): boolean {
  const name = file.name.toLowerCase()
  return ALLOWED_EXTS.some(ext => name.endsWith(ext))
}

onMounted(() => {
  fetchKbList()
})

async function fetchKbList() {
  kbLoading.value = true
  try {
    const res: any = await api.get('/v2/knowledge-bases')
    kbList.value = res.data || []
  } catch { /* noop */ } finally { kbLoading.value = false }
}

function selectKb(kbName: string) {
  selectedKbName.value = kbName
  docPage.value = 1
  selectedDocIds.value = []
  fetchDocuments(kbName)
}

async function fetchDocuments(kbName: string) {
  docsLoading.value = true
  try {
    const res: any = await api.get(`/v2/knowledge-bases/${kbName}/documents/page`, {
      params: { page: docPage.value - 1, size: docPageSize.value }
    })
    const pageData = res.data || {}
    documents.value = pageData.items || []
    docTotal.value = pageData.total || 0
  } catch { /* noop */ } finally { docsLoading.value = false }
}

function onDocPageChange(p: number) {
  docPage.value = p
}

function onDocPageSizeChange(size: number) {
  docPageSize.value = size
  docPage.value = 1
  if (selectedKbName.value) fetchDocuments(selectedKbName.value)
}

watch(docPage, () => {
  if (selectedKbName.value) fetchDocuments(selectedKbName.value)
})

// --- KB CRUD ---
function openCreateModal() {
  kbForm.value = { kbName: '', description: '' }
  showKbModal.value = true
}

async function saveKb() {
  const name = kbForm.value.kbName?.trim()
  if (!name) { message.warning(t('kbNameRequired')); return }
  if (name.length > KB_NAME_MAX) { message.warning(t('kbNameTooLong', { n: KB_NAME_MAX })); return }
  if ((kbForm.value.description || '').length > KB_DESC_MAX) { message.warning(t('kbDescTooLong', { n: KB_DESC_MAX })); return }
  try {
    await api.post('/v2/knowledge-bases', kbForm.value)
    message.success(t('createSuccess'))
    showKbModal.value = false
    await fetchKbList()
  } catch (e: any) {
    message.error(e.response?.data?.message || t('operationFailed'))
  }
}

async function deleteKb(name: string) {
  try {
    await api.delete(`/v2/knowledge-bases/${name}`)
    message.success(t('deleteSuccess'))
    if (selectedKbName.value === name) {
      selectedKbName.value = null
      documents.value = []
      selectedDocIds.value = []
    }
    await fetchKbList()
  } catch { message.error(t('deleteFailed')) }
}

async function rebuildIndex() {
  if (!selectedKbName.value) return
  if (!window.confirm(t('rebuildIndexConfirm') || `确定重建知识库"${selectedKbName.value}"的全部索引？此操作将删除所有现有分块并重新创建。`)) return
  rebuildingIndex.value = true
  try {
    await api.post(`/v2/knowledge-bases/${selectedKbName.value}/rebuild-index`)
    message.success(t('rebuildIndexSuccess'))
    await fetchDocuments(selectedKbName.value)
  } catch (e: any) {
    message.error(e.response?.data?.message || t('rebuildIndexFailed'))
  } finally {
    rebuildingIndex.value = false
  }
}

// --- 批量上传 ---
function handleFileDrop(event: DragEvent) {
  event.preventDefault()
  const target = event.currentTarget as HTMLElement
  target.classList.remove('drag-over')
  const files = event.dataTransfer?.files
  if (files && files.length > 0) filterAndUpload(Array.from(files))
}

function handleFileSelect(event: Event) {
  const input = event.target as HTMLInputElement
  const files = input.files
  if (files && files.length > 0) filterAndUpload(Array.from(files))
  input.value = ''
}

function filterAndUpload(files: File[]) {
  const valid: File[] = []
  const rejected: string[] = []
  for (const f of files) {
    if (isAllowedFile(f)) valid.push(f)
    else rejected.push(f.name)
  }
  if (rejected.length > 0) {
    message.warning(t('unsupportedFileSkipped', { files: rejected.join(', ') }))
  }
  if (valid.length > 0) uploadFiles(valid)
}

async function uploadFiles(files: File[]) {
  if (!selectedKbName.value) return
  uploadingFiles.value = true
  let successCount = 0
  let failCount = 0
  try {
    const results = await Promise.allSettled(files.map(file => {
      const formData = new FormData()
      formData.append('file', file)
      return api.post(`/v2/knowledge-bases/${selectedKbName.value}/documents/upload`, formData)
    }))
    results.forEach(r => r.status === 'fulfilled' ? successCount++ : failCount++)
    if (failCount === 0) message.success(t('uploadSuccessCount', { n: successCount }))
    else message.warning(t('uploadPartialSuccess', { success: successCount, fail: failCount }))
    await fetchDocuments(selectedKbName.value!)
  } catch { message.error(t('batchUploadFailed')) }
  finally { uploadingFiles.value = false }
}

async function deleteDocument(docId: string) {
  try {
    await api.delete(`/v2/knowledge-bases/documents/${docId}`)
    message.success(t('deleteSuccess'))
    await fetchDocuments(selectedKbName.value!)
  } catch { message.error(t('deleteFailed')) }
}

function toggleDocSelection(docId: string) {
  const idx = selectedDocIds.value.indexOf(docId)
  if (idx >= 0) selectedDocIds.value.splice(idx, 1)
  else selectedDocIds.value.push(docId)
}

function toggleAllDocs() {
  const allSelected = documents.value.every(d => selectedDocIds.value.includes(d.docId))
  if (allSelected) {
    selectedDocIds.value = selectedDocIds.value.filter(id => !documents.value.some(d => d.docId === id))
  } else {
    documents.value.forEach(d => {
      if (!selectedDocIds.value.includes(d.docId)) selectedDocIds.value.push(d.docId)
    })
  }
}

const allDocsSelected = computed(() =>
  documents.value.length > 0 && documents.value.every(d => selectedDocIds.value.includes(d.docId))
)

async function batchDeleteDocuments() {
  if (!selectedDocIds.value.length) { message.warning(t('selectDocsToDelete')); return }
  const results = await Promise.allSettled(
    selectedDocIds.value.map(id => api.delete(`/v2/knowledge-bases/documents/${id}`))
  )
  const failed = results.filter(r => r.status === 'rejected').length
  if (failed) message.warning(t('deletePartialSuccess', { success: selectedDocIds.value.length - failed, fail: failed }))
  else message.success(t('deleteAllSuccess', { n: selectedDocIds.value.length }))
  selectedDocIds.value = []
  await fetchDocuments(selectedKbName.value!)
}

async function batchExportDocuments() {
  if (!selectedDocIds.value.length) return
  if (!selectedKbName.value) return
  try {
    const response = await api.post(`/v2/knowledge-bases/${selectedKbName.value}/documents/batch-export`,
        { docIds: selectedDocIds.value },
        { responseType: 'blob' })
    const blob = new Blob([response as any], { type: 'application/zip' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${selectedKbName.value}-export.zip`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
    message.success(t('exportSuccess'))
  } catch { message.error(t('exportFailed')) }
}

function openMoveModal() {
  moveTargetKbName.value = ''
  showMoveModal.value = true
}

async function batchMoveDocuments() {
  if (!moveTargetKbName.value) { message.warning(t('selectTargetKb')); return }
  if (!selectedKbName.value) return
  movingDocs.value = true
  try {
    await api.post(`/v2/knowledge-bases/${selectedKbName.value}/documents/batch-move`, {
      docIds: selectedDocIds.value,
      targetKbName: moveTargetKbName.value,
    })
    message.success(t('moveSuccess', { n: selectedDocIds.value.length, target: moveTargetKbName.value }))
    showMoveModal.value = false
    selectedDocIds.value = []
    await fetchDocuments(selectedKbName.value)
  } catch (e: any) {
    message.error(e.response?.data?.message || t('moveFailed'))
  } finally {
    movingDocs.value = false
  }
}

function editDocument(doc: Document) {
  editForm.value = { docId: doc.docId, filename: doc.filename, textContent: doc.textContent }
  showEditModal.value = true
}

async function saveDocument() {
  if (!editForm.value.filename) { message.warning(t('filenameRequired')); return }
  saving.value = true
  try {
    await api.put(`/v2/knowledge-bases/documents/${editForm.value.docId}`, {
      filename: editForm.value.filename,
      textContent: editForm.value.textContent,
    })
    message.success(t('saveSuccess'))
    showEditModal.value = false
    if (selectedKbName.value) await fetchDocuments(selectedKbName.value)
  } catch (e: any) {
    message.error(e.response?.data?.message || t('saveFailed'))
  } finally {
    saving.value = false
  }
}

// --- 搜索 ---
async function searchAllKb() {
  if (!searchForm.value.query) { message.warning(t('enterSearchContent')); return }
  searching.value = true
  searchResults.value = []
  try {
    if (searchScope.value === 'current' && selectedKbName.value) {
      const res: any = await api.post(`/v2/knowledge-bases/${selectedKbName.value}/search`, {
        query: searchForm.value.query,
        topK: searchForm.value.topK,
        mode: searchMode.value,
        expandQuery: expandQuery.value,
      })
      searchResults.value = res.data || []
    } else {
      const res: any = await api.post('/v2/knowledge-bases/search', {
        query: searchForm.value.query,
        topK: searchForm.value.topK,
        mode: searchMode.value,
        expandQuery: expandQuery.value,
      })
      searchResults.value = res.data || []
    }
  } catch (e: any) {
    message.error(e.response?.data?.message || t('searchFailed'))
  } finally {
    searching.value = false
  }
}

</script>

<template>
  <div class="page-layout">
    <div class="kb-window">
      <!-- 头部：仿技能管理 -->
      <div class="kb-window__header">
        <span class="kb-window__title">{{ t('kbManagement') }}</span>
        <div class="kb-window__header-extra">
          <input v-model="searchForm.query" class="input kb-search-form__input" :placeholder="t('enterSearchContent')"
                 @keyup.enter="searchAllKb" />
          <div class="kb-search-form__group">
            <button class="btn btn-sm" :class="{ 'btn-primary': searchScope === 'current' }"
                    @click="searchScope = 'current'" :disabled="!selectedKbName">{{ t('currentKb') }}</button>
            <button class="btn btn-sm" :class="{ 'btn-primary': searchScope === 'all' }"
                    @click="searchScope = 'all'">{{ t('allKbs') }}</button>
          </div>
          <div class="kb-search-form__group">
            <button class="btn btn-sm" :class="{ 'btn-primary': searchMode === 'VECTOR' }"
                    @click="searchMode = 'VECTOR'">{{ t('vectorSearch') }}</button>
            <button class="btn btn-sm" :class="{ 'btn-primary': searchMode === 'FULLTEXT' }"
                    @click="searchMode = 'FULLTEXT'">{{ t('fulltextSearch') }}</button>
          </div>
          <div class="kb-search-form__topk">
            <span class="kb-search-form__topk-label">{{ t('topK') }}</span>
            <input v-model.number="searchForm.topK" class="input" type="number" min="1" max="100" style="width:64px" />
          </div>
          <label v-if="searchMode === 'VECTOR'" class="kb-search-form__expand" :title="t('expandQuery')">
            <input type="checkbox" v-model="expandQuery" />
            <span>{{ t('expandQuery') }}</span>
          </label>
          <button class="btn btn-sm btn-primary" :disabled="searching" @click="searchAllKb">
            {{ searching ? t('searching') : t('searchBtn') }}
          </button>
        </div>
      </div>
      <div class="kb-window__body">
        <!-- 左栏：KB 列表 -->
        <div class="kb-sidebar">
          <div class="kb-sidebar__search">
            <svg class="kb-sidebar__search-icon" viewBox="0 0 20 20" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.5">
              <circle cx="9" cy="9" r="5"/><path d="M13 13l4 4"/>
            </svg>
            <input v-model="kbSearchQuery" class="kb-sidebar__search-input" :placeholder="t('searchKb')" />
          </div>
          <div class="kb-sidebar__list">
            <div v-if="kbLoading" class="kb-sidebar__loading">{{ t('loading') }}</div>
            <button
              v-for="kb in filteredKbList"
              :key="kb.kbName"
              :class="['kb-list-item', { 'is-selected': selectedKbName === kb.kbName }]"
              @click="selectKb(kb.kbName)"
            >
              <div class="kb-list-item__info">
                <span class="kb-list-item__name">{{ kb.kbName }}</span>
                <template v-if="editingDescKb === kb.kbName">
                  <input v-model="descDraft" class="kb-desc-input kb-list-item__desc-input"
                         :maxlength="KB_DESC_MAX"
                         @blur="saveInlineDesc(kb.kbName)"
                         @keyup.enter="saveInlineDesc(kb.kbName)"
                         @keyup.escape.stop="cancelInlineEdit" />
                </template>
                <span v-else class="kb-list-item__desc" @dblclick="startInlineEdit(kb.kbName)">{{ kb.description || t('noDescription') }}</span>
              </div>
              <div class="kb-list-item__actions" @click.stop>
                <button class="kb-list-item__action" :title="t('edit')" @click="startInlineEdit(kb.kbName)">
                  <svg viewBox="0 0 20 20" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round">
                    <path d="M13.5 3.5l3 3L7 16H4v-3l9.5-9.5z"/>
                  </svg>
                </button>
                <button class="kb-list-item__action kb-list-item__action--danger" :title="t('delete')" @click="deleteKb(kb.kbName)">
                  <svg viewBox="0 0 20 20" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round">
                    <path d="M4 6h12M7 6V4a1 1 0 011-1h4a1 1 0 011 1v2m3 0v11a1 1 0 01-1 1H5a1 1 0 01-1-1V6h12z"/>
                  </svg>
                </button>
              </div>
            </button>
            <div v-if="!kbLoading && !filteredKbList.length" class="kb-sidebar__empty">
              <p>{{ t('noKnowledgeBases') }}</p>
            </div>
          </div>
          <div class="kb-sidebar__footer">
            <button class="btn btn-primary btn-block" @click="openCreateModal">{{ t('createKb') }}</button>
          </div>
        </div>

        <!-- 右栏：KB 详情 -->
        <div class="kb-content">
          <div v-if="!selectedKb" class="kb-content__empty">
            <svg viewBox="0 0 48 48" width="48" height="48" fill="none" stroke="currentColor" stroke-width="1" opacity="0.2">
              <path d="M8 6h14l4 4h14v28H8V6z"/>
              <path d="M20 22h8M20 26h8" stroke-width="1.2" stroke-linecap="round"/>
            </svg>
            <span class="kb-content__empty-text">{{ t('selectKbToView') }}</span>
          </div>
          <template v-else>
            <!-- 详情头 -->
            <div class="kb-content__header">
              <div style="display:flex;align-items:flex-start;justify-content:space-between;gap:var(--sp-unit-2);">
                <div>
                  <h2 class="kb-content__title">{{ selectedKb.kbName }}</h2>
                  <template v-if="editingDescKb === selectedKb.kbName">
                    <input v-model="descDraft" class="kb-desc-input kb-content__desc-input"
                           :maxlength="KB_DESC_MAX"
                           @blur="saveInlineDesc(selectedKb.kbName)"
                           @keyup.enter="saveInlineDesc(selectedKb.kbName)"
                           @keyup.escape.stop="cancelInlineEdit" />
                  </template>
                  <p v-else class="kb-content__desc" @dblclick="startInlineEdit(selectedKb.kbName)">{{ selectedKb.description || t('noDescription') }}</p>
                </div>
                <button class="btn btn-sm btn-primary" :disabled="rebuildingIndex" @click="rebuildIndex">
                  {{ rebuildingIndex ? (t('rebuildIndex') + '…') : t('rebuildIndex') }}
                </button>
              </div>
            </div>

            <!-- 搜索测试结果 -->
            <template v-if="searching || searchResults.length">
              <div class="kb-search-results" v-if="searchResults.length">
                <div v-for="(r, i) in searchResults" :key="r.chunkId || i" class="kb-search-result">
                  <div class="kb-search-result__meta">
                    <span class="kb-search-result__score">{{ t('searchResultScore') }} {{ (r.score * 100).toFixed(1) }}%</span>
                    <span v-if="r.contentType" class="tag tag-info">{{ r.contentType }}</span>
                    <span class="kb-search-result__kb">{{ r.kbName }}</span>
                  </div>
                  <div class="kb-search-result__text">{{ r.text }}</div>
                  <div v-if="r.metadata && Object.keys(r.metadata).length" class="kb-search-result__metadata">{{ JSON.stringify(r.metadata) }}</div>
                </div>
              </div>
              <div v-else class="empty-state" style="flex:1;display:flex;align-items:center;justify-content:center;"><p>{{ t('searching') }}</p></div>
            </template>
            <template v-else>
              <!-- 拖拽上传 -->
              <div class="kb-upload-zone"
                   @dragover.prevent="($event.currentTarget as HTMLElement)?.classList.add('drag-over')"
                   @dragleave="($event.currentTarget as HTMLElement)?.classList.remove('drag-over')"
                   @drop.prevent="handleFileDrop">
                <svg class="kb-upload-zone__icon" viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round">
                  <path d="M12 4v12M8 10l4-6 4 6"/>
                  <path d="M4 17v2a2 2 0 002 2h12a2 2 0 002-2v-2"/>
                </svg>
                <p v-if="!uploadingFiles">{{ t('dragOrClickToUpload') }}</p>
                <p v-else class="kb-upload-zone__uploading">{{ t('uploading') }}</p>
                <input type="file" accept=".txt,.md,.json,.csv,.html,.jpg,.jpeg,.png,.gif,.webp,.bmp,.svg" multiple style="display:none"
                       @change="handleFileSelect" id="kb-batch-file-input" />
                <button class="btn btn-sm" :disabled="uploadingFiles"
                        @click="($refs.batchFileInput as HTMLInputElement)?.click()">{{ t('selectFile') }}</button>
                <input ref="batchFileInput" type="file" accept=".txt,.md,.json,.csv,.html,.jpg,.jpeg,.png,.gif,.webp,.bmp,.svg" multiple
                       style="display:none" @change="handleFileSelect" />
              </div>

              <!-- 文档列表 -->
              <div class="kb-doc-card">
                <div class="kb-doc-card__header">
                  <span class="kb-doc-card__title">{{ t('documentsCount', { n: documents.length }) }}</span>
                  <div class="kb-doc-card__actions">
                    <button v-if="selectedDocIds.length" class="btn btn-sm btn-danger" @click="batchDeleteDocuments">
                      {{ t('deleteSelected', { n: selectedDocIds.length }) }}
                    </button>
                  <button v-if="selectedDocIds.length" class="btn btn-sm" @click="openMoveModal">{{ t('moveTo') }}</button>
                  <button v-if="selectedDocIds.length" class="btn btn-sm" @click="batchExportDocuments">{{ t('exportSelected', { n: selectedDocIds.length }) }}</button>
                </div>
              </div>
              <div class="kb-doc-card__body">
                <div class="table-container">
                  <table class="table">
                    <thead>
                      <tr>
                        <th style="width:36px">
                          <input type="checkbox" :checked="allDocsSelected" @change="toggleAllDocs" />
                        </th>
                        <th>{{ t('fileNameCol') }}</th>
                        <th style="width:60px">{{ t('typeCol') }}</th>
                        <th style="width:70px">{{ t('charCountCol') }}</th>
                        <th style="width:60px">{{ t('chunkCountCol') }}</th>
                        <th style="width:160px">{{ t('createTimeCol') }}</th>
                        <th style="width:100px">{{ t('actionsCol') }}</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-if="docsLoading">
                        <td colspan="7" class="td-empty">{{ t('loading') }}</td>
                      </tr>
                      <tr v-for="doc in documents" :key="doc.docId">
                        <td @click.stop>
                          <input type="checkbox" :checked="selectedDocIds.includes(doc.docId)"
                                 @change="toggleDocSelection(doc.docId)" />
                        </td>
                        <td>
                          <button class="btn-link" @click="editDocument(doc)">{{ doc.filename }}</button>
                        </td>
                        <td><span class="tag tag-info">{{ doc.fileType }}</span></td>
                        <td>{{ doc.charCount?.toLocaleString() }}</td>
                        <td>{{ doc.chunkCount }}</td>
                        <td style="white-space:nowrap">{{ formatTime(doc.createdAt) }}</td>
                        <td class="td-actions">
                          <button class="btn btn-sm btn-danger" @click="deleteDocument(doc.docId)">{{ t('delete') }}</button>
                          <button class="btn btn-sm" @click="editDocument(doc)">{{ t('modify') }}</button>
                        </td>
                      </tr>
                      <tr v-if="!docsLoading && !documents.length">
                        <td colspan="7" class="td-empty">{{ t('noDocuments') }}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
                <!-- 分页 -->
                <div class="kb-doc-pager">
                  <div class="pager">
                    <div class="pager__pages">
                      <button class="btn btn-sm" :disabled="docPage <= 1" @click="onDocPageChange(docPage - 1)">‹</button>
                      <template v-for="p in visibleDocPages" :key="p">
                        <span v-if="p === '...'" class="pager__ellipsis">…</span>
                        <button v-else class="btn btn-sm" :class="{ 'btn-primary': p === docPage }" @click="onDocPageChange(p as number)">{{ p }}</button>
                      </template>
                      <button class="btn btn-sm" :disabled="docPage >= docTotalPages" @click="onDocPageChange(docPage + 1)">›</button>
                    </div>
                    <span class="pager__info">{{ t('totalItems', { n: docTotal }) }}</span>
                    <span class="pager__goto">
                      {{ t('jumpTo') }} <input v-model.number="gotoDocPage" class="input pager__input" @change="handleGotoDocPage" /> {{ t('page') }}
                    </span>
                    <select class="select pager__size" :value="docPageSize" @change="onDocPageSizeChange(Number(($event.target as HTMLSelectElement).value))">
                      <option v-for="s in [10, 20, 50]" :key="s" :value="s">{{ s }}{{ t('itemsPerPage') }}</option>
                    </select>
                  </div>
                </div>
              </div>
            </div>
            </template>
          </template>
        </div>
      </div>
    </div>


    <!-- ========== 模态框：创建/编辑知识库 ========== -->
    <Teleport to="body">
      <div v-if="showKbModal" class="modal-overlay" @click.self="showKbModal = false">
        <div class="modal">
          <div class="modal__header">
            <span>{{ t('createKbTitle') }}</span>
            <button class="modal__close" @click="showKbModal = false">&times;</button>
          </div>
          <div class="modal__body">
            <div class="form-group">
              <label class="form-label">{{ t('nameRequired') }}</label>
              <input v-model="kbForm.kbName" class="input" :placeholder="t('kbNamePlaceholder')"
                     :maxlength="KB_NAME_MAX" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('kbDescription') }}（{{ (kbForm.description || '').length }}/{{ KB_DESC_MAX }}）</label>
              <textarea v-model="kbForm.description" class="input textarea" style="min-height:60px" :placeholder="t('kbDescPlaceholder')"
                        :maxlength="KB_DESC_MAX"></textarea>
            </div>
          </div>
          <div class="modal__footer">
            <button class="btn btn-ghost" @click="showKbModal = false">{{ t('cancel') }}</button>
            <button class="btn btn-primary" @click="saveKb">{{ t('save') }}</button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- ========== 模态框：文档编辑 ========== -->
    <Teleport to="body">
      <div v-if="showEditModal" class="modal-overlay" @click.self="showEditModal = false">
        <div class="modal" style="max-width:720px;">
          <div class="modal__header">
            <span>{{ t('editDocument') }}</span>
            <button class="modal__close" @click="showEditModal = false">&times;</button>
          </div>
          <div class="modal__body">
            <div class="form-group">
              <label class="form-label">{{ t('filename') }}</label>
              <input v-model="editForm.filename" class="input" :placeholder="t('filename')" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('editContent') }}</label>
              <textarea v-model="editForm.textContent" class="input textarea edit-content"
                        :placeholder="t('docContentPlaceholder')"></textarea>
            </div>
          </div>
          <div class="modal__footer">
            <button class="btn btn-ghost" @click="showEditModal = false">{{ t('cancel') }}</button>
            <button class="btn btn-primary" :disabled="saving" @click="saveDocument">
              {{ saving ? t('saving') : t('save') }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- ========== 模态框：移动文档 ========== -->
    <Teleport to="body">
      <div v-if="showMoveModal" class="modal-overlay" @click.self="showMoveModal = false">
        <div class="modal" style="max-width:480px;">
          <div class="modal__header">
            <span>{{ t('moveDocument') }}</span>
            <button class="modal__close" @click="showMoveModal = false">&times;</button>
          </div>
          <div class="modal__body">
            <p style="margin-bottom:12px;font-size:var(--fs-footnote);color:var(--clr-secondary);">
              {{ t('moveDocPrompt', { n: selectedDocIds.length }) }}
            </p>
            <div class="form-group">
              <label class="form-label">{{ t('targetKbRequired') }}</label>
              <select v-model="moveTargetKbName" class="select" style="width:100%">
                <option value="" disabled>{{ t('selectTargetKbOption') }}</option>
                <option v-for="kb in kbList.filter(k => k.kbName !== selectedKbName)" :key="kb.kbName" :value="kb.kbName">
                  {{ kb.kbName }}
                </option>
              </select>
            </div>
          </div>
          <div class="modal__footer">
            <button class="btn btn-ghost" @click="showMoveModal = false">{{ t('cancel') }}</button>
            <button class="btn btn-primary" :disabled="movingDocs || !moveTargetKbName" @click="batchMoveDocuments">
              {{ movingDocs ? t('moving') : t('move') }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>

/* ============================================
   统一窗口 — 16px 圆角 + 柔和阴影
   ============================================ */
.kb-window {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-radius: var(--rad-lg);
  background: var(--clr-bg);
  box-shadow: var(--shd-card);
  overflow: hidden;
  border: 1px solid var(--clr-hairline);
}

/* ============================================
   窗口主体 — 左右分栏
   ============================================ */
.kb-window__body {
  flex: 1;
  display: flex;
  min-height: 0;
}

/* ============================================
   左栏 — 知识库列表（#F7F7FA 浅灰，实色）
   ============================================ */
.kb-sidebar {
  width: 300px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--clr-bg-sidebar);
  border-right: 0.5px solid var(--clr-hairline);
}

/* 搜索框 */
.kb-sidebar__search {
  display: flex;
  align-items: center;
  gap: var(--sp-unit-1);
  padding: var(--sp-unit-1_5);
  flex-shrink: 0;
}
.kb-sidebar__search-icon {
  flex-shrink: 0;
  color: var(--clr-tertiary);
}
.kb-sidebar__search-input {
  flex: 1;
  min-width: 0;
  border: none;
  background: transparent;
  font-size: var(--fs-callout);
  font-family: inherit;
  color: var(--clr-label);
  outline: none;
}
.kb-sidebar__search-input::placeholder {
  color: var(--clr-quaternary);
}

/* 列表滚动区 */
.kb-sidebar__list {
  flex: 1;
  overflow-y: auto;
  padding: 0 var(--sp-unit-1);
}
.kb-sidebar__loading,
.kb-sidebar__empty {
  padding: var(--sp-unit-4) var(--sp-unit-2);
  text-align: center;
  font-size: var(--fs-footnote);
  color: var(--clr-quaternary);
}

/* 列表项 — 12px 圆角卡片 */
.kb-list-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 10px 12px;
  margin-bottom: 2px;
  border: none;
  border-radius: var(--rad-md);
  background: transparent;
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease-out);
  font-family: inherit;
  text-align: left;
}
.kb-list-item:hover {
  background: rgba(0, 0, 0, 0.03);
}
html.dark .kb-list-item:hover {
  background: rgba(255, 255, 255, 0.03);
}
.kb-list-item.is-selected {
  background: var(--clr-selected);
}

.kb-list-item__info {
  flex: 1;
  min-width: 0;
  overflow: hidden;
}
.kb-list-item__name {
  display: block;
  font-size: var(--fs-callout);
  font-weight: var(--fw-medium);
  color: var(--clr-label);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kb-list-item.is-selected .kb-list-item__name {
  color: var(--clr-accent);
}
.kb-list-item__desc {
  display: block;
  font-size: var(--fs-footnote);
  color: var(--clr-tertiary);
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: text;
}
.kb-desc-input {
  font-size: var(--fs-footnote);
  padding: 2px 6px;
  border: 1px solid var(--clr-border);
  border-radius: 4px;
  background: var(--clr-surface);
  color: var(--clr-text);
  width: 100%;
  outline: none;
  margin-top: 2px;
  box-sizing: border-box;
}
.kb-desc-input:focus {
  border-color: var(--clr-accent);
}

/* 列表项操作按钮（hover 显示） */
.kb-list-item__actions {
  display: flex;
  align-items: center;
  gap: 1px;
  flex-shrink: 0;
  opacity: 0;
  transition: opacity var(--dur-fast) var(--ease-out);
}
.kb-list-item:hover .kb-list-item__actions {
  opacity: 1;
}
.kb-list-item__action {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: none;
  border-radius: var(--rad-sm);
  background: transparent;
  color: var(--clr-tertiary);
  cursor: pointer;
  transition: var(--tr-fast);
  font-family: inherit;
}
.kb-list-item__action:hover {
  background: var(--clr-fill);
  color: var(--clr-label);
}
.kb-list-item__action--danger:hover {
  background: rgba(255, 59, 48, 0.08);
  color: var(--clr-red);
}

/* 底部操作 */
.kb-sidebar__footer {
  padding: var(--sp-unit-1_5);
  border-top: 1px solid var(--clr-hairline);
  flex-shrink: 0;
}

/* ============================================
   右栏 — 详情（纯白背景）
   ============================================ */
.kb-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--clr-bg);
}

.kb-content__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  flex: 1;
  gap: var(--sp-unit-1_5);
}
.kb-content__empty-text {
  font-size: var(--fs-callout);
  color: var(--clr-tertiary);
}

/* 详情头 */
.kb-content__header {
  padding: var(--sp-unit-2) var(--sp-unit-3);
  border-bottom: 1px solid var(--clr-hairline);
  flex-shrink: 0;
}
.kb-content__title {
  font-size: var(--fs-title-3);
  font-weight: var(--fw-semibold);
  margin: 0;
  color: var(--clr-label);
}
.kb-content__desc {
  font-size: var(--fs-footnote);
  color: var(--clr-tertiary);
  margin: 4px 0 0;
}

/* ============================================
   页面头部（仿技能管理 — 在 kb-window 内）
   ============================================ */
.kb-window__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--sp-unit-2) var(--sp-unit-3);
  border-bottom: 1px solid var(--clr-hairline);
  font-size: var(--fs-headline);
  font-weight: var(--fw-semibold);
  color: var(--clr-label);
  flex-shrink: 0;
}
.kb-window__title {
  white-space: nowrap;
}
.kb-window__header-extra {
  display: flex;
  gap: 8px;
  align-items: center;
}

/* ============================================
   拖拽上传区
   ============================================ */
.kb-upload-zone {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-unit-1);
  margin: var(--sp-unit-2);
  padding: var(--sp-unit-3);
  border: 2px dashed var(--clr-quaternary);
  border-radius: var(--rad-md);
  font-size: var(--fs-footnote);
  color: var(--clr-tertiary);
  cursor: pointer;
  transition: var(--tr-fast);
}
.kb-upload-zone:hover,
.kb-upload-zone.drag-over {
  border-color: var(--clr-accent);
  background: var(--clr-accent-soft);
  color: var(--clr-accent);
}
.kb-upload-zone__icon {
  color: var(--clr-tertiary);
}
.kb-upload-zone:hover .kb-upload-zone__icon,
.kb-upload-zone.drag-over .kb-upload-zone__icon {
  color: var(--clr-accent);
}
.kb-upload-zone__uploading {
  color: var(--clr-label);
  font-weight: var(--fw-medium);
}

/* ============================================
   文档卡片
   ============================================ */
.kb-doc-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  margin: 0 var(--sp-unit-2) var(--sp-unit-2);
  border: 1px solid var(--clr-hairline);
  border-radius: var(--rad-md);
  overflow: hidden;
  min-height: 0;
}
.kb-doc-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--sp-unit-1) var(--sp-unit-2);
  border-bottom: 1px solid var(--clr-hairline);
  background: var(--clr-bg);
  flex-shrink: 0;
}
.kb-doc-card__title {
  font-size: var(--fs-footnote);
  font-weight: var(--fw-semibold);
  color: var(--clr-secondary);
}
.kb-doc-card__actions {
  display: flex;
  align-items: center;
  gap: var(--sp-unit-1);
}
.kb-doc-card__body {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow-y: auto;
}

/* 分页 */
.kb-doc-pager {
  flex-shrink: 0;
  margin-top: auto;
  border-top: 1px solid var(--clr-hairline);
}
.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 10px 20px;
  font-size: var(--fs-footnote);
  color: var(--clr-label);
}
.pager__pages {
  display: flex;
  align-items: center;
  gap: 2px;
}
.pager__ellipsis {
  padding: 0 4px;
  color: var(--clr-tertiary);
  font-size: var(--fs-footnote);
}
.pager__info {
  color: var(--clr-secondary);
  white-space: nowrap;
}
.pager__goto {
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--clr-secondary);
  white-space: nowrap;
}
.pager__input {
  width: 48px;
  padding: 3px 6px;
  text-align: center;
  font-size: var(--fs-footnote);
}
.pager__size {
  width: auto;
  padding: 3px 6px;
  font-size: var(--fs-footnote);
}

/* ============================================
   检索标签
   ============================================ */
.kb-search-form {
  flex-shrink: 0;
  margin-bottom: var(--sp-unit-2);
}
.kb-search-form__row {
  display: flex;
  gap: var(--sp-unit-1);
  align-items: center;
}
.kb-search-form__input {
  flex: 1;
}
.kb-search-form__group {
  display: inline-flex;
  gap: 4px;
}
.kb-search-form__topk {
  display: flex;
  align-items: center;
  gap: 4px;
}
.kb-search-form__topk-label {
  font-size: var(--fs-footnote);
  color: var(--clr-secondary);
  white-space: nowrap;
}
.kb-search-form__expand {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: var(--fs-footnote);
  color: var(--clr-secondary);
  cursor: pointer;
  white-space: nowrap;
}
.kb-search-form__expand input[type="checkbox"] {
  margin: 0;
}

/* 搜索结果 */
.kb-search-results {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--sp-unit-1);
}
.kb-search-result {
  padding: var(--sp-unit-2);
  border: 1px solid var(--clr-hairline);
  border-radius: var(--rad-md);
  background: var(--clr-bg);
}
.kb-search-result__meta {
  display: flex;
  align-items: center;
  gap: var(--sp-unit-1);
  margin-bottom: var(--sp-unit-1);
}
.kb-search-result__score {
  font-size: var(--fs-footnote);
  font-weight: var(--fw-semibold);
  color: var(--clr-accent);
}
.kb-search-result__kb {
  font-size: var(--fs-footnote);
  color: var(--clr-tertiary);
  margin-left: auto;
}
.kb-search-result__text {
  font-size: var(--fs-callout);
  color: var(--clr-label);
  line-height: 1.7;
  white-space: pre-wrap;
}
.kb-search-result__metadata {
  margin-top: var(--sp-unit-1);
  padding: 6px 8px;
  font-size: var(--fs-footnote);
  color: var(--clr-tertiary);
  background: var(--clr-bg-secondary);
  border-radius: var(--rad-sm);
}

/* ============================================
   文档编辑
   ============================================ */
.edit-content {
  min-height: 50vh;
  font-family: var(--ff-mono);
  font-size: var(--fs-callout);
  line-height: 1.7;
}
</style>
