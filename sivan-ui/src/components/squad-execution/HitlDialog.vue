<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue'
import type { HitlReview } from '../../api/squad'
import { approveHitl, rejectHitl, extendHitlTimeout } from '../../api/squad'
import { useI18n } from '../../utils/i18n'
import { useMessage } from '../../utils/message'

const props = defineProps<{
  review: HitlReview
  execId: string
}>()

const emit = defineEmits<{
  'resolved': []
  'close': []
}>()

const { t } = useI18n()
const message = useMessage()

const feedbackText = ref('')
const submitting = ref(false)
const countdown = ref('')
let countdownTimer: ReturnType<typeof setInterval> | null = null
let heartbeatTimer: ReturnType<typeof setInterval> | null = null

function updateCountdown() {
  if (!props.review?.expiresAt) { countdown.value = ''; return }
  const diff = new Date(props.review.expiresAt).getTime() - Date.now()
  if (diff <= 0) { countdown.value = t('reviewTimeout'); return }
  const m = Math.floor(diff / 60000)
  const s = Math.floor((diff % 60000) / 1000)
  countdown.value = `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

async function handleApprove() {
  submitting.value = true
  try {
    await approveHitl(props.execId, props.review.reviewId, feedbackText.value || undefined)
    emit('resolved')
  } catch { message.error(t('operationFailed')) } finally { submitting.value = false }
}

async function handleReject() {
  submitting.value = true
  try {
    await rejectHitl(props.execId, props.review.reviewId, feedbackText.value || t('reject'))
    emit('resolved')
  } catch { message.error(t('operationFailed')) } finally { submitting.value = false }
}

function startHeartbeat() {
  heartbeatTimer = setInterval(() => {
    extendHitlTimeout(props.review.reviewId, props.execId).catch(() => {})
  }, 120000)
}

function stopAll() {
  if (countdownTimer) { clearInterval(countdownTimer); countdownTimer = null }
  if (heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null }
}

onMounted(() => {
  updateCountdown()
  countdownTimer = setInterval(updateCountdown, 1000)
  startHeartbeat()
})

onUnmounted(() => stopAll())

watch(() => props.review, () => updateCountdown())
</script>

<template>
  <div class="hitl-bar">
    <div class="hitl-bar__header">
      <span class="hitl-bar__icon">⏳</span>
      <span class="hitl-bar__title">{{ t('hitlPhaseReview', { name: review.phaseName || '' }) }}</span>
    </div>
    <div class="hitl-bar__input">{{ review.outputContent || review.inputContent }}</div>
    <div class="hitl-bar__actions">
      <textarea
        v-model="feedbackText" class="hitl-bar__feedback"
        :placeholder="t('reviewFeedbackPlaceholder')" rows="2"
      />
      <div class="hitl-bar__btns">
        <span class="hitl-bar__countdown">⏱ {{ t('remaining') }} {{ countdown }}</span>
        <button class="btn btn-danger" :disabled="submitting" @click="handleReject">{{ t('reject') }} ✗</button>
        <button class="btn btn-primary" :disabled="submitting" @click="handleApprove">{{ t('approve') }} ✓</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.hitl-bar {
  border: 1.5px solid var(--clr-amber);
  border-radius: var(--rad-md);
  padding: 12px;
  background: rgba(255,149,0,0.03);
  margin-top: 12px;
}
.hitl-bar__header { display: flex; align-items: center; gap: 6px; margin-bottom: 8px; }
.hitl-bar__icon { font-size: 14px; }
.hitl-bar__title { font-size: var(--fs-callout); font-weight: var(--fw-semibold); color: var(--clr-amber); }
.hitl-bar__countdown { font-size: var(--fs-footnote); font-family: var(--ff-mono); color: var(--clr-tertiary); }
.hitl-bar__input {
  font-size: var(--fs-footnote); color: var(--clr-secondary);
  background: var(--clr-bg); padding: 8px; border-radius: var(--rad-sm);
  max-height: 80px; overflow-y: auto; white-space: pre-wrap; word-break: break-all; margin-bottom: 8px;
}
.hitl-bar__actions { display: flex; flex-direction: column; gap: 8px; }
.hitl-bar__feedback {
  width: 100%; padding: 6px 8px; border: 1px solid var(--clr-hairline); border-radius: var(--rad-sm);
  font-size: var(--fs-footnote); color: var(--clr-label); background: var(--clr-bg);
  resize: vertical; font-family: inherit;
}
.hitl-bar__feedback:focus { outline: none; border-color: var(--clr-accent); }
.hitl-bar__btns { display: flex; gap: 8px; justify-content: flex-end; }
</style>
