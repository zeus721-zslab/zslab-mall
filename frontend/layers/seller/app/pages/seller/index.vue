<script setup lang="ts">
import { mdiAlertCircleOutline, mdiRefresh } from '@mdi/js'
import { claimStatusLabel, claimTypeLabel, orderItemStatusLabel } from '~/lib/constants/claim'
import { formatDateTime } from '~/lib/utils/datetime'
import type { SellerDashboardPeriod, SellerDashboardRecentOrderItem, SellerDashboardResponse } from '#layers/seller/app/types/seller-dashboard'
import {
  DASHBOARD_DEFAULT_PERIOD_DAYS,
  DASHBOARD_MAX_PERIOD_DAYS,
  DASHBOARD_PERIOD_PRESETS,
  formatPeriodLabel,
  isAllZero,
  matchingPreset,
  normalizeDateOnly,
  orderCountTrendChart,
  presetPeriod,
  revenueTrendChart,
  validatePeriod,
  type DashboardPeriodPreset,
} from '#layers/seller/app/lib/seller-dashboard-view'
import { SELLER_DASHBOARD_PATH, SELLER_ORDERS_PATH } from '#layers/seller/app/lib/seller-back-path'
import { SELLER_CLAIM_STATUS_SEMANTIC, SELLER_ORDER_ITEM_STATUS_SEMANTIC } from '#layers/seller/app/lib/constants/seller-order'
import { semanticChipClass } from '#layers/seller/app/lib/constants/semantic'
import { toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { formatWon } from '#layers/seller/app/lib/format'
import { useSellerDashboard } from '#layers/seller/app/composables/useSellerDashboard'

definePageMeta({ layout: 'seller', middleware: ['seller', 'seller-vuetify'] })
useSeoMeta({ title: '대시보드 · zslab-mall 셀러' })

// 셀러 대시보드(Track 90-B-3·D-192·관리자 FE-33 골격 복제). 단일 GET 1회로 기간 요약·처리 대기·일별 추이·최근 품목/클레임·상위 상품을 받아 조립한다.
// 기간은 로컬 상태(프리셋 7/30/90일 + 직접 입력·최대 92일)이며 요약·추이·상위 상품에만 적용된다(대기·최근 목록은 기간 무관·BE 규칙).
// 매출·환불은 내 품목(order_item) 축이고 "주문 건수"는 내 품목이 포함된 주문 수라 주문 목록의 품목 행 수와 다르다 — 카드 캡션이 그 의미를 적는다.
const dashboardApi = useSellerDashboard()

const period = ref<SellerDashboardPeriod>(presetPeriod(DASHBOARD_DEFAULT_PERIOD_DAYS))
const periodError = ref<string | null>(null)
const activePreset = computed<DashboardPeriodPreset | null>(() => matchingPreset(period.value))

const data = ref<SellerDashboardResponse | null>(null)
const loading = ref(false)
const loadError = ref<string | null>(null)
let requestSequence = 0

async function load(): Promise<void> {
  const validation = validatePeriod(period.value)
  periodError.value = validation
  if (validation) return
  const sequence = ++requestSequence
  loading.value = true
  loadError.value = null
  try {
    const response = await dashboardApi.get(period.value)
    if (sequence !== requestSequence) return // 늦게 도착한 이전 요청은 버린다
    data.value = response
  } catch (error) {
    if (sequence !== requestSequence) return
    loadError.value = toSellerErrorMessage(error)
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

onMounted(load)

function applyPreset(days: DashboardPeriodPreset): void {
  period.value = presetPeriod(days)
  void load()
}

/** 네이티브 date 입력 확정(yyyy-MM-dd 외 값은 무시). 검증 통과 시에만 조회한다. */
function applyDate(key: 'from' | 'to', value: string | null): void {
  const normalized = normalizeDateOnly(value)
  if (!normalized) return
  period.value = { ...period.value, [key]: normalized }
  void load()
}

const revenueChart = computed(() => revenueTrendChart(data.value?.dailyTrend ?? []))
const orderCountChart = computed(() => orderCountTrendChart(data.value?.dailyTrend ?? []))
const revenueEmpty = computed(() => isAllZero((data.value?.dailyTrend ?? []).map((row) => row.revenue)))
const orderCountEmpty = computed(() => isAllZero((data.value?.dailyTrend ?? []).map((row) => row.orderCount)))
const periodLabel = computed(() => (data.value ? formatPeriodLabel(data.value.period) : formatPeriodLabel(period.value)))

// 최근 품목 → 품목 상세(back=대시보드). 최근 클레임은 상세 화면이 없어(90-D) 링크하지 않고, 상위 상품도 상품 화면(90-C) 전이라 링크 없음.
function orderItemTo(row: SellerDashboardRecentOrderItem): string {
  return `${SELLER_ORDERS_PATH}/${row.orderItemId}?back=${encodeURIComponent(SELLER_DASHBOARD_PATH)}`
}
</script>

<template>
  <div data-testid="seller-dashboard">
    <SellerPageHeader title="대시보드" description="내 품목 기준 매출·주문과 처리 대기 현황을 실시간으로 보여줍니다.">
      <template #actions>
        <v-btn variant="text" :prepend-icon="mdiRefresh" :loading="loading" data-testid="dashboard-refresh" @click="load">새로고침</v-btn>
      </template>
    </SellerPageHeader>

    <!-- 기간 선택: 프리셋 + 직접 입력(최대 92일). 요약·추이·상위 상품에 적용. -->
    <v-card class="mb-4" data-testid="dashboard-period">
      <v-card-text class="pa-4 d-flex align-center flex-wrap ga-3">
        <v-btn-toggle :model-value="activePreset" color="primary" variant="outlined" density="comfortable" divided data-testid="dashboard-period-presets">
          <v-btn v-for="preset in DASHBOARD_PERIOD_PRESETS" :key="preset.value" :value="preset.value" :data-testid="`dashboard-preset-${preset.value}`" @click="applyPreset(preset.value)">
            {{ preset.label }}
          </v-btn>
        </v-btn-toggle>
        <div class="d-flex align-center ga-2">
          <v-text-field :model-value="period.from" type="date" label="시작일" hide-details density="compact" style="width: 170px" data-testid="dashboard-from" @update:model-value="(value) => applyDate('from', value)" />
          <span class="text-medium-emphasis">~</span>
          <v-text-field :model-value="period.to" type="date" label="종료일" hide-details density="compact" style="width: 170px" data-testid="dashboard-to" @update:model-value="(value) => applyDate('to', value)" />
        </div>
        <span class="text-caption text-medium-emphasis" data-testid="dashboard-period-label">{{ periodLabel }} · 최대 {{ DASHBOARD_MAX_PERIOD_DAYS }}일</span>
        <span v-if="periodError" class="text-caption text-error" data-testid="dashboard-period-error">{{ periodError }}</span>
      </v-card-text>
    </v-card>

    <v-alert v-if="loadError" type="error" class="mb-4" :icon="mdiAlertCircleOutline" data-testid="dashboard-error">
      <div class="d-flex align-center justify-space-between flex-wrap ga-2">
        <span>{{ loadError }}</span>
        <v-btn size="small" variant="outlined" color="error" data-testid="dashboard-retry" @click="load">다시 시도</v-btn>
      </div>
    </v-alert>

    <v-progress-linear v-if="loading && !data" indeterminate color="primary" class="mb-4" data-testid="dashboard-loading" />

    <SellerDashboardSummaryCards :summary="data?.summary ?? null" />

    <SellerDashboardPending :pending="data?.pending ?? null" />

    <v-row dense class="mb-4">
      <v-col cols="12" lg="7">
        <v-card class="h-100" data-testid="dashboard-chart-revenue">
          <v-card-text class="pa-5">
            <div class="d-flex align-center justify-space-between mb-2">
              <p class="text-subtitle-1 font-weight-bold mb-0">일별 매출</p>
              <span v-if="data && revenueEmpty" class="text-caption text-medium-emphasis" data-testid="dashboard-chart-revenue-empty">데이터 없음</span>
            </div>
            <SellerChart type="bar" :series="revenueChart.series" :options="revenueChart.options" :height="280" />
          </v-card-text>
        </v-card>
      </v-col>
      <v-col cols="12" lg="5">
        <v-card class="h-100" data-testid="dashboard-chart-orders">
          <v-card-text class="pa-5">
            <div class="d-flex align-center justify-space-between mb-2">
              <p class="text-subtitle-1 font-weight-bold mb-0">일별 주문 건수</p>
              <span v-if="data && orderCountEmpty" class="text-caption text-medium-emphasis" data-testid="dashboard-chart-orders-empty">데이터 없음</span>
            </div>
            <SellerChart type="area" :series="orderCountChart.series" :options="orderCountChart.options" :height="280" />
          </v-card-text>
        </v-card>
      </v-col>
    </v-row>

    <v-row dense>
      <v-col cols="12" md="6" lg="4">
        <SellerDashboardListCard
          title="최근 주문 품목"
          :all-link="SELLER_ORDERS_PATH"
          :rows="data?.recentOrderItems ?? []"
          :row-key="(row) => row.orderItemId"
          :row-to="orderItemTo"
          empty-text="결제된 주문 품목이 없습니다"
          test-id="dashboard-recent-order-items"
        >
          <template #row="{ row }">
            <div class="d-flex align-center justify-space-between ga-3">
              <div style="min-width: 0">
                <p class="text-body-2 font-weight-medium mb-0 text-truncate">{{ row.productName }}<span v-if="row.optionLabel" class="text-medium-emphasis"> ({{ row.optionLabel }})</span> · {{ row.quantity }}개</p>
                <p class="text-caption text-medium-emphasis mb-0">{{ row.orderNo }} · {{ formatDateTime(row.paidAt) }}</p>
              </div>
              <div class="d-flex align-center ga-2 flex-shrink-0">
                <span class="text-body-2 font-weight-medium">{{ formatWon(row.totalPrice) }}</span>
                <v-chip :class="semanticChipClass(SELLER_ORDER_ITEM_STATUS_SEMANTIC[row.itemStatus])" size="x-small" variant="flat">{{ orderItemStatusLabel(row.itemStatus) }}</v-chip>
              </div>
            </div>
          </template>
        </SellerDashboardListCard>
      </v-col>
      <v-col cols="12" md="6" lg="4">
        <SellerDashboardListCard
          title="최근 클레임"
          :rows="data?.recentClaims ?? []"
          :row-key="(row) => row.claimPublicId"
          empty-text="접수된 클레임이 없습니다"
          test-id="dashboard-recent-claims"
        >
          <template #row="{ row }">
            <div class="d-flex align-center justify-space-between ga-3">
              <div style="min-width: 0">
                <p class="text-body-2 font-weight-medium mb-0 text-truncate">{{ claimTypeLabel(row.type) }} · {{ row.orderNo }}</p>
                <p class="text-caption text-medium-emphasis mb-0">{{ row.requestedAt ? formatDateTime(row.requestedAt) : '—' }}</p>
              </div>
              <v-chip :class="semanticChipClass(SELLER_CLAIM_STATUS_SEMANTIC[row.status])" size="x-small" variant="flat" class="flex-shrink-0">{{ claimStatusLabel(row.status) }}</v-chip>
            </div>
          </template>
        </SellerDashboardListCard>
      </v-col>
      <v-col cols="12" md="12" lg="4">
        <SellerDashboardListCard
          title="기간 상위 상품"
          :rows="data?.topProducts ?? []"
          :row-key="(row) => row.productPublicId ?? row.productName"
          empty-text="기간 내 판매 상품이 없습니다"
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
        </SellerDashboardListCard>
      </v-col>
    </v-row>
  </div>
</template>
