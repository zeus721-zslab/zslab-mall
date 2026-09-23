<script setup lang="ts">
import { mdiAlertCircleOutline, mdiReceiptTextOutline } from '@mdi/js'
import type { AdminOrderDetail, AdminOrderListQuery, AdminOrderSummary } from '#layers/admin/app/types/admin-order'
import {
  DEFAULT_ADMIN_ORDER_QUERY,
  hasActiveFilters,
  parseAdminOrderQuery,
  toAdminOrderRouteQuery,
} from '#layers/admin/app/lib/admin-order-query'
import { toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminOrders } from '#layers/admin/app/composables/useAdminOrders'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '전체 주문 · zslab-mall 관리자' })

// 관리자 주문 목록(FE-27·Track 79 BE). URL query가 필터·정렬·페이지의 단일 소스: 화면 조작 → router.replace → route.query watch → 조회.
// 취소는 상세에서만 한다(목록 CANCEL 액션은 상세 이동). 송장·배송완료는 행에 품목·배송 id가 없어 상세를 먼저 읽고 다이얼로그를 연다.
const route = useRoute()
const router = useRouter()
const ordersApi = useAdminOrders()
const toast = useAdminToast()

const query = computed<AdminOrderListQuery>(() => parseAdminOrderQuery(route.query))

// ---------- 목록 ----------
const items = ref<AdminOrderSummary[]>([])
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
    loadError.value = toAdminErrorMessage(error)
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

// router.replace가 반영되기 전에 연속 확정(기간 시작→종료 등)되면 앞 변경이 유실되므로 마지막으로 보낸 상태를 기준으로 합친다.
let pendingQuery: AdminOrderListQuery | null = null
watch(() => route.query, () => { pendingQuery = null; void load() }, { immediate: true, deep: true })

function applyQuery(patch: Partial<AdminOrderListQuery>, resetPage = true): void {
  const next: AdminOrderListQuery = { ...(pendingQuery ?? query.value), ...patch }
  if (resetPage && !('page' in patch)) next.page = 0
  pendingQuery = next
  void router.replace({ query: toAdminOrderRouteQuery(next) })
}

function resetQuery(): void {
  pendingQuery = null
  void router.replace({ query: toAdminOrderRouteQuery({ ...DEFAULT_ADMIN_ORDER_QUERY, size: query.value.size }) })
}

const filtersActive = computed(() => hasActiveFilters(query.value))

function open(item: AdminOrderSummary): void {
  // 현재 목록 URL(필터·페이지)을 back으로 넘겨 상세에서 같은 목록으로 복귀한다(FE-26 패턴).
  void navigateTo({ path: `/admin/orders/${item.orderId}`, query: { back: route.fullPath } })
}

// ---------- 행 액션(상세 선조회 → 다이얼로그) ----------
type RowDialog = 'shipment' | 'delivered'
const pendingIds = ref<Set<string>>(new Set())
const dialogDetail = ref<AdminOrderDetail | null>(null)
const activeDialog = ref<RowDialog | null>(null)

async function openRowDialog(item: AdminOrderSummary, dialog: RowDialog): Promise<void> {
  if (pendingIds.value.has(item.orderId)) return
  pendingIds.value = new Set(pendingIds.value).add(item.orderId)
  try {
    dialogDetail.value = await ordersApi.detail(item.orderId)
    activeDialog.value = dialog
  } catch (error) {
    toast.danger(toAdminErrorMessage(error))
  } finally {
    const next = new Set(pendingIds.value)
    next.delete(item.orderId)
    pendingIds.value = next
  }
}

function closeDialog(refresh: boolean): void {
  activeDialog.value = null
  dialogDetail.value = null
  if (refresh) void load()
}
</script>

<template>
  <div>
    <AdminPageHeader title="전체 주문" description="주문을 검색·조회하고 발송·배송완료를 처리합니다. 취소는 주문 상세에서 진행합니다." />

    <AdminOrderFilterCard :query="query" @apply="applyQuery" @reset="resetQuery" />

    <v-card>
      <v-alert v-if="loadError" type="error" class="ma-4" :icon="mdiAlertCircleOutline" data-testid="admin-order-error">
        <div class="d-flex align-center justify-space-between flex-wrap ga-2">
          <span>{{ loadError }}</span>
          <v-btn size="small" variant="outlined" color="error" data-testid="admin-order-retry" @click="load">다시 시도</v-btn>
        </div>
      </v-alert>
      <AdminOrderTable
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
        @prepare-shipment="(item) => openRowDialog(item, 'shipment')"
        @mark-delivered="(item) => openRowDialog(item, 'delivered')"
      >
        <template #empty>
          <div class="d-flex flex-column align-center text-center py-10" data-testid="admin-order-empty">
            <v-avatar color="surface-variant" size="48" class="mb-3">
              <v-icon :icon="mdiReceiptTextOutline" size="22" class="text-medium-emphasis" />
            </v-avatar>
            <template v-if="filtersActive">
              <p class="text-subtitle-2 font-weight-medium mb-1">조건에 맞는 주문이 없습니다</p>
              <p class="text-body-2 text-medium-emphasis mb-3">검색어·기간·상태 필터를 바꾸거나 초기화해 보세요.</p>
              <v-btn size="small" variant="outlined" @click="resetQuery">필터 초기화</v-btn>
            </template>
            <template v-else>
              <p class="text-subtitle-2 font-weight-medium mb-1">주문이 없습니다</p>
              <p class="text-body-2 text-medium-emphasis">아직 접수된 주문이 없습니다.</p>
            </template>
          </div>
        </template>
      </AdminOrderTable>
    </v-card>

    <AdminShipmentDialog
      :open="activeDialog === 'shipment'"
      :detail="dialogDetail"
      @done="closeDialog(true)"
      @stale="closeDialog(true)"
      @cancel="closeDialog(false)"
    />
    <AdminMarkDeliveredDialog
      :open="activeDialog === 'delivered'"
      :detail="dialogDetail"
      @done="closeDialog(true)"
      @stale="closeDialog(true)"
      @cancel="closeDialog(false)"
    />
  </div>
</template>
