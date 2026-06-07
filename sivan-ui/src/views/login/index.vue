<script setup lang="ts">
import {onMounted, onUnmounted, reactive, ref} from 'vue'
import {useRouter} from 'vue-router'
import {useAuthStore} from '../../stores/auth'
import {useThemeStore} from '../../stores/theme'
import {useMessage} from '../../utils/message'
import {useI18n} from '../../utils/i18n'

const router = useRouter()
const auth = useAuthStore()
const theme = useThemeStore()
const message = useMessage()
const { t, lang, setLang } = useI18n()

// ── 页面模式 ──
type PageMode = 'login' | 'register' | 'forgot' | 'forgot_done' | 'reset'
const mode = ref<PageMode>('login')
const loading = ref(false)
const error = ref('')
const generatedToken = ref('')

// ── 表单 ──
const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  email: '',
  displayName: '',
  resetToken: '',
})

// 检测是否因登录过期被重定向
if (sessionStorage.getItem('login_expired')) {
  sessionStorage.removeItem('login_expired')
  error.value = t('loginExpired')
}

async function handleSubmit() {
  error.value = ''
  loading.value = true
  try {
    if (mode.value === 'register') {
      if (form.password !== form.confirmPassword) {
        error.value = t('passwordMismatchLogin')
        loading.value = false
        return
      }
      await auth.register({
        username: form.username, password: form.password,
        email: form.email || undefined, displayName: form.displayName || undefined,
      })
      message.success(t('registerSuccess'))
      mode.value = 'login'
      form.password = ''; form.confirmPassword = ''
      loading.value = false
      return
    }
    if (mode.value === 'forgot') {
      const res: any = await auth.requestPasswordReset(form.username)
      generatedToken.value = res?.data || res || ''
      mode.value = 'forgot_done'
      loading.value = false
      return
    }
    if (mode.value === 'reset') {
      await auth.resetPassword({ token: form.resetToken, newPassword: form.password })
      message.success(t('passwordChanged'))
      mode.value = 'login'
      form.password = ''; form.confirmPassword = ''; form.resetToken = ''
      loading.value = false
      return
    }
    // login
    await auth.login({ username: form.username, password: form.password })
    message.success(t('loginSuccess'))
    router.push('/conversations')
  } catch (e: any) {
    error.value = e.response?.data?.message || e.message || t('operationFailed')
  } finally {
    loading.value = false
  }
}

// ── 粒子效果 ──
let canvasEl: HTMLCanvasElement | null = null
let animId = 0
const PARTICLE_COUNT = 80
const CONNECTION_DIST = 150

interface Particle {
  x: number; y: number; vx: number; vy: number; size: number; alpha: number
}

let particles: Particle[] = []

// 连接状态追踪（用于"连线时发射一次脉冲"）
let wasConnected = new Set<string>()
const pulseStartTimes = new Map<string, number>()

function initParticles(w: number, h: number) {
  particles = Array.from({ length: PARTICLE_COUNT }, () => ({
    x: Math.random() * w, y: Math.random() * h,
    vx: (Math.random() - 0.5) * 0.6, vy: (Math.random() - 0.5) * 0.6,
    size: Math.random() * 2 + 1.5, alpha: Math.random() * 0.5 + 0.4,
  }))
}

function pairKey(i: number, j: number) {
  return `${Math.min(i, j)}-${Math.max(i, j)}`
}

function drawParticles(ctx: CanvasRenderingContext2D, w: number, h: number) {
  ctx.clearRect(0, 0, w, h)

  // 从 CSS 变量读取粒子颜色
  const pageEl = document.querySelector('.login-page')
  const particleRgb = getComputedStyle(pageEl!).getPropertyValue('--login-particle').trim() || '120, 160, 255'

  const pulseSpeed = 150  // 脉冲传播速度 px/s

  // 更新 + 绘制粒子
  for (const p of particles) {
    p.x += p.vx; p.y += p.vy
    if (p.x < 0 || p.x > w) p.vx *= -1
    if (p.y < 0 || p.y > h) p.vy *= -1

    ctx.beginPath()
    ctx.arc(p.x, p.y, p.size, 0, Math.PI * 2)
    ctx.fillStyle = `rgba(${particleRgb}, ${p.alpha})`
    ctx.fill()
  }

  // 记录当前帧的连接状态
  const nowConnected = new Set<string>()

  // 连线 + 仅连接瞬间发射一次脉冲
  for (let i = 0; i < particles.length; i++) {
    for (let j = i + 1; j < particles.length; j++) {
      const dx = particles[i].x - particles[j].x
      const dy = particles[i].y - particles[j].y
      const dist = Math.sqrt(dx * dx + dy * dy)
      if (dist >= CONNECTION_DIST) continue

      const key = pairKey(i, j)
      nowConnected.add(key)

      // 基础连线（始终绘制）
      ctx.beginPath()
      ctx.moveTo(particles[i].x, particles[i].y)
      ctx.lineTo(particles[j].x, particles[j].y)
      ctx.strokeStyle = `rgba(${particleRgb}, 0.24)`
      ctx.lineWidth = 1
      ctx.stroke()

      // 距离太近或已经连接 → 跳过脉冲
      if (dist < 40 || wasConnected.has(key)) continue

      // 首次连接 → 触发一次脉冲
      pulseStartTimes.set(key, performance.now())
    }
  }

  // 绘制活跃脉冲（覆盖在基础连线上）
  for (const [key, startTime] of pulseStartTimes) {
    if (!nowConnected.has(key)) {
      pulseStartTimes.delete(key)  // 连接已断开，清理
      continue
    }
    const [i, j] = key.split('-').map(Number) as [number, number]
    const dx = particles[i].x - particles[j].x
    const dy = particles[i].y - particles[j].y
    const dist = Math.sqrt(dx * dx + dy * dy)

    const elapsed = (performance.now() - startTime) / 1000
    const travelTime = dist / pulseSpeed

    if (elapsed >= travelTime) {
      pulseStartTimes.delete(key)  // 脉冲完成
      continue
    }

    // 脉冲：短光段沿连线从 i 向 j 移动
    const x = elapsed / travelTime
    const frac = 1 - (1 - x) * (1 - x)  // ease-out
    const segLen = Math.min(25, dist * 0.4)  // 光段长度（不超过连线 40%）
    const halfSeg = segLen / (2 * dist)
    const s0 = Math.max(0, frac - halfSeg)
    const s1 = Math.min(1, frac + halfSeg)

    const ax = particles[i].x - dx * s0
    const ay = particles[i].y - dy * s0
    const bx = particles[i].x - dx * s1
    const by = particles[i].y - dy * s1

    const segGrad = ctx.createLinearGradient(ax, ay, bx, by)
    segGrad.addColorStop(0, `rgba(${particleRgb}, 0.1)`)
    segGrad.addColorStop(0.5, `rgba(${particleRgb}, 0.7)`)
    segGrad.addColorStop(1, `rgba(${particleRgb}, 0.1)`)
    ctx.beginPath()
    ctx.moveTo(ax, ay)
    ctx.lineTo(bx, by)
    ctx.strokeStyle = segGrad
    ctx.lineWidth = 2
    ctx.stroke()
  }

  // 更新连接追踪
  wasConnected = nowConnected

  animId = requestAnimationFrame(() => drawParticles(ctx, w, h))
}

function startParticles() {
  canvasEl = document.getElementById('particle-canvas') as HTMLCanvasElement
  if (!canvasEl) return
  const ctx = canvasEl.getContext('2d')
  if (!ctx) return
  const resize = () => {
    canvasEl!.width = window.innerWidth
    canvasEl!.height = window.innerHeight
    initParticles(canvasEl!.width, canvasEl!.height)
  }
  resize()
  window.addEventListener('resize', resize)
  drawParticles(ctx, canvasEl.width, canvasEl.height)
}

onMounted(() => startParticles())
onUnmounted(() => { cancelAnimationFrame(animId) })
</script>

<template>
  <div class="login-page">
    <canvas id="particle-canvas" class="particle-canvas"></canvas>
    <div class="login-toolbar">
      <button class="login-toolbar__btn" @click="setLang(lang === 'zh' ? 'en' : 'zh')" :title="t('switchLang')">{{ lang === 'zh' ? 'EN' : '中' }}</button>
      <button class="login-toolbar__btn" @click="theme.setMode(theme.mode === 'dark' ? 'light' : 'dark'); theme.apply()" :title="t('switchTheme')">{{ theme.mode === 'dark' ? '☀' : '☾' }}</button>
    </div>
    <div class="login-container">
      <!-- Brand -->
      <div class="brand">
        <div class="brand-name">灵枢</div>
        <div class="brand-sub">Sivan — 私人 AI 团队操作系统</div>
      </div>

      <!-- Auth Card -->
      <div class="glass-card">
        <div class="glass-card__header">
          <h2 v-if="mode === 'register'">{{ t('register') }}</h2>
          <h2 v-else-if="mode === 'forgot' || mode === 'forgot_done'">{{ t('forgotPassword') }}</h2>
          <h2 v-else-if="mode === 'reset'">{{ t('resetPassword') }}</h2>
          <p v-if="mode === 'register'" class="glass-card__hint">{{ t('createAccount') }}</p>
          <p v-else-if="mode === 'forgot'" class="glass-card__hint">{{ t('enterUsernameForReset') }}</p>
          <p v-else-if="mode === 'forgot_done'" class="glass-card__hint">{{ t('copyResetToken') }}</p>
          <p v-else-if="mode === 'reset'" class="glass-card__hint">{{ t('enterResetToken') }}</p>
        </div>

        <form @submit.prevent="handleSubmit" class="glass-card__body">
          <!-- Login / Register: username -->
          <div v-if="mode !== 'reset' && mode !== 'forgot_done'" class="field">
            <input v-model="form.username" class="glass-input" :placeholder="t('username')" required autocomplete="username" />
          </div>

          <!-- Login / Register / Reset: password -->
          <div v-if="mode !== 'forgot' && mode !== 'forgot_done'" class="field">
            <input v-model="form.password" class="glass-input" type="password" :placeholder="mode === 'reset' ? t('newPassword') : t('password')" required :minlength="mode === 'register' ? 6 : undefined" autocomplete="new-password" />
          </div>

          <!-- Register: confirm + email + display -->
          <template v-if="mode === 'register'">
            <div class="field">
              <input v-model="form.confirmPassword" class="glass-input" type="password" :placeholder="t('confirmPwd')" />
            </div>
            <div class="field">
              <input v-model="form.email" class="glass-input" :placeholder="t('emailOptional')" />
            </div>
            <div class="field">
              <input v-model="form.displayName" class="glass-input" :placeholder="t('displayNameOptional')" />
            </div>
          </template>

          <!-- Reset: token input -->
          <div v-if="mode === 'reset'" class="field">
            <input v-model="form.resetToken" class="glass-input" :placeholder="t('resetToken')" required />
          </div>

          <!-- Error -->
          <div v-if="error" class="msg msg-error">{{ error }}</div>

          <!-- Token display (forgot_done) -->
          <div v-if="mode === 'forgot_done'" class="token-display">
            <div class="token-label">{{ t('resetToken') }}</div>
            <div class="token-value">{{ generatedToken }}</div>
            <div class="token-hint">{{ t('copyResetTokenHint') }}</div>
          </div>

          <!-- Submit -->
          <button v-if="mode !== 'forgot_done'" class="glass-btn glass-btn-primary" type="submit" :disabled="loading">
            <span v-if="loading" class="btn-spinner"></span>
            <span v-else>
              {{ mode === 'login' ? t('login') : mode === 'register' ? t('register') : mode === 'forgot' ? t('send') : t('resetPassword') }}
            </span>
          </button>

          <!-- Toggle links -->
          <div class="glass-links">
            <button v-if="mode === 'login'" type="button" class="glass-link" @click="mode = 'register'; error = ''">{{ t('noAccount') }}</button>
            <button v-if="mode === 'login'" type="button" class="glass-link glass-link--right" @click="mode = 'forgot'; error = ''">{{ t('forgotPassword') }}</button>
            <button v-if="mode === 'forgot_done'" type="button" class="glass-link" @click="mode = 'reset'; error = ''; form.resetToken = ''; form.password = ''">{{ t('haveToken') }}</button>
            <button v-if="mode !== 'login' && mode !== 'forgot_done'" type="button" class="glass-link" @click="mode = 'login'; error = ''; form.password = ''; form.confirmPassword = ''; form.resetToken = ''">{{ t('backToLogin') }}</button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  overflow: hidden;
  background: linear-gradient(135deg, var(--login-bg-start, #0a0e1a) 0%, var(--login-bg-mid, #141b2d) 50%, var(--login-bg-end, #1a1f35) 100%);
}
.particle-canvas {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 0;
}
.login-container {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 32px;
  width: 380px;
}
.brand {
  text-align: center;
  animation: fadeDown 0.8s ease-out;
}
.brand-name {
  font-size: 28px;
  font-weight: 700;
  color: var(--login-brand);
  letter-spacing: 4px;
}
.brand-sub {
  font-size: 12px;
  color: var(--login-brand-sub);
  margin-top: 4px;
  letter-spacing: 1px;
}

/* ── Glass Card ── */
.glass-card {
  width: 100%;
  background: var(--login-glass-bg);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border: 1px solid var(--login-glass-border);
  border-radius: 16px;
  padding: 32px 28px;
  animation: fadeUp 0.6s ease-out;
}
.glass-card__header {
  margin-bottom: 24px;
  text-align: center;
}
.glass-card__header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--login-text);
}
.glass-card__hint {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--login-text-secondary);
}

/* ── Glass Input ── */
.field { margin-bottom: 14px; }
.glass-input {
  width: 100%;
  box-sizing: border-box;
  padding: 12px 14px;
  background: var(--login-input-bg);
  border: 1px solid var(--login-input-border);
  border-radius: 10px;
  color: var(--login-input-text);
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.glass-input::placeholder { color: var(--login-input-placeholder); }
.glass-input:focus {
  border-color: var(--login-input-focus);
  box-shadow: 0 0 0 3px var(--login-input-focus);
}

/* ── Glass Button ── */
.glass-btn {
  width: 100%;
  padding: 12px;
  border: none;
  border-radius: 10px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
}
.glass-btn-primary {
  background: linear-gradient(135deg, var(--login-btn-grad-start), var(--login-btn-grad-end));
  color: #fff;
  box-shadow: 0 4px 14px var(--login-btn-shadow);
}
.glass-btn-primary:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 6px 20px var(--login-btn-shadow);
}
.glass-btn-primary:active:not(:disabled) { transform: translateY(0); }
.glass-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.btn-spinner {
  width: 18px; height: 18px;
  border: 2px solid rgba(255,255,255,0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

/* ── Links ── */
.glass-links {
  display: flex;
  justify-content: space-between;
  margin-top: 16px;
}
.glass-link {
  background: none;
  border: none;
  color: var(--login-link);
  font-size: 13px;
  cursor: pointer;
  padding: 4px 0;
  transition: color 0.2s;
}
.glass-link:hover { color: var(--login-link-hover); }
.glass-link--right { margin-left: auto; }

/* ── Messages ── */
.msg {
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 13px;
  margin-bottom: 12px;
  text-align: center;
}
.msg-error {
  color: #ff7b7b;
  background: rgba(255, 80, 80, 0.1);
  border: 1px solid rgba(255, 80, 80, 0.2);
}

/* ── Theme variables ── */
.login-page {
  --login-bg-start: #0a0e1a;
  --login-bg-mid: #141b2d;
  --login-bg-end: #1a1f35;
  --login-glass-bg: rgba(20, 27, 45, 0.65);
  --login-glass-border: rgba(120, 160, 255, 0.15);
  --login-text: #e0e6ff;
  --login-text-secondary: rgba(180, 195, 240, 0.55);
  --login-input-bg: rgba(255, 255, 255, 0.06);
  --login-input-border: rgba(120, 160, 255, 0.15);
  --login-input-text: #d0d8f0;
  --login-input-placeholder: rgba(160, 175, 220, 0.4);
  --login-input-focus: rgba(120, 160, 255, 0.5);
  --login-btn-grad-start: #4a7cff;
  --login-btn-grad-end: #6c5ce7;
  --login-btn-shadow: rgba(74, 124, 255, 0.25);
  --login-link: rgba(160, 175, 220, 0.5);
  --login-link-hover: rgba(160, 175, 220, 0.85);
  --login-toolbar-bg: rgba(20, 27, 45, 0.5);
  --login-toolbar-text: rgba(160, 175, 220, 0.6);
  --login-toolbar-hover: rgba(160, 175, 220, 0.9);
  --login-toolbar-border: rgba(120, 160, 255, 0.2);
  --login-token-bg: rgba(120, 160, 255, 0.08);
  --login-token-border: rgba(120, 160, 255, 0.2);
  --login-token-text: #b0c8ff;
  --login-particle: 140, 215, 255;
  --login-brand: #e8edff;
  --login-brand-sub: rgba(180, 195, 240, 0.6);
}
:root.dark .login-page,
.login-page:root.dark {
  /* inherits defaults (dark theme is default) */
}
:root:not(.dark) .login-page {
  --login-bg-start: #e8ecf4;
  --login-bg-mid: #d5dce8;
  --login-bg-end: #c8d0e0;
  --login-glass-bg: rgba(255, 255, 255, 0.7);
  --login-glass-border: rgba(100, 140, 220, 0.2);
  --login-text: #2c3e50;
  --login-text-secondary: rgba(80, 100, 140, 0.5);
  --login-input-bg: rgba(255, 255, 255, 0.5);
  --login-input-border: rgba(100, 140, 220, 0.2);
  --login-input-text: #2c3e50;
  --login-input-placeholder: rgba(80, 100, 140, 0.35);
  --login-input-focus: rgba(74, 124, 255, 0.4);
  --login-btn-grad-start: #4a7cff;
  --login-btn-grad-end: #6c5ce7;
  --login-btn-shadow: rgba(74, 124, 255, 0.2);
  --login-link: rgba(80, 100, 140, 0.5);
  --login-link-hover: rgba(80, 100, 140, 0.8);
  --login-toolbar-bg: rgba(255, 255, 255, 0.6);
  --login-toolbar-text: rgba(80, 100, 140, 0.6);
  --login-toolbar-hover: rgba(80, 100, 140, 0.9);
  --login-toolbar-border: rgba(100, 140, 220, 0.2);
  --login-token-bg: rgba(74, 124, 255, 0.06);
  --login-token-border: rgba(74, 124, 255, 0.15);
  --login-token-text: #3a6bd5;
  --login-particle: 50, 80, 200;
  --login-brand: #1a2a4a;
  --login-brand-sub: rgba(50, 70, 120, 0.7);
}

/* ── Toolbar (lang + theme) ── */
.login-toolbar {
  position: fixed;
  top: 16px;
  right: 16px;
  z-index: 10;
  display: flex;
  gap: 8px;
}
.login-toolbar__btn {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: 1px solid var(--login-toolbar-border);
  background: var(--login-toolbar-bg);
  backdrop-filter: blur(8px);
  color: var(--login-toolbar-text);
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
}
.login-toolbar__btn:hover {
  border-color: var(--login-toolbar-border);
  color: var(--login-toolbar-hover);
  background: var(--login-toolbar-bg);
}

/* ── Token Display ── */
.token-display {
  text-align: center;
  margin-bottom: 16px;
  animation: fadeUp 0.4s ease-out;
}
.token-label {
  font-size: 13px;
  color: rgba(160, 175, 220, 0.5);
  margin-bottom: 6px;
}
.token-value {
  font-size: 13px;
  font-family: 'SF Mono', 'Fira Code', monospace;
  color: var(--login-token-text);
  background: var(--login-token-bg);
  border: 1px solid var(--login-token-border);
  border-radius: 8px;
  padding: 10px 12px;
  word-break: break-all;
  user-select: all;
  letter-spacing: 0.5px;
}
.token-hint {
  font-size: 11px;
  color: var(--login-text-secondary);
  margin-top: 6px;
}

/* ── Animations ── */
@keyframes fadeUp {
  from { opacity: 0; transform: translateY(20px); }
  to   { opacity: 1; transform: translateY(0); }
}
@keyframes fadeDown {
  from { opacity: 0; transform: translateY(-12px); }
  to   { opacity: 1; transform: translateY(0); }
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
