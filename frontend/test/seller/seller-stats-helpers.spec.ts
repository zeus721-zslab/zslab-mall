import { describe, it, expect } from 'vitest'
import {
  DEFAULT_SELLER_SALES_STATS_QUERY,
  parseSellerSalesStatsQuery,
  resolveSellerStatsPeriod,
  sellerStatsPeriodError,
  toSellerBreakdownApiParams,
  toSellerSalesApiParams,
  toSellerSalesStatsRouteQuery,
} from '#layers/seller/app/lib/seller-stats-query'
import {
  changeChipClass,
  sellerBreakdownRowViews,
  sellerSalesSummaryCards,
  sellerSalesTrendChart,
} from '#layers/seller/app/lib/seller-stats-view'
import { SELLER_STATS_AXES, SELLER_STATS_MAX_PERIOD_DAYS } from '#layers/seller/app/lib/constants/seller-stats'
import { resolveBackPath, SELLER_PRODUCTS_PATH, SELLER_STATS_SALES_PATH } from '#layers/seller/app/lib/seller-back-path'
import type { SellerSalesBreakdownResponse, SellerSalesSummary, SellerSalesTrendBucket } from '#layers/seller/app/types/seller-stats'

// Track 90-E-1 셀러 매출 통계 순수 함수: URL 매핑(축 PRODUCT·OPTION·CATEGORY)·기간 오류(역전·365일)·카드 6장(판매수량 포함)·slr-chip 톤·행 뷰(linkable)·차트 계열.

const TODAY = new Date(2026, 8, 21)

function summary(overrides: Partial<SellerSalesSummary> = {}): SellerSalesSummary {
  return { revenue: 48_000, refund: 10_000, netRevenue: 38_000, orderCount: 4, itemQuantity: 6, avgOrderValue: 12_000, avgItemsPerOrder: 1.5, ...overrides }
}

function bucket(key: string, revenue: number, refund = 0): SellerSalesTrendBucket {
  return { bucketKey: key, bucketLabel: key, revenue, refund, netRevenue: revenue - refund, orderCount: revenue > 0 ? 1 : 0 }
}

describe('seller-stats-query', () => {
  it('상수: 축 3종·기간 상한 365 · 기본 상태 = 30d·DAY·NONE·PRODUCT', () => {
    expect([...SELLER_STATS_AXES]).toEqual(['PRODUCT', 'OPTION', 'CATEGORY'])
    expect(SELLER_STATS_MAX_PERIOD_DAYS).toBe(365)
    expect(DEFAULT_SELLER_SALES_STATS_QUERY).toEqual({ preset: '30d', from: null, to: null, unit: 'DAY', compare: 'NONE', axis: 'PRODUCT' })
  })

  it('parse: 잘못된 값은 기본값 · custom은 from·to 둘 다 있어야 · 관리자 축(SELLER)은 기본 PRODUCT로 정규화', () => {
    expect(parseSellerSalesStatsQuery({})).toEqual(DEFAULT_SELLER_SALES_STATS_QUERY)
    expect(parseSellerSalesStatsQuery({ preset: 'custom', from: '2026-03-01', to: '2026-03-31', unit: 'WEEK', compare: 'YEAR_AGO', axis: 'OPTION' }))
      .toEqual({ preset: 'custom', from: '2026-03-01', to: '2026-03-31', unit: 'WEEK', compare: 'YEAR_AGO', axis: 'OPTION' })
    expect(parseSellerSalesStatsQuery({ preset: 'custom', from: '2026-03-01' }).preset).toBe('30d')
    expect(parseSellerSalesStatsQuery({ preset: '99d', unit: 'HOUR', compare: 'x', axis: 'SELLER' })).toEqual(DEFAULT_SELLER_SALES_STATS_QUERY)
    expect(parseSellerSalesStatsQuery({ axis: ['CATEGORY', 'OPTION'] }).axis).toBe('CATEGORY')
  })

  it('toRouteQuery: 기본값 생략 · custom만 from·to · 축 비기본만', () => {
    expect(toSellerSalesStatsRouteQuery(DEFAULT_SELLER_SALES_STATS_QUERY)).toEqual({})
    expect(toSellerSalesStatsRouteQuery({ preset: 'custom', from: '2026-03-01', to: '2026-03-31', unit: 'MONTH', compare: 'PREVIOUS', axis: 'CATEGORY' }))
      .toEqual({ preset: 'custom', from: '2026-03-01', to: '2026-03-31', unit: 'MONTH', compare: 'PREVIOUS', axis: 'CATEGORY' })
    expect(toSellerSalesStatsRouteQuery({ ...DEFAULT_SELLER_SALES_STATS_QUERY, preset: '7d', from: '2026-01-01', to: '2026-01-02' })).toEqual({ preset: '7d' })
  })

  it('기간 오류: 정상 null · 역전 · 365 OK · 366 초과 · 미입력(null) null', () => {
    expect(sellerStatsPeriodError({ from: '2026-03-01', to: '2026-03-31' })).toBeNull()
    expect(sellerStatsPeriodError({ from: '2026-03-11', to: '2026-03-10' })).toContain('시작일이 종료일보다')
    expect(sellerStatsPeriodError({ from: '2025-04-01', to: '2026-03-31' })).toBeNull()
    expect(sellerStatsPeriodError({ from: '2025-03-31', to: '2026-03-31' })).toContain('365일')
    expect(sellerStatsPeriodError(null)).toBeNull()
  })

  it('API 파라미터: 프리셋 기간 해석 · sales는 unit·compare · breakdown은 compare·axis(셀러 파라미터 없음)', () => {
    const state = { ...DEFAULT_SELLER_SALES_STATS_QUERY, preset: '7d' as const, unit: 'WEEK' as const, compare: 'PREVIOUS' as const, axis: 'OPTION' as const }
    const period = resolveSellerStatsPeriod(state, TODAY)
    expect(period).toEqual({ from: '2026-09-15', to: '2026-09-21' })
    expect(toSellerSalesApiParams(state, period!)).toEqual({ from: '2026-09-15', to: '2026-09-21', unit: 'WEEK', compare: 'PREVIOUS' })
    expect(toSellerBreakdownApiParams(state, period!)).toEqual({ from: '2026-09-15', to: '2026-09-21', compare: 'PREVIOUS', axis: 'OPTION' })
  })

  it('back 경로: 상품 상세는 매출 통계(쿼리 포함)로 복귀 허용 · 그 외는 상품 목록', () => {
    expect(resolveBackPath(`${SELLER_STATS_SALES_PATH}?preset=7d&axis=PRODUCT`, SELLER_PRODUCTS_PATH)).toBe(`${SELLER_STATS_SALES_PATH}?preset=7d&axis=PRODUCT`)
    expect(resolveBackPath('/seller/settlements', SELLER_PRODUCTS_PATH)).toBe(SELLER_PRODUCTS_PATH)
  })
})

describe('seller-stats-view', () => {
  it('카드 6장: 매출·환불·순매출·주문수·객단가·판매수량 · 비교 있으면 증감 배지(환불 반전·slr-chip) · 없으면 —', () => {
    const cards = sellerSalesSummaryCards(summary(), summary({ revenue: 40_000, refund: 5_000, itemQuantity: 6 }))
    expect(cards.map((card) => card.key)).toEqual(['revenue', 'refund', 'netRevenue', 'orderCount', 'avgOrderValue', 'itemQuantity'])
    expect(cards[0]).toMatchObject({ label: '매출', value: '48,000원', compareValue: '40,000원', rateText: '+20.0%', rateClass: 'slr-chip slr-chip--success' })
    expect(cards[1]).toMatchObject({ label: '환불', value: '10,000원', rateText: '+100.0%', rateClass: 'slr-chip slr-chip--danger' })
    expect(cards[3]).toMatchObject({ label: '주문수', value: '4건' })
    expect(cards[5]).toMatchObject({ label: '판매수량', value: '6개', rateText: '0.0%', rateClass: 'slr-chip slr-chip--neutral' })
    const noCompare = sellerSalesSummaryCards(summary(), null)
    expect(noCompare[0]).toMatchObject({ compareValue: null, rateText: '—', rateClass: 'slr-chip slr-chip--neutral' })
    expect(sellerSalesSummaryCards(null, null)[0]?.value).toBe('—')
    expect(changeChipClass('up')).toBe('slr-chip slr-chip--success')
    expect(changeChipClass('down')).toBe('slr-chip slr-chip--danger')
    expect(changeChipClass('flat')).toBe('slr-chip slr-chip--neutral')
  })

  it('행 뷰: PRODUCT 축 key 있는 행만 linkable · key 없으면 삭제 표기 · 증감률 · OPTION·CATEGORY는 linkable false', () => {
    const product: SellerSalesBreakdownResponse = {
      axis: 'PRODUCT',
      totalRevenue: 48_000,
      rows: [
        { key: 'prd_A2', name: '상품A2', revenue: 26_000, share: 54.17, orderCount: 2, quantity: 3 },
        { key: 'prd_A', name: '상품A', revenue: 22_000, share: 45.83, orderCount: 3, quantity: 3, compareRevenue: 11_000 },
        { revenue: 1_000, share: 2.08, orderCount: 1, quantity: 1 },
      ],
    }
    const rows = sellerBreakdownRowViews(product)
    expect(rows[0]).toMatchObject({ id: 'prd_A2', key: 'prd_A2', name: '상품A2', linkable: true, deleted: false, compareRevenue: null, rate: null, rateText: '—' })
    expect(rows[1]).toMatchObject({ linkable: true, compareRevenue: 11_000, rateText: '+100.0%', rateClass: 'slr-chip slr-chip--success' })
    expect(rows[2]).toMatchObject({ id: 'row-2', key: null, name: '(삭제된 상품)', deleted: true, linkable: false })
    const option = sellerBreakdownRowViews({ axis: 'OPTION', totalRevenue: 1, rows: [{ key: 'var_1', name: '상품A / 색상: 블랙', revenue: 1, share: 100, orderCount: 1, quantity: 1 }] })
    expect(option[0]).toMatchObject({ name: '상품A / 색상: 블랙', linkable: false })
    const category = sellerBreakdownRowViews({ axis: 'CATEGORY', totalRevenue: 1, rows: [{ key: '9', revenue: 1, share: 100, orderCount: 1, quantity: 1 }] })
    expect(category[0]).toMatchObject({ key: '9', name: '(삭제된 카테고리)', linkable: false })
    expect(sellerBreakdownRowViews(null)).toEqual([])
  })

  it('추이 차트: 3계열 + 비교 시 점선 1계열(후행 0 → null) · 셀러 테마 primary 색 · x축 MM-DD', () => {
    const trend = [bucket('2026-03-01', 6_000), bucket('2026-03-02', 0), bucket('2026-03-03', 30_000, 10_000)]
    const spec = sellerSalesTrendChart(trend, null)
    expect(spec.series.map((series) => series.name)).toEqual(['매출', '환불', '순매출'])
    expect(spec.series[2]?.data).toEqual([6_000, 0, 20_000])
    expect(spec.options.colors?.[0]).toBe('#0D9488')
    expect((spec.options.xaxis as { categories: string[] }).categories).toEqual(['2026-03-01', '2026-03-02', '2026-03-03'])
    const compared = sellerSalesTrendChart(trend, [bucket('2026-02-01', 3_000), bucket('2026-02-02', 0), bucket('2026-02-03', 0)])
    expect(compared.series.map((series) => series.name)).toEqual(['매출', '환불', '순매출', '비교 순매출'])
    expect(compared.series[3]?.data).toEqual([3_000, null, null])
    expect(compared.options.stroke?.dashArray).toEqual([0, 0, 0, 5])
  })
})
