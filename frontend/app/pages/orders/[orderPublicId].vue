<script setup lang="ts">
import { orderStatusLabel } from '~/lib/constants/order'
import { claimableTypes, claimTypeLabel, orderItemStatusLabel, type ClaimType } from '~/lib/constants/claim'
import type { OrderItem } from '~/types/order'

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

// 클레임 요청 폼으로 진입(name은 표시용·서버 미전송). 원주문 id는 폼이 query로 받지 않으므로 성공 후 /orders로 유도.
function goClaim(item: OrderItem, type: ClaimType): void {
  navigateTo(
    `/claims/new?orderItem=${item.orderItemId}&type=${type}&name=${encodeURIComponent(item.productName ?? '')}`,
  )
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
                class="space-y-2"
              >
                <div class="flex items-start justify-between gap-4">
                  <div class="min-w-0">
                    <!-- productName은 표시용 enrich. 삭제 상품(null/부재) 시 방어 문구. -->
                    <p class="truncate text-sm font-medium text-ink">
                      {{ item.productName ?? '삭제된 상품' }}
                    </p>
                    <p class="mt-1 text-xs text-sub">
                      {{ formatPrice(item.unitPrice) }} · 수량 {{ item.quantity }}
                    </p>
                  </div>
                  <div class="flex shrink-0 flex-col items-end gap-1">
                    <span class="text-sm font-medium text-ink">{{ formatPrice(item.totalPrice) }}</span>
                    <!-- 품목 상태 배지(BE label=code이므로 FE 라벨 매핑). -->
                    <span class="rounded-badge bg-gray-100 px-2 py-0.5 text-xs font-medium text-sub">
                      {{ orderItemStatusLabel(item.status.code) }}
                    </span>
                  </div>
                </div>

                <!-- 클레임 진입점: 품목 상태가 허용하는 유형만 노출(claimableTypes 빈 배열이면 미노출). -->
                <div v-if="claimableTypes(item.status.code).length" class="flex flex-wrap gap-2">
                  <Button
                    v-for="type in claimableTypes(item.status.code)"
                    :key="type"
                    variant="outline"
                    size="sm"
                    @click="goClaim(item, type)"
                  >
                    {{ claimTypeLabel(type) }} 요청
                  </Button>
                </div>
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
