<script setup lang="ts">
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import { useSettingsStore } from '../../stores/settings'
import { uploadFile, getFileIcon } from '../../api/files'
import type { Attachment } from '../../api/files'
import { fetchAuthBlob } from '../../utils/auth-fetch'
import { useMessage } from '../../utils/message'
import { useI18n } from '../../utils/i18n'
import { usePreferencesStore } from '../../stores/preferences'

const message = useMessage()
const { t } = useI18n()
const prefsStore = usePreferencesStore()

const props = defineProps<{
  conversationId: string | null
  streaming: boolean
  modelValue: string
  quoteMsg?: { messageId: string; content: string; role: string } | null
  mcpServerIds?: string[]
  knowledgeBases?: { kbName: string; description?: string }[]
  selectedKbNames?: string[]
}>()

const emit = defineEmits<{
  send: [payload: {
    content: string
    images?: string[]
    attachments?: { fileId: string; fileName: string; mimeType: string; fileSize: number }[]
    replyToId?: string
    modelProviderId?: string
    mcpServerIds?: string[]
  }]
  cancel: []
  'update:modelValue': [value: string]
  'openSettings': [tab?: string]
  'closeQuote': []
  'mcpServersChange': [ids: string[]]
  'knowledgeBasesChange': [kbNames: string[]]
}>()

// ====== Model Selector (响应式：直接读 store，provider 变更即时生效) ======
const selectedProviderId = ref<string | null>(null)
const modelDropdownOpen = ref(false)
const streamEnabled = ref(true)

// 直接从 store 读取已过滤的聊天 provider 列表
const chatProviders = computed(() =>
  settingsStore.llmProviders.filter(p => p.active !== false && (p.tags || '').includes('chat'))
)

const capLabels = computed<Record<string, string>>(() => ({
  vision: t('capacityImage'),
  tool_use: t('capacityTool'),
  streaming: t('capacityStream'),
  thinking: t('capacityThink'),
  reasoning_effort: t('capacityReasoning'),
}))

const currentModelLabel = computed(() => {
  if (!chatProviders.value.length) return t('notConfigured')
  const sel = chatProviders.value.find(p => p.providerId === selectedProviderId.value)
  if (sel) return `${sel.name} · ${sel.model}`
  // fallback to default
  const def = chatProviders.value.find(p => p.isDefault)
  if (def) {
    selectedProviderId.value = def.providerId
    return `${def.name} · ${def.model}`
  }
  // 恢复 localStorage 中上次选择的 provider
  const saved = localStorage.getItem('chat_selected_provider')
  if (saved && chatProviders.value.some(p => p.providerId === saved)) {
    selectedProviderId.value = saved
    const p = chatProviders.value.find(p => p.providerId === saved)!
    return `${p.name} · ${p.model}`
  }
  const first = chatProviders.value[0]
  selectedProviderId.value = first.providerId
  return `${first.name} · ${first.model}`
})

onMounted(async () => {
  // 预加载 MCP 服务器列表，确保 MCP 按钮红点能在进入会话时正确显示
  settingsStore.loadMcpServers()
  // 首次加载 provider 列表；后续保存/删除由 store 维护，无需重新加载
  if (!settingsStore.llmProviders.length) {
    await settingsStore.loadProviders()
  }
})

function selectModel(id: string) {
  selectedProviderId.value = id
  localStorage.setItem('chat_selected_provider', id)
  modelDropdownOpen.value = false
}

// ====== Attachment Upload ======
const selectedAttachments = ref<Attachment[]>([])
const attachmentMenuOpen = ref(false)

function toggleAttachmentMenu() {
  attachmentMenuOpen.value = !attachmentMenuOpen.value
}

function triggerImageUpload() {
  attachmentMenuOpen.value = false
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = 'image/*'
  input.multiple = true
  input.onchange = (e) => handleFiles(e)
  input.click()
}

function triggerFileUpload() {
  attachmentMenuOpen.value = false
  const input = document.createElement('input')
  input.type = 'file'
  input.multiple = true
  input.onchange = (e) => handleFiles(e)
  input.click()
}

const SUPPORTED_EXTENSIONS = new Set([
  'jpg','jpeg','png','gif','webp','svg','bmp',
  'mp3','wav','ogg','opus','flac','m4a','aac',
  'txt','text','md','markdown','csv','html','htm','xml','json','yaml','yml',
  'js','mjs','ts','tsx','py','java','kt','sh','bash','toml','log','sql',
  'pdf','doc','docx','xls','xlsx','ppt','pptx',
])

function getExt(filename: string) {
  return filename.split('.').pop()?.toLowerCase() || ''
}

async function handleFiles(e: Event) {
  const files = (e.target as HTMLInputElement).files
  if (!files) return
  const uploads = Array.from(files).map(async (file) => {
    const ext = getExt(file.name)
    if (!SUPPORTED_EXTENSIONS.has(ext)) {
      message.warning(`不支持的文件类型: ${file.name}`)
      return
    }
    try {
      const result = await uploadFile(file)
      selectedAttachments.value.push({
        fileId: result.fileId,
        fileName: file.name,
        mimeType: result.mimeType,
        fileSize: result.fileSize,
        type: file.type.startsWith('image/') ? 'image' : 'file',
      })
    } catch {
      message.error(t('uploadFailed', { name: file.name }))
    }
  })
  await Promise.all(uploads)
  ;(e.target as HTMLInputElement).value = ''
}

// 图片缩略图 blob URL 缓存
const thumbnailBlobUrls = ref<Record<string, string>>({})

watch(selectedAttachments, async (items) => {
  const map: Record<string, string> = {}
  for (const item of items) {
    if (item.type === 'image' && item.fileId) {
      const url = `/api/files/${item.fileId}`
      try {
        map[item.fileId] = thumbnailBlobUrls.value[item.fileId] || await fetchAuthBlob(url)
      } catch { map[item.fileId] = url }
    }
  }
  thumbnailBlobUrls.value = map
}, { deep: true, immediate: true })

function removeAttachment(index: number) {
  selectedAttachments.value.splice(index, 1)
}

// ====== Tool Center (MCP) ======
const toolCenterOpen = ref(false)
const settingsStore = useSettingsStore()
const selectedMcpServerIds = ref<string[]>([])

/** 仅显示已启用的 MCP 服务器 */
const activeMcpServers = computed(() =>
  settingsStore.mcpServers.filter(s => s.active !== false)
)

const activeSelectedMcpCount = computed(() =>
  selectedMcpServerIds.value.filter(id =>
    settingsStore.mcpServers.some(s => s.serverId === id && s.active !== false)
  ).length
)

function toggleToolCenter() {
  toolCenterOpen.value = !toolCenterOpen.value
  if (toolCenterOpen.value) settingsStore.loadMcpServers()
}

function toggleMcpServer(id: string) {
  const idx = selectedMcpServerIds.value.indexOf(id)
  if (idx >= 0) {
    selectedMcpServerIds.value.splice(idx, 1)
  } else {
    selectedMcpServerIds.value.push(id)
  }
  emit('mcpServersChange', [...selectedMcpServerIds.value])
}

// 使用 prop 初始化 MCP 工具勾选状态（切换对话时自动更新）
watch(() => props.mcpServerIds, (val) => {
  selectedMcpServerIds.value = val ? [...val] : []
}, { immediate: true })

// ====== Knowledge Base Selector ======
const kbPanelOpen = ref(false)

function toggleKbPanel() {
  kbPanelOpen.value = !kbPanelOpen.value
}

const localSelectedKbNames = ref<string[]>([])

watch(() => props.selectedKbNames, (val) => {
  localSelectedKbNames.value = val ? [...val] : []
}, { immediate: true })

function toggleKb(kbName: string) {
  const idx = localSelectedKbNames.value.indexOf(kbName)
  if (idx >= 0) {
    localSelectedKbNames.value.splice(idx, 1)
  } else {
    localSelectedKbNames.value.push(kbName)
  }
  emit('knowledgeBasesChange', [...localSelectedKbNames.value])
}

// ====== Textarea ======
const textareaRef = ref<HTMLTextAreaElement | null>(null)

function autoGrow() {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 120) + 'px'
}

function onInput(e: Event) {
  const target = e.target as HTMLTextAreaElement
  emit('update:modelValue', target.value)
  nextTick(() => autoGrow())
}

function onKeydown(e: KeyboardEvent) {
  const mode = prefsStore.prefs.sendMode ?? 'system'
  const isMod = e.metaKey || e.ctrlKey
  const isMac = navigator.userAgent.includes('Mac')
  const modEnter = mode === 'mod-enter' || (mode === 'system' && isMac)

  if (modEnter) {
    if (e.key === 'Enter' && isMod && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  } else {
    if (e.key === 'Enter' && !isMod && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }
}

// ====== Quick Commands ======
const atMatch = computed(() => {
  const m = props.modelValue.match(/^@(\S+)\s+(.+)/)
  return m ? { agent: m[1], text: m[2].trim() } : null
})
const commandHint = computed(() => {
  if (atMatch.value) return { icon: '🤖', label: `@${atMatch.value.agent}：跳过路由，直接调用此智能体`, color: 'var(--clr-green)' }
  return null
})

// ====== Send ======
function handleSend() {
  const raw = props.modelValue.trim()
  const hasAttachments = selectedAttachments.value.length > 0
  if (!raw && !hasAttachments) return
  // 允许 streaming 时继续发送新消息（并发任务），不阻断

  // check pending uploads
  if (selectedAttachments.value.some(a => !a.fileId)) {
    message.info(t('waitForUpload'))
    return
  }

  let content = raw
  let targetAgent: string | undefined

  if (atMatch.value) {
    content = atMatch.value.text
    targetAgent = atMatch.value.agent
  }

  const images = selectedAttachments.value
    .filter(a => a.type === 'image' && a.fileId)
    .map(a => a.fileId)
  const files = selectedAttachments.value
    .filter(a => a.type === 'file' && a.fileId)
    .map(a => ({ fileId: a.fileId, fileName: a.fileName, mimeType: a.mimeType, fileSize: a.fileSize }))

  emit('send', {
    content,
    stream: streamEnabled.value,
    images: images.length ? images : undefined,
    attachments: files.length ? files : undefined,
    replyToId: props.quoteMsg?.messageId,
    modelProviderId: selectedProviderId.value || undefined,
    mcpServerIds: selectedMcpServerIds.value.length ? [...selectedMcpServerIds.value] : undefined,
    ...(targetAgent ? { targetAgent } as any : {}),
  })

  // cleanup
  selectedAttachments.value = []
  // 发送后重置输入框高度
  nextTick(() => autoGrow())
}

// ====== File Preview ======
const showFilePreview = ref(false)
const previewFileName = ref('')
const previewContent = ref('')
const previewLoading = ref(false)

async function previewFile(item: any) {
  if (!item.fileId) return
  previewFileName.value = item.fileName
  previewLoading.value = true
  showFilePreview.value = true
  try {
    const token = localStorage.getItem('token')
    const res = await fetch(`/api/files/${item.fileId}`, { headers: { Authorization: `Bearer ${token}` } })
    if (!res.ok) throw new Error('Failed to load')
    const text = await res.text()
    previewContent.value = text.length > 10000 ? text.slice(0, 10000) + '\n\n...（内容已截断）' : text
  } catch {
    previewContent.value = '(无法加载文件内容)'
  } finally {
    previewLoading.value = false
  }
}

// ====== AI Polish ======
const polishing = ref(false)
const polishedText = ref('')
const showPolishModal = ref(false)
const polishDone = ref(false)
const polishAbortController = ref<AbortController | null>(null)

async function handlePolish() {
  const text = props.modelValue.trim()
  if (!text || polishing.value) return
  if (!props.conversationId) return

  // 立即打开弹窗，显示原文
  showPolishModal.value = true
  polishedText.value = ''
  polishing.value = true
  polishDone.value = false

  const controller = new AbortController()
  polishAbortController.value = controller

  try {
    const token = localStorage.getItem('token')
    const response = await fetch('/api/polish/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ text }),
      signal: controller.signal,
    })

    if (!response.ok) throw new Error('请求失败')

    const reader = response.body?.getReader()
    if (!reader) throw new Error('无可读流')

    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        const trimmed = line.trim()
        if (trimmed.startsWith('data:')) {
          const data = trimmed.slice(5).trim()
          if (data === '[DONE]') continue
          try {
            const parsed = JSON.parse(data)
            if (parsed.type === 'response' && parsed.content) {
              polishedText.value += parsed.content
            }
          } catch { /* ignore */ }
        }
      }
    }
    polishDone.value = true
  } catch (e: any) {
    if (e.name === 'AbortError') {
      showPolishModal.value = false
      return
    }
    message.error(t('polishFailed'))
  } finally {
    polishing.value = false
  }
}

function confirmPolish() {
  if (polishedText.value) {
    emit('update:modelValue', polishedText.value)
    nextTick(() => autoGrow())
  }
  showPolishModal.value = false
  polishedText.value = ''
  polishDone.value = false
}

function cancelPolish() {
  polishAbortController.value?.abort()
  showPolishModal.value = false
  polishedText.value = ''
  polishDone.value = false
}
</script>

<template>
  <div class="chat__input">
    <div class="chat__input-wrap">

      <!-- Quote bar -->
      <div v-if="quoteMsg" class="quote-bar">
        <div class="quote-bar__content">
          <span class="quote-bar__label">{{ quoteMsg.role === 'user' ? t('userRole') : t('aiRole') }}</span>
          <span class="quote-bar__divider">‖</span>
          <span class="quote-bar__text">{{ quoteMsg.content }}</span>
        </div>
        <span class="quote-bar__hint">{{ t('quoting') }}</span>
        <button class="quote-bar__close" @click="emit('closeQuote')">&times;</button>
      </div>

      <!-- Attachment preview strip -->
      <div v-if="selectedAttachments.length" class="image-preview-strip">
        <div v-for="(item, idx) in selectedAttachments" :key="idx" class="image-preview-item" :class="{ 'is-file': item.type === 'file' }">
          <!-- Image preview -->
          <template v-if="item.type === 'image' && item.fileId">
            <img :src="thumbnailBlobUrls[item.fileId] || ''" class="image-preview-thumb" />
          </template>
          <!-- File icon (clickable for preview) -->
          <template v-else>
            <div class="file-preview-icon" :data-icon="getFileIcon(item.mimeType)" @click="previewFile(item)">
              <svg viewBox="0 0 24 28" width="24" height="28" fill="none" stroke="currentColor" stroke-width="1.2">
                <path d="M4 2h10l6 6v18a1 1 0 01-1 1H4a1 1 0 01-1-1V3a1 1 0 011-1z"/>
                <path d="M14 2v6h6"/>
              </svg>
              <span class="file-preview-ext">{{ item.fileName.split('.').pop() || '?' }}</span>
            </div>
          </template>
          <!-- File name -->
          <div v-if="item.type === 'file'" class="file-preview-name">{{ item.fileName }}</div>
          <button v-if="item.fileId" class="image-preview-remove" @click="removeAttachment(idx)">&times;</button>
          <div v-else class="image-preview-loading"><span class="loading-spinner"></span></div>
        </div>
      </div>

      <!-- Command hint bar -->
      <div v-if="commandHint" class="cmd-hint">
        <span class="cmd-hint__icon">{{ commandHint.icon }}</span>
        <span class="cmd-hint__text">{{ commandHint.label }}</span>
      </div>

      <!-- Textarea -->
      <textarea
        ref="textareaRef"
        class="chat__textarea"
        :value="modelValue"
        :placeholder="conversationId ? t('inputPlaceholder') : t('inputPlaceholderNewConv')"
        :disabled="!conversationId"
        rows="1"
        @input="onInput"
        @keydown="onKeydown"
      />

      <!-- Action buttons (inside input box, at the bottom) -->
      <div class="chat__input-actions">
        <!-- Model Selector (inline in the actions bar) -->
        <div class="input-model-selector">
          <button class="input-model-btn" :disabled="!chatProviders.length" @click="modelDropdownOpen = !modelDropdownOpen">
            <span class="input-model-name">{{ currentModelLabel }}</span>
            <svg class="input-model-chevron" :class="{ 'is-open': modelDropdownOpen }" viewBox="0 0 20 20" width="12" height="12">
              <path d="M6 8l4 4 4-4" fill="none" stroke="currentColor" stroke-width="1.5"/>
            </svg>
          </button>

          <!-- Model dropdown overlay (teleported backdrop) -->
          <Teleport to="body">
            <div v-if="modelDropdownOpen" class="input-model-overlay" @click="modelDropdownOpen = false"></div>
          </Teleport>
          <!-- Model dropdown (absolute positioned, anchored to button) -->
          <div v-if="modelDropdownOpen" class="input-model-dropdown">
            <div class="input-model-dropdown__header">{{ t('selectModel') }}</div>
            <div v-for="p in chatProviders" :key="p.providerId"
                 class="input-model-option"
                 :class="{ 'is-selected': p.providerId === selectedProviderId }"
                 @click="selectModel(p.providerId)">
              <div class="input-model-option__info">
                <span class="input-model-option__name">{{ p.name }}</span>
                <span class="input-model-option__model">{{ p.model || p.providerType }}</span>
                <div v-if="p.capabilities" class="input-model-option__caps">
                  <span v-for="c in p.capabilities.split(',').filter(Boolean)" :key="c" class="cap-badge">{{ capLabels[c] || c }}</span>
                </div>
              </div>
              <div class="input-model-option__meta">
                <span v-if="p.isDefault" class="badge badge-default">{{ t('defaultBadge') }}</span>
                <span v-if="p.providerId === selectedProviderId" class="check-mark">✓</span>
              </div>
            </div>
          </div>
        </div>

        <div class="chat__input-actions-spacer"></div>

        <!-- Attachment + -->
        <div class="input-btn-wrap">
          <button class="input-icon-btn" :disabled="streaming || !conversationId" @click="toggleAttachmentMenu" :title="t('uploadAttachment')">
            <svg viewBox="0 0 20 20" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round">
              <path d="M10 3v14M3 10h14"/>
            </svg>
          </button>
          <!-- Attachment menu -->
          <div v-if="attachmentMenuOpen" class="attachment-menu">
            <button class="attachment-menu__item" @click="triggerImageUpload">
              <svg viewBox="0 0 20 20" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.4"><rect x="2" y="3" width="16" height="14" rx="2"/><circle cx="7" cy="8" r="1.5"/><path d="M2 13l4-4 3 3 3-3 4 4"/></svg>
              <span>{{ t('image') }}</span>
            </button>
            <button class="attachment-menu__item" @click="triggerFileUpload">
              <svg viewBox="0 0 20 20" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.4"><path d="M11 1H5a1 1 0 00-1 1v16a1 1 0 001 1h10a1 1 0 001-1V5l-5-4z"/><path d="M11 1v4h4"/></svg>
              <span>{{ t('file') }}</span>
            </button>
          </div>
        </div>

        <!-- Tool Center -->
        <div class="input-btn-wrap">
          <button class="input-icon-btn" :disabled="!conversationId" @click="toggleToolCenter" :title="t('toolCenter')">
            <svg viewBox="0 0 20 20" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.4">
              <path d="M14.7 1a1 1 0 00-.7.3L10 5.2 8.3 3.5a1 1 0 00-1.4 0L3.5 7a1 1 0 000 1.4l3.7 3.7-5.2 5.3a1 1 0 000 1.4l.3.3a1 1 0 001.4 0l5.3-5.2 3.7 3.7a1 1 0 001.4 0l3.5-3.5a1 1 0 000-1.4L14.8 10l4-4a1 1 0 00.3-.7V2a1 1 0 00-1-1h-3.4z"/>
            </svg>
            <span v-if="activeSelectedMcpCount" class="input-btn-badge">{{ activeSelectedMcpCount }}</span>
          </button>
          <!-- Tool center overlay (teleported backdrop) -->
          <Teleport to="body">
            <div v-if="toolCenterOpen" class="tool-center-overlay" @click="toolCenterOpen = false"></div>
          </Teleport>
          <!-- Tool center panel (absolute positioned, anchored to button) -->
          <div v-if="toolCenterOpen" class="tool-center-panel">
            <div class="tool-center-panel__header">
              <span>{{ t('toolCenter') }}</span>
              <button class="tool-center-panel__close" @click="toolCenterOpen = false">&times;</button>
            </div>
            <div v-if="!activeMcpServers.length" class="tool-center-panel__empty">
              {{ t('noMcpConfigured') }}
            </div>
            <div v-else class="tool-center-panel__list">
              <div v-for="s in activeMcpServers" :key="s.serverId"
                   class="tool-center-item"
                   :class="{ 'is-active': s.active, 'is-selected': selectedMcpServerIds.includes(s.serverId) }"
                   @click="toggleMcpServer(s.serverId)">
                <div class="tool-center-item__check">
                  <div class="checkbox" :class="{ 'is-checked': selectedMcpServerIds.includes(s.serverId) }">
                    <svg v-if="selectedMcpServerIds.includes(s.serverId)" viewBox="0 0 20 20" width="12" height="12" fill="none" stroke="#fff" stroke-width="3"><path d="M4 10l4 4 8-8"/></svg>
                  </div>
                </div>
                <div class="tool-center-item__info">
                  <span class="tool-center-item__name">{{ s.name }}</span>
                  <span class="tool-center-item__url">{{ s.serverUrl }}</span>
                </div>
                <span class="tool-center-item__status" :class="s.active ? 'status-online' : 'status-offline'">
                  {{ s.active ? t('online') : t('offline') }}
                </span>
              </div>
            </div>
          </div>
        </div>

        <!-- Knowledge Base Selector -->
        <div class="input-btn-wrap">
          <button class="input-icon-btn" :disabled="!conversationId" @click="toggleKbPanel" :title="t('knowledgeBase')">
            <svg viewBox="0 0 20 20" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
              <path d="M4 4h12v14H4z"/>
              <path d="M8 4V2h4v2"/>
              <path d="M8 10l2 2 4-4"/>
            </svg>
            <span v-if="localSelectedKbNames.length" class="input-btn-badge">{{ localSelectedKbNames.length }}</span>
          </button>
          <Teleport to="body">
            <div v-if="kbPanelOpen" class="tool-center-overlay" @click="kbPanelOpen = false"></div>
          </Teleport>
          <div v-if="kbPanelOpen" class="kb-panel">
            <div class="tool-center-panel__header">
              <span>{{ t('knowledgeBase') }}</span>
              <button class="tool-center-panel__close" @click="kbPanelOpen = false">&times;</button>
            </div>
            <div v-if="!knowledgeBases?.length" class="tool-center-panel__empty">
              {{ t('noKnowledgeBase') }}
            </div>
            <div v-else class="tool-center-panel__list">
              <div v-for="kb in knowledgeBases" :key="kb.kbName"
                   class="tool-center-item"
                   :class="{ 'is-selected': localSelectedKbNames.includes(kb.kbName) }"
                   @click="toggleKb(kb.kbName)">
                <div class="tool-center-item__check">
                  <div class="checkbox" :class="{ 'is-checked': localSelectedKbNames.includes(kb.kbName) }">
                    <svg v-if="localSelectedKbNames.includes(kb.kbName)" viewBox="0 0 20 20" width="12" height="12" fill="none" stroke="#fff" stroke-width="3"><path d="M4 10l4 4 8-8"/></svg>
                  </div>
                </div>
                <div class="tool-center-item__info">
                  <span class="tool-center-item__name">{{ kb.kbName }}</span>
                  <span v-if="kb.description" class="tool-center-item__url">{{ kb.description }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- AI Polish -->
        <button class="input-icon-btn" :disabled="polishing || !conversationId || !modelValue.trim()" @click="handlePolish" :title="t('aiPolish')">
          <svg viewBox="0 0 20 20" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
            <path d="M10 1l1.5 3.5L15 6l-3.5 1.5L10 11l-1.5-3.5L5 6l3.5-1.5L10 1z"/>
            <path d="M6 13l-2 4 4-2 2 4 2-4 4 2-2-4"/>
          </svg>
          <span v-if="polishing" class="input-btn-badge polishing-badge">⋯</span>
        </button>

        <!-- Stream Toggle -->
        <button class="input-icon-btn" :disabled="!conversationId" :title="streamEnabled ? t('streamOn') : t('streamOff')" @click="streamEnabled = !streamEnabled">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" :opacity="streamEnabled ? 1 : 0.3">
            <path d="M5 12h14M12 5l7 7-7 7"/>
          </svg>
        </button>

        <!-- Cancel (streaming 时显示) -->
        <button v-if="streaming" class="btn btn-ghost chat__cancel"
                @click="emit('cancel')">
          {{ t('cancel') }}
        </button>

        <!-- Send -->
        <button class="btn btn-primary chat__send"
                :disabled="!conversationId || !modelValue.trim()"
                @click="handleSend()">
          {{ t('send') }}
        </button>
      </div>
    </div>

    <!-- AI Polish Modal -->
    <Teleport to="body">
      <div v-if="showPolishModal" class="polish-overlay" @click.self="cancelPolish">
        <div class="polish-modal">
          <div class="polish-modal__header">
            <div class="polish-modal__header-left">
              <svg viewBox="0 0 20 20" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
                <path d="M10 1l1.5 3.5L15 6l-3.5 1.5L10 11l-1.5-3.5L5 6l3.5-1.5L10 1z"/>
                <path d="M6 13l-2 4 4-2 2 4 2-4 4 2-2-4"/>
              </svg>
              <span>{{ t('polishTitle') }}</span>
            </div>
            <button class="polish-modal__close" @click="cancelPolish">&times;</button>
          </div>
          <div class="polish-modal__body">
            <div class="polish-modal__section">
              <div class="polish-modal__label">{{ t('originalText') }}</div>
              <div class="polish-modal__source-text">{{ modelValue }}</div>
            </div>
            <div class="polish-modal__section">
              <div class="polish-modal__label">{{ t('polishedResult') }}</div>
              <div class="polish-modal__result-text">
                <template v-if="polishedText">{{ polishedText }}</template>
                <template v-else-if="polishing"><span class="polish-placeholder">{{ t('polishing') }}</span></template>
                <span v-if="polishing && !polishDone" class="polish-cursor">|</span>
              </div>
            </div>
          </div>
          <div class="polish-modal__footer">
            <button class="polish-modal__btn polish-modal__btn--cancel" @click="cancelPolish">
              {{ polishing && !polishDone ? t('cancelGenerate') : t('cancel') }}
            </button>
            <button class="polish-modal__btn polish-modal__btn--confirm"
                    :disabled="!polishDone || !polishedText"
                    @click="confirmPolish">
              {{ t('replace') }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- File Preview Modal -->
    <Teleport to="body">
      <div v-if="showFilePreview" class="modal-overlay" @click.self="showFilePreview = false">
        <div class="file-preview-modal">
          <div class="file-preview-modal__header">
            <span>{{ previewFileName }}</span>
            <button class="file-preview-modal__close" @click="showFilePreview = false">&times;</button>
          </div>
          <div class="file-preview-modal__body">
            <pre v-if="!previewLoading">{{ previewContent }}</pre>
            <div v-else class="file-preview-modal__loading">加载中...</div>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
/* ── Input container ── */
.chat__input {
  padding: 12px 20px 16px;
  border-top: 1px solid var(--clr-hairline);
}
.chat__input-wrap {
  position: relative;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--clr-separator);
  border-radius: var(--rad-lg);
  background: var(--clr-bg);
  transition: border-color var(--dur-fast) var(--ease-out);
}
.chat__input-wrap:focus-within {
  border-color: var(--clr-accent);
}

/* ── Quote bar ── */
.quote-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  margin: 6px 8px 2px;
  border-radius: var(--rad-md);
  background: var(--clr-bg-secondary);
  border-left: 3px solid var(--clr-accent);
  gap: 8px;
}
.quote-bar__content {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  flex: 1;
}
.quote-bar__label {
  font-size: var(--fs-footnote);
  font-weight: var(--fw-medium);
  color: var(--clr-accent);
  white-space: nowrap;
}
.quote-bar__divider {
  color: var(--clr-quaternary);
  font-size: var(--fs-footnote);
}
.quote-bar__text {
  font-size: var(--fs-footnote);
  color: var(--clr-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.quote-bar__hint {
  font-size: var(--fs-caption);
  color: var(--clr-tertiary);
  white-space: nowrap;
}
.quote-bar__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--clr-tertiary);
  font-size: 16px;
  cursor: pointer;
  flex-shrink: 0;
  font-family: inherit;
  line-height: 1;
  transition: background var(--dur-fast) var(--ease-out), color var(--dur-fast) var(--ease-out);
}
.quote-bar__close:hover {
  background: var(--clr-fill-hover);
  color: var(--clr-label);
}

/* ── Image preview strip ── */
/* ── Command hint bar ── */
.cmd-hint {
  display: flex; align-items: center; gap: 6px;
  padding: 6px 10px; margin-bottom: 6px;
  border-radius: var(--rad-sm); background: var(--clr-fill-hover);
  font-size: var(--fs-caption); color: var(--clr-secondary);
}
.cmd-hint__icon { font-size: 14px; flex-shrink: 0; }
.cmd-hint__text { flex: 1; }

/* ── Attachment preview ── */
.image-preview-strip {
  display: flex;
  gap: 8px;
  padding: 8px 8px 0;
  overflow-x: auto;
  flex-wrap: nowrap;
}
.image-preview-item {
  position: relative;
  flex-shrink: 0;
  width: 64px;
  height: 64px;
  border-radius: var(--rad-md);
  overflow: hidden;
  border: 1px solid var(--clr-separator);
}
.image-preview-thumb {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.image-preview-thumb.is-uploading {
  opacity: 0.5;
}
.image-preview-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--fs-caption);
  color: var(--clr-tertiary);
  background: var(--clr-bg-secondary);
}
.image-preview-remove {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 18px;
  height: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 50%;
  background: rgba(0,0,0,0.5);
  color: #fff;
  font-size: 12px;
  cursor: pointer;
  font-family: inherit;
  line-height: 1;
}
.image-preview-loading {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0,0,0,0.3);
}

/* ── Model Selector (inside container) ── */
.input-model-selector {
  position: relative;
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 6px 8px 0;
}
.input-model-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border: none;
  border-radius: var(--rad-md);
  background: transparent;
  color: var(--clr-secondary);
  font-size: var(--fs-footnote);
  cursor: pointer;
  white-space: nowrap;
  max-width: 140px;
  transition: background var(--dur-fast) var(--ease-out), color var(--dur-fast) var(--ease-out);
  font-family: inherit;
}
.input-model-btn:hover {
  background: var(--clr-fill);
  color: var(--clr-label);
}
.input-model-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.input-model-name {
  overflow: hidden;
  text-overflow: ellipsis;
}
.input-model-chevron {
  flex-shrink: 0;
  transition: transform 0.2s;
  color: var(--clr-tertiary);
}
.input-model-chevron.is-open {
  transform: rotate(180deg);
}

/* ── Model dropdown ── */
.input-model-overlay {
  position: fixed;
  inset: 0;
  z-index: var(--z-overlay);
}
.input-model-dropdown {
  position: absolute;
  bottom: 100%;
  left: 0;
  margin-bottom: 6px;
  width: 260px;
  max-height: 300px;
  overflow-y: auto;
  background: var(--clr-bg);
  border: 1px solid var(--clr-separator);
  border-radius: var(--rad-lg);
  box-shadow: var(--shd-modal);
  z-index: calc(var(--z-overlay) + 1);
}
.input-model-dropdown__header {
  padding: 10px 14px;
  font-size: var(--fs-footnote);
  font-weight: var(--fw-semibold);
  color: var(--clr-secondary);
  border-bottom: 1px solid var(--clr-hairline);
}
.input-model-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  cursor: pointer;
  transition: background var(--dur-fast);
}
.input-model-option:hover {
  background: var(--clr-fill);
}
.input-model-option.is-selected {
  background: var(--clr-accent-soft);
}
.input-model-option__info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.input-model-option__name {
  font-size: var(--fs-callout);
  font-weight: var(--fw-medium);
  color: var(--clr-label);
}
.input-model-option__model {
  font-size: var(--fs-footnote);
  color: var(--clr-tertiary);
}
.input-model-option__caps {
  display: flex;
  flex-wrap: wrap;
  gap: 3px;
  margin-top: 3px;
}
.cap-badge {
  display: inline-block;
  font-size: 9px;
  padding: 1px 5px;
  border-radius: var(--rad-full);
  background: var(--clr-fill);
  color: var(--clr-tertiary);
  line-height: 1.4;
  white-space: nowrap;
}
.input-model-option__meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
.badge-default {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: var(--rad-full);
  background: var(--clr-accent-soft);
  color: var(--clr-accent);
  font-weight: var(--fw-medium);
}
.check-mark {
  color: var(--clr-accent);
  font-weight: var(--fw-bold);
  font-size: 14px;
}

/* ── Textarea ── */
.chat__textarea {
  width: 100%;
  border: none !important;
  border-radius: 0 !important;
  padding: 6px 12px !important;
  font-size: var(--fs-body) !important;
  line-height: 1.5 !important;
  font-family: inherit;
  background: transparent;
  color: var(--clr-label);
  resize: none;
  outline: none;
  box-shadow: none !important;
  box-sizing: border-box;
  min-height: 24px;
}
.chat__textarea:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.chat__textarea::placeholder {
  color: var(--clr-tertiary);
}

/* ── Action buttons ── */
.chat__input-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 2px 8px 8px;
}
.chat__input-actions-spacer {
  flex: 1;
}

.input-btn-wrap {
  position: relative;
}

.input-icon-btn {
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
  position: relative;
  transition: border-color var(--dur-fast) var(--ease-out), color var(--dur-fast) var(--ease-out), background var(--dur-fast) var(--ease-out);
  font-family: inherit;
}
.input-icon-btn:hover:not(:disabled) {
  color: var(--clr-accent);
  background: var(--clr-fill);
}
.input-icon-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
.input-btn-badge {
  position: absolute;
  top: -2px;
  right: -2px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: var(--rad-full);
  background: var(--clr-accent);
  color: #fff;
  font-size: 10px;
  font-weight: var(--fw-bold);
  line-height: 16px;
  text-align: center;
}

/* ── Attachment menu ── */
.attachment-menu {
  position: absolute;
  bottom: calc(100% + 6px);
  left: 0;
  width: 140px;
  background: var(--clr-bg);
  border: 1px solid var(--clr-separator);
  border-radius: var(--rad-lg);
  box-shadow: var(--shd-card);
  z-index: var(--z-overlay);
  overflow: hidden;
}
.attachment-menu__item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 10px 14px;
  border: none;
  background: transparent;
  color: var(--clr-label);
  font-size: var(--fs-callout);
  cursor: pointer;
  font-family: inherit;
  transition: background var(--dur-fast);
}
.attachment-menu__item:hover {
  background: var(--clr-fill);
}
.attachment-menu__item svg {
  flex-shrink: 0;
}

/* ── Tool Center Panel ── */
.tool-center-overlay {
  position: fixed;
  inset: 0;
  z-index: var(--z-overlay);
}
.tool-center-panel {
  position: absolute;
  bottom: 100%;
  right: 0;
  margin-bottom: 6px;
  width: 320px;
  max-height: 320px;
  background: var(--clr-bg);
  border: 1px solid var(--clr-separator);
  border-radius: var(--rad-lg);
  box-shadow: var(--shd-modal);
  z-index: calc(var(--z-overlay) + 1);
  display: flex;
  flex-direction: column;
}
.tool-center-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  font-size: var(--fs-callout);
  font-weight: var(--fw-semibold);
  border-bottom: 1px solid var(--clr-hairline);
}
.tool-center-panel__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: none;
  background: transparent;
  color: var(--clr-tertiary);
  font-size: 18px;
  cursor: pointer;
  font-family: inherit;
}
.tool-center-panel__empty {
  padding: 24px;
  text-align: center;
  color: var(--clr-tertiary);
  font-size: var(--fs-callout);
}
.tool-center-panel__list {
  flex: 1;
  overflow-y: auto;
  padding: 4px 0;
}
.tool-center-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  cursor: pointer;
  transition: background var(--dur-fast);
}
.tool-center-item:hover {
  background: var(--clr-fill);
}
.tool-center-item.is-selected {
  background: var(--clr-accent-soft);
}
.tool-center-item__check .checkbox {
  width: 18px;
  height: 18px;
  border: 2px solid var(--clr-separator);
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all var(--dur-fast);
}
.tool-center-item__check .checkbox.is-checked {
  background: var(--clr-accent);
  border-color: var(--clr-accent);
}
.tool-center-item__info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.tool-center-item__name {
  font-size: var(--fs-callout);
  font-weight: var(--fw-medium);
  color: var(--clr-label);
}
.tool-center-item__url {
  font-size: var(--fs-footnote);
  color: var(--clr-tertiary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tool-center-item__status {
  font-size: var(--fs-caption);
  white-space: nowrap;
}
.status-online {
  color: var(--clr-accent);
}
.status-offline {
  color: var(--clr-tertiary);
}

/* ── Knowledge Base Panel ── */
.kb-panel {
  position: absolute;
  bottom: 100%;
  right: 0;
  margin-bottom: 6px;
  width: 280px;
  max-height: 280px;
  background: var(--clr-bg);
  border: 1px solid var(--clr-separator);
  border-radius: var(--rad-lg);
  box-shadow: var(--shd-modal);
  z-index: calc(var(--z-overlay) + 1);
  display: flex;
  flex-direction: column;
}

/* ── Send button ── */
.chat__send {
  padding: 4px 14px;
  border-radius: var(--rad-md);
  min-width: 44px;
  font-size: var(--fs-footnote);
}
.chat__send.is-canceling {
  background: var(--clr-red);
  border-color: var(--clr-red);
  color: #fff;
}
.chat__send.is-canceling:hover {
  opacity: 0.85;
}


/* ── Polish Modal ── */
.polish-overlay {
  position: fixed;
  inset: 0;
  z-index: var(--z-modal);
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  backdrop-filter: blur(2px);
}
.polish-modal {
  width: 560px;
  max-width: 90vw;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  background: var(--clr-bg);
  border-radius: var(--rad-lg);
  box-shadow: var(--shd-modal);
  animation: modalIn 0.2s ease-out;
}
@keyframes modalIn {
  from { opacity: 0; transform: scale(0.96) translateY(8px); }
  to { opacity: 1; transform: scale(1) translateY(0); }
}
.polish-modal__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--clr-hairline);
}
.polish-modal__header-left {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--fs-body);
  font-weight: var(--fw-semibold);
  color: var(--clr-label);
}
.polish-modal__header-left svg {
  color: var(--clr-accent);
}
.polish-modal__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: var(--rad-md);
  background: transparent;
  color: var(--clr-tertiary);
  font-size: 20px;
  cursor: pointer;
  font-family: inherit;
  transition: background var(--dur-fast), color var(--dur-fast);
}
.polish-modal__close:hover {
  background: var(--clr-fill);
  color: var(--clr-label);
}
.polish-modal__body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.polish-modal__section {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.polish-modal__label {
  font-size: var(--fs-footnote);
  font-weight: var(--fw-medium);
  color: var(--clr-secondary);
}
.polish-modal__source-text {
  font-size: var(--fs-callout);
  line-height: 1.6;
  color: var(--clr-secondary);
  white-space: pre-wrap;
  word-break: break-word;
  padding: 10px 12px;
  background: var(--clr-bg-secondary);
  border-radius: var(--rad-md);
  border: 1px solid var(--clr-separator-light);
  max-height: 120px;
  overflow-y: auto;
}
.polish-modal__result-text {
  font-size: var(--fs-callout);
  line-height: 1.6;
  color: var(--clr-label);
  white-space: pre-wrap;
  word-break: break-word;
  padding: 10px 12px;
  background: var(--clr-bg);
  border-radius: var(--rad-md);
  border: 1px solid var(--clr-separator);
  min-height: 60px;
}
.polish-placeholder {
  color: var(--clr-tertiary);
}
.polish-cursor {
  animation: blink 0.8s step-end infinite;
  color: var(--clr-accent);
  font-weight: var(--fw-semibold);
}
@keyframes blink {
  50% { opacity: 0; }
}
.polish-modal__footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 20px;
  border-top: 1px solid var(--clr-hairline);
}
.polish-modal__btn {
  padding: 6px 20px;
  border-radius: var(--rad-md);
  font-size: var(--fs-callout);
  cursor: pointer;
  border: 1px solid transparent;
  font-family: inherit;
  transition: all var(--dur-fast);
}
.polish-modal__btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.polish-modal__btn--cancel {
  background: transparent;
  border-color: var(--clr-separator);
  color: var(--clr-secondary);
}
.polish-modal__btn--cancel:hover:not(:disabled) {
  background: var(--clr-fill);
}
.polish-modal__btn--confirm {
  background: var(--clr-accent);
  color: #fff;
  border-color: var(--clr-accent);
}
.polish-modal__btn--confirm:hover:not(:disabled) {
  opacity: 0.85;
}

/* ── Shared utilities ── */
.loading-spinner {
  width: 18px;
  height: 18px;
  border: 2px solid var(--clr-separator);
  border-top-color: var(--clr-accent);
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}
.loading-sm {
  width: 14px;
  height: 14px;
  border-width: 1.5px;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ── File preview icon in input strip ── */
.file-preview-icon {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  background: var(--clr-bg-secondary);
  color: var(--clr-tertiary);
}
.file-preview-ext {
  position: absolute;
  bottom: 6px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 8px;
  font-weight: var(--fw-bold);
  color: var(--clr-secondary);
  text-transform: uppercase;
  letter-spacing: 0.02em;
  line-height: 1;
}
.file-preview-name {
  position: absolute;
  bottom: 2px;
  left: 0;
  right: 0;
  font-size: 7px;
  color: var(--clr-tertiary);
  text-align: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 0 2px;
  line-height: 1.2;
}
.image-preview-item.is-file {
  border-style: dashed;
}

/* File Preview Modal */
.file-preview-modal {
  background: var(--clr-bg-primary, var(--clr-bg, #fff));
  border-radius: var(--rad-lg);
  max-width: 680px;
  width: 90vw;
  max-height: 70vh;
  display: flex;
  flex-direction: column;
}
.file-preview-modal__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--clr-border, var(--clr-hairline, #e8e8e8));
  font-weight: 600;
}
.file-preview-modal__close {
  background: none;
  border: none;
  font-size: 22px;
  cursor: pointer;
  color: var(--clr-secondary);
}
.file-preview-modal__body {
  padding: 16px;
  overflow-y: auto;
  flex: 1;
}
.file-preview-modal__body pre {
  white-space: pre-wrap;
  word-break: break-all;
  font-size: var(--fs-callout);
  color: var(--clr-primary, var(--clr-label, #333));
  margin: 0;
  line-height: 1.5;
}
.file-preview-modal__loading {
  color: var(--clr-secondary);
  text-align: center;
  padding: 32px;
}
.file-preview-icon { cursor: pointer; }
</style>
