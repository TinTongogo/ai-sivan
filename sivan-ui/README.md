# Sivan UI — 灵枢前端

Sivan（灵枢）v1.0 前端。私人 AI 团队操作系统的用户界面。

## 技术栈

| 类别 | 技术 |
|------|------|
| 框架 | Vue 3 (`<script setup>` SFC) |
| 构建 | Vite 8 |
| 语言 | TypeScript 6 |
| 状态管理 | Pinia 3 |
| 路由 | Vue Router 4 |
| HTTP | Axios (常规请求) + Fetch + ReadableStream (SSE 流式) |
| 虚拟列表 | @tanstack/vue-virtual |
| Markdown | markdown-it + Shiki (代码高亮, Catppuccin Mocha) |
| 包管理 | pnpm |

> 纯自定义 UI 组件，未使用第三方组件库。所有组件（气泡、Toast、按钮、模态框等）均为 Vue SFC + CSS 变量体系。

## 快速开始

```bash
# 安装依赖
pnpm install

# 启动开发服务器（默认 :5173，自动代理 /api → :8080）
pnpm dev

# 构建生产版本
pnpm build

# 预览构建产物
pnpm preview
```

开发环境下，`/api` 请求由 Vite 代理到后端 `http://localhost:8080`。

## 项目结构

```
sivan-ui/
├── src/
│   ├── main.ts                  # 应用入口（含 FOUC 防护）
│   ├── App.vue                  # 根组件（RouterView + Toast 容器）
│   ├── style.css                # 全局样式（CSS 变量 + Reset + 通用组件样式）
│   ├── api/
│   │   ├── index.ts             # Axios 实例（JWT 拦截器、401 处理）
│   │   ├── auth.ts              # 登录/注册 API
│   │   ├── embedding.ts         # Embedding 配置 API
│   │   ├── files.ts             # 文件上传 API
│   │   ├── group.ts             # 分组管理 API
│   │   ├── llm-provider.ts      # LLM 提供商 API
│   │   └── mcp-server.ts        # MCP 服务器配置 API
│   ├── router/
│   │   └── index.ts             # 路由表 + 导航守卫
│   ├── stores/
│   │   ├── auth.ts              # Pinia 认证状态
│   │   ├── theme.ts             # 主题切换（localStorage 持久化）
│   │   └── settings.ts          # 设置弹窗状态
│   ├── components/
│   │   ├── chat/
│   │   │   ├── ChatBubble.vue   # 气泡组件（思考折叠、Markdown、操作栏、合并行）
│   │   │   └── ChatInput.vue    # 输入区组件（模型选择、附件、工具中心、AI 润色）
│   │   ├── common/
│   │   │   └── SystemSettingsModal.vue  # 系统设置弹窗（LLM 提供商、MCP 服务器、Embedding）
│   │   └── layout/
│   │       └── AppLayout.vue    # 主外壳（侧栏 + RouterView）
│   ├── utils/
│   │   ├── i18n.ts              # 国际化工具
│   │   ├── markdown.ts          # markdown-it + Shiki 渲染
│   │   └── message.ts           # Toast 消息工具
│   └── views/
│       ├── login/               # 登录/注册
│       ├── conversations/       # 对话页（核心，虚拟列表）
│       ├── agents/              # 智能体管理
│       ├── skills/              # 技能管理
│       ├── knowledge-bases/     # 知识库管理
│       ├── squads/              # Squad 编排
│       ├── squad-executions/    # Squad 执行记录与监控
│       ├── contracts/           # 契约查询
│       ├── token-usage/         # Token 消耗统计
│       ├── memories/            # 记忆管理
│       └── routing-decisions/   # 路由日志
├── index.html
├── package.json
├── vite.config.ts
├── tsconfig.json
└── tsconfig.node.json
```

## 页面说明

| 路径 | 页面 | 说明 |
|------|------|------|
| `/login` | 登录/注册 | JWT 认证 |
| `/conversations` | 对话 | 核心工作区：SSE 流式对话、虚拟列表、Markdown 渲染、模型选择、多模态附件、MCP 工具、AI 润色 |
| `/agents` | 智能体 | CRUD + 类型分布 |
| `/skills` | 技能 | CRUD 管理 |
| `/knowledge-bases` | 知识库 | 知识库 + 文档上传 + 搜索 + 重建索引 |
| `/squads` | Squad | Squad 编排列表 + 拓扑生成 |
| `/squad-executions` | Squad 执行 | 执行记录与实时进度监控 |
| `/contracts` | 契约 | 按 Squad/执行记录查询契约流转 |
| `/token-usage` | Token 统计 | 汇总卡 + 趋势折线图 + 按智能体/模型排行 |
| `/memories` | 记忆 | 按层级/重要/归档查看，本能模板追溯 |
| `/routing-decisions` | 路由日志 | 只读审计日志，点击展开完整链路 |

## 侧栏导航

按功能分组：
- **团队管理**: 智能体 / Squad / 技能 / 契约
- **监控**: Token 统计 / 路由日志 / 执行记录
- **资源**: 知识库 / 记忆
- **底部**: 用户头像 + 系统设置入口

## 认证流程

1. 登录 → 后端返回 JWT token → 存入 `localStorage`
2. Axios 请求拦截器自动注入 `Authorization: Bearer <token>`
3. 401 响应 → 清除本地 token → 跳转 `/login`
4. 路由导航守卫检查登录状态，未登录重定向到 `/login`

## SSE 流式对话

对话页使用原生 `fetch()` + `ReadableStream` 实现 SSE，支持三种事件类型：

```json
{"type":"thinking","content":"思考过程..."}
{"type":"response","content":"回复内容"}
{"type":"meta","model":"Qwen3-70B","totalTokens":1234,"durationMs":2300,"thinkingDurationMs":1500}
[DONE]
```

- `thinking` — 追加到 AI 消息思考区域（可折叠展开）
- `response` — 追加到 AI 消息正文（Markdown 渲染）
- `meta` — 更新消息的 model、tokens、duration 元信息（流结束时发射）

### 关键特性

- **AbortController 管理**: 切换对话时 cancel 旧流，60 秒空闲超时自动终止
- **续接机制**: 重新进入对话时自动续接 RUNNING 状态的消息
- **后台流**: LLM 在后端独立执行（StreamManager），SSE 断开不影响生成，每 2s flush 到 DB

### AI 润色流

独立的润色 SSE 端点 `POST /api/polish/stream`：

- 点击 ✨ 按钮 → 弹出模态框，上方原文 + 下方流式输出
- 使用 `fetch()` + `ReadableStream.getReader()` 消费 SSE
- AbortController 支持取消生成
- 流完成后「替换」按钮启用，点击替换原文

## 虚拟列表

使用 `@tanstack/vue-virtual` 实现消息列表虚拟滚动：

- 动态高度自适应（ResizeObserver + rAF 合并测量）
- 上滚加载更早消息（cursor-base 分页，sortOrder 光标）
- 滚动位置保持（前置插入消息后补偿 scrollTop）
- 自动回底（`scrollTop < 80px` 阈值）+ 流式节流（80ms 不重复执行）

## Chat 输入区（ChatInput）

输入区重构为五个功能区域：

```
┌──────────┬──────────────────────────────┬──┬──┬──┬──────┐
│ Model ▼  │  输入消息...                  │＋│🔧│✨│ Send │
│ ⚙️       │                              │  │  │  │      │
└──────────┴──────────────────────────────┴──┴──┴──┴──────┘
```

1. **Model Selector** — 下拉选择 LLM Provider + 齿轮打开系统设置
2. **Text Input** — 自动增高 textarea，Enter 发送
3. **Attachment (＋)** — 上传图片或文件
4. **Tool Center (🔧)** — 弹出面板选择 MCP 工具
5. **AI Polish (✨)** — 流式润色，模态框确认替换
6. **Send** — 发送 / 流式时切换为取消

发送时在 body 中携带 `modelProviderId`、`mcpServerIds`、`attachments`。

## 消息气泡

气泡组件 (`ChatBubble.vue`) 结构：

```
bubble
├── bubble__thinking    ← 思考折叠区（仅 AI + 有思考内容时渲染）
├── bubble__reply       ← 引用回复块（有 replyTo 时渲染）
├── bubble__body        ← 正文（Markdown 渲染 + 代码高亮）
├── bubble__meta        ← 元信息行（chain · duration · tokens · model）
└── bubble__actions     ← AI 操作栏（悬停显示，复制/引用/保存到知识库/评价/重新生成/删除）
```

### 合并行（跨消息）

用户消息操作栏与下一条 AI 思考折叠按钮合并为一行，消除两行间距：

```
merge-row
├── merge-row__left     ← 思考折叠按钮 + 思考子操作（悬停显示）
└── merge-row__right    ← 用户操作按钮（悬停显示）+ 时间（常显）
```

当用户消息下方紧邻 AI 消息时生效，通过 `hideExtra` prop 控制气泡内对应区域显隐。

## Markdown 渲染

AI 回复使用 `markdown-it` + `Shiki`（Catppuccin Mocha 主题）渲染。代码块带语言标签（左上角）+ 复制按钮（右上角 hover 显示）。支持 40+ 编程语言。

代码块样式位于 unscoped `<style>` 块中（v-html 注入的 DOM 不匹配 scoped CSS）。

## 主题系统

- 亮/暗切换：Pinia Theme Store → `<html class="dark">` → CSS 变量自动切换
- `localStorage` 持久化，`main.ts` 中防 FOUC（`createApp` 前执行）
- 全局样式表 `style.css` 统一控制

## Toast 通知

自定义 Toast 系统（非第三方库）：

- `App.vue` 中提供 `.toast-container` 渲染锚点
- `utils/message.ts` 导出 `message.success()` / `message.error()` / `message.warning()` / `message.info()`
- 2.5s 自动消失，支持点击关闭

## 后端 API

后端基于 Spring Boot 3 + WebFlux + PostgreSQL 16 + pgvector，参见项目根目录文档。
