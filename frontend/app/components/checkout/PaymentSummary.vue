<script setup lang="ts">
import type { CheckoutSummary } from '~/lib/utils/checkout-summary'

// 체크아웃 결제 금액 요약(FE-17). 상품 금액 합계·배송비·최종 결제 금액·종류/수량과, 결제 불가 안내(선택 0개·구매 불가 포함)를 표시한다.
// 결제 버튼·제출은 상위(페이지)가 소유한다.
defineProps<{ summary: CheckoutSummary }>()

function formatPrice(value: number): string {
  return `${value.toLocaleString('ko-KR')}원`
}
</script>

<template>
  <div class="space-y-4">
    <!-- 선택 0개: 안내 + 장바구니 링크(결제 버튼 비활성은 상위 canSubmit) -->
    <div v-if="summary.items.length === 0">
      <p role="status" class="text-sm text-sub">선택된 상품이 없습니다</p>
      <NuxtLink to="/cart" class="mt-1 inline-block text-sm text-primary underline">장바구니로 이동</NuxtLink>
    </div>

    <template v-else>
      <!-- 구매 불가 품목 포함: 서버는 selected 전체를 주문하므로 삭제 유도(cart.vue에서 구매 불가는 선택 해제 불가·삭제만 가능) -->
      <div v-if="summary.hasUnpurchasableSelected">
        <p role="alert" class="text-sm text-soldout">구매할 수 없는 상품이 포함되어 있습니다. 장바구니에서 삭제해 주세요.</p>
        <NuxtLink to="/cart" class="mt-1 inline-block text-sm text-primary underline">장바구니로 이동</NuxtLink>
      </div>

      <dl class="space-y-2 text-sm">
        <div class="flex items-center justify-between">
          <dt class="text-sub">상품 금액</dt>
          <dd class="font-medium text-ink">{{ formatPrice(summary.productTotal) }}</dd>
        </div>
        <div class="flex items-center justify-between">
          <dt class="text-sub">배송비</dt>
          <dd class="font-medium text-ink">{{ formatPrice(summary.shippingFee) }}</dd>
        </div>
        <div class="flex items-center justify-between border-t border-line pt-2">
          <dt class="font-semibold text-ink">최종 결제 금액</dt>
          <dd class="text-xl font-bold text-price">{{ formatPrice(summary.finalAmount) }}</dd>
        </div>
      </dl>
      <p class="text-xs text-sub">총 {{ summary.kindCount }}종 · {{ summary.totalQuantity }}개</p>
    </template>
  </div>
</template>
