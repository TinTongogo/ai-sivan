import api from './index'

export interface LoginRequest {
  username: string
  password: string
}

export interface RegisterRequest {
  username: string
  password: string
  email?: string
  displayName?: string
}

export interface AuthResponse {
  token: string
  accountId: string
  username: string
  displayName: string
}

interface ApiResponse<T> {
  code: number
  data: T
  message: string
}

export function login(data: LoginRequest) {
  return api.post<any, ApiResponse<AuthResponse>>('/auth/login', data)
}

export function register(data: RegisterRequest) {
  return api.post<any, ApiResponse<AuthResponse>>('/auth/register', data)
}

export interface ChangePasswordRequest {
  oldPassword: string
  newPassword: string
}

export function changePassword(data: ChangePasswordRequest) {
  return api.put<any, ApiResponse<any>>('/account/password', data)
}

export interface PasswordResetRequest {
  token: string
  newPassword: string
}

export function requestPasswordReset(username: string) {
  return api.post<any, ApiResponse<string>>('/auth/password-reset/request', { username })
}

export function resetPassword(data: PasswordResetRequest) {
  return api.post<any, ApiResponse<any>>('/auth/password-reset/reset', data)
}
