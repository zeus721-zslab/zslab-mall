<script setup lang="ts">
import { orderStatusLabel } from '~/lib/constants/order'

// BUYER 전용 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다.
definePageMeta({ middleware: 'buyer' })

const route = useRoute()
const orderPublicId = route.params.orderPublicId as string

const { data, pending, error, refresh } = useOrderDetail(orderPublicId)

// 401(세션 만료)은 /login 유도. 404(타인·미존재)는 존재 은닉이라 안내만(재조회 버튼 없이 목록으로 유도).
watch(
  error,
  (fetchError) => {
    if ((fetchError as { statusCode?: number } | null)?.statusCode === 401) {
      navigateTo(`/login?redirect=${encodeURIComponent(`/orders/${orderPublicId}`)}`)
    }
  },
  { immediate: true },
)

// 404와 그 외 오류 문구 구분(존재 은닉이라 미노출도 404).
const errorMessage = computed<string>(() =>
  (error.value as { statusCode?: number } | null)?.statusCode === 404
    ? '주문을 찾을 수 없습니다'
    : '주문을 불러오지 못했습니다',
)

function formatPrice(value: number): string {
  return `${value.toLocaleString('ko-KR')}원`
}

useSeoMeta({
  title: () => (data.value ? `주문 ${data.value.orderId} · zslab-mall` : '주문 상세 · zslab-mall'),
  description: 'zslab-mall 주문 상세',
})
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-[880px] px-4 md:px-6">
      <!-- 로딩 -->
      <div v-if="pending" class="space-y-4">
        <div class="h-8 w-2/3 animate-pulse rounded bg-gray-100"></div>
        <div class="h-40 animate-pulse rounded-card bg-gray-100"></div>
      </div>

      <!-- 에러 / 없음(404 포함) -->
      <CommonErrorState v-else-if="error || !data" :message="errorMessage" @retry="refresh" />

      <!-- 상세 -->
      <template v-else>
        <!-- 헤더: 주문번호 + 상태 -->
        <div class="mb-6 flex items-start justify-between gap-4">
          <div class="min-w-0">
            <p class="text-sm text-sub">주문번호</p>
            <h1 class="mt-1 break-all font-mono text-lg font-medium text-ink">{{ data.orderId }}</h1>
          </div>
          <span class="shrink-0 rounded-badge bg-gray-100 px-3 py-1 text-sm font-medium text-ink">
            {{ orderStatusLabel(data.status.code) }}
          </span>
        </div>

        <!-- seller 그룹별 품목 -->
        <section class="space-y-4">
          <div
            v-for="seller in data.sellers"
            :key="seller.sellerId"
            class="rounded-card border border-line p-5"
          >
            <p class="mb-3 text-sm font-semibold text-seller">{{ seller.companyName }}</p>

            <ul class="space-y-3">
              <li
                v-for="item in seller.items"
                :key="item.orderItemId"
                class="flex items-start justify-between gap-4"
              >
                <div class="min-w-0">
                  <!-- productName은 표시용 enrich. 삭제 상품(null/부재) 시 방어 문구. -->
                  <p class="truncate text-sm font-medium text-ink">
                    {{ item.productName ?? '삭제된 상품' }}
                  </p>
                  <p class="mt-1 text-xs text-sub">
                    {{ formatPrice(item.unitPrice) }} · 수량 {{ item.quantity }}
                  </p>
                </div>
                <span class="shrink-0 text-sm font-medium text-ink">{{ formatPrice(item.totalPrice) }}</span>
              </li>
            </ul>

            <div class="mt-3 flex items-center justify-between border-t border-line pt-3">
              <span class="text-xs text-sub">판매자 소계</span>
              <span class="text-sm font-semibold text-ink">{{ formatPrice(seller.subtotal) }}</span>
            </div>
          </div>
        </section>

        <!-- 주문 합계 -->
        <div class="mt-6 flex items-center justify-between rounded-card border border-line p-5">
          <span class="text-base font-semibold text-ink">총 결제금액</span>
          <span class="text-xl font-bold text-price">{{ formatPrice(data.totalPrice) }}</span>
        </div>

        <!-- 배송지: 스냅샷 부재 시 미표시 -->
        <section v-if="data.shippingAddress" class="mt-6 rounded-card border border-line p-5">
          <h2 class="mb-3 text-base font-semibold text-ink">배송지</h2>
          <div class="space-y-1 text-sm text-ink">
            <p>{{ data.shippingAddress.recipientName }} · {{ data.shippingAddress.recipientPhone }}</p>
            <p class="text-sub">
              ({{ data.shippingAddress.zonecode }}) {{ data.shippingAddress.addressRoad }}
              <template v-if="data.shippingAddress.addressDetail"> {{ data.shippingAddress.addressDetail }}</template>
            </p>
            <p v-if="data.shippingAddress.deliveryMemo" class="text-sub">
              메모: {{ data.shippingAddress.deliveryMemo }}
            </p>
          </div>
        </section>

        <!-- 목록으로 -->
        <div class="mt-8">
          <Button variant="outline" size="lg" class="w-full" as-child>
            <NuxtLink to="/orders">주문 내역으로</NuxtLink>
          </Button>
        </div>
      </template>
    </div>
  </div>
</template>
