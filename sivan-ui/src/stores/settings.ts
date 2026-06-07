import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  fetchProviders as apiFetchProviders,
  createProvider as apiCreateProvider,
  updateProvider as apiUpdateProvider,
  deleteProvider as apiDeleteProvider,
  setDefaultProvider as apiSetDefault,
  testConnection as apiTestConnection,
  fetchModels as apiFetchModels,
} from '../api/llm-provider'
import type { LlmProviderItem } from '../api/llm-provider'
import {
  fetchServers as apiFetchServers,
  createServer as apiCreateServer,
  updateServer as apiUpdateServer,
  deleteServer as apiDeleteServer,
  testConnection as apiTestMcpConnection,
} from '../api/mcp-server'
import type { McpServerItem } from '../api/mcp-server'

export const useSettingsStore = defineStore('settings', () => {
  const llmProviders = ref<LlmProviderItem[]>([])
  const mcpServers = ref<McpServerItem[]>([])

  async function loadProviders() {
    try {
      const res = await apiFetchProviders()
      llmProviders.value = res.data || []
    } catch {
      llmProviders.value = []
    }
  }

  async function createProvider(req: { name: string; providerType: string; apiKey?: string; baseUrl?: string; model?: string; capabilities?: string; temperature?: number | null; contextLength?: number; tags?: string }) {
    const res = await apiCreateProvider(req)
    llmProviders.value.push(res.data)
    return res.data
  }

  async function updateProvider(id: string, req: Partial<LlmProviderItem>) {
    const res = await apiUpdateProvider(id, req)
    const idx = llmProviders.value.findIndex(p => p.providerId === id)
    if (idx >= 0) llmProviders.value[idx] = res.data
    return res.data
  }

  async function deleteProvider(id: string) {
    await apiDeleteProvider(id)
    llmProviders.value = llmProviders.value.filter(p => p.providerId !== id)
  }

  async function setDefault(id: string) {
    const res = await apiSetDefault(id)
    const updated = res.data
    // 替换已更新的项
    const idx = llmProviders.value.findIndex(p => p.providerId === id)
    if (idx >= 0) llmProviders.value[idx] = updated
    // 同 tag 组内其他提供商标记为非默认（后端已清除，同步前端状态）
    const tag = updated.tags?.split(',')[0]?.trim()
    if (tag) {
      llmProviders.value.forEach(p => {
        if (p.providerId !== id && p.tags?.includes(tag)) {
          p.isDefault = false
        }
      })
    }
    return updated
  }

  async function testConnection(req: { providerType: string; apiKey: string; baseUrl: string }) {
    const res = await apiTestConnection(req)
    return res.data
  }

  async function fetchModels(providerType: string, apiKey: string, baseUrl: string) {
    const res = await apiFetchModels({ providerType, apiKey, baseUrl })
    return res.data
  }

  // ---- MCP 服务器配置 ----

  async function loadMcpServers() {
    try {
      const res = await apiFetchServers()
      mcpServers.value = res.data || []
    } catch {
      mcpServers.value = []
    }
  }

  async function createMcpServer(req: { name: string; serverUrl: string; apiKey?: string; transport?: string }) {
    const res = await apiCreateServer(req)
    mcpServers.value.push(res.data)
    return res.data
  }

  async function updateMcpServer(id: string, req: Partial<McpServerItem>) {
    const res = await apiUpdateServer(id, req)
    const idx = mcpServers.value.findIndex(s => s.serverId === id)
    if (idx >= 0) mcpServers.value[idx] = res.data
    return res.data
  }

  async function deleteMcpServer(id: string) {
    await apiDeleteServer(id)
    mcpServers.value = mcpServers.value.filter(s => s.serverId !== id)
  }

  async function testMcpConnection(serverUrl: string, apiKey: string, transport?: string) {
    const res = await apiTestMcpConnection({ serverUrl, apiKey, transport })
    return res.data
  }

  return {
    llmProviders, loadProviders,
    createProvider, updateProvider, deleteProvider, setDefault,
    testConnection, fetchModels,
    mcpServers, loadMcpServers,
    createMcpServer, updateMcpServer, deleteMcpServer, testMcpConnection,
  }
})
