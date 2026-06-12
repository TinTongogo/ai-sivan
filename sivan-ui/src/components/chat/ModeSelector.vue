<template>
  <div class="mode-selector">
    <button
      v-for="m in modes"
      :key="m.value"
      :class="['mode-btn', { active: modelValue === m.value }]"
      @click="$emit('update:modelValue', m.value)"
      :title="m.tip"
    >
      {{ m.label }}
    </button>
  </div>
</template>

<script setup lang="ts">
defineProps<{ modelValue: string }>()
defineEmits<{ 'update:modelValue': [value: string] }>()

const modes = [
  { value: 'STREAM', label: '实时', tip: '流式输出，逐 token 展示' },
  { value: 'SUMMARY', label: '汇总', tip: '完成后一次性展示结果' },
]
</script>

<style scoped>
.mode-selector {
  display: inline-flex;
  border: 1px solid var(--border-color, #e0e0e0);
  border-radius: 6px;
  overflow: hidden;
}
.mode-btn {
  padding: 4px 12px;
  font-size: 12px;
  border: none;
  background: transparent;
  cursor: pointer;
  transition: all .15s;
}
.mode-btn.active {
  background: var(--accent-color, #409eff);
  color: #fff;
}
.mode-btn:not(.active):hover { background: var(--bg-hover, #f0f0f0); }
</style>
