<script setup lang="ts">
import type { CheckoutCompletePageVm } from '~/skins/contracts/checkout-complete'

// renew 주문 완료(FE-71 · FE-78). 결과 띠 면(민트): 흰 원형 체크 · 안내 · 주문번호(식별자라 mono). 주문번호가 없으면(직접 진입 등) classic처럼 주문 목록으로 유도한다.
// 띠 면 위라 보조 버튼은 흰 바탕이다.
defineProps<{ vm: CheckoutCompletePageVm }>()
</script>

<template>
  <div class="px-5 pb-8 pt-10 md:pt-16">
    <div class="mx-auto max-w-[480px] rounded-(--panel-radius) bg-(--pastel-mint-bg) px-6 py-10 text-center md:px-10">
      <span class="mx-auto flex h-20 w-20 items-center justify-center rounded-full bg-white text-(--pastel-mint-ink)" aria-hidden="true">
        <svg class="h-10 w-10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M5 12.5l4.5 4.5L19 7.5" />
        </svg>
      </span>
      <h1 class="mt-6 text-h1 text-ink">주문이 완료됐어요</h1>
      <p class="mt-2 text-body text-sub">결제가 정상적으로 처리되었습니다. 감사합니다.</p>

      <div v-if="vm.orderPublicId" class="mt-8 rounded-card bg-white px-5 py-4">
        <p class="text-caption font-normal text-sub">주문번호</p>
        <p class="mt-1 break-all font-mono text-body font-semibold text-ink" data-testid="checkout-complete-order-id">{{ vm.orderPublicId }}</p>
      </div>

      <div class="mt-8 space-y-2">
        <NuxtLink :to="vm.orderPublicId ? `/orders/${vm.orderPublicId}` : '/orders'" class="btn btn-primary btn-lg w-full">
          {{ vm.orderPublicId ? '주문 상세 보기' : '주문 내역 보기' }}
        </NuxtLink>
        <NuxtLink to="/products" class="btn btn-lg w-full bg-white text-primary">쇼핑 계속하기</NuxtLink>
      </div>
    </div>
  </div>
</template>
