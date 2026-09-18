import type { ApexOptions } from 'apexcharts'
import type {
  AdminClaimReasonShare,
  AdminClaimSummary,
  AdminClaimTrendBucket,
  AdminClaimTypeShare,
  AdminLeadTimeMetric,
  AdminOrderFunnel,
  AdminOrderLeadTime,
  AdminOrderStatsResponse,
} from '#layers/admin/app/types/admin-order-stats'
import { CLAIM_REASON_LABELS, CLAIM_TYPE_LABELS, type ClaimReasonCode, type ClaimType } from '~/lib/constants/claim'
import { changeChipClass, changeRate, formatChangeRate, type AdminChartSeries } from '#layers/admin/app/lib/admin-dashboard-view'
import { salesChangeTone, axisLabel, formatCount } from '#layers/admin/app/lib/admin-sales-stats-view'
import { formatWon } from '#layers/admin/app/lib/format'

/**
 * 주문·클레임 통계 표시 규칙 순수 함수(FE-35·admin-sales-stats-view 패턴). 응답 정규화·퍼널 도달률/이탈률·소요시간 포맷(24h 경계)·
 * 클레임 요약 카드·클레임률/환불률 추이(비교 환불률 점선·후행 0 → null)·분포(유형·사유 라벨·미매핑 원문)·도넛 옵션을 여기 모아 vitest로 고정한다.
 */

// ---------- 정규화 ----------

export interface NormalizedOrderStats {
  funnel: AdminOrderFunnel
  leadTime: { paidToShipped: AdminLeadTimeMetric | null; shippedToDelivered: AdminLeadTimeMetric | null; claimRequestedToClosed: AdminLeadTimeMetric | null }
  claimSummary: AdminClaimSummary
  compareClaimSummary: AdminClaimSummary | null
  claimTrend: AdminClaimTrendBucket[]
  compareClaimTrend: AdminClaimTrendBucket[] | null
  claimByType: AdminClaimTypeShare[]
  claimByReason: AdminClaimReasonShare[]
}

/** BE NON_NULL 직렬화로 생략된 비교·소요시간 필드를 null로 고정한다. */
export function normalizeOrderStats(response: AdminOrderStatsResponse): NormalizedOrderStats {
  const leadTime: AdminOrderLeadTime = response.leadTime ?? {}
  return {
    funnel: response.funnel,
    leadTime: {
      paidToShipped: leadTime.paidToShipped ?? null,
      shippedToDelivered: leadTime.shippedToDelivered ?? null,
      claimRequestedToClosed: leadTime.claimRequestedToClosed ?? null,
    },
    claimSummary: response.claimSummary,
    compareClaimSummary: response.compareClaimSummary ?? null,
    claimTrend: response.claimTrend,
    compareClaimTrend: response.compareClaimTrend ?? null,
    claimByType: response.claimByType,
    claimByReason: response.claimByReason,
  }
}

// ---------- 퍼널 ----------

export type FunnelStageKey = 'paidItems' | 'shippedItems' | 'deliveredItems' | 'confirmedItems'

export const FUNNEL_STAGES: { key: FunnelStageKey; label: string }[] = [
  { key: 'paidItems', label: '결제' },
  { key: 'shippedItems', label: '발송' },
  { key: 'deliveredItems', label: '배송완료' },
  { key: 'confirmedItems', label: '구매확정' },
]

export interface FunnelStageView {
  key: FunnelStageKey
  label: string
  count: number
  /** 첫 단계(결제) 대비 도달률 %. 결제 0이면 0(첫 단계는 100). */
  reachRate: number
  /** 직전 단계 대비 이탈률 %. 첫 단계·직전 0이면 null. */
  dropRate: number | null
}

const PERCENT = 100
const RATE_FRACTION_DIGITS = 1

function percent(numerator: number, denominator: number): number {
  return denominator === 0 ? 0 : (numerator / denominator) * PERCENT
}

/** 4단계 도달률(결제 대비)·이탈률(직전 대비). 비율은 FE 계산(D-182·BE는 건수만). */
export function funnelStages(funnel: AdminOrderFunnel | null): FunnelStageView[] {
  return FUNNEL_STAGES.map((stage, index) => {
    const count = funnel ? funnel[stage.key] : 0
    const first = funnel ? funnel.paidItems : 0
    const previous = index === 0 || !funnel ? null : funnel[FUNNEL_STAGES[index - 1]!.key]
    return {
      key: stage.key,
      label: stage.label,
      count,
      reachRate: index === 0 && first > 0 ? PERCENT : percent(count, first),
      dropRate: previous === null || previous === 0 ? null : percent(previous - count, previous),
    }
  })
}

export function formatRate(rate: number | null): string {
  return rate === null ? '—' : `${rate.toFixed(RATE_FRACTION_DIGITS)}%`
}

// ---------- 소요시간 ----------

const HOURS_PER_DAY = 24
const HOURS_FRACTION_DIGITS = 1

/** 24h 미만은 "N.N시간", 이상은 "N일 M시간"(시간은 반올림·24시간 올림 시 일 증가). */
export function formatHours(hours: number): string {
  if (hours < HOURS_PER_DAY) return `${hours.toFixed(HOURS_FRACTION_DIGITS)}시간`
  let days = Math.floor(hours / HOURS_PER_DAY)
  let remainder = Math.round(hours - days * HOURS_PER_DAY)
  if (remainder === HOURS_PER_DAY) {
    days += 1
    remainder = 0
  }
  return remainder === 0 ? `${days}일` : `${days}일 ${remainder}시간`
}

export type LeadTimeKey = keyof NormalizedOrderStats['leadTime']

export const LEAD_TIME_LABELS: Record<LeadTimeKey, string> = {
  paidToShipped: '결제 → 발송',
  shippedToDelivered: '발송 → 배송완료',
  claimRequestedToClosed: '클레임 요청 → 종결',
}

export interface LeadTimeCardView {
  key: LeadTimeKey
  label: string
  /** 표본 0이면 "데이터 없음". */
  average: string
  median: string
  caption: string
  empty: boolean
}

export const LEAD_TIME_EMPTY = '데이터 없음'

export function leadTimeCards(leadTime: NormalizedOrderStats['leadTime'] | null): LeadTimeCardView[] {
  return (Object.keys(LEAD_TIME_LABELS) as LeadTimeKey[]).map((key) => {
    const metric = leadTime ? leadTime[key] : null
    if (!metric) return { key, label: LEAD_TIME_LABELS[key], average: LEAD_TIME_EMPTY, median: '—', caption: '표본 0건', empty: true }
    return {
      key,
      label: LEAD_TIME_LABELS[key],
      average: formatHours(metric.avgHours),
      median: formatHours(metric.medianHours),
      caption: `표본 ${metric.count.toLocaleString('ko-KR')}건 · 종결 시각 기준`,
      empty: false,
    }
  })
}

// ---------- 클레임 요약 카드 ----------

export type ClaimSummaryKey = 'claimCount' | 'claimRate' | 'refundAmount' | 'refundRate'

export interface StatsSummaryCardView {
  key: string
  label: string
  value: string
  compareValue: string | null
  rateText: string
  rateClass: string
}

export function formatPercent(value: number): string {
  return `${value.toFixed(2)}%`
}

/** 클레임 요약 4장(건수·클레임률·환불액·환불률·전부 증가가 부정이라 톤 반전). 비교 없으면 배지 "—". */
export function claimSummaryCards(summary: AdminClaimSummary | null, compareSummary: AdminClaimSummary | null): StatsSummaryCardView[] {
  const specs: { key: ClaimSummaryKey; label: string; format: (value: number) => string }[] = [
    { key: 'claimCount', label: '클레임 건수', format: formatCount },
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

const CHART_COLOR_CLAIM = '#F97316'
const CHART_COLOR_REFUND = '#2563EB'
const CHART_COLOR_COMPARE = '#94A3B8'
const CHART_FONT_FAMILY = 'inherit'
const DASH_SOLID = 0
const DASH_COMPARE = 5
const LINE_WIDTH = 2
const MAX_X_TICKS = 12

export function isZeroClaimBucket(bucket: AdminClaimTrendBucket): boolean {
  return bucket.claimCount === 0 && bucket.refundAmount === 0 && bucket.refundCount === 0
}

/** 비교 환불률 계열. BE가 뒤를 0으로 채우므로 후행 0 구간만 null로 끊는다(FE-34 compareNetSeries와 동일 규칙). */
export function compareRefundRateSeries(compareTrend: AdminClaimTrendBucket[] | null, length: number): (number | null)[] | null {
  if (compareTrend === null) return null
  const trimmed = compareTrend.slice(0, length)
  const values: (number | null)[] = trimmed.map((bucket) => bucket.refundRate)
  for (let index = trimmed.length - 1; index >= 0 && isZeroClaimBucket(trimmed[index]!); index--) {
    values[index] = null
  }
  while (values.length < length) values.push(null)
  return values
}

export interface OrderChartSpec {
  series: AdminChartSeries
  options: ApexOptions
}

/** 클레임률·환불률(금액) 2계열 line + (비교 시) 비교 환불률 점선. y축 %·툴팁에 건수·금액 병기. */
export function claimTrendChart(trend: AdminClaimTrendBucket[], compareTrend: AdminClaimTrendBucket[] | null): OrderChartSpec {
  const categories = trend.map((bucket) => bucket.bucketLabel)
  const compareRefund = compareRefundRateSeries(compareTrend, trend.length)
  const series: AdminChartSeries = [
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
      yaxis: { labels: { formatter: (value: number) => `${value.toFixed(RATE_FRACTION_DIGITS)}%`, style: { colors: '#71717A' } }, min: 0, forceNiceScale: true },
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

export function isClaimTrendEmpty(trend: AdminClaimTrendBucket[]): boolean {
  return trend.every(isZeroClaimBucket)
}

// ---------- 분포 ----------

export interface DistributionRowView {
  key: string
  label: string
  count: number
  share: number
}

/** 유형 라벨(단일 소스 claim.ts). */
export function claimTypeLabel(type: ClaimType): string {
  return CLAIM_TYPE_LABELS[type] ?? type
}

/** 사유 라벨. BE reason_code는 무제약 VARCHAR라 enum 밖 값은 원문 그대로 표기한다(D-182). */
export function claimReasonLabel(code: string): string {
  return CLAIM_REASON_LABELS[code as ClaimReasonCode] ?? code
}

export function claimTypeRows(rows: AdminClaimTypeShare[]): DistributionRowView[] {
  return rows.map((row) => ({ key: row.type, label: claimTypeLabel(row.type), count: row.count, share: row.share }))
}

export function claimReasonRows(rows: AdminClaimReasonShare[]): DistributionRowView[] {
  return rows.map((row) => ({ key: row.reasonCode, label: claimReasonLabel(row.reasonCode), count: row.count, share: row.share }))
}

// ---------- 도넛 ----------

/** 분포 도넛 색(Argon 톤·최대 10조각·초과는 순환). */
export const DONUT_COLORS = ['#2563EB', '#F97316', '#22C55E', '#0EA5E9', '#A855F7', '#EAB308', '#EF4444', '#14B8A6', '#64748B', '#F43F5E']

export interface DonutChartSpec {
  series: number[]
  options: ApexOptions
  empty: boolean
}

/** 도넛(인원·건수 분포). 합이 0이면 empty(화면이 빈 문구로 대체). 툴팁·범례는 "라벨 N건(단위)". */
export function donutChart(rows: { label: string; count: number }[], unit: string): DonutChartSpec {
  const series = rows.map((row) => row.count)
  const total = series.reduce((sum, value) => sum + value, 0)
  return {
    series,
    empty: total === 0,
    options: {
      chart: { fontFamily: CHART_FONT_FAMILY, animations: { enabled: false } },
      labels: rows.map((row) => row.label),
      colors: rows.map((_row, index) => DONUT_COLORS[index % DONUT_COLORS.length]!),
      legend: { position: 'bottom' },
      dataLabels: { enabled: true, formatter: (value: number) => `${value.toFixed(RATE_FRACTION_DIGITS)}%` },
      stroke: { width: 1 },
      plotOptions: { pie: { donut: { size: '62%' } } },
      tooltip: { y: { formatter: (value: number) => `${value.toLocaleString('ko-KR')}${unit}` } },
    },
  }
}
