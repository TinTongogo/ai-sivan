import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  fetchExecutionDetail, fetchExecutionContracts,
  fetchHitlReview, extendHitlTimeout,
  connectDashboardStream,
  type SquadExecution, type Contract, type HitlReview, type ExecutionStats,
} from '../api/squad'

export interface ContractEdge {
  fromPhase: number; toPhase: number; contracts: Contract[]
  dataSize: number; status: 'pending' | 'streaming' | 'complete'
}

// ── 工具函数 ──

/** 计算执行持续时间，endedAt 为空时计算到当前时间。 */
function computeDuration(startedAt?: string, endedAt?: string): string {
  if (!startedAt) return '--'
  const start = new Date(startedAt).getTime()
  const end = endedAt ? new Date(endedAt).getTime() : Date.now()
  const diff = Math.max(0, end - start)
  const m = Math.floor(diff / 60000)
  const s = Math.floor((diff % 60000) / 1000)
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

/**
 * 将扁平契约列表按阶段分组，构建阶段间的有向边。
 * 仅保留有 targetAgent 的契约（排除广播消息），
 * 用于 PhaseFlowView 中展示阶段间的数据流转。
 * squadMode 控制边的拓扑结构以匹配 DAG 布局。
 */
function buildContractEdges(all: Contract[], squadMode?: string): ContractEdge[] {
  const byPhase = new Map<number, Contract[]>()
  for (const c of all) {
    if (!byPhase.has(c.phase)) byPhase.set(c.phase, [])
    byPhase.get(c.phase)!.push(c)
  }
  const sorted = [...byPhase.entries()].sort((a, b) => a[0] - b[0])
  if (sorted.length < 2) return []

  const edges: ContractEdge[] = []
  const mode = squadMode || 'SEQUENTIAL'

  if (mode === 'HIERARCHICAL') {
    // 阶段 0 → 其余所有阶段
    const [rootPhase, rootContracts] = sorted[0]
    const rootEc = rootContracts.filter((c) => c.targetAgent != null)
    for (let i = 1; i < sorted.length; i++) {
      const [toPhase, toContracts] = sorted[i]
      const ec = [...rootEc, ...toContracts.filter((c) => c.targetAgent != null)]
      edges.push({
        fromPhase: rootPhase,
        toPhase,
        contracts: ec,
        dataSize: ec.reduce((sum, c) => sum + (c.content?.length || 0), 0),
        status: 'complete',
      })
    }
  } else if (mode === 'PARALLEL' || mode === 'CONSENSUS') {
    // 并行模式：dagre 使用虚拟起止节点布局，不产生顺序边
    // 跨阶段数据流通过契约自身的 targetAgent 关联，不依赖阶段顺序
  } else {
    // SEQUENTIAL / CONDITIONAL: i → i+1
    for (let i = 0; i < sorted.length - 1; i++) {
      const [from, fromContracts] = sorted[i]
      const ec = fromContracts.filter((c) => c.targetAgent != null)
      edges.push({
        fromPhase: from,
        toPhase: sorted[i + 1][0],
        contracts: ec,
        dataSize: ec.reduce((sum, c) => sum + (c.content?.length || 0), 0),
        status: 'complete',
      })
    }
  }
  return edges
}

// ── Store ──

export const useSquadExecutionStore = defineStore('squadExecution', () => {
  // ═══ 仪表盘状态 ═══
  const stats = ref<ExecutionStats>({ running: 0, hitlWaiting: 0, todayDone: 0, todayFailed: 0 })
  const executions = ref<SquadExecution[]>([])
  const totalCount = ref(0)
  const dashboardLoading = ref(false)

  // ═══ 仪表盘 SSE 连接 ═══
  let dashboardDisconnect: (() => void) | null = null

  // ═══ 监控状态 ═══
  const currentExecution = ref<SquadExecution | null>(null)
  const contracts = ref<Map<number, Contract[]>>(new Map())
  const contractEdges = ref<ContractEdge[]>([])
  const sseStatus = ref<'connected' | 'disconnected' | 'reconnecting'>('disconnected')
  const hitlReview = ref<HitlReview | null>(null)
  const selectedPhase = ref<number | null>(null)
  const monitorLoading = ref(false)

  // ═══ SSE 连接 ═══
  let abortController: AbortController | null = null
  let stallTimer: ReturnType<typeof setTimeout> | null = null
  let reconnectCount = 0
  let disposed = false
  let heartbeatTimer: ReturnType<typeof setInterval> | null = null

  // ═══ 派生 ═══
  const phases = computed(() => {
    if (!currentExecution.value?.topologySnapshot) return [] as { phase: number; name?: string; agents?: string[]; hitlMode?: string; hitlAgents?: string[]; mode?: string; description?: string }[]
    try {
      return JSON.parse(currentExecution.value.topologySnapshot) as { phase: number; name?: string; agents?: string[]; hitlMode?: string; hitlAgents?: string[]; mode?: string; description?: string }[]
    } catch { return [] }
  })

  /** 根据 currentPhase 和整体状态推断每个阶段的实时状态。 */
  const phaseStatuses = computed(() => {
    const m = new Map<number, 'pending' | 'running' | 'completed' | 'failed' | 'hitl'>()
    if (!currentExecution.value) return m
    const cp = currentExecution.value.currentPhase ?? -1
    const st = currentExecution.value.status
    for (const p of phases.value) {
      if (p.phase < cp) m.set(p.phase, 'completed')
      else if (p.phase === cp) {
        if (st === 'FAILED') m.set(p.phase, 'failed')
        else if (st === 'HITL_PENDING') m.set(p.phase, 'hitl')
        else m.set(p.phase, 'running')
      } else m.set(p.phase, 'pending')
    }
    return m
  })

  // ═══ Dashboard Actions (SSE) ═══

  function startDashboard() {
    dashboardLoading.value = true
    dashboardDisconnect = connectDashboardStream((event) => {
      stats.value = event.stats
      executions.value = event.executions || []
      totalCount.value = event.totalCount
      dashboardLoading.value = false
    })
  }

  function stopDashboard() {
    dashboardDisconnect?.()
    dashboardDisconnect = null
  }

  // ═══ Monitor Actions ═══

  async function loadMonitor(execId: string) {
    monitorLoading.value = true
    try {
      const res = await fetchExecutionDetail(execId)
      currentExecution.value = res.data
      selectedPhase.value = res.data.currentPhase ?? null
    } catch { /* noop */ } finally { monitorLoading.value = false }
  }

  async function loadContracts(execId: string) {
    try {
      const res = await fetchExecutionContracts(execId)
      const all = (res.data || []) as Contract[]
      const grouped = new Map<number, Contract[]>()
      for (const c of all) {
        if (!grouped.has(c.phase)) grouped.set(c.phase, [])
        grouped.get(c.phase)!.push(c)
      }
      contracts.value = grouped
      contractEdges.value = buildContractEdges(all, currentExecution.value?.squadMode)
    } catch { /* noop */ }
  }

  async function loadHitl(execId: string) {
    try {
      const res = await fetchHitlReview(execId)
      hitlReview.value = res.data
      if (res.data) startHeartbeat(execId)
    } catch { hitlReview.value = null }
  }

  // ═══ SSE (monitor) — Fetch + ReadableStream 实现 SSE，支持断线重连与 60s 空转检测 ═══

  function connectSse(execId: string) {
    if (disposed) return
    disconnectSse()

    abortController = new AbortController()
    const token = localStorage.getItem('token')
    sseStatus.value = reconnectCount > 0 ? 'reconnecting' : 'connected'

    fetch(`/api/squads/executions/${execId}/progress`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      signal: abortController.signal,
    })
      .then(async (response) => {
        sseStatus.value = 'connected'
        reconnectCount = 0
        const reader = response.body?.getReader()
        if (!reader) return
        const decoder = new TextDecoder()
        let buffer = ''

        const resetStall = () => {
          if (stallTimer) clearTimeout(stallTimer)
          stallTimer = setTimeout(() => { abortController?.abort(); scheduleReconnect(execId) }, 60000)
        }
        resetStall()

        while (true) {
          const { done, value } = await reader.read()
          if (done) break
          resetStall()
          buffer += decoder.decode(value, { stream: true })
          const lines = buffer.split('\n')
          buffer = lines.pop() || ''
          for (const line of lines) {
            if (!line.startsWith('data:')) continue
            try {
              const event = JSON.parse(line.slice(5).trim())
              applySseEvent(event, execId)
            } catch { /* skip */ }
          }
        }
      })
      .catch(() => {
        if (!disposed && abortController && !abortController.signal.aborted) {
          sseStatus.value = 'disconnected'
          scheduleReconnect(execId)
        }
      })
  }

  function disconnectSse() {
    abortController?.abort()
    abortController = null
    if (stallTimer) { clearTimeout(stallTimer); stallTimer = null }
  }

  function scheduleReconnect(execId: string) {
    if (disposed) return
    reconnectCount++
    if (reconnectCount > 3) { sseStatus.value = 'disconnected'; return }
    sseStatus.value = 'reconnecting'
    setTimeout(() => connectSse(execId), 3000)
  }

  function applySseEvent(event: Record<string, unknown>, execId: string) {
    const st = event.status as string | undefined
    const cp = event.currentPhase as number | undefined

    if (st && currentExecution.value) currentExecution.value.status = st
    if (cp !== undefined && cp !== null && currentExecution.value) {
      currentExecution.value.currentPhase = cp
      selectedPhase.value = cp
    }
    if (st === 'FAILED' && event.message && currentExecution.value) {
      currentExecution.value.errorMessage = event.message as string
    }
    if (st === 'HITL_PENDING') loadHitl(execId)
    if (st === 'COMPLETED' || st === 'FAILED') {
      loadContracts(execId)
      stopHeartbeat()
    }
  }

  // ═══ HITL 心跳 — 每 2 分钟延长一次超时，防止用户在审核中因超时被踢 ═══

  function startHeartbeat(execId: string) {
    stopHeartbeat()
    heartbeatTimer = setInterval(() => {
      if (hitlReview.value) extendHitlTimeout(hitlReview.value.reviewId, execId).catch(() => {})
    }, 120000)
  }

  function stopHeartbeat() {
    if (heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null }
  }

  // ═══ Monitor 生命周期 ═══

  async function startMonitor(execId: string) {
    resetMonitor()
    disposed = false
    await loadMonitor(execId)
    await loadContracts(execId)
    await loadHitl(execId)
    connectSse(execId)
  }

  function stopMonitor() {
    disposed = true
    disconnectSse()
    stopHeartbeat()
    resetMonitor()
  }

  function resetMonitor() {
    currentExecution.value = null
    contracts.value = new Map()
    contractEdges.value = []
    sseStatus.value = 'disconnected'
    hitlReview.value = null
    selectedPhase.value = null
    monitorLoading.value = false
  }

  // ═══ HITL 操作 ═══

  async function approve(execId: string, reviewId: string, feedback?: string) {
    const { approveHitl } = await import('../api/squad')
    await approveHitl(execId, reviewId, feedback)
    hitlReview.value = null
    stopHeartbeat()
  }

  async function reject(execId: string, reviewId: string, feedback?: string) {
    const { rejectHitl } = await import('../api/squad')
    await rejectHitl(execId, reviewId, feedback)
    hitlReview.value = null
    stopHeartbeat()
  }

  async function retry(execId: string) {
    const { retryExecution } = await import('../api/squad')
    await retryExecution(execId)
    await startMonitor(execId)
  }

  return {
    // dashboard
    stats, executions, totalCount, dashboardLoading,
    startDashboard, stopDashboard,
    // monitor
    currentExecution, contracts, contractEdges, sseStatus, hitlReview,
    selectedPhase, monitorLoading, phases, phaseStatuses,
    startMonitor, stopMonitor, loadContracts, loadHitl,
    // actions
    approve, reject, retry,
    computeDuration,
  }
})
