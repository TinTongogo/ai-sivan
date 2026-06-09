import {describe, expect, it, vi} from 'vitest'
import {applySseEvent, type AssistantMessage, type ProgressState} from './sse-parser'

function createMsg(overrides?: Partial<AssistantMessage>): AssistantMessage {
  return { content: '', ...overrides }
}

describe('applySseEvent', () => {
  it('should append response content', () => {
    const msg = createMsg()
    applySseEvent({ type: 'response', content: '你好' }, msg)
    applySseEvent({ type: 'response', content: '世界' }, msg)
    expect(msg.content).toBe('你好世界')
  })

  it('should append thinking content', () => {
    const msg = createMsg()
    applySseEvent({ type: 'thinking', content: '思考中' }, msg)
    expect(msg.thinking).toBe('思考中')
  })

  it('should handle meta event', () => {
    const msg = createMsg()
    applySseEvent({ type: 'meta', model: 'gpt-4o', totalTokens: 150, durationMs: 3200, messageId: 'msg-1' }, msg)
    expect(msg.model).toBe('gpt-4o')
    expect(msg.tokens).toBe(150)
    expect(msg.duration).toBe('3.2s')
    expect(msg.messageId).toBe('msg-1')
  })

  it('should call onMessageId callback', () => {
    const onId = vi.fn()
    const msg = createMsg()
    applySseEvent({ type: 'meta', messageId: 'msg-1' }, msg, onId)
    expect(onId).toHaveBeenCalledWith('msg-1')
  })

  it('should handle error event', () => {
    const msg = createMsg()
    applySseEvent({ type: 'error', message: '出错了' }, msg)
    expect(msg.content).toContain('出错了')
  })

  it('should be safe when asst is null', () => {
    expect(() => applySseEvent({ type: 'response', content: 'x' }, null)).not.toThrow()
    expect(() => applySseEvent({ type: 'response', content: 'x' }, undefined)).not.toThrow()
  })

  // ── 编排事件：透传，不修改 asst ──

  it('should ignore phase_start event (not modify asst)', () => {
    const msg = createMsg()
    applySseEvent({ type: 'phase_start', phase: '需求分析', agent: '分析师', phaseIndex: 0, totalPhases: 3 }, msg)
    expect(msg.content).toBe('')
    expect(msg.sections).toBeUndefined()
  })

  it('should ignore phase_end event (not modify asst)', () => {
    const msg = createMsg()
    applySseEvent({ type: 'phase_end', phase: '需求分析', agent: '分析师', tokens: 450, durationMs: 3200 }, msg)
    expect(msg.content).toBe('')
  })

  it('should ignore progress event (not modify asst)', () => {
    const msg = createMsg()
    const progress: ProgressState = {
      status: 'RUNNING', totalPhases: 3, completedPhases: 1,
      currentPhase: '代码实现',
      phases: [
        { name: '需求分析', status: 'COMPLETED', agent: '分析师' },
        { name: '代码实现', status: 'RUNNING', agent: '工程师', progress: 45 },
        { name: '测试', status: 'PENDING', agent: 'QA' },
      ],
      elapsedMs: 8500, totalTokens: 650,
    }
    applySseEvent({ type: 'progress', data: progress }, msg)
    expect(msg.content).toBe('')
  })

  // ── response/thinking 可携带 phase 字段 ──

  it('should handle response with phase field', () => {
    const msg = createMsg()
    applySseEvent({ type: 'response', content: '答案', phase: '分析' }, msg)
    expect(msg.content).toBe('答案')  // 仍追加到 content
  })

  it('should handle thinking with phase field', () => {
    const msg = createMsg()
    applySseEvent({ type: 'thinking', content: '思考', phase: '分析' }, msg)
    expect(msg.thinking).toBe('思考')
  })
})
