import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  timeout: 30000,
})

// 请求拦截器：注入 JWT
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 401 时需清除的 localStorage Key（避免残留数据污染新账户）
const AUTH_KEYS = ['token', 'accountId', 'username', 'displayName']
const APP_KEYS = ['sivan-last-group', 'sivan-settings']

// 响应拦截器：401 时清除凭证并跳转登录
api.interceptors.response.use(
  (res) => res.data,
  (err) => {
    // 统一 BaseResponse 格式的错误消息
    if (err.response?.data?.code !== undefined) {
      err.message = err.response.data.message || err.message
    }
    if (err.response?.status === 401) {
      const hadToken = !!localStorage.getItem('token')
      AUTH_KEYS.forEach(k => localStorage.removeItem(k))
      APP_KEYS.forEach(k => localStorage.removeItem(k))
      if (hadToken) {
        sessionStorage.setItem('login_expired', '1')
      }
      // 跳过登录页本身的 401，避免死循环
      if (!window.location.pathname.startsWith('/login')) {
        window.location.href = '/login'
      }
    }
    return Promise.reject(err)
  },
)

export default api
