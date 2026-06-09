import { ref, computed, watch, nextTick } from 'vue'
import api from '../api'
import { fetchGroups, createGroup, renameGroup, deleteGroup, archiveGroup, unarchiveGroup } from '../api/group'
import { useMessage } from '../utils/message'
import { useI18n } from '../utils/i18n'

interface Conversation {
  conversationId: string
  projectId?: string
  title: string
  messageCount: number
  mcpServerIds?: string[]
  lastMessageAt?: string
  createdAt?: string
}

interface Project {
  projectId: string
  name: string
  shortId?: string
  description?: string
  localPath?: string | null
  localPathAuto?: boolean
  archived?: boolean
  archivedAt?: string
  undeletable?: boolean
  createdAt?: string
}

interface Agent {
  agentId: string
  agentName: string
  displayName?: string
}

interface KnowledgeBase {
  kbName: string
  description?: string
}

export function useSidebar() {
  const { t } = useI18n()
  const message = useMessage()

  // ── 基础状态 ──
  const conversations = ref<Conversation[]>([])
  const loadingConversations = ref(false)
  const searchQuery = ref('')
  const sortMode = ref<'time' | 'alpha'>('time')
  const currentConversationId = ref('')
  const currentProjectContext = ref<string | null>(null)
  const selectedGroupId = ref<string | null>(null)
  const projects = ref<Project[]>([])
  const knowledgeBases = ref<KnowledgeBase[]>([])
  const agents = ref<Agent[]>([])
  const selectedAgentId = ref<string | null>(null)

  // ── 侧栏 UI 状态 ──
  const sidebarCollapsed = ref(localStorage.getItem('conv-sidebar-collapsed') === 'true')
  const sidebarHidden = ref(false)
  const settingsOpen = ref(false)
  const groupSettingsOpen = ref(false)

  // ── 编辑状态 ──
  const editingTitle = ref(false)
  const editTitleText = ref('')
  const editingGroupTitle = ref(false)
  const editGroupTitleText = ref('')

  // ── 内联分组管理 ──
  const groupCreating = ref(false)
  const newGroupName = ref('')
  const groupRenamingId = ref<string | null>(null)
  const renameGroupName = ref('')

  // ── 分组设置 ──
  const groupKbNames = ref<string[]>([])
  const groupLocalPath = ref('')
  const groupShortId = ref('')

  // ── 记忆 ──
  const sessionMemories = ref<any[]>([])
  const loadingSessionMemories = ref(false)
  const projectMemories = ref<any[]>([])
  const loadingProjectMemories = ref(false)

  // ── 分组折叠 ──
  function loadGroupCollapsed(): Record<string, boolean> {
    try { return JSON.parse(localStorage.getItem('conv-group-collapsed') || '{}') } catch { return {} }
  }
  const convGroupCollapsed = ref<Record<string, boolean>>(loadGroupCollapsed())

  // ── computed ──
  const filteredConversations = computed(() => {
    let list = conversations.value
    if (searchQuery.value) {
      const q = searchQuery.value.toLowerCase()
      list = list.filter(c => c.title.toLowerCase().includes(q))
    }
    if (sortMode.value === 'alpha') {
      list = [...list].sort((a, b) => a.title.localeCompare(b.title))
    } else {
      list = [...list].sort((a, b) => {
        const ta = Date.parse(a.createdAt || a.lastMessageAt || '')
        const tb = Date.parse(b.createdAt || b.lastMessageAt || '')
        return ta - tb
      })
    }
    return list
  })

  const conversationGroups = computed(() => {
    const groups = new Map<string, { id: string; name: string; conversations: Conversation[] }>()
    for (const p of projects.value) {
      groups.set(p.projectId, { id: p.projectId, name: p.name, conversations: [] })
    }
    // 无 projectId 的对话归入第一个项目
    const firstProjectId = projects.value[0]?.projectId || ''
    for (const c of filteredConversations.value) {
      const gid = c.projectId || firstProjectId
      const group = groups.get(gid)
      if (group) group.conversations.push(c)
    }
    return Array.from(groups.values())
      .sort((a, b) => {
        if (sortMode.value === 'time') {
          const projA = projects.value.find(p => p.projectId === a.id)
          const projB = projects.value.find(p => p.projectId === b.id)
          const ta = Date.parse(projA?.createdAt || '')
          const tb = Date.parse(projB?.createdAt || '')
          return ta - tb
        }
        return a.name.localeCompare(b.name)
      })
  })

  const currentTitle = computed(() => {
    const conv = conversations.value.find(c => c.conversationId === currentConversationId.value)
    return conv?.title || ''
  })

  const currentGroupName = computed(() => {
    if (selectedGroupId.value === null) return ''
    return projects.value.find(p => p.projectId === selectedGroupId.value)?.name || ''
  })

  const showTitlebar = computed(() => !!currentConversationId.value || selectedGroupId.value !== null)

  const selectedKbNames = computed(() => groupKbNames.value)
  const availableKbs = computed(() => {
    if (!groupKbNames.value.length) return []
    const bound = new Set(groupKbNames.value)
    return knowledgeBases.value.filter(kb => bound.has(kb.kbName))
  })

  const currentMcpServerIds = computed(() => {
    if (!currentConversationId.value) return []
    const conv = conversations.value.find(c => c.conversationId === currentConversationId.value)
    return conv?.mcpServerIds || []
  })

  // ── 数据获取 ──
  async function fetchConversations() {
    loadingConversations.value = true
    try {
      const res: any = await api.get('/conversations')
      conversations.value = res.data || []
    } catch { /* noop */ } finally {
      loadingConversations.value = false
    }
  }

  async function fetchContextData() {
    try {
      const groupRes = await fetchGroups()
      projects.value = (groupRes as any) || []
    } catch { /* noop */ }
  }

  let kbFetched = false
  async function fetchKnowledgeBases() {
    if (kbFetched) return
    try {
      const res: any = await api.get('/knowledge-bases')
      knowledgeBases.value = res.data || []
      kbFetched = true
    } catch { /* noop */ }
  }

  let agentsFetched = false
  async function fetchAgents() {
    if (agentsFetched) return
    try {
      const res: any = await api.get('/agents')
      agents.value = res.data || []
      agentsFetched = true
    } catch { /* noop */ }
  }

  async function fetchSessionMemories() {
    const cid = currentConversationId.value
    if (!cid) return
    loadingSessionMemories.value = true
    try {
      const res: any = await api.get(`/memories?level=SESSION&scopeId=${cid}`)
      sessionMemories.value = res.data || []
    } catch { /* ignore */ } finally {
      loadingSessionMemories.value = false
    }
  }

  async function fetchProjectMemories() {
    if (!selectedGroupId.value) return
    loadingProjectMemories.value = true
    try {
      const res: any = await api.get(`/memories?level=PROJECT&scopeId=${selectedGroupId.value}`)
      projectMemories.value = res.data || []
    } catch { /* ignore */ } finally {
      loadingProjectMemories.value = false
    }
  }

  // ── 分组操作 ──
  function persistGroupCollapsed() {
    localStorage.setItem('conv-group-collapsed', JSON.stringify(convGroupCollapsed.value))
  }

  function toggleGroup(id: string) {
    const next = { ...convGroupCollapsed.value, [id]: !(convGroupCollapsed.value[id] ?? true) }
    convGroupCollapsed.value = next
    persistGroupCollapsed()
  }

  // ── 分组 CRUD ──
  async function createGroupInline() {
    const name = newGroupName.value.trim()
    if (!name) return
    try {
      const group = await createGroup(name)
      projects.value.push({ projectId: group.projectId, name: group.name })
      newGroupName.value = ''
      groupCreating.value = false
    } catch (e: any) {
      message.error(e.response?.data?.message || t('createFailed'))
    }
  }

  async function confirmRenameGroup(id: string) {
    const name = renameGroupName.value.trim()
    if (!name) return
    try {
      await renameGroup(id, name)
      const g = projects.value.find(p => p.projectId === id)
      if (g) g.name = name
    } catch (e: any) {
      message.error(e.response?.data?.message || t('renameFailed'))
    } finally {
      groupRenamingId.value = null
    }
  }

  function cancelRenameGroup() {
    groupRenamingId.value = null
  }

  async function deleteGroupInline(id: string, removeFiles: boolean = false) {
    try {
      await deleteGroup(id, removeFiles)
      projects.value = projects.value.filter(p => p.projectId !== id)
      if (currentProjectContext.value === id) currentProjectContext.value = null
    } catch (e: any) {
      message.error(e.response?.data?.message || t('deleteFailed'))
    }
  }

  async function archiveGroupInline(id: string) {
    try {
      const updated = await archiveGroup(id)
      const g = projects.value.find(p => p.projectId === id)
      if (g) {
        g.archived = updated.archived
        g.archivedAt = updated.archivedAt
      }
    } catch (e: any) {
      message.error(e.response?.data?.message || t('operationFailed'))
    }
  }

  async function unarchiveGroupInline(id: string) {
    try {
      const updated = await unarchiveGroup(id)
      const g = projects.value.find(p => p.projectId === id)
      if (g) {
        g.archived = updated.archived
        g.archivedAt = updated.archivedAt
      }
    } catch (e: any) {
      message.error(e.response?.data?.message || t('operationFailed'))
    }
  }

  // ── 分组设置 ──
  async function loadGroupSettings(groupId: string) {
    const key = `sivan-group-${groupId}`
    try {
      const stored = localStorage.getItem(key)
      if (stored) {
        const parsed = JSON.parse(stored)
        groupKbNames.value = parsed.kbNames || []
      } else {
        groupKbNames.value = []
      }
    } catch { groupKbNames.value = [] }
    // 刷新项目列表后读取 localPath 和 shortId
    try {
      const groups = await fetchGroups()
      projects.value = (groups as any) || []
    } catch { /* 保留已有数据 */ }
    const project = projects.value.find(p => p.projectId === groupId)
    groupLocalPath.value = project?.localPath || ''
    groupShortId.value = project?.shortId || ''
  }

  function saveGroupSettings() {
    if (!selectedGroupId.value) return
    const key = `sivan-group-${selectedGroupId.value}`
    localStorage.setItem(key, JSON.stringify({
      kbNames: groupKbNames.value,
    }))
  }

  function onGroupKbChange(kbNames: string[]) {
    groupKbNames.value = kbNames
    saveGroupSettings()
  }

  function onKnowledgeBasesChange(kbNames: string[]) {
    onGroupKbChange(kbNames)
  }

  // ── 侧栏 UI ──
  function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value
    localStorage.setItem('conv-sidebar-collapsed', String(sidebarCollapsed.value))
  }

  function toggleSidebarHidden() {
    sidebarHidden.value = !sidebarHidden.value
  }

  // ── 对话创建/删除 ──
  async function createConversation(title: string): Promise<Conversation | null> {
    try {
      const body: any = { title }
      if (currentProjectContext.value) body.projectId = currentProjectContext.value
      const res: any = await api.post('/conversations', body)
      const conv = res.data as Conversation
      conversations.value.unshift(conv)
      return conv
    } catch (e: any) {
      message.error(e.response?.data?.message || t('createConversationFailed'))
      return null
    }
  }

  async function deleteConversation(id: string): Promise<boolean> {
    try {
      await api.delete(`/conversations/${id}`)
      message.success(t('conversationDeleted'))
      if (currentConversationId.value === id) {
        currentConversationId.value = ''
      }
      await fetchConversations()
      return true
    } catch (e: any) {
      message.error(e.response?.data?.message || t('deleteConversationFailed'))
      return false
    }
  }

  // ── 标题编辑 ──
  async function saveTitle() {
    if (!currentConversationId.value) { editingTitle.value = false; return }
    const title = editTitleText.value.trim() || currentTitle.value
    try {
      await api.put(`/conversations/${currentConversationId.value}`, { title })
      editingTitle.value = false
      await fetchConversations()
    } catch { editingTitle.value = false }
  }

  function cancelEditTitle() {
    editingTitle.value = false
    editTitleText.value = ''
  }

  function startEditTitle() {
    editingTitle.value = true
    editTitleText.value = currentTitle.value
    nextTick(() => {
      (document.querySelector('.chat__title-input') as HTMLInputElement)?.focus()
    })
  }

  async function saveGroupTitle() {
    if (!selectedGroupId.value) {
      editingGroupTitle.value = false
      return
    }
    const name = editGroupTitleText.value.trim()
    if (!name || name === currentGroupName.value) {
      editingGroupTitle.value = false
      return
    }
    try {
      await renameGroup(selectedGroupId.value, name)
      const g = projects.value.find(p => p.projectId === selectedGroupId.value)
      if (g) g.name = name
      editingGroupTitle.value = false
    } catch { editingGroupTitle.value = false }
  }

  function startEditGroupTitle() {
    if (!selectedGroupId.value) return
    editingGroupTitle.value = true
    editGroupTitleText.value = currentGroupName.value
    nextTick(() => {
      (document.querySelector('.chat__title-input') as HTMLInputElement)?.focus()
    })
  }

  // ── MCP Servers ──
  async function onMcpServersChange(ids: string[]) {
    const conv = conversations.value.find(c => c.conversationId === currentConversationId.value)
    if (conv) conv.mcpServerIds = ids
    if (currentConversationId.value) {
      try {
        await api.put(`/conversations/${currentConversationId.value}`, { mcpServerIds: ids.length ? ids : null })
      } catch { /* 静默失败，本地状态已更新 */ }
    }
  }

  async function onConversationKbChange(kbNames: string[]) {
    // 同步更新项目级 KB 绑定
    onGroupKbChange(kbNames)
    // 持久化到对话级别
    if (currentConversationId.value) {
      try {
        await api.put(`/conversations/${currentConversationId.value}`, { knowledgeBaseIds: kbNames.length ? kbNames : null })
      } catch { /* 静默失败 */ }
    }
  }

  // ── watcher ──
  watch(settingsOpen, async (open) => {
    if (open && currentConversationId.value) {
      await Promise.all([fetchSessionMemories(), fetchKnowledgeBases()])
    } else if (!open) {
      sessionMemories.value = []
    }
  })

  watch(groupSettingsOpen, async (open) => {
    if (open && selectedGroupId.value) {
      await Promise.all([fetchProjectMemories(), fetchKnowledgeBases()])
    } else if (!open) {
      projectMemories.value = []
    }
  })

  return {
    // refs
    conversations, loadingConversations, searchQuery, sortMode,
    currentConversationId, currentProjectContext, selectedGroupId,
    projects, knowledgeBases, agents, selectedAgentId,
    sidebarCollapsed, sidebarHidden,
    settingsOpen, groupSettingsOpen,
    editingTitle, editTitleText, editingGroupTitle, editGroupTitleText,
    groupCreating, newGroupName, groupRenamingId, renameGroupName,
    groupKbNames, groupLocalPath, groupShortId,
    sessionMemories, loadingSessionMemories, projectMemories, loadingProjectMemories,
    convGroupCollapsed,
    // computed
    filteredConversations, conversationGroups,
    currentTitle, currentGroupName, showTitlebar,
    selectedKbNames, availableKbs, currentMcpServerIds,
    // methods
    fetchConversations, fetchContextData,
    fetchKnowledgeBases, fetchAgents,
    fetchSessionMemories, fetchProjectMemories,
    toggleGroup, persistGroupCollapsed,
    createGroupInline, confirmRenameGroup, cancelRenameGroup, deleteGroupInline,
    archiveGroupInline, unarchiveGroupInline,
    loadGroupSettings, saveGroupSettings, onGroupKbChange, onKnowledgeBasesChange,
    toggleSidebar, toggleSidebarHidden,
    createConversation, deleteConversation,
    saveTitle, cancelEditTitle, startEditTitle,
    saveGroupTitle, startEditGroupTitle,
    onMcpServersChange, onConversationKbChange,
  }
}
