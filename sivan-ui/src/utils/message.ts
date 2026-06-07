import {ref} from 'vue'

let idCounter = 0

interface Toast {
  id: number
  type: 'success' | 'error' | 'warning' | 'info'
  text: string
  timer?: ReturnType<typeof setTimeout>
}

export const toasts = ref<Toast[]>([])

export function closeToast(id: number) {
  const idx = toasts.value.findIndex(t => t.id === id)
  if (idx === -1) return
  const toast = toasts.value[idx]
  if (toast.timer) clearTimeout(toast.timer)
  toasts.value.splice(idx, 1)
}

function addToast(type: Toast['type'], text: string) {
  const id = ++idCounter
  const timer = setTimeout(() => closeToast(id), 3000)
  toasts.value.push({ id, type, text, timer })
}

export function useMessage() {
  return {
    success(text: string) { addToast('success', text) },
    error(text: string) { addToast('error', text) },
    warning(text: string) { addToast('warning', text) },
    info(text: string) { addToast('info', text) },
  }
}
