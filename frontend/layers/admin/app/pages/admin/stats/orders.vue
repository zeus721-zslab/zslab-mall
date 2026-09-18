<script setup lang="ts">
import { mdiAlertCircleOutline, mdiCashRefund, mdiClipboardAlertOutline, mdiInformationOutline, mdiPercentOutline, mdiRefresh } from '@mdi/js'
import {
  DEFAULT_ADMIN_STATS_PERIOD_QUERY,
  parseAdminStatsPeriodQuery,
  resolveStatsPeriod,
  toAdminStatsPeriodRouteQuery,
  toStatsApiParams,
  type AdminStatsPeriodQuery,
} from '#layers/admin/app/lib/admin-stats-period-query'
import { isPeriodInverted } from '#layers/admin/app/lib/admin-sales-stats-query'
import { showsCompare } from '#layers/admin/app/lib/admin-sales-stats-view'
import {
  claimReasonRows,
  claimSummaryCards,
  claimTrendChart,
  claimTypeRows,
  isClaimTrendEmpty,
  normalizeOrderStats,
  type NormalizedOrderStats,
} from '#layers/admin/app/lib/admin-order-stats-view'
import { toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminOrderStats } from '#layers/admin/app/composables/useAdminOrderStats'

definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '주문·클레임 통계 · zslab-mall 관리자' })

// 주문·클레임 통계(FE-35·D-182). URL query(preset·from·to·unit·compare)가 단일 소스(매출 통계와 같은 흐름): 조작 → router.replace → watch → 조회.
// 응답이 하나라 요청 키가 바뀔 때만 1회 조회한다. 기간 역전·미입력이면 요청하지 않는다.
const route = useRoute()
const router = useRouter()
const statsApi = useAdminOrderStats()

const query = computed<AdminStatsPeriodQuery>(() => parseAdminStatsPeriodQuery(route.query))
const period = computed(() => resolveStatsPeriod(query.value, new Date()))
const periodInverted = computed(() => isPeriodInverted(period.value))
const canQuery = computed(() => period.value !== null && !periodInverted.value)

let pendingQuery: AdminStatsPeriodQuery | null = null
function applyQuery(patch: Partial<AdminStatsPeriodQuery>): void {
  const base = pendingQuery ?? query.value
  const next: AdminStatsPeriodQuery = { ...base, ...patch }
  if (next.preset === 'custom' && base.preset !== 'custom') {
    if (!('from' in patch)) next.from = period.value?.from ?? null
    if (!('to' in patch)) next.to = period.value?.to ?? null
  }
  pendingQuery = next
  void router.replace({ query: toAdminStatsPeriodRouteQuery(next) })
}

const stats = ref<NormalizedOrderStats | null>(null)
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
    const response = await statsApi.orders(toStatsApiParams(query.value, period.value))
    if (current !== sequence) return
    stats.value = normalizeOrderStats(response)
  } catch (error) {
    if (current !== sequence) return
    loadError.value = toAdminErrorMessage(error)
  } finally {
    if (current === sequence) loading.value = false
  }
}

const requestKey = computed(() => JSON.stringify([period.value, query.value.unit, query.value.compare]))
watch(requestKey, () => { pendingQuery = null; void load() }, { immediate: true })

function resetQuery(): void {
  void router.replace({ query: toAdminStatsPeriodRouteQuery(DEFAULT_ADMIN_STATS_PERIOD_QUERY) })
}

// ---------- 표시 ----------
const summaryCards = computed(() => claimSummaryCards(stats.value?.claimSummary ?? null, stats.value?.compareClaimSummary ?? null))
const SUMMARY_ICONS = { claimCount: mdiClipboardAlertOutline, claimRate: mdiPercentOutline, refundAmount: mdiCashRefund, refundRate: mdiPercentOutline }
const SUMMARY_COLORS = { claimCount: 'warning', claimRate: 'warning', refundAmount: 'error', refundRate: 'error' } as const
const trendChart = computed(() => claimTrendChart(stats.value?.claimTrend ?? [], stats.value?.compareClaimTrend ?? null))
const trendEmpty = computed(() => stats.value !== null && isClaimTrendEmpty(stats.value.claimTrend))
const compareShown = computed(() => showsCompare(query.value.compare))
const compareUnavailable = computed(() => compareShown.value && stats.value !== null && stats.value.compareClaimSummary === null)
const typeRows = computed(() => claimTypeRows(stats.value?.claimByType ?? []))
const reasonRows = computed(() => claimReasonRows(stats.value?.claimByReason ?? []))
</script>

<template>
  <div data-testid="admin-order-stats">
    <AdminPageHeader title="주문·클레임 통계" description="결제 코호트 퍼널·처리 소요시간·클레임률/환불률 추이와 클레임 유형·사유 분포를 봅니다.">
      <template #actions>
        <v-btn variant="text" size="small" data-testid="orders-reset" @click="resetQuery">초기화</v-btn>
        <v-btn variant="text" :prepend-icon="mdiRefresh" :loading="loading" data-testid="orders-refresh" @click="load">새로고침</v-btn>
      </template>
    </AdminPageHeader>

    <AdminStatsTabs />

    <AdminPeriodPicker
      :preset="query.preset"
      :from="period?.from ?? query.from"
      :to="period?.to ?? query.to"
      :unit="query.unit"
      :compare="query.compare"
      :inverted="periodInverted"
      @apply="applyQuery"
    />

    <v-alert v-if="loadError" type="error" class="mb-4" :icon="mdiAlertCircleOutline" data-testid="orders-error">
      <div class="d-flex align-center justify-space-between flex-wrap ga-2">
        <span>{{ loadError }}</span>
        <v-btn size="small" variant="outlined" color="error" data-testid="orders-retry" @click="load">다시 시도</v-btn>
      </div>
    </v-alert>

    <v-progress-linear v-if="loading && !stats" indeterminate color="primary" class="mb-4" data-testid="orders-loading" />

    <AdminOrderFunnel :funnel="stats?.funnel ?? null" />

    <AdminLeadTimeCards :lead-time="stats?.leadTime ?? null" />

    <v-alert v-if="compareUnavailable" type="info" variant="tonal" density="compact" class="mb-4" :icon="mdiInformationOutline" data-testid="orders-compare-unavailable">
      비교 기간에 클레임·환불·결제 데이터가 없어 증감률을 표시하지 않습니다.
    </v-alert>

    <AdminStatsSummaryCards :cards="summaryCards" :icons="SUMMARY_ICONS" :colors="SUMMARY_COLORS" :compare="query.compare" testid-prefix="claim" :lg="3" />

    <v-card class="mb-4" data-testid="claim-trend-chart">
      <v-card-text class="pa-5">
        <div class="d-flex align-center justify-space-between mb-2">
          <p class="text-subtitle-1 font-weight-bold mb-0">클레임률·환불률 추이</p>
          <span v-if="trendEmpty" class="text-caption text-medium-emphasis" data-testid="claim-trend-empty">데이터 없음</span>
        </div>
        <p class="text-caption text-medium-emphasis mb-2">클레임률 = 요청 건수 ÷ 결제 품목 수 · 환불률 = 완료 환불액 ÷ 매출(금액 기준)</p>
        <AdminChart type="line" :series="trendChart.series" :options="trendChart.options" :height="320" />
      </v-card-text>
    </v-card>

    <v-row dense class="mb-4">
      <v-col cols="12" lg="6">
        <AdminDonutCard title="클레임 유형" :rows="typeRows" unit="건" testid="claim-by-type" />
      </v-col>
      <v-col cols="12" lg="6">
        <AdminDonutCard title="클레임 사유" :rows="reasonRows" unit="건" testid="claim-by-reason" />
      </v-col>
    </v-row>
  </div>
</template>
