// Theme initialization — must run before createApp to prevent FOUC
const theme = (() => {
  const stored = localStorage.getItem('sivan-theme')
  if (stored === 'dark' || stored === 'light') return stored
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
})()
document.documentElement.classList.toggle('dark', theme === 'dark')

// 抑制 ResizeObserver 循环报错（浏览器已自行截断，非应用 BUG）
window.addEventListener('error', (e) => {
  if (e.message?.includes('ResizeObserver loop')) {
    e.stopImmediatePropagation()
    e.preventDefault()
  }
})

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './style.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
