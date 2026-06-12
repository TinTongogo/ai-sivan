import api from './index'

/** MCP 服务器项（08-API契约 §3.3，07-工具动态感知 §5.1）。 */
export interface McpServerItem {
  serverId: string
  name: string
  serverUrl: string
  apiKey: string
  transport: string
  active: boolean
  connectionStatus: string   // DISCONNECTED / CONNECTING / CONNECTED / ERROR
  lastError?: string
  lastConnectedAt?: string
  toolCount?: number
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

export interface McpToolInfo {
  name: string
  title?: string
  description: string
  inputSchema?: Record<string, unknown>
  outputSchema?: Record<string, unknown>
  annotations?: Record<string, unknown>
  meta?: Record<string, unknown>
}

export interface PreflightResult {
  serverId: string
  toolName: string
  available: boolean
  message: string | null
  credentialValid: boolean
  isServerLevel: boolean
  isReady: boolean
  toDisplay: string
}

// ── CRUD ──

export function fetchServers() {
  return api.get<any, { code: number; data: McpServerItem[] }>('/v2/mcp-servers')
}

export function createServer(req: CreateMcpServerRequest) {
  return api.post<any, { code: number; data: McpServerItem }>('/v2/mcp-servers', req)
}

export function getServer(serverId: string) {
  return api.get<any, { code: number; data: McpServerItem }>(`/v2/mcp-servers/${serverId}`)
}

export function updateServer(id: string, req: UpdateMcpServerRequest) {
  return api.put<any, { code: number; data: McpServerItem }>(`/v2/mcp-servers/${id}`, req)
}

export function deleteServer(id: string) {
  return api.delete<any, { code: number; data: any }>(`/v2/mcp-servers/${id}`)
}

export function testConnection(req: { serverUrl: string; apiKey: string; transport?: string }) {
  return api.post<any, { code: number; data: { success: boolean; message: string; tools: McpToolInfo[] } }>('/v2/mcp-servers/test', req)
}

// ── 连接管理（07-工具动态感知 §5.2）──

/** 手动连接 MCP 服务器。 */
export function connectServer(serverId: string) {
  return api.post<any, { code: number; data: McpServerItem }>(`/v2/mcp-servers/${serverId}/connect`)
}

/** 手动断开 MCP 服务器。 */
export function disconnectServer(serverId: string) {
  return api.post<any, { code: number; data: McpServerItem }>(`/v2/mcp-servers/${serverId}/disconnect`)
}

/** 查看 MCP 服务器的工具列表。 */
export function listServerTools(serverId: string) {
  return api.get<any, { code: number; data: McpToolInfo[] }>(`/v2/mcp-servers/${serverId}/tools`)
}

/** 运行 MCP 服务器预检。 */
export function preflightServer(serverId: string) {
  return api.post<any, { code: number; data: PreflightResult[] }>(`/v2/mcp-servers/${serverId}/preflight`)
}
