<script setup lang="ts">
import { mdiAlertCircleOutline, mdiReceiptTextOutline } from '@mdi/js'
import type { SellerOrderItemSummary, SellerOrderListQuery } from '#layers/seller/app/types/seller-order'
import {
  DEFAULT_SELLER_ORDER_QUERY,
  hasActiveFilters,
  parseSellerOrderQuery,
  toSellerOrderRouteQuery,
} from '#layers/seller/app/lib/seller-order-query'
import { SELLER_CLAIMS_PATH, SELLER_ORDERS_PATH } from '#layers/seller/app/lib/seller-back-path'
import { toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerOrders } from '#layers/seller/app/composables/useSellerOrders'

definePageMeta({ layout: 'seller', middleware: ['seller', 'seller-vuetify'] })
useSeoMeta({ title: '주문 · zslab-mall 셀러' })

// 셀러 주문(품목) 목록(Track 90-B-3·D-191·관리자 orders/index.vue 골격 복제). 행 = 자기 품목(한 주문에 여러 셀러 품목이 섞이므로 주문 단위가 아님).
// URL query가 필터·페이지의 단일 소스: 화면 조작 → router.replace → route.query watch → 조회. 발송(prepare-shipment)은 PAID 행에서 바로 다이얼로그를 연다
// (행에 품목 id가 있어 상세 선조회 불요). 배송완료·송장 정정은 배송 화면.
const route = useRoute()
const router = useRouter()
const ordersApi = useSellerOrders()

const query = computed<SellerOrderListQuery>(() => parseSellerOrderQuery(route.query))

// ---------- 목록 ----------
const items = ref<SellerOrderItemSummary[]>([])
const totalCount = ref(0)
const loading = ref(false)
const loadError = ref<string | null>(null)
let requestSequence = 0

async function load(): Promise<void> {
  const sequence = ++requestSequence
  loading.value = true
  loadError.value = null
  try {
    const response = await ordersApi.list(query.value)
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
let pendingQuery: SellerOrderListQuery | null = null
watch(() => route.query, () => { pendingQuery = null; void load() }, { immediate: true, deep: true })

function applyQuery(patch: Partial<SellerOrderListQuery>, resetPage = true): void {
  const next: SellerOrderListQuery = { ...(pendingQuery ?? query.value), ...patch }
  if (resetPage && !('page' in patch)) next.page = 0
  pendingQuery = next
  void router.replace({ query: toSellerOrderRouteQuery(next) })
}

function resetQuery(): void {
  pendingQuery = null
  void router.replace({ query: toSellerOrderRouteQuery({ ...DEFAULT_SELLER_ORDER_QUERY, size: query.value.size }) })
}

const filtersActive = computed(() => hasActiveFilters(query.value))

function open(item: SellerOrderItemSummary): void {
  // 현재 목록 URL(필터·페이지)을 back으로 넘겨 상세에서 같은 목록으로 복귀한다.
  void navigateTo({ path: `${SELLER_ORDERS_PATH}/${item.orderItemId}`, query: { back: route.fullPath } })
}

/** 클레임 칩(Track 90-D-1) → 최신 클레임 상세. back은 이 목록(클레임 상세가 주문 목록 복귀를 허용). */
function openClaim(item: SellerOrderItemSummary): void {
  if (!item.claim) return
  void navigateTo({ path: `${SELLER_CLAIMS_PATH}/${item.claim.claimId}`, query: { back: route.fullPath } })
}

// ---------- 발송 다이얼로그 ----------
const shipmentItem = ref<SellerOrderItemSummary | null>(null)
const pendingIds = computed<Set<string>>(() => (shipmentItem.value ? new Set([shipmentItem.value.orderItemId]) : new Set()))

function openShipment(item: SellerOrderItemSummary): void {
  shipmentItem.value = item
}

function closeShipment(refresh: boolean): void {
  shipmentItem.value = null
  if (refresh) void load()
}
</script>

<template>
  <div data-testid="seller-orders">
    <SellerPageHeader title="주문" description="내 품목 단위로 주문을 조회하고 결제완료 품목을 발송합니다. 배송완료·송장 정정은 배송 화면에서 처리합니다." />

    <SellerOrderFilterCard :query="query" @apply="applyQuery" @reset="resetQuery" />

    <v-card>
      <v-alert v-if="loadError" type="error" class="ma-4" :icon="mdiAlertCircleOutline" data-testid="seller-order-error">
        <div class="d-flex align-center justify-space-between flex-wrap ga-2">
          <span>{{ loadError }}</span>
          <v-btn size="small" variant="outlined" color="error" data-testid="seller-order-retry" @click="load">다시 시도</v-btn>
        </div>
      </v-alert>
      <SellerOrderItemTable
        v-else
        :items="items"
        :total-count="totalCount"
        :page="query.page"
        :size="query.size"
        :loading="loading"
        :pending-ids="pendingIds"
        @update:page="(page) => applyQuery({ page }, false)"
        @update:size="(size) => applyQuery({ size })"
        @open="open"
        @prepare-shipment="openShipment"
        @open-claim="openClaim"
      >
        <template #empty>
          <div class="d-flex flex-column align-center text-center py-10" data-testid="seller-order-empty">
            <v-avatar color="surface-variant" size="48" class="mb-3">
              <v-icon :icon="mdiReceiptTextOutline" size="22" class="text-medium-emphasis" />
            </v-avatar>
            <template v-if="filtersActive">
              <p class="text-subtitle-2 font-weight-medium mb-1">조건에 맞는 주문 품목이 없습니다</p>
              <p class="text-body-2 text-medium-emphasis mb-3">검색어·기간·상태 필터를 바꾸거나 초기화해 보세요.</p>
              <v-btn size="small" variant="outlined" @click="resetQuery">필터 초기화</v-btn>
            </template>
            <template v-else>
              <p class="text-subtitle-2 font-weight-medium mb-1">주문 품목이 없습니다</p>
              <p class="text-body-2 text-medium-emphasis">아직 결제된 주문이 없습니다. 미결제 주문은 표시되지 않습니다.</p>
            </template>
          </div>
        </template>
      </SellerOrderItemTable>
    </v-card>

    <SellerShipmentDialog
      :open="shipmentItem !== null"
      :item="shipmentItem"
      @done="closeShipment(true)"
      @stale="closeShipment(true)"
      @cancel="closeShipment(false)"
    />
  </div>
</template>
