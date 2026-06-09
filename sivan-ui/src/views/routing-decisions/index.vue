<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useI18n } from '../../utils/i18n'
import { useMessage } from '../../utils/message'
import { formatTime } from '../../utils/time'
import api from '../../api'
import PaginatedTable from '../../components/common/PaginatedTable.vue'

const { t } = useI18n()

/** 解析 agent 列表：优先从 context.agentNames 取（多智能体），否则取 selectedAgentName */
function resolveAgentNames(row: RoutingDecision): string[] {
  if (row.context && Array.isArray(row.context.agentNames) && row.context.agentNames.length > 0) {
    return row.context.agentNames.filter(Boolean)
  }
  if (row.selectedAgentName) {
    return [row.selectedAgentName]
  }
  return []
}

const message = useMessage()

interface RoutingDecision {
  decisionId: string
  taskDescription: string
  selectedAgentName: string
  strategy: string
  success: boolean
  confidence: number
  context: Record<string, any> | null
  errorHint: string
  reasoning: string
  createdAt: string
}

interface PageResponse {
  items: RoutingDecision[]
  total: number
  page: number
  size: number
  totalPages: number
}

const data = ref<PageResponse>({ items: [], total: 0, page: 1, size: 20, totalPages: 0 })
const loading = ref(false)
const searchQuery = ref('')
const strategyFilter = ref('')
const successFilter = ref('')
const page = ref(1)
const pageSize = ref(20)

const showDetail = ref(false)
const selectedRow = ref<RoutingDecision | null>(null)

// 多选
const selectedIds = ref<Set<string>>(new Set())

const strategyMap = computed<Record<string, { label: string; cls: string }>>(() => ({
  semantic: { label: t('routingStrategySemantic'), cls: 'tag-info' },
  ml: { label: t('routingStrategyMl'), cls: 'tag-info' },
  context: { label: t('routingStrategyContext'), cls: 'tag-warning' },
  adaptive: { label: t('routingStrategyAdaptive'), cls: 'tag-success' },
  auto: { label: t('routingStrategyAuto'), cls: 'tag-info' },
  fallback: { label: t('routingStrategyFallback'), cls: 'tag-error' },
  CHAT: { label: t('routingStrategyChat'), cls: 'tag-info' },
  SINGLE_AGENT: { label: t('routingStrategySingleAgent'), cls: 'tag-info' },
  FORCE_INTENT: { label: t('routingStrategyForceIntent'), cls: 'tag-warning' },
  INTENT_CLASSIFICATION: { label: t('routingStrategyIntentClassification'), cls: 'tag-info' },
  AUTO_CREATE: { label: t('routingStrategyAutoCreate'), cls: 'tag-warning' },
}))

const strategies = computed(() => {
  const set = new Set(data.value.items.map(d => d.strategy).filter(Boolean))
  return [...set].sort()
})

// 前端过滤
const localFilteredData = computed(() => {
  let list = data.value.items
  if (successFilter.value === 'success') list = list.filter(d => d.success)
  else if (successFilter.value === 'fail') list = list.filter(d => !d.success)
  if (searchQuery.value) {
    const q = searchQuery.value.toLowerCase()
    list = list.filter(d =>
      d.taskDescription?.toLowerCase().includes(q) ||
      d.selectedAgentName?.toLowerCase().includes(q) ||
      d.reasoning?.toLowerCase().includes(q)
    )
  }
  return list
})

// 多选全选
const allFilteredSelected = computed(() => {
  const filtered = localFilteredData.value
  return filtered.length > 0 && filtered.every(d => selectedIds.value.has(d.decisionId))
})

function toggleAll() {
  const filtered = localFilteredData.value
  if (allFilteredSelected.value) {
    filtered.forEach(d => selectedIds.value.delete(d.decisionId))
    selectedIds.value = new Set(selectedIds.value)
  } else {
    filtered.forEach(d => selectedIds.value.add(d.decisionId))
    selectedIds.value = new Set(selectedIds.value)
  }
}

function toggleOne(id: string) {
  const next = new Set(selectedIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selectedIds.value = next
}

watch([searchQuery, successFilter], () => {
  selectedIds.value = new Set()
})

watch(strategyFilter, () => {
  page.value = 1
  selectedIds.value = new Set()
  fetch()
})

onMounted(() => { fetch() })

async function fetch() {
  loading.value = true
  try {
    const params: any = { page: page.value - 1, size: pageSize.value }
    if (strategyFilter.value) params.strategy = strategyFilter.value
    const res: any = await api.get('/routing-decisions', { params })
    data.value = res.data || { items: [], total: 0, page: 1, size: 20, totalPages: 0 }
  } catch (e: any) {
    console.error('获取路由决策失败', e)
    message.error(t('routingFetchFailed'))
  } finally { loading.value = false }
}

function openDetail(row: RoutingDecision) {
  selectedRow.value = row
  showDetail.value = true
}

function closeDetail() {
  showDetail.value = false
  selectedRow.value = null
}

async function deleteOne(id: string) {
  try {
    await api.delete(`/routing-decisions/${id}`)
    message.success(t('routingDeleted'))
    selectedIds.value.delete(id)
    selectedIds.value = new Set(selectedIds.value)
    await fetch()
  } catch (e: any) {
    message.error(e.response?.data?.message || t('routingDeleteFailed'))
  }
}

async function deleteSelected() {
  const ids = [...selectedIds.value]
  if (ids.length === 0) return
  try {
    await api.post('/routing-decisions/batch-delete', ids)
    message.success(t('routingBatchDeleted', { n: ids.length }))
    selectedIds.value = new Set()
    await fetch()
  } catch (e: any) {
    message.error(e.response?.data?.message || t('routingBatchDeleteFailed'))
  }
}

function onPageChange(p: number) {
  page.value = p
  selectedIds.value = new Set()
  fetch()
}

function onPageSizeChange(size: number) {
  pageSize.value = size
  page.value = 1
  selectedIds.value = new Set()
  fetch()
}
</script>

<template>
  <div class="page-layout">
    <PaginatedTable
      :current-page="page"
      :total-pages="data.totalPages"
      :total="data.total"
      :page-size="pageSize"
      @page-change="onPageChange"
      @page-size-change="onPageSizeChange"
    >
      <template #header>
        <span>{{ t('routingLog') }}</span>
        <div class="header-extra">
          <button class="btn btn-sm btn-danger" :disabled="selectedIds.size === 0" @click="deleteSelected">
            {{ t('deleteSelectedCount', { n: selectedIds.size }) }}
          </button>
          <input v-model="searchQuery" class="input" style="width:180px" :placeholder="t('search')" />
          <select v-model="strategyFilter" class="select" style="width:100px">
            <option value="">{{ t('allStrategies') }}</option>
            <option v-for="s in strategies" :key="s" :value="s">{{ strategyMap[s]?.label || s }}</option>
          </select>
          <select v-model="successFilter" class="select" style="width:90px">
            <option value="">{{ t('allResults') }}</option>
            <option value="success">{{ t('success') }}</option>
            <option value="fail">{{ t('failed') }}</option>
          </select>
        </div>
      </template>
      <template #thead>
        <tr>
          <th style="width:36px">
            <input type="checkbox" :checked="allFilteredSelected" @change="toggleAll" />
          </th>
          <th>{{ t('taskDescriptionCol') }}</th>
          <th>{{ t('strategyCol') }}</th>
          <th>{{ t('reasoningCol') }}</th>
          <th>{{ t('selectedAgent') }}</th>
          <th>{{ t('resultCol') }}</th>
          <th>{{ t('confidenceCol') }}</th>
          <th>{{ t('createTimeCol') }}</th>
          <th style="width:60px">{{ t('actionsCol') }}</th>
        </tr>
      </template>
      <template #tbody>
        <tr v-if="loading">
          <td colspan="9" class="td-empty">{{ t('loading') }}</td>
        </tr>
        <tr v-for="row in localFilteredData" :key="row.decisionId" class="clickable-row">
          <td @click.stop>
            <input type="checkbox" :checked="selectedIds.has(row.decisionId)" @change="toggleOne(row.decisionId)" />
          </td>
          <td @click="openDetail(row)" class="td-ellipsis" :title="row.taskDescription" style="max-width:180px">{{ row.taskDescription }}</td>
          <td @click="openDetail(row)"><span class="tag" :class="strategyMap[row.strategy]?.cls || ''">{{ strategyMap[row.strategy]?.label || row.strategy }}</span></td>
          <td @click="openDetail(row)" class="td-ellipsis" :title="row.reasoning" style="max-width:160px">{{ row.reasoning }}</td>
          <td @click="openDetail(row)">
            <span v-if="resolveAgentNames(row).length > 0" class="agent-tags">
              <span class="agent-tag" v-for="(name, i) in resolveAgentNames(row)" :key="i">{{ name }}</span>
            </span>
            <span v-else class="no-agent">{{ t('none') }}</span>
          </td>
          <td @click="openDetail(row)"><span class="tag" :class="row.success ? 'tag-success' : 'tag-error'">{{ row.success ? t('success') : t('failed') }}</span></td>
          <td @click="openDetail(row)">
            <div class="confidence-bar-wrap">
              <span class="confidence-text">{{ row.confidence != null ? (row.confidence * 100).toFixed(0) + '%' : '-' }}</span>
              <div v-if="row.confidence != null" class="confidence-bar-track">
                <div class="confidence-bar-fill" :style="{ width: (row.confidence * 100) + '%' }"></div>
              </div>
            </div>
          </td>
          <td @click="openDetail(row)" style="white-space:nowrap">{{ formatTime(row.createdAt) }}</td>
          <td @click.stop>
            <button class="btn btn-sm btn-danger" @click="deleteOne(row.decisionId)" :title="t('delete')">✕</button>
          </td>
        </tr>
        <tr v-if="!loading && !localFilteredData.length">
          <td colspan="9" class="td-empty">{{ t('noData') }}</td>
        </tr>
      </template>
    </PaginatedTable>

    <!-- 详情抽屉 -->
    <Teleport to="body">
      <div v-if="showDetail && selectedRow" class="drawer-overlay" @click.self="closeDetail">
        <div class="drawer">
          <div class="drawer__header">
            <span>{{ t('routingDetail') }}</span>
            <button class="drawer__close" @click="closeDetail">&times;</button>
          </div>
          <div class="drawer__body">
            <div class="detail-section">
              <div class="detail-label">{{ t('taskDescription') }}</div>
              <div class="detail-value">{{ selectedRow.taskDescription }}</div>
            </div>
            <div class="detail-section">
              <div class="detail-label">{{ t('selectedAgent') }}</div>
              <div class="detail-value">
                <span v-if="resolveAgentNames(selectedRow).length > 0" class="agent-tags">
                  <span class="agent-tag" v-for="(name, i) in resolveAgentNames(selectedRow)" :key="i">{{ name }}</span>
                </span>
                <span v-else class="no-agent">{{ t('none') }}</span>
              </div>
            </div>
            <div class="detail-row">
              <div class="detail-section" style="flex:1">
                <div class="detail-label">{{ t('strategyCol') }}</div>
                <div class="detail-value"><span class="tag" :class="strategyMap[selectedRow.strategy]?.cls || ''">{{ strategyMap[selectedRow.strategy]?.label || selectedRow.strategy }}</span></div>
              </div>
              <div class="detail-section" style="flex:1">
                <div class="detail-label">{{ t('resultCol') }}</div>
                <div class="detail-value"><span class="tag" :class="selectedRow.success ? 'tag-success' : 'tag-error'">{{ selectedRow.success ? t('success') : t('failed') }}</span></div>
              </div>
              <div class="detail-section" style="flex:1">
                <div class="detail-label">{{ t('confidence') }}</div>
                <div class="detail-value">{{ selectedRow.confidence != null ? (selectedRow.confidence * 100).toFixed(1) + '%' : '-' }}</div>
              </div>
            </div>
            <div class="detail-section">
              <div class="detail-label">{{ t('reasoning') }}</div>
              <div class="detail-value detail-reasoning">{{ selectedRow.reasoning || t('none') }}</div>
            </div>
            <div v-if="selectedRow.errorHint" class="detail-section">
              <div class="detail-label">{{ t('errorHint') }}</div>
              <div class="detail-value detail-error">{{ selectedRow.errorHint }}</div>
            </div>
            <div v-if="selectedRow.context" class="detail-section">
              <div class="detail-label">{{ t('context') }}</div>
              <div class="detail-value detail-context">
                <div v-for="(val, key) in selectedRow.context" :key="key" class="context-item">
                  <span class="context-key">{{ key }}:</span>
                  <span class="context-val">{{ typeof val === 'object' ? JSON.stringify(val) : val }}</span>
                </div>
              </div>
            </div>
            <div class="detail-section">
              <div class="detail-label">{{ t('createTimeCol') }}</div>
              <div class="detail-value">{{ formatTime(selectedRow.createdAt) }}</div>
            </div>
            <div class="detail-section">
              <div class="detail-label">{{ t('decisionId') }}</div>
              <div class="detail-value"><code class="inline-code">{{ selectedRow.decisionId }}</code></div>
            </div>
            <div class="detail-actions">
              <button class="btn btn-danger" @click="deleteOne(selectedRow.decisionId); closeDetail()">{{ t('deleteThisRecord') }}</button>
            </div>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.header-extra { display: flex; gap: 8px; align-items: center; }

.table-clickable .clickable-row { cursor: pointer; }
.table-clickable .clickable-row:hover td { background: var(--clr-accent-soft); }

.inline-code {
  font-family: var(--ff-mono);
  font-size: 0.9em;
  padding: 1px 6px;
  border-radius: var(--rad-xs);
  background: var(--clr-bg-secondary);
  color: var(--clr-label);
}

/* 智能体标签 */
.agent-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.agent-tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 10px;
  background: var(--clr-accent-soft, #e8f0fe);
  color: var(--clr-accent, #1a73e8);
  font-size: var(--fs-footnote, 11px);
  font-weight: var(--fw-medium, 500);
  white-space: nowrap;
  font-family: inherit;
}
.no-agent {
  color: var(--clr-tertiary, #999);
  font-style: italic;
  font-size: var(--fs-caption, 12px);
}

.confidence-bar-wrap { display: flex; align-items: center; gap: 6px; }
.confidence-text { font-size: var(--fs-footnote); min-width: 32px; }
.confidence-bar-track {
  width: 60px;
  height: 4px;
  border-radius: 2px;
  background: var(--clr-bg-tertiary);
  overflow: hidden;
}
.confidence-bar-fill {
  height: 100%;
  border-radius: 2px;
  background: var(--clr-accent);
  transition: width 0.2s ease;
}

/* 抽屉 */
.drawer-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.2);
  z-index: var(--z-drawer, 300);
  display: flex;
  justify-content: flex-end;
}
.drawer {
  width: 420px;
  max-width: 90vw;
  height: 100%;
  background: var(--clr-bg);
  box-shadow: var(--shd-modal);
  display: flex;
  flex-direction: column;
  animation: drawer-in 0.2s ease;
}
@keyframes drawer-in {
  from { transform: translateX(100%); }
  to { transform: translateX(0); }
}
.drawer__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--clr-hairline);
  font-size: var(--fs-headline);
  font-weight: var(--fw-semibold);
}
.drawer__close {
  width: 28px; height: 28px;
  display: flex; align-items: center; justify-content: center;
  border: none; background: transparent; color: var(--clr-tertiary);
  cursor: pointer; border-radius: var(--rad-md);
  font-family: inherit;
}
.drawer__close:hover { background: var(--clr-fill); color: var(--clr-label); }
.drawer__body {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.detail-section { margin-bottom: 16px; }
.detail-label {
  font-size: var(--fs-footnote);
  color: var(--clr-secondary);
  font-weight: var(--fw-medium);
  margin-bottom: 4px;
}
.detail-value {
  font-size: var(--fs-callout);
  color: var(--clr-label);
  line-height: 1.5;
}
.detail-reasoning {
  white-space: pre-wrap;
  background: var(--clr-bg-secondary);
  padding: 12px;
  border-radius: var(--rad-md);
  font-size: var(--fs-footnote);
  max-height: 300px;
  overflow-y: auto;
}
.detail-error {
  color: var(--clr-error, #e53e3e);
  background: var(--clr-bg-secondary);
  padding: 8px 12px;
  border-radius: var(--rad-md);
  font-size: var(--fs-footnote);
}
.detail-context {
  background: var(--clr-bg-secondary);
  padding: 12px;
  border-radius: var(--rad-md);
  font-size: var(--fs-footnote);
}
.context-item {
  display: flex;
  gap: 8px;
  padding: 2px 0;
}
.context-key {
  font-weight: var(--fw-medium);
  color: var(--clr-secondary);
  min-width: 80px;
}
.context-val {
  color: var(--clr-label);
  word-break: break-all;
}
.detail-row { display: flex; gap: 12px; }
.detail-actions { margin-top: 20px; padding-top: 16px; border-top: 1px solid var(--clr-hairline); }

</style>
