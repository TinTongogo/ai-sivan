import api from './index'

export interface GoalItem {
  goalId: string
  projectId: string | null
  conversationId: string | null
  title: string
  description: string
  status: string
  autoMode: string
  milestones: any[] | null
  currentMilestone: number
  totalTasks: number
  completedTasks: number
  pauseReason: string | null
  fileRootPath: string | null
  createdAt: string
  updatedAt: string
  completedAt: string | null
  pausedAt: string | null
}

export interface ArtifactItem {
  artifactId: string
  goalId: string
  filePath: string
  fileType: string
  summary: string
  fileSize: number
  createdAt: string
}

// ── 基础 CRUD ──

/** 创建目标（含 LLM 拆解）。 */
export function createGoal(data: { title: string; description: string; projectId?: string }) {
  return api.post('/goals', data) as Promise<any>
}

/** 获取目标列表。 */
export function fetchGoals() {
  return api.get('/goals') as Promise<any>
}

/** 获取目标详情。 */
export function fetchGoalDetail(goalId: string) {
  return api.get(`/goals/${goalId}`) as Promise<any>
}

/** 启动目标执行（SSE 流）。返回 AbortController，调用者通过 reader 读取。 */
export function startGoalStream(goalId: string): { controller: AbortController; reader: Promise<Response> } {
  const token = localStorage.getItem('token')
  const controller = new AbortController()
  const reader = fetch(`/api/goals/${goalId}/start`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    signal: controller.signal,
  })
  return { controller, reader }
}

/** 暂停目标。 */
export function pauseGoal(goalId: string, reason?: string) {
  return api.post(`/goals/${goalId}/pause`, reason ? { reason } : {}) as Promise<any>
}

/** 恢复目标执行（SSE 流）。 */
export function resumeGoalStream(goalId: string): { controller: AbortController; reader: Promise<Response> } {
  const token = localStorage.getItem('token')
  const controller = new AbortController()
  const reader = fetch(`/api/goals/${goalId}/resume`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    signal: controller.signal,
  })
  return { controller, reader }
}

/** 取消目标。 */
export function cancelGoal(goalId: string) {
  return api.post(`/goals/${goalId}/cancel`) as Promise<any>
}

/** 追加 Task。 */
export function appendTasks(goalId: string, descriptions: string[]) {
  return api.post(`/goals/${goalId}/tasks`, { descriptions }) as Promise<any>
}

/** 获取产物列表。 */
export function fetchArtifacts(goalId: string) {
  return api.get(`/goals/${goalId}/artifacts`) as Promise<any>
}

/** 读取产物文件内容。 */
export function readArtifact(goalId: string, path: string) {
  return api.get(`/goals/${goalId}/artifacts/read`, { params: { path } }) as Promise<any>
}
