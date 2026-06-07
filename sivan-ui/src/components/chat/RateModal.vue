<script setup lang="ts">
import { ref, computed } from 'vue'
import api from '../../api'
import { useMessage } from '../../utils/message'
import { useI18n } from '../../utils/i18n'

const { t } = useI18n()
const message = useMessage()

const props = defineProps<{ messageId: string }>()
const emit = defineEmits<{ close: [] }>()

const value = ref(0)
const rateHints = computed(() => ['', t('rateHint1'), t('rateHint2'), t('rateHint3'), t('rateHint4'), t('rateHint5')])

async function confirm() {
  if (value.value < 1 || value.value > 5) { message.warning(t('selectRating')); return }
  try {
    await api.patch(`/conversations/messages/${props.messageId}/rating?rating=${value.value}`)
    message.success(t('rateSubmitted'))
    emit('close')
  } catch { message.error(t('rateSubmitFailed')) }
}
</script>

<template>
  <Teleport to="body">
    <div class="drawer-overlay" @click.self="$emit('close')">
      <div class="modal" @click.stop>
        <div class="modal__header">
          <span>{{ t('rateReply') }}</span>
          <button class="modal__close" @click="$emit('close')">&times;</button>
        </div>
        <div class="modal__body">
          <div class="rate-stars" @click.stop>
            <span v-for="s in 5" :key="s" class="rate-star" :class="{ active: s <= value }" @click="value = s">
              <svg viewBox="0 0 20 20" width="32" height="32">
                <path d="M10 1l2.39 4.84 5.34.78-3.87 3.77.91 5.33L10 13.88l-4.77 2.84.91-5.33L2.27 6.62l5.34-.78L10 1z" fill="currentColor"/>
              </svg>
            </span>
          </div>
          <p class="rate-hint">{{ rateHints[value] }}</p>
        </div>
        <div class="modal__footer">
          <button class="btn btn-ghost" @click="$emit('close')">{{ t('cancel') }}</button>
          <button class="btn btn-primary" @click="confirm">{{ t('submitRating', { n: value }) }}</button>
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
.rate-stars {
  display: flex;
  justify-content: center;
  gap: 8px;
  padding: 12px 0;
}
.rate-star {
  cursor: pointer;
  color: var(--clr-quaternary);
  transition: color 0.15s ease, transform 0.12s ease;
}
.rate-star:hover {
  transform: scale(1.15);
}
.rate-star.active {
  color: #f5a623;
}
.rate-star:hover ~ .rate-star {
  color: var(--clr-quaternary);
}
.rate-hint {
  text-align: center;
  font-size: var(--fs-callout);
  color: var(--clr-secondary);
  margin: 8px 0 0;
}
</style>
