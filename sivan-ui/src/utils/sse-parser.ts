/** SSE 事件负载类型 */
export type SseEvent =
  | { type: 'response'; content: string }
  | { type: 'thinking'; content: string }
  | { type: 'meta'; model?: string; totalTokens?: number; durationMs?: number; thinkingDurationMs?: number; thinkingTokens?: number; messageId?: string; chain?: string; generationGroup?: string; generationTotal?: number }
  | { type: 'step_start'; step?: string; message?: string; executionId?: string; squadName?: string; agentCount?: number }
  | { type: 'step_end'; step?: string; agentCount?: number }
  | { type: 'final' }
  | { type: 'error'; message?: string; content?: string }

/** 助手消息对象接口 */
export interface AssistantMessage {
  content: string
  thinking?: string
  model?: string
  tokens?: number
  duration?: string
  thinkingDurationMs?: number
  thinkingTokens?: number
  messageId?: string
  chain?: string
  generationGroup?: string
  generationTotal?: number
  orchestration?: {
    status: string
    phases: { name: string; status: string; agentCount?: number }[]
    executionId?: string
    squadName?: string
    currentStep?: string
    currentMessage?: string
    agentCount?: number
  }
}

/** 处理 SSE 事件，更新助手消息对象。 */
export function applySseEvent(parsed: SseEvent, asst: AssistantMessage | null | undefined, onMessageId?: (id: string) => void) {
  if (!asst) return

  switch (parsed.type) {
    case 'response':
      asst.content += parsed.content
      break
    case 'thinking':
      asst.thinking = (asst.thinking || '') + parsed.content
      break
    case 'meta':
      if (parsed.model) asst.model = parsed.model
      if (parsed.totalTokens != null) asst.tokens = parsed.totalTokens
      if (parsed.durationMs != null) asst.duration = `${(parsed.durationMs / 1000).toFixed(1)}s`
      if (parsed.thinkingDurationMs != null) asst.thinkingDurationMs = parsed.thinkingDurationMs
      if (parsed.thinkingTokens != null) asst.thinkingTokens = parsed.thinkingTokens
      if (parsed.messageId) {
        asst.messageId = parsed.messageId
        onMessageId?.(parsed.messageId)
      }
      if (parsed.chain) asst.chain = parsed.chain
      if (parsed.generationGroup) asst.generationGroup = parsed.generationGroup
      if (parsed.generationTotal != null) asst.generationTotal = parsed.generationTotal
      break
    case 'step_start':
      if (!asst.orchestration) asst.orchestration = { status: 'running', phases: [] }
      asst.orchestration.currentStep = parsed.step
      asst.orchestration.currentMessage = parsed.message
      if (parsed.executionId) asst.orchestration.executionId = parsed.executionId
      if (parsed.squadName) asst.orchestration.squadName = parsed.squadName
      if (!asst.orchestration.phases.some(p => p.name === parsed.step)) {
        asst.orchestration.phases.push({ name: parsed.step || '', status: 'running', agentCount: parsed.agentCount || 1 })
      } else {
        const existing = asst.orchestration.phases.find(p => p.name === parsed.step)
        if (existing) existing.status = 'running'
      }
      break
    case 'step_end':
      if (!asst.orchestration) asst.orchestration = { status: 'running', phases: [] }
      const phase = asst.orchestration.phases.find(p => p.name === parsed.step)
      if (phase) phase.status = 'completed'
      if (parsed.agentCount) asst.orchestration.agentCount = parsed.agentCount
      break
    case 'final':
      if (!asst.orchestration) asst.orchestration = { status: 'completed', phases: [] }
      asst.orchestration.status = 'completed'
      break
    case 'error':
      if (asst.orchestration) asst.orchestration.status = 'failed'
      asst.content += parsed.message || parsed.content || ''
      break
  }
}
