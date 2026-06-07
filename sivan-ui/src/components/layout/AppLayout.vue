<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { useI18n } from '../../utils/i18n'
import SystemSettingsModal from '../common/SystemSettingsModal.vue'
import {
  MessageSquare, GitBranch, Bot, Zap, Library, BrainCircuit,
  Sparkles, BarChart3, Share2, PanelRightClose, PanelLeftClose, Target,
} from '@lucide/vue'

interface MenuItem {
  labelKey: string
  key: string
  icon: string // Lucide component name
}

interface MenuGroup {
  labelKey: string
  items: MenuItem[]
}

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const { t } = useI18n()

const collapsed = ref(localStorage.getItem('sider-collapsed') === 'true')
const showSettings = ref(false)

function toggleCollapsed() {
  collapsed.value = !collapsed.value
  localStorage.setItem('sider-collapsed', String(collapsed.value))
}

const activeKey = computed(() => route.path)

const iconMap: Record<string, any> = {
  MessageSquare, GitBranch, Bot, Zap, Library, BrainCircuit,
  Sparkles, BarChart3, Share2, Target,
}

const menuGroups: MenuGroup[] = [
  {
    labelKey: 'navGroupComm',
    items: [
      { labelKey: 'navConversations', key: '/conversations', icon: 'MessageSquare' },
      { labelKey: 'navSquads', key: '/squads', icon: 'GitBranch' },
    ],
  },
  {
    labelKey: 'navGroupAssets',
    items: [
      { labelKey: 'navAgents', key: '/agents', icon: 'Bot' },
      { labelKey: 'navSkills', key: '/skills', icon: 'Zap' },
      { labelKey: 'navKnowledgeBases', key: '/knowledge-bases', icon: 'Library' },
      { labelKey: 'navMemories', key: '/memories', icon: 'BrainCircuit' },
      { labelKey: 'navPatterns', key: '/patterns', icon: 'Sparkles' },
    ],
  },
  {
    labelKey: 'navGroupMonitor',
    items: [
      { labelKey: 'navTokenUsage', key: '/token-usage', icon: 'BarChart3' },
      { labelKey: 'navRouting', key: '/routing-decisions', icon: 'Share2' },
    ],
  },
]

function handleMenuClick(key: string) {
  router.push(key)
}
</script>

<template>
  <div class="app-shell">
    <div class="app-layout">
      <!-- 侧栏 -->
      <aside class="app-sider" :class="{ 'is-collapsed': collapsed }">
        <!-- 品牌区 -->
        <div class="app-sider__brand">
          <span class="app-sider__logo">{{ t('appName') }}</span>
          <button class="sider-toggle" :title="collapsed ? t('expandSider') : t('collapseSider')" @click="toggleCollapsed">
            <PanelRightClose v-if="!collapsed" :size="18" stroke-width="1.5" />
            <PanelLeftClose v-else :size="18" stroke-width="1.5" />
          </button>
        </div>

        <!-- 菜单（分组） -->
        <nav class="app-sider__nav">
          <template v-for="(group, gi) in menuGroups" :key="group.labelKey">
            <div v-if="!collapsed" class="nav-group-label">{{ t(group.labelKey) }}</div>
            <a
              v-for="item in group.items"
              :key="item.key"
              :class="['nav-item', { 'is-active': activeKey.startsWith(item.key) }]"
              :title="collapsed ? t(item.labelKey) : undefined"
              @click="handleMenuClick(item.key)"
            >
              <span class="nav-item__icon">
                <component :is="iconMap[item.icon]" :size="20" stroke-width="1.5" />
              </span>
              <span class="nav-item__label">{{ t(item.labelKey) }}</span>
            </a>
            <!-- 分组间分割线 -->
            <div v-if="gi < menuGroups.length - 1" class="nav-group-sep" />
          </template>
        </nav>

        <!-- 用户区 -->
        <div class="app-sider__user">
          <div class="user-trigger">
            <div class="user-avatar">{{ auth.displayName?.[0] || '?' }}</div>
            <span class="user-name">{{ auth.displayName }}</span>
          </div>
          <button class="settings-btn" :title="collapsed ? t('systemSettings') : undefined" :aria-label="t('systemSettings')" @click.stop="showSettings = true">
            <svg viewBox="0 0 20 20" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round">
              <path d="M10 13a3 3 0 100-6 3 3 0 000 6z" />
              <path d="M17.4 10a7.4 7.4 0 01-.14 1.4l1.5 1.17a.36.36 0 01.08.48l-1.42 2.46a.36.36 0 01-.46.16l-1.77-.71a5.73 5.73 0 01-1.21.7l-.27 1.9a.36.36 0 01-.35.29H8.64a.36.36 0 01-.35-.29l-.27-1.9a5.73 5.73 0 01-1.21-.7l-1.77.71a.36.36 0 01-.46-.16l-1.42-2.46a.36.36 0 01.08-.48l1.5-1.17A7.4 7.4 0 014.6 10c0-.47.04-.94.14-1.4L3.24 7.43a.36.36 0 01-.08-.48l1.42-2.46a.36.36 0 01.46-.16l1.77.71a5.73 5.73 0 011.21-.7l.27-1.9a.36.36 0 01.35-.29h2.72a.36.36 0 01.35.29l.27 1.9a5.73 5.73 0 011.21.7l1.77-.71a.36.36 0 01.46.16l1.42 2.46a.36.36 0 01-.08.48l-1.5 1.17c.1.46.14.93.14 1.4z" />
            </svg>
          </button>
        </div>
      </aside>

      <!-- 内容区 -->
      <div class="app-content">
        <RouterView />
      </div>
    </div>

    <!-- 系统设置 -->
    <SystemSettingsModal v-if="showSettings" @close="showSettings = false" />
  </div>
</template>

<style scoped>
/* ── 外壳：留出视口边距，页面背景 ── */
.app-shell {
  height: 100vh;
  padding: 8px;
  background: var(--clr-bg-page);
  box-sizing: border-box;
}

/* ── 主布局：16px 统一圆角窗口容器 ── */
.app-layout {
  display: flex;
  height: 100%;
  min-width: 890px;
  border-radius: var(--rad-lg);
  overflow: hidden;
  box-shadow: var(--shd-window);
}

/* ── 侧栏 ── */
.app-sider {
  width: 220px;
  flex-shrink: 0;
  display: flex;
  border-right: 1px solid var(--clr-separator-light);
  flex-direction: column;
  background: var(--clr-bg-sidebar);
  overflow: hidden;
  transition: width var(--dur-normal) var(--ease-apple);
}

.app-sider.is-collapsed {
  width: 56px;
}

/* ── 品牌区 ── */
.app-sider__brand {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px var(--sp-unit-2);
  border-bottom: 1px solid var(--clr-hairline);
  min-height: 48px;
}

.app-sider__logo {
  font-weight: var(--fw-bold);
  font-size: var(--fs-title-3);
  color: var(--clr-accent);
  letter-spacing: var(--ls-title);
  overflow: hidden;
  white-space: nowrap;
  opacity: 1;
  transition: opacity var(--dur-fast) var(--ease-out);
  transition-delay: 0.1s;
}

.app-sider.is-collapsed .app-sider__brand {
  justify-content: center;
}
.app-sider.is-collapsed .app-sider__logo {
  display: none;
}

.sider-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border: 1px solid var(--clr-hairline);
  border-radius: var(--rad-md);
  background: transparent;
  color: var(--clr-tertiary);
  cursor: pointer;
  transition: var(--tr-fast);
  flex-shrink: 0;
}
.sider-toggle:hover {
  background: var(--clr-fill-hover);
  color: var(--clr-label);
  border-color: var(--clr-separator-light);
}

/* ── 导航菜单 ── */
.app-sider__nav {
  flex: 1;
  overflow-y: auto;
  padding: var(--sp-unit-1) 0;
}

.nav-group-label {
  padding: var(--sp-unit-1) var(--sp-unit-2) 2px;
  font-size: var(--fs-caption);
  font-weight: var(--fw-semibold);
  color: var(--clr-quaternary);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  opacity: 1;
  transition: opacity var(--dur-fast) var(--ease-out);
}

.app-sider.is-collapsed .nav-group-label {
  opacity: 0;
}

.nav-group-sep {
  height: 1px;
  margin: 6px 16px;
  background: var(--clr-hairline);
  transition: opacity var(--dur-fast) var(--ease-out);
}

.app-sider.is-collapsed .nav-group-sep {
  margin: 6px 10px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 14px;
  margin: 1px 10px;
  border-radius: var(--rad-md);
  color: var(--clr-secondary);
  cursor: pointer;
  transition: var(--tr-fast);
  text-decoration: none;
  position: relative;
}
.nav-item:hover {
  background: var(--clr-fill-hover);
  color: var(--clr-label);
}
.nav-item.is-active {
  background: var(--clr-selected);
  color: var(--clr-accent);
}

.app-sider.is-collapsed .nav-item {
  padding: 9px 0;
  margin: 1px 8px;
  justify-content: center;
  gap: 0;
}

.nav-item__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 20px;
  height: 20px;
}

.nav-item__label {
  font-size: var(--fs-callout);
  font-weight: var(--fw-medium);
  white-space: nowrap;
  overflow: hidden;
  opacity: 1;
  transition: opacity var(--dur-fast) var(--ease-out);
  transition-delay: 0.1s;
}

.app-sider.is-collapsed .nav-item__label {
  display: none;
}

/* ── 用户区 ── */
.app-sider__user {
  padding: var(--sp-unit-1);
  border-top: 1px solid var(--clr-hairline);
  flex-shrink: 0;
  position: relative;
  display: flex;
  align-items: center;
  gap: 4px;
}

.app-sider.is-collapsed .app-sider__user {
  justify-content: center;
  padding: var(--sp-unit-1);
}

.user-trigger {
  display: flex;
  align-items: center;
  gap: var(--sp-unit-1);
  padding: 4px;
  border-radius: var(--rad-md);
  flex: 1;
  min-width: 0;
}

.app-sider.is-collapsed .user-trigger {
  display: none;
}

.user-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--clr-accent);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--fs-footnote);
  font-weight: var(--fw-semibold);
  flex-shrink: 0;
}

.user-name {
  font-size: var(--fs-callout);
  color: var(--clr-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  opacity: 1;
  transition: opacity var(--dur-fast) var(--ease-out);
  transition-delay: 0.1s;
}

.app-sider.is-collapsed .user-name {
  display: none;
}

.settings-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border: none;
  background: transparent;
  color: var(--clr-tertiary);
  cursor: pointer;
  border-radius: var(--rad-md);
  transition: var(--tr-fast);
  flex-shrink: 0;
}
.settings-btn:hover {
  background: var(--clr-fill-hover);
  color: var(--clr-label);
}

/* ── 内容区 ── */
.app-content {
  flex: 1;
  overflow-x: auto;
  overflow-y: hidden;
  display: flex;
  flex-direction: column;
  background: var(--clr-bg);
}
</style>
