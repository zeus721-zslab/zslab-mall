import type { ApexOptions } from 'apexcharts'
import type { ClaimSummary, ClaimTrendBucket, LeadTimeMetric } from '~/types/stats'
import type { SellerClaimProductShare, SellerOrderFunnel, SellerOrderLeadTime, SellerOrderStatsResponse } from '#layers/seller/app/types/seller-order-stats'
import { formatCount, formatWon } from '#layers/seller/app/lib/format'
import type { SellerChartSeries } from '#layers/seller/app/lib/seller-dashboard-view'
import { changeChipClass, type SellerStatsSummaryCardView } from '#layers/seller/app/lib/seller-stats-view'
import {
  axisLabel,
  changeRate,
  compareRefundRateSeries,
  formatChangeRate,
  formatHours,
  formatPercent,
  LEAD_TIME_EMPTY,
  percentOf,
  salesChangeTone,
} from '~/lib/stats-view'

/**
 * 셀러 주문·클레임 통계 표시 규칙(Track 90-E-2·관리자 admin-order-stats-view 복제·순수 함수 부분은 공용 ~/lib/stats-view 사용). 레이어에 묶이는 것
 * (slr-chip 배지·셀러 테마 차트/도넛 색·퍼널 3단계·소요시간 2종·카드 정의·상품별 표 행)만 여기 둔다.
 */

// ---------- 정규화 ----------

export interface NormalizedSellerOrderStats {
  funnel: SellerOrderFunnel
  leadTime: { paidToShipped: LeadTimeMetric | null; shippedToDelivered: LeadTimeMetric | null }
  claimSummary: ClaimSummary
  compareClaimSummary: ClaimSummary | null
  claimTrend: ClaimTrendBucket[]
  compareClaimTrend: ClaimTrendBucket[] | null
  claimByType: SellerOrderStatsResponse['claimByType']
  claimByReason: SellerOrderStatsResponse['claimByReason']
  claimByProduct: SellerClaimProductShare[]
}

/** BE NON_NULL 직렬화로 생략된 비교·소요시간 필드를 null로 고정한다. */
export function normalizeSellerOrderStats(response: SellerOrderStatsResponse): NormalizedSellerOrderStats {
  const leadTime: SellerOrderLeadTime = response.leadTime ?? {}
  return {
    funnel: response.funnel,
    leadTime: { paidToShipped: leadTime.paidToShipped ?? null, shippedToDelivered: leadTime.shippedToDelivered ?? null },
    claimSummary: response.claimSummary,
    compareClaimSummary: response.compareClaimSummary ?? null,
    claimTrend: response.claimTrend,
    compareClaimTrend: response.compareClaimTrend ?? null,
    claimByType: response.claimByType,
    claimByReason: response.claimByReason,
    claimByProduct: response.claimByProduct,
  }
}

// ---------- 퍼널(3단계) ----------

export type SellerFunnelStageKey = keyof SellerOrderFunnel

export const SELLER_FUNNEL_STAGES: { key: SellerFunnelStageKey; label: string }[] = [
  { key: 'paidItems', label: '결제' },
  { key: 'shippedItems', label: '발송' },
  { key: 'deliveredItems', label: '배송완료' },
]

export interface SellerFunnelStageView {
  key: SellerFunnelStageKey
  label: string
  count: number
  /** 첫 단계(결제) 대비 도달률 %. 결제 0이면 0(첫 단계는 100). */
  reachRate: number
  /** 직전 단계 대비 이탈률 %. 첫 단계·직전 0이면 null. */
  dropRate: number | null
}

const FULL_PERCENT = 100

/** 3단계 도달률(결제 대비)·이탈률(직전 대비). 비율은 FE 계산(D-200·BE는 건수만). */
export function sellerFunnelStages(funnel: SellerOrderFunnel | null): SellerFunnelStageView[] {
  return SELLER_FUNNEL_STAGES.map((stage, index) => {
    const count = funnel ? funnel[stage.key] : 0
    const first = funnel ? funnel.paidItems : 0
    const previous = index === 0 || !funnel ? null : funnel[SELLER_FUNNEL_STAGES[index - 1]!.key]
    return {
      key: stage.key,
      label: stage.label,
      count,
      reachRate: index === 0 && first > 0 ? FULL_PERCENT : percentOf(count, first),
      dropRate: previous === null || previous === 0 ? null : percentOf(previous - count, previous),
    }
  })
}

// ---------- 소요시간(2종) ----------

export type SellerLeadTimeKey = keyof NormalizedSellerOrderStats['leadTime']

export const SELLER_LEAD_TIME_LABELS: Record<SellerLeadTimeKey, string> = {
  paidToShipped: '결제 → 발송',
  shippedToDelivered: '발송 → 배송완료',
}

export interface SellerLeadTimeCardView {
  key: SellerLeadTimeKey
  label: string
  /** 표본 0이면 "데이터 없음". */
  median: string
  average: string
  caption: string
  empty: boolean
}

/** 카드 값은 중앙값(D-200 "소요시간 중앙값")·캡션에 평균·표본 수. */
export function sellerLeadTimeCards(leadTime: NormalizedSellerOrderStats['leadTime'] | null): SellerLeadTimeCardView[] {
  return (Object.keys(SELLER_LEAD_TIME_LABELS) as SellerLeadTimeKey[]).map((key) => {
    const metric = leadTime ? leadTime[key] : null
    if (!metric) return { key, label: SELLER_LEAD_TIME_LABELS[key], median: LEAD_TIME_EMPTY, average: '—', caption: '표본 0건', empty: true }
    return {
      key,
      label: SELLER_LEAD_TIME_LABELS[key],
      median: formatHours(metric.medianHours),
      average: formatHours(metric.avgHours),
      caption: `표본 ${metric.count.toLocaleString('ko-KR')}건 · 종결 시각 기준`,
      empty: false,
    }
  })
}

// ---------- 클레임 요약 카드 ----------

export type SellerClaimSummaryKey = 'claimCount' | 'claimRate' | 'refundAmount' | 'refundRate'

/** 클레임 요약 4장(건수·클레임률·환불액·환불률·전부 증가가 부정이라 톤 반전). 비교 없으면 배지 "—". */
export function sellerClaimSummaryCards(summary: ClaimSummary | null, compareSummary: ClaimSummary | null): SellerStatsSummaryCardView[] {
  const specs: { key: SellerClaimSummaryKey; label: string; format: (value: number) => string }[] = [
    { key: 'claimCount', label: '클레임 건수', format: (value) => formatCount(value) },
    { key: 'claimRate', label: '클레임률', format: formatPercent },
    { key: 'refundAmount', label: '환불액', format: formatWon },
    { key: 'refundRate', label: '환불률', format: formatPercent },
  ]
  return specs.map((spec) => {
    const current = summary ? summary[spec.key] : null
    const previous = compareSummary ? compareSummary[spec.key] : null
    const rate = current !== null && previous !== null ? changeRate(current, previous) : null
    return {
      key: spec.key,
      label: spec.label,
      value: current === null ? '—' : spec.format(current),
      compareValue: previous === null ? null : spec.format(previous),
      rateText: formatChangeRate(rate),
      rateClass: changeChipClass(salesChangeTone(rate, true)),
    }
  })
}

// ---------- 추이 차트 ----------

/** 셀러 테마: 클레임률 warning 주황·환불률 primary teal·비교 회색. */
const CHART_COLOR_CLAIM = '#F97316'
const CHART_COLOR_REFUND = '#0D9488'
const CHART_COLOR_COMPARE = '#94A3B8'
const CHART_FONT_FAMILY = 'inherit'
const DASH_SOLID = 0
const DASH_COMPARE = 5
const LINE_WIDTH = 2
const MAX_X_TICKS = 12
const AXIS_RATE_FRACTION_DIGITS = 1

export interface SellerOrderChartSpec {
  series: SellerChartSeries
  options: ApexOptions
}

/** 클레임률·환불률(금액) 2계열 line + (비교 시) 비교 환불률 점선. y축 %·툴팁에 건수·금액 병기. */
export function sellerClaimTrendChart(trend: ClaimTrendBucket[], compareTrend: ClaimTrendBucket[] | null): SellerOrderChartSpec {
  const categories = trend.map((bucket) => bucket.bucketLabel)
  const compareRefund = compareRefundRateSeries(compareTrend, trend.length)
  const series: SellerChartSeries = [
    { name: '클레임률', data: trend.map((bucket) => bucket.claimRate) },
    { name: '환불률', data: trend.map((bucket) => bucket.refundRate) },
  ]
  const colors = [CHART_COLOR_CLAIM, CHART_COLOR_REFUND]
  const dashArray = [DASH_SOLID, DASH_SOLID]
  if (compareRefund !== null) {
    series.push({ name: '비교 환불률', data: compareRefund })
    colors.push(CHART_COLOR_COMPARE)
    dashArray.push(DASH_COMPARE)
  }
  const detailOf = (index: number): string => {
    const bucket = trend[index]
    if (!bucket) return ''
    return ` (클레임 ${formatCount(bucket.claimCount)} · 환불 ${formatCount(bucket.refundCount)} ${formatWon(bucket.refundAmount)})`
  }
  return {
    series,
    options: {
      chart: { toolbar: { show: false }, zoom: { enabled: false }, fontFamily: CHART_FONT_FAMILY, animations: { enabled: false } },
      colors,
      dataLabels: { enabled: false },
      stroke: { curve: 'straight', width: LINE_WIDTH, dashArray },
      grid: { borderColor: '#E4E4E7', strokeDashArray: 4 },
      xaxis: {
        categories,
        tickAmount: Math.min(categories.length, MAX_X_TICKS),
        axisBorder: { show: false },
        axisTicks: { show: false },
        labels: { style: { colors: '#71717A' }, rotate: 0, hideOverlappingLabels: true, formatter: axisLabel },
      },
      yaxis: { labels: { formatter: (value: number) => `${value.toFixed(AXIS_RATE_FRACTION_DIGITS)}%`, style: { colors: '#71717A' } }, min: 0, forceNiceScale: true },
      legend: { position: 'top', horizontalAlign: 'left' },
      tooltip: {
        shared: true,
        intersect: false,
        x: { formatter: (_value: number, opts?: { dataPointIndex?: number }) => `${categories[opts?.dataPointIndex ?? 0] ?? ''}${detailOf(opts?.dataPointIndex ?? 0)}` },
        y: { formatter: (value: number | null) => (value === null ? '—' : formatPercent(value)) },
      },
    },
  }
}

// ---------- 도넛 ----------

/** 분포 도넛 색(셀러 teal 계열 우선·최대 10조각·초과는 순환). */
export const SELLER_DONUT_COLORS = ['#0D9488', '#F97316', '#0EA5E9', '#22C55E', '#A855F7', '#EAB308', '#EF4444', '#14B8A6', '#64748B', '#F43F5E']

export interface SellerDonutChartSpec {
  series: number[]
  options: ApexOptions
  empty: boolean
}

/** 도넛(건수 분포). 합이 0이면 empty(화면이 빈 문구로 대체). 툴팁은 "N건". */
export function sellerDonutChart(rows: { label: string; count: number }[], unit: string): SellerDonutChartSpec {
  const series = rows.map((row) => row.count)
  const total = series.reduce((sum, value) => sum + value, 0)
  return {
    series,
    empty: total === 0,
    options: {
      chart: { fontFamily: CHART_FONT_FAMILY, animations: { enabled: false } },
      labels: rows.map((row) => row.label),
      colors: rows.map((_row, index) => SELLER_DONUT_COLORS[index % SELLER_DONUT_COLORS.length]!),
      legend: { position: 'bottom' },
      dataLabels: { enabled: true, formatter: (value: number) => `${value.toFixed(AXIS_RATE_FRACTION_DIGITS)}%` },
      stroke: { width: 1 },
      plotOptions: { pie: { donut: { size: '62%' } } },
      tooltip: { y: { formatter: (value: number) => `${value.toLocaleString('ko-KR')}${unit}` } },
    },
  }
}

// ---------- 클레임 상품별 표 ----------

export interface SellerClaimProductRowView {
  id: string
  key: string | null
  name: string
  count: number
  share: number
  /** key 있는 행만 셀러 상품 상세로 이동한다. */
  linkable: boolean
}

export function sellerClaimProductRows(rows: SellerClaimProductShare[]): SellerClaimProductRowView[] {
  return rows.map((row, index) => {
    const key = row.productKey ?? null
    return { id: key ?? `row-${index}`, key, name: row.productName, count: row.count, share: row.share, linkable: key !== null }
  })
}
