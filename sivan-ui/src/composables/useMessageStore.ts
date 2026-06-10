import { ref } from 'vue'
import api from '../api'

export interface Message {
  id?: string
  messageId?: string
  role: 'user' | 'assistant'
  content: string
  createdAt?: string
  thinking?: string
  model?: string
  tokens?: number
  duration?: string
  thinkingDurationMs?: number
  thinkingTokens?: number
  sortOrder?: number
  status?: string
  replyToId?: string
  replyTo?: { role: string; content: string }
  images?: string[]
  attachments?: { fileId: string; fileName: string; mimeType: string; fileSize: number }[]
  generationIndex?: number
  generationGroup?: string
  generationTotal?: number
  chain?: string
  _key: string
}

export function useMessageStore() {
  const messages = ref<Message[]>([])

  const PAGE_SIZE = 15
  const hasMore = ref(true)
  const loadingMore = ref(false)
  const allLoaded = ref(false)
  let oldestSortOrder: number | undefined
  let fetchSerial = 0

  function normalize(msg: any): Message {
    const m = msg as Message
    if (!m._key) m._key = msgKey(m)
    if ((msg as any).totalTokens != null) m.tokens = (msg as any).totalTokens
    if ((msg as any).durationMs != null) m.duration = `${((msg as any).durationMs / 1000).toFixed(1)}s`
    if ((msg as any).thinkingTokens != null) m.thinkingTokens = (msg as any).thinkingTokens
    return m
  }

  let _keyCounter = 0
  function msgKey(m: { sortOrder?: number; messageId?: string }): string {
    return m.sortOrder != null ? `s-${m.sortOrder}` : `t-${Date.now()}-${++_keyCounter}`
  }

  let inflightFetch: Promise<void> | null = null
  let inflightConvId: string | null = null

  async function fetchLatest(conversationId: string) {
    // 避免重复请求（同一对话可复用，不同对话必须重新请求）
    if (inflightFetch && inflightConvId === conversationId) return inflightFetch

    const serial = ++fetchSerial
    messages.value = []
    hasMore.value = true
    allLoaded.value = false
    oldestSortOrder = undefined

    inflightConvId = conversationId
    inflightFetch = (async () => {
      try {
        const res: any = await api.get(`/v2/conversations/${conversationId}/messages?limit=${PAGE_SIZE}`)
        if (serial !== fetchSerial) return
        const list: Message[] = (res.data?.messages || []).map(normalize)
        messages.value = list
        hasMore.value = res.data?.hasMore ?? (list.length >= PAGE_SIZE)
        if (list.length > 0) {
          oldestSortOrder = list[0].sortOrder
        }
      } catch {
        if (serial !== fetchSerial) return
        messages.value = []
        hasMore.value = false
      } finally {
        inflightFetch = null
        inflightConvId = null
      }
    })()

    return inflightFetch
  }

  async function loadMore(conversationId: string) {
    if (loadingMore.value || !hasMore.value) return
    if (oldestSortOrder == null && messages.value.length > 0) {
      hasMore.value = false
      allLoaded.value = true
      return
    }
    loadingMore.value = true
    const serial = ++fetchSerial
    try {
      const params = `limit=${PAGE_SIZE}${oldestSortOrder != null ? `&before=${oldestSortOrder}` : ''}`
      const res: any = await api.get(`/v2/conversations/${conversationId}/messages?${params}`)
      if (serial !== fetchSerial) return
      const older: Message[] = (res.data?.messages || []).map(normalize)
      hasMore.value = res.data?.hasMore ?? !(older.length < PAGE_SIZE)
      if (!older.length) {
        allLoaded.value = true
      } else {
        oldestSortOrder = older[0].sortOrder
        messages.value.unshift(...older)
        allLoaded.value = !hasMore.value
      }
    } finally {
      loadingMore.value = false
    }
  }

  function appendUserAndAssistant(userMsg: Message, asstMsg: Message) {
    messages.value.push(userMsg, asstMsg)
  }

  function findAsstByKey(key: string): Message | undefined {
    return messages.value.find(m => m._key === key)
  }

  function replaceAt(index: number, msg: Message) {
    messages.value.splice(index, 1, msg)
  }

  function removeAt(index: number) {
    messages.value.splice(index, 1)
  }

  /**
   * 流完成后轻量同步最新消息的服务器字段（sortOrder / createdAt）。
   * SSE 流已推送 messageId/tokens/duration 等，无需全量重取。
   * 仅拉取最新 3 条消息补充辅助字段，对已有 meta 不做覆盖。
   */
  async function syncLatestMeta(conversationId: string) {
    try {
      const res: any = await api.get(`/v2/conversations/${conversationId}/messages?limit=3`)
      const msgs: Message[] = (res.data?.messages || res.data || [])
      for (const fetched of msgs) {
        if (!fetched.messageId) continue
        const existing = messages.value.find(m => m.messageId === fetched.messageId)
        if (existing) {
          existing.sortOrder = fetched.sortOrder
          existing.createdAt = fetched.createdAt
          // 以下字段若 SSE 流中未命中 meta 事件，在此补充
          if (!existing.model && fetched.model) existing.model = fetched.model
          if (existing.tokens == null && (fetched as any).totalTokens != null) existing.tokens = (fetched as any).totalTokens
          if (!existing.duration && (fetched as any).durationMs != null) existing.duration = `${((fetched as any).durationMs / 1000).toFixed(1)}s`
        }
      }
    } catch { /* 静默失败 */ }
  }

  function clear() {
    messages.value = []
    hasMore.value = true
    allLoaded.value = false
    oldestSortOrder = undefined
  }

  function cancelFetch() {
    ++fetchSerial
  }

  return {
    messages,
    PAGE_SIZE,
    hasMore,
    loadingMore,
    allLoaded,
    oldestSortOrder: () => oldestSortOrder,
    msgKey,
    fetchLatest,
    loadMore,
    appendUserAndAssistant,
    findAsstByKey,
    replaceAt,
    removeAt,
    clear,
    syncLatestMeta,
    cancelFetch,
  }
}
