<script setup lang="ts">
import { mdiAlertCircleOutline, mdiArrowLeft, mdiDownload, mdiInformationOutline, mdiRefresh } from '@mdi/js'
import type { AdminSalesBreakdownResponse } from '#layers/admin/app/types/admin-sales-stats'
import {
  CATEGORY_AXIS_NOTICE,
  STATS_AXES,
  STATS_AXIS_LABELS,
  type StatsAxis,
} from '#layers/admin/app/lib/constants/admin-sales-stats'
import {
  DEFAULT_ADMIN_SALES_STATS_QUERY,
  isPeriodInverted,
  parseAdminSalesStatsQuery,
  resolvePeriod,
  toAdminSalesStatsRouteQuery,
  toBreakdownApiParams,
  toSalesApiParams,
  type AdminSalesStatsQuery,
} from '#layers/admin/app/lib/admin-sales-stats-query'
import {
  breadcrumbLabel,
  breakdownRowViews,
  isTrendEmpty,
  normalizeSalesStats,
  salesTrendChart,
  showsCompare,
  type NormalizedSalesStats,
  type SalesBreakdownRowView,
} from '#layers/admin/app/lib/admin-sales-stats-view'
import { toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminSalesStats } from '#layers/admin/app/composables/useAdminSalesStats'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '매출 통계 · zslab-mall 관리자' })

// 매출 통계(FE-34·D-181). URL query(preset·from·to·unit·compare·axis·parent)가 단일 소스: 화면 조작 → router.replace → route.query watch → 조회.
// 요약·추이(sales)와 분해(breakdown)는 별도 요청이라 축·드릴다운만 바뀌면 breakdown만 다시 부른다. 기간 역전·미입력이면 요청하지 않는다.
const route = useRoute()
const router = useRouter()
const statsApi = useAdminSalesStats()
const toast = useAdminToast()

const query = computed<AdminSalesStatsQuery>(() => parseAdminSalesStatsQuery(route.query))
const period = computed(() => resolvePeriod(query.value, new Date()))
const periodInverted = computed(() => isPeriodInverted(period.value))
const canQuery = computed(() => period.value !== null && !periodInverted.value)

// router.replace가 반영되기 전에 연속 확정되면 앞 변경이 유실되므로 마지막으로 보낸 상태를 기준으로 합친다(주문 목록 선례).
let pendingQuery: AdminSalesStatsQuery | null = null
// 프리셋 → custom 전환(날짜 한쪽만 입력)이면 반대쪽은 현재 표시 기간으로 보충해 URL이 custom으로 성립하게 한다.
function applyQuery(patch: Partial<AdminSalesStatsQuery>): void {
  const base = pendingQuery ?? query.value
  const next: AdminSalesStatsQuery = { ...base, ...patch }
  if (next.preset === 'custom' && base.preset !== 'custom') {
    if (!('from' in patch)) next.from = period.value?.from ?? null
    if (!('to' in patch)) next.to = period.value?.to ?? null
  }
  pendingQuery = next
  void router.replace({ query: toAdminSalesStatsRouteQuery(next) })
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
    const response = await statsApi.sales(toSalesApiParams(query.value, period.value))
    if (sequence !== statsSequence) return // 늦게 도착한 이전 요청은 버린다
    stats.value = normalizeSalesStats(response)
  } catch (error) {
    if (sequence !== statsSequence) return
    statsError.value = toAdminErrorMessage(error)
  } finally {
    if (sequence === statsSequence) statsLoading.value = false
  }
}

// ---------- 분해 ----------
const breakdown = ref<AdminSalesBreakdownResponse | null>(null)
const breakdownLoading = ref(false)
const breakdownError = ref<string | null>(null)
let breakdownSequence = 0
/** 드릴다운 상위 행 이름(클릭 시 기억·새로고침 후엔 key만 표시). */
const parentName = ref<string | null>(null)

async function loadBreakdown(): Promise<void> {
  const sequence = ++breakdownSequence
  if (!canQuery.value || period.value === null) {
    breakdown.value = null
    return
  }
  breakdownLoading.value = true
  breakdownError.value = null
  try {
    const response = await statsApi.breakdown(toBreakdownApiParams(query.value, period.value))
    if (sequence !== breakdownSequence) return
    breakdown.value = response
  } catch (error) {
    if (sequence !== breakdownSequence) return
    breakdownError.value = toAdminErrorMessage(error)
  } finally {
    if (sequence === breakdownSequence) breakdownLoading.value = false
  }
}

// 요청 키가 실제로 바뀔 때만 각각 재조회(같은 프리셋의 router.replace 반복·무관한 항목 변경에 재요청하지 않는다).
const statsKey = computed(() => JSON.stringify([period.value, query.value.unit, query.value.compare]))
const breakdownKey = computed(() => JSON.stringify([period.value, query.value.compare, query.value.axis, query.value.parent]))
watch(statsKey, () => { pendingQuery = null; void loadStats() }, { immediate: true })
watch(breakdownKey, () => { pendingQuery = null; void loadBreakdown() }, { immediate: true })

function reload(): void {
  void loadStats()
  void loadBreakdown()
}

// ---------- 표시 ----------
const trendChart = computed(() => salesTrendChart(stats.value?.trend ?? [], stats.value?.compareTrend ?? null))
const trendEmpty = computed(() => stats.value !== null && isTrendEmpty(stats.value.trend))
const compareShown = computed(() => showsCompare(query.value.compare))
const compareUnavailable = computed(() => compareShown.value && stats.value !== null && stats.value.compareSummary === null)
const rows = computed(() => breakdownRowViews(breakdown.value))
const axisTabs = STATS_AXES.map((value) => ({ value, title: STATS_AXIS_LABELS[value] }))

function changeAxis(axis: StatsAxis): void {
  parentName.value = null
  applyQuery({ axis, parent: null })
}

function drill(row: SalesBreakdownRowView): void {
  if (!row.key) return
  parentName.value = row.name
  applyQuery({ parent: row.key })
}

function drillUp(): void {
  parentName.value = null
  applyQuery({ parent: null })
}

function resetQuery(): void {
  parentName.value = null
  void router.replace({ query: toAdminSalesStatsRouteQuery(DEFAULT_ADMIN_SALES_STATS_QUERY) })
}

// ---------- CSV ----------
const csvBusy = ref(false)
const OBJECT_URL_REVOKE_DELAY_MS = 10_000

/** Bearer가 필요해 직링크 대신 blob → 임시 a 태그 클릭 → revokeObjectURL. 파일명은 Content-Disposition에서 추출(실패 시 기본명). */
async function downloadCsv(): Promise<void> {
  if (!canQuery.value || period.value === null || csvBusy.value) return
  csvBusy.value = true
  try {
    const { blob, fileName } = await statsApi.breakdownCsv(toBreakdownApiParams(query.value, period.value))
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
    toast.danger(`CSV 내보내기 실패: ${toAdminErrorMessage(error)}`)
  } finally {
    csvBusy.value = false
  }
}
</script>

<template>
  <div data-testid="admin-sales-stats">
    <AdminPageHeader title="매출 통계" description="기간·집계 단위·비교 기간을 바꿔 매출 추이를 보고, 카테고리·셀러·상품으로 분해합니다.">
      <template #actions>
        <v-btn variant="text" size="small" data-testid="sales-reset" @click="resetQuery">초기화</v-btn>
        <v-btn variant="text" :prepend-icon="mdiRefresh" :loading="statsLoading || breakdownLoading" data-testid="sales-refresh" @click="reload">새로고침</v-btn>
      </template>
    </AdminPageHeader>

    <AdminPeriodPicker
      :preset="query.preset"
      :from="period?.from ?? query.from"
      :to="period?.to ?? query.to"
      :unit="query.unit"
      :compare="query.compare"
      :inverted="periodInverted"
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

    <AdminSalesSummaryCards :summary="stats?.summary ?? null" :compare-summary="stats?.compareSummary ?? null" :compare="query.compare" />

    <v-card class="mb-4" data-testid="sales-chart">
      <v-card-text class="pa-5">
        <div class="d-flex align-center justify-space-between mb-2">
          <p class="text-subtitle-1 font-weight-bold mb-0">매출 추이</p>
          <span v-if="trendEmpty" class="text-caption text-medium-emphasis" data-testid="sales-chart-empty">데이터 없음</span>
        </div>
        <AdminChart type="line" :series="trendChart.series" :options="trendChart.options" :height="320" />
      </v-card-text>
    </v-card>

    <v-card data-testid="sales-breakdown">
      <div class="d-flex align-center justify-space-between flex-wrap ga-2 pt-4 px-5">
        <v-card-title class="text-subtitle-2 font-weight-bold pa-0">분해</v-card-title>
        <v-btn size="small" variant="outlined" color="primary" :prepend-icon="mdiDownload" :loading="csvBusy" :disabled="!canQuery || csvBusy" data-testid="sales-csv" @click="downloadCsv">CSV 내보내기</v-btn>
      </div>
      <v-tabs :model-value="query.axis" color="primary" density="comfortable" class="px-2" data-testid="breakdown-axis-tabs" @update:model-value="(value) => changeAxis(value as StatsAxis)">
        <v-tab v-for="tab in axisTabs" :key="tab.value" :value="tab.value" :data-testid="`breakdown-axis-${tab.value}`">{{ tab.title }}</v-tab>
      </v-tabs>
      <p v-if="query.axis === 'CATEGORY'" class="text-caption text-medium-emphasis px-5 pt-2 mb-0" data-testid="breakdown-category-notice">
        <v-icon :icon="mdiInformationOutline" size="14" class="mr-1" />{{ CATEGORY_AXIS_NOTICE }}
      </p>
      <div v-if="query.parent" class="d-flex align-center flex-wrap ga-2 px-5 pt-3" data-testid="breakdown-breadcrumb">
        <v-btn size="small" variant="text" color="primary" :prepend-icon="mdiArrowLeft" data-testid="breakdown-drill-up" @click="drillUp">전체로</v-btn>
        <span class="text-body-2 text-medium-emphasis">전체</span>
        <span class="text-body-2 text-medium-emphasis">›</span>
        <span class="text-body-2 font-weight-medium" data-testid="breakdown-breadcrumb-parent">{{ breadcrumbLabel(query.axis, query.parent, parentName) }}</span>
        <span class="text-caption text-medium-emphasis">— 상품별</span>
      </div>
      <AdminSalesBreakdownTable
        :rows="rows"
        :show-compare="compareShown"
        :loading="breakdownLoading"
        :load-error="breakdownError"
        @drill="drill"
        @retry="loadBreakdown"
      />
    </v-card>
  </div>
</template>
