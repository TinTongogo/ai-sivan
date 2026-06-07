import { ref, nextTick, onBeforeUnmount, type Ref } from 'vue'
import type { Virtualizer } from '@tanstack/vue-virtual'

type ScrollIntent =
    | { type: 'bottom'; smooth?: boolean }
    | { type: 'restore'; itemIndex: number; offset?: number }
    | { type: 'index'; index: number; align?: 'start' | 'end' }

export function useScrollScheduler(
    virtualizer: Ref<Virtualizer<HTMLDivElement, any> | undefined>,
    scrollElement: Ref<HTMLElement | null | undefined>,
) {
  const pendingIntent = ref<ScrollIntent | null>(null)
  let intentId = 0
  let isScheduled = false          // 是否有正在进行的异步调度
  let lastScrollTime = 0

  /** 等待虚拟滚动器可用（最多 5 帧） */
  async function waitForVirtualizer() {
    for (let i = 0; i < 5; i++) {
      if (scrollElement.value && virtualizer.value) return
      await new Promise(r => requestAnimationFrame(r))
    }
  }

  /** 等待两帧确保虚拟项测量完成 */
  function doubleRaf(): Promise<void> {
    return new Promise(resolve => {
      requestAnimationFrame(() => requestAnimationFrame(() => resolve()))
    })
  }

  /**
   * 核心调度方法
   * 同一时间只允许一个意图在执行，后续意图会取消前一个（通过递增 intentId）
   */
  async function schedule(intent: ScrollIntent) {
    // 如果已有调度进行中，则通过递增 id 让旧任务自动丢弃
    const id = ++intentId
    isScheduled = true
    pendingIntent.value = intent

    try {
      await nextTick()
      await doubleRaf()
      // 等待虚拟滚动器挂载
      await waitForVirtualizer()
      if (id !== intentId) return   // 已被新意图取代

      executeIntent(intent)
      pendingIntent.value = null
    } finally {
      if (id === intentId) isScheduled = false
    }
  }

  /**
   * 带节流的 schedule，用于流式跟随底部。
   * 节流期内只发一次请求，且如果当前已有调度则跳过。
   */
  function scheduleThrottled(intent: ScrollIntent, throttleMs = 80) {
    const now = Date.now()
    if (now - lastScrollTime < throttleMs) return
    if (isScheduled) return          // 上一轮滚动尚未完成
    lastScrollTime = now
    schedule(intent)
  }

  function executeIntent(intent: ScrollIntent) {
    const el = scrollElement.value
    const v = virtualizer.value

    // 虚拟滚动器可用时，全部走虚拟 API（确保测量一致性）
    if (v && el) {
      switch (intent.type) {
        case 'bottom': {
          const lastIndex = v.options.count - 1
          if (lastIndex >= 0) {
            v.scrollToIndex(lastIndex, { align: 'end', behavior: intent.smooth ? 'smooth' : 'auto' })
          }
          break
        }
        case 'restore': {
          const index = Math.min(intent.itemIndex, v.options.count - 1)
          // TanStack Virtual 的 scrollToIndex 支持 offset 参数（v3+）
          v.scrollToIndex(index, {
            align: 'start',
            // @ts-expect-error offset 是内部支持的选项
            offset: intent.offset ?? 0,
          })
          break
        }
        case 'index': {
          v.scrollToIndex(intent.index, {
            align: intent.align ?? 'start',
          })
          break
        }
      }
      return
    }

    // 虚拟滚动器不可用时，降级为原生滚动（保守兜底）
    if (!el) return

    switch (intent.type) {
      case 'bottom': {
        if (intent.smooth) {
          el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
        } else {
          el.scrollTop = el.scrollHeight
        }
        break
      }
      case 'restore': {
        // 原生滚动无法精确还原，仅滚动到大约位置
        el.scrollTop = 0  // 简单处理，实际可计算偏移
        break
      }
      case 'index': {
        // 原生滚动不实现精确索引
        break
      }
    }
  }

  /** 取消所有未完成的调度（组件卸载时调用） */
  function dispose() {
    intentId++
    isScheduled = false
    pendingIntent.value = null
  }

  // 自动绑定清理
  onBeforeUnmount(() => dispose())

  return { schedule, scheduleThrottled, pendingIntent }
}