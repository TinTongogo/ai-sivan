<script setup lang="ts">
import { ref, computed } from 'vue'
import api from '../../api'
import { useMessage } from '../../utils/message'
import { useI18n } from '../../utils/i18n'

const { t } = useI18n()
const message = useMessage()

const props = defineProps<{
  text: string
  knowledgeBases: { kbName: string; description?: string }[]
}>()

const emit = defineEmits<{ close: [] }>()

const kbName = ref(props.knowledgeBases[0]?.kbName || '')
const filename = ref(defaultFilename())

function defaultFilename(): string {
  const prefix = props.text.trim().slice(0, 30).replace(/[\n\r]+/g, ' ').trim() || t('defaultKbFilename')
  return `${prefix}.md`
}

const preview = computed(() => props.text.slice(0, 300) + (props.text.length > 300 ? '...' : ''))

async function confirm() {
  if (!kbName.value) { message.warning(t('selectKb')); return }
  if (!filename.value.trim()) { message.warning(t('enterFilename')); return }
  try {
    await api.post(`/knowledge-bases/${kbName.value}/documents`, {
      filename: filename.value.trim(),
      textContent: props.text,
    })
    message.success(t('savedToKb'))
    emit('close')
  } catch { message.error(t('saveToKbFailed')) }
}
</script>

<template>
  <Teleport to="body">
    <div class="drawer-overlay" @click.self="$emit('close')">
      <div class="modal" @click.stop>
        <div class="modal__header">
          <span>{{ t('saveToKnowledgeBase') }}</span>
          <button class="modal__close" @click="$emit('close')">&times;</button>
        </div>
        <div class="modal__body">
          <div class="form-group">
            <label class="form-label">{{ t('targetKb') }}</label>
            <select v-model="kbName" class="select">
              <option v-for="k in knowledgeBases" :key="k.kbName" :value="k.kbName">{{ k.kbName }}</option>
            </select>
          </div>
          <div class="form-group">
            <label class="form-label">{{ t('filename') }}</label>
            <input v-model="filename" class="input" :placeholder="t('filenamePlaceholder')"/>
          </div>
          <div class="form-group">
            <label class="form-label">{{ t('contentPreview') }}</label>
            <div class="modal__preview">{{ preview }}</div>
          </div>
        </div>
        <div class="modal__footer">
          <button class="btn btn-ghost" @click="$emit('close')">{{ t('cancel') }}</button>
          <button class="btn btn-primary" @click="confirm">{{ t('save') }}</button>
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
.modal {
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 440px;
  max-width: 90vw;
  background: var(--clr-bg);
  border-radius: var(--rad-lg);
  box-shadow: var(--shd-modal);
  display: flex;
  flex-direction: column;
  z-index: var(--z-drawer);
}
.modal__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--clr-hairline);
  font-size: var(--fs-headline);
  font-weight: var(--fw-semibold);
}
.modal__close {
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
.modal__close:hover {
  background: var(--clr-fill);
  color: var(--clr-label);
}
.modal__body {
  padding: 20px;
}
.modal__footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 20px;
  border-top: 1px solid var(--clr-hairline);
}
.modal__preview {
  padding: 10px;
  background: var(--clr-bg-secondary);
  border-radius: var(--rad-md);
  font-size: var(--fs-callout);
  color: var(--clr-secondary);
  line-height: var(--lh-body);
  max-height: 120px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
