/**
 * 带认证头加载资源并返回 blob URL。
 * 用于 <img> 标签加载需要认证的图片（<img> 无法发送 Authorization header）。
 */

const blobCache = new Map<string, string>()
export { blobCache }
const BLOB_CACHE_MAX = 50

export async function fetchAuthBlob(url: string): Promise<string> {
  const cached = blobCache.get(url)
  if (cached) return cached

  const token = localStorage.getItem('token')
  const res = await fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  const blob = await res.blob()
  const blobUrl = URL.createObjectURL(blob)
  // 限制缓存大小，超出时淘汰最早条目
  if (blobCache.size >= BLOB_CACHE_MAX) {
    const oldest = blobCache.keys().next().value
    if (oldest !== undefined) revokeAuthBlob(oldest)
  }
  blobCache.set(url, blobUrl)
  return blobUrl
}

export function revokeAuthBlob(url: string) {
  const blobUrl = blobCache.get(url)
  if (blobUrl) {
    URL.revokeObjectURL(blobUrl)
    blobCache.delete(url)
  }
}

/** 带认证下载文件，触发浏览器下载 */
export async function downloadAuthFile(url: string, fileName: string) {
  const token = localStorage.getItem('token')
  const res = await fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  const blob = await res.blob()
  const blobUrl = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = blobUrl
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(blobUrl)
}
