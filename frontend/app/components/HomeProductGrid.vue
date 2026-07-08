<script setup lang="ts">
// FE-03: 상태 4종(Loading·Error·Empty·Success)을 이 컴포넌트가 내부 처리한다.
// 공용 EmptyState·ErrorState·LoadingSkeleton 추출은 두 번째 목록 화면에서 승격(소비처 1개).
const { data, pending, error, refresh } = useProducts()

// 스켈레톤 개수 = 홈 노출 건수(8)와 동일.
const SKELETON_COUNT = 8
</script>

<template>
  <div class="mx-auto max-w-[1240px] px-4 md:px-6">
    <!-- Loading -->
    <div v-if="pending" class="grid grid-cols-2 gap-4 md:grid-cols-3 md:gap-6 lg:grid-cols-4">
      <div v-for="n in SKELETON_COUNT" :key="n" class="animate-pulse">
        <div class="aspect-square rounded-lg bg-gray-100"></div>
        <div class="mt-3 h-4 w-3/4 rounded bg-gray-100"></div>
        <div class="mt-2 h-3 w-1/2 rounded bg-gray-100"></div>
        <div class="mt-3 h-4 w-1/3 rounded bg-gray-100"></div>
      </div>
    </div>

    <!-- Error -->
    <div v-else-if="error" class="flex flex-col items-center justify-center gap-4 py-20 text-center">
      <svg class="h-12 w-12 text-gray-300" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
        <path stroke-linecap="round" stroke-linejoin="round" d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126zM12 15.75h.007v.008H12v-.008z" />
      </svg>
      <p class="text-gray-500">상품을 불러오지 못했습니다</p>
      <button
        type="button"
        class="rounded-full border border-gray-300 px-5 py-2 text-sm font-medium text-gray-700 transition duration-200 hover:bg-gray-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-gray-900"
        @click="refresh()"
      >
        다시 시도
      </button>
    </div>

    <!-- Empty -->
    <div v-else-if="!data || data.items.length === 0" class="flex flex-col items-center justify-center gap-4 py-20 text-center">
      <svg class="h-14 w-14 text-gray-300" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
        <path stroke-linecap="round" stroke-linejoin="round" d="M15.75 10.5V6a3.75 3.75 0 10-7.5 0v4.5m11.356-1.993l1.263 12c.07.665-.45 1.243-1.119 1.243H4.25a1.125 1.125 0 01-1.12-1.243l1.264-12A1.125 1.125 0 015.513 7.5h12.974c.576 0 1.059.435 1.119 1.007z" />
      </svg>
      <p class="text-gray-500">등록된 상품이 없습니다</p>
    </div>

    <!-- Success -->
    <div v-else class="grid grid-cols-2 gap-4 md:grid-cols-3 md:gap-6 lg:grid-cols-4">
      <ProductCard v-for="item in data.items" :key="item.productPublicId" :product="item" />
    </div>
  </div>
</template>
