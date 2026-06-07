import api from './index'

// ── 后端统一响应包装 ──
// axios 拦截器 (api/index.ts) 已解 axios response → 返回 JSON body = BaseResponse<T>
export interface BaseResponse<T> { code: number; message: string; data: T }

// ── 领域类型 ──

export interface PhaseNode {
  phase: number; name: string; mode: string; agents: string[]
  description: string; inputFilter: string; outputFilter: string
  hitlMode?: string; hitlAgents?: string[]
}

export interface Squad {
  squadId: string; projectId: string | null; name: string; description: string
  mode: string; source: string; active: boolean; phases: PhaseNode[]
  usageCount: number; successRate: number; createdAt: string; updatedAt: string
}

export interface SquadExecution {
  executionId: string; squadId: string; projectId: string
  taskDescription: string; status: string; content?: string; thinking?: string; topologySnapshot: string
  squadName?: string; squadMode?: string; currentPhase: number; errorMessage: string | null
  startedAt: string; pausedAt: string | null; completedAt: string | null; createdAt: string
}

export interface HitlReview {
  reviewId: string; executionId: string; phase: number; phaseName: string
  inputContent: string; outputContent: string | null; humanFeedback: string | null
  status: string; expiresAt: string | null; createdAt: string; updatedAt: string
}

export interface Contract {
  contractId: string; executionId: string; phase: number
  sourceAgent: string; targetAgent: string | null; content: string; contentType: string; createdAt: string
}

export interface ExecutionStats {
  running: number; hitlWaiting: number; todayDone: number; todayFailed: number
}

export interface DashboardEvent {
  stats: ExecutionStats
  executions: SquadExecution[]
  totalCount: number
}

export interface PageResponse<T> {
  items: T[]; total: number; page: number; size: number; totalPages: number
}

// ── API 函数（返回 BaseResponse<T>，由 axios 拦截器解包后即为后端 JSON body） ──

export function fetchSquads(params: Record<string, unknown> = {}) {
  return api.get('/squads', { params }) as Promise<BaseResponse<PageResponse<Squad>>>
}
export function fetchSquadDetail(id: string) {
  return api.get(`/squads/${id}`) as Promise<BaseResponse<Squad>>
}
export function createSquad(data: Record<string, unknown>) {
  return api.post('/squads', data) as Promise<BaseResponse<Squad>>
}
export function updateSquad(id: string, data: Record<string, unknown>) {
  return api.put(`/squads/${id}`, data) as Promise<BaseResponse<Squad>>
}
export function deleteSquad(id: string) {
  return api.delete(`/squads/${id}`) as Promise<BaseResponse<null>>
}
export function generateTopology(taskDescription: string) {
  return api.post('/squads/generate-topology', { taskDescription }) as Promise<BaseResponse<{ mode: string; phases: PhaseNode[] }>>
}

// ── 执行 ──

export function executeSquad(squadId: string, taskDescription: string) {
  return api.post(`/squads/${squadId}/execute`, { taskDescription }) as Promise<BaseResponse<SquadExecution>>
}
export function fetchSquadExecutions(squadId: string, page = 0, size = 20) {
  return api.get(`/squads/${squadId}/executions`, { params: { page, size } }) as Promise<BaseResponse<PageResponse<SquadExecution>>>
}
export function fetchAllExecutions(params: Record<string, unknown> = {}) {
  return api.get('/squads/executions', { params }) as Promise<BaseResponse<PageResponse<SquadExecution>>>
}
export function fetchExecutionDetail(execId: string) {
  return api.get(`/squads/executions/${execId}`) as Promise<BaseResponse<SquadExecution>>
}
export function fetchExecutionStats() {
  return api.get('/squads/executions/stats') as Promise<BaseResponse<ExecutionStats>>
}
export function retryExecution(execId: string) {
  return api.post(`/squads/executions/${execId}/retry`) as Promise<BaseResponse<null>>
}
export function deleteExecution(execId: string) {
  return api.delete(`/squads/executions/${execId}`) as Promise<BaseResponse<null>>
}

// ── 契约 ──

export function fetchExecutionContracts(execId: string) {
  return api.get(`/squads/executions/${execId}/contracts`) as Promise<BaseResponse<Contract[]>>
}

// ── HITL ──

export function fetchHitlReview(execId: string) {
  return api.get(`/squads/executions/${execId}/hitl/review`) as Promise<BaseResponse<HitlReview | null>>
}
export function approveHitl(execId: string, reviewId: string, feedback?: string) {
  return api.post(`/squads/executions/${execId}/hitl/${reviewId}/approve`, feedback ? { feedback } : {}) as Promise<BaseResponse<HitlReview>>
}
export function rejectHitl(execId: string, reviewId: string, feedback?: string) {
  return api.post(`/squads/executions/${execId}/hitl/${reviewId}/reject`, feedback ? { feedback } : {}) as Promise<BaseResponse<HitlReview>>
}
export function extendHitlTimeout(reviewId: string, execId: string) {
  return api.post(`/squads/executions/${execId}/hitl/${reviewId}/extend`) as Promise<BaseResponse<HitlReview>>
}

/** 连接仪表盘 SSE 流，返回 abort 函数用于断开。 */
export function connectDashboardStream(onEvent: (event: DashboardEvent) => void): () => void {
  const token = localStorage.getItem('token')
  const controller = new AbortController()

  fetch('/api/squads/executions/dashboard-stream', {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    signal: controller.signal,
  })
    .then(async (response) => {
      const reader = response.body?.getReader()
      if (!reader) return
      const decoder = new TextDecoder()
      let buffer = ''
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''
        for (const line of lines) {
          if (!line.startsWith('data:')) continue
          try {
            const event = JSON.parse(line.slice(5).trim()) as DashboardEvent
            onEvent(event)
          } catch { /* skip malformed */ }
        }
      }
    })
    .catch(() => { /* abort 或网络错误 */ })

  return () => controller.abort()
}
