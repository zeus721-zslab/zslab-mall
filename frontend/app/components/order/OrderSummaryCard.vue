<script setup lang="ts">
import type { OrderSummary } from '~/types/order'
import { orderStatusLabel } from '~/lib/constants/order'
import { formatDateTime } from '~/lib/utils/datetime'
import { toActiveClaimBadges } from '~/lib/utils/active-claim-badge'

/** 주문 목록 카드(FE-63·orders/index.vue 본문 승격). 표시만 담당하고 조회·페이징은 페이지가 가진다. */
const props = defineProps<{ order: OrderSummary }>()

const claimBadges = computed(() => toActiveClaimBadges(props.order.activeClaims))

function formatPrice(value: number): string {
  return `${value.toLocaleString('ko-KR')}원`
}
</script>

<template>
  <!--
    카드 전체가 주문 상세 링크인데 클레임 배지도 각자 탭으로 이동해야 한다. <a> 안에 <a>를 넣을 수 없으므로
    상세 링크를 카드를 덮는 오버레이로 깔고(absolute inset-0) 배지만 그 위(z-10)로 올린다.
  -->
  <div
    class="relative rounded-card border border-line p-5 transition duration-normal hover:border-gray-300"
    data-testid="order-card"
  >
    <NuxtLink :to="`/orders/${order.orderId}`" class="absolute inset-0 rounded-card" :aria-label="`주문 ${order.previewTitle} 상세`" />

    <div class="flex items-start justify-between gap-4">
      <div class="min-w-0">
        <p class="truncate text-base font-medium text-ink">{{ order.previewTitle }}</p>
        <p class="mt-1 text-sm text-sub">
          {{ formatDateTime(order.orderedAt) }} · 판매자 {{ order.sellerCount }}곳
        </p>
      </div>
      <span class="shrink-0 rounded-badge bg-gray-100 px-3 py-1 text-xs font-medium text-ink">
        {{ orderStatusLabel(order.status.code) }}
      </span>
    </div>

    <div class="mt-3 flex items-end justify-between gap-4">
      <!-- 진행 중 클레임 배지(FE-63): 주문 상태 배지(무채색 칩)와 구분되도록 강조색 칩(FE-07 badge-new 토큰)을 쓴다. 없으면 영역 자체가 없다. -->
      <div v-if="claimBadges.length > 0" class="relative z-10 flex flex-wrap gap-1" data-testid="order-claim-badges">
        <template v-for="badge in claimBadges" :key="badge.label">
          <NuxtLink
            v-if="badge.tab"
            :to="{ path: '/orders', query: { tab: badge.tab } }"
            class="rounded-badge bg-badge-new-bg px-2 py-0.5 text-[11px] font-medium text-badge-new-ink hover:opacity-80"
          >
            {{ badge.label }}
          </NuxtLink>
          <span v-else class="rounded-badge border border-line px-2 py-0.5 text-[11px] text-sub">{{ badge.label }}</span>
        </template>
      </div>
      <div v-else></div>

      <p class="text-lg font-bold text-price">{{ formatPrice(order.totalPrice) }}</p>
    </div>
  </div>
</template>
