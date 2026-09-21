import type { ApexOptions } from 'apexcharts'
import type { SellerSalesBreakdownResponse, SellerSalesBreakdownRow, SellerSalesSummary, SellerSalesTrendBucket } from '#layers/seller/app/types/seller-stats'
import { SELLER_DELETED_NAME_LABELS } from '#layers/seller/app/lib/constants/seller-stats'
import { semanticChipClass } from '#layers/seller/app/lib/constants/semantic'
import { formatCount, formatWon } from '#layers/seller/app/lib/format'
import type { SellerChartSeries } from '#layers/seller/app/lib/seller-dashboard-view'
import {
  axisLabel,
  changeRate,
  changeTone,
  compareNetSeries,
  formatChangeRate,
  salesChangeTone,
  type ChangeTone,
  type SortableBreakdownRow,
} from '~/lib/stats-view'

/**
 * 셀러 매출 통계 표시 규칙(Track 90-E-1·관리자 admin-sales-stats-view 복제·순수 함수 부분은 공용 ~/lib/stats-view 사용). 레이어에 묶이는 것
 * (slr-chip 배지 클래스·셀러 테마 차트 색·카드 정의·삭제 표기)만 여기 둔다. 응답 정규화·후행 0 절단·정렬·CSV 파일명은 공용 함수를 그대로 쓴다.
 */

// ---------- 배지 ----------

/** 증감 배지 CSS(seller-vuetify.css .slr-chip--*). flat은 회색(neutral). */
export function changeChipClass(tone: ChangeTone): string {
  return semanticChipClass(tone === 'up' ? 'success' : tone === 'down' ? 'danger' : 'neutral')
}

// ---------- 요약 카드 ----------

export type SellerSalesSummaryKey = keyof SellerSalesSummary

export interface SellerSalesSummaryCardSpec {
  key: SellerSalesSummaryKey
  label: string
  format: (value: number) => string
  /** true면 증가가 부정(환불) — 톤 색을 반전한다. */
  inverse: boolean
}

/** 카드 6장 정의·순서(D-200 요약 = 매출·주문수·객단가·판매수량 + 환불·순매출·환불만 inverse). 주문당 품목수는 카드로 두지 않는다. */
export const SELLER_SALES_SUMMARY_CARDS: SellerSalesSummaryCardSpec[] = [
  { key: 'revenue', label: '매출', format: formatWon, inverse: false },
  { key: 'refund', label: '환불', format: formatWon, inverse: true },
  { key: 'netRevenue', label: '순매출', format: formatWon, inverse: false },
  { key: 'orderCount', label: '주문수', format: (value) => formatCount(value), inverse: false },
  { key: 'avgOrderValue', label: '객단가', format: formatWon, inverse: false },
  { key: 'itemQuantity', label: '판매수량', format: (value) => formatCount(value, '개'), inverse: false },
]

/** 요약 카드 뷰(매출·클레임 공용·key는 카드별 문자열). */
export interface SellerStatsSummaryCardView {
  key: string
  label: string
  value: string
  compareValue: string | null
  rateText: string
  rateClass: string
}

/** 카드 뷰 6장. 비교 없음(compareSummary null)이면 배지 "—"·회색·비교값 없음. */
export function sellerSalesSummaryCards(summary: SellerSalesSummary | null, compareSummary: SellerSalesSummary | null): SellerStatsSummaryCardView[] {
  return SELLER_SALES_SUMMARY_CARDS.map((card) => {
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

/** 셀러 Vuetify 테마와 같은 색(lib/vuetify.ts primary teal·info·success) + 비교 회색. */
const CHART_COLOR_PRIMARY = '#0D9488'
const CHART_COLOR_WARNING = '#F97316'
const CHART_COLOR_SUCCESS = '#22C55E'
const CHART_COLOR_COMPARE = '#94A3B8'
const CHART_FONT_FAMILY = 'inherit'
const DASH_SOLID = 0
const DASH_COMPARE = 5
const LINE_WIDTH = 2
const MAX_X_TICKS = 12
const COMPARE_SERIES_NAME = '비교 순매출'

export interface SellerSalesTrendChartSpec {
  series: SellerChartSeries
  options: ApexOptions
}

/**
 * 매출·환불·순매출 3계열 line + (비교 시) 비교 순매출 1계열 점선(관리자 FE-34 §1-A α 동일). 매출·환불 비교값은 툴팁에 병기한다.
 * x축은 bucketLabel(주는 월요일 날짜). y축·툴팁은 원 단위 콤마.
 */
export function sellerSalesTrendChart(trend: SellerSalesTrendBucket[], compareTrend: SellerSalesTrendBucket[] | null): SellerSalesTrendChartSpec {
  const categories = trend.map((bucket) => bucket.bucketLabel)
  const compareNet = compareNetSeries(compareTrend, trend.length)
  const series: SellerChartSeries = [
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

export interface SellerSalesBreakdownRowView extends SortableBreakdownRow {
  /** 행 식별(key 없으면 이름·순번으로 대체). */
  id: string
  key: string | null
  deleted: boolean
  rateText: string
  rateClass: string
  /** 상품 행(PRODUCT 축·key 있음)만 셀러 상품 상세로 이동한다. */
  linkable: boolean
}

/** 응답 행 → 표 행. name null은 "(삭제된 …)"·PRODUCT 축의 key 있는 행만 linkable. */
export function sellerBreakdownRowViews(response: SellerSalesBreakdownResponse | null): SellerSalesBreakdownRowView[] {
  if (!response) return []
  return response.rows.map((row: SellerSalesBreakdownRow, index) => {
    const key = row.key ?? null
    const name = row.name ?? null
    const compareRevenue = row.compareRevenue ?? null
    const rate = compareRevenue === null ? null : changeRate(row.revenue, compareRevenue)
    return {
      id: key ?? `${name ?? 'row'}-${index}`,
      key,
      name: name ?? SELLER_DELETED_NAME_LABELS[response.axis],
      deleted: name === null,
      revenue: row.revenue,
      share: row.share,
      orderCount: row.orderCount,
      quantity: row.quantity,
      compareRevenue,
      rate,
      rateText: formatChangeRate(rate),
      rateClass: changeChipClass(changeTone(rate)),
      linkable: response.axis === 'PRODUCT' && key !== null,
    }
  })
}
