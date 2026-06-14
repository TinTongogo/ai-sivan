<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useThemeStore } from '../../stores/theme'
import { useSettingsStore } from '../../stores/settings'
import { useAuthStore } from '../../stores/auth'
import { useI18n } from '../../utils/i18n'
import { useMessage } from '../../utils/message'
import { fetchCapabilities, fetchDefaultCapabilities, type CapabilityInfo } from '../../api/llm-provider'
import { changePassword, fetchAiProfile, updateAiProfile, fetchProfileChangeHistory, type UserProfileResponse, type ProfileChangeLogEntry } from '../../api/account'
import { usePreferencesStore } from '../../stores/preferences'
import { useRouter } from 'vue-router'
import { AGREEMENT_CONTENT } from '../../utils/agreement-content'
import { renderMarkdown } from '../../utils/markdown'

const emit = defineEmits<{ close: [] }>()

const theme = useThemeStore()
const settings = useSettingsStore()
const auth = useAuthStore()
const { t, lang, setLang } = useI18n()
const message = useMessage()
const router = useRouter()
const prefsStore = usePreferencesStore()

const fontSizeOptions = computed(() => [
  { value: '13px', label: t('fontSizeSmall') },
  { value: '15px', label: t('fontSizeMedium') },
  { value: '17px', label: t('fontSizeLarge') },
  { value: '19px', label: t('fontSizeXLarge') },
])

const sendModeOptions = [
  { value: 'enter', labelKey: 'sendModeEnter' },
  { value: 'mod-enter', labelKey: 'sendModeModEnter' },
  { value: 'system', labelKey: 'sendModeSystem' },
]

function fontSizeBtnStyle(px: string) {
  const scale = parseInt(px) / 15
  return {
    fontSize: `${Math.round(12 * scale)}px`,
    padding: `${Math.round(5 * scale)}px ${Math.round(12 * scale)}px`,
    lineHeight: 1.3,
  }
}

// ── 字体浏览器 ──
const systemFonts = ref<{ family: string; fullName: string }[]>([])
const fontSearch = ref('')
const fontLoading = ref(false)
const showFontBrowser = ref(false)

const isCustomFont = computed(() =>
  prefsStore.prefs.fontFamily !== 'Inter' && prefsStore.prefs.fontFamily !== 'System'
)

const fontFamilyPreset = computed(() => {
  if (prefsStore.prefs.fontFamily === 'Inter') return 'Inter'
  if (prefsStore.prefs.fontFamily === 'System') return 'System'
  return '__browse__'
})

const filteredFonts = computed(() => {
  const q = fontSearch.value.toLowerCase()
  return systemFonts.value.filter(f =>
    f.family.toLowerCase().includes(q) || f.fullName.toLowerCase().includes(q)
  )
})

async function loadSystemFonts() {
  if (systemFonts.value.length > 0) return
  if (!('queryLocalFonts' in window)) {
    message.info('当前浏览器不支持浏览系统字体')
    return
  }
  fontLoading.value = true
  try {
    const fonts: any[] = await (window as any).queryLocalFonts()
    const seen = new Set<string>()
    for (const f of fonts) {
      if (!seen.has(f.family)) {
        seen.add(f.family)
        systemFonts.value.push({ family: f.family, fullName: f.fullName })
      }
    }
    systemFonts.value.sort((a, b) => a.family.localeCompare(b.family))
  } catch { /* 用户拒绝权限 */ }
  fontLoading.value = false
}

function onFontFamilyPresetChange(e: Event) {
  const v = (e.target as HTMLSelectElement).value
  if (v === 'Inter' || v === 'System') {
    prefsStore.save({ fontFamily: v })
    showFontBrowser.value = false
  } else {
    showFontBrowser.value = true
    loadSystemFonts()
  }
}

function selectFont(family: string) {
  prefsStore.save({ fontFamily: family })
  showFontBrowser.value = false
}

type Section = 'general' | 'account' | 'llm' | 'mcp' | 'agreement'
const activeSection = ref<Section>('general')

const sections: { key: Section; labelKey: string }[] = [
  { key: 'general', labelKey: 'general' },
  { key: 'account', labelKey: 'account' },
  { key: 'llm', labelKey: 'llm' },
  { key: 'mcp', labelKey: 'mcp' },
  { key: 'agreement', labelKey: 'agreement' },
]

// 主题
const themeOptions = [
  { value: 'light', labelKey: 'themeLight' },
  { value: 'dark', labelKey: 'themeDark' },
  { value: 'system', labelKey: 'themeSystem' },
] as const

// 账号
const accountTab = ref<'info' | 'password' | 'profile'>('info')
const displayNameDraft = ref(auth.displayName)

function saveAccount() {
  if (displayNameDraft.value.trim()) {
    auth.displayName = displayNameDraft.value
    localStorage.setItem('displayName', displayNameDraft.value)
    message.success(t('saved'))
  }
}

// ── AI 画像 ──
const aiProfile = ref<UserProfileResponse>({ profileId: '', accountId: '', name: '', bio: '', aiLanguage: 'auto', expertise: [], autoLearn: true, active: true })
const loadingProfile = ref(false)
const savingProfile = ref(false)
const expertiseInput = ref('')

async function loadProfile() {
  loadingProfile.value = true
  try {
    const res = await fetchAiProfile()
    if (res.data) {
      aiProfile.value = {
        ...res.data,
        expertise: res.data.expertise ?? [],
      }
    }
  } catch { /* ignore */ } finally {
    loadingProfile.value = false
  }
}

async function saveProfile() {
  savingProfile.value = true
  try {
    await updateAiProfile({
      name: aiProfile.value.name || undefined,
      bio: aiProfile.value.bio || undefined,
      aiLanguage: aiProfile.value.aiLanguage || undefined,
      expertise: aiProfile.value.expertise.length ? aiProfile.value.expertise : undefined,
      autoLearn: aiProfile.value.autoLearn,
    })
    message.success(t('saved'))
  } catch (e: any) {
    message.error(e.response?.data?.message || t('saveFailed'))
  } finally {
    savingProfile.value = false
  }
}

// ── 画像变更历史 ──
const changeHistory = ref<ProfileChangeLogEntry[]>([])
const showHistory = ref(false)
const loadingHistory = ref(false)

async function loadChangeHistory() {
  loadingHistory.value = true
  try {
    const res = await fetchProfileChangeHistory()
    changeHistory.value = res.data || []
  } catch { /* ignore */ } finally {
    loadingHistory.value = false
  }
}

function toggleHistory() {
  showHistory.value = !showHistory.value
  if (showHistory.value && changeHistory.value.length === 0) {
    loadChangeHistory()
  }
}

function addExpertise() {
  const v = expertiseInput.value.trim()
  if (v && !aiProfile.value.expertise.includes(v)) {
    aiProfile.value.expertise.push(v)
  }
  expertiseInput.value = ''
}

function removeExpertise(item: string) {
  aiProfile.value.expertise = aiProfile.value.expertise.filter(e => e !== item)
}

const aiLanguageOptions = [
  { value: '', labelKey: 'aiLangAuto' },
  { value: 'zh-CN', labelKey: 'aiLangZh' },
  { value: 'en', labelKey: 'aiLangEn' },
  { value: 'ja', labelKey: 'aiLangJa' },
]

watch(accountTab, (tab) => {
  if (tab === 'profile') loadProfile()
})

// 修改密码
const passwordForm = ref({ oldPassword: '', newPassword: '', confirmPassword: '' })
const changingPassword = ref(false)

async function doChangePassword() {
  if (!passwordForm.value.oldPassword || !passwordForm.value.newPassword) {
    message.warning(t('fillOldAndNewPassword'))
    return
  }
  if (passwordForm.value.newPassword !== passwordForm.value.confirmPassword) {
    message.warning(t('passwordMismatch'))
    return
  }
  changingPassword.value = true
  try {
    await changePassword({ oldPassword: passwordForm.value.oldPassword, newPassword: passwordForm.value.newPassword })
    message.success(t('passwordChanged'))
    passwordForm.value = { oldPassword: '', newPassword: '', confirmPassword: '' }
    // 修改密码成功后自动退出，要求重新登录
    setTimeout(() => {
      auth.logout()
      emit('close')
      router.push('/login')
    }, 1500)
  } catch (e: any) {
    message.error(e.response?.data?.message || t('wrongPassword'))
  } finally {
    changingPassword.value = false
  }
}

// 退出登录
function doLogout() {
  emit('close')
  auth.logout()
  router.push('/login')
}

// LLM 配置
const selectedLlmIdx = ref(0)
const testing = ref(false)
const modelOptions = ref<{ name: string; contextLength: number | null }[]>([])
const testResult = ref<{ success: boolean; message: string; models?: { name: string; contextLength: number | null }[] } | null>(null)

function onModelSelect() {
  const p = currentProvider.value
  if (!p?.model) return
  const selected = modelOptions.value.find(m => m.name === p.model)
  if (selected?.contextLength) p.contextLength = selected.contextLength
}
const savingLlm = ref(false)
const deletingLlm = ref(false)

const currentProvider = computed(() =>
    settings.llmProviders[selectedLlmIdx.value] || null
)

// 所有可用的能力定义（code → label）
const capabilityList = ref<CapabilityInfo[]>([])
const capLabelMap: Record<string, string> = {
  vision: 'capacityImage',
  tool_use: 'capacityTool',
  streaming: 'capacityStream',
  thinking: 'capacityThink',
  reasoning_effort: 'capacityReasoning',
	  system_prompt: 'capacitySystemPrompt',
	  json_mode: 'capacityJsonMode',
	  batch: 'capacityBatch',
	  image_gen: 'capacityImageGen',
	  speech_synth: 'capacitySpeechSynth',
	  speech_recog: 'capacitySpeechRecog',
	  video_gen: 'capacityVideoGen',
}
function capLabel(code: string): string {
  const key = capLabelMap[code]
  return key ? t(key) : code
}
onMounted(async () => {
  try {
    const res = await fetchCapabilities()
    capabilityList.value = res.data || []
  } catch { /* ignore */ }
})

// 当前 provider 已具备的能力集合
const currentCapabilities = computed<Set<string>>(() => {
  const p = currentProvider.value
  if (!p || !p.capabilities) return new Set()
  return new Set(p.capabilities.split(',').map(c => c.trim()).filter(Boolean))
})

// 切换能力的勾选状态
function toggleCapability(code: string) {
  const p = currentProvider.value
  if (!p) return
  const caps = p.capabilities ? p.capabilities.split(',').map(c => c.trim()).filter(Boolean) : []
  const idx = caps.indexOf(code)
  if (idx >= 0) {
    caps.splice(idx, 1)
  } else {
    caps.push(code)
  }
  p.capabilities = caps.join(',')
}

// 用途标签选项
const tagOptions = [
  { value: 'chat', labelKey: 'tagChat' },
]

// 当前 provider 已勾选的用途标签集合
const currentTags = computed<Set<string>>(() => {
  const p = currentProvider.value
  if (!p || !p.tags) return new Set()
  return new Set(p.tags.split(',').map(t => t.trim()).filter(Boolean))
})

// 切换用途标签
function toggleTag(code: string) {
  const p = currentProvider.value
  if (!p) return
  const tags = p.tags ? p.tags.split(',').map(t => t.trim()).filter(Boolean) : []
  const idx = tags.indexOf(code)
  if (idx >= 0) {
    tags.splice(idx, 1)
  } else {
    tags.push(code)
  }
  p.tags = tags.join(',')
}

// 加载默认能力（按模型名推断优先，providerType 兜底）
async function loadDefaultCapabilities(providerType: string, modelName?: string) {
  try {
    const res = await fetchDefaultCapabilities(providerType, modelName)
    const codes = res.data || []
    if (currentProvider.value && (!currentProvider.value.capabilities || currentProvider.value.providerId === '') && currentProvider.value.tags?.includes('chat')) {
      currentProvider.value.capabilities = codes.join(',')
    }
  } catch { /* ignore */ }
}

async function selectLlm(index: number) {
  selectedLlmIdx.value = index
  testResult.value = null
  modelOptions.value = []
  if (settings.llmProviders[index]?.providerId) {
    await settings.loadProviders()
  }
}

async function saveLlm() {
  const p = currentProvider.value
  if (!p) return
  savingLlm.value = true

  // 更新时如果 apiKey 含 "****" 说明是掩码值（用户未修改），去掉以免覆盖真实 key
  const isUpdate = !!p.providerId
  const apiKeyToSend = isUpdate && p.apiKey?.includes('****') ? undefined : p.apiKey

  try {
    if (p.providerId) {
      await settings.updateProvider(p.providerId, {
        name: p.name,
        providerType: p.providerType,
        apiKey: apiKeyToSend,
        baseUrl: p.baseUrl,
        model: p.model,
        capabilities: p.capabilities,
        temperature: p.temperature || null,
        contextLength: p.contextLength || undefined,
        tags: p.tags,
      })
    } else {
      await settings.createProvider({
        name: p.name,
        providerType: p.providerType,
        apiKey: p.apiKey,
        baseUrl: p.baseUrl,
        model: p.model,
        capabilities: p.capabilities,
        temperature: p.temperature || null,
        contextLength: p.contextLength || undefined,
        tags: p.tags || 'chat',
      })
      const idx = selectedLlmIdx.value
      settings.llmProviders.splice(idx, 1)
      selectedLlmIdx.value = Math.max(0, settings.llmProviders.length - 1)
    }
    message.success(t('saved'))
  } catch (e: any) {
    message.error(e.response?.data?.message || '保存失败')
  } finally {
    savingLlm.value = false
  }
}

async function addLlmProvider() {
  const existingIdx = settings.llmProviders.findIndex(p => !p.providerId)
  if (existingIdx >= 0) {
    selectedLlmIdx.value = existingIdx
    return
  }
  settings.llmProviders.push({
    providerId: '',
    name: '',
    providerType: 'openai',
    apiKey: '',
    baseUrl: 'https://api.openai.com/v1',
    model: '',
    active: true,
    isDefault: false,
    capabilities: '',
    tags: 'chat',
    temperature: null as number | null,
    contextLength: undefined as number | undefined,
    createdAt: '',
    updatedAt: '',
  })
  selectedLlmIdx.value = settings.llmProviders.length - 1
  testResult.value = null
  modelOptions.value = []
}

async function removeLlmProvider(idx: number) {
  const p = settings.llmProviders[idx]
  if (!p) return
  if (!confirm(t('confirmDeleteProvider') || '确定删除该提供商吗？')) return
  deletingLlm.value = true
  try {
    if (p.providerId) {
      await settings.deleteProvider(p.providerId)
    }
    settings.llmProviders.splice(idx, 1)
    if (selectedLlmIdx.value >= settings.llmProviders.length) {
      selectedLlmIdx.value = Math.max(0, settings.llmProviders.length - 1)
    }
    testResult.value = null
    modelOptions.value = []
    message.success(t('deleted'))
  } catch (e: any) {
    message.error(e.response?.data?.message || '删除失败')
  } finally {
    deletingLlm.value = false
  }
}

async function testConnection() {
  const p = currentProvider.value
  if (!p) return
  testing.value = true
  testResult.value = null
  try {
    const res = await settings.testConnection({ providerType: p.providerType, apiKey: p.apiKey, baseUrl: p.baseUrl })
    testResult.value = res
    if (res.success) {
      if (res.models?.length) modelOptions.value = res.models
      message.success(t('testSuccess'))
    }
    else message.error(t('testFailed') + ': ' + res.message)
  } catch (e: any) {
    testResult.value = { success: false, message: e.message }
    message.error(t('testFailed'))
  } finally {
    testing.value = false
  }
}


async function setAsDefault(idx: number) {
  const p = settings.llmProviders[idx]
  if (!p || !p.providerId) {
    message.warning(t('saveFirst'))
    return
  }
  await settings.setDefault(p.providerId)
  message.success(t('saved'))
}

const providerTypeOptions = computed(() => [
  { value: 'openai', label: 'OpenAI' },
  { value: 'anthropic', label: 'Anthropic' },
  { value: 'openai-compatible', label: t('providerTypeCompat') },
])


// MCP 配置
const selectedMcpIdx = ref(0)
const mcpTesting = ref(false)
const mcpTestResult = ref<{ success: boolean; message: string; tools?: { name: string; title?: string; description: string }[] } | null>(null)
const savingMcp = ref(false)
const deletingMcp = ref(false)

const currentMcpServer = computed(() =>
    settings.mcpServers[selectedMcpIdx.value] || null
)

async function selectMcp(index: number) {
  selectedMcpIdx.value = index
  mcpTestResult.value = null
  if (settings.mcpServers[index]?.serverId) {
    await settings.loadMcpServers()
  }
}

async function saveMcpServer() {
  const s = currentMcpServer.value
  if (!s) return
  savingMcp.value = true
  try {
    if (s.serverId) {
      await settings.updateMcpServer(s.serverId, {
        name: s.name,
        serverUrl: s.serverUrl,
        apiKey: s.apiKey,
        transport: s.transport,
        active: s.active,
      })
    } else {
      // 新建：先移除临时占位，再调用 API（createMcpServer 会在 store 中追加）
      const idx = selectedMcpIdx.value
      settings.mcpServers.splice(idx, 1)
      await settings.createMcpServer({
        name: s.name,
        serverUrl: s.serverUrl,
        apiKey: s.apiKey,
        transport: s.transport,
      })
      selectedMcpIdx.value = Math.max(0, settings.mcpServers.length - 1)
    }
    message.success(t('saved'))
  } catch (e: any) {
    message.error(e.response?.data?.message || '保存失败')
  } finally {
    savingMcp.value = false
  }
}

async function addMcpServer() {
  const existingIdx = settings.mcpServers.findIndex(s => !s.serverId)
  if (existingIdx >= 0) {
    selectedMcpIdx.value = existingIdx
    return
  }
  settings.mcpServers.push({
    serverId: '',
    name: '',
    serverUrl: '',
    apiKey: '',
    transport: 'sse',
    active: true,
    connectionStatus: 'DISCONNECTED',
    createdAt: '',
    updatedAt: '',
  })
  selectedMcpIdx.value = settings.mcpServers.length - 1
  mcpTestResult.value = null
}

async function removeMcpServer(idx: number) {
  const s = settings.mcpServers[idx]
  if (!s) return
  if (!confirm(t('confirmDelete'))) return
  deletingMcp.value = true
  try {
    if (s.serverId) {
      await settings.deleteMcpServer(s.serverId)
    }
    settings.mcpServers.splice(idx, 1)
    if (selectedMcpIdx.value >= settings.mcpServers.length) {
      selectedMcpIdx.value = Math.max(0, settings.mcpServers.length - 1)
    }
    mcpTestResult.value = null
  } catch (e: any) {
    message.error(e.response?.data?.message || '删除失败')
  } finally {
    deletingMcp.value = false
  }
}

async function testMcpConnection() {
  const s = currentMcpServer.value
  if (!s) return
  mcpTesting.value = true
  mcpTestResult.value = null
  try {
    const res = await settings.testMcpConnection(s.serverUrl, s.apiKey, s.transport)
    mcpTestResult.value = res
    if (res.success) message.success(t('mcpTestSuccess'))
    else message.error(t('mcpTestFailed') + ': ' + res.message)
  } catch (e: any) {
    mcpTestResult.value = { success: false, message: e.message }
    message.error(t('mcpTestFailed'))
  } finally {
    mcpTesting.value = false
  }
}

const mcpTransportOptions = [
  { value: 'sse', labelKey: 'mcpTransportSse' },
  { value: 'streamable-http', labelKey: 'mcpTransportHttp' },
]

// providerType 或 model 变化时，按模型名推断能力标签（仅新建/未保存时自动填充）
watch(() => currentProvider.value?.providerType, (newType) => {
  if (newType) loadDefaultCapabilities(newType, currentProvider.value?.model)
})
watch(() => currentProvider.value?.model, (newModel) => {
  if (newModel && currentProvider.value?.providerType) {
    loadDefaultCapabilities(currentProvider.value.providerType, newModel)
  }
})

onMounted(() => {
  if (!settings.llmProviders.length) {
    settings.loadProviders()
  }
  if (!settings.mcpServers.length) {
    settings.loadMcpServers()
  }
})
</script>

<template>
  <div class="settings-overlay" @click.self="emit('close')">
    <div class="settings-modal">
      <!-- 左侧导航 -->
      <aside class="settings-nav">
        <div class="settings-nav__header">{{ t('settings') }}</div>
        <div class="settings-nav__list">
          <button
            v-for="s in sections"
            :key="s.key"
            :class="['settings-nav__item', { 'is-active': activeSection === s.key }]"
            @click="activeSection = s.key"
          >
            {{ t(s.labelKey) }}
          </button>
        </div>
        <div class="settings-nav__footer">
          <button class="settings-nav__logout" @click="doLogout">
            <svg viewBox="0 0 20 20" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round">
              <path d="M7 3H4a1 1 0 00-1 1v12a1 1 0 001 1h3m7-9l-3-3m3 3l-3 3m3-3H7"/>
            </svg>
            {{ t('logout') }}
          </button>
        </div>
      </aside>

      <!-- 右侧内容 -->
      <div class="settings-content">
        <div class="settings-content__header">
          <span>{{ t(activeSection) }}</span>
          <button class="modal__close" @click="emit('close')">&times;</button>
        </div>

        <div class="settings-content__body">
          <!-- 通用设置 -->
          <div v-if="activeSection === 'general'" class="settings-panel">
            <div class="form-group">
              <label class="form-label">{{ t('theme') }}</label>
              <div class="theme-options">
                <label
                  v-for="opt in themeOptions"
                  :key="opt.value"
                  :class="['theme-option', { 'is-active': theme.mode === opt.value }]"
                >
                  <input type="radio" :value="opt.value" v-model="theme.mode" />
                  <span>{{ t(opt.labelKey) }}</span>
                </label>
              </div>
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('language') }}</label>
              <select class="select" style="width:200px" :value="lang" @change="setLang(($event.target as HTMLSelectElement).value)">
                <option value="zh-CN">中文</option>
                <option value="en">English</option>
              </select>
            </div>

            <!-- 发送方式 -->
            <div class="form-group">
              <label class="form-label">{{ t('sendMode') }}</label>
              <select class="select" style="width:200px"
                :value="prefsStore.prefs.sendMode ?? 'system'"
                @change="prefsStore.save({ sendMode: ($event.target as HTMLSelectElement).value as any })"
              >
                <option v-for="opt in sendModeOptions" :key="opt.value" :value="opt.value">{{ t(opt.labelKey) }}</option>
              </select>
            </div>

            <!-- 外观偏好 -->
            <div class="settings-section-title">{{ t('appearance') }}</div>

            <div class="form-group">
              <label class="form-label">{{ t('fontSize') }}</label>
              <div class="theme-options">
                <button v-for="opt in fontSizeOptions" :key="opt.value"
                  :class="['theme-option', { 'is-active': prefsStore.prefs.fontSize === opt.value }]"
                  :style="fontSizeBtnStyle(opt.value)"
                  @click="prefsStore.save({ fontSize: opt.value })"
                >{{ opt.label }}</button>
              </div>
            </div>

            <div class="form-group">
              <label class="form-label">{{ t('fontFamily') }}</label>
              <div style="display:flex; flex-direction:column; gap:8px">
                <div style="display:flex; gap:8px; align-items:flex-start">
                  <select class="select" style="width:160px"
                    :value="fontFamilyPreset"
                    @change="onFontFamilyPresetChange"
                  >
                    <option value="Inter">{{ t('fontFamilyInter') }}</option>
                    <option value="System">{{ t('fontFamilySystem') }}</option>
                    <option value="__browse__">{{ t('fontFamilyBrowse') }}</option>
                  </select>
                  <div v-if="isCustomFont" class="font-current-preview">
                    <span class="font-current-name" :style="{ fontFamily: prefsStore.prefs.fontFamily! }">{{ prefsStore.prefs.fontFamily }}</span>
                    <button class="btn btn-sm" @click="prefsStore.reset('fontFamily')">{{ t('reset') }}</button>
                  </div>
                </div>
                <!-- 系统字体浏览器 -->
                <div v-if="showFontBrowser" class="font-browser">
                  <div class="font-browser-search">
                    <input class="input" v-model="fontSearch" :placeholder="t('fontFamilySearch')" />
                  </div>
                  <div class="font-browser-list" ref="fontListRef">
                    <div v-for="f in filteredFonts" :key="f.family"
                      class="font-browser-item"
                      :class="{ 'is-selected': prefsStore.prefs.fontFamily === f.family }"
                      @click="selectFont(f.family)"
                    >
                      <span class="font-browser-item-preview" :style="{ fontFamily: f.family }">Aa {{ f.family }}</span>
                    </div>
                    <div v-if="!fontLoading && filteredFonts.length === 0" class="font-browser-empty">
                      {{ t('fontFamilyNoMatch') }}
                    </div>
                    <div v-if="fontLoading" class="font-browser-loading">
                      {{ t('loading') }}
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <div class="form-group">
              <label class="form-label">{{ t('chatBgColor') }}</label>
              <div class="color-pick-row">
                <input type="color" :value="prefsStore.prefs.chatBackground"
                  @change="prefsStore.save({ chatBackground: ($event.target as HTMLInputElement).value })" />
                <span class="color-pick-val">{{ prefsStore.prefs.chatBackground }}</span>
                <button class="btn btn-sm" @click="prefsStore.reset('chatBackground')">{{ t('reset') }}</button>
              </div>
            </div>

            <div class="form-group">
              <label class="form-label">{{ t('pageBgColor') }}</label>
              <div class="color-pick-row">
                <input type="color" :value="prefsStore.prefs.pageBackground"
                  @change="prefsStore.save({ pageBackground: ($event.target as HTMLInputElement).value })" />
                <span class="color-pick-val">{{ prefsStore.prefs.pageBackground }}</span>
                <button class="btn btn-sm" @click="prefsStore.reset('pageBackground')">{{ t('reset') }}</button>
              </div>
            </div>

            <div class="form-group">
              <label class="form-label">{{ t('userBubbleColor') }}</label>
              <div class="color-pick-row">
                <input type="color" :value="prefsStore.prefs.userBubbleColor"
                  @change="prefsStore.save({ userBubbleColor: ($event.target as HTMLInputElement).value })" />
                <span class="color-pick-val">{{ prefsStore.prefs.userBubbleColor }}</span>
                <button class="btn btn-sm" @click="prefsStore.reset('userBubbleColor')">{{ t('reset') }}</button>
              </div>
            </div>
          </div>

          <!-- 账号管理 -->
          <div v-if="activeSection === 'account'" class="settings-panel">
            <div class="account-tabs">
              <button
                :class="['account-tab', { 'is-active': accountTab === 'info' }]"
                @click="accountTab = 'info'"
              >{{ t('accountInfo') }}</button>
              <button
                :class="['account-tab', { 'is-active': accountTab === 'password' }]"
                @click="accountTab = 'password'"
              >{{ t('changePassword') }}</button>
              <button
                :class="['account-tab', { 'is-active': accountTab === 'profile' }]"
                @click="accountTab = 'profile'"
              >{{ t('aiProfile') }}</button>
            </div>

            <!-- 基本信息 -->
            <div v-if="accountTab === 'info'" class="account-panel">
              <div class="form-group">
                <label class="form-label">{{ t('username') }}</label>
                <input class="input" style="width:300px" :value="auth.username" disabled />
              </div>
              <div class="form-group">
                <label class="form-label">{{ t('displayName') }}</label>
                <input class="input" style="width:300px" v-model="displayNameDraft" />
              </div>
              <button class="btn btn-primary" @click="saveAccount">{{ t('save') }}</button>
            </div>

            <!-- 修改密码 -->
            <div v-if="accountTab === 'password'" class="account-panel">
              <div class="form-group">
                <label class="form-label">{{ t('oldPassword') }}</label>
                <input class="input" style="width:300px" type="password" v-model="passwordForm.oldPassword" autocomplete="current-password" />
              </div>
              <div class="form-group">
                <label class="form-label">{{ t('newPassword') }}</label>
                <input class="input" style="width:300px" type="password" v-model="passwordForm.newPassword" autocomplete="new-password" />
              </div>
              <div class="form-group">
                <label class="form-label">确认新密码</label>
                <input class="input" style="width:300px" type="password" v-model="passwordForm.confirmPassword" autocomplete="new-password" />
              </div>
              <button class="btn btn-primary" :disabled="changingPassword" @click="doChangePassword">
                {{ changingPassword ? '修改中...' : t('changePassword') }}
              </button>
            </div>

            <!-- AI 画像 -->
            <div v-if="accountTab === 'profile'" class="account-panel">
              <p class="settings-panel__desc" style="margin:0 0 16px">{{ t('aiProfileDesc') }}</p>
              <div v-if="loadingProfile" class="placeholder-content">{{ t('loading') }}</div>
              <template v-else>
                <div class="form-group">
                  <label class="form-label">{{ t('aiProfileName') }}</label>
                  <input class="input" style="width:100%;max-width:360px" v-model="aiProfile.name"
                    :placeholder="t('aiProfileNameHint')" />
                </div>
                <div class="form-group">
                  <label class="form-label">{{ t('aiProfileBio') }}</label>
                  <textarea class="input" style="width:100%;max-width:360px;min-height:80px;resize:vertical"
                    v-model="aiProfile.bio" :placeholder="t('aiProfileBioHint')" />
                </div>
                <div class="form-group">
                  <label class="form-label">{{ t('aiProfileLanguage') }}</label>
                  <select class="select" style="width:200px" v-model="aiProfile.aiLanguage">
                    <option v-for="opt in aiLanguageOptions" :key="opt.value" :value="opt.value">{{ t(opt.labelKey) }}</option>
                  </select>
                </div>
                <div class="form-group">
                  <label class="form-label">{{ t('aiProfileExpertise') }}</label>
                  <div style="display:flex;gap:8px;max-width:360px">
                    <input class="input" style="flex:1" v-model="expertiseInput"
                      :placeholder="t('aiProfileExpertiseHint')"
                      @keydown.enter.prevent="addExpertise" />
                    <button class="btn" @click="addExpertise">+</button>
                  </div>
                  <div v-if="aiProfile.expertise?.length" style="display:flex;flex-wrap:wrap;gap:6px;margin-top:8px">
                    <span v-for="item in aiProfile.expertise" :key="item" class="expertise-tag">
                      {{ item }}
                      <button class="expertise-tag__remove" @click="removeExpertise(item)">&times;</button>
                    </span>
                  </div>
                </div>
                <div class="form-group" style="margin-top:12px">
                  <label class="form-label" style="display:flex;align-items:center;gap:8px;cursor:pointer">
                    <input type="checkbox" v-model="aiProfile.autoLearn" />
                    <span>{{ t('aiAutoLearn') }}</span>
                  </label>
                  <p class="settings-panel__desc" style="margin:2px 0 0 24px;font-size:12px">{{ t('aiAutoLearnDesc') }}</p>
                </div>
                <button class="btn btn-primary" :disabled="savingProfile" @click="saveProfile">
                  {{ savingProfile ? t('saving') : t('save') }}
                </button>
                <details class="profile-history" style="margin-top:16px" @toggle="toggleHistory">
                  <summary class="profile-history__summary">{{ t('aiProfileHistory') }} ({{ changeHistory.length }})</summary>
                  <div v-if="loadingHistory" class="placeholder-content" style="padding:8px 0">{{ t('loading') }}</div>
                  <div v-else-if="changeHistory.length === 0" class="settings-panel__desc" style="padding:8px 0">{{ t('noHistory') }}</div>
                  <div v-else class="profile-history__list">
                    <div v-for="entry in changeHistory" :key="entry.logId" class="profile-history__item">
                      <div class="profile-history__header">
                        <span class="profile-history__field">{{ entry.fieldName }}</span>
                        <span class="profile-history__source" :class="entry.source">{{ entry.source === 'auto_learn' ? t('aiAutoLearn') : t('manual') }}</span>
                        <span class="profile-history__time">{{ new Date(entry.createdAt).toLocaleString() }}</span>
                      </div>
                      <div class="profile-history__diff" v-if="entry.oldValue || entry.newValue">
                        <span v-if="entry.oldValue" class="profile-history__old">{{ entry.oldValue }}</span>
                        <span v-if="entry.oldValue && entry.newValue" class="profile-history__arrow">→</span>
                        <span v-if="entry.newValue" class="profile-history__new">{{ entry.newValue }}</span>
                      </div>
                    </div>
                  </div>
                </details>
              </template>
            </div>
          </div>

          <!-- LLM 配置 -->
          <div v-if="activeSection === 'llm'" class="settings-panel-llm">
            <!-- 左侧提供商列表 -->
            <div class="llm-sidebar">
              <div class="llm-sidebar__header">{{ t('provider') }}</div>
              <div class="llm-sidebar__list">
                <div
                  v-for="(p, idx) in settings.llmProviders"
                  :key="p.providerId || idx"
                  :class="['llm-sidebar__item', { 'is-active': selectedLlmIdx === idx }]"
                  @click="selectLlm(idx)"
                >
                  <span class="llm-sidebar__top">
                    <span class="llm-sidebar__name">{{ p.name || p.providerType }}</span>
                    <span class="llm-sidebar__actions">
                      <span v-if="p.isDefault" class="llm-default-badge">{{ t('defaultBadge') }}</span>
                      <button
                        v-else-if="p.providerId"
                        class="llm-sidebar__action-btn llm-sidebar__set-default"
                        @click.stop="setAsDefault(idx)"
                      >{{ t('defaultProvider') }}</button>
                      <button
                        class="llm-sidebar__action-btn llm-sidebar__delete"
                        :title="t('delete')"
                        @click.stop="removeLlmProvider(idx)"
                      >
                        <svg viewBox="0 0 20 20" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round">
                          <path d="M5 5l10 10M15 5l-10 10"/>
                        </svg>
                      </button>
                    </span>
                  </span>
                  <span class="llm-sidebar__model">{{ p.model || p.providerType }}</span>
                </div>
                <div v-if="!settings.llmProviders.length" class="llm-sidebar__empty">{{ t('noProvider') }}</div>
              </div>
              <button class="btn btn-sm llm-add-btn" @click="addLlmProvider">+ {{ t('addProvider') }}</button>
            </div>

            <!-- 右侧配置详情 -->
            <div class="llm-detail">
              <div v-if="currentProvider" class="llm-detail__form">
                <div class="form-group">
                  <label class="form-label">{{ t('providerName') }}</label>
                  <input class="input" v-model="currentProvider.name" :placeholder="t('providerPlaceholder')" />
                </div>
                <div class="form-group">
                  <label class="form-label">{{ t('providerType') }}</label>
                  <select class="select" v-model="currentProvider.providerType">
                    <option v-for="opt in providerTypeOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
                  </select>
                </div>
                <div class="form-group">
                  <label class="form-label">{{ t('apiKey') }}</label>
                  <input class="input" type="password" v-model="currentProvider.apiKey" :placeholder="t('placeholderKey')" />
                </div>
                <div class="form-group">
                  <label class="form-label">{{ t('baseUrl') }}</label>
                  <input class="input" v-model="currentProvider.baseUrl" :placeholder="t('placeholderUrl')" />
                </div>
                <div class="form-group">
                  <label class="form-label">{{ t('model') }}</label>
                  <select v-if="modelOptions.length" class="select" v-model="currentProvider.model" @change="onModelSelect">
                    <option value="">{{ t('selectModel') }}</option>
                    <option v-for="m in modelOptions" :key="m.name" :value="m.name">
                      {{ m.name }}{{ m.contextLength ? ' (' + (m.contextLength / 1024).toFixed(0) + 'K)' : '' }}
                    </option>
                  </select>
                  <input v-else class="input" v-model="currentProvider.model" placeholder="gpt-4o" />
                </div>
                <div class="form-row">
                  <div class="form-group" style="flex:1">
                    <label class="form-label">{{ t('temperature') }}</label>
                    <input class="input" type="number" step="0.1" min="0" max="2" v-model.number="currentProvider.temperature" placeholder="API 默认" />
                  </div>
                  <div class="form-group" style="flex:1">
                    <label class="form-label">{{ t('contextLength') }}</label>
                    <input class="input" type="number" min="0" step="1" v-model.number="currentProvider.contextLength" placeholder="自动检测" />
                  </div>
                </div>
                <!-- 模型能力（动态标签） -->
                <div class="form-group">
                  <label class="form-label">{{ t('capabilities') }}</label>
                  <div class="capability-tags">
                    <button v-for="cap in capabilityList" :key="cap.code"
                      class="capability-tag"
                      :class="{ 'is-active': currentCapabilities.has(cap.code) }"
                      @click="toggleCapability(cap.code)">
                      {{ capLabel(cap.code) }}
                    </button>
                  </div>
                </div>
                <!-- 用途标签 -->
                <div class="form-group">
                  <label class="form-label">{{ t('tagUsage') }}</label>
                  <div class="capability-tags">
                    <button v-for="opt in tagOptions" :key="opt.value"
                      class="capability-tag"
                      :class="{ 'is-active': currentTags.has(opt.value) }"
                      @click="toggleTag(opt.value)">
                      {{ t(opt.labelKey) }}
                    </button>
                  </div>
                </div>
                <!-- 操作 -->
                <div v-if="testResult" class="llm-test-result-line" :class="testResult.success ? 'is-ok' : 'is-err'">
                  {{ testResult.message }}
                </div>
                <div class="llm-detail__actions">
                  <button class="btn btn-primary" :disabled="testing" @click="testConnection">
                    {{ testing ? t('testing') : t('testConnection') }}
                  </button>
                  <button class="btn btn-primary" :disabled="savingLlm || !currentProvider.name" @click="saveLlm">
                    {{ savingLlm ? '...' : t('save') }}
                  </button>
                </div>
              </div>
              <div v-else class="llm-detail__empty">
                <p>{{ t('configureFirst') }}</p>
              </div>
            </div>
          </div>

          <!-- MCP 配置 -->
          <div v-if="activeSection === 'mcp'" class="settings-panel-llm">
            <!-- 左侧服务器列表 -->
            <div class="llm-sidebar">
              <div class="llm-sidebar__header">{{ t('mcp') }}</div>
              <div class="llm-sidebar__list">
                <div
                  v-for="(s, idx) in settings.mcpServers"
                  :key="s.serverId || idx"
                  :class="['llm-sidebar__item', { 'is-active': selectedMcpIdx === idx }]"
                  @click="selectMcp(idx)"
                >
                  <span class="llm-sidebar__top">
                    <span class="llm-sidebar__name">{{ s.name || s.serverUrl }}</span>
                    <span class="llm-sidebar__actions">
                      <button
                        class="llm-sidebar__action-btn llm-sidebar__mcp-status"
                        :class="{ 'is-active': s.active }"
                        :title="s.active ? t('mcpEnabled') : t('mcpDisabled')"
                        @click.stop="s.active = !s.active"
                      >{{ s.active ? t('mcpEnabled') : t('mcpDisabled') }}</button>
                      <button
                        class="llm-sidebar__action-btn llm-sidebar__delete"
                        :title="t('delete')"
                        @click.stop="removeMcpServer(idx)"
                      >
                        <svg viewBox="0 0 20 20" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round">
                          <path d="M5 5l10 10M15 5l-10 10"/>
                        </svg>
                      </button>
                    </span>
                  </span>
                  <span class="llm-sidebar__model">{{ s.serverUrl }}</span>
                </div>
                <div v-if="!settings.mcpServers.length" class="llm-sidebar__empty">{{ t('mcpNoServer') }}</div>
              </div>
              <button class="btn btn-sm llm-add-btn" @click="addMcpServer">+ {{ t('mcpAddServer') }}</button>
            </div>

            <!-- 右侧配置详情 -->
            <div class="llm-detail">
              <div v-if="currentMcpServer" class="llm-detail__form">
                <div class="form-group">
                  <label class="form-label">{{ t('mcpServerName') }}</label>
                  <input class="input" v-model="currentMcpServer.name" :placeholder="t('mcpServerName')" />
                </div>
                <div class="form-group">
                  <label class="form-label">{{ t('mcpServerUrl') }}</label>
                  <input class="input" v-model="currentMcpServer.serverUrl" placeholder="http://localhost:3100/mcp" />
                </div>
                <div class="form-group">
                  <label class="form-label">{{ t('mcpApiKey') }}</label>
                  <input class="input" type="password" v-model="currentMcpServer.apiKey" />
                </div>
                <div class="form-group">
                  <label class="form-label">{{ t('mcpTransport') }}</label>
                  <select class="select" v-model="currentMcpServer.transport">
                    <option v-for="opt in mcpTransportOptions" :key="opt.value" :value="opt.value">{{ t(opt.labelKey) }}</option>
                  </select>
                </div>
                <!-- 工具列表 -->
                <div v-if="mcpTestResult?.tools?.length" class="form-group">
                  <div class="mcp-tool-panel">
                    <div class="mcp-tool-header">
                      <span>{{ t('mcpTools') }} ({{ mcpTestResult.tools.length }})</span>
                    </div>
                    <div class="mcp-tool-list">
                      <div v-for="tool in mcpTestResult.tools" :key="tool.name" class="mcp-tool-item">
                        <div class="mcp-tool-name">{{ tool.title || tool.name }}</div>
                        <div class="mcp-tool-desc">{{ tool.description }}</div>
                      </div>
                    </div>
                  </div>
                </div>
                <!-- 操作 -->
                <div v-if="mcpTestResult" class="llm-test-result-line" :class="mcpTestResult.success ? 'is-ok' : 'is-err'">
                  {{ mcpTestResult.message }}
                </div>
                <div class="llm-detail__actions">
                  <button class="btn btn-primary" :disabled="mcpTesting" @click="testMcpConnection">
                    {{ mcpTesting ? t('mcpTesting') : t('mcpTestConnection') }}
                  </button>
                  <button class="btn btn-primary" :disabled="savingMcp || !currentMcpServer.name || !currentMcpServer.serverUrl" @click="saveMcpServer">
                    {{ savingMcp ? '...' : t('save') }}
                  </button>
                </div>
              </div>
              <div v-else class="llm-detail__empty">
                <p>{{ t('mcpConfigureFirst') }}</p>
              </div>
            </div>
          </div>



          <!-- 服务协议 -->
          <div v-if="activeSection === 'agreement'" class="settings-panel">
            <div class="agreement-content" v-html="renderMarkdown(AGREEMENT_CONTENT)"></div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.settings-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.3);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: var(--z-modal);
  animation: fade-in 0.15s ease;
}

.settings-modal {
  display: flex;
  width: 90%;
  max-width: 820px;
  height: 70vh;
  max-height: 600px;
  background: var(--clr-bg);
  border-radius: var(--rad-xl);
  box-shadow: var(--shd-modal);
  overflow: hidden;
  animation: slide-up 0.2s ease;
}

/* 左侧导航 */
.settings-nav {
  width: 180px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border-right: 0.5px solid var(--clr-hairline);
  background: var(--clr-bg-secondary);
}

.settings-nav__header {
  padding: 16px 16px 12px;
  font-size: var(--fs-footnote);
  font-weight: var(--fw-semibold);
  color: var(--clr-secondary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.settings-nav__list {
  flex: 1;
  overflow-y: auto;
  padding: 0 8px 8px;
}

.settings-nav__item {
  display: block;
  width: 100%;
  padding: 8px 12px;
  text-align: left;
  font-size: var(--fs-callout);
  color: var(--clr-label);
  background: transparent;
  border: none;
  border-radius: var(--rad-md);
  cursor: pointer;
  font-family: inherit;
  transition: var(--tr-fast);
}

.settings-nav__item:hover {
  background: var(--clr-fill-hover);
}

.settings-nav__item.is-active {
  background: var(--clr-accent-soft);
  color: var(--clr-accent);
  font-weight: var(--fw-medium);
}

.settings-nav__footer {
  padding: 8px;
  border-top: 1px solid var(--clr-hairline);
}

.settings-nav__logout {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 12px;
  text-align: left;
  font-size: var(--fs-callout);
  color: var(--clr-tertiary);
  background: transparent;
  border: none;
  border-radius: var(--rad-md);
  cursor: pointer;
  font-family: inherit;
  transition: var(--tr-fast);
}

.settings-nav__logout:hover {
  background: rgba(255, 59, 48, 0.08);
  color: var(--clr-red);
}

/* 右侧内容 */
.settings-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.settings-content__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 20px;
  border-bottom: 1px solid var(--clr-hairline);
  font-size: var(--fs-headline);
  font-weight: var(--fw-semibold);
  color: var(--clr-label);
}

.settings-content__body {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

/* 面板 */
.settings-panel {
  max-width: 560px;
}

.settings-section-title {
  font-size: var(--fs-footnote);
  font-weight: var(--fw-semibold);
  color: var(--clr-secondary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin-bottom: var(--sp-unit-1_5);
}

.settings-section-divider {
  height: 1px;
  background: var(--clr-hairline);
  margin: var(--sp-unit-3) 0 var(--sp-unit-2);
}

/* 账号标签页 */
.account-tabs {
  display: flex;
  gap: 0;
  border: 1px solid var(--clr-separator);
  border-radius: var(--rad-md);
  overflow: hidden;
  margin-bottom: var(--sp-unit-3);
  width: fit-content;
}

.account-tab {
  padding: 6px 16px;
  font-size: var(--fs-callout);
  font-weight: var(--fw-medium);
  color: var(--clr-secondary);
  background: transparent;
  border: none;
  cursor: pointer;
  font-family: inherit;
  transition: var(--tr-fast);
}
.account-tab + .account-tab {
  border-left: 1px solid var(--clr-separator);
}
.account-tab:hover {
  color: var(--clr-label);
  background: var(--clr-fill);
}
.account-tab.is-active {
  background: var(--clr-accent);
  color: #fff;
}

.account-panel {
  /* panel content */
}

/* 技能标签 */
.expertise-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  background: var(--clr-accent-soft);
  color: var(--clr-accent);
  border-radius: var(--rad-sm);
  font-size: var(--fs-footnote);
}
.expertise-tag__remove {
  border: none;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
  padding: 0;
}
.expertise-tag__remove:hover {
  opacity: 0.7;
}

/* 主题选择 */
.theme-options {
  display: flex;
  gap: 8px;
}

.theme-option {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border: none;
  border-radius: var(--rad-md);
  cursor: pointer;
  font-size: var(--fs-callout);
  color: var(--clr-secondary);
  background: var(--clr-fill);
  transition: var(--tr-fast);
}

.theme-option:hover {
  background: var(--clr-fill-hover);
  color: var(--clr-label);
}

.theme-option.is-active {
  background: var(--clr-accent);
  color: #fff;
}

.theme-option input {
  display: none;
}

/* 外观偏好 — 颜色选择器 */
.color-pick-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.color-pick-row input[type="color"] {
  width: 32px;
  height: 32px;
  border: 1px solid var(--clr-separator);
  border-radius: var(--rad-sm);
  padding: 2px;
  cursor: pointer;
  background: transparent;
}
.color-pick-val {
  font-size: var(--fs-footnote);
  color: var(--clr-secondary);
  font-family: var(--ff-mono);
}

/* 字体浏览器 */
.font-current-preview {
  display: flex;
  align-items: center;
  gap: 8px;
}
.font-current-name {
  font-size: var(--fs-callout);
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 0 4px;
}

.font-browser {
  border: 1px solid var(--clr-separator-light);
  border-radius: var(--rad-md);
  overflow: hidden;
  width: 360px;
}
.font-browser-search {
  padding: 8px;
  border-bottom: 1px solid var(--clr-hairline);
}
.font-browser-search .input {
  width: 100%;
}
.font-browser-list {
  max-height: 260px;
  overflow-y: auto;
}
.font-browser-item {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  cursor: pointer;
  transition: var(--tr-fast);
}
.font-browser-item:hover {
  background: var(--clr-fill);
}
.font-browser-item.is-selected {
  background: var(--clr-accent-soft);
}
.font-browser-item-preview {
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.font-browser-empty,
.font-browser-loading {
  padding: 24px;
  text-align: center;
  font-size: var(--fs-footnote);
  color: var(--clr-tertiary);
}

/* LLM 提供者列表 + 详情 */
.settings-panel-llm {
  display: flex;
  gap: 0;
  height: 100%;
  margin: -20px;
}

.llm-sidebar {
  width: 200px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border-right: 0.5px solid var(--clr-hairline);
}

.llm-sidebar__header {
  padding: 12px 14px;
  font-size: var(--fs-footnote);
  font-weight: var(--fw-semibold);
  color: var(--clr-secondary);
  text-transform: uppercase;
  letter-spacing: 0.03em;
  border-bottom: 1px solid var(--clr-hairline);
}

.llm-sidebar__list {
  flex: 1;
  overflow-y: auto;
  padding: 6px;
}

.llm-sidebar__item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  padding: 10px 12px;
  text-align: left;
  background: transparent;
  border: none;
  border-radius: var(--rad-md);
  cursor: pointer;
  font-family: inherit;
  transition: var(--tr-fast);
}

.llm-sidebar__item:hover {
  background: var(--clr-fill-hover);
}

.llm-sidebar__item.is-active {
  background: var(--clr-accent-soft);
}

.llm-sidebar__name {
  padding-right: 10px !important;
  font-size: var(--fs-callout);
  color: var(--clr-label);
  font-weight: var(--fw-medium);
}

.llm-sidebar__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}

.llm-sidebar__toggle {
  display: flex;
  align-items: center;
  cursor: pointer;
  flex-shrink: 0;
  line-height: 0;
}

.llm-sidebar__actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
}

.llm-sidebar__action-btn {
  opacity: 0;
  transition: opacity var(--tr-fast);
}

.llm-sidebar__item:hover .llm-sidebar__action-btn {
  opacity: 1;
}

.llm-sidebar__star {
  opacity: 1;
}

.llm-sidebar__action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: none;
  border-radius: var(--rad-sm);
  background: transparent;
  color: var(--clr-tertiary);
  cursor: pointer;
  transition: all var(--tr-fast);
  flex-shrink: 0;
}

.llm-sidebar__action-btn:hover {
  background: var(--clr-fill-hover);
  color: var(--clr-label);
}

.llm-sidebar__action-btn:disabled {
  opacity: 0.25;
  cursor: not-allowed;
}

.llm-sidebar__set-default {
  width: auto;
  padding: 2px 6px;
  font-size: var(--fs-footnote);
  color: var(--clr-tertiary);
}

.llm-sidebar__set-default:hover {
  color: var(--clr-label);
  background: var(--clr-fill-hover);
}

.llm-sidebar__mcp-status {
  width: auto;
  padding: 1px 5px;
  border: 1px dashed var(--clr-separator-light);
  border-radius: 4px;
  font-size: var(--fs-caption);
  color: var(--clr-tertiary);
  opacity: 1;
}

.llm-sidebar__mcp-status.is-active {
  border-color: var(--clr-accent);
  background: var(--clr-accent-soft);
  color: var(--clr-accent);
}

.llm-default-badge {
  padding: 1px 5px;
  border: 1px dashed var(--clr-accent);
  border-radius: 4px;
  background: var(--clr-accent-soft);
  color: var(--clr-accent);
  font-size: var(--fs-caption);
  flex-shrink: 0;
}

.llm-sidebar__delete:hover {
  color: var(--clr-danger);
  background: var(--clr-danger-soft);
}

.llm-sidebar__model {
  font-size: var(--fs-footnote);
  color: var(--clr-tertiary);
}

.llm-sidebar__empty {
  padding: 20px 12px;
  font-size: var(--fs-footnote);
  color: var(--clr-tertiary);
  text-align: center;
}

.llm-add-btn {
  margin: 8px;
}

.llm-detail {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  min-width: 0;
}

.llm-detail__form {
  max-width: 480px;
}

.llm-detail__actions {
  display: flex;
  gap: 8px;
  margin-top: 20px;
}

.llm-test-result-line {
  max-height: 48px;
  overflow-y: auto;
  padding: 6px 10px;
  margin-bottom: 8px;
  border-radius: var(--rad-sm);
  font-size: var(--fs-footnote);
  line-height: 1.4;
}
.llm-test-result-line.is-ok {
  background: #dcfce7;
  color: #166534;
}
.llm-test-result-line.is-err {
  background: #fee2e2;
  color: #991b1b;
}
.mcp-tool-panel { max-height: 280px; overflow-y: auto; border: 1px solid var(--clr-border); border-radius: 6px; }
.mcp-tool-header { position: sticky; top: 0; z-index: 2; padding: 8px 12px; background: var(--clr-bg); color: var(--clr-primary); font-weight: 600; font-size: var(--fs-callout); border-bottom: 2px solid var(--clr-border); }
.mcp-tool-item { padding: 0 12px 8px; }
.mcp-tool-name { position: sticky; top: 37px; z-index: 1; padding: 8px 0 4px; background: var(--clr-bg); font-weight: 500; color: var(--clr-primary); border-bottom: 1px solid var(--clr-border); }
.mcp-tool-desc { color: var(--clr-secondary); font-size: var(--fs-callout); line-height: 1.4; padding-top: 4px; }
.llm-ctxlen { font-size: var(--fs-callout); color: var(--clr-secondary); padding: 6px 0; }

.llm-model-row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}
.llm-model-row .select { flex: 1; }
.llm-model-row .input { flex: 1; }
.llm-model-row .btn { white-space: nowrap; }

.capability-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.capability-tag {
  padding: 3px 10px;
  border: 1px dashed var(--clr-separator-light);
  border-radius: 6px;
  background: transparent;
  color: var(--clr-tertiary);
  font-size: var(--fs-footnote);
  cursor: pointer;
  transition: var(--tr-fast);
}
.capability-tag:hover {
  border-color: var(--clr-accent-border);
  color: var(--clr-label);
}
.capability-tag.is-active {
  border-color: var(--clr-accent);
  background: var(--clr-accent-soft);
  color: var(--clr-accent);
}

.llm-test-result-line {
  max-height: 48px;
  overflow-y: auto;
  padding: 6px 10px;
  margin-bottom: 8px;
  border-radius: var(--rad-sm);
  font-size: var(--fs-footnote);
  line-height: 1.4;
}
.llm-test-result-line.is-ok {
  background: #dcfce7;
  color: #166534;
}
.llm-test-result-line.is-err {
  background: #fee2e2;
  color: #991b1b;
}
.mcp-tool-panel { max-height: 280px; overflow-y: auto; border: 1px solid var(--clr-border); border-radius: 6px; }
.mcp-tool-header { position: sticky; top: 0; z-index: 2; padding: 8px 12px; background: var(--clr-bg); color: var(--clr-primary); font-weight: 600; font-size: var(--fs-callout); border-bottom: 2px solid var(--clr-border); }
.mcp-tool-item { padding: 0 12px 8px; }
.mcp-tool-name { position: sticky; top: 37px; z-index: 1; padding: 8px 0 4px; background: var(--clr-bg); font-weight: 500; color: var(--clr-primary); border-bottom: 1px solid var(--clr-border); }
.mcp-tool-desc { color: var(--clr-secondary); font-size: var(--fs-callout); line-height: 1.4; padding-top: 4px; }
.llm-ctxlen { font-size: var(--fs-callout); color: var(--clr-secondary); padding: 6px 0; }

.llm-model-row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}
.llm-model-row .select { flex: 1; }
.llm-model-row .input { flex: 1; }
.llm-model-row .btn { white-space: nowrap; }

.checkbox-row {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  font-size: var(--fs-callout);
  color: var(--clr-label);
  user-select: none;
}
.checkbox-row input[type="checkbox"] {
  width: 16px;
  height: 16px;
  accent-color: var(--clr-accent);
  cursor: pointer;
}

.llm-detail__empty {
  padding: 40px 0;
  color: var(--clr-tertiary);
  font-size: var(--fs-callout);
  text-align: center;
}

/* 模型服务配置 */
.model-config-group {
  margin-bottom: 20px;
  padding-bottom: 20px;
  border-bottom: 1px solid var(--clr-hairline);
}
.model-config-group:last-child {
  border-bottom: none;
}
.model-config__title {
  font-size: var(--fs-headline);
  font-weight: var(--fw-semibold);
  color: var(--clr-label);
  margin: 0 0 12px;
}
.settings-panel__desc {
  font-size: var(--fs-footnote);
  color: var(--clr-secondary);
  margin: 0 0 16px;
}

/* 占位内容 */
.placeholder-content {
  padding: 40px 0;
  color: var(--clr-tertiary);
  font-size: var(--fs-callout);
}








.form-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}







/* 服务协议 */
.agreement-content {
  font-size: var(--fs-callout);
  color: var(--clr-label);
  line-height: 1.8;
}
.agreement-content :deep(h1) {
  font-size: 1.3em;
  font-weight: var(--fw-semibold);
  margin: 20px 0 12px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--clr-hairline);
}
.agreement-content :deep(h2) {
  font-size: 1.1em;
  font-weight: var(--fw-semibold);
  margin: 16px 0 8px;
}
.agreement-content :deep(h3) {
  font-size: 1em;
  font-weight: var(--fw-medium);
  margin: 12px 0 6px;
}
.agreement-content :deep(strong) {
  font-weight: var(--fw-semibold);
}
.agreement-content :deep(ul) {
  margin: 6px 0;
  padding-left: 20px;
}
.agreement-content :deep(li) {
  margin-bottom: 4px;
}
.agreement-content :deep(hr) {
  border: none;
  border-top: 1px solid var(--clr-hairline);
  margin: 20px 0;
}
.agreement-content :deep(em) {
  font-style: normal;
  color: var(--clr-secondary);
}

@keyframes fade-in { from { opacity: 0; } to { opacity: 1; } }
@keyframes slide-up { from { opacity: 0; transform: translateY(12px); } to { opacity: 1; transform: translateY(0); } }

/* 切换开关 */
.toggle-row {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  user-select: none;
}
.toggle-row__input {
  display: none;
}
.toggle-row__switch {
  position: relative;
  width: 36px;
  height: 20px;
  background: var(--border-color, #ccc);
  border-radius: 10px;
  transition: background 0.2s;
  flex-shrink: 0;
}
.toggle-row__switch::after {
  content: '';
  position: absolute;
  top: 2px;
  left: 2px;
  width: 16px;
  height: 16px;
  background: #fff;
  border-radius: 50%;
  transition: transform 0.2s;
}
.toggle-row__input:checked + .toggle-row__switch {
  background: var(--primary, #4f6ef7);
}
.toggle-row__input:checked + .toggle-row__switch::after {
  transform: translateX(16px);
}
.toggle-row__text {
  font-size: 13px;
  color: var(--text2);
}
</style>
