import api from './index'

export interface McpServerItem {
  serverId: string
  name: string
  serverUrl: string
  apiKey: string
  transport: string
  active: boolean
  createdAt: string
  updatedAt: string
}

export interface CreateMcpServerRequest {
  name: string
  serverUrl: string
  apiKey?: string
  transport?: string
}

export interface UpdateMcpServerRequest {
  name?: string
  serverUrl?: string
  apiKey?: string
  transport?: string
  active?: boolean
}

export function fetchServers() {
  return api.get<any, { code: number; data: McpServerItem[] }>('/mcp-servers')
}

export function createServer(req: CreateMcpServerRequest) {
  return api.post<any, { code: number; data: McpServerItem }>('/mcp-servers', req)
}

export function updateServer(id: string, req: UpdateMcpServerRequest) {
  return api.put<any, { code: number; data: McpServerItem }>(`/mcp-servers/${id}`, req)
}

export function deleteServer(id: string) {
  return api.delete<any, { code: number; data: any }>(`/mcp-servers/${id}`)
}

export interface McpToolInfo {
  name: string
  title?: string
  description: string
  inputSchema?: Record<string, unknown>
  outputSchema?: Record<string, unknown>
  annotations?: Record<string, unknown>
  meta?: Record<string, unknown>
}

export function testConnection(req: { serverUrl: string; apiKey: string; transport?: string }) {
  return api.post<any, { code: number; data: { success: boolean; message: string; tools: McpToolInfo[] } }>('/mcp-servers/test', req)
}
