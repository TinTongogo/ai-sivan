import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

/** 阶段工具调用摘要 */
export interface ToolSummary {
  name: string
  count: number
  status: string
}

/** 编排阶段详情 */
export interface SectionPhase {
  phase: string
  agent?: string
  mode?: string
  status: string
  thinking?: string
  content?: string
  toolSummary?: ToolSummary[]
  children?: SectionPhase[]
  tokens?: number
  durationMs?: number
  model?: string
}

/** 运行时进度状态 */
export interface ProgressState {
  status: string
  totalPhases: number
  completedPhases: number
  currentPhase?: string
  phases: Array<{
    name: string
    status: string
    agent?: string
    progress?: number
  }>
  elapsedMs: number
  totalTokens: number
}

/**
 * 编排状态管理 Store — 由 SSE 事件驱动，PhaseCard/ProgressBar 消费。
 */
export const useOrchestrationStore = defineStore('orchestration', () => {
  /** 运行时进度 */
  const progress = ref<ProgressState | null>(null)

  /** 已完成阶段的累积内容（用于断连恢复） */
  const phaseContents = ref<Map<string, { thinking: string; content: string }>>(new Map())

  /** 是否正在编排中 */
  const isOrchestrating = computed(() => progress.value?.status === 'RUNNING' || progress.value?.status === 'PENDING')

  /** 当前阶段名称 */
  const currentPhase = computed(() => progress.value?.currentPhase)

  /** 完成百分比 */
  const percentComplete = computed(() => {
    if (!progress.value || progress.value.totalPhases === 0) return 0
    return Math.round((progress.value.completedPhases / progress.value.totalPhases) * 100)
  })

  /** 进度概要文本 */
  const summary = computed(() => {
    if (!progress.value) return ''
    return `阶段 ${progress.value.completedPhases}/${progress.value.totalPhases} · ${currentPhase.value || ''}`
  })

  /** 由 SSE progress 事件驱动，直接替换运行时进度 */
  function setProgress(p: ProgressState) {
    progress.value = p
  }

  /** 设置阶段累积内容（由 useChatStream 在处理 thinking/response 时调用） */
  function setPhaseContent(phaseName: string, thinking: string, content: string) {
    const existing = phaseContents.value.get(phaseName) || { thinking: '', content: '' }
    phaseContents.value.set(phaseName, {
      thinking: existing.thinking + thinking,
      content: existing.content + content,
    })
  }

  /** 清除状态（新编排开始时） */
  function reset() {
    progress.value = null
    phaseContents.value = new Map()
  }

  /** 编排完成时清除运行时进度 */
  function complete() {
    if (progress.value) {
      progress.value.status = 'COMPLETED'
    }
  }

  return {
    progress,
    phaseContents,
    isOrchestrating,
    currentPhase,
    percentComplete,
    summary,
    setProgress,
    setPhaseContent,
    reset,
    complete,
  }
})
