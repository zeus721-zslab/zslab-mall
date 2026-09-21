<script setup lang="ts">
import { mdiAlertCircleOutline, mdiDownload, mdiInformationOutline, mdiOpenInNew, mdiRefresh } from '@mdi/js'
import type { SellerSalesBreakdownResponse } from '#layers/seller/app/types/seller-stats'
import { CATEGORY_AXIS_NOTICE } from '~/lib/constants/stats'
import { isTrendEmpty, normalizeSalesStats, showsCompare, type NormalizedSalesStats } from '~/lib/stats-view'
import { SELLER_STATS_AXES, SELLER_STATS_AXIS_LABELS, SELLER_STATS_MAX_PERIOD_DAYS, type SellerStatsAxis } from '#layers/seller/app/lib/constants/seller-stats'
import {
  DEFAULT_SELLER_SALES_STATS_QUERY,
  parseSellerSalesStatsQuery,
  resolveSellerStatsPeriod,
  sellerStatsPeriodError,
  toSellerBreakdownApiParams,
  toSellerSalesApiParams,
  toSellerSalesStatsRouteQuery,
  type SellerSalesStatsQuery,
} from '#layers/seller/app/lib/seller-stats-query'
import { sellerBreakdownRowViews, sellerSalesTrendChart, type SellerSalesBreakdownRowView } from '#layers/seller/app/lib/seller-stats-view'
import { SELLER_PRODUCTS_PATH, SELLER_SETTLEMENTS_PATH } from '#layers/seller/app/lib/seller-back-path'
import { toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerSalesStats } from '#layers/seller/app/composables/useSellerSalesStats'
import { useSellerToast } from '#layers/seller/app/composables/useSellerToast'

definePageMeta({ layout: 'seller', middleware: ['seller', 'seller-vuetify'] })
useSeoMeta({ title: '매출 통계 · zslab-mall 셀러' })

// 셀러 매출 통계(Track 90-E-1·D-200·관리자 FE-34 골격 복제). URL query(preset·from·to·unit·compare·axis)가 단일 소스: 화면 조작 → router.replace →
// route.query watch → 조회. 요약·추이(sales)와 분해(breakdown)는 별도 요청이라 축만 바뀌면 breakdown만 다시 부른다. 기간 오류(역전·365일 초과·미입력)면
// 요청하지 않는다. 값은 내 품목(order_item) 축(D-192)이며 수수료·정산예정액은 표기하지 않고 정산 내역 화면으로 안내한다.
const route = useRoute()
const router = useRouter()
const statsApi = useSellerSalesStats()
const toast = useSellerToast()

const query = computed<SellerSalesStatsQuery>(() => parseSellerSalesStatsQuery(route.query))
const period = computed(() => resolveSellerStatsPeriod(query.value, new Date()))
const periodError = computed(() => sellerStatsPeriodError(period.value))
const canQuery = computed(() => period.value !== null && periodError.value === null)

// router.replace가 반영되기 전에 연속 확정되면 앞 변경이 유실되므로 마지막으로 보낸 상태를 기준으로 합친다(관리자·주문 목록 선례).
let pendingQuery: SellerSalesStatsQuery | null = null
// 프리셋 → custom 전환(날짜 한쪽만 입력)이면 반대쪽은 현재 표시 기간으로 보충해 URL이 custom으로 성립하게 한다.
function applyQuery(patch: Partial<SellerSalesStatsQuery>): void {
  const base = pendingQuery ?? query.value
  const next: SellerSalesStatsQuery = { ...base, ...patch }
  if (next.preset === 'custom' && base.preset !== 'custom') {
    if (!('from' in patch)) next.from = period.value?.from ?? null
    if (!('to' in patch)) next.to = period.value?.to ?? null
  }
  pendingQuery = next
  void router.replace({ query: toSellerSalesStatsRouteQuery(next) })
}

// ---------- 요약·추이 ----------
const stats = ref<NormalizedSalesStats | null>(null)
const statsLoading = ref(false)
const statsError = ref<string | null>(null)
let statsSequence = 0

async function loadStats(): Promise<void> {
  const sequence = ++statsSequence
  if (!canQuery.value || period.value === null) {
    stats.value = null
    return
  }
  statsLoading.value = true
  statsError.value = null
  try {
    const response = await statsApi.sales(toSellerSalesApiParams(query.value, period.value))
    if (sequence !== statsSequence) return // 늦게 도착한 이전 요청은 버린다
    stats.value = normalizeSalesStats(response)
  } catch (error) {
    if (sequence !== statsSequence) return
    statsError.value = toSellerErrorMessage(error)
  } finally {
    if (sequence === statsSequence) statsLoading.value = false
  }
}

// ---------- 분해 ----------
const breakdown = ref<SellerSalesBreakdownResponse | null>(null)
const breakdownLoading = ref(false)
const breakdownError = ref<string | null>(null)
let breakdownSequence = 0

async function loadBreakdown(): Promise<void> {
  const sequence = ++breakdownSequence
  if (!canQuery.value || period.value === null) {
    breakdown.value = null
    return
  }
  breakdownLoading.value = true
  breakdownError.value = null
  try {
    const response = await statsApi.breakdown(toSellerBreakdownApiParams(query.value, period.value))
    if (sequence !== breakdownSequence) return
    breakdown.value = response
  } catch (error) {
    if (sequence !== breakdownSequence) return
    breakdownError.value = toSellerErrorMessage(error)
  } finally {
    if (sequence === breakdownSequence) breakdownLoading.value = false
  }
}

// 요청 키가 실제로 바뀔 때만 각각 재조회(같은 프리셋의 router.replace 반복·무관한 항목 변경에 재요청하지 않는다).
const statsKey = computed(() => JSON.stringify([period.value, query.value.unit, query.value.compare]))
const breakdownKey = computed(() => JSON.stringify([period.value, query.value.compare, query.value.axis]))
watch(statsKey, () => { pendingQuery = null; void loadStats() }, { immediate: true })
watch(breakdownKey, () => { pendingQuery = null; void loadBreakdown() }, { immediate: true })

function reload(): void {
  void loadStats()
  void loadBreakdown()
}

// ---------- 표시 ----------
const trendChart = computed(() => sellerSalesTrendChart(stats.value?.trend ?? [], stats.value?.compareTrend ?? null))
const trendEmpty = computed(() => stats.value !== null && isTrendEmpty(stats.value.trend))
const compareShown = computed(() => showsCompare(query.value.compare))
const compareUnavailable = computed(() => compareShown.value && stats.value !== null && stats.value.compareSummary === null)
const rows = computed(() => sellerBreakdownRowViews(breakdown.value))
const axisTabs = SELLER_STATS_AXES.map((value) => ({ value, title: SELLER_STATS_AXIS_LABELS[value] }))

function changeAxis(axis: SellerStatsAxis): void {
  applyQuery({ axis })
}

/** 상품 행 → 셀러 상품 상세(back=현재 통계 URL·resolveBackPath 허용 목록에 SELLER_STATS_SALES_PATH 등록). */
function openProduct(row: SellerSalesBreakdownRowView): void {
  if (!row.key) return
  void navigateTo({ path: `${SELLER_PRODUCTS_PATH}/${row.key}`, query: { back: route.fullPath } })
}

function resetQuery(): void {
  void router.replace({ query: toSellerSalesStatsRouteQuery(DEFAULT_SELLER_SALES_STATS_QUERY) })
}

// ---------- CSV ----------
const csvBusy = ref(false)
const OBJECT_URL_REVOKE_DELAY_MS = 10_000

/** Bearer가 필요해 직링크 대신 blob → 임시 a 태그 클릭 → revokeObjectURL. 파일명은 Content-Disposition에서 추출(실패 시 기본명). */
async function downloadCsv(): Promise<void> {
  if (!canQuery.value || period.value === null || csvBusy.value) return
  csvBusy.value = true
  try {
    const { blob, fileName } = await statsApi.breakdownCsv(toSellerBreakdownApiParams(query.value, period.value))
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = fileName
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    // 클릭 직후 동기 revoke는 브라우저가 다운로드를 시작하기 전에 URL을 무효화할 수 있어(Firefox 알려진 케이스) 지연 revoke
    window.setTimeout(() => URL.revokeObjectURL(url), OBJECT_URL_REVOKE_DELAY_MS)
    toast.success(`${fileName} 다운로드를 시작했습니다.`)
  } catch (error) {
    toast.danger(`CSV 내보내기 실패: ${toSellerErrorMessage(error)}`)
  } finally {
    csvBusy.value = false
  }
}
</script>

<template>
  <div data-testid="seller-sales-stats">
    <SellerPageHeader title="매출 통계" description="내 품목 기준 매출을 기간·집계 단위·비교 기간으로 보고, 상품·옵션·카테고리로 분해합니다.">
      <template #actions>
        <v-btn variant="text" size="small" data-testid="sales-reset" @click="resetQuery">초기화</v-btn>
        <v-btn variant="text" :prepend-icon="mdiRefresh" :loading="statsLoading || breakdownLoading" data-testid="sales-refresh" @click="reload">새로고침</v-btn>
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

    <v-alert v-if="statsError" type="error" class="mb-4" :icon="mdiAlertCircleOutline" data-testid="sales-error">
      <div class="d-flex align-center justify-space-between flex-wrap ga-2">
        <span>{{ statsError }}</span>
        <v-btn size="small" variant="outlined" color="error" data-testid="sales-retry" @click="loadStats">다시 시도</v-btn>
      </div>
    </v-alert>

    <v-progress-linear v-if="statsLoading && !stats" indeterminate color="primary" class="mb-4" data-testid="sales-loading" />

    <v-alert v-if="compareUnavailable" type="info" variant="tonal" density="compact" class="mb-4" :icon="mdiInformationOutline" data-testid="sales-compare-unavailable">
      비교 기간에 결제·환불 데이터가 없어 증감률을 표시하지 않습니다.
    </v-alert>

    <SellerSalesSummaryCards :summary="stats?.summary ?? null" :compare-summary="stats?.compareSummary ?? null" :compare="query.compare" />

    <!-- 수수료·정산예정액은 정산 산식(구매확정·월 단위)과 축이 달라 통계에 싣지 않는다(D-200) → 정산 내역 화면으로 안내 -->
    <p class="text-caption text-medium-emphasis mb-4 d-flex align-center ga-1" data-testid="sales-settlement-hint">
      <v-icon :icon="mdiInformationOutline" size="14" />
      <span>매출은 결제 완료 기준 내 품목 금액 합입니다. 수수료·정산 예정액은</span>
      <NuxtLink :to="SELLER_SETTLEMENTS_PATH" class="text-primary text-decoration-none font-weight-medium d-inline-flex align-center" data-testid="sales-settlement-link">
        정산 내역 보기 <v-icon :icon="mdiOpenInNew" size="12" class="ml-1" />
      </NuxtLink>
      <span>에서 확인하세요.</span>
    </p>

    <v-card class="mb-4" data-testid="sales-chart">
      <v-card-text class="pa-5">
        <div class="d-flex align-center justify-space-between mb-2">
          <p class="text-subtitle-1 font-weight-bold mb-0">매출 추이</p>
          <span v-if="trendEmpty" class="text-caption text-medium-emphasis" data-testid="sales-chart-empty">데이터 없음</span>
        </div>
        <SellerChart type="line" :series="trendChart.series" :options="trendChart.options" :height="320" />
      </v-card-text>
    </v-card>

    <v-card data-testid="sales-breakdown">
      <div class="d-flex align-center justify-space-between flex-wrap ga-2 pt-4 px-5">
        <v-card-title class="text-subtitle-2 font-weight-bold pa-0">분해</v-card-title>
        <v-btn size="small" variant="outlined" color="primary" :prepend-icon="mdiDownload" :loading="csvBusy" :disabled="!canQuery || csvBusy" data-testid="sales-csv" @click="downloadCsv">CSV 내보내기</v-btn>
      </div>
      <v-tabs :model-value="query.axis" color="primary" density="comfortable" class="px-2" data-testid="breakdown-axis-tabs" @update:model-value="(value) => changeAxis(value as SellerStatsAxis)">
        <v-tab v-for="tab in axisTabs" :key="tab.value" :value="tab.value" :data-testid="`breakdown-axis-${tab.value}`">{{ tab.title }}</v-tab>
      </v-tabs>
      <p v-if="query.axis === 'CATEGORY'" class="text-caption text-medium-emphasis px-5 pt-2 mb-0" data-testid="breakdown-category-notice">
        <v-icon :icon="mdiInformationOutline" size="14" class="mr-1" />{{ CATEGORY_AXIS_NOTICE }}
      </p>
      <p v-else-if="query.axis === 'OPTION'" class="text-caption text-medium-emphasis px-5 pt-2 mb-0" data-testid="breakdown-option-notice">
        <v-icon :icon="mdiInformationOutline" size="14" class="mr-1" />주문 시점의 상품명·옵션 라벨 기준입니다. 옵션이 없는 상품은 "(옵션 없음)"으로 표시됩니다.
      </p>
      <SellerSalesBreakdownTable
        :rows="rows"
        :show-compare="compareShown"
        :loading="breakdownLoading"
        :load-error="breakdownError"
        @open="openProduct"
        @retry="loadBreakdown"
      />
    </v-card>
  </div>
</template>
