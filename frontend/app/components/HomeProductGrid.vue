<script setup lang="ts">
// FE-03: 상태 4종(Loading·Error·Empty·Success)을 이 컴포넌트가 판정한다.
// FE-05: Loading·Error·Empty 마크업을 common/ 3종으로 승격(소비처 = 홈·목록). 판정식은 여기 유지.
const { data, pending, error, refresh } = useProducts()

// 스켈레톤 개수 = 홈 노출 건수(8)와 동일.
const SKELETON_COUNT = 8
</script>

<template>
  <div class="mx-auto max-w-[1240px] px-4 md:px-6">
    <!-- Loading -->
    <div v-if="pending" class="grid grid-cols-2 gap-4 md:grid-cols-3 md:gap-6 lg:grid-cols-4">
      <CommonLoadingSkeleton :count="SKELETON_COUNT" />
    </div>

    <!-- Error -->
    <CommonErrorState v-else-if="error" @retry="refresh" />

    <!-- Empty -->
    <CommonEmptyState v-else-if="!data || data.items.length === 0" />

    <!-- Success -->
    <div v-else class="grid grid-cols-2 gap-4 md:grid-cols-3 md:gap-6 lg:grid-cols-4">
      <ProductCard v-for="item in data.items" :key="item.productPublicId" :product="item" />
    </div>
  </div>
</template>
