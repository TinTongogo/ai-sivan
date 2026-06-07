import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { login as loginApi, register as registerApi, requestPasswordReset as requestResetApi, resetPassword as resetPasswordApi } from '../api/auth'
import type { LoginRequest, RegisterRequest, PasswordResetRequest } from '../api/auth'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('token') || '')
  const accountId = ref(localStorage.getItem('accountId') || '')
  const username = ref(localStorage.getItem('username') || '')
  const displayName = ref(localStorage.getItem('displayName') || '')

  const isLoggedIn = computed(() => !!token.value)

  async function login(req: LoginRequest) {
    const res = await loginApi(req)
    const d = res.data as { token: string; accountId: string; username: string; displayName: string }
    token.value = d.token
    accountId.value = d.accountId
    username.value = d.username
    displayName.value = d.displayName
    localStorage.setItem('token', d.token)
    localStorage.setItem('accountId', d.accountId)
    localStorage.setItem('username', d.username)
    localStorage.setItem('displayName', d.displayName)
  }

  async function register(req: RegisterRequest) {
    const res = await registerApi(req)
    const d = res.data as { token: string; accountId: string; username: string; displayName: string }
    token.value = d.token
    accountId.value = d.accountId
    username.value = d.username
    displayName.value = d.displayName
    localStorage.setItem('token', d.token)
    localStorage.setItem('accountId', d.accountId)
    localStorage.setItem('username', d.username)
    localStorage.setItem('displayName', d.displayName)
  }

  function logout() {
    token.value = ''
    accountId.value = ''
    username.value = ''
    displayName.value = ''
    localStorage.removeItem('token')
    localStorage.removeItem('accountId')
    localStorage.removeItem('username')
    localStorage.removeItem('displayName')
  }

  async function requestPasswordReset(username: string) {
    const res = await requestResetApi(username)
    return res.data
  }

  async function resetPassword(req: PasswordResetRequest) {
    await resetPasswordApi(req)
  }

  return { token, accountId, username, displayName, isLoggedIn, login, register, logout, requestPasswordReset, resetPassword }
})
