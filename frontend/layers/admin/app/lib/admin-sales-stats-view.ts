import type { ApexOptions } from 'apexcharts'
import type {
  AdminSalesBreakdownResponse,
  AdminSalesBreakdownRow,
  AdminSalesSummary,
  AdminSalesTrendBucket,
} from '#layers/admin/app/types/admin-sales-stats'
import { DELETED_NAME_LABELS, STATS_AXIS_LABELS, type StatsAxis } from '#layers/admin/app/lib/constants/admin-sales-stats'
import { changeChipClass, type AdminChartSeries } from '#layers/admin/app/lib/admin-dashboard-view'
import { formatWon } from '#layers/admin/app/lib/format'
import {
  axisLabel,
  changeRate,
  changeTone,
  compareNetSeries,
  formatChangeRate,
  formatItemsPerOrder,
  salesChangeTone,
  type SortableBreakdownRow,
} from '~/lib/stats-view'

// 정규화·증감 반전·후행 0 절단·x축 라벨·정렬·비교 열·CSV 파일명은 셀러 통계와 공용이라 app/lib/stats-view.ts로 이동(FE-52)·기존 이름 re-export
export {
  normalizeSalesStats,
  formatItemsPerOrder,
  salesChangeTone,
  isZeroBucket,
  compareNetSeries,
  axisLabel,
  isTrendEmpty,
  sortBreakdownRows,
  showsCompare,
  csvFileNameFrom,
} from '~/lib/stats-view'
export type { NormalizedSalesStats, BreakdownSortKey } from '~/lib/stats-view'

/**
 * 매출 통계 표시 규칙 순수 함수(FE-34·admin-dashboard-view 패턴). 응답 정규화(NON_NULL 생략 → null)·요약 카드·증감 톤(환불 반전)·
 * 추이 차트(3계열 + 비교 순매출 점선·후행 0 → null)·분해 행(이름 대체·증감률)·CSV 파일명 추출을 여기 모아 vitest로 고정한다.
 */

// ---------- 요약 카드 ----------

export type SalesSummaryKey = keyof AdminSalesSummary

export interface SalesSummaryCardSpec {
  key: SalesSummaryKey
  label: string
  format: (value: number) => string
  /** true면 증가가 부정(환불) — 톤 색을 반전한다. */
  inverse: boolean
}

export function formatCount(value: number): string {
  return `${value.toLocaleString('ko-KR')}건`
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

// ---------- 분해 테이블 ----------

export interface SalesBreakdownRowView extends SortableBreakdownRow {
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

/** 브레드크럼 상위 라벨: 축 라벨 + 이름(모르면 key). */
export function breadcrumbLabel(axis: StatsAxis, parentKey: string, parentName: string | null): string {
  return `${STATS_AXIS_LABELS[axis]} · ${parentName ?? parentKey}`
}
