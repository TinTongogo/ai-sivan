import api from './index'

export interface UserPreferences {
  fontSize?: string
  fontFamily?: string
  chatBackground?: string
  pageBackground?: string
  userBubbleColor?: string
  sendMode?: 'enter' | 'mod-enter' | 'system'
}

export function fetchPreferences(): Promise<UserPreferences> {
  return api.get('/account/preferences').then(res => res.data || {})
}

export function savePreferences(data: UserPreferences): Promise<void> {
  return api.put('/account/preferences', data).then(res => res.data)
}

// ── AI 画像 / Profile ──

export interface AccountProfile {
  accountId: string
  username: string
  email: string
  displayName: string
  preferences: string  // JSON string
  quota: string
  status: string
}

export interface AiProfile {
  name: string
  bio: string
  aiLanguage: string
  expertise: string[]
}

export function fetchProfile(): Promise<{ data: AccountProfile }> {
  return api.get('/account/profile')
}

export function updateProfile(data: { displayName?: string; preferences?: string; quota?: string }): Promise<{ data: AccountProfile }> {
  return api.put('/account/profile', data)
}

// ── AI 画像 v2 (UserProfile API) ──

export interface UserProfileResponse {
  profileId: string
  accountId: string
  name: string
  bio: string
  aiLanguage: string
  expertise: string[]
  autoLearn: boolean
  active: boolean
}

export interface UpdateUserProfileRequest {
  name?: string
  bio?: string
  aiLanguage?: string
  expertise?: string[]
  autoLearn?: boolean
}

export function fetchAiProfile(): Promise<{ data: UserProfileResponse }> {
  return api.get('/account/profile/ai')
}

export function updateAiProfile(data: UpdateUserProfileRequest): Promise<{ data: UserProfileResponse }> {
  return api.put('/account/profile/ai', data)
}

// ── 画像变更历史 ──

export interface ProfileChangeLogEntry {
  logId: string
  accountId: string
  source: string
  fieldName: string
  oldValue: string | null
  newValue: string | null
  createdAt: string
}

export function fetchProfileChangeHistory(): Promise<{ data: ProfileChangeLogEntry[] }> {
  return api.get('/account/profile/ai/history')
}

// ── Auth ──

export interface LoginRequest { username: string; password: string }
export interface RegisterRequest { username: string; password: string; email?: string; displayName?: string }

export function login(data: LoginRequest): Promise<{ data: any }> {
  return api.post('/auth/login', data)
}

export function register(data: RegisterRequest): Promise<{ data: any }> {
  return api.post('/auth/register', data)
}

export function changePassword(data: { oldPassword: string; newPassword: string }): Promise<{ data: any }> {
  return api.put('/account/password', data)
}
