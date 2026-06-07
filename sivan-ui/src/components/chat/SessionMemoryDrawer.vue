<script setup lang="ts">
import { useI18n } from '../../utils/i18n'
const { t } = useI18n()

defineProps<{
  memories: any[]
  loading: boolean
}>()

defineEmits<{ close: [] }>()
</script>

<template>
  <Teleport to="body">
    <div class="drawer-overlay" @click.self="$emit('close')">
      <div class="drawer" @click.stop>
        <div class="drawer__header">
          <span class="session-memory__title">{{ t('sessionMemory') }}</span>
          <span class="session-memory__count">{{ t('memoryCount', { n: memories.length }) }}</span>
          <button class="drawer__close" @click="$emit('close')">&times;</button>
        </div>
        <div class="drawer__body">
          <div class="session-memory-section">
            <div v-if="loading" class="session-memory__loading">{{ t('loading') }}</div>
            <div v-else-if="memories.length === 0" class="session-memory__empty">
              {{ t('noSessionMemory') }}
            </div>
            <div v-else class="session-memory__list">
              <div v-for="m in memories" :key="m.memoryId" class="session-memory__item">
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
.session-memory-section {
  flex: 1;
  overflow-y: auto;
}
.session-memory__title {
  font-size: var(--fs-callout);
  font-weight: var(--fw-semibold);
  color: var(--clr-label);
}
.session-memory__count {
  font-size: var(--fs-footnote);
  color: var(--clr-tertiary);
}
.session-memory__loading,
.session-memory__empty {
  font-size: var(--fs-footnote);
  color: var(--clr-quaternary);
  padding: 8px 0;
  text-align: center;
}
.session-memory__list {
  display: flex;
  flex-direction: column;
  gap: 6px;
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
