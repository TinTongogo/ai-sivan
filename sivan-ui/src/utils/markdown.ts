import type {Highlighter} from 'shiki'
import {createHighlighter} from 'shiki'
import MarkdownIt from 'markdown-it'

/** 懒加载 highlighter：模块初始化时不阻塞，首次需要时才开始加载。 */
let highlighterReady = false
let loadedHighlighter: Highlighter
// 立即启动加载，但不 await（不阻塞首屏）
void createHighlighter({
  langs: [
    'bash', 'c', 'cpp', 'css', 'diff', 'docker', 'go', 'graphql',
    'groovy', 'html', 'http', 'ini', 'java', 'javascript', 'json',
    'jsx', 'kotlin', 'lua', 'makefile', 'markdown', 'nginx', 'php',
    'powershell', 'prisma', 'properties', 'proto', 'python', 'ruby',
    'rust', 'sass', 'scala', 'scss', 'shell', 'sql', 'swift', 'toml',
    'tsx', 'typescript', 'vue', 'wasm', 'xml', 'yaml',
  ],
  themes: ['catppuccin-mocha'],
}).then(h => { highlighterReady = true; loadedHighlighter = h })

const md = new MarkdownIt({
  html: false,
  breaks: true,
  linkify: true,
  typographer: true,
})

const defaultFence = md.renderer.rules.fence!
md.renderer.rules.fence = (tokens, idx, options, env, self) => {
  const token = tokens[idx]
  const lang = token.info.trim() || 'text'

  // highlighter 未就绪时使用默认渲染（不阻塞同步 render 调用）
  if (!highlighterReady) {
    return defaultFence(tokens, idx, options, env, self)
  }

  let highlighted: string
  try {
    highlighted = loadedHighlighter.codeToHtml(token.content, {
      lang: lang === 'text' ? 'plaintext' : lang,
      theme: 'catppuccin-mocha',
    })
  } catch {
    highlighted = defaultFence(tokens, idx, options, env, self)
  }

  const langLabel = lang !== 'text' ? md.utils.escapeHtml(lang) : ''

  return `<div class="code-block-wrapper">
    ${langLabel ? `<span class="code-lang-label">${langLabel}</span>` : ''}
    <button class="code-copy-btn" title="复制代码">
      <svg class="copy-icon" viewBox="0 0 20 20" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"><rect x="5.5" y="2.5" width="10" height="13" rx="1.5"/><rect x="3.5" y="4.5" width="10" height="13" rx="1.5"/></svg>
      <svg class="check-icon" viewBox="0 0 20 20" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" style="display:none"><path d="M5 10l3 3 7-7"/></svg>
    </button>
    ${highlighted}
  </div>`
}

/**
 * 检测文本块是否为目录树结构（含 ├ └ │ 等制表符的行），
 * 若是则补上 fenced code block，确保树结构以等宽字体正确对齐。
 *
 * 非树行（如纯文件名）也可包含在树块中，只有遇到空行或 fences 才切分。
 */
function wrapTreeBlock(text: string): string {
  const lines = text.split('\n')
  let inFence = false
  let inTree = false
  let treeStart = -1
  const ranges: Array<[number, number]> = []

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    const isFenceMark = /^\s*(```|~~~)/.test(line)

    if (isFenceMark) {
      if (inTree) { ranges.push([treeStart, i - 1]); inTree = false }
      inFence = !inFence
      continue
    }

    if (inFence) continue

    const hasTreeChar = /[├└│]/.test(line)
    const isEmpty = /^\s*$/.test(line)

    if (hasTreeChar && !inTree) {
      inTree = true
      treeStart = i
    } else if (isEmpty && inTree) {
      // 空行结束当前树块
      ranges.push([treeStart, i - 1])
      inTree = false
    }
  }
  if (inTree) ranges.push([treeStart, lines.length - 1])

  // 没有树块 → 原文
  if (ranges.length === 0) return text

  // 从右往左包裹（避免索引偏移）
  const result = [...lines]
  for (const [start, end] of ranges.reverse()) {
    // 跳过已处于 fenced code block 的行（保险）
    if (start > 0 && /^\s*(```|~~~)/.test(result[start - 1])) continue
    if (end < result.length - 1 && /^\s*(```|~~~)/.test(result[end + 1])) continue
    result[start] = '```\n' + result[start]
    result[end] = result[end] + '\n```'
  }

  return result.join('\n')
}

export function renderMarkdown(text: string): string {
  if (!text) return ''
  return md.render(wrapTreeBlock(text))
}
