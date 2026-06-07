<script setup lang="ts">
import {computed, onMounted, onUnmounted, ref} from 'vue'
import {useRouter} from 'vue-router'
import {useI18n} from '../../utils/i18n'
import {useMessage} from '../../utils/message'
import {useSquadExecutionStore} from '../../stores/squadExecution'
import {deleteExecution, fetchSquads, type Squad} from '../../api/squad'
import api from '../../api'
import ExecutionStatusBadge from '../../components/squad-execution/ExecutionStatusBadge.vue'
import SquadSnapshotModal from '../../components/squad-execution/SquadSnapshotModal.vue'
import PaginatedTable from '../../components/common/PaginatedTable.vue'

const router = useRouter()
const store = useSquadExecutionStore()
const { t } = useI18n()
const message = useMessage()

const filterStatus = ref('')
const searchText = ref('')
const squadFilter = ref('')
const page = ref(0)
const pageSize = ref(20)
const deleting = ref<string | null>(null)
const selectedIds = ref(new Set<string>())

const allPageSelected = computed(() =>
  paged.value.length > 0 && paged.value.every(e => selectedIds.value.has(e.executionId)))

function toggleAll() {
  if (allPageSelected.value) { selectedIds.value = new Set() }
  else { paged.value.forEach(e => selectedIds.value.add(e.executionId)) }
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
    await api.post('/squads/executions/batch-delete', ids)
    store.executions = store.executions.filter(e => !ids.includes(e.executionId))
    selectedIds.value = new Set()
  } catch { message.error(t('executionBatchDeleteFailed')) }
}

const squads = ref<Squad[]>([])
const snapshotExecution = ref<any>(null)

async function loadSquadNames() {
  try {
    const res: any = await fetchSquads()
    squads.value = res.data?.items || res.data || []
  } catch { /* noop */ }
}

function filtered() {
  let list = store.executions
  if (filterStatus.value) list = list.filter((e) => e.status === filterStatus.value)
  if (searchText.value) {
    const q = searchText.value.toLowerCase()
    list = list.filter((e) => e.taskDescription.toLowerCase().includes(q))
  }
  if (squadFilter.value) list = list.filter((e) => e.squadName === squadFilter.value || e.squadId === squadFilter.value)
  return list
}

const paged = computed(() => {
  const start = page.value * pageSize.value
  return filtered().slice(start, start + pageSize.value)
})

const totalFiltered = computed(() => filtered().length)
const totalPages = computed(() => Math.ceil(totalFiltered.value / pageSize.value) || 1)

function onFilterChange() { page.value = 0 }

function openSnapshot(execution: any) { snapshotExecution.value = execution }

async function handleDelete(execId: string) {
  if (!confirm(t('confirmDelete')?.toString())) return
  deleting.value = execId
  try {
    await deleteExecution(execId)
    store.executions = store.executions.filter(e => e.executionId !== execId)
  } catch { message.error(t('deleteFailed')) }
  finally { deleting.value = null }
}

onMounted(() => { store.startDashboard(); loadSquadNames() })
onUnmounted(() => store.stopDashboard())
</script>

<template>
  <div class="page-layout">
    <div class="dg-stats">
      <div v-for="s in [
        { n: store.stats.running, l: t('statusRunning'), c: 'dg-stat--running', i: '●' },
        { n: store.stats.hitlWaiting, l: t('statusHitl'), c: 'dg-stat--hitl', i: '⏳' },
        { n: store.stats.todayDone, l: t('today') + t('statusCompleted'), c: 'dg-stat--done', i: '✓' },
        { n: store.stats.todayFailed, l: t('today') + t('statusFailed'), c: 'dg-stat--failed', i: '✗' },
      ]" :key="s.l" :class="['dg-stat', s.c]">
        <div class="dg-stat__n">{{ s.n }}</div>
        <div class="dg-stat__l">{{ s.i }} {{ s.l }}</div>
      </div>
    </div>

    <PaginatedTable
      :current-page="page"
      :total-pages="totalPages"
      :total="totalFiltered"
      :page-size="pageSize"
      :page-size-options="[10, 20, 50, 100]"
      @page-change="page = $event"
      @page-size-change="pageSize = $event; page = 0"
    >
      <template #header>
        <span>{{ t('squadHome') }}</span>
        <div class="header-extra">
          <button v-if="selectedIds.size > 0" class="btn btn-sm btn-danger" @click="deleteSelected">{{ t('deleteSelectedCount', { n: selectedIds.size }) }}</button>
          <select v-model="filterStatus" class="dg-select" @change="onFilterChange">
            <option value="">{{ t('allStatuses') }}</option>
            <option value="RUNNING">{{ t('statusRunning') }}</option>
            <option value="HITL_PENDING">{{ t('statusHitl') }}</option>
            <option value="COMPLETED">{{ t('statusCompleted') }}</option>
            <option value="FAILED">{{ t('statusFailed') }}</option>
          </select>
          <select v-model="squadFilter" class="dg-select" style="width:140px" @change="onFilterChange">
            <option value="">{{ t('allSquads') }}</option>
            <option v-for="sq in squads" :key="sq.squadId" :value="sq.squadId">{{ sq.name }}</option>
          </select>
          <input v-model="searchText" class="dg-search" :placeholder="t('search')" />
          <RouterLink to="/squads/orchestration" class="dg-link">{{ t('viewSquad') }} →</RouterLink>
        </div>
      </template>
      <template #thead>
        <tr>
          <th style="width:30px"><input type="checkbox" :checked="allPageSelected" @change="toggleAll"></th>
          <th>ID</th>
          <th>{{ t('squadNameCol') }}</th>
          <th>{{ t('taskDescriptionCol') }}</th>
          <th>{{ t('status') }}</th>
          <th>{{ t('currentPhaseCol') }}</th>
          <th>{{ t('durationCol') }}</th>
          <th style="width:60px"></th>
        </tr>
      </template>
      <template #tbody>
        <tr v-if="store.dashboardLoading && store.executions.length === 0"><td colspan="8" class="dg-empty">{{ t('loading') }}</td></tr>
        <tr v-else-if="filtered().length === 0"><td colspan="8" class="dg-empty">{{ t('noExecutionRecords') }}</td></tr>
        <tr v-for="e in paged" :key="e.executionId" class="dg-row" @click="router.push('/squads/executions/' + e.executionId)">
          <td @click.stop><input type="checkbox" :checked="selectedIds.has(e.executionId)" @change="toggleOne(e.executionId)"></td>
          <td class="dg-mono">#{{ e.executionId.slice(0, 8) }}</td>
          <td>
            <span v-if="e.squadName" class="dg-squad-link" @click.stop="openSnapshot(e)">{{ e.squadName }}</span>
            <span v-else class="dg-mono">--</span>
          </td>
          <td class="dg-task">{{ e.taskDescription.length > 60 ? e.taskDescription.slice(0, 60) + '...' : e.taskDescription }}</td>
          <td><ExecutionStatusBadge :status="e.status" /></td>
          <td>{{ e.currentPhase != null ? e.currentPhase + 1 : '--' }}</td>
          <td class="dg-mono">{{ store.computeDuration(e.startedAt, e.completedAt || e.pausedAt || undefined) }}</td>
          <td @click.stop>
            <button
              class="dg-del-btn"
              :disabled="deleting === e.executionId"
              @click="handleDelete(e.executionId)"
            >{{ deleting === e.executionId ? '...' : '✕' }}</button>
          </td>
        </tr>
      </template>
    </PaginatedTable>

    <SquadSnapshotModal
      v-if="snapshotExecution"
      :execution="snapshotExecution"
      @close="snapshotExecution = null"
    />
  </div>
</template>

<style scoped>
.dg-link { margin-left: auto; font-size: var(--fs-callout); color: var(--clr-accent); text-decoration: none; white-space: nowrap; }
.header-extra { display: flex; gap: 8px; align-items: center; }
.dg-stats { display: flex; gap: 12px; }
.dg-stat { flex: 1; padding: 14px 16px; border-radius: var(--rad-md); background: var(--clr-bg); border: 1px solid var(--clr-hairline); }
.dg-stat__n { font-size: var(--fs-title-1); font-weight: var(--fw-bold); }
.dg-stat__l { font-size: var(--fs-caption); color: var(--clr-tertiary); margin-top: 4px; }
.dg-stat--running { border-left: 3px solid var(--clr-accent); } .dg-stat--running .dg-stat__n { color: var(--clr-accent); }
.dg-stat--hitl { border-left: 3px solid var(--clr-status-hitl); } .dg-stat--hitl .dg-stat__n { color: var(--clr-status-hitl); }
.dg-stat--done { border-left: 3px solid var(--clr-green); } .dg-stat--done .dg-stat__n { color: var(--clr-green); }
.dg-stat--failed { border-left: 3px solid var(--clr-red); } .dg-stat--failed .dg-stat__n { color: var(--clr-red); }
.dg-select { padding: 6px 10px; border: 1px solid var(--clr-hairline); border-radius: var(--rad-sm); font-size: var(--fs-caption); color: var(--clr-label); background: var(--clr-bg); }
.dg-search { flex: 1; max-width: 240px; padding: 6px 10px; border: 1px solid var(--clr-hairline); border-radius: var(--rad-sm); font-size: var(--fs-caption); color: var(--clr-label); background: var(--clr-bg); }
.dg-row { cursor: pointer; } .dg-row:hover { background: var(--clr-fill-hover); }
.dg-mono { font-family: var(--ff-mono); font-size: var(--fs-caption); color: var(--clr-quaternary); }
.dg-task { max-width: 320px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dg-squad-link { color: var(--clr-accent); cursor: pointer; font-weight: var(--fw-medium); }
.dg-squad-link:hover { text-decoration: underline; }
.dg-empty { text-align: center; padding: 32px; color: var(--clr-tertiary); }
.dg-del-btn {
  padding: 2px 8px; border: 1px solid var(--clr-hairline); border-radius: var(--rad-sm);
  background: transparent; cursor: pointer; font-size: var(--fs-caption); color: var(--clr-red);
}
.dg-del-btn:hover:not(:disabled) { background: var(--clr-red-bg); }
.dg-del-btn:disabled { opacity: 0.5; cursor: default; }
</style>
