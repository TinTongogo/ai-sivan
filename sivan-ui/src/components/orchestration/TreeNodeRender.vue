<script setup lang="ts">
import { computed } from 'vue'

interface TreeNode {
  name: string
  status: string
  mode?: string
  agent?: string
  reasoning?: string
  output?: string
  isLeaf?: boolean
  toolCalls?: { name: string; count: number; status: string }[]
  children?: TreeNode[]
}

const props = defineProps<{
  node: TreeNode
  depth?: number
}>()

const depth = computed(() => props.depth ?? 0)
const hasOutput = computed(() => props.node.output && props.node.output.length > 0)
</script>

<template>
  <div>
    <!-- 内层节点 → 标题样式 -->
    <div v-if="node.mode" :style="{
      marginLeft: depth * 16 + 'px',
      marginTop: depth === 0 ? '0' : '12px',
      marginBottom: '4px',
      fontWeight: 600,
      fontSize: depth === 0 ? '1.05em' : '0.95em',
      color: 'var(--clr-label)',
    }">
      {{ node.reasoning || node.name }}
      <span v-if="node.agent" :style="{ color: 'var(--clr-tertiary)', marginLeft: '8px', fontSize: '0.85em' }">
        [{{ node.agent }}]
      </span>
    </div>

    <!-- 叶子节点 → 产出内容 -->
    <div v-if="hasOutput" :style="{
      marginLeft: depth * 16 + 'px',
      paddingLeft: '8px',
      borderLeft: '2px solid var(--clr-hairline, #e0e0e0)',
      lineHeight: 1.7,
      color: 'var(--clr-label)',
      whiteSpace: 'pre-wrap',
      overflowWrap: 'break-word',
    }">
      {{ node.output }}
    </div>

    <!-- 递归渲染子节点 -->
    <TreeNodeRender
      v-for="(child, idx) in node.children?.filter(c => c.output || c.children?.length)"
      :key="idx"
      :node="child"
      :depth="depth + 1"
    />
  </div>
</template>
