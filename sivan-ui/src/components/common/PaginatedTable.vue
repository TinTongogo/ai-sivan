<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from '../../utils/i18n'

const { t } = useI18n()

const props = withDefaults(defineProps<{
  currentPage?: number
  totalPages?: number
  total?: number
  pageSize?: number
  pageSizeOptions?: number[]
  hideOnSinglePage?: boolean
}>(), {
  currentPage: 1,
  totalPages: 1,
  total: 0,
  pageSize: 20,
  pageSizeOptions: () => [10, 20, 50, 100],
  hideOnSinglePage: false,
})

const emit = defineEmits<{
  'page-change': [page: number]
  'page-size-change': [size: number]
}>()

const showPagination = computed(() => {
  if (props.hideOnSinglePage && props.totalPages <= 1) return false
  return true
})

const gotoPage = ref(props.currentPage)
watch(() => props.currentPage, (val) => { gotoPage.value = val })

const visiblePages = computed(() => {
  const total = props.totalPages
  const current = props.currentPage
  const maxVisible = 7
  if (total <= maxVisible) return Array.from({ length: total }, (_, i) => i + 1)
  const pages: (number | string)[] = [1]
  let start = Math.max(2, current - 2)
  let end = Math.min(total - 1, current + 2)
  if (current <= 3) { start = 2; end = Math.min(total - 1, maxVisible - 1) }
  if (current >= total - 2) { start = Math.max(2, total - maxVisible + 2); end = total - 1 }
  if (start > 2) pages.push('...')
  for (let i = start; i <= end; i++) pages.push(i)
  if (end < total - 1) pages.push('...')
  if (total > 1) pages.push(total)
  return pages
})

function emitPage(p: number) {
  if (p < 1 || p > props.totalPages || p === props.currentPage) return
  emit('page-change', p)
}

function emitPageSize(e: Event) {
  const val = Number((e.target as HTMLSelectElement).value)
  emit('page-size-change', val)
}

function handleGoto() {
  let p = gotoPage.value
  if (isNaN(p) || p < 1) p = 1
  if (p > props.totalPages) p = props.totalPages
  emitPage(p)
}
</script>

<template>
  <div class="card pt-card">
    <div v-if="$slots.header" class="card__header">
      <slot name="header" />
    </div>
    <div class="pt-body">
      <div class="pt-scroll">
        <table class="table pt-table">
          <thead class="pt-thead"><slot name="thead" /></thead>
          <tbody><slot name="tbody" /></tbody>
        </table>
      </div>
      <div v-if="showPagination" class="pt-pager">
        <div class="pager">
          <div class="pager__pages">
            <button class="btn btn-sm" :disabled="currentPage <= 1" @click="emitPage(currentPage - 1)">‹</button>
            <template v-for="p in visiblePages" :key="p">
              <span v-if="p === '...'" class="pager__ellipsis">…</span>
              <button v-else class="btn btn-sm" :class="{ 'btn-primary': p === currentPage }" @click="emitPage(p as number)">{{ p }}</button>
            </template>
            <button class="btn btn-sm" :disabled="currentPage >= totalPages" @click="emitPage(currentPage + 1)">›</button>
          </div>
          <span class="pager__info">{{ t('totalItems', { n: total }) }}</span>
          <span class="pager__goto">
            {{ t('jumpTo') }} <input v-model.number="gotoPage" class="input pager__input" @change="handleGoto" /> {{ t('page') }}
          </span>
          <select v-if="pageSizeOptions.length" class="select pager__size" :value="pageSize" @change="emitPageSize">
            <option v-for="s in pageSizeOptions" :key="s" :value="s">{{ s }} {{ t('itemsPerPage') }}</option>
          </select>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ===== Card container ===== */
.pt-card {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  /* keep .card's overflow:hidden — required for flex height constraint chain */
}

/* ===== Body: scroll area + pager ===== */
.pt-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 0;
}

/* ===== Scrollable table wrapper ===== */
.pt-scroll {
  flex: 1;
  overflow: auto;  /* both x and y scroll */
  min-height: 0;
}

/* ===== Table ===== */
.pt-table {
  border-collapse: separate;
  border-spacing: 0;
}

/* ===== Sticky thead ===== */
.pt-thead {
  position: sticky;
  top: 0;
  z-index: 1;
  background: var(--clr-bg);
}

/* ===== Pager bar ===== */
.pt-pager {
  flex-shrink: 0;
  border-top: 1px solid var(--clr-hairline);
}

/* ===== Pager inner ===== */
.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 10px 20px;
  font-size: var(--fs-footnote);
  color: var(--clr-label);
}
.pager__pages {
  display: flex;
  align-items: center;
  gap: 2px;
}
.pager__ellipsis {
  padding: 0 4px;
  color: var(--clr-tertiary);
  font-size: var(--fs-footnote);
}
.pager__info {
  color: var(--clr-secondary);
  white-space: nowrap;
}
.pager__goto {
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--clr-secondary);
  white-space: nowrap;
}
.pager__input {
  width: 48px !important;
  padding: 3px 6px !important;
  text-align: center;
  font-size: var(--fs-footnote);
}
.pager__size {
  width: auto !important;
  padding: 3px 6px !important;
  font-size: var(--fs-footnote);
}
</style>
