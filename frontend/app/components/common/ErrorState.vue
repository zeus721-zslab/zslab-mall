<script setup lang="ts">
// 홈·목록 공용 에러 상태(FE-05 승격·원본 HomeProductGrid). 재시도는 상위로 위임(emit)한다 — 내부에서 refetch를 직접 호출하지 않는다.
withDefaults(defineProps<{ message?: string }>(), { message: '상품을 불러오지 못했습니다' })
const emit = defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="flex flex-col items-center justify-center gap-4 py-20 text-center">
    <svg class="h-12 w-12 text-gray-300" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
      <path stroke-linecap="round" stroke-linejoin="round" d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126zM12 15.75h.007v.008H12v-.008z" />
    </svg>
    <p class="text-sub">{{ message }}</p>
    <button
      type="button"
      class="rounded-full border border-gray-300 px-5 py-2 text-sm font-medium text-gray-700 transition duration-200 hover:bg-gray-50 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-gray-900"
      @click="emit('retry')"
    >
      다시 시도
    </button>
  </div>
</template>
