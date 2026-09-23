<script setup lang="ts">
import {
  ORDER_LIST_TABS,
  ORDER_LIST_TAB_LABELS,
  claimTypeOfTab,
  parseOrderListPage,
  parseOrderListTab,
  type OrderListTab,
} from '~/lib/constants/order-tabs'

// BUYER 전용 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다.
definePageMeta({ middleware: 'buyer' })

const route = useRoute()
const router = useRouter()

// URL(?tab=&page=)이 단일 소스다(FE-63). 로컬 ref를 진실로 두지 않아 새로고침·뒤로가기·배지 링크가 같은 화면을 복원한다.
const tab = computed<OrderListTab>(() => parseOrderListTab(route.query.tab))
const page = computed<number>(() => parseOrderListPage(route.query.page))
const claimType = computed(() => claimTypeOfTab(tab.value))

// 탭 전환은 page를 0으로 되돌린다(2페이지에서 탭만 바꾸면 빈 목록이 나오는 트랩 방지). history는 늘리지 않는다.
function moveTo(nextTab: OrderListTab, nextPage: number): void {
  router.replace({ query: { ...route.query, tab: nextTab, page: String(nextPage) } })
}

const isOrderTab = computed(() => tab.value === 'order')

// 두 조회 모두 page/tab Ref에 반응한다(useFetch query reactive 관례). 주문 탭에서도 클레임 조회가 뜨지 않도록
// type이 null인 클레임 조회는 실행하지 않는다(immediate:false + watch 재조회).
const { data: orders, pending: ordersPending, error: ordersError, refresh: refreshOrders } = useOrderList(page)
const {
  data: claims,
  pending: claimsPending,
  error: claimsError,
  refresh: refreshClaims,
} = useClaimList(page, claimType)

const pending = computed(() => (isOrderTab.value ? ordersPending.value : claimsPending.value))
const error = computed(() => (isOrderTab.value ? ordersError.value : claimsError.value))
const hasNext = computed(() => (isOrderTab.value ? orders.value?.hasNext : claims.value?.hasNext) ?? false)
const isEmpty = computed(() =>
  isOrderTab.value ? !orders.value || orders.value.items.length === 0 : !claims.value || claims.value.items.length === 0,
)

function retry(): void {
  if (isOrderTab.value) refreshOrders()
  else refreshClaims()
}

// 세션 만료 등으로 서버가 401이면 로그인으로 유도(미들웨어는 진입 UX만·실인가 SoT는 서버). 복귀 지점은 현재 탭이다.
watch(
  error,
  (fetchError) => {
    if ((fetchError as { statusCode?: number } | null)?.statusCode === 401) {
      navigateTo(`/login?redirect=${encodeURIComponent(`/orders?tab=${tab.value}`)}`)
    }
  },
  { immediate: true },
)

const emptyMessage = computed(() =>
  isOrderTab.value ? '주문 내역이 없습니다' : `${ORDER_LIST_TAB_LABELS[tab.value]} 내역이 없습니다`,
)
const errorMessage = computed(() => (isOrderTab.value ? '주문 내역을 불러오지 못했습니다' : '클레임 내역을 불러오지 못했습니다'))

useSeoMeta({ title: '주문 내역 · zslab-mall', description: 'zslab-mall 주문·취소·반품·교환 내역' })
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-[1240px] px-4 md:px-6">
      <h1 class="mb-6 text-2xl font-bold tracking-tight text-ink">주문 내역</h1>

      <!-- 탭(FE-63): 주문 + 클레임 3유형. CategoryTabs와 같은 nav/aria-current 관례. -->
      <nav aria-label="주문내역 구분" data-testid="order-tabs" class="mb-6 overflow-x-auto">
        <ul class="flex gap-6 border-b border-gray-100">
          <li v-for="item in ORDER_LIST_TABS" :key="item">
            <button
              type="button"
              class="whitespace-nowrap border-b-2 px-1 pb-3 text-sm transition duration-normal"
              :class="item === tab ? 'border-ink font-semibold text-ink' : 'border-transparent text-sub hover:text-ink'"
              :aria-current="item === tab ? 'page' : undefined"
              @click="moveTo(item, 0)"
            >
              {{ ORDER_LIST_TAB_LABELS[item] }}
            </button>
          </li>
        </ul>
      </nav>

      <!-- 로딩 -->
      <div v-if="pending" class="space-y-3">
        <div v-for="n in 5" :key="n" class="h-24 animate-pulse rounded-card bg-gray-100"></div>
      </div>

      <!-- 에러 -->
      <CommonErrorState v-else-if="error" :message="errorMessage" @retry="retry" />

      <!-- 빈 목록 -->
      <CommonEmptyState v-else-if="isEmpty" :message="emptyMessage" />

      <!-- 목록 -->
      <template v-else>
        <ul class="space-y-3">
          <template v-if="isOrderTab">
            <li v-for="order in orders!.items" :key="order.orderId">
              <OrderSummaryCard :order="order" />
            </li>
          </template>
          <template v-else>
            <li v-for="claim in claims!.items" :key="claim.publicId">
              <ClaimSummaryCard :claim="claim" />
            </li>
          </template>
        </ul>

        <!-- 페이징: hasNext 기준 이전/다음(누적 아님·page 왕복). 탭이 달라도 같은 페이저를 쓴다. -->
        <div class="mt-6 flex items-center justify-center gap-3">
          <Button variant="outline" size="sm" :disabled="page === 0" @click="moveTo(tab, Math.max(page - 1, 0))">
            이전
          </Button>
          <span class="text-sm text-sub">{{ page + 1 }}</span>
          <Button variant="outline" size="sm" :disabled="!hasNext" @click="moveTo(tab, page + 1)">
            다음
          </Button>
        </div>
      </template>
    </div>
  </div>
</template>
