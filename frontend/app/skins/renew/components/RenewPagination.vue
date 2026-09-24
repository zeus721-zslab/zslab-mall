<script setup lang="ts">
// renew 번호 페이지(FE-69 목록에서 분리·FE-74 검색 결과와 공용). 현재 페이지 앞뒤 PAGE_WINDOW개만 보이고, 1페이지뿐이면 숨긴다.
const props = defineProps<{ page: number; totalPages: number }>()
const emit = defineEmits<{ change: [page: number] }>()

// 번호 페이지는 현재 페이지 앞뒤로 이만큼만 보인다.
const PAGE_WINDOW = 2
const ARROW_BUTTON =
  'flex h-11 min-w-11 items-center justify-center rounded-full text-body font-bold text-ink transition duration-fast ease-soft hover:bg-surface-muted disabled:opacity-40'

const pageNumbers = computed<number[]>(() => {
  const first = Math.max(1, props.page - PAGE_WINDOW)
  const last = Math.min(props.totalPages, props.page + PAGE_WINDOW)
  return Array.from({ length: last - first + 1 }, (_, index) => first + index)
})
</script>

<template>
  <nav v-if="totalPages > 1" aria-label="페이지" class="mt-14 flex items-center justify-center gap-1">
    <button
      type="button"
      :class="ARROW_BUTTON"
      :disabled="page <= 1"
      aria-label="이전 페이지"
      @click="emit('change', page - 1)"
    >
      ‹
    </button>
    <button
      v-for="pageNumber in pageNumbers"
      :key="pageNumber"
      type="button"
      :class="[
        'flex h-11 min-w-11 items-center justify-center rounded-full px-3 text-body font-semibold tabular-nums transition duration-fast ease-soft',
        pageNumber === page ? 'bg-primary text-primary-foreground' : 'text-ink hover:bg-surface-muted',
      ]"
      :aria-current="pageNumber === page ? 'page' : undefined"
      @click="emit('change', pageNumber)"
    >
      {{ pageNumber }}
    </button>
    <button
      type="button"
      :class="ARROW_BUTTON"
      :disabled="page >= totalPages"
      aria-label="다음 페이지"
      @click="emit('change', page + 1)"
    >
      ›
    </button>
  </nav>
</template>
