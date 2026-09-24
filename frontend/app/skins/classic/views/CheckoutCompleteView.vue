<script setup lang="ts">
import type { CheckoutCompletePageVm } from '~/skins/contracts/checkout-complete'

defineProps<{ vm: CheckoutCompletePageVm }>()
</script>

<template>
  <div class="py-12 md:py-16">
    <div class="mx-auto max-w-[480px] px-4 text-center md:px-6">
      <h1 class="text-2xl font-bold tracking-tight text-ink">주문이 완료되었습니다</h1>
      <p class="mt-2 text-sm text-sub">결제가 정상적으로 처리되었습니다. 감사합니다.</p>

      <div v-if="vm.orderPublicId" class="mt-6 rounded-card border border-line p-5">
        <p class="text-sm text-sub">주문번호</p>
        <p class="mt-1 break-all font-mono text-sm font-medium text-ink">{{ vm.orderPublicId }}</p>
      </div>

      <div class="mt-8 space-y-2">
        <!-- 주문번호가 관통됐으면 해당 주문 상세로, 없으면(직접 진입 등) 주문 목록으로 유도. -->
        <Button size="lg" class="w-full" as-child>
          <NuxtLink :to="vm.orderPublicId ? `/orders/${vm.orderPublicId}` : '/orders'">주문 내역 보기</NuxtLink>
        </Button>
        <Button size="lg" variant="outline" class="w-full" as-child>
          <NuxtLink to="/">홈으로</NuxtLink>
        </Button>
      </div>
    </div>
  </div>
</template>
