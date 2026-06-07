import {describe, expect, it, vi} from 'vitest'
import {applySseEvent, type AssistantMessage} from './sse-parser'

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

  it('should handle step_start with new phase', () => {
    const msg = createMsg()
    applySseEvent({ type: 'step_start', step: '分析', message: '正在分析...' }, msg)
    expect(msg.orchestration?.status).toBe('running')
    expect(msg.orchestration?.currentStep).toBe('分析')
    expect(msg.orchestration?.phases).toHaveLength(1)
    expect(msg.orchestration?.phases[0].name).toBe('分析')
    expect(msg.orchestration?.phases[0].status).toBe('running')
  })

  it('should handle step_start with executionId', () => {
    const msg = createMsg()
    applySseEvent({ type: 'step_start', step: '编排', executionId: 'exec-1', squadName: '测试Squad' }, msg)
    expect(msg.orchestration?.executionId).toBe('exec-1')
    expect(msg.orchestration?.squadName).toBe('测试Squad')
  })

  it('should update existing phase on step_start', () => {
    const msg = createMsg({ orchestration: { status: 'running', phases: [{ name: '分析', status: 'pending' }] } })
    applySseEvent({ type: 'step_start', step: '分析' }, msg)
    expect(msg.orchestration?.phases[0].status).toBe('running')
  })

  it('should handle step_end', () => {
    const msg = createMsg({ orchestration: { status: 'running', phases: [{ name: '分析', status: 'running' }] } })
    applySseEvent({ type: 'step_end', step: '分析' }, msg)
    expect(msg.orchestration?.phases[0].status).toBe('completed')
  })

  it('should handle final event', () => {
    const msg = createMsg({ orchestration: { status: 'running', phases: [] } })
    applySseEvent({ type: 'final' }, msg)
    expect(msg.orchestration?.status).toBe('completed')
  })

  it('should create orchestration on final if missing', () => {
    const msg = createMsg()
    applySseEvent({ type: 'final' }, msg)
    expect(msg.orchestration?.status).toBe('completed')
  })

  it('should handle error event', () => {
    const msg = createMsg({ orchestration: { status: 'running', phases: [] } })
    applySseEvent({ type: 'error', message: '出错了' }, msg)
    expect(msg.orchestration?.status).toBe('failed')
    expect(msg.content).toContain('出错了')
  })

  it('should be safe when asst is null', () => {
    expect(() => applySseEvent({ type: 'response', content: 'x' }, null)).not.toThrow()
    expect(() => applySseEvent({ type: 'response', content: 'x' }, undefined)).not.toThrow()
  })
})
