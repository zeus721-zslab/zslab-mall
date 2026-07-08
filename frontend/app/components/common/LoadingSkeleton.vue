<script setup lang="ts">
// 홈·목록 공용 로딩 스켈레톤(FE-05 승격·원본 HomeProductGrid). 카드 1개(animate-pulse)를 count개 반복만 렌더한다.
// 그리드 wrapper는 소유하지 않는다 — 호출 측이 grid 컨테이너로 감싼다.
withDefaults(defineProps<{ count?: number }>(), { count: 8 })
</script>

<template>
  <div v-for="n in count" :key="n" class="animate-pulse">
    <div class="aspect-square rounded-lg bg-gray-100"></div>
    <div class="mt-3 h-4 w-3/4 rounded bg-gray-100"></div>
    <div class="mt-2 h-3 w-1/2 rounded bg-gray-100"></div>
    <div class="mt-3 h-4 w-1/3 rounded bg-gray-100"></div>
  </div>
</template>
