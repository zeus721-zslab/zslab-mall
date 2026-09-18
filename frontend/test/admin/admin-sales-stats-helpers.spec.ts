import { describe, it, expect } from 'vitest'
import {
  DEFAULT_ADMIN_SALES_STATS_QUERY,
  isPeriodInverted,
  parseAdminSalesStatsQuery,
  presetPeriod,
  resolvePeriod,
  toAdminSalesStatsRouteQuery,
  toBreakdownApiParams,
  toDateOnly,
} from '#layers/admin/app/lib/admin-sales-stats-query'
import {
  axisLabel,
  breakdownRowViews,
  compareNetSeries,
  csvFileNameFrom,
  normalizeSalesStats,
  salesChangeTone,
  salesSummaryCards,
  salesTrendChart,
  sortBreakdownRows,
} from '#layers/admin/app/lib/admin-sales-stats-view'
import type { AdminSalesBreakdownResponse, AdminSalesSummary, AdminSalesTrendBucket } from '#layers/admin/app/types/admin-sales-stats'

// FE-34 매출 통계: 기간 프리셋·URL 매핑·증감률/환불 톤 반전·compareTrend 후행 0 → null·분해 행·정렬·CSV 파일명.

const TODAY = new Date(2026, 8, 18) // 2026-09-18(금)

describe('기간 프리셋', () => {
  it('7일·30일은 오늘 포함 N일, 3개월은 3개월 전 같은 날 +1일, 올해는 1월 1일부터', () => {
    expect(presetPeriod('7d', TODAY)).toEqual({ from: '2026-09-12', to: '2026-09-18' })
    expect(presetPeriod('30d', TODAY)).toEqual({ from: '2026-08-20', to: '2026-09-18' })
    expect(presetPeriod('3m', TODAY)).toEqual({ from: '2026-06-19', to: '2026-09-18' })
    expect(presetPeriod('ytd', TODAY)).toEqual({ from: '2026-01-01', to: '2026-09-18' })
  })

  it('월말 3개월 전 계산도 유효한 날짜(setMonth 넘침 없이 toDateOnly 통과)', () => {
    expect(presetPeriod('3m', new Date(2026, 4, 31)).from).toBe('2026-03-04')
    expect(toDateOnly(new Date(2026, 0, 5))).toBe('2026-01-05')
  })

  it('custom은 from·to를 그대로, 하나라도 비면 null·역전 판정', () => {
    expect(resolvePeriod({ ...DEFAULT_ADMIN_SALES_STATS_QUERY, preset: 'custom', from: '2026-01-01', to: '2026-01-31' }, TODAY))
      .toEqual({ from: '2026-01-01', to: '2026-01-31' })
    expect(resolvePeriod({ ...DEFAULT_ADMIN_SALES_STATS_QUERY, preset: 'custom', from: '2026-01-01', to: null }, TODAY)).toBeNull()
    expect(isPeriodInverted({ from: '2026-02-01', to: '2026-01-31' })).toBe(true)
    expect(isPeriodInverted({ from: '2026-01-31', to: '2026-01-31' })).toBe(false)
    expect(isPeriodInverted(null)).toBe(false)
  })
})

describe('URL query 매핑', () => {
  it('parse: 허용 외 값은 기본값·custom인데 날짜가 없으면 기본 프리셋', () => {
    expect(parseAdminSalesStatsQuery({})).toEqual(DEFAULT_ADMIN_SALES_STATS_QUERY)
    expect(parseAdminSalesStatsQuery({ preset: 'ytd', unit: 'WEEK', compare: 'YEAR_AGO', axis: 'SELLER', parent: 'slr_1' }))
      .toEqual({ preset: 'ytd', from: null, to: null, unit: 'WEEK', compare: 'YEAR_AGO', axis: 'SELLER', parent: 'slr_1' })
    expect(parseAdminSalesStatsQuery({ preset: 'custom', from: '2026-01-01', to: '2026-01-31' }))
      .toMatchObject({ preset: 'custom', from: '2026-01-01', to: '2026-01-31' })
    expect(parseAdminSalesStatsQuery({ preset: 'custom', from: '2026-01-01' }).preset).toBe('30d')
    expect(parseAdminSalesStatsQuery({ preset: 'bogus', unit: 'HOUR', compare: 'X', axis: 'OPTION', from: '20260101' }))
      .toEqual(DEFAULT_ADMIN_SALES_STATS_QUERY)
  })

  it('toRoute: 기본값 생략·custom일 때만 from·to·parent는 있을 때만', () => {
    expect(toAdminSalesStatsRouteQuery(DEFAULT_ADMIN_SALES_STATS_QUERY)).toEqual({})
    expect(toAdminSalesStatsRouteQuery({ preset: 'custom', from: '2026-01-01', to: '2026-01-31', unit: 'MONTH', compare: 'PREVIOUS', axis: 'PRODUCT', parent: null }))
      .toEqual({ preset: 'custom', from: '2026-01-01', to: '2026-01-31', unit: 'MONTH', compare: 'PREVIOUS', axis: 'PRODUCT' })
    expect(toAdminSalesStatsRouteQuery({ ...DEFAULT_ADMIN_SALES_STATS_QUERY, preset: '7d', from: '2026-01-01', to: '2026-01-31', parent: '5' }))
      .toEqual({ preset: '7d', parent: '5' })
  })

  it('breakdown 파라미터: parentKey는 있을 때만·PRODUCT 축엔 붙이지 않는다(BE 400)', () => {
    const period = { from: '2026-09-01', to: '2026-09-18' }
    expect(toBreakdownApiParams({ ...DEFAULT_ADMIN_SALES_STATS_QUERY, axis: 'CATEGORY', parent: '5' }, period))
      .toEqual({ from: '2026-09-01', to: '2026-09-18', compare: 'NONE', axis: 'CATEGORY', parentKey: '5' })
    expect(toBreakdownApiParams({ ...DEFAULT_ADMIN_SALES_STATS_QUERY, axis: 'PRODUCT', parent: '5' }, period))
      .toEqual({ from: '2026-09-01', to: '2026-09-18', compare: 'NONE', axis: 'PRODUCT' })
  })
})

const SUMMARY: AdminSalesSummary = { revenue: 120, refund: 30, netRevenue: 90, orderCount: 4, itemQuantity: 6, avgOrderValue: 30, avgItemsPerOrder: 1.5 }
const PREVIOUS: AdminSalesSummary = { revenue: 100, refund: 20, netRevenue: 80, orderCount: 4, itemQuantity: 5, avgOrderValue: 25, avgItemsPerOrder: 1.25 }

describe('요약 카드·환불 톤 반전', () => {
  it('환불은 증가가 부정(danger)·감소가 긍정(success), 나머지는 그대로·flat은 회색', () => {
    expect(salesChangeTone(10, false)).toBe('up')
    expect(salesChangeTone(10, true)).toBe('down')
    expect(salesChangeTone(-10, true)).toBe('up')
    expect(salesChangeTone(0, true)).toBe('flat')
    expect(salesChangeTone(null, true)).toBe('flat')
  })

  it('카드 6장: 값·비교값·배지(매출 +20.0% 녹색·환불 +50.0% 빨강·주문수 0.0% 회색)', () => {
    const cards = salesSummaryCards(SUMMARY, PREVIOUS)
    expect(cards.map((card) => card.key)).toEqual(['revenue', 'refund', 'netRevenue', 'orderCount', 'avgOrderValue', 'avgItemsPerOrder'])
    expect(cards[0]).toMatchObject({ value: '120원', compareValue: '100원', rateText: '+20.0%', rateClass: 'adm-chip adm-chip--success' })
    expect(cards[1]).toMatchObject({ value: '30원', rateText: '+50.0%', rateClass: 'adm-chip adm-chip--danger' })
    expect(cards[3]).toMatchObject({ value: '4건', rateText: '0.0%', rateClass: 'adm-chip adm-chip--neutral' })
    expect(cards[5]).toMatchObject({ value: '1.50개', compareValue: '1.25개', rateText: '+20.0%' })
  })

  it('비교 없음(null)·로딩(null)이면 "—"·회색·비교값 없음', () => {
    const noCompare = salesSummaryCards(SUMMARY, null)
    expect(noCompare[0]).toMatchObject({ value: '120원', compareValue: null, rateText: '—', rateClass: 'adm-chip adm-chip--neutral' })
    expect(salesSummaryCards(null, null)[0]).toMatchObject({ value: '—', rateText: '—' })
  })

  it('normalize: NON_NULL로 생략된 비교 필드를 null로 고정', () => {
    const normalized = normalizeSalesStats({ summary: SUMMARY, trend: [] })
    expect(normalized.compareSummary).toBeNull()
    expect(normalized.compareTrend).toBeNull()
  })
})

function bucket(label: string, revenue: number, refund = 0, orderCount = revenue > 0 ? 1 : 0): AdminSalesTrendBucket {
  return { bucketKey: label, bucketLabel: label, revenue, refund, netRevenue: revenue - refund, orderCount }
}

describe('compareTrend 후행 0 → null', () => {
  it('끝의 0 구간만 null로 끊고 중간 0은 유지·길이는 trend에 맞춤', () => {
    const compare = [bucket('a', 100), bucket('b', 0), bucket('c', 50), bucket('d', 0), bucket('e', 0)]
    expect(compareNetSeries(compare, 5)).toEqual([100, 0, 50, null, null])
    expect(compareNetSeries(compare, 3)).toEqual([100, 0, 50])
    expect(compareNetSeries([bucket('a', 100)], 3)).toEqual([100, null, null])
    expect(compareNetSeries(null, 3)).toBeNull()
  })

  it('환불만 있는 구간(매출 0)은 실제 데이터라 0으로 유지', () => {
    expect(compareNetSeries([bucket('a', 100), bucket('b', 0, 10, 0)], 2)).toEqual([100, -10])
  })

  it('차트: 비교 있으면 4계열(비교 순매출 점선 dashArray 5)·없으면 3계열·x축은 bucketLabel', () => {
    const trend = [bucket('2026-09-14', 100, 10), bucket('2026-09-15', 0)]
    const withCompare = salesTrendChart(trend, [bucket('2026-09-07', 80), bucket('2026-09-08', 0)])
    expect(withCompare.series.map((series) => series.name)).toEqual(['매출', '환불', '순매출', '비교 순매출'])
    expect(withCompare.series[3]?.data).toEqual([80, null])
    expect(withCompare.options.stroke?.dashArray).toEqual([0, 0, 0, 5])
    expect(withCompare.options.xaxis?.categories).toEqual(['2026-09-14', '2026-09-15'])
    expect(axisLabel('2026-09-14')).toBe('09-14')
    expect(axisLabel('2026-09')).toBe('2026-09')
    const noCompare = salesTrendChart(trend, null)
    expect(noCompare.series).toHaveLength(3)
    expect(noCompare.series[2]?.data).toEqual([90, 0])
  })
})

const BREAKDOWN: AdminSalesBreakdownResponse = {
  axis: 'SELLER',
  totalRevenue: 1000,
  rows: [
    { key: 'slr_a', name: '셀러A', revenue: 600, share: 60, orderCount: 3, quantity: 5, compareRevenue: 500, drillable: true },
    { revenue: 300, share: 30, orderCount: 2, quantity: 2, compareRevenue: 400, drillable: true },
    { key: 'slr_c', name: '셀러C', revenue: 100, share: 10, orderCount: 1, quantity: 1, drillable: true },
  ],
}

describe('분해 행·정렬', () => {
  it('이름 null은 축별 대체 표기·key 없으면 드릴다운 불가·증감률(비교 없는 키는 "—")', () => {
    const rows = breakdownRowViews(BREAKDOWN)
    expect(rows[0]).toMatchObject({ key: 'slr_a', name: '셀러A', deleted: false, rateText: '+20.0%', drillable: true })
    expect(rows[1]).toMatchObject({ key: null, name: '(삭제된 셀러)', deleted: true, rateText: '-25.0%', drillable: false })
    expect(rows[2]).toMatchObject({ compareRevenue: null, rate: null, rateText: '—', drillable: true })
    // parentKey가 있으면 행은 상품이라 대체 표기도 상품
    expect(breakdownRowViews({ ...BREAKDOWN, axis: 'CATEGORY', parentKey: '5', rows: [BREAKDOWN.rows[1]!] })[0]?.name).toBe('(삭제된 상품)')
    expect(breakdownRowViews(null)).toEqual([])
  })

  it('비중 정렬 asc/desc·비교 불가(null)는 방향과 무관하게 맨 뒤·이름은 가나다', () => {
    const rows = breakdownRowViews(BREAKDOWN)
    expect(sortBreakdownRows(rows, 'share', 'asc').map((row) => row.share)).toEqual([10, 30, 60])
    expect(sortBreakdownRows(rows, 'share', 'desc').map((row) => row.share)).toEqual([60, 30, 10])
    expect(sortBreakdownRows(rows, 'rate', 'desc').map((row) => row.rateText)).toEqual(['+20.0%', '-25.0%', '—'])
    expect(sortBreakdownRows(rows, 'rate', 'asc').map((row) => row.rateText)).toEqual(['-25.0%', '+20.0%', '—'])
    expect(sortBreakdownRows(rows, 'name', 'asc').map((row) => row.name)).toEqual(['(삭제된 셀러)', '셀러A', '셀러C'])
    // 원본 불변
    expect(rows.map((row) => row.share)).toEqual([60, 30, 10])
  })
})

describe('CSV 파일명 추출', () => {
  it('filename*=UTF-8 우선 디코드·없으면 filename·둘 다 없으면 기본명', () => {
    expect(csvFileNameFrom(`attachment; filename="sales-breakdown-product-2026-09-01_2026-09-18.csv"; filename*=UTF-8''%EB%A7%A4%EC%B6%9C%ED%86%B5%EA%B3%84_%EC%83%81%ED%92%88_2026-09-01_2026-09-18.csv`))
      .toBe('매출통계_상품_2026-09-01_2026-09-18.csv')
    expect(csvFileNameFrom('attachment; filename="plain.csv"')).toBe('plain.csv')
    expect(csvFileNameFrom('attachment; filename=bare.csv')).toBe('bare.csv')
    expect(csvFileNameFrom(null)).toBe('sales-breakdown.csv')
    expect(csvFileNameFrom(`attachment; filename="fallback.csv"; filename*=UTF-8''%E0%A4%A`)).toBe('fallback.csv')
  })
})
