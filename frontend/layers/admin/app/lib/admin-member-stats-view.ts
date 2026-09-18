import type { ApexOptions } from 'apexcharts'
import type {
  AdminBuyerSplit,
  AdminGradeDistribution,
  AdminMemberStatsResponse,
  AdminMemberSummary,
  AdminSignupTrendBucket,
  AdminTopBuyer,
} from '#layers/admin/app/types/admin-member-stats'
import { ADMIN_BUYER_GRADE_LABEL } from '#layers/admin/app/lib/constants/admin-member'
import { changeChipClass, changeRate, formatChangeRate, type AdminChartSeries } from '#layers/admin/app/lib/admin-dashboard-view'
import { axisLabel, salesChangeTone } from '#layers/admin/app/lib/admin-sales-stats-view'
import { formatPercent, type DistributionRowView, type StatsSummaryCardView } from '#layers/admin/app/lib/admin-order-stats-view'
import { formatWon } from '#layers/admin/app/lib/format'

/**
 * 회원 통계 표시 규칙 순수 함수(FE-35). 응답 정규화·요약 카드(탈퇴만 톤 반전)·가입 추이 혼합 차트(신규 막대 + 활성 누적 선·이중 y축)·
 * 등급 분포 행·구매자 분리·상위 회원 행(이름·이메일 null → "—")을 여기 모아 vitest로 고정한다.
 */

// ---------- 정규화 ----------

export interface NormalizedMemberStats {
  summary: AdminMemberSummary
  compareSummary: AdminMemberSummary | null
  signupTrend: AdminSignupTrendBucket[]
  compareSignupTrend: AdminSignupTrendBucket[] | null
  gradeDistribution: AdminGradeDistribution[]
  buyerSplit: AdminBuyerSplit
  topBuyers: AdminTopBuyer[]
}

export function normalizeMemberStats(response: AdminMemberStatsResponse): NormalizedMemberStats {
  return {
    summary: response.summary,
    compareSummary: response.compareSummary ?? null,
    signupTrend: response.signupTrend,
    compareSignupTrend: response.compareSignupTrend ?? null,
    gradeDistribution: response.gradeDistribution,
    buyerSplit: response.buyerSplit,
    topBuyers: response.topBuyers,
  }
}

// ---------- 요약 카드 ----------

export type MemberSummaryKey = keyof AdminMemberSummary

export function formatPeople(value: number): string {
  return `${value.toLocaleString('ko-KR')}명`
}

/** 카드 6장 정의·순서. 탈퇴만 inverse(증가가 부정). */
export const MEMBER_SUMMARY_CARDS: { key: MemberSummaryKey; label: string; format: (value: number) => string; inverse: boolean }[] = [
  { key: 'newCount', label: '신규 가입', format: formatPeople, inverse: false },
  { key: 'withdrawnCount', label: '탈퇴', format: formatPeople, inverse: true },
  { key: 'activeTotal', label: '활성 회원', format: formatPeople, inverse: false },
  { key: 'repurchaseRate', label: '재구매율', format: formatPercent, inverse: false },
  { key: 'buyerCount', label: '구매자 수', format: formatPeople, inverse: false },
  { key: 'repeatBuyerCount', label: '재구매자 수', format: formatPeople, inverse: false },
]

export function memberSummaryCards(summary: AdminMemberSummary | null, compareSummary: AdminMemberSummary | null): StatsSummaryCardView[] {
  return MEMBER_SUMMARY_CARDS.map((card) => {
    const current = summary ? summary[card.key] : null
    const previous = compareSummary ? compareSummary[card.key] : null
    const rate = current !== null && previous !== null ? changeRate(current, previous) : null
    return {
      key: card.key,
      label: card.label,
      value: current === null ? '—' : card.format(current),
      compareValue: previous === null ? null : card.format(previous),
      rateText: formatChangeRate(rate),
      rateClass: changeChipClass(salesChangeTone(rate, card.inverse)),
    }
  })
}

// ---------- 가입 추이(혼합·이중 축) ----------

const CHART_COLOR_NEW = '#2563EB'
const CHART_COLOR_ACTIVE = '#22C55E'
const CHART_FONT_FAMILY = 'inherit'
const MAX_X_TICKS = 12
const LINE_WIDTH = 2
const NEW_SERIES_NAME = '신규 가입'
const ACTIVE_SERIES_NAME = '활성 누적'

export interface SignupChartSpec {
  series: AdminChartSeries
  options: ApexOptions
}

/** 신규 가입(막대·좌축) + 활성 누적(선·우축) 혼합. 활성 누적은 스케일이 달라 y축 2개(opposite)를 쓴다. 계열 순서 = y축 순서. */
export function signupTrendChart(trend: AdminSignupTrendBucket[]): SignupChartSpec {
  const categories = trend.map((bucket) => bucket.bucketLabel)
  return {
    series: [
      { name: NEW_SERIES_NAME, type: 'column', data: trend.map((bucket) => bucket.newCount) },
      { name: ACTIVE_SERIES_NAME, type: 'line', data: trend.map((bucket) => bucket.activeCumulative) },
    ],
    options: {
      chart: { toolbar: { show: false }, zoom: { enabled: false }, fontFamily: CHART_FONT_FAMILY, animations: { enabled: false } },
      colors: [CHART_COLOR_NEW, CHART_COLOR_ACTIVE],
      dataLabels: { enabled: false },
      stroke: { curve: 'straight', width: [0, LINE_WIDTH] },
      plotOptions: { bar: { columnWidth: '45%', borderRadius: 4 } },
      grid: { borderColor: '#E4E4E7', strokeDashArray: 4 },
      xaxis: {
        categories,
        tickAmount: Math.min(categories.length, MAX_X_TICKS),
        axisBorder: { show: false },
        axisTicks: { show: false },
        labels: { style: { colors: '#71717A' }, rotate: 0, hideOverlappingLabels: true, formatter: axisLabel },
      },
      yaxis: [
        { seriesName: NEW_SERIES_NAME, title: { text: '신규 가입(명)', style: { color: '#71717A', fontWeight: 500 } }, labels: { formatter: (value: number) => `${Math.round(value)}`, style: { colors: '#71717A' } }, min: 0, forceNiceScale: true },
        { seriesName: ACTIVE_SERIES_NAME, opposite: true, title: { text: '활성 누적(명)', style: { color: '#71717A', fontWeight: 500 } }, labels: { formatter: (value: number) => `${Math.round(value)}`, style: { colors: '#71717A' } }, min: 0, forceNiceScale: true },
      ],
      legend: { position: 'top', horizontalAlign: 'left' },
      tooltip: { shared: true, intersect: false, y: { formatter: (value: number | null) => (value === null ? '—' : formatPeople(value)) } },
    },
  }
}

export function isSignupTrendEmpty(trend: AdminSignupTrendBucket[]): boolean {
  return trend.every((bucket) => bucket.newCount === 0 && bucket.activeCumulative === 0)
}

// ---------- 등급 분포 ----------

export interface GradeRowView extends DistributionRowView {
  revenue: number
  revenueShare: number
}

export const GRADE_NOTICE = '회원의 현재 등급 기준으로 집계됩니다. 등급이 바뀌면 과거 매출의 등급 귀속도 함께 바뀝니다.'

export function gradeRows(rows: AdminGradeDistribution[]): GradeRowView[] {
  return rows.map((row) => ({
    key: row.gradeCode,
    label: ADMIN_BUYER_GRADE_LABEL[row.gradeCode] ?? row.gradeCode,
    count: row.memberCount,
    share: row.share,
    revenue: row.revenue,
    revenueShare: row.revenueShare,
  }))
}

// ---------- 신규 vs 재구매 ----------

export interface BuyerSplitView {
  firstTime: { count: number; revenue: number; revenueShare: number }
  repeat: { count: number; revenue: number; revenueShare: number }
  empty: boolean
}

const PERCENT = 100

/** 1회 구매자 vs 재구매자 인원·매출·매출 비중(합 0이면 empty·비중 0). */
export function buyerSplitView(split: AdminBuyerSplit | null): BuyerSplitView {
  const firstRevenue = split?.firstTimeRevenue ?? 0
  const repeatRevenue = split?.repeatRevenue ?? 0
  const total = firstRevenue + repeatRevenue
  const share = (value: number): number => (total === 0 ? 0 : (value / total) * PERCENT)
  return {
    firstTime: { count: split?.firstTimeBuyerCount ?? 0, revenue: firstRevenue, revenueShare: share(firstRevenue) },
    repeat: { count: split?.repeatBuyerCount ?? 0, revenue: repeatRevenue, revenueShare: share(repeatRevenue) },
    empty: (split?.firstTimeBuyerCount ?? 0) + (split?.repeatBuyerCount ?? 0) === 0,
  }
}

// ---------- 상위 회원 ----------

export const NAME_UNAVAILABLE = '—'

export interface TopBuyerRowView {
  id: string
  userPublicId: string | null
  name: string
  email: string
  orderCount: number
  revenue: string
  /** publicId가 있어야 회원 상세로 이동한다. */
  navigable: boolean
}

export function topBuyerRows(rows: AdminTopBuyer[]): TopBuyerRowView[] {
  return rows.map((row, index) => {
    const userPublicId = row.userPublicId ?? null
    return {
      id: userPublicId ?? `row-${index}`,
      userPublicId,
      name: row.name ?? NAME_UNAVAILABLE,
      email: row.email ?? NAME_UNAVAILABLE,
      orderCount: row.orderCount,
      revenue: formatWon(row.revenue),
      navigable: userPublicId !== null,
    }
  })
}
