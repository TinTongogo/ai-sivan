import api from './index'

export interface BaseResponse<T> {
  code: number
  message: string
  data: T
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

export function fetchRoutingDecision(id: string) {
  return api.get(`/routing-decisions/${id}`) as Promise<BaseResponse<RoutingDecision>>
}
