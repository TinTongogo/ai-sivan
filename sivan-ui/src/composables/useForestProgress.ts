import { ref, onBeforeUnmount } from 'vue'
import type { ForestProgress } from '../types/forest'
import { fetchForestProgress } from '../api/goals'

/**
 * Forest 进度轮询 composable。
 *
 * 在 SUMMARY 模式下轮询 `GET /api/v2/goals/{goalId}/progress`，
 * 在 STREAM 模式下不开启轮询（进度由 SSE 事件驱动）。
 */
export function useForestProgress() {
  const progress = ref<ForestProgress | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  let pollTimer: ReturnType<typeof setInterval> | undefined

  onBeforeUnmount(() => {
    stopPolling()
  })

  function startPolling(goalId: string, intervalMs = 2000) {
    stopPolling()
    fetchOnce(goalId)
    pollTimer = setInterval(() => fetchOnce(goalId), intervalMs)
  }

  function stopPolling() {
    if (pollTimer !== undefined) {
      clearInterval(pollTimer)
      pollTimer = undefined
    }
  }

  async function fetchOnce(goalId: string) {
    loading.value = true
    error.value = null
    try {
      progress.value = await fetchForestProgress(goalId)
    } catch (e: any) {
      error.value = e?.message || '获取进度失败'
    } finally {
      loading.value = false
    }
  }

  return { progress, loading, error, startPolling, stopPolling, fetchOnce }
}
