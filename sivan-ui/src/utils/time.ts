/**
 * 将 UTC 时间戳字符串格式化为本地时间字符串。
 * 输入示例： "2026-05-16T04:30:00.000Z"
 * 输出示例： "2026-05-16 12:30"
 */
export function formatTime(dateStr?: string): string {
  if (!dateStr) return '-'
  const ts = ensureUtcSuffix(dateStr)
  const date = new Date(ts)
  if (isNaN(date.getTime())) return dateStr
  const y = date.getFullYear()
  const M = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  const h = String(date.getHours()).padStart(2, '0')
  const m = String(date.getMinutes()).padStart(2, '0')
  return `${y}-${M}-${d} ${h}:${m}`
}

/**
 * 将 UTC 时间戳字符串格式化为本地日期字符串。
 * 输出示例： "2026-05-16"
 */
export function formatDate(dateStr?: string): string {
  if (!dateStr) return '-'
  const ts = ensureUtcSuffix(dateStr)
  const date = new Date(ts)
  if (isNaN(date.getTime())) return dateStr
  const y = date.getFullYear()
  const M = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${M}-${d}`
}

/**
 * 确保时间戳字符串有 UTC 后缀（Z），便于 Date 构造函数正确解析。
 */
function ensureUtcSuffix(dateStr: string): string {
  return /[Z+-]\d{2}:\d{2}$/.test(dateStr) || dateStr.endsWith('Z')
    ? dateStr
    : dateStr + 'Z'
}

/**
 * 将 UTC 时间戳转为相对于当前的友好描述。
 * 不到 1 分钟 → "刚刚"
 * 不到 1 小时 → "N 分钟前"
 * 不到 24 小时 → "N 小时前"
 * 超过 24 小时 → formatTime() 格式的本地时间
 */
export function relativeTime(dateStr?: string): string {
  if (!dateStr) return ''
  const ts = ensureUtcSuffix(dateStr)
  const date = new Date(ts).getTime()
  if (isNaN(date)) return ''
  const diff = Math.floor((Date.now() - date) / 1000)
  if (diff < 60) return '刚刚'
  if (diff < 3600) return `${Math.floor(diff / 60)}分钟前`
  if (diff < 86400) return `${Math.floor(diff / 3600)}小时前`
  return formatTime(dateStr)
}
