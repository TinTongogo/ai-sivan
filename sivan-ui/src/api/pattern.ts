import api from './index'

export interface InstinctPattern {
  patternId: string
  accountId: string
  executionMode: string
  hitCount: number
  successCount: number
  totalCount: number
  sourcePatternId: string | null
  version: number
  active: boolean
  modeSuccessRate: number | null
  lastMatchAt: string | null
  createdAt: string
  updatedAt: string
}

export interface SharedTemplate {
  templateId: string
  patternId: string
  ownerAccountId: string
  visibility: string
  status: string
  quality: string
  useCount: number
  successCount: number
  sharedAt: string
  createdAt: string
  executionMode: string
  taskDescription: string
  patternVersion: number
}

export function fetchPatterns(): Promise<InstinctPattern[]> {
  return api.get('/patterns').then(res => (res as any).data || [])
}

export function fetchPatternDetail(id: string): Promise<InstinctPattern> {
  return api.get(`/patterns/${id}`).then(res => (res as any).data)
}

export function deletePattern(id: string): Promise<void> {
  return api.delete(`/patterns/${id}`)
}

export function batchDeletePatterns(ids: string[]): Promise<void> {
  return api.post('/patterns/batch-delete', ids)
}

export function sharePattern(patternId: string, visibility: string): Promise<SharedTemplate> {
  return api.post(`/patterns/${patternId}/share`, { visibility }).then(res => (res as any).data)
}

export function unshareTemplate(templateId: string): Promise<void> {
  return api.delete(`/patterns/share/${templateId}`)
}

export function fetchMySharedTemplates(): Promise<SharedTemplate[]> {
  return api.get('/patterns/shared/mine').then(res => (res as any).data || [])
}

export function fetchAccessibleTemplates(): Promise<SharedTemplate[]> {
  return api.get('/patterns/shared/accessible').then(res => (res as any).data || [])
}
