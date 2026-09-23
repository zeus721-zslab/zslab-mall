<script setup lang="ts">
import { mdiAlertCircleOutline, mdiInformationOutline, mdiTruckOutline } from '@mdi/js'
import type { SellerDeliveryListQuery, SellerDeliverySummary } from '#layers/seller/app/types/seller-delivery'
import {
  DEFAULT_SELLER_DELIVERY_QUERY,
  hasActiveFilters,
  parseSellerDeliveryQuery,
  toSellerDeliveryRouteQuery,
} from '#layers/seller/app/lib/seller-delivery-query'
import { SELLER_ORDERS_PATH } from '#layers/seller/app/lib/seller-back-path'
import { toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerDeliveries } from '#layers/seller/app/composables/useSellerDeliveries'
import { useSellerToast } from '#layers/seller/app/composables/useSellerToast'

definePageMeta({ layout: 'seller', middleware: ['seller', 'seller-vuetify'] })
useSeoMeta({ title: '배송 · zslab-mall 셀러' })

// 셀러 배송 목록(Track 90-B-3·D-191·관리자 orders/deliveries.vue 골격 복제). 배송(delivery) 행 단위·기본 조회 범위는 원 발송. URL query가 필터·정렬·페이지의
// 단일 소스: 화면 조작 → router.replace → route.query watch → 조회. 행 메뉴 → 배송완료(mark-delivered)·송장 정정(PATCH·SHIPPING만).
// 주의(D-191 §5-2): 발송 대상(PAID 품목)은 아직 Delivery 행이 없어 이 목록에 나오지 않는다 — 발송은 주문 화면, 배송완료·송장 정정은 여기(진입점 분리·상단 안내).
const route = useRoute()
const router = useRouter()
const deliveriesApi = useSellerDeliveries()
const toast = useSellerToast()

const query = computed<SellerDeliveryListQuery>(() => parseSellerDeliveryQuery(route.query))
const shippingReadyLink = `${SELLER_ORDERS_PATH}?status=PAID`

// ---------- 목록 ----------
const items = ref<SellerDeliverySummary[]>([])
const totalCount = ref(0)
const loading = ref(false)
const loadError = ref<string | null>(null)
let requestSequence = 0

async function load(): Promise<void> {
  const sequence = ++requestSequence
  loading.value = true
  loadError.value = null
  try {
    const response = await deliveriesApi.list(query.value)
    if (sequence !== requestSequence) return // 늦게 도착한 이전 요청은 버린다
    items.value = response.items
    totalCount.value = response.totalCount
  } catch (error) {
    if (sequence !== requestSequence) return
    loadError.value = toSellerErrorMessage(error)
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

// router.replace가 반영되기 전에 연속 확정(기간 시작→종료 등)되면 앞 변경이 유실되므로 마지막으로 보낸 상태를 기준으로 합친다.
let pendingQuery: SellerDeliveryListQuery | null = null
watch(() => route.query, () => { pendingQuery = null; void load() }, { immediate: true, deep: true })

function applyQuery(patch: Partial<SellerDeliveryListQuery>, resetPage = true): void {
  const next: SellerDeliveryListQuery = { ...(pendingQuery ?? query.value), ...patch }
  if (resetPage && !('page' in patch)) next.page = 0
  pendingQuery = next
  void router.replace({ query: toSellerDeliveryRouteQuery(next) })
}

function resetQuery(): void {
  pendingQuery = null
  void router.replace({ query: toSellerDeliveryRouteQuery({ ...DEFAULT_SELLER_DELIVERY_QUERY, size: query.value.size }) })
}

const filtersActive = computed(() => hasActiveFilters(query.value))

// ---------- 이동·복사 ----------
function openOrderItem(item: SellerDeliverySummary): void {
  if (!item.orderItemId) return
  // 현재 목록 URL(필터·페이지)을 back으로 넘겨 품목 상세에서 같은 목록으로 복귀한다.
  void navigateTo({ path: `${SELLER_ORDERS_PATH}/${item.orderItemId}`, query: { back: route.fullPath } })
}

async function copyTracking(trackingNo: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(trackingNo)
    toast.info(`송장번호를 복사했습니다: ${trackingNo}`)
  } catch (error) {
    // 클립보드 권한 거부·비보안 컨텍스트 — 번호는 화면에 있으므로 안내만 한다.
    console.warn('[seller-deliveries] clipboard write failed', error)
    toast.warning('클립보드에 복사하지 못했습니다. 송장번호를 직접 선택해 복사하세요.')
  }
}

// ---------- 행 액션 다이얼로그(배송완료·송장 정정) ----------
type RowDialog = 'delivered' | 'tracking'
const activeDialog = ref<RowDialog | null>(null)
const dialogItem = ref<SellerDeliverySummary | null>(null)
const pendingIds = computed<Set<string>>(() => (dialogItem.value ? new Set([dialogItem.value.deliveryId]) : new Set()))

function openRowDialog(item: SellerDeliverySummary, dialog: RowDialog): void {
  dialogItem.value = item
  activeDialog.value = dialog
}

function closeDialog(refresh: boolean): void {
  activeDialog.value = null
  dialogItem.value = null
  if (refresh) void load()
}
</script>

<template>
  <div data-testid="seller-deliveries">
    <SellerPageHeader title="배송" description="발송된 배송을 송장·수령인·주문번호로 검색하고 배송완료 처리·송장 정정을 합니다." />

    <!-- 진입점 안내: 발송 전 품목은 배송 행이 없어 여기 없다. -->
    <v-alert type="info" variant="tonal" density="compact" class="mb-4" :icon="mdiInformationOutline" data-testid="seller-delivery-entry-notice">
      아직 발송하지 않은 결제완료 품목은 배송이 생성되기 전이라 이 목록에 없습니다.
      발송(택배사·송장 입력)은 <NuxtLink :to="shippingReadyLink" class="text-primary font-weight-medium" data-testid="seller-delivery-shipping-ready-link">주문 화면의 결제완료 품목</NuxtLink>에서 하고,
      배송완료 처리와 송장 정정은 이 화면(배송중 행)에서 합니다.
    </v-alert>

    <SellerDeliveryFilterCard :query="query" @apply="applyQuery" @reset="resetQuery" />

    <v-card>
      <v-alert v-if="loadError" type="error" class="ma-4" :icon="mdiAlertCircleOutline" data-testid="seller-delivery-error">
        <div class="d-flex align-center justify-space-between flex-wrap ga-2">
          <span>{{ loadError }}</span>
          <v-btn size="small" variant="outlined" color="error" data-testid="seller-delivery-retry" @click="load">다시 시도</v-btn>
        </div>
      </v-alert>
      <SellerDeliveryTable
        v-else
        :items="items"
        :total-count="totalCount"
        :page="query.page"
        :size="query.size"
        :loading="loading"
        :pending-ids="pendingIds"
        @update:page="(page) => applyQuery({ page }, false)"
        @update:size="(size) => applyQuery({ size })"
        @open-order-item="openOrderItem"
        @mark-delivered="(item) => openRowDialog(item, 'delivered')"
        @correct-tracking="(item) => openRowDialog(item, 'tracking')"
        @copy-tracking="copyTracking"
      >
        <template #empty>
          <div class="d-flex flex-column align-center text-center py-10" data-testid="seller-delivery-empty">
            <v-avatar color="surface-variant" size="48" class="mb-3">
              <v-icon :icon="mdiTruckOutline" size="22" class="text-medium-emphasis" />
            </v-avatar>
            <template v-if="filtersActive">
              <p class="text-subtitle-2 font-weight-medium mb-1">조건에 맞는 배송이 없습니다</p>
              <p class="text-body-2 text-medium-emphasis mb-3">검색어·조회 범위·기간·상태 필터를 바꾸거나 초기화해 보세요.</p>
              <v-btn size="small" variant="outlined" @click="resetQuery">필터 초기화</v-btn>
            </template>
            <template v-else>
              <p class="text-subtitle-2 font-weight-medium mb-1">배송이 없습니다</p>
              <p class="text-body-2 text-medium-emphasis">아직 발송한 품목이 없습니다. 결제완료 품목은 주문 화면에서 발송하세요.</p>
            </template>
          </div>
        </template>
      </SellerDeliveryTable>
    </v-card>

    <SellerMarkDeliveredDialog
      :open="activeDialog === 'delivered'"
      :item="dialogItem"
      @done="closeDialog(true)"
      @stale="closeDialog(true)"
      @cancel="closeDialog(false)"
    />
    <SellerDeliveryTrackingDialog
      :open="activeDialog === 'tracking'"
      :item="dialogItem"
      @done="closeDialog(true)"
      @stale="closeDialog(true)"
      @cancel="closeDialog(false)"
    />
  </div>
</template>
