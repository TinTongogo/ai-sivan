import { ref } from 'vue'

/**
 * SSE 流式读取 composable，专用于 Goal 执行 SSE 流。
 * 返回 { events, streaming, start, stop } 供组件消费。
 */
export function useGoalSSE() {
  const events = ref<string[]>([])
  const streaming = ref(false)
  const controller = ref<AbortController | null>(null)

  function stop() {
    controller.value?.abort()
    controller.value = null
    streaming.value = false
  }

  async function start(fetchPromise: Promise<Response>) {
    events.value = []
    streaming.value = true
    const ctrl = new AbortController()
    controller.value = ctrl

    try {
      const response = await fetchPromise
      const reader = response.body?.getReader()
      if (!reader) return false

      const decoder = new TextDecoder()
      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        const chunk = decoder.decode(value, { stream: true })
        for (const line of chunk.split('\n')) {
          if (!line.startsWith('data:')) continue
          const data = line.slice(5).trim()
          if (data === '[DONE]') continue
          try {
            const parsed = JSON.parse(data)
            if (parsed.type === 'step_start' || parsed.type === 'step_end' || parsed.type === 'error') {
              events.value.push(parsed.message || '')
            }
          } catch { /* skip malformed */ }
        }
      }
      return true
    } catch {
      return false
    } finally {
      streaming.value = false
      controller.value = null
    }
  }

  return { events, streaming, start, stop }
}
