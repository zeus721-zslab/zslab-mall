<script setup lang="ts">
import { mdiAlertCircleOutline, mdiCashRefund, mdiClipboardAlertOutline, mdiInformationOutline, mdiPercentOutline, mdiRefresh } from '@mdi/js'
import { showsCompare, claimReasonRows, claimTypeRows, isClaimTrendEmpty } from '~/lib/stats-view'
import { SELLER_STATS_MAX_PERIOD_DAYS } from '#layers/seller/app/lib/constants/seller-stats'
import {
  DEFAULT_SELLER_STATS_PERIOD_QUERY,
  parseSellerStatsPeriodQuery,
  resolveSellerStatsPeriod,
  sellerStatsPeriodError,
  toSellerOrderStatsApiParams,
  toSellerStatsPeriodRouteQuery,
  type SellerStatsPeriodQuery,
} from '#layers/seller/app/lib/seller-stats-query'
import {
  normalizeSellerOrderStats,
  sellerClaimProductRows,
  sellerClaimSummaryCards,
  sellerClaimTrendChart,
  type NormalizedSellerOrderStats,
  type SellerClaimProductRowView,
} from '#layers/seller/app/lib/seller-order-stats-view'
import { SELLER_PRODUCTS_PATH } from '#layers/seller/app/lib/seller-back-path'
import { toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerOrderStats } from '#layers/seller/app/composables/useSellerOrderStats'

definePageMeta({ layout: 'seller', middleware: ['seller', 'seller-vuetify'] })
useSeoMeta({ title: '주문·클레임 통계 · zslab-mall 셀러' })

// 셀러 주문·클레임 통계(Track 90-E-2·D-200·관리자 FE-35 골격 복제). URL query(preset·from·to·unit·compare)가 단일 소스(매출 통계와 같은 흐름):
// 조작 → router.replace → watch → 조회. 응답이 하나라 요청 키가 바뀔 때만 1회 조회한다. 기간 오류(역전·365일 초과·미입력)면 요청하지 않는다.
// 값은 내 품목(order_item) 단위이며 환불률 분모는 내 품목 매출(D-200 결정 3 α).
const route = useRoute()
const router = useRouter()
const statsApi = useSellerOrderStats()

const query = computed<SellerStatsPeriodQuery>(() => parseSellerStatsPeriodQuery(route.query))
const period = computed(() => resolveSellerStatsPeriod({ ...query.value, axis: 'PRODUCT' }, new Date()))
const periodError = computed(() => sellerStatsPeriodError(period.value))
const canQuery = computed(() => period.value !== null && periodError.value === null)

let pendingQuery: SellerStatsPeriodQuery | null = null
function applyQuery(patch: Partial<SellerStatsPeriodQuery>): void {
  const base = pendingQuery ?? query.value
  const next: SellerStatsPeriodQuery = { ...base, ...patch }
  if (next.preset === 'custom' && base.preset !== 'custom') {
    if (!('from' in patch)) next.from = period.value?.from ?? null
    if (!('to' in patch)) next.to = period.value?.to ?? null
  }
  pendingQuery = next
  void router.replace({ query: toSellerStatsPeriodRouteQuery(next) })
}

const stats = ref<NormalizedSellerOrderStats | null>(null)
const loading = ref(false)
const loadError = ref<string | null>(null)
let sequence = 0

async function load(): Promise<void> {
  const current = ++sequence
  if (!canQuery.value || period.value === null) {
    stats.value = null
    return
  }
  loading.value = true
  loadError.value = null
  try {
    const response = await statsApi.orders(toSellerOrderStatsApiParams(query.value, period.value))
    if (current !== sequence) return
    stats.value = normalizeSellerOrderStats(response)
  } catch (error) {
    if (current !== sequence) return
    loadError.value = toSellerErrorMessage(error)
  } finally {
    if (current === sequence) loading.value = false
  }
}

const requestKey = computed(() => JSON.stringify([period.value, query.value.unit, query.value.compare]))
watch(requestKey, () => { pendingQuery = null; void load() }, { immediate: true })

function resetQuery(): void {
  void router.replace({ query: toSellerStatsPeriodRouteQuery(DEFAULT_SELLER_STATS_PERIOD_QUERY) })
}

// ---------- 표시 ----------
const summaryCards = computed(() => sellerClaimSummaryCards(stats.value?.claimSummary ?? null, stats.value?.compareClaimSummary ?? null))
const SUMMARY_ICONS = { claimCount: mdiClipboardAlertOutline, claimRate: mdiPercentOutline, refundAmount: mdiCashRefund, refundRate: mdiPercentOutline }
const SUMMARY_COLORS = { claimCount: 'warning', claimRate: 'warning', refundAmount: 'error', refundRate: 'error' } as const
const trendChart = computed(() => sellerClaimTrendChart(stats.value?.claimTrend ?? [], stats.value?.compareClaimTrend ?? null))
const trendEmpty = computed(() => stats.value !== null && isClaimTrendEmpty(stats.value.claimTrend))
const compareShown = computed(() => showsCompare(query.value.compare))
const compareUnavailable = computed(() => compareShown.value && stats.value !== null && stats.value.compareClaimSummary === null)
const typeRows = computed(() => claimTypeRows(stats.value?.claimByType ?? []))
const reasonRows = computed(() => claimReasonRows(stats.value?.claimByReason ?? []))
const productRows = computed(() => sellerClaimProductRows(stats.value?.claimByProduct ?? []))

/** 상품 행 → 셀러 상품 상세(back=현재 통계 URL·resolveBackPath 허용 목록 SELLER_STATS_ORDERS_PATH). */
function openProduct(row: SellerClaimProductRowView): void {
  if (!row.key) return
  void navigateTo({ path: `${SELLER_PRODUCTS_PATH}/${row.key}`, query: { back: route.fullPath } })
}
</script>

<template>
  <div data-testid="seller-order-stats">
    <SellerPageHeader title="주문·클레임 통계" description="내 품목 기준 결제 → 출고 → 배송완료 퍼널, 처리 소요시간, 클레임률·환불률과 클레임 유형·사유·상품 분포를 봅니다.">
      <template #actions>
        <v-btn variant="text" size="small" data-testid="orders-reset" @click="resetQuery">초기화</v-btn>
        <v-btn variant="text" :prepend-icon="mdiRefresh" :loading="loading" data-testid="orders-refresh" @click="load">새로고침</v-btn>
      </template>
    </SellerPageHeader>

    <SellerStatsTabs />

    <SellerPeriodPicker
      :preset="query.preset"
      :from="period?.from ?? query.from"
      :to="period?.to ?? query.to"
      :unit="query.unit"
      :compare="query.compare"
      :period-error="periodError"
      :max-days="SELLER_STATS_MAX_PERIOD_DAYS"
      @apply="applyQuery"
    />

    <v-alert v-if="loadError" type="error" class="mb-4" :icon="mdiAlertCircleOutline" data-testid="orders-error">
      <div class="d-flex align-center justify-space-between flex-wrap ga-2">
        <span>{{ loadError }}</span>
        <v-btn size="small" variant="outlined" color="error" data-testid="orders-retry" @click="load">다시 시도</v-btn>
      </div>
    </v-alert>

    <v-progress-linear v-if="loading && !stats" indeterminate color="primary" class="mb-4" data-testid="orders-loading" />

    <SellerOrderFunnel :funnel="stats?.funnel ?? null" />

    <SellerLeadTimeCards :lead-time="stats?.leadTime ?? null" />

    <v-alert v-if="compareUnavailable" type="info" variant="tonal" density="compact" class="mb-4" :icon="mdiInformationOutline" data-testid="orders-compare-unavailable">
      비교 기간에 클레임·환불·결제 데이터가 없어 증감률을 표시하지 않습니다.
    </v-alert>

    <SellerStatsSummaryCards :cards="summaryCards" :icons="SUMMARY_ICONS" :colors="SUMMARY_COLORS" :compare="query.compare" testid-prefix="claim" :lg="3" />

    <v-card class="mb-4" data-testid="claim-trend-chart">
      <v-card-text class="pa-5">
        <div class="d-flex align-center justify-space-between mb-2">
          <p class="text-subtitle-1 font-weight-bold mb-0">클레임률·환불률 추이</p>
          <span v-if="trendEmpty" class="text-caption text-medium-emphasis" data-testid="claim-trend-empty">데이터 없음</span>
        </div>
        <p class="text-caption text-medium-emphasis mb-2">클레임률 = 요청 건수 ÷ 결제 품목 수 · 환불률 = 완료 환불액 ÷ 내 품목 매출(금액 기준)</p>
        <SellerChart type="line" :series="trendChart.series" :options="trendChart.options" :height="320" />
      </v-card-text>
    </v-card>

    <v-row dense class="mb-4">
      <v-col cols="12" lg="6">
        <SellerDonutCard title="클레임 유형" :rows="typeRows" unit="건" testid="claim-by-type" />
      </v-col>
      <v-col cols="12" lg="6">
        <SellerDonutCard title="클레임 사유" :rows="reasonRows" unit="건" testid="claim-by-reason" />
      </v-col>
    </v-row>

    <SellerClaimProductTable :rows="productRows" @open="openProduct" />
  </div>
</template>
