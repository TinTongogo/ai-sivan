<script setup lang="ts">
import { ref, reactive, onMounted, onBeforeUnmount, nextTick, computed, watch, defineAsyncComponent } from 'vue'
import { useRoute } from 'vue-router'
import { useVirtualizer } from '@tanstack/vue-virtual'
import api from '../../api'
import ChatBubble from '../../components/chat/ChatBubble.vue'
import SessionMemoryDrawer from '../../components/chat/SessionMemoryDrawer.vue'
import ProjectSettingsDrawer from '../../components/chat/ProjectSettingsDrawer.vue'
import SaveToKbModal from '../../components/chat/SaveToKbModal.vue'
import RateModal from '../../components/chat/RateModal.vue'
import PipelineDialog from '../../components/chat/PipelineDialog.vue'
import RoutingDecisionDialog from '../../components/chat/RoutingDecisionDialog.vue'
import PhaseProgressBar from '../../components/orchestration/PhaseProgressBar.vue'
import { useMessage } from '../../utils/message'
import { relativeTime } from '../../utils/time'
import { useI18n } from '../../utils/i18n'
import { useMessageStore, type Message } from '../../composables/useMessageStore'
import { useScrollScheduler } from '../../composables/useScrollScheduler'
import { useChatStream } from '../../composables/useChatStream'
import { useSidebar } from '../../composables/useSidebar'
import { useOrchestrationStore } from '../../stores/orchestration'

const ChatInput = defineAsyncComponent(() => import('../../components/chat/ChatInput.vue'))

const route = useRoute()
const msg = useMessage()
const { t } = useI18n()

// ── 消息仓库 ──
const store = useMessageStore()
const { messages } = store

// ── 侧栏 ──
const sidebar = useSidebar()
const {
  conversations, searchQuery, sortMode,
  currentConversationId, currentProjectContext, selectedGroupId,
  projects, knowledgeBases, selectedAgentId,
  sidebarCollapsed, sidebarHidden,
  settingsOpen, groupSettingsOpen,
  editingTitle, editTitleText, editingGroupTitle, editGroupTitleText,
  groupCreating, newGroupName, groupRenamingId, renameGroupName,
  groupKbNames, groupLocalPath, groupShortId,
  sessionMemories, loadingSessionMemories, projectMemories, loadingProjectMemories,
  convGroupCollapsed, conversationGroups,
  currentTitle, currentGroupName, showTitlebar,
  selectedKbNames, availableKbs, currentMcpServerIds,
} = sidebar
sidebar.currentConversationId.value = route.query.id as string || ''

// ── 虚拟列表 + 滚动调度器 ──
const scrollRef = ref<HTMLDivElement | null>(null)
const autoScroll = ref(true)
const initialScrollDone = ref(true)

// ── 删除确认弹窗 ──
const deleteDialog = ref<{ show: boolean; projectId: string; projectName: string }>({
  show: false, projectId: '', projectName: ''
})

const virtualizer = useVirtualizer({
  get count() { return messages.value.length },
  getScrollElement: () => scrollRef.value,
  estimateSize: (index: number) => {
    // 增大基数给 meta(15px) + actions(24px) 留空间，最后一项额外给底部留白
    let base = 166
    if (isUserWithNextAI(index)) base += 80
    if (index === messages.value.length - 1) base += 60
    return base
  },
  getItemKey: (index: number) => messages.value[index]?._key ?? index,
  overscan: 3,
})

const virtualItems = computed(() => virtualizer.value.getVirtualItems())
const { schedule } = useScrollScheduler(virtualizer, scrollRef)

// ── 聊天流 ──
const loadingMessages = ref(false)
const inputText = ref('')

const chatStream = useChatStream({
  store,
  currentConversationId,
  conversations,
  currentProjectContext,
  selectedAgentId,
  selectedKbNames,
  inputText,
  autoScroll,
  schedule,
})
const { streaming, quoteMsg } = chatStream

// ── 思考状态 ──
const thinkingStates = reactive<Record<string, boolean>>({})

function isUserWithNextAI(index: number): boolean {
  const msg = messages.value[index]
  return msg?.role === 'user' && index + 1 < messages.value.length && messages.value[index + 1]?.role === 'assistant'
}

function getThinkingState(m: Message): boolean { return thinkingStates[m._key] ?? false }
function toggleThinking(m: Message) { thinkingStates[m._key] = !getThinkingState(m) }

function getThinkingDuration(m: Message): string {
  let label = ''
  if (m.thinkingDurationMs) label += ` ${(m.thinkingDurationMs / 1000).toFixed(1)}秒`
  if (m.thinkingTokens) label += ` · ${m.thinkingTokens} tokens`
  return label
}

function measureRef(el: any) {
  if (el && el instanceof Element) {
    requestAnimationFrame(() => { virtualizer.value?.measureElement(el) })
  }
}

// ====== 分组归档/删除辅助 ======
function getGroupArchived(id: string): boolean {
  return !!projects.value.find(p => p.projectId === id)?.archived
}

function handleArchiveGroup(id: string) {
  const g = projects.value.find(p => p.projectId === id)
  if (!g) return
  if (!confirm(t('archiveConfirm', { name: g.name }))) return
  sidebar.archiveGroupInline(id)
}

function handleUnarchiveGroup(id: string) {
  sidebar.unarchiveGroupInline(id)
}

function handleDeleteGroup(id: string) {
  const g = projects.value.find(p => p.projectId === id)
  if (!g) return
  deleteDialog.value = { show: true, projectId: id, projectName: g.name }
}

function confirmDeleteGroup(removeFiles: boolean) {
  deleteDialog.value.show = false
  sidebar.deleteGroupInline(deleteDialog.value.projectId, removeFiles)
}

function cancelDeleteGroup() {
  deleteDialog.value.show = false
}

// ====== 对话生命周期 ======
async function selectGroup(id: string) {
  selectedGroupId.value = id
  currentProjectContext.value = id
  currentConversationId.value = ''
  store.clear()
  await sidebar.loadGroupSettings(id)
  localStorage.setItem('sivan-last-group', id)
}

async function enterConversation(convId: string) {
  autoScroll.value = true
  initialScrollDone.value = false
  _topLoaded = true
  await store.fetchLatest(convId)
  for (const m of messages.value) {
    if (m.status === 'RUNNING') {
      // 断连恢复：先拉取 progress 重建状态，再续接 SSE 流
      recoverOrchestrationProgress(m.messageId)
      chatStream.resumeStream(m)
      break
    }
  }
  await nextTick()
  await schedule({ type: 'bottom' })
  // 等待 scrollToIndex + ResizeObserver 联动产生的异步事件全部完成
  for (let i = 0; i < 4; i++) {
    await new Promise(r => requestAnimationFrame(r))
  }
  initialScrollDone.value = true
  _topLoaded = false
}

async function selectConversation(id: string) {
  chatStream.abortStream()
  loadingMessages.value = true
  currentConversationId.value = id
  const conv = conversations.value.find(c => c.conversationId === id)
  currentProjectContext.value = conv?.projectId || null
  const gid = conv?.projectId || projects.value[0]?.projectId || ''
  selectedGroupId.value = gid
  localStorage.setItem('sivan-last-group', gid)
  await sidebar.loadGroupSettings(gid)
  await enterConversation(id)
  loadingMessages.value = false
}

/** 断连恢复：从 REST 接口拉取编排进度，重建 OrchestrationStore 状态 */
async function recoverOrchestrationProgress(msgId?: string) {
  if (!msgId) return
  try {
    const res: any = await api.get(`/v2/conversations/${currentConversationId.value}/messages/${msgId}/progress`)
    const progressData = res.data
    if (progressData && Object.keys(progressData).length > 0) {
      const orchStore = useOrchestrationStore()
      orchStore.setProgress(progressData as any)
    }
  } catch {
    // 静默失败 — 没有进度也不影响主流程
  }
}

async function newConversation() {
  chatStream.abortStream()
  if (projects.value.length === 0) {
    msg.error(t('noProjectsCreateFirst'))
    return
  }
  if (!currentProjectContext.value) {
    msg.error(t('pleaseSelectProject'))
    return
  }
  const gid = currentProjectContext.value || projects.value[0]?.projectId || ''
  convGroupCollapsed.value = { ...convGroupCollapsed.value, [gid]: false }
  sidebar.persistGroupCollapsed()
  selectedGroupId.value = gid
  currentProjectContext.value = gid
  currentConversationId.value = ''
  store.clear()
  await sidebar.loadGroupSettings(gid)
  localStorage.setItem('sivan-last-group', gid)

  const conv = await sidebar.createConversation(t('newConversation'))
  if (conv) currentConversationId.value = conv.conversationId
}

async function deleteConversation(id: string) {
  if (chatStream.streaming.value && id === currentConversationId.value) chatStream.abortStream()
  await sidebar.deleteConversation(id)
  if (currentConversationId.value === '') store.clear()
}

async function handleDeleteMessage(item: any, index: number) {
  if (item.messageId) {
    try { await api.delete(`/v2/conversations/messages/${item.messageId}`) } catch { /* ignore */ }
  }
  store.removeAt(index)
}

// ====== 滚动 ======
let resizeObserver: ResizeObserver | null = null
let lastResizeScroll = 0
onBeforeUnmount(() => resizeObserver?.disconnect())

watch(scrollRef, (el) => {
  resizeObserver?.disconnect()
  resizeObserver = null
  if (!el) return
  const ro = new ResizeObserver(() => {
    // 容器尺寸变化时若在底部则自动重滚，节流避免输入栏变高时循环跳动
    const now = Date.now()
    if (autoScroll.value && now - lastResizeScroll > 200) {
      lastResizeScroll = now
      nextTick(() => schedule({ type: 'bottom' }))
    }
  })
  ro.observe(el)
  resizeObserver = ro
})

let _lastScrollTop = 0
let _lastLoadMoreTime = 0
const LOAD_MORE_COOLDOWN = 800
const SCROLL_TOP_THRESHOLD = 200

function onScrollerScroll() {
  const el = scrollRef.value
  if (!el) return
  const distFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight
  if (chatStream.streaming.value) {
    if (distFromBottom > 20) autoScroll.value = false
  } else {
    autoScroll.value = distFromBottom < 100
  }
  // 仅在下滚（离开阈值区域）时复位 _topLoaded，防止 loadMore 自身的 scrollTop 调整误复位
  const scrollingDown = el.scrollTop > _lastScrollTop
  if (scrollingDown && el.scrollTop > SCROLL_TOP_THRESHOLD && !_loadingMore) _topLoaded = false
  // 阈值触发：距顶部 200px 内预加载，用户滚到顶时数据已就绪。企业级方案 (Slack/微信均用此模式)
  if (el.scrollTop < SCROLL_TOP_THRESHOLD && store.hasMore.value && !store.loadingMore.value && !_topLoaded && initialScrollDone.value) {
    const now = Date.now()
    if (now - _lastLoadMoreTime >= LOAD_MORE_COOLDOWN) {
      _lastLoadMoreTime = now
      loadMore(el)
    }
  }
  _lastScrollTop = el.scrollTop
}

let _loadingMore = false  // 防并发
let _topLoaded = false    // 防二次触发，需滚动离开后复位

async function loadMore(el: HTMLDivElement) {
  if (_loadingMore || _topLoaded) return
  _loadingMore = true
  _topLoaded = true
  try {
    const prevScrollHeight = el.scrollHeight
    // 隐藏过渡：unshift 后虚拟列表会用旧 scrollOffset 重算可见范围导致错位，
    // visibility:hidden 保留布局和 scrollTop，遮盖这一帧直到调整完毕
    el.style.visibility = 'hidden'
    await store.loadMore(currentConversationId.value)
    await nextTick()
    const delta = el.scrollHeight - prevScrollHeight
    if (delta > 0) {
      el.scrollTop += delta
      el.dispatchEvent(new Event('scroll'))
    }
    // 等一帧让虚拟列表根据调整后的 scrollTop 重新计算可见项，再恢复可见
    await new Promise(r => requestAnimationFrame(r))
    el.style.visibility = ''
  } finally {
    _loadingMore = false
  }
}

function handleCopy(text: string) {
  navigator.clipboard.writeText(text).then(() => msg.success(t('copied')))
}

function handleQuote(m: Message) {
  if (!m.messageId) return
  quoteMsg.value = { messageId: m.messageId, content: m.content, role: m.role }
}

// ====== 保存到知识库 / 评价 ======
const showSaveToKbModal = ref(false)
const saveToKbText = ref('')
function handleSaveToKb(text: string) {
  saveToKbText.value = text
  showSaveToKbModal.value = true
  sidebar.fetchKnowledgeBases()
}

const showRateModal = ref(false)
const rateMsgId = ref('')
function handleRate(m: any) {
  if (!m.messageId) { msg.warning(t('msgNotPersisted')); return }
  rateMsgId.value = m.messageId
  showRateModal.value = true
}

const showPipelineModal = ref(false)
const pipelineMsgId = ref('')
function handleShowPipeline(m: any) {
  if (!m.messageId) return
  pipelineMsgId.value = m.messageId
  showPipelineModal.value = true
}

const showRoutingDecisionModal = ref(false)
const routingDecisionId = ref('')
function handleShowRoutingDecision(id: string) {
  if (!id) return
  routingDecisionId.value = id
  showRoutingDecisionModal.value = true
}

// ====== 生成版本切换 ======
const generationCache = new Map<string, any[]>()
let generationFetchInFlight = new Set<string>()

async function switchGeneration(m: Message, index: number) {
  if (!m.generationGroup || !m.messageId) return
  const groupKey = m.generationGroup
  if (!generationCache.has(groupKey)) {
    if (generationFetchInFlight.has(groupKey)) return
    generationFetchInFlight.add(groupKey)
    try {
      const res: any = await api.get(`/v2/conversations/${currentConversationId.value}/messages/${m.messageId}/generations`)
      const list = (res.data || []) as any[]
      if (list.length <= 1) { generationCache.set(groupKey, []); return }
      generationCache.set(groupKey, list)
      const targetTotal = messages.value[index]
      if (targetTotal) targetTotal.generationTotal = list.length
    } catch { return }
    finally { generationFetchInFlight.delete(groupKey) }
  }
  const versions = generationCache.get(groupKey)
  if (!versions || versions.length <= 1) return
  const currentGen = m.generationIndex || 1
  const curIdx = versions.findIndex((v: any) => v.generationIndex === currentGen)
  const nextIdx = (curIdx + 1) % versions.length
  const nextVer = versions[nextIdx]
  const target = messages.value[index]
  if (!target) return
  target.content = nextVer.content || ''
  target.thinking = nextVer.thinking || undefined
  target.model = nextVer.model
  target.tokens = nextVer.totalTokens
  target.duration = nextVer.durationMs ? `${(nextVer.durationMs / 1000).toFixed(1)}s` : undefined
  target.thinkingDurationMs = nextVer.thinkingDurationMs
  target.generationIndex = nextVer.generationIndex
  target.generationTotal = versions.length
  target.messageId = nextVer.messageId
  target.chain = nextVer.chain

  // 同步更新已打开对话框的引用
  if (showPipelineModal.value) pipelineMsgId.value = nextVer.messageId
  if (showRoutingDecisionModal.value) routingDecisionId.value = nextVer.chain
}

// ====== mount ======
onMounted(async () => {
  await Promise.all([sidebar.fetchConversations(), sidebar.fetchContextData()])
  if (currentConversationId.value) {
    loadingMessages.value = true
    const conv = conversations.value.find(c => c.conversationId === currentConversationId.value)
    selectedGroupId.value = conv?.projectId || projects.value[0]?.projectId || ''
    localStorage.setItem('sivan-last-group', selectedGroupId.value)
    await enterConversation(currentConversationId.value)
    loadingMessages.value = false
  } else {
    const lastGroup = localStorage.getItem('sivan-last-group')
    // 校验 lastGroup 是否属于当前账户：必须在 projects 列表中
    const validLastGroup = !!lastGroup
      && projects.value.some(p => p.projectId === lastGroup)
    const target = validLastGroup
      ? lastGroup! : projects.value[0]?.projectId || ''
    selectedGroupId.value = target
    currentProjectContext.value = target
    currentConversationId.value = ''
    store.clear()
    await sidebar.loadGroupSettings(target)
    localStorage.setItem('sivan-last-group', target)
  }
})
</script>

<template>
  <div class="page-layout">
    <!-- === 左栏：对话列表 === -->
    <div class="sidebar" :class="{ 'is-collapsed': sidebarCollapsed, 'is-hidden': sidebarHidden }">
      <template v-if="!sidebarCollapsed">
        <div class="sidebar__search">
          <div class="sidebar__search-row">
            <input v-model="searchQuery" class="input sidebar__search-input" :placeholder="t('search')" />
            <button class="sidebar__new-btn" @click="newConversation" :title="t('newConversation')">
              <svg viewBox="0 0 20 20" width="14" height="14"><path d="M10 2v16M2 10h16" fill="none" stroke="currentColor" stroke-width="1.6"/></svg>
            </button>
          </div>
          <div class="sidebar__search-meta">
            <span class="sidebar__conv-count">{{ t('convCount', { n: conversations.length }) }}</span>
            <div class="sidebar__sort-tabs">
              <button :class="['sidebar__sort-btn', { 'is-active': sortMode === 'time' }]" @click="sortMode = 'time'">{{ t('sortByTime') }}</button>
              <button :class="['sidebar__sort-btn', { 'is-active': sortMode === 'alpha' }]" @click="sortMode = 'alpha'">{{ t('sortByName') }}</button>
            </div>
          </div>
        </div>
        <div class="sidebar__list">
          <div v-if="conversationGroups.length">
            <div v-for="group in conversationGroups" :key="group.id" class="conv-group">
              <div
                class="conv-group__header"
                :class="{ 'is-active': selectedGroupId === group.id }"
                @click="selectGroup(group.id)"
              >
                <button
                  class="conv-group__toggle"
                  :class="{ 'is-open': convGroupCollapsed[group.id] === false }"
                  @click.stop="sidebar.toggleGroup(group.id)"
                >
                  <svg viewBox="0 0 20 20" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round">
                    <path v-if="convGroupCollapsed[group.id] !== false" d="M2 4h6l1.5 2H18v10H2V4z" />
                    <path v-else d="M2 4h6l2 2h8v10H2V4zM2 16V6" />
                  </svg>
                </button>
                <template v-if="groupRenamingId === group.id">
                  <input
                    class="conv-group__rename-input"
                    v-model="renameGroupName"
                    @keyup.enter="sidebar.confirmRenameGroup(group.id)"
                    @keyup.escape="sidebar.cancelRenameGroup"
                    @blur="sidebar.confirmRenameGroup(group.id)"
                    @click.stop
                  />
                </template>
                <template v-else>
                  <span class="conv-group__name">{{ group.name }}</span>
                  <span v-if="getGroupArchived(group.id)" class="conv-group__archived-badge">{{ t('archived') }}</span>
                </template>
                <span class="conv-group__actions" @click.stop>
                  <button v-if="!getGroupArchived(group.id)" class="conv-group__action-btn" :title="t('archive')" @click="handleArchiveGroup(group.id)">
                    <svg viewBox="0 0 20 20" width="12" height="12"><path d="M2 4h16v2H2V4zm2 3h12v9a2 2 0 01-2 2H6a2 2 0 01-2-2V7zm3 3v4h6v-4H7z" fill="currentColor"/></svg>
                  </button>
                  <button v-else class="conv-group__action-btn" :title="t('unarchive')" @click="handleUnarchiveGroup(group.id)">
                    <svg viewBox="0 0 20 20" width="12" height="12"><path d="M2 4h16v2H2V4zm2 3h12l-1 9a2 2 0 01-2 2H7a2 2 0 01-2-2L4 7zm6 2v4M7 11h6" fill="none" stroke="currentColor" stroke-width="1.3"/></svg>
                  </button>
                  <button class="conv-group__action-btn conv-group__action-btn--danger" :title="t('delete')" @click="handleDeleteGroup(group.id)">
                    <svg viewBox="0 0 20 20" width="12" height="12"><path d="M7 3h6v1h4v2h-1v11a2 2 0 01-2 2H6a2 2 0 01-2-2V6H3V4h4V3zM6 6v10h8V6H6z" fill="currentColor"/></svg>
                  </button>
                </span>
              </div>
              <div v-if="convGroupCollapsed[group.id] === false" class="conv-group__items">
                <div
                  v-for="c in group.conversations"
                  :key="c.conversationId"
                  :class="['conv-item', { 'is-active': c.conversationId === currentConversationId }]"
                  @click="selectConversation(c.conversationId)"
                >
                  <div class="conv-item__main">
                    <div class="conv-item__title">{{ c.title }}</div>
                    <div class="conv-item__meta">
                      <span>{{ relativeTime(c.lastMessageAt) }}</span>
                    </div>
                  </div>
                  <button class="conv-item__delete" :title="t('delete')" @click.stop="deleteConversation(c.conversationId)">
                    <svg viewBox="0 0 20 20" width="13" height="13"><path d="M7 3h6v1h4v2h-1v11a2 2 0 01-2 2H6a2 2 0 01-2-2V6H3V4h4V3zM6 6v10h8V6H6z" fill="currentColor"/></svg>
                  </button>
                </div>
              </div>
            </div>
          </div>
          <div v-else class="empty-state">
            <p>{{ t('noConversations') }}</p>
          </div>
          <!-- 新建项目 -->
          <div class="sidebar__group-create">
            <template v-if="groupCreating">
              <div class="conv-group__create-row">
                <input
                  class="conv-group__create-input"
                  v-model="newGroupName"
                  :placeholder="t('projectNamePlaceholder')"
                  @keyup.enter="sidebar.createGroupInline()"
                  @keyup.escape="groupCreating = false"
                  @blur="groupCreating = false"
                  ref="sidebar.groupCreateInputRef"
                />
                <button class="conv-group__create-cancel" @click="groupCreating = false" :title="t('cancelCreate')">&times;</button>
              </div>
            </template>
            <button v-else class="btn btn-sm btn-ghost sidebar__add-group-btn" @click="groupCreating = true; nextTick(() => (sidebar as any).groupCreateInputRef?.focus?.())">
              <svg viewBox="0 0 20 20" width="14" height="14"><path d="M10 2v16M2 10h16" fill="none" stroke="currentColor" stroke-width="1.5"/></svg>
              {{ t('newProject') }}
            </button>
          </div>
        </div>
      </template>
      <div v-else class="sidebar__collapsed">
        <button class="sidebar__expand-btn" :title="t('newConversation')" @click="newConversation(); sidebar.toggleSidebar()">
          <svg viewBox="0 0 20 20" width="16" height="16"><path d="M10 2v16M2 10h16" fill="none" stroke="currentColor" stroke-width="1.5"/></svg>
        </button>
      </div>
    </div>

    <!-- === 右栏：聊天区 === -->
    <div class="chat">
      <!-- 标题栏 -->
      <div class="chat__titlebar" v-if="showTitlebar">
        <button class="chat__sidebar-toggle" :title="sidebarHidden ? t('showConvList') : t('hideConvList')" @click="sidebar.toggleSidebarHidden()">
          <svg viewBox="0 0 20 20" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round">
            <path v-if="sidebarHidden" d="M8 4h8M4 4v12M4 10h8M4 16h8" />
            <path v-else d="M4 4h12M4 10h12M4 16h12" />
          </svg>
        </button>
        <div class="chat__title-area">
          <template v-if="currentConversationId">
            <input v-if="editingTitle" v-model="editTitleText"
              class="chat__title-input" @blur="sidebar.saveTitle()" @keyup.enter="sidebar.saveTitle()" @keyup.escape="sidebar.cancelEditTitle()" />
            <template v-else>
              <span class="chat__title-text" @click="sidebar.startEditTitle()">{{ currentTitle || t('newConversation') }}</span>
              <button class="chat__title-edit" @click="sidebar.startEditTitle()" :title="t('rename')">
                <svg viewBox="0 0 20 20" width="14" height="14"><path d="M13.5 3.5l3 3L7 16H4v-3l9.5-9.5z" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></svg>
              </button>
            </template>
          </template>
          <template v-else-if="selectedGroupId">
            <svg class="chat__group-icon" viewBox="0 0 20 20" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.3">
              <path d="M2 4h6l2 2h8v10H2V4z" />
            </svg>
            <input v-if="editingGroupTitle" v-model="editGroupTitleText"
              class="chat__title-input" @blur="sidebar.saveGroupTitle()" @keyup.enter="sidebar.saveGroupTitle()" @keyup.escape="editingGroupTitle = false" />
            <span v-else class="chat__title-text" @click="sidebar.startEditGroupTitle()">{{ currentGroupName }}</span>
          </template>
        </div>
        <div v-if="currentConversationId" class="chat__titlebar-actions">
          <button class="btn btn-icon" @click="settingsOpen = true" :title="t('convSettings')">
            <svg viewBox="0 0 20 20" width="16" height="16"><path d="M10 13a3 3 0 100-6 3 3 0 000 6zm7.5-3l1.5 1-1 2-1.8-.5a7 7 0 01-.8 1.5l.5 1.8-2 1-1.2-1.5a7 7 0 01-1.7 0L8.5 17l-2-1 .5-1.8a7 7 0 01-.8-1.5L4.5 13l-1-2 1.5-1a7 7 0 010-1.7L3.5 7l1-2 1.8.5a7 7 0 01.8-1.5L6.5 2l2-1 1.2 1.5a7 7 0 011.7 0L12.5 1l2 1-.5 1.8a7 7 0 01.8 1.5L16.5 5l1 2-1.5 1a7 7 0 010 1.7z" fill="none" stroke="currentColor" stroke-width="1.2"/></svg>
          </button>
        </div>
        <div v-if="!currentConversationId && selectedGroupId" class="chat__titlebar-actions">
          <button class="btn btn-icon" @click="groupSettingsOpen = true" :title="t('projectSettings')">
            <svg viewBox="0 0 20 20" width="16" height="16"><path d="M10 13a3 3 0 100-6 3 3 0 000 6zm7.5-3l1.5 1-1 2-1.8-.5a7 7 0 01-.8 1.5l.5 1.8-2 1-1.2-1.5a7 7 0 01-1.7 0L8.5 17l-2-1 .5-1.8a7 7 0 01-.8-1.5L4.5 13l-1-2 1.5-1a7 7 0 010-1.7L3.5 7l1-2 1.8.5a7 7 0 01.8-1.5L6.5 2l2-1 1.2 1.5a7 7 0 011.7 0L12.5 1l2 1-.5 1.8a7 7 0 01.8 1.5L16.5 5l1 2-1.5 1a7 7 0 010 1.7z" fill="none" stroke="currentColor" stroke-width="1.2"/></svg>
          </button>
        </div>
      </div>

      <!-- 虚拟滚动消息列表 -->
      <template v-if="messages.length > 0 || loadingMessages">
        <div class="messages-scroll">
          <div v-if="loadingMessages" class="messages-loading-overlay">
            <div class="chat__loading-spinner" />
          </div>
          <div v-if="messages.length > 0" ref="scrollRef" class="dynamic-scroller" @scroll="onScrollerScroll">
          <div :style="{ height: `${virtualizer.getTotalSize()}px`, width: '100%', position: 'relative', paddingBottom: '80px' }">
            <div v-for="virtualRow in virtualItems"
              :key="messages[virtualRow.index]?._key ?? virtualRow.index"
              :data-index="virtualRow.index"
              :ref="measureRef"
              :style="{
                position: 'absolute',
                top: 0,
                left: 0,
                width: '100%',
                transform: `translateY(${virtualRow.start}px)`,
              }"
              class="dynamic-scroller__item"
            >
              <div v-if="virtualRow.index === 0" class="load-more-trigger">
                <span v-if="store.loadingMore.value" class="load-more-spinner"></span>
                <span v-else-if="store.allLoaded.value" class="load-more-hint load-more-end">{{ t('noMoreMessages') }}</span>
              </div>
              <ChatBubble
                :key="messages[virtualRow.index]?._key"
                :message="messages[virtualRow.index]"
                :meta="messages[virtualRow.index].role === 'assistant' ? {
                  duration: messages[virtualRow.index].duration,
                  model: messages[virtualRow.index].model,
                  tokens: messages[virtualRow.index].tokens,
                  thinkingTokens: messages[virtualRow.index].thinkingTokens,
                } : undefined"
                :streaming="streaming && virtualRow.index === messages.length - 1"
                :hideExtra="isUserWithNextAI(virtualRow.index) ? 'user-footer' : (virtualRow.index > 0 && messages[virtualRow.index - 1]?.role === 'user' && messages[virtualRow.index]?.role === 'assistant' ? 'thinking-toggle' : 'none')"
                :thinkingOpen="messages[virtualRow.index].role === 'assistant' ? getThinkingState(messages[virtualRow.index]) : undefined"
                @update:thinkingOpen="(v: boolean) => { if (messages[virtualRow.index]) thinkingStates[messages[virtualRow.index]._key] = v }"
                @copy="handleCopy"
                @quote="() => handleQuote(messages[virtualRow.index])"
                @delete="() => handleDeleteMessage(messages[virtualRow.index], virtualRow.index)"
                @save-to-kb="handleSaveToKb"
                @rate="handleRate(messages[virtualRow.index])"
                @regenerate="chatStream.handleRegenerate(virtualRow.index)"
                @switch-generation="switchGeneration(messages[virtualRow.index], virtualRow.index)"
                @show-pipeline="handleShowPipeline(messages[virtualRow.index])"
                @show-routing-decision="handleShowRoutingDecision"
              >
                <template v-if="messages[virtualRow.index].role === 'user'" #time>
                  {{ messages[virtualRow.index].createdAt ? relativeTime(messages[virtualRow.index].createdAt) : '' }}
                </template>
              </ChatBubble>

              <!-- 合并行 -->
              <div v-if="isUserWithNextAI(virtualRow.index)" class="merge-row">
                <div class="merge-row__left">
                  <button class="merge-toggle" @click="toggleThinking(messages[virtualRow.index + 1])" :title="t('thinking')">
                    <svg class="toggle__chevron" :class="{ 'is-open': getThinkingState(messages[virtualRow.index + 1]) }" viewBox="0 0 20 20" width="10" height="10">
                      <path d="M6 8l4 4 4-4" fill="none" stroke="currentColor" stroke-width="1.5"/>
                    </svg>
                    <span class="merge-toggle__label">{{ getThinkingState(messages[virtualRow.index + 1]) ? t('hideThinking') : t('thoughtTime') }}{{ getThinkingDuration(messages[virtualRow.index + 1]) }}</span>
                  </button>
                  <span class="merge-thinking-actions">
                    <button class="merge-action-btn" :title="t('copyThinking')" @click="handleCopy(messages[virtualRow.index + 1].thinking || '')">
                      <svg viewBox="0 0 20 20" width="12" height="12" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"><rect x="5.5" y="2.5" width="10" height="13" rx="1.5"/><rect x="3.5" y="4.5" width="10" height="13" rx="1.5"/></svg>
                    </button>
                    <button class="merge-action-btn" :title="t('saveThinkingToKb')" @click="handleSaveToKb(messages[virtualRow.index + 1].thinking || '')">
                      <svg viewBox="0 0 20 20" width="12" height="12" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"><path d="M4 13v2a1 1 0 001 1h10a1 1 0 001-1v-2"/><path d="M10 3v9M7 9l3 3 3-3"/></svg>
                    </button>
                  </span>
                </div>
                <div class="merge-row__right">
                  <div class="merge-actions">
                    <button class="merge-action-btn" :title="t('copy')" @click="handleCopy(messages[virtualRow.index].content)">
                      <svg viewBox="0 0 20 20" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"><rect x="5.5" y="2.5" width="10" height="13" rx="1.5"/><rect x="3.5" y="4.5" width="10" height="13" rx="1.5"/></svg>
                    </button>
                    <button class="merge-action-btn" :title="t('quoteReply')" @click="handleQuote(messages[virtualRow.index])">
                      <svg viewBox="0 0 20 20" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"><path d="M3 3.5A1.5 1.5 0 014.5 2h11A1.5 1.5 0 0117 3.5v9a1.5 1.5 0 01-1.5 1.5H10l-3 3v-3H4.5A1.5 1.5 0 013 12.5v-9z"/></svg>
                    </button>
                    <button class="merge-action-btn" :title="t('deleteMsg')" @click="() => handleDeleteMessage(messages[virtualRow.index], virtualRow.index)">
                      <svg viewBox="0 0 20 20" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"><path d="M3 4h14M5 4v12a2 2 0 002 2h6a2 2 0 002-2V4M8 4V3a1 1 0 011-1h2a1 1 0 011 1v1"/></svg>
                    </button>
                  </div>
                  <span class="merge-time">{{ messages[virtualRow.index].createdAt ? relativeTime(messages[virtualRow.index].createdAt) : '' }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
      </template>
      <div v-else class="messages-scroll">
        <div class="chat__empty">
          <div class="empty-state">
            <p>{{ currentConversationId ? t('sendFirstMessage') : t('selectOrCreateConv') }}</p>
          </div>
        </div>
      </div>

      <!-- 回到底部 -->
      <button v-if="!autoScroll && messages.length > 0" class="scroll-bottom-btn" @click="autoScroll = true; schedule({ type: 'bottom', smooth: true })">
        <svg viewBox="0 0 20 20" width="16" height="16"><path d="M10 3v10l-4-4m4 4l4-4" fill="none" stroke="currentColor" stroke-width="1.5"/></svg>
        {{ t('backToLatest') }}
      </button>

      <!-- 编排进度条 -->
      <PhaseProgressBar />

      <!-- 输入区 -->
      <ChatInput
        :conversationId="currentConversationId"
        :streaming="streaming"
        v-model="inputText"
        :quoteMsg="quoteMsg"
        :mcpServerIds="currentMcpServerIds"
        :knowledgeBases="availableKbs"
        :selectedKbNames="selectedKbNames"
        @send="chatStream.sendMessage"
        @cancel="chatStream.cancelStream"
        @closeQuote="quoteMsg = null"
        @openSettings="currentConversationId ? (settingsOpen = true) : (groupSettingsOpen = true)"
        @mcpServersChange="sidebar.onMcpServersChange"
        @knowledgeBasesChange="sidebar.onConversationKbChange"
      />
    </div>

    <!-- 抽屉/弹窗 -->
    <SessionMemoryDrawer
      v-if="settingsOpen"
      :memories="sessionMemories"
      :loading="loadingSessionMemories"
      @close="settingsOpen = false"
    />
    <ProjectSettingsDrawer
      v-if="groupSettingsOpen"
      :groupId="selectedGroupId || ''"
      :groupLocalPath="groupLocalPath"
      :groupShortId="groupShortId"
      :groupKbNames="groupKbNames"
      :knowledgeBases="knowledgeBases"
      :projectMemories="projectMemories"
      :loadingMemories="loadingProjectMemories"
      @close="groupSettingsOpen = false"
      @update:groupKbNames="v => { groupKbNames = v; sidebar.saveGroupSettings() }"
    />
    <SaveToKbModal
      v-if="showSaveToKbModal"
      :text="saveToKbText"
      :knowledgeBases="knowledgeBases"
      @close="showSaveToKbModal = false"
    />
    <RateModal
      v-if="showRateModal"
      :messageId="rateMsgId"
      @close="showRateModal = false"
    />
    <PipelineDialog
      v-if="showPipelineModal && pipelineMsgId"
      :messageId="pipelineMsgId"
      :conversationId="currentConversationId"
      @close="showPipelineModal = false"
    />
    <RoutingDecisionDialog
      v-if="showRoutingDecisionModal && routingDecisionId"
      :routingDecisionId="routingDecisionId"
      @close="showRoutingDecisionModal = false"
    />

    <!-- 删除确认弹窗 -->
    <div v-if="deleteDialog.show" class="modal-overlay" @click.self="cancelDeleteGroup">
      <div class="modal" style="max-width:400px;">
        <div class="modal__header">
          <h3>{{ t('deleteConfirm') }}</h3>
          <button class="modal__close" @click="cancelDeleteGroup">&times;</button>
        </div>
        <div class="modal__body">
          <p>{{ t('deleteProjectConfirm', { name: deleteDialog.projectName }) }}</p>
        </div>
        <div class="modal__footer" style="display:flex;gap:8px;justify-content:flex-end;">
          <button class="btn btn-ghost" @click="cancelDeleteGroup">{{ t('cancel') }}</button>
          <button class="btn btn-ghost" @click="confirmDeleteGroup(false)">{{ t('deleteDirOnly') }}</button>
          <button class="btn btn-danger" @click="confirmDeleteGroup(true)">{{ t('deleteAll') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ================================
   左栏 — 对话列表
   ================================ */
.page-layout {
  padding: 0;
  flex-direction: row;
  overflow-y: hidden;
  gap: 0;
}

.sidebar {
  width: 260px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  position: relative;
  border-right: 0.5px solid var(--clr-hairline);
  background: var(--clr-bg-sidebar);
  transition: width var(--dur-normal) var(--ease-spring);
}
.sidebar.is-collapsed { width: 44px; }
.sidebar.is-hidden { width: 0; border-right: none; overflow: hidden; }
.sidebar__collapsed {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 4px 0;
}
.sidebar__expand-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: var(--rad-md);
  background: transparent;
  color: var(--clr-tertiary);
  cursor: pointer;
  transition: var(--tr-fast);
}
.sidebar__expand-btn:hover { background: var(--clr-fill-hover); color: var(--clr-label); }
.sidebar__search { display: flex; flex-direction: column; gap: 6px; padding: 12px 12px 6px; }
.sidebar__search-row { display: flex; align-items: center; gap: 6px; }
.sidebar__search-input { flex: 1; min-width: 0; }
.sidebar__new-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px; height: 28px;
  border: none;
  border-radius: var(--rad-md);
  background: transparent;
  color: var(--clr-tertiary);
  cursor: pointer;
  flex-shrink: 0;
  transition: var(--tr-fast);
}
.sidebar__new-btn:hover { background: var(--clr-fill-hover); color: var(--clr-label); }
.sidebar__search-meta { display: flex; align-items: center; justify-content: space-between; padding: 0 2px; }
.sidebar__conv-count { font-size: var(--fs-caption2); color: var(--clr-tertiary); }
.sidebar__sort-tabs { display: flex; gap: 0; border: 1px solid var(--clr-hairline); border-radius: var(--rad-md); overflow: hidden; }
.sidebar__sort-btn {
  font-size: 10px;
  padding: 1px 6px;
  height: 18px;
  border: none;
  background: transparent;
  color: var(--clr-tertiary);
  cursor: pointer;
  font-family: inherit;
  letter-spacing: 0.3px;
  transition: background var(--dur-fast) var(--ease-out), color var(--dur-fast) var(--ease-out);
}
.sidebar__sort-btn.is-active { background: var(--clr-accent-soft); color: var(--clr-accent); font-weight: var(--fw-medium); }
.sidebar__sort-btn + .sidebar__sort-btn { border-left: 1px solid var(--clr-hairline); }
.sidebar__list { flex: 1; overflow-y: auto; }

/* 对话分组 */
.conv-group { margin-top: 2px; }
.conv-group__header {
  display: flex; align-items: center; gap: 4px;
  padding: 6px 12px 4px; margin: 0 8px;
  border-radius: var(--rad-md);
  cursor: pointer; user-select: none;
  transition: background var(--dur-fast) var(--ease-out);
}
.conv-group__header:hover { background: var(--clr-fill); }
.conv-group__header.is-active { background: var(--clr-accent-soft); color: var(--clr-accent); }
.conv-group__toggle {
  display: inline-flex; align-items: center; justify-content: center;
  width: 20px; height: 20px;
  border: none; background: transparent;
  color: var(--clr-quaternary); cursor: pointer; padding: 0; flex-shrink: 0;
  transition: color var(--dur-fast) var(--ease-out);
}
.conv-group__toggle.is-open { color: var(--clr-accent); }
.conv-group__toggle:hover { color: var(--clr-accent); }
.conv-group__name {
  font-size: var(--fs-callout); font-weight: var(--fw-semibold);
  color: var(--clr-label); flex: 1;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.conv-group__archived-badge {
  font-size: var(--fs-nano, 10px); font-weight: var(--fw-medium);
  color: var(--clr-tertiary); background: var(--clr-fill-hover);
  padding: 0 5px; border-radius: 3px; flex-shrink: 0; margin-right: 4px;
}
.conv-group__actions {
  display: flex; align-items: center; gap: 1px; flex-shrink: 0; margin-right: 2px;
  visibility: hidden; opacity: 0;
  transition: opacity var(--dur-fast) var(--ease-out);
}
.conv-group__header:hover .conv-group__actions { visibility: visible; opacity: 1; }
.conv-group__action-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 20px; height: 20px;
  border: none; border-radius: 4px; background: transparent;
  color: var(--clr-tertiary); cursor: pointer; padding: 0;
  transition: background var(--dur-fast) var(--ease-out), color var(--dur-fast) var(--ease-out);
}
.conv-group__action-btn:hover { background: var(--clr-fill-hover); color: var(--clr-label); }
.conv-group__action-btn--danger:hover { color: var(--clr-red); }
.conv-group__rename-input {
  flex: 1;
  font-size: var(--fs-footnote); font-weight: var(--fw-semibold);
  padding: 1px 4px; border: 1px solid var(--clr-accent); border-radius: 4px;
  background: var(--clr-bg); color: var(--clr-label); outline: none;
  font-family: inherit; min-width: 0;
}
.conv-group__create-input {
  flex: 1;
  font-size: var(--fs-footnote); padding: 4px 8px;
  border: 1px solid var(--clr-accent); border-radius: var(--rad-md);
  background: var(--clr-bg); color: var(--clr-label); outline: none; font-family: inherit;
}
.conv-group__create-row { display: flex; align-items: center; gap: 4px; }
.conv-group__create-cancel {
  display: inline-flex; align-items: center; justify-content: center;
  width: 22px; height: 22px;
  border: none; border-radius: 50%; background: transparent;
  color: var(--clr-tertiary); cursor: pointer; font-size: 16px; font-family: inherit; flex-shrink: 0;
  transition: background var(--dur-fast) var(--ease-out), color var(--dur-fast) var(--ease-out);
}
.conv-group__create-cancel:hover { background: var(--clr-fill-hover); color: var(--clr-label); }
.sidebar__group-create { padding: 8px 16px 12px; }
.sidebar__add-group-btn {
  width: 100%; border-style: dashed !important;
  border-color: var(--clr-separator-light); color: var(--clr-tertiary);
}
.sidebar__add-group-btn:hover { color: var(--clr-accent); border-color: var(--clr-accent); }
.conv-group__items { margin-top: 1px; }

/* 对话列表项 */
.conv-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 7px 12px; margin: 1px 8px;
  border-radius: var(--rad-md); cursor: pointer;
  transition: background var(--dur-fast) var(--ease-out);
  contain: layout style paint;
}
.conv-item:hover { background: var(--clr-fill); }
.conv-item.is-active { background: var(--clr-accent-soft); }
.conv-item__main { flex: 1; min-width: 0; }
.conv-item__title {
  font-size: var(--fs-footnote); font-weight: var(--fw-medium);
  color: var(--clr-label);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.conv-item__meta {
  display: flex; align-items: center; gap: 8px; margin-top: 2px;
  font-size: var(--fs-footnote); color: var(--clr-tertiary);
}
.conv-item__delete {
  display: flex; align-items: center; justify-content: center;
  width: 22px; height: 22px;
  border: none; border-radius: 4px; background: transparent;
  color: var(--clr-quaternary); cursor: pointer; flex-shrink: 0;
  visibility: hidden; opacity: 0;
  transition: opacity var(--dur-fast) var(--ease-out);
}
.conv-item:hover .conv-item__delete,
.conv-item.is-active .conv-item__delete { visibility: visible; opacity: 1; }
.conv-item__delete:hover { background: rgba(255, 59, 48, 0.10); color: var(--clr-red); }

/* ================================
   右栏 — 聊天区
   ================================ */
.chat__titlebar {
  display: flex; align-items: center; justify-content: center;
  padding: 10px 20px; border-bottom: 1px solid var(--clr-separator-light);
  min-height: 48px; flex-shrink: 0; position: relative;
}
.chat__sidebar-toggle {
  position: absolute; left: 14px; top: 50%; transform: translateY(-50%);
  display: inline-flex; align-items: center; justify-content: center;
  width: 30px; height: 30px;
  border: none; border-radius: var(--rad-md); background: transparent;
  color: var(--clr-tertiary); cursor: pointer; flex-shrink: 0;
  transition: var(--tr-fast);
}
.chat__sidebar-toggle:hover { background: var(--clr-fill); color: var(--clr-label); }
.chat__title-area { display: flex; align-items: center; justify-content: center; gap: 6px; min-width: 0; max-width: 400px; }
.chat__title-text {
  font-size: var(--fs-headline); font-weight: var(--fw-semibold);
  color: var(--clr-label); cursor: pointer;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  transition: color var(--dur-fast) var(--ease-out);
}
.chat__title-text:hover { color: var(--clr-accent); }
.chat__title-text--static { cursor: default; }
.chat__title-text--static:hover { color: var(--clr-label); }
.chat__group-icon { flex-shrink: 0; color: var(--clr-tertiary); }
.chat__title-edit {
  display: inline-flex; align-items: center; justify-content: center;
  width: 24px; height: 24px;
  border: none; border-radius: 4px; background: transparent;
  color: var(--clr-quaternary); cursor: pointer;
  transition: color var(--dur-fast) var(--ease-out), background var(--dur-fast) var(--ease-out);
  flex-shrink: 0;
}
.chat__title-edit:hover { background: var(--clr-fill); color: var(--clr-secondary); }
.chat__title-input {
  font-size: var(--fs-headline); font-weight: var(--fw-semibold);
  font-family: inherit; padding: 4px 8px;
  border: 1px solid var(--clr-accent); border-radius: var(--rad-md);
  outline: none; background: var(--clr-bg); color: var(--clr-label);
  width: 100%; max-width: 200px;
}
.chat__titlebar-actions {
  position: absolute; right: 14px; top: 50%; transform: translateY(-50%);
  display: flex; align-items: center; gap: 8px; flex-shrink: 0;
}
.chat {
  flex: 1; display: flex; flex-direction: column;
  min-width: 420px; background: var(--clr-bg); position: relative;
}
.chat__empty { display: flex; align-items: center; justify-content: center; height: 100%; }
.chat__loading-spinner {
  width: 24px; height: 24px;
  border: 2px solid var(--clr-hairline); border-top-color: var(--clr-accent);
  border-radius: 50%; animation: chat-spin 0.6s linear infinite;
}
@keyframes chat-spin { to { transform: rotate(360deg); } }
.messages-loading-overlay {
  position: absolute; inset: 0;
  display: flex; align-items: center; justify-content: center;
  background: rgba(235, 235, 235, 0.5); z-index: 10; pointer-events: none;
}
html.dark .messages-loading-overlay { background: rgba(34, 34, 38, 0.5); }
.messages-scroll { position: relative; flex: 1; min-height: 0; background: var(--clr-bg-chat); }
.dynamic-scroller { height: 100%; overflow-y: auto; overflow-anchor: none; }
.dynamic-scroller__item { padding: 0 24px 4px; contain: layout style; }

/* 上滚加载更多 */
.load-more-trigger { display: flex; justify-content: center; padding: 12px 0; }
.load-more-spinner {
  display: inline-block; width: 16px; height: 16px;
  border: 2px solid var(--clr-quaternary); border-top-color: transparent;
  border-radius: 50%; animation: spin 0.6s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
.load-more-hint { font-size: var(--fs-footnote); color: var(--clr-quaternary); }
.load-more-end { color: var(--clr-tertiary); }

/* 回到底部 */
.scroll-bottom-btn {
  position: absolute; bottom: 100px; left: 50%; transform: translateX(-50%);
  display: flex; align-items: center; gap: 4px;
  padding: 6px 14px;
  border: 1px solid var(--clr-separator); border-radius: var(--rad-full);
  background: var(--clr-bg); color: var(--clr-secondary);
  font-size: var(--fs-footnote); cursor: pointer;
  box-shadow: var(--shd-card); z-index: var(--z-overlay);
  white-space: nowrap; transition: var(--tr-fast); font-family: inherit;
}
.scroll-bottom-btn:hover { background: var(--clr-bg-secondary); color: var(--clr-label); }


/* 跨消息合并行 */
.merge-row {
  display: flex; align-items: center; justify-content: space-between;
  min-height: 28px; padding: 2px 0;
}
.merge-row__left { display: flex; align-items: center; padding-left: 44px; }
.merge-row__right { display: flex; align-items: center; gap: 8px; padding-right: 44px; }
.merge-toggle {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 2px 8px; border: none; border-radius: 4px;
  background: transparent; color: var(--clr-quaternary);
  font-size: var(--fs-caption); cursor: pointer; transition: color 0.12s ease;
}
.merge-toggle:hover { color: var(--clr-secondary); }
.merge-toggle .toggle__chevron { transition: transform 0.2s var(--ease-spring); }
.merge-toggle .toggle__chevron.is-open { transform: rotate(180deg); }
.merge-toggle__label { font-weight: var(--fw-regular); }
.merge-thinking-actions { display: inline-flex; gap: 1px; opacity: 0; transition: opacity 0.18s ease; }
.merge-row:hover .merge-thinking-actions { opacity: 1; }
.merge-actions { display: flex; gap: 1px; opacity: 0; transition: opacity 0.18s ease; }
.merge-row:hover .merge-actions { opacity: 1; }
.merge-action-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 24px; height: 24px; border: none; border-radius: 4px;
  background: transparent; color: var(--clr-quaternary);
  cursor: pointer; transition: background 0.12s ease, color 0.12s ease, transform 0.12s ease;
}
.merge-action-btn:hover { background: var(--clr-fill); color: var(--clr-secondary); }
.merge-action-btn:active { transform: scale(0.9); }
.merge-time { font-size: var(--fs-footnote); color: var(--clr-tertiary); }
</style>