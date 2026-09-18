<script setup lang="ts">
import {
  mdiAccountArrowRightOutline,
  mdiAccountCheckOutline,
  mdiAccountGroupOutline,
  mdiAccountMultiplePlusOutline,
  mdiAccountPlusOutline,
  mdiAlertCircleOutline,
  mdiInformationOutline,
  mdiRefresh,
  mdiRepeat,
} from '@mdi/js'
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
  GRADE_NOTICE,
  gradeRows,
  isSignupTrendEmpty,
  memberSummaryCards,
  normalizeMemberStats,
  signupTrendChart,
  type NormalizedMemberStats,
} from '#layers/admin/app/lib/admin-member-stats-view'
import { toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminMemberStats } from '#layers/admin/app/composables/useAdminMemberStats'
import { formatWon } from '#layers/admin/app/lib/format'

definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '회원 통계 · zslab-mall 관리자' })

// 회원 통계(FE-35·D-182). URL query(preset·from·to·unit·compare)가 단일 소스(주문·매출 통계와 같은 흐름). 응답이 하나라 요청 키가 바뀔 때만 1회 조회.
const route = useRoute()
const router = useRouter()
const statsApi = useAdminMemberStats()

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

const stats = ref<NormalizedMemberStats | null>(null)
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
    const response = await statsApi.members(toStatsApiParams(query.value, period.value))
    if (current !== sequence) return
    stats.value = normalizeMemberStats(response)
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
const SHARE_FRACTION_DIGITS = 2
const summaryCards = computed(() => memberSummaryCards(stats.value?.summary ?? null, stats.value?.compareSummary ?? null))
const SUMMARY_ICONS = {
  newCount: mdiAccountPlusOutline,
  withdrawnCount: mdiAccountArrowRightOutline,
  activeTotal: mdiAccountGroupOutline,
  repurchaseRate: mdiRepeat,
  buyerCount: mdiAccountCheckOutline,
  repeatBuyerCount: mdiAccountMultiplePlusOutline,
}
const SUMMARY_COLORS = {
  newCount: 'primary',
  withdrawnCount: 'warning',
  activeTotal: 'success',
  repurchaseRate: 'info',
  buyerCount: 'info',
  repeatBuyerCount: 'primary',
} as const
const signupChart = computed(() => signupTrendChart(stats.value?.signupTrend ?? []))
const signupEmpty = computed(() => stats.value !== null && isSignupTrendEmpty(stats.value.signupTrend))
const compareShown = computed(() => showsCompare(query.value.compare))
const compareUnavailable = computed(() => compareShown.value && stats.value !== null && stats.value.compareSummary === null)
const grades = computed(() => gradeRows(stats.value?.gradeDistribution ?? []))
</script>

<template>
  <div data-testid="admin-member-stats">
    <AdminPageHeader title="회원 통계" description="신규 가입·활성 누적·등급 분포·재구매와 구매 상위 회원을 봅니다.">
      <template #actions>
        <v-btn variant="text" size="small" data-testid="members-reset" @click="resetQuery">초기화</v-btn>
        <v-btn variant="text" :prepend-icon="mdiRefresh" :loading="loading" data-testid="members-refresh" @click="load">새로고침</v-btn>
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

    <v-alert v-if="loadError" type="error" class="mb-4" :icon="mdiAlertCircleOutline" data-testid="members-error">
      <div class="d-flex align-center justify-space-between flex-wrap ga-2">
        <span>{{ loadError }}</span>
        <v-btn size="small" variant="outlined" color="error" data-testid="members-retry" @click="load">다시 시도</v-btn>
      </div>
    </v-alert>

    <v-progress-linear v-if="loading && !stats" indeterminate color="primary" class="mb-4" data-testid="members-loading" />

    <v-alert v-if="compareUnavailable" type="info" variant="tonal" density="compact" class="mb-4" :icon="mdiInformationOutline" data-testid="members-compare-unavailable">
      비교 기간에 가입·탈퇴·구매 데이터가 없어 증감률을 표시하지 않습니다.
    </v-alert>

    <AdminStatsSummaryCards :cards="summaryCards" :icons="SUMMARY_ICONS" :colors="SUMMARY_COLORS" :compare="query.compare" testid-prefix="member" />

    <v-card class="mb-4" data-testid="signup-chart">
      <v-card-text class="pa-5">
        <div class="d-flex align-center justify-space-between mb-2">
          <p class="text-subtitle-1 font-weight-bold mb-0">가입 추이</p>
          <span v-if="signupEmpty" class="text-caption text-medium-emphasis" data-testid="signup-chart-empty">데이터 없음</span>
        </div>
        <p class="text-caption text-medium-emphasis mb-2">막대 = 구간 신규 가입 · 선 = 구간 종료 시점 활성 회원(가입 누계 − 탈퇴 누계)</p>
        <AdminChart type="line" :series="signupChart.series" :options="signupChart.options" :height="320" />
      </v-card-text>
    </v-card>

    <v-row dense class="mb-4">
      <v-col cols="12" lg="7">
        <AdminDonutCard title="등급 분포" :rows="grades" unit="명" :notice="GRADE_NOTICE" testid="grade-distribution" :chart-cols="4">
          <v-table density="compact" class="adm-table adm-table--compact" data-testid="grade-distribution-table">
            <thead>
              <tr>
                <th class="text-start adm-nowrap">등급</th>
                <th class="text-end adm-nowrap">인원</th>
                <th class="text-end adm-nowrap">비중</th>
                <th class="text-end adm-nowrap">매출</th>
                <th class="text-end adm-nowrap">매출 비중</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="grades.length === 0">
                <td colspan="5" class="text-center text-medium-emphasis py-4">데이터 없음</td>
              </tr>
              <tr v-for="row in grades" :key="row.key" data-testid="grade-distribution-row">
                <td class="text-body-2 adm-nowrap">{{ row.label }}</td>
                <td class="text-end text-body-2 adm-nowrap">{{ row.count.toLocaleString('ko-KR') }}명</td>
                <td class="text-end text-body-2 adm-nowrap">{{ row.share.toFixed(SHARE_FRACTION_DIGITS) }}%</td>
                <td class="text-end text-body-2 adm-nowrap">{{ formatWon(row.revenue) }}</td>
                <td class="text-end text-body-2 adm-nowrap">{{ row.revenueShare.toFixed(SHARE_FRACTION_DIGITS) }}%</td>
              </tr>
            </tbody>
          </v-table>
        </AdminDonutCard>
      </v-col>
      <v-col cols="12" lg="5">
        <AdminBuyerSplitCard :split="stats?.buyerSplit ?? null" />
      </v-col>
    </v-row>

    <AdminTopBuyersTable :rows="stats?.topBuyers ?? []" />
  </div>
</template>
