<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from '../../utils/i18n'

const { t } = useI18n()

interface DailyPoint {
  date: string
  totalTokens: number
  level: number
}

interface TrendPoint {
  bucket: number
  totalInput: number
  totalOutput: number
}

const props = withDefaults(defineProps<{
  data: DailyPoint[]
  days: number
  trendData?: TrendPoint[]
}>(), {
  days: 90,
  trendData: undefined,
})

/** 将 Date 格式化为 yyyy-MM-dd（本地时间，与后端 java.sql.Date.toString 对齐）。 */
function formatYMD(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const d_ = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${d_}`
}

/**
 * 构建周网格：从今天往前回溯 days 天，对齐到周一，覆盖期间所有日历日。
 * 无数据的日期保留空占位符，保证日期连续性。
 */
const weeks = computed(() => {
  // 日期 → 数据点映射
  const map = new Map<string, DailyPoint>()
  for (const p of props.data) map.set(p.date, p)

  const today = new Date()
  today.setHours(0, 0, 0, 0)

  // 起始日期：today - (days - 1)
  const rangeStart = new Date(today)
  rangeStart.setDate(today.getDate() - (props.days - 1))

  // 对齐到起始日期所在周的周一
  const gridStart = new Date(rangeStart)
  const dow = gridStart.getDay() // 0=Sun … 6=Sat
  gridStart.setDate(gridStart.getDate() + (dow === 0 ? -6 : 1 - dow))

  const result: { cells: { date: string | null; totalTokens: number; level: number; tooltip: string }[] }[] = []
  const cursor = new Date(gridStart)

  while (cursor <= today) {
    const cells: { date: string | null; totalTokens: number; level: number; tooltip: string }[] = []
    for (let d = 0; d < 7; d++) {
      const dateStr = formatYMD(cursor)
      const point = map.get(dateStr)
      const tokens = point?.totalTokens ?? 0
      cells.push({
        date: point ? dateStr : null,
        totalTokens: tokens,
        level: point?.level ?? 0,
        tooltip: point
          ? `${dateStr}: ${tokens.toLocaleString()} tokens`
          : `${dateStr}: ${t('noConsumptionData')}`,
      })
      cursor.setDate(cursor.getDate() + 1)
    }
    result.push({ cells })
  }

  return result
})

// ── 30 分钟粒度模式 ──

const TREND_ROWS = 6 // 48 ÷ 8 = 6 rows per column

const trendSlots = computed(() => {
  if (!props.trendData || !props.trendData.length) return []
  const max = Math.max(...props.trendData.map(p => p.totalInput + p.totalOutput), 1)
  const result: {
    bucket: number
    label: string
    totalInput: number
    totalOutput: number
    totalTokens: number
    level: number
  }[] = []
  for (let b = 0; b < 48; b++) {
    const point = props.trendData.find(p => p.bucket === b)
    const total = point ? point.totalInput + point.totalOutput : 0
    const h = Math.floor(b / 2)
    const m = b % 2 === 0 ? '00' : '30'
    const label = `${String(h).padStart(2, '0')}:${m}`
    result.push({
      bucket: b,
      label,
      totalInput: point?.totalInput ?? 0,
      totalOutput: point?.totalOutput ?? 0,
      totalTokens: total,
      level: total === 0 ? 0 : Math.min(4, Math.ceil((total / max) * 4)),
    })
  }
  return result
})

/** 8 列 × 6 行 = 48 slots，每列 3 小时（00:00-02:30, 03:00-05:30, …）。 */
const trendGrid = computed(() => {
  if (!trendSlots.value.length) return []
  const cols = 8
  const result: { cells: typeof trendSlots.value }[] = []
  for (let c = 0; c < cols; c++) {
    const start = c * TREND_ROWS
    result.push({ cells: trendSlots.value.slice(start, start + TREND_ROWS) })
  }
  return result
})

function trendCellTooltip(cell: typeof trendSlots.value[number]): string {
  return `${cell.label} — ${t('input')} ${cell.totalInput.toLocaleString()} / ${t('output')} ${cell.totalOutput.toLocaleString()}`
}
</script>

<template>
  <div v-if="trendSlots.length || weeks.length" class="graph">
    <div v-if="trendSlots.length" class="graph-grid graph-grid--trend">
      <div v-for="(col, ci) in trendGrid" :key="ci" class="week-col">
        <div
          v-for="(cell, si) in col.cells"
          :key="si"
          class="cell"
          :class="[cell.totalTokens > 0 ? `level-${cell.level}` : 'empty']"
          :title="trendCellTooltip(cell)"
        />
      </div>
    </div>
    <div v-else class="graph-grid">
      <div v-for="(week, wi) in weeks" :key="wi" class="week-col">
        <div
          v-for="(cell, ci) in week.cells"
          :key="ci"
          class="cell"
          :class="[cell.date ? `level-${cell.level}` : 'empty']"
          :data-tooltip="cell.tooltip"
          :title="cell.tooltip"
        />
      </div>
    </div>
    <div class="legend">
      <span class="legend-label">{{ t('less') }}</span>
      <span class="legend-swatch level-0" />
      <span class="legend-swatch level-1" />
      <span class="legend-swatch level-2" />
      <span class="legend-swatch level-3" />
      <span class="legend-swatch level-4" />
      <span class="legend-label">{{ t('more') }}</span>
    </div>
  </div>
  <div v-else class="empty">
    <p>{{ t('noConsumption') }}</p>
  </div>
</template>

<style scoped>
.graph {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.graph-grid {
  display: flex;
  gap: 1px;
  overflow-x: auto;
  padding-bottom: 1px;
}
.graph-grid--trend {
  overflow: visible;
}
.week-col {
  display: flex;
  flex-direction: column-reverse;
  gap: 1px;
}
.cell {
  width: 12px;
  height: 12px;
  border-radius: 2px;
  background: var(--clr-fill);
  flex-shrink: 0;
  transition: transform 0.1s;
}
.cell:hover {
  transform: scale(1.3);
}
.cell.empty {
  background: color-mix(in srgb, var(--clr-fill) 88%, var(--clr-tertiary) 12%);
  padding: 0;
}
.level-0 { background: var(--clr-fill); }
.level-1 { background: color-mix(in srgb, var(--clr-accent) 15%, transparent); }
.level-2 { background: color-mix(in srgb, var(--clr-accent) 32%, transparent); }
.level-3 { background: color-mix(in srgb, var(--clr-accent) 52%, transparent); }
.level-4 { background: color-mix(in srgb, var(--clr-accent) 75%, transparent); }
.legend {
  display: flex;
  align-items: center;
  gap: 2px;
}
.legend-label {
  font-size: 9px;
  color: var(--clr-tertiary);
  line-height: 1;
}
.legend-swatch {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 2px;
}
.empty {
  text-align: center;
  color: var(--clr-tertiary);
  font-size: var(--fs-footnote);
  padding: 8px 0;
}
</style>
