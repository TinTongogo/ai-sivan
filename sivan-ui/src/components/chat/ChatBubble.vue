<script setup lang="ts">
import { ref, computed, watch, onUnmounted } from 'vue'
import { renderMarkdown } from '../../utils/markdown'
import { useI18n } from '../../utils/i18n'
import { fetchAuthBlob, downloadAuthFile } from '../../utils/auth-fetch'
import { fetchMessageForest, type ForestTreeResponse, type ForestTreeResponseNode } from '../../api/goals'
import TreeNodeRender from '../orchestration/TreeNodeRender.vue'
const { t } = useI18n()

const props = defineProps<{
  message: {
    role: 'user' | 'assistant'; content: string; thinking?: string; classifyText?: string; thinkingDurationMs?: number; thinkingTokens?: number
    status?: string; replyTo?: { role: string; content: string }
    messageId?: string
    chain?: string
    images?: string[]; attachments?: { fileId: string; fileName: string; mimeType: string; fileSize: number; url?: string }[]
    generationGroup?: string; generationIndex?: number; generationTotal?: number
    sections?: any[]
  }
  meta?: { duration?: string; tokens?: number; model?: string; thinkingTokens?: number }
  streaming?: boolean
  hideExtra?: 'none' | 'thinking-toggle' | 'user-footer' | 'both'
  thinkingOpen?: boolean
}>()

const emit = defineEmits<{
  copy: [text: string]
  quote: [text: string]
  saveToKb: [text: string]
  rate: []
  regenerate: []
  switchGeneration: []
  delete: []
  'update:thinkingOpen': [value: boolean]
  showPipeline: []
}>()

const _internalThinkingOpen = ref(false)
const thinkingOpen = computed({
  get: () => props.thinkingOpen ?? _internalThinkingOpen.value,
  set: (val) => {
    _internalThinkingOpen.value = val
    emit('update:thinkingOpen', val)
  },
})

const previewImageUrl = ref('')

// 图片 blob URL 缓存：原始 URL → blob URL（<img> 无法发 Authorization header）
const imageBlobUrls = ref<Record<string, string>>({})

watch(() => props.message.images, async (images) => {
  if (!images?.length) { imageBlobUrls.value = {}; return }
  const map: Record<string, string> = {}
  for (const img of images) {
    try {
      map[img] = imageBlobUrls.value[img] || await fetchAuthBlob(img)
    } catch { map[img] = img }
  }
  imageBlobUrls.value = map
}, { immediate: true })

function previewImage(url: string) {
  previewImageUrl.value = url
}

function closePreview() {
  previewImageUrl.value = ''
}

async function handleFileClick(f: { fileId: string; fileName: string; url?: string }) {
  const fileUrl = f.url || `/api/files/${f.fileId}`
  try {
    await downloadAuthFile(fileUrl, f.fileName)
  } catch {
    window.open(fileUrl, '_blank')
  }
}
interface LocalForestTreeNode {
  name: string
  status: string
  mode?: string
  agent?: string
  reasoning?: string
  output?: string
  isLeaf?: boolean
  routeTier?: number
  routeConfidence?: number
  toolCalls?: { name: string; count: number; status: string }[]
  children?: LocalForestTreeNode[]
}

// 执行树展示（替代扁平内容）
const forestTree = ref<LocalForestTreeNode | null>(null)
const treeLoading = ref(false)

watch(() => props.message.messageId, async (msgId) => {
  forestTree.value = null
  if (!msgId || props.message.role !== 'assistant') return
  // 等待流式完成后才加载执行树
  if (props.streaming) return
  treeLoading.value = true
  try {
    const convId = props.message.chain
    if (!convId) return
    const resp: ForestTreeResponse | null = await fetchMessageForest(convId, msgId)
    if (resp?.root) {
      forestTree.value = toForestTreeNode(resp.root)
    }
  } catch {
    // 无执行树是正常的（简单对话）
  } finally {
    treeLoading.value = false
  }
})

watch(() => props.streaming, (val) => {
  if (!val && props.message.messageId && props.message.role === 'assistant') {
    // 流式完成后加载执行树
    loadTreeDelayed()
  }
})

function loadTreeDelayed() {
  const msgId = props.message.messageId
  const convId = props.message.chain
  if (!msgId || !convId) return
  treeLoading.value = true
  fetchMessageForest(convId, msgId)
    .then(resp => {
      if (resp?.root) forestTree.value = toForestTreeNode(resp.root)
    })
    .catch(() => {})
    .finally(() => { treeLoading.value = false })
}

function toForestTreeNode(node: ForestTreeResponseNode): LocalForestTreeNode {
  return {
    name: node.output || node.name,
    status: node.status,
    mode: node.mode,
    agent: node.agent,
    reasoning: node.reasoning,
    output: node.output,
    isLeaf: node.leaf,
    routeTier: (node as any).routeTier,
    routeConfidence: node.routeConfidence,
    toolCalls: (node as any).toolCalls?.map((tc: any) => ({ name: tc.name, count: tc.count, status: tc.status })),
    children: node.children?.map(toForestTreeNode),
  }
}


// 首次内容到达时自动折叠思考区（仅一次），之后由用户控制
let initialContentCollapseDone = false

// 思考内容打字机效果
const displayThinking = ref(props.message.thinking || '')
const pendingThinkingChars = ref('')
const thinkingDisplayedUpTo = ref((props.message.thinking || '').length)

// 打字机效果 — 120fps setTimeout 驱动，每帧批量渲染
const displayContent = ref(props.message.content)
const pendingChars = ref('')
const displayedUpTo = ref(props.message.content.length)
let timerId: ReturnType<typeof setTimeout> | null = null
let streamEnded = false

const TICK_MS = 8 // 120fps
const TEXT_SPEED_CPS = 40 // 自然阅读速度（字符/秒），不受帧率影响

/** 将余下的排队字符立即刷到 display */
function flushDisplay() {
  displayContent.value = props.message.content
  displayedUpTo.value = props.message.content.length
  pendingChars.value = ''
  displayThinking.value = props.message.thinking || ''
  thinkingDisplayedUpTo.value = (props.message.thinking || '').length
  pendingThinkingChars.value = ''
  stopTyping()
}

watch(() => props.message.content, (newContent) => {
  if (newContent && (props.message.thinking || displayThinking.value) && !initialContentCollapseDone) {
    initialContentCollapseDone = true
    thinkingOpen.value = false
    displayThinking.value = props.message.thinking || ''
    pendingThinkingChars.value = ''
    thinkingDisplayedUpTo.value = (props.message.thinking || '').length
  }
  if (newContent.length > displayedUpTo.value) {
    pendingChars.value += newContent.slice(displayedUpTo.value)
    displayedUpTo.value = newContent.length
    if (!props.streaming) streamEnded = true
    startTyping()
  } else if (newContent !== displayContent.value) {
    flushDisplay()
  }
})

watch(() => props.message.thinking, (newThinking) => {
  if (newThinking && !props.message.content) thinkingOpen.value = true
  if (newThinking && newThinking.length > thinkingDisplayedUpTo.value) {
    pendingThinkingChars.value += newThinking.slice(thinkingDisplayedUpTo.value)
    thinkingDisplayedUpTo.value = newThinking.length
    startTyping()
  } else if (newThinking && newThinking !== displayThinking.value) {
    flushDisplay()
  }
})

watch(() => props.streaming, (isStreaming) => {
  if (!isStreaming) streamEnded = true
})

/** 120fps 批量渲染：根据 TEXT_SPEED_CPS 和 TICK_MS 计算每帧字符数 */
function startTyping() {
  if (timerId !== null || (pendingChars.value.length === 0 && pendingThinkingChars.value.length === 0)) return
  // 每帧输出的基础字符数 = 40cps / 120fps ≈ 0.33 → 至少 1
  const baseCharsPerTick = Math.max(1, Math.round(TEXT_SPEED_CPS * TICK_MS / 1000))
  function tick() {
    // 思考内容一次性输出
    if (pendingThinkingChars.value.length > 0) {
      displayThinking.value += pendingThinkingChars.value
      pendingThinkingChars.value = ''
    }
    if (pendingChars.value.length > 0) {
      const q = pendingChars.value.length
      let n: number
      if (streamEnded) {
        // 流结束加速收尾，2 tick 内消化完
        n = Math.ceil(q / 2)
      } else if (q > 200) {
        n = baseCharsPerTick * 12 // 队列积压严重 → 快速追赶
      } else if (q > 80) {
        n = baseCharsPerTick * 6
      } else if (q > 30) {
        n = baseCharsPerTick * 3
      } else {
        n = baseCharsPerTick // 自然节奏
      }
      displayContent.value += pendingChars.value.slice(0, n)
      pendingChars.value = pendingChars.value.slice(n)
    }
    if (pendingChars.value.length === 0 && pendingThinkingChars.value.length === 0) {
      stopTyping()
      if (streamEnded) flushDisplay()
      return
    }
    timerId = setTimeout(tick, TICK_MS)
  }
  timerId = setTimeout(tick, TICK_MS)
}

function stopTyping() {
  if (timerId !== null) {
    clearTimeout(timerId)
    timerId = null
  }
}

onUnmounted(() => {
  stopTyping()
  // 清理 blob URL，防止内存泄漏
  for (const blobUrl of Object.values(imageBlobUrls.value)) {
    if (blobUrl.startsWith('blob:')) URL.revokeObjectURL(blobUrl)
  }
})

function handleCodeCopy(e: MouseEvent) {
  const target = e.target as HTMLElement
  const btn = target.closest('.code-copy-btn') as HTMLElement | null
  if (!btn) return
  const wrapper = btn.closest('.code-block-wrapper')
  if (!wrapper) return
  const code = wrapper.querySelector('code')
  if (!code) return
  const text = code.textContent || ''
  navigator.clipboard.writeText(text).then(() => {
    const copyIcon = btn.querySelector('.copy-icon') as HTMLElement | null
    const checkIcon = btn.querySelector('.check-icon') as HTMLElement | null
    if (copyIcon) copyIcon.style.display = 'none'
    if (checkIcon) checkIcon.style.display = ''
    setTimeout(() => {
      if (copyIcon) copyIcon.style.display = ''
      if (checkIcon) checkIcon.style.display = 'none'
    }, 2000)
  })
}

const renderedContent = computed(() => {
  let html = renderMarkdown(displayContent.value)
  // 20-编排产出展示方案: (§Agent) → 可点击 badge
  html = html.replace(/\(§([^)]+)\)/g, (_, agent) =>
    `<span class="agent-badge" onclick="event.stopPropagation()" title="${agent} 的产出">🔍 ${agent}</span>`
  )
  return html
})
const renderedThinking = computed(() => renderMarkdown(displayThinking.value))

// 检测音频内容：格式为 [audio:data:audio/mp3;base64,xxxxx]
const audioSrc = computed(() => {
  const c = displayContent.value
  if (!c.startsWith('[audio:')) return null
  const match = c.match(/^\[audio:(data:.+)\]$/)
  return match ? match[1] : null
})
const renderedClassify = computed(() => props.message.classifyText ? renderMarkdown(props.message.classifyText) : '')

const thinkingDurationLabel = computed(() => {
  let label = ''
  if (props.message.thinkingDurationMs) {
    label += ` ${(props.message.thinkingDurationMs / 1000).toFixed(1)}秒`
  }
  if (props.message.thinkingTokens) {
    label += ` · ${props.message.thinkingTokens} tokens`
  }
  return label
})

function getFileType(mimeType: string): string {
  if (mimeType.includes('pdf')) return 'pdf'
  if (mimeType.includes('word') || mimeType.includes('document')) return 'doc'
  if (mimeType.includes('spreadsheet') || mimeType.includes('excel')) return 'xls'
  if (mimeType.includes('text/')) return 'text'
  if (mimeType.includes('zip') || mimeType.includes('rar') || mimeType.includes('tar')) return 'archive'
  if (mimeType.includes('json') || mimeType.includes('xml') || mimeType.includes('code')) return 'code'
  return 'file'
}

function formatFileSize(bytes: number): string {
  if (!bytes || bytes === 0) return ''
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let size = bytes
  while (size >= 1024 && i < units.length - 1) { size /= 1024; i++ }
  return size.toFixed(i === 0 ? 0 : 1) + ' ' + units[i]
}
</script>

<template>
  <div class="bubble-row" :class="`bubble-row--${message.role}`">
    <!-- 头像 -->
    <div class="bubble-avatar" :title="message.role === 'user' ? t('userRole') : t('aiRole')">
      <div class="avatar-circle">
        {{ message.role === 'user' ? t('me') : t('ai') }}
      </div>
    </div>

    <!-- 消息主体 -->
    <div class="bubble-main">
      <!-- 意图分析（classify 流式推理） -->
      <div v-if="message.role === 'assistant' && message.classifyText" class="bubble__classify" v-html="renderedClassify"></div>

      <!-- 思考内容（可折叠） -->
      <div v-if="message.role === 'assistant' && message.thinking" class="bubble__thinking">
        <div v-show="thinkingOpen" class="thinking__content" v-html="renderedThinking"></div>
      </div>

      <!-- 引用回复块 -->
      <div v-if="message.replyTo" class="bubble__reply">
        <span class="bubble__reply-label">{{ message.replyTo.role === 'user' ? t('userRole') : t('aiRole') }}</span>
        <span class="bubble__reply-text">{{ message.replyTo.content }}</span>
      </div>

      <!-- 图片展示 -->
      <div v-if="message.images && message.images.length" class="bubble__images">
        <img v-for="(img, idx) in message.images" :key="idx" :src="imageBlobUrls[img] || img" class="bubble__image" loading="lazy" @click="previewImage(img)" @error="(e) => { (e.target as HTMLImageElement).style.display = 'none' }" />
      </div>

      <!-- 文件附件展示 -->
      <div v-if="message.attachments && message.attachments.length" class="bubble__files">
        <div v-for="(f, idx) in message.attachments" :key="idx" class="bubble__file" @click="handleFileClick(f)">
          <span class="bubble__file-icon" :data-type="getFileType(f.mimeType)">
            <svg viewBox="0 0 24 28" width="20" height="24" fill="none" stroke="currentColor" stroke-width="1.2">
              <path d="M4 2h10l6 6v18a1 1 0 01-1 1H4a1 1 0 01-1-1V3a1 1 0 011-1z"/>
              <path d="M14 2v6h6"/>
            </svg>
            <span class="bubble__file-ext">{{ f.fileName.split('.').pop() || '?' }}</span>
          </span>
          <span class="bubble__file-info">
            <span class="bubble__file-name">{{ f.fileName }}</span>
            <span class="bubble__file-size">{{ formatFileSize(f.fileSize) }}</span>
          </span>
        </div>
      </div>

      <!-- 气泡正文（含三角形尾巴） -->
      <div class="bubble__body">
        <div class="bubble__text" :class="{ 'bubble__text--streaming': streaming }" @click="handleCodeCopy">
          <template v-if="audioSrc">
            <audio controls class="bubble__audio" :src="audioSrc" style="width:100%;max-width:300px;height:40px;">
              {{ t('audioNotSupported') }}
            </audio>
          </template>
          <template v-else-if="forestTree">
            <TreeNodeRender :node="forestTree" :depth="0" />
          </template>
          <template v-else>
            <div v-html="renderedContent"></div>
          </template>
        </div>
        <div v-show="streaming && !displayContent" class="bubble__streaming">
          <span class="streaming-dot"></span>
          <span class="streaming-dot"></span>
          <span class="streaming-dot"></span>
        </div>
      </div>

      <!-- 底部元信息（仅 AI） -->
      <div v-if="message.role === 'assistant' && (meta?.duration || meta?.tokens != null || meta?.model || message.messageId)" class="bubble__meta">
        <span v-if="message.messageId" class="meta__item meta__pipeline-btn" @click.stop="emit('showPipeline')">{{ t('viewPipeline') }}</span>
        <span v-if="meta?.duration" class="meta__item">{{ meta.duration }}</span>
        <span v-if="meta?.tokens != null" class="meta__item">{{ meta.tokens.toLocaleString() }} tokens</span>
        <span v-if="meta?.model" class="meta__item">{{ meta.model }}</span>
      </div>

      <!-- 操作栏（AI：悬停显示，思考折叠按钮合并在此行） -->
      <div v-if="message.role === 'assistant'" class="bubble__actions">
        <button v-if="message.generationGroup" class="gen-counter" @click="emit('switchGeneration')" :title="t('switchGeneration')">&lt;{{ message.generationIndex || 1 }},{{ message.generationTotal ?? '?' }}&gt;</button>
        <button v-if="(message.thinking || message.thinkingTokens) && hideExtra !== 'thinking-toggle' && hideExtra !== 'both'" class="thinking__toggle" @click="thinkingOpen = !thinkingOpen" :title="t('thinking')">
          <svg class="toggle__chevron" :class="{ 'is-open': thinkingOpen }" viewBox="0 0 20 20" width="10" height="10">
            <path d="M6 8l4 4 4-4" fill="none" stroke="currentColor" stroke-width="1.5"/>
          </svg>
          <span class="toggle__label">{{ thinkingOpen ? t('hideThinking') : t('thoughtTime') }}{{ thinkingDurationLabel }}</span>
          <span class="toggle__actions" @click.stop>
            <button class="action-btn" :title="t('copyThinking')" @click="emit('copy', message.thinking || '')">
              <svg viewBox="0 0 20 20" width="12" height="12" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"><rect x="5.5" y="2.5" width="10" height="13" rx="1.5"/><rect x="3.5" y="4.5" width="10" height="13" rx="1.5"/></svg>
            </button>
            <button class="action-btn" :title="t('saveThinkingToKb')" @click="emit('saveToKb', message.thinking || '')">
              <svg viewBox="0 0 20 20" width="12" height="12" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"><path d="M4 13v2a1 1 0 001 1h10a1 1 0 001-1v-2"/><path d="M10 3v9M7 9l3 3 3-3"/></svg>
            </button>
          </span>
        </button>
          <button class="action-btn" :title="t('copy')" @click="emit('copy', message.content)">
            <svg viewBox="0 0 20 20" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"><rect x="5.5" y="2.5" width="10" height="13" rx="1.5"/><rect x="3.5" y="4.5" width="10" height="13" rx="1.5"/></svg>
          </button>
          <button class="action-btn" :title="t('quoteReply')" @click="emit('quote', message.content)">
            <svg viewBox="0 0 20 20" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"><path d="M3 3.5A1.5 1.5 0 014.5 2h11A1.5 1.5 0 0117 3.5v9a1.5 1.5 0 01-1.5 1.5H10l-3 3v-3H4.5A1.5 1.5 0 013 12.5v-9z"/></svg>
          </button>
          <button class="action-btn" :title="t('saveToKb')" @click="emit('saveToKb', message.content)">
            <svg viewBox="0 0 20 20" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"><path d="M4 13v2a1 1 0 001 1h10a1 1 0 001-1v-2"/><path d="M10 3v9M7 9l3 3 3-3"/></svg>
          </button>
          <button class="action-btn" :title="t('rate')" @click="emit('rate')">
            <svg viewBox="0 0 20 20" width="13" height="13"><path d="M10 1l2.39 4.84 5.34.78-3.87 3.77.91 5.33L10 13.88l-4.77 2.84.91-5.33L2.27 6.62l5.34-.78L10 1z" fill="currentColor"/></svg>
          </button>
          <button class="action-btn" :title="t('regenerate')" @click="emit('regenerate')">
            <svg viewBox="0 0 20 20" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"><path d="M15 4.5A7 7 0 104.5 15M5 15.5A7 7 0 1015.5 5"/><path d="M15 4.5h2.5v-2M5 15.5H2.5v2"/></svg>
          </button>
          <button class="action-btn" :title="t('deleteMsg')" @click="emit('delete')">
            <svg viewBox="0 0 20 20" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"><path d="M3 4h14M5 4v12a2 2 0 002 2h6a2 2 0 002-2V4M8 4V3a1 1 0 011-1h2a1 1 0 011 1v1"/></svg>
          </button>
        </div>

      <!-- 用户消息底部行（操作栏悬停 + 时间常显在最右） -->
      <div v-if="message.role === 'user' && hideExtra !== 'user-footer' && hideExtra !== 'both'" class="bubble__footer-row">
        <div class="bubble__actions bubble__actions--user">
          <button class="action-btn" :title="t('copy')" @click="emit('copy', message.content)">
            <svg viewBox="0 0 20 20" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"><rect x="5.5" y="2.5" width="10" height="13" rx="1.5"/><rect x="3.5" y="4.5" width="10" height="13" rx="1.5"/></svg>
          </button>
          <button class="action-btn" :title="t('quoteReply')" @click="emit('quote', message.content)">
            <svg viewBox="0 0 20 20" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"><path d="M3 3.5A1.5 1.5 0 014.5 2h11A1.5 1.5 0 0117 3.5v9a1.5 1.5 0 01-1.5 1.5H10l-3 3v-3H4.5A1.5 1.5 0 013 12.5v-9z"/></svg>
          </button>
          <button class="action-btn" :title="t('deleteMsg')" @click="emit('delete')">
            <svg viewBox="0 0 20 20" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"><path d="M3 4h14M5 4v12a2 2 0 002 2h6a2 2 0 002-2V4M8 4V3a1 1 0 011-1h2a1 1 0 011 1v1"/></svg>
          </button>
        </div>
        <div class="bubble__footer">
          <slot name="time" />
        </div>
      </div>
    </div>
  </div>

  <!-- 图片预览遮罩 -->
  <Teleport to="body">
    <div v-if="previewImageUrl" class="image-preview-overlay" @click.self="closePreview">
      <button class="image-preview-close-btn" @click="closePreview">&times;</button>
      <img :src="imageBlobUrls[previewImageUrl] || previewImageUrl" class="image-preview-full" />
    </div>
  </Teleport>
</template>

<style scoped>
/* ── 行布局 ── */
.bubble-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  animation: bubble-in var(--dur-normal) var(--ease-out) forwards;
  contain: layout style paint;
}

@keyframes bubble-in {
  from { opacity: 0; transform: translateY(8px); }
  to   { opacity: 1; transform: translateY(0); }
}

.bubble-row--user {
  flex-direction: row-reverse;
}

/* ── 头像 ── */
.bubble-avatar {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  margin-top: 4px;
}

.avatar-circle {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--fs-callout);
  font-weight: var(--fw-semibold);
  user-select: none;
  overflow: hidden;
}

.bubble-row--user .avatar-circle {
  background: var(--clr-accent);
  color: #fff;
}

.bubble-row--assistant .avatar-circle {
  background: var(--clr-fill);
  color: var(--clr-secondary);
  border: 1px solid var(--clr-hairline);
}

/* ── 消息主体 ── */
.bubble-main {
  max-width: 80%;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.bubble-row--assistant .bubble-main {
  align-items: flex-start;
}

.bubble-row--user .bubble-main {
  align-items: flex-end;
}

/* ── 引用回复块 ── */
.bubble__reply {
  display: flex;
  flex-direction: column;
  padding: 6px 10px;
  margin-bottom: 6px;
  border-radius: var(--rad-md);
  background: var(--clr-bg-secondary);
  border-left: 3px solid var(--clr-quaternary);
  gap: 2px;
  cursor: default;
}
.bubble__reply-label {
  font-size: var(--fs-footnote);
  font-weight: var(--fw-medium);
  color: var(--clr-accent);
}
.bubble__reply-text {
  font-size: var(--fs-callout);
  color: var(--clr-secondary);
  overflow: hidden;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
}

/* ── 气泡正文 ── */
.bubble__body {
  position: relative;
  padding: var(--sp-unit-1_5) var(--sp-unit-2);
  line-height: var(--lh-body);
  font-size: var(--fs-body);
  letter-spacing: var(--ls-body);
  word-break: break-word;
  max-width: 100%;
  min-height: 46px;
  contain: layout style;
}

/* 用户气泡 */
.bubble-row--user .bubble__body {
  background: var(--clr-bubble-user);
  color: var(--clr-bubble-user-text);
  border-radius: 6px;
}

/* AI 气泡 */
.bubble-row--assistant .bubble__body {
  background: var(--clr-bubble-ai);
  color: var(--clr-label);
  border-radius: 6px;
  box-shadow: var(--shd-bubble);
}

/* ── 三角形尾巴 ── */
.bubble-row--user .bubble__body::before {
  content: '';
  position: absolute;
  right: -6px;
  top: 12px;
  width: 0;
  height: 0;
  border: 6px solid transparent;
  border-left-color: var(--clr-bubble-user);
  border-right: 0;
}

.bubble-row--assistant .bubble__body::before {
  content: '';
  position: absolute;
  left: -6px;
  top: 12px;
  width: 0;
  height: 0;
  border: 6px solid transparent;
  border-right-color: var(--clr-bubble-ai);
  border-left: 0;
}

/* ── 打字指示器 ── */
.bubble__text--streaming::after {
  content: '▊';
  animation: cursor-blink 1s step-end infinite;
  margin-left: 2px;
  color: var(--clr-accent);
  font-size: 0.9em;
}

@keyframes cursor-blink {
  0%, 50% { opacity: 1; }
  51%, 100% { opacity: 0; }
}

.bubble__streaming {
  display: flex;
  gap: 4px;
  padding: 4px 0;
}

.streaming-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--clr-accent);
  animation: dot-pulse 1.2s infinite;
}

.streaming-dot:nth-child(2) { animation-delay: 0.2s; }
.streaming-dot:nth-child(3) { animation-delay: 0.4s; }

@keyframes dot-pulse {
  0%, 80%, 100% { opacity: 0.2; }
  40% { opacity: 0.8; }
}

.bubble__interrupted {
  font-size: var(--fs-footnote);
  color: var(--clr-tertiary);
  padding: 4px 0 2px;
}
.bubble__interrupted--partial {
  color: #d97706;
  font-style: italic;
}

/* ── 思考折叠区 ── */
.thinking__toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--clr-quaternary);
  font-size: var(--fs-caption);
  cursor: pointer;
  transition: background 0.12s ease, color 0.12s ease, border-color 0.12s ease;
}

.thinking__toggle:hover {
  color: var(--clr-secondary);
}

.toggle__chevron {
  transition: transform 0.2s var(--ease-spring);
}

.toggle__chevron.is-open {
  transform: rotate(180deg);
}

.toggle__label {
  font-weight: var(--fw-regular);
}

.toggle__actions {
  display: inline-flex;
  gap: 2px;
  margin-left: 6px;
}

/* 意图分析区域（classify 流式推理） */
.bubble__classify {
  margin-bottom: var(--sp-unit-1);
  padding: var(--sp-unit-1) var(--sp-unit-1_5);
  border-radius: var(--rad-md);
  background: var(--clr-bg-tertiary);
  font-size: var(--fs-footnote);
  color: var(--clr-tertiary);
  line-height: 1.5;
  border-left: 3px solid var(--clr-accent-border);
  white-space: pre-wrap;
}

/* 思考折叠区容器 */
.bubble__thinking {
  margin-bottom: var(--sp-unit-1);
}

.thinking__content {
  margin-top: var(--sp-unit-1);
  padding: var(--sp-unit-1) var(--sp-unit-1_5);
  border-radius: var(--rad-md);
  background: var(--clr-bg-tertiary);
  font-size: var(--fs-callout);
  color: var(--clr-secondary);
  line-height: 1.5;
  border-left: 3px solid var(--clr-accent-border);
}

/* ── 思考内容的 Markdown 样式（同气泡正文） ── */
.thinking__content :deep(p) {
  margin: 0 0 8px;
  line-height: var(--lh-body);
}
.thinking__content :deep(p:last-child) {
  margin-bottom: 0;
}
.thinking__content :deep(ul),
.thinking__content :deep(ol) {
  margin: 0 0 8px;
  padding-left: 20px;
}
.thinking__content :deep(li) {
  margin-bottom: 2px;
}
.thinking__content :deep(h1),
.thinking__content :deep(h2),
.thinking__content :deep(h3),
.thinking__content :deep(h4) {
  margin: 12px 0 6px;
  font-weight: var(--fw-semibold);
  line-height: 1.4;
}
.thinking__content :deep(h1) { font-size: 1.3em; }
.thinking__content :deep(h2) { font-size: 1.15em; }
.thinking__content :deep(h3) { font-size: 1.05em; }
.thinking__content :deep(a) {
  color: var(--clr-accent);
  text-decoration: underline;
}
.thinking__content :deep(blockquote) {
  margin: 6px 0;
  padding: 4px 12px;
  border-left: 3px solid var(--clr-quaternary);
  color: var(--clr-secondary);
}
.thinking__content :deep(table) {
  border-collapse: collapse;
  margin: 8px 0;
  font-size: var(--fs-footnote);
  display: block;
  overflow-x: auto;
  max-width: 100%;
}
.thinking__content :deep(th),
.thinking__content :deep(td) {
  padding: 5px 10px;
  text-align: left;
}
.thinking__content :deep(th) {
  background: var(--clr-fill);
  font-weight: var(--fw-semibold);
  white-space: nowrap;
  border-bottom: 2px solid var(--clr-separator);
}
.thinking__content :deep(td) {
  border-bottom: 1px solid var(--clr-hairline);
  color: var(--clr-primary);
}
.thinking__content :deep(tr:last-child td) {
  border-bottom: none;
}
.thinking__content :deep(hr) {
  border: none;
  border-top: 1px solid var(--clr-hairline);
  margin: 12px 0;
}
.thinking__content :deep(code) {
  font-family: 'SF Mono', 'Fira Code', 'Cascadia Code', 'JetBrains Mono', Consolas, monospace;
  font-size: 0.85em;
  background: var(--clr-fill);
  padding: 2px 5px;
  border-radius: 4px;
}
.thinking__content :deep(pre) {
  margin: 8px 0;
  padding: 12px 14px;
  border-radius: 6px;
  background: #1e1e2e;
  overflow-x: auto;
  line-height: 1.5;
  font-size: 0.85em;
}
.thinking__content :deep(pre code) {
  background: none;
  padding: 0;
  border-radius: 0;
  font-size: inherit;
  color: #cdd6f4;
}

/* ── 底部元信息 ── */
.bubble__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 3px;
  padding: 0 4px;
}

.meta__item {
  font-size: var(--fs-caption);
  color: var(--clr-quaternary);
}
.meta__item + .meta__item::before {
  content: ' · ';
  color: var(--clr-quaternary);
}

.meta__pipeline-btn {
  color: var(--clr-accent);
  font-weight: var(--fw-medium);
  font-size: var(--fs-caption);
  cursor: pointer;
  transition: opacity 0.15s;
}
.meta__pipeline-btn:hover {
  opacity: 0.75;
}
.meta__routing-btn:hover {
  opacity: 0.75;
}
.meta__pipeline-btn + .meta__item::before {
  content: ' · ';
  color: var(--clr-quaternary);
}

/* ── 操作栏 ── */
.bubble__actions {
  display: flex;
  gap: 1px;
  margin-top: 2px;
  padding: 0;
  opacity: 0;
  transition: opacity 0.18s ease;
  contain: layout style paint;
}

.bubble-row:hover .bubble__actions,
.bubble-row:focus-within .bubble__actions {
  opacity: 1;
}

/* 用户消息操作栏：右对齐 */
.bubble__actions--user {
  justify-content: flex-end;
}


.gen-counter {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: var(--fs-caption);
  color: var(--clr-quaternary);
  font-variant-numeric: tabular-nums;
  padding: 0 4px;
  line-height: 24px;
  border: none;
  background: transparent;
  cursor: pointer;
  font-family: inherit;
  border-radius: 4px;
  transition: color var(--dur-fast) var(--ease-out), background var(--dur-fast) var(--ease-out);
}
.gen-counter:hover {
  color: var(--clr-accent);
  background: var(--clr-fill);
}

.action-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--clr-quaternary);
  cursor: pointer;
  transition: background 0.12s ease, color 0.12s ease, transform 0.12s ease;
}

.action-btn:hover {
  background: var(--clr-fill);
  color: var(--clr-secondary);
}

.action-btn:active {
  transform: scale(0.9);
}

.action-separator {
  width: 1px;
  height: 14px;
  margin: 0 2px;
  background: var(--clr-hairline);
  align-self: center;
}


/* ── 用户消息底部行 ── */
.bubble__footer-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 4px;
  min-height: 24px;
}
.bubble__footer {
  display: flex;
  font-size: var(--fs-footnote);
  color: var(--clr-tertiary);
  padding: 0 4px;
}

/* ── Markdown 渲染 ── */
.bubble__text {
  white-space: normal;
}

.bubble__text :deep(p) {
  margin: 0 0 8px;
  line-height: var(--lh-body);
}
.bubble__text :deep(p:last-child) {
  margin-bottom: 0;
}

.bubble__text :deep(ul),
.bubble__text :deep(ol) {
  margin: 0 0 8px;
  padding-left: 20px;
}
.bubble__text :deep(li) {
  margin-bottom: 2px;
}
.bubble__text :deep(h1),
.bubble__text :deep(h2),
.bubble__text :deep(h3),
.bubble__text :deep(h4) {
  margin: 12px 0 6px;
  font-weight: var(--fw-semibold);
  line-height: 1.4;
}
.bubble__text :deep(h1) { font-size: 1.3em; }
.bubble__text :deep(h2) { font-size: 1.15em; }
.bubble__text :deep(h3) { font-size: 1.05em; }
.bubble__text :deep(a) {
  color: var(--clr-accent);
  text-decoration: underline;
}
.bubble-row--user .bubble__text :deep(a) {
  color: rgba(255, 255, 255, 0.9);
  text-decoration: underline;
}
.bubble-row--user .bubble__text :deep(a:hover) {
  color: #fff;
}
.bubble-row--user .bubble__text :deep(code) {
  background: rgba(255, 255, 255, 0.15);
  color: var(--clr-bubble-user-text);
}
.bubble__text :deep(blockquote) {
  margin: 6px 0;
  padding: 4px 12px;
  border-left: 3px solid var(--clr-quaternary);
  color: var(--clr-secondary);
}
.bubble__text :deep(hr) {
  border: none;
  border-top: 1px solid var(--clr-hairline);
  margin: 12px 0;
}
.bubble__text :deep(code) {
  font-family: 'SF Mono', 'Fira Code', 'Cascadia Code', 'JetBrains Mono', Consolas, monospace;
  font-size: 0.85em;
  background: var(--clr-fill);
  padding: 2px 5px;
  border-radius: 4px;
}
.bubble__text :deep(pre) {
  margin: 8px 0;
  padding: 12px 14px;
  border-radius: 6px;
  background: #1e1e2e;
  overflow-x: auto;
  line-height: 1.5;
  font-size: 0.85em;
}
.bubble__text :deep(pre code) {
  background: none;
  padding: 0;
  border-radius: 0;
  font-size: inherit;
  color: #cdd6f4;
}

/* ── 图片展示 ── */
.bubble__images {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: var(--sp-unit-1);
}

.bubble__image {
  width: 200px;
  height: 200px;
  border-radius: var(--rad-md);
  cursor: zoom-in;
  object-fit: cover;
  border: 1px solid var(--clr-hairline);
  transition: opacity 0.15s ease;
}

.bubble__image:hover {
  opacity: 0.85;
}

/* ── 文件附件展示 ── */
.bubble__files {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: var(--sp-unit-1);
}
.bubble__file {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: var(--rad-md);
  border: 1px solid var(--clr-separator);
  text-decoration: none;
  transition: background var(--dur-fast);
  cursor: pointer;
}
.bubble__file:hover {
  background: var(--clr-fill);
}
.bubble__file-icon {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 36px;
  flex-shrink: 0;
  color: var(--clr-tertiary);
}
.bubble__file-ext {
  position: absolute;
  bottom: 3px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 7px;
  font-weight: var(--fw-bold);
  color: var(--clr-secondary);
  text-transform: uppercase;
  letter-spacing: 0.02em;
  line-height: 1;
}
.bubble__file-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}
.bubble__file-name {
  font-size: var(--fs-callout);
  color: var(--clr-label);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.bubble__file-size {
  font-size: var(--fs-caption);
  color: var(--clr-tertiary);
}

/* ── 图片预览遮罩（全局样式 via Teleport to body） ── */
</style>

<!-- 代码块样式（unscoped，作用于 v-html 注入的元素） -->
<style>
.code-block-wrapper {
  position: relative;
}
.code-lang-label {
  position: absolute;
  top: 4px;
  right: 30px;
  font-size: 13px;
  color: rgba(255,255,255,0.2);
  text-transform: lowercase;
  user-select: none;
  pointer-events: none;
  z-index: 1;
}
.code-copy-btn {
  position: absolute;
  top: 2px;
  right: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: rgba(255,255,255,0.4);
  cursor: pointer;
  z-index: 1;
  opacity: 0;
  transition: opacity 0.15s ease, color 0.12s ease, background 0.12s ease;
}
.code-block-wrapper:hover .code-copy-btn {
  opacity: 1;
}
.code-copy-btn:hover {
  color: rgba(255,255,255,0.85);
  background: rgba(255,255,255,0.08);
}

/* ── 图片预览遮罩 ── */
.image-preview-overlay {
  position: fixed;
  inset: 0;
  z-index: 10000;
  background: rgba(0, 0, 0, 0.75);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: zoom-out;
  backdrop-filter: blur(4px);
  animation: overlay-fade-in 0.2s ease;
}
@keyframes overlay-fade-in {
  from { opacity: 0; }
  to { opacity: 1; }
}
.image-preview-close-btn {
  position: fixed;
  top: 16px;
  right: 20px;
  background: none;
  border: none;
  color: #fff;
  font-size: 32px;
  cursor: pointer;
  z-index: 10001;
  opacity: 0.7;
  transition: opacity 0.15s;
}
.image-preview-close-btn:hover {
  opacity: 1;
}
.image-preview-full {
  max-width: 90vw;
  max-height: 90vh;
  object-fit: contain;
  border-radius: 6px;
  box-shadow: 0 8px 40px rgba(0,0,0,0.4);
}

/* ── Markdown 表格 ── */
.bubble__text table {
  border-collapse: collapse;
  margin: 8px 0;
  font-size: var(--fs-footnote);
  display: block;
  overflow-x: auto;
  max-width: 100%;
}
.bubble__text thead {
  border-bottom: 2px solid var(--clr-separator);
}
.bubble__text th {
  padding: 6px 10px;
  text-align: left;
  font-weight: var(--fw-semibold);
  color: var(--clr-label);
  white-space: nowrap;
  background: var(--clr-fill);
}
.bubble__text td {
  padding: 5px 10px;
  border-bottom: 1px solid var(--clr-hairline);
  color: var(--clr-primary);
}
.bubble__text tr:last-child td {
  border-bottom: none;
}
.bubble__text tbody tr:hover {
  background: var(--clr-fill-hover);
}

/* ── Agent 内联标注（20-编排产出展示方案） ── */
:deep(.agent-badge) {
  display: inline;
  padding: 0 4px;
  font-size: var(--fs-caption, 11px);
  color: var(--clr-accent, #409eff);
  background: var(--clr-accent-soft, #ecf5ff);
  border-radius: 3px;
  cursor: default;
  white-space: nowrap;
}
:deep(.agent-badge:hover) {
  background: var(--clr-accent, #409eff);
  color: #fff;
}
</style>
