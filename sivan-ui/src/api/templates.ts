import api from './index'

/** GoalTree 模板（08-API契约 §3.3）。 */
export interface TemplateItem {
  templateId: string
  accountId: string
  name: string
  description?: string
  usageCount: number
  successCount: number
  createdAt: string
  updatedAt: string
}

export interface CreateTemplateRequest {
  name: string
  description?: string
  /** 模板根节点树（JSON 结构，按 ExecutableNode 多态序列化）。 */
  rootNode?: Record<string, unknown>
}

export interface UpdateTemplateRequest {
  name?: string
  description?: string
  rootNode?: Record<string, unknown>
}

/** 模板列表。 */
export function fetchTemplates() {
  return api.get<any, { code: number; data: TemplateItem[] }>('/v2/templates')
}

/** 创建模板。 */
export function createTemplate(req: CreateTemplateRequest) {
  return api.post<any, { code: number; data: TemplateItem }>('/v2/templates', req)
}

/** 获取模板详情。 */
export function getTemplate(templateId: string) {
  return api.get<any, { code: number; data: TemplateItem }>(`/v2/templates/${templateId}`)
}

/** 更新模板。 */
export function updateTemplate(templateId: string, req: UpdateTemplateRequest) {
  return api.put<any, { code: number; data: TemplateItem }>(`/v2/templates/${templateId}`, req)
}

/** 删除模板。 */
export function deleteTemplate(templateId: string) {
  return api.delete(`/v2/templates/${templateId}`)
}

/** 实例化模板为 GoalTree。 */
export function instantiateTemplate(templateId: string) {
  return api.post<any, { code: number; data: { goalId: string; templateId: string; status: string } }>(`/v2/templates/${templateId}/instantiate`)
}
