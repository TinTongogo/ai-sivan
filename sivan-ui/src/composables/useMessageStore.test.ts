import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useMessageStore, type Message } from './useMessageStore'

// mock API
const mockGet = vi.fn()
vi.mock('../api', () => ({
  default: { get: (...args: any[]) => mockGet(...args) },
}))

describe('useMessageStore', () => {
  let store: ReturnType<typeof useMessageStore>

  beforeEach(() => {
    vi.clearAllMocks()
    store = useMessageStore()
  })

  describe('msgKey', () => {
    it('should use sortOrder when available', () => {
      const key = store.msgKey({ sortOrder: 42 })
      expect(key).toBe('s-42')
    })

    it('should generate temp key when no sortOrder', () => {
      const key = store.msgKey({})
      expect(key).toMatch(/^t-\d+-\d+$/)
    })
  })

  describe('normalize (via fetchLatest)', () => {
    it('should map totalTokens and durationMs from API response', async () => {
      mockGet.mockResolvedValue({
        data: {
          messages: [
            { role: 'assistant', content: 'test', totalTokens: 150, durationMs: 3200, sortOrder: 1 },
          ],
        },
      })
      await store.fetchLatest('conv-1')
      expect(store.messages.value[0].tokens).toBe(150)
      expect(store.messages.value[0].duration).toBe('3.2s')
    })

    it('should add _key to messages', async () => {
      mockGet.mockResolvedValue({
        data: {
          messages: [{ role: 'user', content: 'hi', sortOrder: 1 }],
        },
      })
      await store.fetchLatest('conv-1')
      expect(store.messages.value[0]._key).toBeDefined()
    })
  })

  describe('appendUserAndAssistant', () => {
    it('should append both messages', () => {
      const user: Message = { role: 'user', content: '你好', _key: 'u1' }
      const asst: Message = { role: 'assistant', content: '你好！', _key: 'a1' }

      store.appendUserAndAssistant(user, asst)

      expect(store.messages.value).toHaveLength(2)
      expect(store.messages.value[0].content).toBe('你好')
      expect(store.messages.value[1].content).toBe('你好！')
    })
  })

  describe('findAsstByKey', () => {
    it('should find message by _key', () => {
      store.appendUserAndAssistant(
        { role: 'user', content: 'q', _key: 'u1' },
        { role: 'assistant', content: 'a', _key: 'a1' },
      )
      expect(store.findAsstByKey('a1')?.content).toBe('a')
      expect(store.findAsstByKey('unknown')).toBeUndefined()
    })
  })

  describe('replaceAt / removeAt', () => {
    it('should replace message at index', () => {
      store.appendUserAndAssistant(
        { role: 'user', content: 'q', _key: 'u1' },
        { role: 'assistant', content: 'old', _key: 'a1' },
      )
      store.replaceAt(1, { role: 'assistant', content: 'new', _key: 'a2' })
      expect(store.messages.value[1].content).toBe('new')
    })

    it('should remove message at index', () => {
      store.appendUserAndAssistant(
        { role: 'user', content: 'q', _key: 'u1' },
        { role: 'assistant', content: 'a', _key: 'a1' },
      )
      store.removeAt(0)
      expect(store.messages.value).toHaveLength(1)
      expect(store.messages.value[0].role).toBe('assistant')
    })
  })

  describe('fetchLatest', () => {
    it('should populate messages from API', async () => {
      mockGet.mockResolvedValue({
        data: {
          messages: [
            { role: 'user', content: '你好', sortOrder: 2 },
            { role: 'assistant', content: '你好！', sortOrder: 3 },
          ],
          hasMore: false,
        },
      })

      await store.fetchLatest('conv-1')

      expect(store.messages.value).toHaveLength(2)
      expect(store.hasMore.value).toBe(false)
      expect(mockGet).toHaveBeenCalledWith(
        expect.stringContaining('/conversations/conv-1/messages?limit=15'),
      )
    })

    it('should handle API error gracefully', async () => {
      mockGet.mockRejectedValue(new Error('network error'))

      await store.fetchLatest('conv-1')

      expect(store.messages.value).toHaveLength(0)
      expect(store.hasMore.value).toBe(false)
    })

    it('should deduplicate concurrent calls for same conversation', async () => {
      mockGet.mockResolvedValue({ data: { messages: [] } })

      const p1 = store.fetchLatest('conv-1')
      const p2 = store.fetchLatest('conv-1')

      // 两个并发请求应该共享同一个 inflightFetch，完成后只调用一次 API
      await Promise.all([p1, p2])
      expect(mockGet).toHaveBeenCalledTimes(1)
    })
  })

  describe('clear', () => {
    it('should reset state', () => {
      store.appendUserAndAssistant(
        { role: 'user', content: 'q', _key: 'u1' },
        { role: 'assistant', content: 'a', _key: 'a1' },
      )
      expect(store.messages.value).toHaveLength(2)

      store.clear()
      expect(store.messages.value).toHaveLength(0)
      expect(store.hasMore.value).toBe(true)
    })
  })

  describe('cancelFetch', () => {
    it('should prevent stale fetch from updating state', async () => {
      // Delay ensures cancelFetch happens before resolve
      mockGet.mockImplementation(
        () => new Promise((resolve) => setTimeout(() => resolve({ data: { messages: [] } }), 50)),
      )

      const promise = store.fetchLatest('conv-1')
      store.cancelFetch()
      await promise

      expect(store.messages.value).toHaveLength(0)
    })
  })

  describe('loadMore', () => {
    it('should not load when hasMore is false', async () => {
      store.hasMore.value = false
      await store.loadMore('conv-1')
      expect(mockGet).not.toHaveBeenCalled()
    })

    it('should not load when loadingMore is true', async () => {
      store.loadingMore.value = true
      await store.loadMore('conv-1')
      expect(mockGet).not.toHaveBeenCalled()
    })
  })
})
