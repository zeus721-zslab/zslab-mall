<script setup lang="ts">
import type { CartItemView } from '~/types/cart'

// 체크아웃 주문 상품 목록(FE-17). selected 품목 전체를 받아 썸네일·상품명·판매자명·단가·수량·소계를 표시한다(옵션명 제외).
// 구매 불가 품목은 cart.vue 표기·opacity 관습대로 구분 표시만 하고, 결제 차단 판정은 상위(페이지)가 한다.
defineProps<{ items: CartItemView[] }>()

function formatPrice(value: number): string {
  return `${value.toLocaleString('ko-KR')}원`
}
</script>

<template>
  <section class="space-y-4">
    <h2 class="text-lg font-semibold text-ink">주문 상품</h2>
    <div
      v-for="item in items"
      :key="item.variantPublicId"
      class="flex gap-4 rounded-card border border-line p-4"
      :class="{ 'opacity-60': !item.purchasable }"
    >
      <!-- 썸네일 -->
      <div class="relative h-20 w-20 shrink-0 overflow-hidden rounded-control border border-line bg-gray-100">
        <img
          v-if="item.thumbnailUrl"
          :src="item.thumbnailUrl"
          :alt="item.productName ?? '상품 이미지'"
          class="h-full w-full object-cover"
        />
        <div v-else class="flex h-full w-full items-center justify-center text-gray-300">
          <svg class="h-8 w-8" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
            <path stroke-linecap="round" stroke-linejoin="round" d="M2.25 15.75l5.159-5.159a2.25 2.25 0 013.182 0l5.159 5.159m-1.5-1.5l1.409-1.409a2.25 2.25 0 013.182 0l2.909 2.909M6 12h.008v.008H6V12zm18 0a1.5 1.5 0 01-1.5 1.5H3.75A1.5 1.5 0 012.25 12V6A1.5 1.5 0 013.75 4.5h16.5A1.5 1.5 0 0122.5 6v6z" />
          </svg>
        </div>
      </div>

      <!-- 상품 정보 + 금액 -->
      <div class="flex min-w-0 flex-1 flex-col gap-2">
        <div class="min-w-0">
          <p class="truncate text-sm font-medium text-ink">{{ item.productName ?? '상품 정보 없음' }}</p>
          <p v-if="item.sellerName" class="truncate text-xs text-seller">{{ item.sellerName }}</p>
          <p v-if="!item.purchasable" class="mt-1 text-xs text-soldout">구매 불가 (품절 또는 판매 중지)</p>
        </div>
        <div class="mt-auto flex items-center justify-between gap-3">
          <span class="text-sm text-sub">{{ formatPrice(item.displayPrice) }} · 수량 {{ item.quantity }}</span>
          <span class="text-sm font-bold text-price">{{ formatPrice(item.displayPrice * item.quantity) }}</span>
        </div>
      </div>
    </div>
  </section>
</template>
