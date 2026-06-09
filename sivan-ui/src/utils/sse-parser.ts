/** 阶段工具调用摘要 */
export interface ToolSummary {
  name: string
  count: number
  status: 'ok' | 'partial' | 'failed'
}

/** 编排阶段信息 */
export interface SectionPhase {
  /** 阶段名称 */
  phase: string
  /** 执行 AI 名称（叶子阶段） */
  agent?: string
  /** 编排模式（非叶子阶段，含 children 时） */
  mode?: string
  /** 阶段状态 */
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
  /** 思考内容 */
  thinking?: string
  /** 正文内容 */
  content?: string
  /** 工具调用摘要 */
  toolSummary?: ToolSummary[]
  /** 嵌套子阶段 */
  children?: SectionPhase[]
  /** token 用量 */
  tokens?: number
  /** 耗时 ms */
  durationMs?: number
  /** 使用的模型 */
  model?: string
}

/** 编排进度状态 */
export interface ProgressState {
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  totalPhases: number
  completedPhases: number
  currentPhase?: string
  phases: Array<{
    name: string
    status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
    agent?: string
    progress?: number
  }>
  elapsedMs: number
  totalTokens: number
}

/** SSE 事件负载类型 */
export type SseEvent =
  | { type: 'response'; content: string; phase?: string }
  | { type: 'thinking'; content: string; phase?: string }
  | { type: 'meta'; model?: string; totalTokens?: number; durationMs?: number; thinkingDurationMs?: number; thinkingTokens?: number; messageId?: string; chain?: string; generationGroup?: string; generationTotal?: number }
  | { type: 'error'; message?: string; content?: string }
  // 编排事件
  | { type: 'phase_start'; phase: string; agent?: string; mode?: string; phaseIndex: number; totalPhases: number }
  | { type: 'phase_end'; phase: string; agent?: string; tokens?: number; durationMs?: number; model?: string }
  | { type: 'progress'; data: ProgressState }

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
  /** 编排阶段详情（仅复杂任务） */
  sections?: SectionPhase[]
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
    case 'error':
      asst.content += parsed.message || parsed.content || ''
      break
    // 编排事件 — sse-parser 仅透传，由 useOrchestrationStore 消费
    case 'phase_start':
    case 'phase_end':
    case 'progress':
      break
  }
}
