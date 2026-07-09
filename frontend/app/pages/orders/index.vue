<script setup lang="ts">
import { orderStatusLabel } from '~/lib/constants/order'

// BUYER 전용 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다.
definePageMeta({ middleware: 'buyer' })

// 페이징: page/size 왕복 최소 구현. page 변경 시 useOrderList의 useFetch가 재조회한다(size는 기본 20 고정).
const page = ref<number>(0)
const { data, pending, error, refresh } = useOrderList(page)

// 세션 만료 등으로 서버가 401이면 로그인으로 유도(미들웨어는 진입 UX만·실인가 SoT는 서버).
watch(
  error,
  (fetchError) => {
    if ((fetchError as { statusCode?: number } | null)?.statusCode === 401) {
      navigateTo(`/login?redirect=${encodeURIComponent('/orders')}`)
    }
  },
  { immediate: true },
)

/**
 * BE는 LocalDateTime을 'yyyy-MM-ddTHH:mm:ss.SSSZ' 문자열로 내려준다(Z는 포맷 잔재·실제 UTC 오프셋 아님).
 * new Date 파싱은 로컬 타임존 변환으로 SSR/클라 hydration 불일치를 유발하므로 문자열을 직접 자른다.
 */
function formatOrderedAt(iso: string): string {
  const matched = iso.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/)
  if (!matched) return iso
  const [, year, month, day, hour, minute] = matched
  return `${year}.${month}.${day} ${hour}:${minute}`
}

function formatPrice(value: number): string {
  return `${value.toLocaleString('ko-KR')}원`
}

useSeoMeta({ title: '주문 내역 · zslab-mall', description: 'zslab-mall 주문 내역' })
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-[1240px] px-4 md:px-6">
      <h1 class="mb-6 text-2xl font-bold tracking-tight text-ink">주문 내역</h1>

      <!-- 로딩 -->
      <div v-if="pending" class="space-y-3">
        <div v-for="n in 5" :key="n" class="h-24 animate-pulse rounded-card bg-gray-100"></div>
      </div>

      <!-- 에러 -->
      <CommonErrorState v-else-if="error" message="주문 내역을 불러오지 못했습니다" @retry="refresh" />

      <!-- 빈 목록 -->
      <CommonEmptyState v-else-if="!data || data.items.length === 0" message="주문 내역이 없습니다" />

      <!-- 목록 -->
      <template v-else>
        <ul class="space-y-3">
          <li v-for="order in data.items" :key="order.orderId">
            <NuxtLink
              :to="`/orders/${order.orderId}`"
              class="block rounded-card border border-line p-5 transition duration-normal hover:border-gray-300"
            >
              <div class="flex items-start justify-between gap-4">
                <div class="min-w-0">
                  <p class="truncate text-base font-medium text-ink">{{ order.previewTitle }}</p>
                  <p class="mt-1 text-sm text-sub">
                    {{ formatOrderedAt(order.orderedAt) }} · 판매자 {{ order.sellerCount }}곳
                  </p>
                </div>
                <span class="shrink-0 rounded-badge bg-gray-100 px-3 py-1 text-xs font-medium text-ink">
                  {{ orderStatusLabel(order.status.code) }}
                </span>
              </div>
              <p class="mt-3 text-right text-lg font-bold text-price">{{ formatPrice(order.totalPrice) }}</p>
            </NuxtLink>
          </li>
        </ul>

        <!-- 페이징: hasNext 기준 이전/다음(누적 아님·page 왕복). -->
        <div class="mt-6 flex items-center justify-center gap-3">
          <Button variant="outline" size="sm" :disabled="page === 0" @click="page = Math.max(page - 1, 0)">
            이전
          </Button>
          <span class="text-sm text-sub">{{ page + 1 }}</span>
          <Button variant="outline" size="sm" :disabled="!data.hasNext" @click="page = page + 1">
            다음
          </Button>
        </div>
      </template>
    </div>
  </div>
</template>
