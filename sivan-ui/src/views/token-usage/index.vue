<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from '../../utils/i18n'
import api from '../../api'
import { fetchDailyConsumption, type DailyConsumption } from '../../api/token-usage'
import ContributionGraph from '../../components/token-usage/ContributionGraph.vue'

const { t } = useI18n()

interface PeriodSummary {
  totalInput: number
  totalOutput: number
  totalTokens: number
}
interface Summary {
  today: PeriodSummary
  last7Days: PeriodSummary
  last30Days: PeriodSummary
  last90Days: PeriodSummary
}
interface TrendPoint {
  bucket: number
  totalInput: number
  totalOutput: number
}
interface AgentSummary {
  agentId: string
  agentName: string | null
  totalInput: number
  totalOutput: number
  totalTokens: number
}
interface ModelSummary {
  modelName: string
  totalInput: number
  totalOutput: number
  totalTokens: number
}

const summary = ref<Summary | null>(null)
const trendPoints = ref<TrendPoint[]>([])
const agentData = ref<AgentSummary[]>([])
const modelData = ref<ModelSummary[]>([])
const consumptionData = ref<DailyConsumption[]>([])
const loading = ref(false)

const periodLabels = computed<Record<string, string>>(() => ({
  today: t('today'),
  last7Days: t('last7Days'),
  last30Days: t('last30Days'),
  last90Days: t('last90Days'),
}))

const periodDays: Record<string, number> = {
  last7Days: 7,
  last30Days: 30,
  last90Days: 90,
}

const summaryEntries = computed(() => {
  if (!summary.value) return []
  return Object.entries(summary.value) as [string, PeriodSummary][]
})

// 今日趋势汇总
const trendTotal = computed(() => {
  if (!trendPoints.value.length) return { input: 0, output: 0, total: 0 }
  let input = 0, output = 0
  for (const pt of trendPoints.value) {
    input += pt.totalInput
    output += pt.totalOutput
  }
  return { input, output, total: input + output }
})

function bucketLabel(bucket: number): string {
  const h = Math.floor(bucket / 2)
  const m = bucket % 2 === 0 ? '00' : '30'
  return `${String(h).padStart(2, '0')}:${m}`
}

function barHeight(pt: TrendPoint): number {
  const max = Math.max(...trendPoints.value.map(p => p.totalInput + p.totalOutput), 1)
  return Math.max(4, ((pt.totalInput + pt.totalOutput) / max) * 80)
}

onMounted(async () => {
  loading.value = true
  try {
    const [sumRes, trendRes, agentRes, modelRes] = await Promise.all([
      api.get('/token-usage/summary'),
      api.get('/token-usage/daily-trend'),
      api.get('/token-usage/by-agent'),
      api.get('/token-usage/by-model'),
    ])
    summary.value = (sumRes as any).data || null
    trendPoints.value = (trendRes as any).data || []
    agentData.value = (agentRes as any).data || []
    modelData.value = (modelRes as any).data || []
  } catch { /* noop */ } finally {
    loading.value = false
  }

  try {
    consumptionData.value = await fetchDailyConsumption(90)
  } catch { /* noop */ }
})
</script>

<template>
  <div class="page-layout">
    <div class="card">
      <div class="card__header">{{ t('tokenOverview') }}</div>
      <div class="card__body">
        <div v-if="summaryEntries.length" class="summary-grid">
          <div v-for="[key, period] in summaryEntries" :key="key" class="summary-card">
            <div class="summary-info">
              <div class="summary-label">{{ periodLabels[key] || key }}</div>
              <div class="summary-value">{{ period.totalTokens.toLocaleString() }}</div>
              <div class="summary-detail">
                <div>{{ t('input') }} {{ period.totalInput.toLocaleString() }}</div>
                <div>{{ t('output') }} {{ period.totalOutput.toLocaleString() }}</div>
              </div>
            </div>
            <div v-if="key === 'today'" class="summary-graph">
              <ContributionGraph :data="consumptionData" :days="1" :trend-data="trendPoints" />
            </div>
            <div v-else-if="periodDays[key]" class="summary-graph">
              <ContributionGraph :data="consumptionData" :days="periodDays[key]" />
            </div>
          </div>
        </div>
        <div v-else class="empty-state"><p>{{ t('noConsumptionData') }}</p></div>
      </div>
    </div>

    <div class="card">
      <div class="card__header">{{ t('todayTrend') }}</div>
      <div class="card__body">
        <div v-if="trendPoints.length">
          <!-- 今日汇总 -->
          <div class="trend-summary">
            <span>{{ t('today') }} {{ t('input') }} <b>{{ trendTotal.input.toLocaleString() }}</b></span>
            <span>{{ t('output') }} <b>{{ trendTotal.output.toLocaleString() }}</b></span>
            <span>{{ t('totalCol') }} <b>{{ trendTotal.total.toLocaleString() }}</b></span>
          </div>
          <!-- 趋势柱状图 -->
          <div class="trend-scroll">
            <div class="trend-chart">
              <div v-for="pt in trendPoints" :key="pt.bucket" class="trend-bar-group">
                <div class="trend-bar" :style="{ height: barHeight(pt) + 'px' }" :title="`${bucketLabel(pt.bucket)} — 入 ${pt.totalInput.toLocaleString()} / 出 ${pt.totalOutput.toLocaleString()}`"></div>
                <div class="trend-label">{{ bucketLabel(pt.bucket) }}</div>
              </div>
            </div>
          </div>
        </div>
        <div v-else class="empty-state"><p>{{ t('noDataToday') }}</p></div>
      </div>
    </div>

    <div class="table-row">
      <div class="card table-card">
        <div class="card__header">{{ t('byAgent') }}</div>
        <div>
          <table class="table">
            <thead>
              <tr>
                <th>{{ t('agentCol') }}</th>
                <th>{{ t('inputTokenCol') }}</th>
                <th>{{ t('outputTokenCol') }}</th>
                <th>{{ t('totalCol') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in agentData" :key="row.agentId">
                <td>{{ row.agentName || row.agentId?.slice(0, 8) || '(无)' }}</td>
                <td>{{ row.totalInput.toLocaleString() }}</td>
                <td>{{ row.totalOutput.toLocaleString() }}</td>
                <td>{{ row.totalTokens.toLocaleString() }}</td>
              </tr>
              <tr v-if="!agentData.length">
                <td colspan="4" class="td-empty">{{ t('noConsumptionData') }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <div class="card table-card">
        <div class="card__header">{{ t('byModel') }}</div>
        <div>
          <table class="table">
            <thead>
              <tr>
                <th>{{ t('modelCol') }}</th>
                <th>{{ t('inputTokenCol') }}</th>
                <th>{{ t('outputTokenCol') }}</th>
                <th>{{ t('totalCol') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in modelData" :key="row.modelName">
                <td>{{ row.modelName }}</td>
                <td>{{ row.totalInput.toLocaleString() }}</td>
                <td>{{ row.totalOutput.toLocaleString() }}</td>
                <td>{{ row.totalTokens.toLocaleString() }}</td>
              </tr>
              <tr v-if="!modelData.length">
                <td colspan="4" class="td-empty">{{ t('noConsumptionData') }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.summary-grid {
  display: flex;
  gap: var(--sp-unit-2);
  flex-wrap: wrap;
}
.summary-card {
  flex: 1;
  min-width: 200px;
  padding: var(--sp-unit-1) var(--sp-unit-1_5);
  border-radius: var(--rad-md);
  background: var(--clr-bg);
  border: 1px solid var(--clr-hairline);
  display: flex;
  gap: var(--sp-unit-1);
  align-items: center;
  justify-content: space-between;
}
.summary-info {
  flex-shrink: 0;
}
.summary-graph {
  display: flex;
  align-items: center;
  overflow: hidden;
}
.summary-label {
  font-size: var(--fs-footnote);
  font-weight: var(--fw-medium);
  margin-bottom: 2px;
  color: var(--clr-secondary);
}
.summary-value {
  font-size: var(--fs-headline);
  font-weight: var(--fw-semibold);
  color: var(--clr-accent);
}
.summary-detail {
  font-size: var(--fs-caption);
  color: var(--clr-tertiary);
  margin-top: 2px;
}
.trend-summary {
  display: flex;
  gap: var(--sp-unit-2);
  font-size: var(--fs-caption);
  color: var(--clr-tertiary);
}
.trend-summary b {
  color: var(--clr-label);
}
.trend-scroll {
  overflow-x: auto;
}
.trend-chart {
  display: flex;
  gap: 2px;
  padding: var(--sp-unit-1) 0;
  min-width: 960px;
  align-items: flex-end;
  height: 96px;
}
.trend-bar-group {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  height: 100%;
}
.trend-label {
  font-size: 9px;
  color: var(--clr-tertiary);
  margin-top: 2px;
}
.trend-bar {
  width: 100%;
  background: var(--clr-accent);
  border-radius: 2px 2px 0 0;
  opacity: 0.7;
  min-height: 2px;
}
.trend-bar:hover {
  opacity: 1;
}
.table-row {
  display: flex;
  gap: var(--sp-unit-2);
}
.table-card {
  flex: 1;
}
</style>
