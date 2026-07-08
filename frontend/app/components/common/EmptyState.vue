<script setup lang="ts">
// 홈·목록 공용 빈 상태(FE-05 승격·원본 HomeProductGrid). 표시 문구만 prop으로 받고, 빈 판정(items 0 등)은 부모가 한다.
withDefaults(defineProps<{ message?: string }>(), { message: '등록된 상품이 없습니다' })
</script>

<template>
  <div class="flex flex-col items-center justify-center gap-4 py-20 text-center">
    <svg class="h-14 w-14 text-gray-300" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
      <path stroke-linecap="round" stroke-linejoin="round" d="M15.75 10.5V6a3.75 3.75 0 10-7.5 0v4.5m11.356-1.993l1.263 12c.07.665-.45 1.243-1.119 1.243H4.25a1.125 1.125 0 01-1.12-1.243l1.264-12A1.125 1.125 0 015.513 7.5h12.974c.576 0 1.059.435 1.119 1.007z" />
    </svg>
    <p class="text-sub">{{ message }}</p>
  </div>
</template>
