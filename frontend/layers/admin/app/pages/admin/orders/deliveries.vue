<script setup lang="ts">
import { mdiAlertCircleOutline, mdiTruckOutline } from '@mdi/js'
import type { AdminDeliveryDetail, AdminDeliveryListQuery, AdminDeliverySummary } from '#layers/admin/app/types/admin-delivery'
import {
  DEFAULT_ADMIN_DELIVERY_QUERY,
  hasActiveFilters,
  parseAdminDeliveryQuery,
  toAdminDeliveryRouteQuery,
} from '#layers/admin/app/lib/admin-delivery-query'
import { toClaimListPath } from '#layers/admin/app/lib/admin-delivery-view'
import { toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminDeliveries } from '#layers/admin/app/composables/useAdminDeliveries'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '배송 관리 · zslab-mall 관리자' })

// 관리자 배송 목록(FE-37·Track 89-B BE). 배송(delivery) 행 단위·기본 조회 범위는 원 발송. URL query가 필터·정렬·페이지의 단일 소스
// (orders/index.vue 패턴): 화면 조작 → router.replace → route.query watch → 조회. 행 클릭 → 상세 다이얼로그 → 송장 수정(SHIPPING만).
const route = useRoute()
const router = useRouter()
const deliveriesApi = useAdminDeliveries()
const toast = useAdminToast()

const query = computed<AdminDeliveryListQuery>(() => parseAdminDeliveryQuery(route.query))

// ---------- 목록 ----------
const items = ref<AdminDeliverySummary[]>([])
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
    loadError.value = toAdminErrorMessage(error)
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

// router.replace가 반영되기 전에 연속 확정(기간 시작→종료 등)되면 앞 변경이 유실되므로 마지막으로 보낸 상태를 기준으로 합친다.
let pendingQuery: AdminDeliveryListQuery | null = null
watch(() => route.query, () => { pendingQuery = null; void load() }, { immediate: true, deep: true })

function applyQuery(patch: Partial<AdminDeliveryListQuery>, resetPage = true): void {
  const next: AdminDeliveryListQuery = { ...(pendingQuery ?? query.value), ...patch }
  if (resetPage && !('page' in patch)) next.page = 0
  pendingQuery = next
  void router.replace({ query: toAdminDeliveryRouteQuery(next) })
}

function resetQuery(): void {
  pendingQuery = null
  void router.replace({ query: toAdminDeliveryRouteQuery({ ...DEFAULT_ADMIN_DELIVERY_QUERY, size: query.value.size }) })
}

const filtersActive = computed(() => hasActiveFilters(query.value))

// ---------- 이동·복사 ----------
function openOrder(item: Pick<AdminDeliverySummary, 'orderId'>): void {
  if (!item.orderId) return
  // 현재 목록 URL(필터·페이지)을 back으로 넘겨 상세에서 같은 목록으로 복귀한다(FE-26 패턴).
  void navigateTo({ path: `/admin/orders/${item.orderId}`, query: { back: route.fullPath } })
}

function openClaim(item: Pick<AdminDeliverySummary, 'orderNo' | 'claimType'>): void {
  void navigateTo(toClaimListPath(item))
}

async function copyTracking(trackingNo: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(trackingNo)
    toast.info(`송장번호를 복사했습니다: ${trackingNo}`)
  } catch (error) {
    // 클립보드 권한 거부·비보안 컨텍스트 — 번호는 화면에 있으므로 안내만 한다.
    console.warn('[admin-deliveries] clipboard write failed', error)
    toast.warning('클립보드에 복사하지 못했습니다. 송장번호를 직접 선택해 복사하세요.')
  }
}

// ---------- 상세·송장 수정 다이얼로그 ----------
const detail = ref<AdminDeliveryDetail | null>(null)
const detailLoading = ref(false)
const detailOpen = ref(false)
const trackingOpen = ref(false)

async function openDetail(item: AdminDeliverySummary): Promise<void> {
  detail.value = null
  detailOpen.value = true
  detailLoading.value = true
  try {
    detail.value = await deliveriesApi.detail(item.deliveryId)
  } catch (error) {
    detailOpen.value = false
    toast.danger(toAdminErrorMessage(error))
  } finally {
    detailLoading.value = false
  }
}

function closeDetail(): void {
  detailOpen.value = false
  detail.value = null
}

function openTracking(): void {
  trackingOpen.value = true
}

function closeTracking(refresh: boolean): void {
  trackingOpen.value = false
  if (refresh) {
    closeDetail()
    void load()
  }
}
</script>

<template>
  <div>
    <AdminPageHeader title="배송 관리" description="배송 행 단위로 송장·수령인·주문번호를 검색하고 반품·교환 배송을 조회합니다. 송장 정정은 배송중인 배송만 가능합니다." />

    <AdminDeliveryFilterCard :query="query" @apply="applyQuery" @reset="resetQuery" />

    <v-card>
      <v-alert v-if="loadError" type="error" class="ma-4" :icon="mdiAlertCircleOutline" data-testid="admin-delivery-error">
        <div class="d-flex align-center justify-space-between flex-wrap ga-2">
          <span>{{ loadError }}</span>
          <v-btn size="small" variant="outlined" color="error" data-testid="admin-delivery-retry" @click="load">다시 시도</v-btn>
        </div>
      </v-alert>
      <AdminDeliveryTable
        v-else
        :items="items"
        :total-count="totalCount"
        :page="query.page"
        :size="query.size"
        :loading="loading"
        @update:page="(page) => applyQuery({ page }, false)"
        @update:size="(size) => applyQuery({ size })"
        @open="openDetail"
        @open-order="openOrder"
        @open-claim="openClaim"
        @copy-tracking="copyTracking"
      >
        <template #empty>
          <div class="d-flex flex-column align-center text-center py-10" data-testid="admin-delivery-empty">
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
              <p class="text-body-2 text-medium-emphasis">아직 발송된 배송이 없습니다.</p>
            </template>
          </div>
        </template>
      </AdminDeliveryTable>
    </v-card>

    <AdminDeliveryDetailDialog
      :open="detailOpen"
      :detail="detail"
      :loading="detailLoading"
      @correct="openTracking"
      @open-order="detail && openOrder(detail)"
      @open-claim="detail && openClaim(detail)"
      @copy-tracking="copyTracking"
      @close="closeDetail"
    />
    <AdminDeliveryTrackingDialog
      :open="trackingOpen"
      :detail="detail"
      @done="closeTracking(true)"
      @stale="closeTracking(true)"
      @cancel="closeTracking(false)"
    />
  </div>
</template>
