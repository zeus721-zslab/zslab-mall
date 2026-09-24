<script setup lang="ts">
import {
  CLAIM_TYPE_FILTERS,
  CLAIM_TYPE_QUERY_VALUES,
  ORDER_LIST_TABS,
  ORDER_LIST_TAB_LABELS,
  isLegacyClaimTab,
  parseClaimTypeFilter,
  parseItemStatusFilter,
  parseOrderListPage,
  parseOrderListTab,
  type OrderItemStatusFilter,
  type OrderListTab,
} from '~/lib/constants/order-tabs'
import { ITEM_CONFIRM_WARNING } from '~/lib/constants/order'
import {
  CLAIM_REASON_LABELS,
  claimableTypes,
  claimRejectReasonLabel,
  claimStatusLabel,
  claimTypeLabel,
  orderItemStatusLabel,
  refundStatusLabel,
  type ClaimType,
} from '~/lib/constants/claim'
import { canResumePayment } from '~/lib/utils/payment-resume'
import { toActiveClaimBadges } from '~/lib/utils/active-claim-badge'
import { formatDateTime } from '~/lib/utils/datetime'
import type { OrderSummary, OrderSummaryItem } from '~/types/order'
import type { ItemConfirmNotice } from '~/skins/contracts/order-detail'
import type { OrdersPageVm } from '~/skins/contracts/orders'
import type { LocationQuery } from 'vue-router'

// BUYER 전용 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다.
definePageMeta({ middleware: 'buyer' })

const route = useRoute()
const router = useRouter()

// URL(?tab=&page=)이 단일 소스다(FE-63). 로컬 ref를 진실로 두지 않아 새로고침·뒤로가기·배지 링크가 같은 화면을 복원한다.
const tab = computed<OrderListTab>(() => parseOrderListTab(route.query.tab))
const page = computed<number>(() => parseOrderListPage(route.query.page))

// 탭 전환은 page를 0으로 되돌린다(2페이지에서 탭만 바꾸면 빈 목록이 나오는 트랩 방지). history는 늘리지 않는다.
// 품목 상태 필터는 전체 주문 탭에만 걸리므로 클레임 탭으로 가면 뺀다(FE-80). 같은 탭 안 페이지 이동은 쿼리를 그대로 이어받는다.
function moveTo(nextTab: OrderListTab, nextPage: number): void {
  const query: LocationQuery = { ...route.query, tab: nextTab, page: String(nextPage) }
  if (nextTab === 'claim') delete query.itemStatus
  router.replace({ query })
}

const isOrderTab = computed(() => tab.value === 'order')
const isClaimTab = computed(() => tab.value === 'claim')
// 취소·반품·교환 탭의 유형 필터(FE-73 보완 1·null = 전체). ?type= 우선, 옛 ?tab=cancel|return|exchange도 해당 유형으로 읽는다.
const claimTypeFilter = computed<ClaimType | null>(() => parseClaimTypeFilter(route.query.type, route.query.tab))
// 전체 주문 탭의 품목 상태 필터(D-224·FE-80 · null = 없음). 허용 5값 밖은 무시하고, 클레임 탭에서는 쿼리가 남아 있어도 걸지 않는다.
const itemStatusFilter = computed<OrderItemStatusFilter | null>(() =>
  isOrderTab.value ? parseItemStatusFilter(route.query.itemStatus) : null,
)

// 필터 해제 = itemStatus 쿼리 제거 + 첫 페이지.
function clearItemStatusFilter(): void {
  const query: LocationQuery = { ...route.query, page: '0' }
  delete query.itemStatus
  router.replace({ query })
}

// 유형 칩을 바꾸면 claim 탭 첫 페이지로 간다. 전체(null)는 type 쿼리를 뺀다. 품목 상태 필터는 클레임 탭에 걸리지 않아 함께 뺀다.
function moveToClaimType(type: ClaimType | null): void {
  const query: LocationQuery = { ...route.query, tab: 'claim', page: '0' }
  delete query.itemStatus
  if (type) {
    router.replace({ query: { ...query, type: CLAIM_TYPE_QUERY_VALUES[type] } })
    return
  }
  delete query.type
  router.replace({ query })
}

// 옛 유형 탭 링크(?tab=return 등)는 새 형식(tab=claim&type=return)으로 주소만 정리한다 — 이후 페이지·탭 이동(moveTo)이
// 쿼리를 이어받을 때 유형이 사라지지 않도록. 해석 결과는 같아 다시 조회하지 않는다.
onMounted(() => {
  if (!isLegacyClaimTab(route.query.tab) || claimTypeFilter.value === null) return
  router.replace({ query: { ...route.query, tab: 'claim', type: CLAIM_TYPE_QUERY_VALUES[claimTypeFilter.value] } })
})

// 주문 조회는 page Ref에 반응한다(useFetch query reactive 관례). 클레임 조회(FE-73: 유형 구분 없음)는 클레임 탭일 때만 실행한다.
const { data: orders, pending: ordersPending, error: ordersError, refresh: refreshOrders } = useOrderList(page, itemStatusFilter)
const {
  data: claims,
  pending: claimsPending,
  status: claimsStatus,
  error: claimsError,
  refresh: refreshClaims,
} = useClaimList(page, isClaimTab, claimTypeFilter)

// 클레임 탭으로 막 전환해 아직 조회 전(idle)이면 빈 목록 대신 로딩으로 보인다.
const pending = computed(() => (isOrderTab.value ? ordersPending.value : claimsPending.value || claimsStatus.value === 'idle'))
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
const errorMessage = computed(() => (isOrderTab.value ? '주문 내역을 불러오지 못했습니다' : '취소·반품·교환 내역을 불러오지 못했습니다'))

// 목록 품목 행의 클레임 진입(FE-73): 주문 상세 goClaim과 같은 이동 경로·같은 교환 파라미터(상품·변형·주문 단가).
function goClaim(item: OrderSummaryItem, type: ClaimType): void {
  const base = `/claims/new?orderItem=${item.orderItemId}&type=${type}&name=${encodeURIComponent(item.productName ?? '')}`
  const exchangeQuery = type === 'EXCHANGE'
    ? `&product=${item.productId ?? ''}&variant=${item.variantId ?? ''}&unitPrice=${item.unitPrice}`
    : ''
  navigateTo(base + exchangeQuery)
}

// 목록 품목 행의 구매확정(FE-73): 확인 모달에서 확정하면 주문 상세와 같은 API를 부르고 목록을 다시 읽는다.
// 안내(성공·실패)는 그 품목 행 아래에 보인다. 제출 중에는 모달 버튼을 잠가 중복 호출을 막는다.
const { confirmPurchase } = useOrderActions()
const confirmTarget = ref<{ orderId: string; item: OrderSummaryItem } | null>(null)
const confirming = ref<boolean>(false)
const confirmNotice = ref<ItemConfirmNotice | null>(null)

function openConfirm(order: OrderSummary, item: OrderSummaryItem): void {
  confirmNotice.value = null
  confirmTarget.value = { orderId: order.orderId, item }
}

function cancelConfirm(): void {
  if (confirming.value) return
  confirmTarget.value = null
}

async function submitConfirm(): Promise<void> {
  const target = confirmTarget.value
  if (confirming.value || target === null) return
  confirming.value = true
  try {
    await confirmPurchase(target.orderId, target.item.orderItemId)
    confirmNotice.value = { orderItemId: target.item.orderItemId, tone: 'success', text: '구매확정이 완료되었습니다.' }
    confirmTarget.value = null
    await refreshOrders()
  } catch (submitError) {
    // 401은 로그인 유도, 그 외(422 상태 불일치·순수령액 0 이하·대사 중·404 등)는 서버 detail 우선 표시 후 재조회(주문 상세와 같은 규칙).
    const failure = submitError as { statusCode?: number; data?: { detail?: string } }
    if (failure.statusCode === 401) {
      navigateTo(`/login?redirect=${encodeURIComponent('/orders')}`)
      return
    }
    confirmNotice.value = {
      orderItemId: target.item.orderItemId,
      tone: 'error',
      text: failure.data?.detail ?? '구매확정에 실패했습니다. 잠시 후 다시 시도하세요.',
    }
    confirmTarget.value = null
    await refreshOrders()
  } finally {
    confirming.value = false
  }
}

useSeoMeta({ title: '주문 내역 · zslab-mall', description: 'zslab-mall 주문·취소·반품·교환 내역' })

const vm: OrdersPageVm = reactive({
  ORDER_LIST_TABS,
  ORDER_LIST_TAB_LABELS,
  tab,
  page,
  moveTo,
  isOrderTab,
  orders,
  claims,
  pending,
  error,
  errorMessage,
  retry,
  isEmpty,
  emptyMessage,
  hasNext,
  goClaim,
  confirmTarget,
  confirming,
  confirmNotice,
  openConfirm,
  cancelConfirm,
  submitConfirm,
  ITEM_CONFIRM_WARNING,
  orderItemStatusLabel,
  claimableTypes,
  claimTypeLabel,
  claimStatusLabel,
  claimRejectReasonLabel,
  refundStatusLabel,
  CLAIM_REASON_LABELS,
  canResumePayment,
  toActiveClaimBadges,
  formatDateTime,
  claimTypeFilter,
  CLAIM_TYPE_FILTERS,
  CLAIM_TYPE_QUERY_VALUES,
  moveToClaimType,
  itemStatusFilter,
  clearItemStatusFilter,
})
</script>

<template>
  <component :is="useSkinView('OrdersView')" :vm="vm" />
</template>
