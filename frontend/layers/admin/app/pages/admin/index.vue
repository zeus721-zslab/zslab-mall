<script setup lang="ts">
import { mdiAlertCircleOutline, mdiRefresh } from '@mdi/js'
import { orderStatusLabel } from '~/lib/constants/order'
import { claimStatusLabel, claimTypeLabel } from '~/lib/constants/claim'
import { formatDateTime } from '~/lib/utils/datetime'
import type {
  AdminDashboardRecentClaim,
  AdminDashboardRecentOrder,
  AdminDashboardResponse,
  AdminDashboardTopProduct,
  AdminDashboardTopSeller,
} from '#layers/admin/app/types/admin-dashboard'
import { dailyOrdersChart, isAllZero, monthlyRevenueChart } from '#layers/admin/app/lib/admin-dashboard-view'
import { ADMIN_CLAIMS_PATH, ADMIN_DASHBOARD_PATH, ADMIN_ORDERS_PATH, ADMIN_PRODUCTS_PATH, ADMIN_SETTLEMENTS_SELLERS_PATH } from '#layers/admin/app/lib/admin-back-path'
import { ADMIN_CLAIM_STATUS_SEMANTIC, ADMIN_ORDER_STATUS_SEMANTIC } from '#layers/admin/app/lib/constants/admin-order'
import { semanticChipClass } from '#layers/admin/app/lib/constants/semantic'
import { toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { formatWon } from '#layers/admin/app/lib/format'
import { useAdminDashboard } from '#layers/admin/app/composables/useAdminDashboard'

definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '대시보드 · zslab-mall 관리자' })

// 관리자 대시보드(FE-33·D-180). 단일 GET 1회로 요약·처리 대기·추이·최근·상위를 받아 조립한다. 로딩·에러·빈 상태는 이 페이지가 소유하고
// 표시 규칙(증감률·톤·차트 옵션·링크)은 lib/admin-dashboard-view.ts와 하위 컴포넌트에 둔다.
const dashboardApi = useAdminDashboard()

const data = ref<AdminDashboardResponse | null>(null)
const loading = ref(false)
const loadError = ref<string | null>(null)
let requestSequence = 0

async function load(): Promise<void> {
  const sequence = ++requestSequence
  loading.value = true
  loadError.value = null
  try {
    const response = await dashboardApi.get()
    if (sequence !== requestSequence) return // 늦게 도착한 이전 요청은 버린다
    data.value = response
  } catch (error) {
    if (sequence !== requestSequence) return
    loadError.value = toAdminErrorMessage(error)
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

onMounted(load)

const monthlyChart = computed(() => monthlyRevenueChart(data.value?.monthlyRevenue ?? []))
const dailyChart = computed(() => dailyOrdersChart(data.value?.dailyOrders ?? []))
const monthlyEmpty = computed(() => isAllZero((data.value?.monthlyRevenue ?? []).flatMap((row) => [row.revenue, row.refund])))
const dailyEmpty = computed(() => isAllZero((data.value?.dailyOrders ?? []).map((row) => row.orderCount)))

// 상세 이동: 주문·상품은 기존 상세 라우트(back=대시보드), 클레임은 상세 화면이 없어 클레임 목록의 주문번호 정확일치 검색으로,
// 셀러는 셀러 상세가 없어 셀러별 정산 이력으로 연결한다.
function orderTo(row: AdminDashboardRecentOrder): string {
  return `${ADMIN_ORDERS_PATH}/${row.orderPublicId}?back=${encodeURIComponent(ADMIN_DASHBOARD_PATH)}`
}
function claimTo(row: AdminDashboardRecentClaim): string {
  return `${ADMIN_CLAIMS_PATH}?keyword=${encodeURIComponent(row.orderNo)}`
}
function sellerTo(row: AdminDashboardTopSeller): string | null {
  return row.sellerPublicId ? `${ADMIN_SETTLEMENTS_SELLERS_PATH}?seller=${encodeURIComponent(row.sellerPublicId)}` : null
}
function productTo(row: AdminDashboardTopProduct): string | null {
  return row.productPublicId ? `${ADMIN_PRODUCTS_PATH}/${row.productPublicId}?back=${encodeURIComponent(ADMIN_DASHBOARD_PATH)}` : null
}
</script>

<template>
  <div data-testid="admin-dashboard">
    <AdminPageHeader title="대시보드" description="오늘·이번 달 매출과 처리 대기 현황, 최근 6개월·30일 추이를 실시간으로 보여줍니다.">
      <template #actions>
        <v-btn variant="text" :prepend-icon="mdiRefresh" :loading="loading" data-testid="dashboard-refresh" @click="load">새로고침</v-btn>
      </template>
    </AdminPageHeader>

    <v-alert v-if="loadError" type="error" class="mb-4" :icon="mdiAlertCircleOutline" data-testid="dashboard-error">
      <div class="d-flex align-center justify-space-between flex-wrap ga-2">
        <span>{{ loadError }}</span>
        <v-btn size="small" variant="outlined" color="error" data-testid="dashboard-retry" @click="load">다시 시도</v-btn>
      </div>
    </v-alert>

    <v-progress-linear v-if="loading && !data" indeterminate color="primary" class="mb-4" data-testid="dashboard-loading" />

    <AdminDashboardSummaryCards :summary="data?.summary ?? null" />

    <AdminDashboardPending :pending="data?.pending ?? null" />

    <v-row dense class="mb-4">
      <v-col cols="12" lg="7">
        <v-card class="h-100" data-testid="dashboard-chart-monthly">
          <v-card-text class="pa-5">
            <div class="d-flex align-center justify-space-between mb-2">
              <p class="text-subtitle-1 font-weight-bold mb-0">최근 6개월 매출</p>
              <span v-if="data && monthlyEmpty" class="text-caption text-medium-emphasis" data-testid="dashboard-chart-monthly-empty">데이터 없음</span>
            </div>
            <AdminChart type="bar" :series="monthlyChart.series" :options="monthlyChart.options" :height="280" />
          </v-card-text>
        </v-card>
      </v-col>
      <v-col cols="12" lg="5">
        <v-card class="h-100" data-testid="dashboard-chart-daily">
          <v-card-text class="pa-5">
            <div class="d-flex align-center justify-space-between mb-2">
              <p class="text-subtitle-1 font-weight-bold mb-0">최근 30일 주문수</p>
              <span v-if="data && dailyEmpty" class="text-caption text-medium-emphasis" data-testid="dashboard-chart-daily-empty">데이터 없음</span>
            </div>
            <AdminChart type="area" :series="dailyChart.series" :options="dailyChart.options" :height="280" />
          </v-card-text>
        </v-card>
      </v-col>
    </v-row>

    <v-row dense>
      <v-col cols="12" md="6">
        <AdminDashboardListCard
          title="최근 주문"
          :all-link="ADMIN_ORDERS_PATH"
          :rows="data?.recentOrders ?? []"
          :row-key="(row) => row.orderPublicId"
          :row-to="orderTo"
          test-id="dashboard-recent-orders"
        >
          <template #row="{ row }">
            <div class="d-flex align-center justify-space-between ga-3">
              <div style="min-width: 0">
                <p class="text-body-2 font-weight-medium mb-0 text-truncate">{{ row.orderNo }}</p>
                <p class="text-caption text-medium-emphasis mb-0">{{ row.buyerName ?? '—' }} · {{ formatDateTime(row.paidAt) }}</p>
              </div>
              <div class="d-flex align-center ga-2 flex-shrink-0">
                <span class="text-body-2 font-weight-medium">{{ formatWon(row.totalPrice) }}</span>
                <v-chip :class="semanticChipClass(ADMIN_ORDER_STATUS_SEMANTIC[row.status])" size="x-small" variant="flat">{{ orderStatusLabel(row.status) }}</v-chip>
              </div>
            </div>
          </template>
        </AdminDashboardListCard>
      </v-col>
      <v-col cols="12" md="6">
        <AdminDashboardListCard
          title="최근 클레임"
          :all-link="ADMIN_CLAIMS_PATH"
          :rows="data?.recentClaims ?? []"
          :row-key="(row) => row.claimPublicId"
          :row-to="claimTo"
          test-id="dashboard-recent-claims"
        >
          <template #row="{ row }">
            <div class="d-flex align-center justify-space-between ga-3">
              <div style="min-width: 0">
                <p class="text-body-2 font-weight-medium mb-0 text-truncate">{{ claimTypeLabel(row.type) }} · {{ row.orderNo }}</p>
                <p class="text-caption text-medium-emphasis mb-0">{{ row.requestedAt ? formatDateTime(row.requestedAt) : '—' }}</p>
              </div>
              <v-chip :class="semanticChipClass(ADMIN_CLAIM_STATUS_SEMANTIC[row.status])" size="x-small" variant="flat" class="flex-shrink-0">{{ claimStatusLabel(row.status) }}</v-chip>
            </div>
          </template>
        </AdminDashboardListCard>
      </v-col>
      <v-col cols="12" md="6">
        <AdminDashboardListCard
          title="이번 달 상위 셀러"
          :all-link="ADMIN_SETTLEMENTS_SELLERS_PATH"
          :rows="data?.topSellers ?? []"
          :row-key="(row) => row.sellerPublicId ?? row.sellerName ?? String(row.revenue)"
          :row-to="sellerTo"
          test-id="dashboard-top-sellers"
        >
          <template #row="{ row }">
            <div class="d-flex align-center justify-space-between ga-3">
              <div style="min-width: 0">
                <p class="text-body-2 font-weight-medium mb-0 text-truncate">{{ row.sellerName ?? '—' }}</p>
                <p class="text-caption text-medium-emphasis mb-0">품목 {{ row.orderItemCount.toLocaleString('ko-KR') }}건</p>
              </div>
              <span class="text-body-2 font-weight-medium flex-shrink-0">{{ formatWon(row.revenue) }}</span>
            </div>
          </template>
        </AdminDashboardListCard>
      </v-col>
      <v-col cols="12" md="6">
        <AdminDashboardListCard
          title="이번 달 상위 상품"
          :all-link="ADMIN_PRODUCTS_PATH"
          :rows="data?.topProducts ?? []"
          :row-key="(row) => row.productPublicId ?? row.productName"
          :row-to="productTo"
          test-id="dashboard-top-products"
        >
          <template #row="{ row }">
            <div class="d-flex align-center justify-space-between ga-3">
              <div style="min-width: 0">
                <p class="text-body-2 font-weight-medium mb-0 text-truncate">{{ row.productName }}</p>
                <p class="text-caption text-medium-emphasis mb-0">수량 {{ row.quantity.toLocaleString('ko-KR') }}개</p>
              </div>
              <span class="text-body-2 font-weight-medium flex-shrink-0">{{ formatWon(row.revenue) }}</span>
            </div>
          </template>
        </AdminDashboardListCard>
      </v-col>
    </v-row>
  </div>
</template>
