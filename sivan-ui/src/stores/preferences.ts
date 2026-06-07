import { defineStore } from 'pinia'
import { ref } from 'vue'
import { fetchPreferences, savePreferences, type UserPreferences } from '../api/account'

const DEFAULTS: UserPreferences = {
  fontSize: '15px',
  fontFamily: 'Inter',
  sendMode: 'system',
}

/** 监听 <html> class 变化，主题切换时重新应用偏好 */
function onThemeChange(cb: () => void) {
  const observer = new MutationObserver((mutations) => {
    for (const m of mutations) {
      if (m.attributeName === 'class') { cb(); return }
    }
  })
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
  return () => observer.disconnect()
}

export const usePreferencesStore = defineStore('preferences', () => {
  const prefs = ref<UserPreferences>({ ...DEFAULTS })
  const loaded = ref(false)

  async function load() {
    if (loaded.value) return
    try {
      const data = await fetchPreferences()
      prefs.value = { ...DEFAULTS, ...data }
    } catch { /* 未登录或无数据 */ }
    loaded.value = true
    clearAllOverrides()
    applyOverrides(prefs.value)
    onThemeChange(() => applyOverrides(prefs.value))
  }

  async function save(data: UserPreferences) {
    prefs.value = { ...prefs.value, ...data }
    clearAllOverrides()
    applyOverrides(prefs.value)
    try { await savePreferences(prefs.value) } catch { /* 不阻塞 UI */ }
  }

  /** 还原单个属性为默认 — 从 store 和 DOM 中清除，CSS 变量回退到主题默认 */
  async function reset(key: keyof UserPreferences) {
    const copy = { ...prefs.value }
    delete copy[key]
    prefs.value = copy
    clearAllOverrides()
    applyOverrides(prefs.value)
    try { await savePreferences(copy) } catch { /* 不阻塞 UI */ }
  }

  /** 移除所有通过 setProperty 设置的内联样式 */
  function clearAllOverrides() {
    const root = document.documentElement
    document.body.style.fontSize = ''
    ;['--ff-sans', '--clr-bg-chat', '--clr-bg-page', '--clr-bubble-user', '--clr-bubble-user-text',
      '--fs-body', '--fs-callout', '--fs-footnote', '--fs-headline', '--fs-title-3',
    ].forEach(v => root.style.removeProperty(v))
  }

  /** 将偏好值写入 DOM 内联样式，覆盖 :root / html.dark 变量 */
  function applyOverrides(p: UserPreferences) {
    const root = document.documentElement

    if (p.fontSize) {
      const scale = parseInt(p.fontSize) / 15
      document.body.style.fontSize = p.fontSize
      root.style.setProperty('--fs-body', p.fontSize)
      root.style.setProperty('--fs-callout', Math.round(14 * scale) + 'px')
      root.style.setProperty('--fs-footnote', Math.round(12 * scale) + 'px')
      root.style.setProperty('--fs-headline', Math.round(16 * scale) + 'px')
      root.style.setProperty('--fs-title-3', Math.round(18 * scale) + 'px')
    }
    if (p.fontFamily) {
      root.style.setProperty('--ff-sans', p.fontFamily === 'System'
        ? "-apple-system, 'PingFang SC', 'Microsoft YaHei', sans-serif"
        : p.fontFamily.startsWith('Inter')
          ? "'Inter', 'Noto Sans SC', -apple-system, 'PingFang SC', sans-serif"
          : `${p.fontFamily}, -apple-system, 'PingFang SC', sans-serif`)
    }
    if (p.chatBackground) root.style.setProperty('--clr-bg-chat', p.chatBackground)
    if (p.pageBackground) root.style.setProperty('--clr-bg-page', p.pageBackground)
    if (p.userBubbleColor) root.style.setProperty('--clr-bubble-user', p.userBubbleColor)
  }

  return { prefs, loaded, load, save, reset }
})
