import api from './index'

export interface LlmProviderItem {
  providerId: string
  name: string
  providerType: string
  apiKey: string
  baseUrl: string
  model: string
  active: boolean
  isDefault: boolean
  capabilities: string
  contextLength?: number
  temperature?: number | null
  tags: string
  createdAt: string
  updatedAt: string
}

export interface CreateLlmProviderRequest {
  name: string
  providerType: string
  apiKey?: string
  baseUrl?: string
  model?: string
  capabilities?: string
  temperature?: number | null
  contextLength?: number
  tags?: string
}

export interface UpdateLlmProviderRequest {
  name?: string
  providerType?: string
  apiKey?: string
  baseUrl?: string
  model?: string
  active?: boolean
  isDefault?: boolean
  capabilities?: string
  temperature?: number | null
  contextLength?: number
  tags?: string
}

export function fetchProviders() {
  return api.get<any, { code: number; data: LlmProviderItem[] }>('/llm-providers')
}

export function createProvider(req: CreateLlmProviderRequest) {
  return api.post<any, { code: number; data: LlmProviderItem }>('/llm-providers', req)
}

export function updateProvider(id: string, req: UpdateLlmProviderRequest) {
  return api.put<any, { code: number; data: LlmProviderItem }>(`/llm-providers/${id}`, req)
}

export function deleteProvider(id: string) {
  return api.delete<any, { code: number; data: any }>(`/llm-providers/${id}`)
}

export function setDefaultProvider(id: string) {
  return api.post<any, { code: number; data: LlmProviderItem }>(`/llm-providers/${id}/set-default`)
}

export function testConnection(req: { providerType: string; apiKey: string; baseUrl: string }) {
  return api.post<any, { code: number; data: { success: boolean; message: string; contextLength?: number; models?: { name: string; contextLength: number | null }[] } }>('/llm-providers/test', req)
}

export function fetchModels(req: { providerType: string; apiKey: string; baseUrl: string }) {
  return api.post<any, { code: number; data: { models: string[] } }>('/llm-providers/models', req)
}

export interface CapabilityInfo {
  code: string
  label: string
}

export function fetchCapabilities() {
  return api.get<any, { code: number; data: CapabilityInfo[] }>('/llm-providers/capabilities')
}

export function fetchDefaultCapabilities(providerType: string, modelName?: string) {
  const params = modelName ? `?modelName=${encodeURIComponent(modelName)}` : ''
  return api.get<any, { code: number; data: string[] }>(`/llm-providers/capabilities/${providerType}${params}`)
}
