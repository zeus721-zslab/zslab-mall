import type { ApexOptions } from 'apexcharts'
import type {
  AdminSalesBreakdownResponse,
  AdminSalesBreakdownRow,
  AdminSalesStatsResponse,
  AdminSalesSummary,
  AdminSalesTrendBucket,
} from '#layers/admin/app/types/admin-sales-stats'
import { DELETED_NAME_LABELS, STATS_AXIS_LABELS, type StatsAxis, type StatsCompare } from '#layers/admin/app/lib/constants/admin-sales-stats'
import { changeChipClass, changeRate, changeTone, formatChangeRate, type AdminChartSeries, type ChangeTone } from '#layers/admin/app/lib/admin-dashboard-view'
import { formatWon } from '#layers/admin/app/lib/format'

/**
 * 매출 통계 표시 규칙 순수 함수(FE-34·admin-dashboard-view 패턴). 응답 정규화(NON_NULL 생략 → null)·요약 카드·증감 톤(환불 반전)·
 * 추이 차트(3계열 + 비교 순매출 점선·후행 0 → null)·분해 행(이름 대체·증감률)·CSV 파일명 추출을 여기 모아 vitest로 고정한다.
 */

// ---------- 정규화 ----------

export interface NormalizedSalesStats {
  summary: AdminSalesSummary
  compareSummary: AdminSalesSummary | null
  trend: AdminSalesTrendBucket[]
  compareTrend: AdminSalesTrendBucket[] | null
}

/** BE NON_NULL 직렬화로 생략된 비교 필드를 null로 고정한다. */
export function normalizeSalesStats(response: AdminSalesStatsResponse): NormalizedSalesStats {
  return {
    summary: response.summary,
    compareSummary: response.compareSummary ?? null,
    trend: response.trend,
    compareTrend: response.compareTrend ?? null,
  }
}

// ---------- 요약 카드 ----------

export type SalesSummaryKey = keyof AdminSalesSummary

export interface SalesSummaryCardSpec {
  key: SalesSummaryKey
  label: string
  format: (value: number) => string
  /** true면 증가가 부정(환불) — 톤 색을 반전한다. */
  inverse: boolean
}

const ITEMS_PER_ORDER_FRACTION_DIGITS = 2

export function formatCount(value: number): string {
  return `${value.toLocaleString('ko-KR')}건`
}

export function formatItemsPerOrder(value: number): string {
  return `${value.toFixed(ITEMS_PER_ORDER_FRACTION_DIGITS)}개`
}

/** 카드 6장 정의·순서. 환불만 inverse. */
export const SALES_SUMMARY_CARDS: SalesSummaryCardSpec[] = [
  { key: 'revenue', label: '매출', format: formatWon, inverse: false },
  { key: 'refund', label: '환불', format: formatWon, inverse: true },
  { key: 'netRevenue', label: '순매출', format: formatWon, inverse: false },
  { key: 'orderCount', label: '주문수', format: formatCount, inverse: false },
  { key: 'avgOrderValue', label: '객단가', format: formatWon, inverse: false },
  { key: 'avgItemsPerOrder', label: '주문당 품목수', format: formatItemsPerOrder, inverse: false },
]

/** 증감 톤. inverse(환불)면 up↔down을 바꿔 "환불 증가 = 빨강"이 되게 한다. flat은 그대로. */
export function salesChangeTone(rate: number | null, inverse: boolean): ChangeTone {
  const tone = changeTone(rate)
  if (!inverse || tone === 'flat') return tone
  return tone === 'up' ? 'down' : 'up'
}

export interface SalesSummaryCardView {
  key: SalesSummaryKey
  label: string
  value: string
  compareValue: string | null
  rateText: string
  rateClass: string
}

/** 카드 뷰 6장. 비교 없음(compareSummary null)이면 배지 "—"·회색·비교값 없음. */
export function salesSummaryCards(summary: AdminSalesSummary | null, compareSummary: AdminSalesSummary | null): SalesSummaryCardView[] {
  return SALES_SUMMARY_CARDS.map((card) => {
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

// ---------- 추이 차트 ----------

const CHART_COLOR_PRIMARY = '#2563EB'
const CHART_COLOR_WARNING = '#F97316'
const CHART_COLOR_SUCCESS = '#22C55E'
const CHART_COLOR_COMPARE = '#94A3B8'
const CHART_FONT_FAMILY = 'inherit'
const DASH_SOLID = 0
const DASH_COMPARE = 5
const LINE_WIDTH = 2
const MAX_X_TICKS = 12

/** 구간이 모두 0(매출·환불·주문 없음)인지 — 후행 0 판정과 빈 상태 판정에 같이 쓴다. */
export function isZeroBucket(bucket: AdminSalesTrendBucket): boolean {
  return bucket.revenue === 0 && bucket.refund === 0 && bucket.orderCount === 0
}

/**
 * 비교 추이의 순매출 계열. BE는 compareTrend를 trend 길이에 맞춰 뒤를 0으로 채우거나 절단하므로(D-181 §1-A 4), 실제 비교 구간이
 * 더 적을 때 끝이 0으로 급락해 보인다 → <b>후행</b> 0 구간만 null로 바꿔 선을 끊는다(중간 0은 실제 0이라 유지). 비교 없으면 null.
 */
export function compareNetSeries(compareTrend: AdminSalesTrendBucket[] | null, length: number): (number | null)[] | null {
  if (compareTrend === null) return null
  const trimmed = compareTrend.slice(0, length)
  const values: (number | null)[] = trimmed.map((bucket) => bucket.netRevenue)
  for (let index = trimmed.length - 1; index >= 0 && isZeroBucket(trimmed[index]!); index--) {
    values[index] = null
  }
  while (values.length < length) values.push(null)
  return values
}

export interface SalesTrendChartSpec {
  series: AdminChartSeries
  options: ApexOptions
}

const COMPARE_SERIES_NAME = '비교 순매출'

/**
 * 매출·환불·순매출 3계열 line + (비교 시) 비교 순매출 1계열 점선. 6계열은 과밀이라 비교는 순매출만 겹치고(FE-34 §1-A α) 매출·환불 비교값은
 * 툴팁에 병기한다. x축은 bucketLabel(주는 월요일 날짜). y축·툴팁은 원 단위 콤마.
 */
export function salesTrendChart(trend: AdminSalesTrendBucket[], compareTrend: AdminSalesTrendBucket[] | null): SalesTrendChartSpec {
  const categories = trend.map((bucket) => bucket.bucketLabel)
  const compareNet = compareNetSeries(compareTrend, trend.length)
  const series: AdminChartSeries = [
    { name: '매출', data: trend.map((bucket) => bucket.revenue) },
    { name: '환불', data: trend.map((bucket) => bucket.refund) },
    { name: '순매출', data: trend.map((bucket) => bucket.netRevenue) },
  ]
  const colors = [CHART_COLOR_PRIMARY, CHART_COLOR_WARNING, CHART_COLOR_SUCCESS]
  const dashArray = [DASH_SOLID, DASH_SOLID, DASH_SOLID]
  if (compareNet !== null) {
    series.push({ name: COMPARE_SERIES_NAME, data: compareNet })
    colors.push(CHART_COLOR_COMPARE)
    dashArray.push(DASH_COMPARE)
  }
  const compareLabelOf = (index: number): string => {
    const compare = compareTrend?.[index]
    if (!compare || compareNet?.[index] === null) return ''
    return ` (비교 매출 ${formatWon(compare.revenue)} · 환불 ${formatWon(compare.refund)} · ${compare.bucketLabel})`
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
      yaxis: { labels: { formatter: (value: number) => formatWon(value), style: { colors: '#71717A' } }, min: 0, forceNiceScale: true },
      legend: { position: 'top', horizontalAlign: 'left' },
      tooltip: {
        shared: true,
        intersect: false,
        x: { formatter: (_value: number, opts?: { dataPointIndex?: number }) => `${categories[opts?.dataPointIndex ?? 0] ?? ''}${compareLabelOf(opts?.dataPointIndex ?? 0)}` },
        y: { formatter: (value: number | null) => (value === null ? '—' : formatWon(value)) },
      },
    },
  }
}

/** x축 라벨: yyyy-MM-dd(일·주 시작일)는 연도를 떼 MM-DD로 줄인다(30일·7일 조회에서 겹침 실측). 월(yyyy-MM)은 그대로. 툴팁은 전체 라벨. */
export function axisLabel(label: string): string {
  return /^\d{4}-\d{2}-\d{2}$/.test(label) ? label.slice(5) : label
}

/** 추이 전 구간이 0인지(빈 상태 캡션). */
export function isTrendEmpty(trend: AdminSalesTrendBucket[]): boolean {
  return trend.every(isZeroBucket)
}

// ---------- 분해 테이블 ----------

export interface SalesBreakdownRowView {
  /** 행 식별(key 없으면 이름·순번으로 대체). */
  id: string
  key: string | null
  name: string
  deleted: boolean
  revenue: number
  share: number
  orderCount: number
  quantity: number
  compareRevenue: number | null
  /** 정렬용 숫자(비교 불가는 null → 맨 뒤). */
  rate: number | null
  rateText: string
  rateClass: string
  drillable: boolean
}

/** 행이 실제로 속한 축(parentKey가 있으면 상품 행). */
export function rowAxisOf(axis: StatsAxis, parentKey: string | null): StatsAxis {
  return parentKey ? 'PRODUCT' : axis
}

/** 응답 행 → 표 행. name null은 "(삭제된 …)"·key null은 드릴다운 불가. */
export function breakdownRowViews(response: AdminSalesBreakdownResponse | null): SalesBreakdownRowView[] {
  if (!response) return []
  const rowAxis = rowAxisOf(response.axis, response.parentKey ?? null)
  return response.rows.map((row: AdminSalesBreakdownRow, index) => {
    const key = row.key ?? null
    const name = row.name ?? null
    const compareRevenue = row.compareRevenue ?? null
    const rate = compareRevenue === null ? null : changeRate(row.revenue, compareRevenue)
    return {
      id: key ?? `${name ?? 'row'}-${index}`,
      key,
      name: name ?? DELETED_NAME_LABELS[rowAxis],
      deleted: name === null,
      revenue: row.revenue,
      share: row.share,
      orderCount: row.orderCount,
      quantity: row.quantity,
      compareRevenue,
      rate,
      rateText: formatChangeRate(rate),
      rateClass: changeChipClass(changeTone(rate)),
      drillable: row.drillable && key !== null,
    }
  })
}

export type BreakdownSortKey = 'name' | 'revenue' | 'share' | 'orderCount' | 'quantity' | 'compareRevenue' | 'rate'

/** 클라이언트 정렬(BE는 매출 DESC 고정). null(비교 불가)은 방향과 무관하게 맨 뒤. 동률은 매출 DESC. */
export function sortBreakdownRows(rows: SalesBreakdownRowView[], key: BreakdownSortKey, order: 'asc' | 'desc'): SalesBreakdownRowView[] {
  const direction = order === 'asc' ? 1 : -1
  return [...rows].sort((a, b) => {
    const left = a[key]
    const right = b[key]
    if (left === null && right === null) return b.revenue - a.revenue
    if (left === null) return 1
    if (right === null) return -1
    const primary = typeof left === 'string' && typeof right === 'string' ? left.localeCompare(right, 'ko-KR') : Number(left) - Number(right)
    return primary !== 0 ? primary * direction : b.revenue - a.revenue
  })
}

/** 브레드크럼 상위 라벨: 축 라벨 + 이름(모르면 key). */
export function breadcrumbLabel(axis: StatsAxis, parentKey: string, parentName: string | null): string {
  return `${STATS_AXIS_LABELS[axis]} · ${parentName ?? parentKey}`
}

/** 비교 열 표시 여부. */
export function showsCompare(compare: StatsCompare): boolean {
  return compare !== 'NONE'
}

// ---------- CSV ----------

const CSV_DEFAULT_NAME = 'sales-breakdown.csv'

/**
 * Content-Disposition에서 파일명 추출: filename*=UTF-8''(RFC 5987) 우선·없으면 filename="…"·둘 다 없으면 기본명.
 * BE는 `attachment; filename="sales-breakdown-…csv"; filename*=UTF-8''매출통계_…csv`(D-181 §1-A 10)를 보낸다.
 */
export function csvFileNameFrom(contentDisposition: string | null): string {
  if (!contentDisposition) return CSV_DEFAULT_NAME
  const extended = /filename\*=UTF-8''([^;]+)/i.exec(contentDisposition)
  if (extended?.[1]) {
    try {
      return decodeURIComponent(extended[1].trim())
    } catch {
      // 잘못된 percent-encoding이면 ASCII filename으로 폴백
    }
  }
  const plain = /filename="([^"]+)"/i.exec(contentDisposition) ?? /filename=([^;]+)/i.exec(contentDisposition)
  return plain?.[1]?.trim() || CSV_DEFAULT_NAME
}
