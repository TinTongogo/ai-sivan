<script setup lang="ts">
import { ref, computed } from 'vue'
import { renderMarkdown } from '../../utils/markdown'
import { useI18n } from '../../utils/i18n'
import type { SectionPhase } from '../../stores/orchestration'

const { t } = useI18n()

const props = defineProps<{
  phase: SectionPhase
  /** 是否为实时阶段（流式），展开思考区 */
  live?: boolean
}>()

const emit = defineEmits<{
  copy: [text: string]
  saveToKb: [text: string]
}>()

/** 思考区展开/折叠 */
const thinkingOpen = ref(props.live || false)

const hasThinking = computed(() => !!props.phase.thinking)
const hasContent = computed(() => !!props.phase.content)
const hasChildren = computed(() => !!props.phase.children?.length)
const isLeaf = computed(() => !hasChildren.value)

const statusIcon = computed(() => {
  switch (props.phase.status) {
    case 'COMPLETED': return '✅'
    case 'RUNNING': return '●'
    case 'FAILED': return '❌'
    case 'CANCELLED': return '⛔'
    default: return '○'
  }
})

const statusCls = computed(() => {
  switch (props.phase.status) {
    case 'COMPLETED': return 'phase-status--completed'
    case 'RUNNING': return 'phase-status--running'
    case 'FAILED': return 'phase-status--failed'
    default: return 'phase-status--pending'
  }
})

function copyContent(text: string) {
  navigator.clipboard.writeText(text)
  emit('copy', text)
}
</script>

<template>
  <div class="phase-card" :class="[statusCls, { 'phase-card--live': live }]">
    <!-- 阶段头部 -->
    <div class="phase-card__header">
      <span class="phase-card__icon">{{ statusIcon }}</span>
      <span class="phase-card__name">{{ phase.phase }}</span>
      <span v-if="phase.agent" class="phase-card__agent">{{ phase.agent }}</span>
      <span v-if="phase.durationMs" class="phase-card__meta">{{ (phase.durationMs / 1000).toFixed(1) }}s</span>
      <span v-if="phase.tokens" class="phase-card__meta">{{ phase.tokens }} tok</span>
      <span v-if="phase.mode" class="phase-card__mode">{{ phase.mode }}</span>
    </div>

    <!-- 叶子阶段：显示思考 + 内容 -->
    <template v-if="isLeaf">
      <!-- 思考区 -->
      <div v-if="hasThinking" class="phase-card__thinking">
        <button class="phase-thinking-toggle" @click="thinkingOpen = !thinkingOpen">
          <span class="toggle__chevron" :class="{ 'is-open': thinkingOpen }">▸</span>
          <span>{{ thinkingOpen ? t('hideThinking') : t('thinking') }}</span>
        </button>
        <div v-show="thinkingOpen" class="phase-thinking-content" v-html="renderMarkdown(phase.thinking || '')"></div>
      </div>

      <!-- 内容区 -->
      <div v-if="hasContent" class="phase-card__content" v-html="renderMarkdown(phase.content || '')"></div>

      <!-- 工具摘要 -->
      <div v-if="phase.toolSummary?.length" class="phase-card__tools">
        <span v-for="(tool, idx) in phase.toolSummary" :key="idx" class="tool-badge" :class="'tool-badge--' + tool.status">
          🔧 {{ tool.name }} × {{ tool.count }}
        </span>
      </div>
    </template>

    <!-- 非叶子阶段：递归显示子阶段 -->
    <template v-else>
      <div v-if="hasChildren" class="phase-card__children">
        <PhaseCard
          v-for="(child, idx) in phase.children"
          :key="idx"
          :phase="child"
          :live="live && child.status === 'RUNNING'"
          @copy="(text) => emit('copy', text)"
          @save-to-kb="(text) => emit('saveToKb', text)"
        />
      </div>
    </template>
  </div>
</template>

<style scoped>
.phase-card {
  border: 1px solid var(--clr-hairline);
  border-radius: var(--rad-md);
  margin-bottom: 6px;
  overflow: hidden;
  transition: border-color 0.2s ease;
}
.phase-card--live {
  border-color: var(--clr-accent);
  box-shadow: 0 0 0 1px var(--clr-accent-soft);
}
.phase-status--running .phase-card__icon { color: var(--clr-accent); animation: pulse 1.5s infinite; }
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.4; } }
.phase-status--completed .phase-card__icon { color: var(--clr-success); }
.phase-status--failed .phase-card__icon { color: var(--clr-danger); }

.phase-card__header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  background: var(--clr-fill);
  font-size: var(--fs-footnote);
  flex-wrap: wrap;
}
.phase-card__name { font-weight: var(--fw-semibold); color: var(--clr-label); }
.phase-card__agent {
  color: var(--clr-accent);
  font-size: var(--fs-caption);
  background: var(--clr-accent-soft);
  padding: 0 6px;
  border-radius: 4px;
}
.phase-card__meta { color: var(--clr-tertiary); font-size: var(--fs-caption); }
.phase-card__mode {
  color: var(--clr-secondary);
  font-size: var(--fs-caption);
  font-family: var(--ff-mono);
  background: var(--clr-bg-secondary);
  padding: 0 6px;
  border-radius: 4px;
}

.phase-card__thinking { border-top: 1px solid var(--clr-hairline); }
.phase-thinking-toggle {
  display: flex; align-items: center; gap: 4px;
  padding: 4px 10px; width: 100%;
  border: none; background: transparent;
  color: var(--clr-quaternary); cursor: pointer;
  font-size: var(--fs-caption); font-family: inherit;
}
.phase-thinking-toggle:hover { color: var(--clr-secondary); background: var(--clr-fill); }
.toggle__chevron { transition: transform 0.15s ease; display: inline-block; }
.toggle__chevron.is-open { transform: rotate(90deg); }
.phase-thinking-content {
  padding: 6px 10px 8px;
  font-size: var(--fs-footnote);
  color: var(--clr-secondary);
  background: var(--clr-bg-tertiary);
  line-height: 1.5;
  border-left: 3px solid var(--clr-accent-border);
  margin: 0 8px 6px;
  border-radius: 4px;
}

.phase-card__content {
  padding: 6px 10px 8px;
  font-size: var(--fs-callout);
  color: var(--clr-label);
  line-height: 1.6;
  white-space: pre-wrap;
}

.phase-card__tools {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
  padding: 4px 10px 6px;
}
.tool-badge {
  font-size: var(--fs-caption);
  padding: 1px 8px;
  border-radius: 4px;
  background: var(--clr-fill);
  color: var(--clr-secondary);
}
.tool-badge--failed { color: var(--clr-danger); }
.tool-badge--partial { color: #d97706; }

.phase-card__children {
  padding: 4px 4px 4px 12px;
}
</style>
