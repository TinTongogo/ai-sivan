import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from './auth'

const mockLoginApi = vi.fn()
const mockRegisterApi = vi.fn()

vi.mock('../api/auth', () => ({
  login: (...args: any[]) => mockLoginApi(...args),
  register: (...args: any[]) => mockRegisterApi(...args),
}))

describe('useAuthStore', () => {
  let store: ReturnType<typeof useAuthStore>

  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    setActivePinia(createPinia())
    store = useAuthStore()
  })

  describe('initial state', () => {
    it('should be logged out initially', () => {
      expect(store.isLoggedIn).toBe(false)
      expect(store.token).toBe('')
      expect(store.accountId).toBe('')
    })
  })

  describe('login', () => {
    it('should set auth state on success', async () => {
      mockLoginApi.mockResolvedValue({
        data: { token: 'jwt-token', accountId: 'acct-1', username: 'test', displayName: 'Test' },
      })

      await store.login({ username: 'test', password: 'pass' })

      expect(store.isLoggedIn).toBe(true)
      expect(store.token).toBe('jwt-token')
      expect(store.accountId).toBe('acct-1')
      expect(store.displayName).toBe('Test')
      expect(localStorage.getItem('token')).toBe('jwt-token')
    })

    it('should throw on API error', async () => {
      mockLoginApi.mockRejectedValue(new Error('invalid credentials'))

      await expect(store.login({ username: 'test', password: 'wrong' })).rejects.toThrow('invalid credentials')
      expect(store.isLoggedIn).toBe(false)
    })
  })

  describe('register', () => {
    it('should set auth state on success', async () => {
      mockRegisterApi.mockResolvedValue({
        data: { token: 'jwt-token', accountId: 'acct-2', username: 'newuser', displayName: 'New User' },
      })

      await store.register({ username: 'newuser', password: 'pass' })

      expect(store.isLoggedIn).toBe(true)
      expect(store.username).toBe('newuser')
    })
  })

  describe('logout', () => {
    it('should clear auth state and localStorage', () => {
      localStorage.setItem('token', 'some-token')
      localStorage.setItem('accountId', 'acct-1')
      store.token = 'some-token'
      store.accountId = 'acct-1'

      store.logout()

      expect(store.isLoggedIn).toBe(false)
      expect(store.token).toBe('')
      expect(localStorage.getItem('token')).toBeNull()
    })
  })
})
