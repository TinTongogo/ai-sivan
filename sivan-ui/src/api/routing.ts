import api from './index'
import type {BaseResponse} from './squad'

export interface StrategyPerformance {
  id: string
  strategy: string
  total: number
  success: number
  avgConfidence: number
  successRate: number
  updatedAt: string
}

export interface RoutingDecision {
  decisionId: string
  conversationId: string
  taskDescription: string
  selectedAgentName: string | null
  strategy: string
  success: boolean
  confidence: number | null
  reasoning: string
  errorHint: string | null
  context: Record<string, any> | null
  createdAt: string
}

export function fetchStrategyPerformance() {
  return api.get('/routing-decisions/performance') as Promise<BaseResponse<StrategyPerformance[]>>
}

export function fetchRoutingDecision(id: string) {
  return api.get(`/routing-decisions/${id}`) as Promise<BaseResponse<RoutingDecision>>
}

export function resetStrategyPerformance() {
  return api.delete('/routing-decisions/performance') as Promise<BaseResponse<void>>
}
