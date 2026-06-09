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
