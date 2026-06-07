<script setup lang="ts">
import { computed, ref, reactive } from 'vue'
import dagre from 'dagre'
import type { ContractEdge } from '../../stores/squadExecution'

export interface PhaseNode {
  phase: number; name?: string; agents?: string[]; mode?: string; hitl?: boolean
  hitlMode?: string; hitlAgents?: string[]
  dependsOn?: number[]
}

const props = defineProps<{
  phases: PhaseNode[]
  currentPhase: number | null
  phaseStatuses: Map<number, 'pending' | 'running' | 'completed' | 'failed' | 'hitl'>
  contractEdges: ContractEdge[]
  selectedPhase: number | null
  selectedEdge?: ContractEdge | null
  squadMode?: string
  readonly?: boolean
  compact?: boolean
}>()

const emit = defineEmits<{
  'select-phase': [phaseIndex: number]
  'hover-edge': [edge: ContractEdge | null]
  'select-edge': [edge: ContractEdge | null]
  'delete-phase': [phaseIndex: number]
}>()

const hoveredEdge = ref<ContractEdge | null>(null)
const zoom = ref(1)

// ── 缩放与平移（基于 viewBox） ──
const panX = ref(0)
const panY = ref(0)

const vbW = computed(() => layout.value.svgW / zoom.value)
const vbH = computed(() => layout.value.svgH / zoom.value)
const viewBox = computed(() => `${panX.value} ${panY.value} ${vbW.value} ${vbH.value}`)

function applyZoom(newZoom: number) {
  newZoom = Math.max(0.25, Math.min(3, newZoom))
  if (newZoom === zoom.value) return
  const cx = panX.value + vbW.value / 2
  const cy = panY.value + vbH.value / 2
  zoom.value = newZoom
  panX.value = cx - (layout.value.svgW / newZoom) / 2
  panY.value = cy - (layout.value.svgH / newZoom) / 2
}
function zoomIn() { applyZoom(zoom.value * 1.25) }
function zoomOut() { applyZoom(zoom.value / 1.25) }
function zoomReset() { zoom.value = 1; panX.value = 0; panY.value = 0 }
function zoomCenter() {
  panX.value = (layout.value.svgW - vbW.value) / 2
  panY.value = (layout.value.svgH - vbH.value) / 2
}

/** 屏幕坐标 → SVG viewBox 坐标（标准 CTM 逆变换） */
function screenToSvg(evt: MouseEvent, svg: SVGSVGElement) {
  const pt = svg.createSVGPoint()
  pt.x = evt.clientX
  pt.y = evt.clientY
  const ctm = svg.getScreenCTM()
  if (!ctm) return { x: pt.x, y: pt.y }
  const r = pt.matrixTransform(ctm.inverse())
  return { x: r.x, y: r.y }
}

// ── 画布平移 ──
const panning = ref(false)
let panStart = { x: 0, y: 0, px: 0, py: 0, sx: 0, sy: 0 }

function onCanvasMouseDown(evt: MouseEvent) {
  if (dragging.value) return
  const el = evt.target as Element
  if (el.closest('.cf-svg__node') || el.closest('.cf-svg__edge-area')) return
  panning.value = true
  const svg = evt.currentTarget as SVGSVGElement
  const p = screenToSvg(evt, svg)
  panStart = { x: evt.clientX, y: evt.clientY, px: panX.value, py: panY.value, sx: p.x, sy: p.y }
}

function onCanvasMouseMove(evt: MouseEvent) {
  if (dragging.value) {
    onSvgMouseMove(evt)
    return
  }
  if (!panning.value) return
  const svg = evt.currentTarget as SVGSVGElement
  const p = screenToSvg(evt, svg)
  panX.value = panStart.px + (panStart.sx - p.x)
  panY.value = panStart.py + (panStart.sy - p.y)
}

function onCanvasMouseUp() {
  dragging.value = null
  panning.value = false
}

// ── 节点拖放 ──
const nodeOffsets = reactive<Record<number, { dx: number; dy: number }>>({})
const dragging = ref<{ phase: number; startX: number; startY: number; origX: number; origY: number } | null>(null)

function onNodeMouseDown(phase: number, evt: MouseEvent) {
  if (props.readonly) return
  evt.preventDefault()
  evt.stopPropagation()
  const svg = (evt.currentTarget as SVGGElement).closest('svg') as SVGSVGElement
  if (!svg) return
  const p = screenToSvg(evt, svg)
  const off = nodeOffsets[phase] || { dx: 0, dy: 0 }
  dragging.value = { phase, startX: p.x, startY: p.y, origX: off.dx, origY: off.dy }
}

function onSvgMouseMove(evt: MouseEvent) {
  if (!dragging.value || props.readonly) return
  const svg = evt.currentTarget as SVGSVGElement
  const p = screenToSvg(evt, svg)
  nodeOffsets[dragging.value.phase] = {
    dx: dragging.value.origX + (p.x - dragging.value.startX),
    dy: dragging.value.origY + (p.y - dragging.value.startY),
  }
}

/** 是否存在已拖拽的节点。 */
const hasDirty = computed(() => Object.values(nodeOffsets).some(o => o.dx || o.dy))
function resetPositions() {
  for (const key of Object.keys(nodeOffsets)) delete nodeOffsets[Number(key)]
}

function nodeTranslate(n: { phase: number; x: number; y: number }) {
  if (props.readonly) return `${n.x}, ${n.y}`
  const off = nodeOffsets[n.phase]
  if (!off) return `${n.x}, ${n.y}`
  return `${n.x + off.dx}, ${n.y + off.dy}`
}

/** 对边路径点施加节点偏移，使边跟随拖拽。只读模式不偏移。 */
function offsetEdgePoints(pts: { x: number; y: number }[], srcPhase: number, tgtPhase: number) {
  if (props.readonly) return pts
  const srcOff = nodeOffsets[srcPhase] || { dx: 0, dy: 0 }
  const tgtOff = nodeOffsets[tgtPhase] || { dx: 0, dy: 0 }
  if (!srcOff.dx && !srcOff.dy && !tgtOff.dx && !tgtOff.dy) return pts
  const n = pts.length
  if (n < 2) return pts
  return pts.map((p, i) => {
    const t = i / (n - 1)
    return {
      x: p.x + srcOff.dx * (1 - t) + tgtOff.dx * t,
      y: p.y + srcOff.dy * (1 - t) + tgtOff.dy * t,
    }
  })
}

/** 带偏移的边路径 D 属性。 */
function edgePathD(e: DagEdge) {
  const pts = offsetEdgePoints(e.points, Number(e.from), Number(e.to))
  return 'M' + pts.map(p => `${p.x},${p.y}`).join(' L')
}

/** 带偏移的边标签位置。 */
function edgeLabelPos(e: DagEdge) {
  const pts = offsetEdgePoints(e.points, Number(e.from), Number(e.to))
  const mid = Math.floor(pts.length / 2)
  return {
    x: pts.length % 2 === 0 && mid > 0 ? (pts[mid - 1].x + pts[mid].x) / 2 : pts[mid].x,
    y: pts.length % 2 === 0 && mid > 0 ? (pts[mid - 1].y + pts[mid].y) / 2 : pts[mid].y,
  }
}

function onEdgeEnter(from: number, to: number) {
  const ce = contractEdgeFor(from, to) || null
  hoveredEdge.value = ce
  emit('hover-edge', ce)
}
function onEdgeLeave() {
  hoveredEdge.value = null
  emit('hover-edge', null)
}
function onEdgeClick(from: number, to: number) {
  const ce = contractEdgeFor(from, to)
  if (props.selectedEdge && props.selectedEdge.fromPhase === from && props.selectedEdge.toPhase === to) {
    emit('select-edge', null)
  } else {
    emit('select-edge', ce || null)
  }
}

function nodeStatus(phase: number) {
  return props.phaseStatuses.get(phase) || 'pending'
}

const sortedPhases = computed(() =>
  [...props.phases].sort((a, b) => a.phase - b.phase)
)

const mode = computed(() => props.squadMode || 'SEQUENTIAL')

// ── DAG 布局 ──
const NODE_W = computed(() => props.compact ? 120 : 160)
const NODE_H = computed(() => props.compact ? 36 : 50)
const PAD = computed(() => props.compact ? 12 : 16)
const MIN_SVG_W = computed(() => props.compact ? 160 : 200)

interface DagNode extends PhaseNode {
  x: number; y: number
}
interface DagEdge {
  from: string; to: string
  points: { x: number; y: number }[]
  dataSize: number
  contractCount: number
  sourceMode: string     // 来源阶段的间模式
  sourceModeColor: string
  labelX: number
  labelY: number
}

const layout = computed(() => {
  const phases = sortedPhases.value
  if (!phases.length) return { nodes: [] as DagNode[], edges: [] as DagEdge[], svgW: MIN_SVG_W.value, svgH: 100 }

  const g = new dagre.graphlib.Graph()
  g.setDefaultEdgeLabel(() => ({}))
  g.setGraph({ rankdir: 'TB', nodesep: 40, ranksep: 50, marginx: 0, marginy: 0 })

  for (const p of phases) {
    g.setNode(String(p.phase), { width: NODE_W.value, height: NODE_H.value })
  }

  // 按 dependsOn 构建边（依赖数据驱动），无显式依赖时按 squadMode 推断
  const hasDependsOn = phases.some(p => p.dependsOn && p.dependsOn.length > 0)
  if (hasDependsOn) {
    for (const p of phases) {
      if (p.dependsOn && p.dependsOn.length > 0) {
        for (const dep of p.dependsOn) {
          g.setEdge(String(dep), String(p.phase))
        }
      }
    }
    // 标注每边的间模式：取自来源阶段的 mode，或 squadMode
    // 无出边的阶段自动为其添加指向虚拟 __end__ 的边以辅助 dagre 布局
    const hasOutEdge = new Set<string>()
    for (const e of g.edges()) hasOutEdge.add(e.v)
    for (const p of phases) {
      if (!hasOutEdge.has(String(p.phase))) {
        if (!g.node('__end__')) g.setNode('__end__', { width: 0, height: 0 })
        g.setEdge(String(p.phase), '__end__')
      }
    }
  } else if (mode.value === 'HIERARCHICAL' && phases.length > 1) {
    for (let i = 1; i < phases.length; i++) {
      g.setEdge(String(phases[0].phase), String(phases[i].phase))
    }
  } else if (mode.value === 'PARALLEL' || mode.value === 'CONSENSUS') {
    g.setNode('__start__', { width: 0, height: 0 })
    g.setNode('__end__', { width: 0, height: 0 })
    for (const p of phases) {
      g.setEdge('__start__', String(p.phase))
      g.setEdge(String(p.phase), '__end__')
    }
  } else {
    for (let i = 0; i < phases.length - 1; i++) {
      g.setEdge(String(phases[i].phase), String(phases[i + 1].phase))
    }
  }

  dagre.layout(g)

  // 构建边数据，合并 contractEdges 信息
  const edgeMap = new Map<string, ContractEdge>()
  for (const ce of props.contractEdges) {
    edgeMap.set(`${ce.fromPhase}->${ce.toPhase}`, ce)
  }

  const edgeData: DagEdge[] = []
  for (const e of g.edges()) {
    if (e.v === '__start__' || e.v === '__end__' || e.w === '__start__' || e.w === '__end__') continue
    const ce = edgeMap.get(`${e.v}->${e.w}`)
    // 边模式：有 dependsOn 时取来源阶段 mode，否则用 squadMode
    const src = phases.find(p => String(p.phase) === e.v)
    const edgeMode = hasDependsOn ? (src?.mode || mode.value) : mode.value
    const pts = g.edge(e).points
    // 边标签位于路径中段
    const mid = Math.floor(pts.length / 2)
    const lx = pts.length % 2 === 0 && mid > 0 ? (pts[mid - 1].x + pts[mid].x) / 2 : pts[mid].x
    const ly = pts.length % 2 === 0 && mid > 0 ? (pts[mid - 1].y + pts[mid].y) / 2 : pts[mid].y
    edgeData.push({
      from: e.v, to: e.w,
      points: pts,
      dataSize: ce?.dataSize || 0,
      contractCount: ce?.contracts.length || 0,
      sourceMode: edgeMode,
      sourceModeColor: modeColor(edgeMode),
      labelX: lx,
      labelY: ly,
    })
  }

  // 提取节点坐标
  const nw = NODE_W.value, nh = NODE_H.value, pad = PAD.value, minW = MIN_SVG_W.value
  const nodeData: DagNode[] = phases.map(p => {
    const n = g.node(String(p.phase))
    return { ...p, x: n.x - nw / 2, y: n.y - nh / 2 }
  })

  // 计算 SVG 视口
  let maxX = 0, maxY = 0
  for (const n of nodeData) {
    maxX = Math.max(maxX, n.x + nw)
    maxY = Math.max(maxY, n.y + nh)
  }
  for (const e of edgeData) {
    for (const pt of e.points) {
      maxX = Math.max(maxX, pt.x)
      maxY = Math.max(maxY, pt.y)
    }
  }

  return {
    nodes: nodeData,
    edges: edgeData,
    svgW: Math.max(maxX + pad, minW),
    svgH: maxY + pad,
  }
})

const maxDataSize = computed(() =>
  Math.max(1, ...layout.value.edges.map(e => e.dataSize))
)

function edgeWidth(dataSize: number) {
  const ratio = maxDataSize.value > 0 ? dataSize / maxDataSize.value : 0
  return 2 + ratio * 1.5 // 2px ~ 3.5px，缩小变化范围使箭头统一
}

// ── 样式工具 ──

/** 编排模式对应的色值。 */
function modeColor(mode: string): string {
  switch (mode) {
    case 'SEQUENTIAL': return '#007AFF'
    case 'PARALLEL': return '#34C759'
    case 'CONSENSUS': return '#AF52DE'
    case 'HIERARCHICAL': return '#FF9500'
    case 'CONDITIONAL': return '#FFD60A'
    default: return '#86868B'
  }
}

/** 编排模式对应的边框样式。 */
function modeBorder(mode: string): string {
  switch (mode) {
    case 'SEQUENTIAL': return ''
    case 'PARALLEL': return '5,3'
    case 'CONSENSUS': return '2,3'
    case 'HIERARCHICAL': return '10,3,2,3'
    case 'CONDITIONAL': return '8,3'
    default: return ''
  }
}
/** 编排模式对应的图标字符。 */
function modeIcon(mode: string): string {
  switch (mode) {
    case 'SEQUENTIAL': return '→'
    case 'PARALLEL': return '⇉'
    case 'CONSENSUS': return '◎'
    case 'HIERARCHICAL': return '◈'
    case 'CONDITIONAL': return '◇'
    default: return '○'
  }
}

/** 编排模式缩写标签。 */
function modeLabel(mode: string): string {
  switch (mode) {
    case 'SEQUENTIAL': return 'SEQ'
    case 'PARALLEL': return 'PAR'
    case 'CONSENSUS': return 'CON'
    case 'HIERARCHICAL': return 'HIE'
    case 'CONDITIONAL': return 'CND'
    default: return mode.slice(0, 3)
  }
}

/** HITL 模式缩写标签。 */
function hitlModeLabel(mode?: string): string {
  switch (mode) {
    case 'PRE': return 'PRE'
    case 'POST': return 'POST'
    case 'ALL': return 'ALL'
    case 'AGENT_LIST': return 'LIST'
    default: return 'HITL'
  }
}

/** HITL 模式对应颜色。 */
function hitlModeColor(mode?: string): string {
  switch (mode) {
    case 'PRE': return '#007AFF'    // 蓝色 — 执行前
    case 'ALL': return '#FF3B30'    // 红色 — 双向
    case 'AGENT_LIST': return '#FF9500' // 橙色 — 指定 Agent
    default: return '#FF9500'       // 橙色 — POST / 旧版 hitl
  }
}

/** 边是否处于激活态（hover 或 selected）。 */
function edgeActive(e: DagEdge) {
  const from = Number(e.from), to = Number(e.to)
  if (props.selectedEdge && props.selectedEdge.fromPhase === from && props.selectedEdge.toPhase === to) return true
  if (hoveredEdge.value && hoveredEdge.value.fromPhase === from && hoveredEdge.value.toPhase === to) return true
  return false
}
/** 边颜色：selected > hovered > 默认。 */
function edgeStroke(e: DagEdge) {
  const from = Number(e.from), to = Number(e.to)
  if (props.selectedEdge && props.selectedEdge.fromPhase === from && props.selectedEdge.toPhase === to) return 'var(--clr-accent)'
  if (hoveredEdge.value && hoveredEdge.value.fromPhase === from && hoveredEdge.value.toPhase === to) return 'var(--clr-accent)'
  return 'var(--clr-hairline)'
}
/** 边箭头 marker：选中/悬浮用强调色，否则默认。 */
function edgeMarker(e: DagEdge) {
  const from = Number(e.from), to = Number(e.to)
  if (props.selectedEdge && props.selectedEdge.fromPhase === from && props.selectedEdge.toPhase === to) return 'url(#arrow-active)'
  if (hoveredEdge.value && hoveredEdge.value.fromPhase === from && hoveredEdge.value.toPhase === to) return 'url(#arrow-active)'
  return 'url(#arrow)'
}

function formatSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  return `${(bytes / 1024).toFixed(1)} KB`
}

// 找出与边匹配的 contract edge（按起止阶段配对）
function contractEdgeFor(from: number, to: number) {
  return props.contractEdges.find(
    e => e.fromPhase === from && e.toPhase === to
  )
}
</script>

<template>
  <div class="cf-root">
    <div v-if="layout.nodes.length" class="cf-canvas">
    <svg
      :viewBox="viewBox"
      :class="['cf-svg', { 'cf-svg--dragging': !!dragging, 'cf-svg--panning': panning }]"
      xmlns="http://www.w3.org/2000/svg"
      @mousedown="onCanvasMouseDown"
      @mousemove="onCanvasMouseMove"
      @mouseup="onCanvasMouseUp"
      @mouseleave="onCanvasMouseUp"
    >
      <!-- 箭头定义（markerUnits=userSpaceOnUse 确保箭头不随边宽缩放） -->
      <defs>
        <!-- 边缘发光滤镜 -->
        <filter id="edge-glow" x="-20%" y="-20%" width="140%" height="140%">
          <feGaussianBlur stdDeviation="3" result="blur" />
          <feComposite in="SourceGraphic" in2="blur" operator="over" />
        </filter>
        <!-- 节点脉冲发光（运行态） -->
        <filter id="node-pulse" x="-20%" y="-20%" width="140%" height="140%">
          <feGaussianBlur stdDeviation="4" result="blur" />
          <feFlood flood-color="var(--clr-accent)" flood-opacity="0.3" result="color" />
          <feComposite in="color" in2="blur" operator="in" result="glow" />
          <feMerge><feMergeNode in="glow"/><feMergeNode in="SourceGraphic"/></feMerge>
        </filter>
        <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5"
                markerWidth="10" markerHeight="10" markerUnits="userSpaceOnUse"
                orient="auto-start-reverse">
          <path d="M 0 0 L 10 5 L 0 10 z" fill="var(--clr-hairline)" />
        </marker>
        <marker id="arrow-active" viewBox="0 0 10 10" refX="9" refY="5"
                markerWidth="10" markerHeight="10" markerUnits="userSpaceOnUse"
                orient="auto-start-reverse">
          <path d="M 0 0 L 10 5 L 0 10 z" fill="var(--clr-accent)" />
        </marker>
      </defs>

      <!-- 边 -->
      <g v-for="e in layout.edges" :key="`e-${e.from}-${e.to}`" class="cf-svg__edge-area" style="cursor:pointer">
        <!-- 点击热区（宽透明路径，使用偏移边路径） -->
        <path
          :d="edgePathD(e)"
          stroke="transparent" stroke-width="14" fill="none"
          @click="onEdgeClick(Number(e.from), Number(e.to))"
          @mouseenter="onEdgeEnter(Number(e.from), Number(e.to))"
          @mouseleave="onEdgeLeave()"
        />
        <!-- 可见边路径 -->
        <path
          :d="edgePathD(e)"
          :stroke="edgeStroke(e)"
          :stroke-width="edgeWidth(e.dataSize)"
          fill="none"
          stroke-linecap="round"
          :marker-end="edgeMarker(e)"
          :class="['cf-svg__edge', { 'cf-svg__edge--active': edgeActive(e) }]"
          @click="onEdgeClick(Number(e.from), Number(e.to))"
          @mouseenter="onEdgeEnter(Number(e.from), Number(e.to))"
          @mouseleave="onEdgeLeave()"
        />
        <!-- 边信息标签 -->
        <title>{{ e.sourceMode }} · {{ e.contractCount }} 条契约 · {{ formatSize(e.dataSize) }}</title>
        <!-- 边模式标签（可点击选中边） -->
        <g v-if="!compact" :transform="`translate(${edgeLabelPos(e).x}, ${edgeLabelPos(e).y - 8})`" style="cursor:pointer"
           @click="onEdgeClick(Number(e.from), Number(e.to))"
           @mouseenter="onEdgeEnter(Number(e.from), Number(e.to))"
           @mouseleave="onEdgeLeave()">
          <rect x="-18" y="-8" width="36" height="16" rx="4" fill="var(--clr-bg)" stroke="var(--clr-hairline)" stroke-width="1" />
          <text x="0" y="1" font-size="8" font-weight="700" fill="var(--clr-label)" text-anchor="middle" dominant-baseline="central">
            {{ modeLabel(e.sourceMode) }}
          </text>
        </g>
      </g>

      <!-- 节点 -->
      <g
        v-for="n in layout.nodes"
        :key="`n-${n.phase}`"
        :transform="`translate(${nodeTranslate(n)})`"
        :class="['cf-svg__node', { 'cf-svg__node--selected': selectedPhase === n.phase, 'cf-svg__node--readonly': readonly, 'cf-svg__node--dragging': dragging?.phase === n.phase }]"
        @mousedown="onNodeMouseDown(n.phase, $event)"
        @click="!readonly && emit('select-phase', n.phase)"
        :style="{ cursor: readonly ? 'default' : 'grab' }"
      >
        <rect
          :width="NODE_W" :height="NODE_H" :rx="compact ? 5 : 8"
          :class="[
            'cf-svg__node-bg',
            `cf-svg__node-bg--${nodeStatus(n.phase)}`,
          ]"
          :filter="nodeStatus(n.phase) === 'running' ? 'url(#node-pulse)' : 'drop-shadow(0 1px 2px var(--clr-shadow-lg))'"
        />
        <!-- 模式图标（仅当 n.mode 存在时显示，否则灰色占位） -->
        <text x="12" y="18" font-size="11" text-anchor="middle"
              :fill="n.mode ? modeColor(n.mode) : 'var(--clr-quaternary)'" dominant-baseline="central">
          {{ n.mode ? modeIcon(n.mode) : '·' }}
        </text>
        <!-- 阶段名称 + 模式标记 -->
        <template v-if="!compact">
          <!-- 阶段名称 -->
          <text x="34" y="14" font-size="12" font-weight="600"
                fill="var(--clr-label)" dominant-baseline="central">
            {{ n.name || `阶段 ${n.phase}` }}
          </text>
          <!-- Agent 数 + HITL -->
          <text x="22" y="28" font-size="10" fill="var(--clr-tertiary)" dominant-baseline="central">
            <tspan v-if="n.agents?.length">{{ n.agents.length }} Agent</tspan>
            <tspan v-if="n.hitlMode" :fill="hitlModeColor(n.hitlMode)">{{ ' · ' + hitlModeLabel(n.hitlMode) }}</tspan>
          </text>
          <!-- 阶段内模式标签（仅当有明确值时显示） -->
          <template v-if="n.mode">
            <rect x="22" y="34" width="36" height="14" rx="4" :fill="modeColor(n.mode)" />
            <text x="40" y="41" font-size="9" font-weight="700"
                  fill="#fff" text-anchor="middle" dominant-baseline="central">
              {{ modeLabel(n.mode) }}
            </text>
          </template>
          <text v-else x="22" y="41" font-size="9" fill="var(--clr-quaternary)" dominant-baseline="central">
            — — —
          </text>
        </template>
        <template v-else>
          <text x="22" y="18" font-size="10" font-weight="600"
                fill="var(--clr-label)" dominant-baseline="central">
            {{ n.name || `阶段 ${n.phase}` }}
          </text>
        </template>
        <!-- 模式边框叠加（仅当有明确值时使用模式专属 dasharray，否则不显示） -->
        <template v-if="n.mode">
          <rect
            :width="NODE_W" :height="NODE_H" :rx="compact ? 5 : 8"
            fill="none" stroke-width="1.5"
            :stroke="modeColor(n.mode)"
            :stroke-dasharray="modeBorder(n.mode)"
            :opacity="compact ? 0 : 1"
          />
        </template>
        <!-- 删除按钮（编辑模式） -->
        <g v-if="!readonly && !compact" class="cf-svg__node-del" @click.stop="emit('delete-phase', n.phase)">
          <circle :cx="NODE_W - 1" cy="1" r="9" fill="var(--clr-bg)" stroke="var(--clr-hairline)" stroke-width="1" />
          <text :x="NODE_W - 1" y="2" font-size="10" text-anchor="middle" fill="var(--clr-red)" dominant-baseline="central">✕</text>
        </g>
      </g>
    </svg>
    </div> <!-- end cf-canvas -->
    <div v-else class="cf-empty">暂无阶段数据</div>
    <!-- 阶段间模式（Squad mode）标识 -->
    <div class="cf-footer">
      <div class="cf-footer__l">
        <div v-if="!compact" class="cf-mode-bar">
          <span class="cf-mode-bar__dot" :style="{ background: modeColor(mode) }"></span>
          <span class="cf-mode-bar__label">Squad · {{ modeLabel(mode) }}</span>
        </div>
        <div class="cf-zoom">
          <button class="cf-zoom__btn" @click="zoomOut" :disabled="zoom <= 0.25">−</button>
          <span class="cf-zoom__val">{{ Math.round(zoom * 100) }}%</span>
          <button class="cf-zoom__btn" @click="zoomIn" :disabled="zoom >= 3">+</button>
          <button class="cf-zoom__btn cf-zoom__btn--reset" @click="zoomCenter" title="居中">⊡</button>
          <button class="cf-zoom__btn cf-zoom__btn--reset" @click="zoomReset" :disabled="zoom === 1">1:1</button>
        </div>
      </div>
      <button v-if="hasDirty" class="cf-reset-btn" @click="resetPositions()">重置布局</button>
    </div>
  </div>
</template>

<style scoped>
.cf-root {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.cf-canvas {
  flex: 1;
  overflow: auto;
}

.cf-svg {
  display: block;
  width: 100%;
  height: 100%;
  min-width: 240px;
  user-select: none;
}
.cf-svg--dragging { cursor: grabbing; }
.cf-svg--panning { cursor: grab; }

/* ── 边 ── */
.cf-svg__edge {
  transition: stroke 0.25s, filter 0.25s;
}
.cf-svg__edge--active {
  filter: drop-shadow(0 0 4px var(--clr-accent));
}

/* ── 节点 ── */
.cf-svg__node-bg {
  fill: var(--clr-bg);
  stroke: var(--clr-hairline);
  stroke-width: 1.5;
  transition: stroke 0.2s, fill 0.2s;
}
.cf-svg__node:hover .cf-svg__node-bg {
  fill: var(--clr-fill-hover);
}
.cf-svg__node--selected .cf-svg__node-bg {
  stroke: var(--clr-accent);
  fill: var(--clr-selected);
}
.cf-svg__node--readonly:hover .cf-svg__node-bg {
  fill: var(--clr-bg);
}
.cf-svg__node-bg--running {
  stroke: var(--clr-accent);
}
.cf-svg__node-bg--completed {
  stroke: var(--clr-green);
}
.cf-svg__node-bg--failed {
  stroke: var(--clr-red);
}
.cf-svg__node-bg--hitl {
  stroke: var(--clr-orange);
}
.cf-svg__node--dragging .cf-svg__node-bg {
  stroke: var(--clr-accent);
  fill: var(--clr-selected);
}

.cf-empty {
  text-align: center;
  padding: 24px;
  color: var(--clr-tertiary);
  font-size: var(--fs-caption);
}

/* ── 底部栏 ── */
.cf-footer {
  display:flex; align-items:center; justify-content:space-between;
  margin-top:10px; padding:0 8px; gap:8px;
}
.cf-footer__l { display:flex; align-items:center; gap:12px; }
.cf-mode-bar { display:flex; align-items:center; gap:6px; font-size:var(--fs-caption); color:var(--clr-tertiary); }
.cf-mode-bar__dot { width:8px; height:8px; border-radius:50%; flex-shrink:0; }
.cf-mode-bar__label { font-weight:var(--fw-medium); }
.cf-zoom { display:flex; align-items:center; gap:2px; }
.cf-zoom__btn {
  width:22px; height:22px; display:inline-flex; align-items:center; justify-content:center;
  font-size:12px; font-family:inherit; border:1px solid var(--clr-hairline); border-radius:var(--rad-xs);
  background:var(--clr-bg); color:var(--clr-label); cursor:pointer; padding:0;
}
.cf-zoom__btn:hover { background:var(--clr-fill-hover); }
.cf-zoom__btn:disabled { opacity:0.3; cursor:not-allowed; }
.cf-zoom__btn--reset { width:auto; padding:0 6px; font-size:var(--fs-caption); }
.cf-zoom__val { font-size:var(--fs-caption); color:var(--clr-tertiary); min-width:36px; text-align:center; }
.cf-reset-btn {
  font-size:var(--fs-footnote); padding:2px 8px; border-radius:var(--rad-sm);
  border:1px solid var(--clr-hairline); background:var(--clr-bg);
  color:var(--clr-accent); cursor:pointer; font-family:inherit;
}
.cf-reset-btn:hover { background:var(--clr-fill-hover); }

/* ── 节点删除按钮 ── */
.cf-svg__node-del { opacity:0; transition: opacity 0.15s; }
.cf-svg__node:hover .cf-svg__node-del { opacity:1; }
.cf-svg__node-del:hover circle { fill: var(--clr-red); stroke: var(--clr-red); }
.cf-svg__node-del:hover text { fill: #fff; }

/* ── 运行态脉冲动画 ── */
@keyframes node-pulse {
  0%, 100% { filter: drop-shadow(0 0 2px var(--clr-accent)); }
  50% { filter: drop-shadow(0 0 8px var(--clr-accent)); }
}
.cf-svg__node-bg--running {
  animation: node-pulse 2s ease-in-out infinite;
}
</style>
