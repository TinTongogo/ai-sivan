import { ref, onBeforeUnmount, type Ref } from 'vue'
import api from '../api'
import { useMessage } from '../utils/message'
import { useI18n } from '../utils/i18n'
import { applySseEvent } from '../utils/sse-parser'
import { useOrchestrationStore } from '../stores/orchestration'
import type { useMessageStore } from './useMessageStore'
import type { useScrollScheduler } from './useScrollScheduler'

interface Conversation {
  conversationId: string
  projectId?: string
  title: string
  messageCount: number
  mcpServerIds?: string[]
  lastMessageAt?: string
  createdAt?: string
}

const STALL_TIMEOUT = 60000

export function useChatStream(deps: {
  store: ReturnType<typeof useMessageStore>
  currentConversationId: Ref<string>
  conversations: Ref<Conversation[]>
  currentProjectContext: Ref<string | null>
  selectedAgentId: Ref<string | null>
  selectedKbNames: Ref<string[]>
  inputText: Ref<string>
  autoScroll: Ref<boolean>
  schedule: ReturnType<typeof useScrollScheduler>['schedule']
  onConversationCreated?: (conv: Conversation) => void
}) {
  const { t } = useI18n()
  const message = useMessage()
  const {
    store, currentConversationId, conversations, currentProjectContext,
    selectedAgentId, selectedKbNames, inputText, autoScroll, schedule,
  } = deps

  const streaming = ref(false)
  const quoteMsg = ref<{ messageId: string; content: string; role: string } | null>(null)
  const lastModelProviderId = ref<string | undefined>()
  const lastMcpServerIds = ref<string[]>([])
  const currentController = ref<AbortController | null>(null)
  let pendingCancelCtx: { convId: string; controller: AbortController } | null = null
  let pendingCancelTimer: ReturnType<typeof setTimeout> | undefined

  onBeforeUnmount(() => {
    currentController.value?.abort()
    pendingCancelCtx?.controller.abort()
    clearTimeout(pendingCancelTimer)
  })

  // applySseEvent 从 utils/sse-parser 导入

  function handlePendingCancel(messageId: string) {
    if (pendingCancelCtx && currentConversationId.value === pendingCancelCtx.convId) {
      const ctx = pendingCancelCtx
      pendingCancelCtx = null
      clearTimeout(pendingCancelTimer)
      api.post(`/v2/conversations/${ctx.convId}/stream/${messageId}/cancel`)
        .catch((err: any) => console.warn('延迟取消请求失败:', err))
      ctx.controller.abort()
    }
  }

  async function cancelStream() {
    streaming.value = false
    if (currentConversationId.value) {
      const runningAsst = store.messages.value.find(m => m.role === 'assistant' && m.status === 'RUNNING')
      if (runningAsst?.messageId) {
        runningAsst.status = 'CANCELLED'
        if (runningAsst.content) {
          runningAsst.content += '\n\n*' + t('cancelSend') + '*'
        } else {
          runningAsst.content = t('cancelSend')
        }
        const cid = currentConversationId.value
        api.post(`/v2/conversations/${cid}/stream/${runningAsst.messageId}/cancel`)
          .catch((err: any) => console.warn('取消请求失败(不影响 SSE 断开):', err))
        currentController.value?.abort()
        currentController.value = null
      } else {
        const ctrl = currentController.value
        if (ctrl) {
          pendingCancelCtx = { convId: currentConversationId.value, controller: ctrl }
          currentController.value = null
          clearTimeout(pendingCancelTimer)
          pendingCancelTimer = setTimeout(() => {
            if (pendingCancelCtx) {
              pendingCancelCtx.controller.abort()
              pendingCancelCtx = null
            }
          }, 5000)
        }
      }
    } else {
      currentController.value?.abort()
      currentController.value = null
    }
  }

  /** 读取 SSE 流的通用逻辑，返回 true 表示正常结束（非中断） */
  async function readStream(
    reader: ReadableStreamDefaultReader<Uint8Array>,
    convId: string,
    findAsst: () => any,
    controller: AbortController,
  ): Promise<boolean> {
    const decoder = new TextDecoder()
    let stallTimer: ReturnType<typeof setTimeout> | undefined = setTimeout(() => controller.abort(), STALL_TIMEOUT)
    try {
      while (true) {
        if (currentConversationId.value !== convId) return false
        const { done, value } = await reader.read()
        if (done) break
        clearTimeout(stallTimer)
        stallTimer = setTimeout(() => controller.abort(), STALL_TIMEOUT)
        const chunk = decoder.decode(value, { stream: true })
        const lines = chunk.split('\n')
        for (const line of lines) {
          if (line.startsWith('data:')) {
            const data = line.slice(5).trim()
            if (data === '[DONE]') continue
            try {
              const parsed = JSON.parse(data)
              const asst = findAsst()
              applySseEvent(parsed, asst, (msgId) => handlePendingCancel(msgId))
              // 编排事件分发到 orchestration store
              handleOrchestrationEvent(parsed, asst)
            } catch {
              const a = findAsst()
              if (a) a.content += data
            }
          }
        }
        if (autoScroll.value) schedule({ type: 'bottom' })
      }
      return true
    } finally {
      clearTimeout(stallTimer)
    }
  }

  /** 处理编排事件 — 将 SSE 编排事件分发给 orchestration store */
  function handleOrchestrationEvent(parsed: any, asst: any) {
    if (!parsed || !parsed.type) return

    const orchStore = useOrchestrationStore()

    switch (parsed.type) {
      case 'progress':
        if (parsed.data) {
          orchStore.setProgress(parsed.data)
        }
        break
      case 'phase_start':
        if (asst) {
          orchStore.reset()
          orchStore.setProgress({
            status: 'RUNNING',
            totalPhases: parsed.totalPhases || 1,
            completedPhases: 0,
            currentPhase: parsed.phase || '执行',
            phases: [{ name: parsed.phase || '执行', status: 'RUNNING' }],
            elapsedMs: 0,
            totalTokens: 0,
          })
        }
        break
      case 'phase_end':
        if (orchStore.progress) {
          if (parsed.tokens) orchStore.progress.totalTokens += parsed.tokens
          orchStore.progress.completedPhases = orchStore.progress.totalPhases
          orchStore.progress.status = 'COMPLETED'
        }
        break
      case 'meta':
        // 编排完成（最终 meta 事件到达时）
        if (parsed.messageId && orchStore.isOrchestrating) {
          orchStore.complete()
        }
        break
    }
  }

  async function sendMessage(payload?: {
    content: string
    stream?: boolean
    images?: string[]
    attachments?: { fileId: string; fileName: string; mimeType: string; fileSize: number }[]
    replyToId?: string
    modelProviderId?: string
    mcpServerIds?: string[]
  }) {
    const text = payload?.content ?? inputText.value.trim()
    const hasAttachments = !!((payload as any)?.attachments?.length || payload?.images?.length)
    if (!text && !hasAttachments) return

    if (!currentConversationId.value) {
      try {
        const body: any = { title: text.slice(0, 30) }
        if (currentProjectContext.value) body.projectId = currentProjectContext.value
        const res: any = await api.post('/v2/conversations', body)
        const conv = res.data as Conversation
        conversations.value.unshift(conv)
        currentConversationId.value = conv.conversationId
        deps.onConversationCreated?.(conv)
      } catch {
        message.error(t('createConversationFailed'))
        return
      }
    }

    const startTime = Date.now()
    const convId = currentConversationId.value
    const currentQuote = quoteMsg.value
    quoteMsg.value = null

    const userMsg: any = { role: 'user', content: text }
    userMsg._key = store.msgKey(userMsg)
    if (payload?.images?.length) {
      userMsg.images = payload.images.map(id => '/api/files/' + id)
    }
    if ((payload as any)?.attachments?.length) {
      userMsg.attachments = (payload as any).attachments.map((a: any) => ({
        ...a,
        url: '/api/files/' + a.fileId,
      }))
    }
    if (currentQuote) {
      userMsg.replyToId = currentQuote.messageId
      userMsg.replyTo = { role: currentQuote.role, content: currentQuote.content }
    }
    const asstMsg: any = { role: 'assistant', content: '', thinking: '', status: 'RUNNING' }
    asstMsg._key = store.msgKey(asstMsg)
    if (currentQuote) {
      asstMsg.replyToId = currentQuote.messageId
      asstMsg.replyTo = { role: currentQuote.role, content: currentQuote.content }
    }
    store.appendUserAndAssistant(userMsg, asstMsg)
    const asstKey = asstMsg._key
    const findAsst = () => store.findAsstByKey(asstKey)
    inputText.value = ''
    streaming.value = true
    const wasAtBottom = autoScroll.value
    if (wasAtBottom) schedule({ type: 'bottom' })

    const controller = new AbortController()
    currentController.value = controller

    try {
      const token = localStorage.getItem('token')
      const body: any = { content: text, stream: payload?.stream !== false }
      if ((payload as any)?.targetAgent) body.targetAgent = (payload as any).targetAgent
      else if (selectedAgentId.value) body.targetAgent = selectedAgentId.value
      if (currentProjectContext.value) body.projectId = currentProjectContext.value
      if (selectedKbNames.value.length) body.knowledgeBases = selectedKbNames.value
      if (payload?.replyToId) {
        body.replyToId = payload.replyToId
      } else if (currentQuote) {
        body.replyToId = currentQuote.messageId
      }
      if (payload?.images?.length) body.images = payload.images
      if ((payload as any)?.attachments?.length) body.attachments = (payload as any).attachments
      if (payload?.modelProviderId) {
        body.modelProviderId = payload.modelProviderId
        lastModelProviderId.value = payload.modelProviderId
      }
      if (payload?.mcpServerIds?.length) {
        body.mcpServerIds = payload.mcpServerIds
        lastMcpServerIds.value = payload.mcpServerIds
      } else {
        lastMcpServerIds.value = []
      }

      const response = await fetch(`/api/v2/conversations/${currentConversationId.value}/stream`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify(body),
        signal: controller.signal,
      })

      if (!response.ok) {
        let errMsg = `HTTP ${response.status}`
        try {
          const errBody = await response.json()
          if (errBody.message) errMsg = errBody.message
        } catch { /* ignore */ }
        throw new Error(errMsg)
      }
      const reader = response.body?.getReader()
      if (!reader) throw new Error('无可读流')

      await readStream(reader, convId, findAsst, controller)

      const doneAsst = findAsst()
      if (doneAsst) doneAsst.duration = `${((Date.now() - startTime) / 1000).toFixed(1)}s`
      streaming.value = false
      // SSE 流已推送完整消息内容和 meta（messageId/tokens/duration），
      // 这里仅轻量同步确保最新消息的 sortOrder/createdAt 等辅助字段，
      // 替代全量重取 syncFromServer（减少 ~50 条消息的冗余请求）
      await store.syncLatestMeta(convId)
      if (autoScroll.value) schedule({ type: 'bottom' })
    } catch (e: any) {
      if (e.name === 'AbortError') {
        // 后台任务仍在继续，不修改消息状态。用户返回后可续接。
        return
      }
      const errAsst = findAsst()
      if (errAsst) {
        errAsst.duration = `${((Date.now() - startTime) / 1000).toFixed(1)}s`
        errAsst.content += '\n\n' + t('requestFailed', { msg: e.message || t('unknownError') })
      }
    } finally {
      streaming.value = false
      currentController.value = null
      if (autoScroll.value) schedule({ type: 'bottom' })
    }
  }

  /** 续接流（LLM 后台独立运行，SSE 断连不中断） */
  async function resumeStream(msg: any) {
    if (streaming.value) return
    const msgId = msg.messageId
    if (!msgId) return
    const convId = currentConversationId.value
    streaming.value = true
    const controller = new AbortController()
    currentController.value = controller

    try {
      const token = localStorage.getItem('token')
      const response = await fetch(`/api/v2/conversations/${currentConversationId.value}/messages/${msgId}/subscribe`, {
        headers: { Authorization: `Bearer ${token}` },
        signal: controller.signal,
      })
      if (!response.ok || !response.body) {
        msg.status = 'COMPLETED'
        return
      }
      msg.content = ''
      msg.thinking = ''
      const reader = response.body.getReader()
      const findAsst = () => msg
      await readStream(reader, convId, findAsst, controller)
      msg.status = 'COMPLETED'
    } catch (e: any) {
      if (e.name === 'AbortError') {
        // 后台任务仍在继续，不修改消息状态。
        return
      }
      msg.status = 'FAILED'
      msg.content += '\n\n' + t('requestFailed', { msg: e.message || t('unknownError') })
    } finally {
      streaming.value = false
    }
  }

  /** 重新生成 */
  async function handleRegenerate(index: number) {
    const oldMsg = store.messages.value[index]
    if (!oldMsg.messageId) return

    const newGenIndex = (oldMsg.generationIndex || 1) + 1
    const startTime = Date.now()
    const convId = currentConversationId.value

    const asstMsg: any = {
      role: 'assistant',
      content: '',
      status: 'RUNNING',
      generationIndex: newGenIndex,
      _key: store.msgKey({ sortOrder: undefined, messageId: undefined }),
    }
    store.replaceAt(index, asstMsg)
    streaming.value = true
    const wasAtBottom = autoScroll.value
    if (wasAtBottom) schedule({ type: 'bottom' })

    const controller = new AbortController()
    currentController.value = controller

    try {
      const token = localStorage.getItem('token')
      const body: any = {
        messageId: oldMsg.messageId,
        modelProviderId: lastModelProviderId.value || null,
        mcpServerIds: lastMcpServerIds.value.length ? lastMcpServerIds.value : null,
      }
      const response = await fetch(`/api/v2/conversations/${convId}/regenerate`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify(body),
        signal: controller.signal,
      })
      if (!response.ok) throw new Error('请求失败')
      const reader = response.body?.getReader()
      if (!reader) throw new Error('无可读流')
      // 从 store 中查找 reactive 代理对象，确保 SSE 更新触发 Vue 响应式
      const asstKey = asstMsg._key
      const findAsst = () => store.findAsstByKey(asstKey)
      await readStream(reader, convId, findAsst, controller)
      asstMsg.duration = `${((Date.now() - startTime) / 1000).toFixed(1)}s`
      await store.syncLatestMeta(convId)
      if (autoScroll.value) schedule({ type: 'bottom' })
    } catch (e: any) {
      if (e.name === 'AbortError') {
        // 后台任务仍在继续，不修改消息状态。
        return
      }
      asstMsg.content += '\n\n' + t('requestFailed', { msg: e.message || t('unknownError') })
    } finally {
      streaming.value = false
      currentController.value = null
      if (wasAtBottom) schedule({ type: 'bottom' })
    }
  }

  /** 中止当前流（供外部切换对话时调用） */
  function abortStream() {
    currentController.value?.abort()
    pendingCancelCtx?.controller.abort()
    pendingCancelCtx = null
    clearTimeout(pendingCancelTimer)
    currentController.value = null
    streaming.value = false
    // 切换对话时清除编排进度状态
    useOrchestrationStore().reset()
  }

  return {
    streaming,
    quoteMsg,
    lastModelProviderId,
    lastMcpServerIds,
    sendMessage,
    resumeStream,
    handleRegenerate,
    cancelStream,
    abortStream,
  }
}
