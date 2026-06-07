<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from '../../utils/i18n'
import { openProjectDirectory } from '../../api/group'
const { t } = useI18n()

const props = defineProps<{
  groupId: string
  groupLocalPath: string
  groupShortId: string
  groupKbNames: string[]
  knowledgeBases: { kbName: string; description?: string }[]
  projectMemories: any[]
  loadingMemories: boolean
}>()

const emit = defineEmits<{
  close: []
  'update:groupKbNames': [value: string[]]
  saveGroupSettings: []
}>()

const openingDir = ref(false)
async function handleOpenDir() {
  if (openingDir.value || !props.groupLocalPath) return
  openingDir.value = true
  try {
    await openProjectDirectory(props.groupId)
  } catch { /* ignore */ }
  finally { openingDir.value = false }
}

function onKbToggle(kbName: string) {
  const next = props.groupKbNames.includes(kbName)
    ? props.groupKbNames.filter(n => n !== kbName)
    : [...props.groupKbNames, kbName]
  emit('update:groupKbNames', next)
}
</script>

<template>
  <Teleport to="body">
    <div class="drawer-overlay" @click.self="$emit('close')">
      <div class="drawer" @click.stop>
        <div class="drawer__header">
          <span>{{ t('projectSettings') }}</span>
          <button class="drawer__close" @click="$emit('close')">&times;</button>
        </div>
        <div class="drawer__body">
          <!-- 短标识符 -->
          <div class="form-group">
            <label class="form-label">{{ t('projectShortId') }}</label>
            <div class="readonly-field">{{ groupShortId || '-' }}</div>
          </div>
          <!-- 工作目录 -->
          <div class="form-group" style="margin-top:12px">
            <label class="form-label">{{ t('workDir') }}</label>
            <div
              class="readonly-field"
              :class="{ 'is-clickable': !!groupLocalPath }"
              :title="groupLocalPath ? t('openInFinder') : ''"
              @click="handleOpenDir"
            >
              <span>{{ groupLocalPath || t('noLocalPath') }}</span>
              <svg v-if="groupLocalPath && !openingDir" class="open-icon" viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.3">
                <path d="M2 4h4l2 2h6v8H2V4z" />
                <path d="M8 11l3-3-3-3M5 8h6" />
              </svg>
              <span v-if="openingDir" class="open-icon">{{ t('opening') }}</span>
            </div>
            <p class="group-toggle-desc">{{ t('workDirDesc') }}</p>
          </div>

          <!-- 知识库绑定 -->
          <div class="form-group" style="margin-top:20px">
            <label class="form-label">{{ t('bindKb') }}</label>
            <div v-if="knowledgeBases.length" class="group-kb-list">
              <label v-for="kb in knowledgeBases" :key="kb.kbName" class="group-kb-check">
                <input type="checkbox" :checked="groupKbNames.includes(kb.kbName)" @change="onKbToggle(kb.kbName)" />
                <span>{{ kb.kbName }}</span>
              </label>
            </div>
            <div v-else class="form-hint">{{ t('noAvailableKb') }}</div>
          </div>

          <!-- 项目记忆 -->
          <div class="form-group" style="margin-top:24px">
            <label class="form-label">{{ t('projectMemoryCount', { n: projectMemories.length }) }}</label>
            <div v-if="loadingMemories" class="form-hint">{{ t('loading') }}</div>
            <div v-else-if="projectMemories.length === 0" class="form-hint">{{ t('noProjectMemory') }}</div>
            <div v-else class="project-memory-list">
              <div v-for="m in projectMemories" :key="m.memoryId" class="session-memory__item">
                <div class="session-memory__content">{{ m.content }}</div>
                <div class="session-memory__meta">
                  <span v-if="m.important" class="session-memory__tag session-memory__tag--important">{{ t('important') }}</span>
                  <span v-if="m.archived" class="session-memory__tag session-memory__tag--archived">{{ t('archived') }}</span>
                  <span v-if="m.retention != null" class="session-memory__tag">{{ t('retention', { n: (m.retention * 100).toFixed(0) }) }}</span>
                  <span v-if="m.summary" class="session-memory__tag">{{ m.summary }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.drawer-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.2);
  z-index: var(--z-drawer);
  animation: fade-in 0.15s ease;
}
.drawer {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  width: 360px;
  max-width: 90vw;
  background: var(--clr-bg);
  border-left: 1px solid var(--clr-separator-light);
  box-shadow: var(--shd-modal);
  display: flex;
  flex-direction: column;
  animation: drawer-slide-in 0.2s ease;
}
@keyframes drawer-slide-in {
  from { transform: translateX(100%); }
  to { transform: translateX(0); }
}
.drawer__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--clr-hairline);
  font-size: var(--fs-headline);
  font-weight: var(--fw-semibold);
  flex-shrink: 0;
}
.drawer__close {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: var(--clr-tertiary);
  cursor: pointer;
  border-radius: var(--rad-md);
  font-size: 18px;
  font-family: inherit;
}
.drawer__close:hover {
  background: var(--clr-fill);
  color: var(--clr-label);
}
.drawer__body {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}
.group-kb-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 240px;
  overflow-y: auto;
  border: 1px solid var(--clr-separator-light);
  border-radius: var(--rad-md);
  padding: 6px;
}
.group-kb-check {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: var(--rad-sm);
  cursor: pointer;
  font-size: var(--fs-callout);
  color: var(--clr-label);
  transition: background var(--dur-fast) var(--ease-out);
}
.group-kb-check:hover {
  background: var(--clr-fill);
}
.group-kb-check input {
  accent-color: var(--clr-accent);
}
.readonly-field {
  padding: 8px 12px;
  background: var(--clr-bg-secondary);
  border-radius: var(--rad-md);
  font-size: var(--fs-callout);
  color: var(--clr-secondary);
  word-break: break-all;
  line-height: var(--lh-body);
}
.readonly-field.is-clickable {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease-out);
}
.readonly-field.is-clickable:hover {
  background: var(--clr-accent-soft);
}
.open-icon {
  flex-shrink: 0;
  color: var(--clr-accent);
  font-size: var(--fs-footnote);
}
.group-toggle-desc {
  margin-top: 8px;
  font-size: var(--fs-footnote);
  color: var(--clr-tertiary);
  line-height: var(--lh-body);
}
.form-hint {
  font-size: var(--fs-footnote);
  color: var(--clr-tertiary);
  padding: 8px 0;
}
.project-memory-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 280px;
  overflow-y: auto;
  margin-top: 4px;
}
.session-memory__item {
  padding: 8px 10px;
  background: var(--clr-bg-secondary);
  border-radius: var(--rad-md);
  border-left: 3px solid var(--clr-accent);
}
.session-memory__content {
  font-size: var(--fs-footnote);
  color: var(--clr-secondary);
  line-height: var(--lh-body);
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
}
.session-memory__meta {
  display: flex;
  gap: 4px;
  margin-top: 4px;
  flex-wrap: wrap;
}
.session-memory__tag {
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 3px;
  background: var(--clr-accent-soft);
  color: var(--clr-accent);
}
.session-memory__tag--important {
  background: rgba(255, 149, 0, 0.12);
  color: var(--clr-orange);
}
.session-memory__tag--archived {
  background: var(--clr-fill);
  color: var(--clr-tertiary);
}
</style>
