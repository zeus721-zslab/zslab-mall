<script setup lang="ts">
import type { CheckoutCompletePageVm } from '~/skins/contracts/checkout-complete'

// renew 주문 완료(FE-71). 가운데 카드: 민트 원형 체크 · 안내 · 주문번호(mono). 주문번호가 없으면(직접 진입 등) classic처럼 주문 목록으로 유도한다.
defineProps<{ vm: CheckoutCompletePageVm }>()

const BUTTON = 'flex min-h-14 w-full items-center justify-center rounded-full text-base font-bold transition duration-200 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2'
</script>

<template>
  <div class="px-5 pb-8 pt-10 md:pt-16">
    <div class="mx-auto max-w-[480px] rounded-[28px] bg-white px-6 py-10 text-center md:px-10">
      <span class="mx-auto flex h-20 w-20 items-center justify-center rounded-full bg-(--pastel-mint-bg) text-(--pastel-mint-ink)" aria-hidden="true">
        <svg class="h-10 w-10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M5 12.5l4.5 4.5L19 7.5" />
        </svg>
      </span>
      <h1 class="mt-6 text-2xl font-bold tracking-tight text-ink">주문이 완료됐어요</h1>
      <p class="mt-2 text-sm text-sub">결제가 정상적으로 처리되었습니다. 감사합니다.</p>

      <div v-if="vm.orderPublicId" class="mt-8 rounded-card bg-surface-muted px-5 py-4">
        <p class="text-xs text-sub">주문번호</p>
        <p class="mt-1 break-all font-mono text-sm font-semibold text-ink" data-testid="checkout-complete-order-id">{{ vm.orderPublicId }}</p>
      </div>

      <div class="mt-8 space-y-2">
        <NuxtLink
          :to="vm.orderPublicId ? `/orders/${vm.orderPublicId}` : '/orders'"
          :class="[BUTTON, 'bg-primary text-primary-foreground hover:bg-primary-hover']"
        >
          {{ vm.orderPublicId ? '주문 상세 보기' : '주문 내역 보기' }}
        </NuxtLink>
        <NuxtLink to="/products" :class="[BUTTON, 'border border-line bg-white text-ink hover:border-ink']">쇼핑 계속하기</NuxtLink>
      </div>
    </div>
  </div>
</template>
