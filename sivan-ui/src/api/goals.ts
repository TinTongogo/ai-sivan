import api from './index'

// ── V2 Forest API ──

export interface ForestProgress {
  goalId: string
  title?: string
  progress: number
  completed: number
  activated: number
  total: number
  depth: number
}

/**
 * 查询 Forest 执行进度。
 * 后端端点: GET /api/v2/goals/{goalId}/progress
 */
export function fetchForestProgress(goalId: string): Promise<ForestProgress> {
  return api.get(`/v2/goals/${goalId}/progress`) as Promise<ForestProgress>
}

// ── Forest 执行树 API（PipelineDialog） ──

export interface ForestTreeResponse {
  forestId: string
  root: ForestTreeResponseNode
  progress: { completed: number; total: number }
}

export interface A2aMessage {
  sourceAgent: string
  targetAgent: string
  content: string
  messageType: string
}

export interface ForestTreeResponseNode {
  nodeId?: string
  name: string
  status: string
  a2aMessages?: A2aMessage[]
  mode?: string
  agent?: string
  leaf: boolean
  durationMs?: number
  tokens?: number
  routeTier?: number
  routeConfidence?: number
  children?: ForestTreeResponseNode[]
}

/**
 * 查询消息的 Forest 执行树。
 * 后端端点: GET /api/v2/conversations/{conversationId}/messages/{messageId}/forest
 */
export async function fetchMessageForest(conversationId: string, messageId: string): Promise<ForestTreeResponse | null> {
  const res: any = await api.get(`/v2/conversations/${conversationId}/messages/${messageId}/forest`)
  return res?.data || null
}

/** HITL: 批准暂停节点继续执行。 */
export function approveHitl(goalId: string, nodeId: string) {
  return api.post(`/v2/goals/${goalId}/hitl/approve`, { nodeId })
}

/** HITL: 拒绝暂停节点，取消执行。 */
export function rejectHitl(goalId: string, nodeId: string, reason?: string) {
  return api.post(`/v2/goals/${goalId}/hitl/reject`, { nodeId, reason })
}
