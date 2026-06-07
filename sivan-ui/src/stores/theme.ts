import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

type ThemeMode = 'light' | 'dark' | 'system'

function resolveTheme(mode: ThemeMode): 'light' | 'dark' {
  if (mode === 'light') return 'light'
  if (mode === 'dark') return 'dark'
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

function applyTheme(resolved: 'light' | 'dark') {
  document.documentElement.classList.toggle('dark', resolved === 'dark')
}

export const useThemeStore = defineStore('theme', () => {
  const mode = ref<ThemeMode>((localStorage.getItem('sivan-theme') as ThemeMode) || 'system')

  function setMode(val: ThemeMode) {
    mode.value = val
  }

  // 应用当前主题
  function apply() {
    applyTheme(resolveTheme(mode.value))
    localStorage.setItem('sivan-theme', mode.value)
  }

  // 监听系统主题变化（system 模式需要）
  let mql: MediaQueryList | null = null
  function listenSystem() {
    if (mql) return
    mql = window.matchMedia('(prefers-color-scheme: dark)')
    mql.addEventListener('change', () => {
      if (mode.value === 'system') applyTheme(resolveTheme('system'))
    })
  }

  watch(mode, (val) => {
    applyTheme(resolveTheme(val))
    localStorage.setItem('sivan-theme', val)
    if (val === 'system') listenSystem()
  }, { immediate: true })

  return { mode, setMode, apply, listenSystem }
})
