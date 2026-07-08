<script setup lang="ts">
import type { ProductSummary } from '~/types/product'

const props = defineProps<{
  product: ProductSummary
}>()

// displayPrice는 대표가(판매가능 variant 최저값·백엔드 DTO). 원화 천단위 구분 표기.
const formattedPrice = computed(() => `${props.product.displayPrice.toLocaleString('ko-KR')}원`)
</script>

<template>
  <!-- 상세 경로는 후속 트랙. 현재는 구조·포커스만 확보(href="#"). -->
  <a
    href="#"
    :aria-label="product.name"
    class="group block rounded-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-gray-900 focus-visible:ring-offset-2"
  >
    <div class="overflow-hidden rounded-lg border border-gray-100 bg-white transition duration-200 group-hover:-translate-y-1 group-hover:shadow-lg">
      <!-- 이미지 영역: 정사각·변경 경계 캡슐화. mainImageUrl 부재 시 회색 placeholder. -->
      <div class="relative aspect-square overflow-hidden bg-gray-100">
        <img
          v-if="product.mainImageUrl"
          :src="product.mainImageUrl"
          :alt="product.name"
          loading="lazy"
          class="h-full w-full object-cover transition duration-200 group-hover:scale-[1.03]"
        />
        <div v-else class="flex h-full w-full items-center justify-center text-gray-300">
          <svg class="h-12 w-12" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
            <path stroke-linecap="round" stroke-linejoin="round" d="M2.25 15.75l5.159-5.159a2.25 2.25 0 013.182 0l5.159 5.159m-1.5-1.5l1.409-1.409a2.25 2.25 0 013.182 0l2.909 2.909M6 12h.008v.008H6V12zm18 0a1.5 1.5 0 01-1.5 1.5H3.75A1.5 1.5 0 012.25 12V6A1.5 1.5 0 013.75 4.5h16.5A1.5 1.5 0 0122.5 6v6z" />
          </svg>
        </div>
        <!-- 품절 오버레이 -->
        <div v-if="product.soldOut" class="absolute inset-0 flex items-center justify-center bg-white/60">
          <span class="rounded bg-gray-900/80 px-3 py-1 text-sm font-medium text-white">품절</span>
        </div>
      </div>

      <div class="space-y-1 p-3">
        <p class="line-clamp-2 text-sm font-medium leading-snug text-gray-900">{{ product.name }}</p>
        <p class="text-xs text-gray-400">{{ product.sellerName }}</p>
        <p class="pt-1 text-base font-bold text-gray-900">{{ formattedPrice }}</p>
      </div>
    </div>
  </a>
</template>
