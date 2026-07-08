<script setup lang="ts">
// FE-03 정적 셸 헤더. 검색·장바구니는 형태만이며 동작은 후속 트랙에서 연결한다.
</script>

<template>
  <header class="sticky top-0 z-50 border-b border-gray-100 bg-white">
    <div class="mx-auto flex max-w-[1240px] items-center gap-4 px-4 py-4 md:gap-6 md:px-6">
      <NuxtLink to="/" class="shrink-0 text-xl font-bold tracking-tight text-gray-900">
        zslab-mall
      </NuxtLink>

      <!-- 검색: 형태만·비율 크게 강조 -->
      <div class="flex-1">
        <label class="relative block">
          <span class="sr-only">상품 검색</span>
          <span class="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-4 text-gray-400">
            <svg class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2" aria-hidden="true">
              <path stroke-linecap="round" stroke-linejoin="round" d="M21 21l-4.35-4.35m1.35-5.4a7.5 7.5 0 11-15 0 7.5 7.5 0 0115 0z" />
            </svg>
          </span>
          <input
            type="search"
            placeholder="찾으시는 상품을 검색해 보세요"
            aria-label="상품 검색"
            class="w-full rounded-full border border-gray-200 bg-gray-50 py-3 pl-12 pr-4 text-sm text-gray-900 placeholder-gray-400 transition duration-200 focus:border-gray-900 focus:bg-white focus:outline-none focus:ring-1 focus:ring-gray-900"
          />
        </label>
      </div>

      <button
        type="button"
        aria-label="장바구니"
        class="shrink-0 rounded-full p-2 text-gray-700 transition duration-200 hover:bg-gray-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-gray-900"
      >
        <svg class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
          <path stroke-linecap="round" stroke-linejoin="round" d="M2.25 3h1.386c.51 0 .955.343 1.087.835l.383 1.437M7.5 14.25a3 3 0 00-3 3h15.75m-12.75-3h11.218c1.121-2.3 2.1-4.684 2.924-7.138a60.114 60.114 0 00-16.536-1.84M7.5 14.25L5.106 5.272M6 20.25a.75.75 0 11-1.5 0 .75.75 0 011.5 0zm12.75 0a.75.75 0 11-1.5 0 .75.75 0 011.5 0z" />
        </svg>
      </button>
    </div>
  </header>
</template>
