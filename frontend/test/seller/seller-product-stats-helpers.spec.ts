import { describe, it, expect } from 'vitest'
import {
  DEFAULT_SELLER_STATS_RANGE_QUERY,
  parseSellerStatsRangeQuery,
  toSellerProductStatsApiParams,
  toSellerStatsRangeRouteQuery,
} from '#layers/seller/app/lib/seller-stats-query'
import {
  DEPLETION_NO_SALES,
  DEPLETION_OUT_OF_STOCK,
  formatDepletionDays,
  isProductStatsEmpty,
  productRankRows,
  soldOutCaption,
  stockTurnoverRows,
  unsoldRows,
} from '#layers/seller/app/lib/seller-product-stats-view'
import { resolveBackPath, SELLER_PRODUCTS_PATH, SELLER_STATS_PRODUCTS_PATH } from '#layers/seller/app/lib/seller-back-path'
import type { SellerProductStatsResponse } from '#layers/seller/app/types/seller-product-stats'

// Track 90-E-3 셀러 상품 통계 순수 함수: 기간 query(preset|from|to만)·소진 예상 표기 3분기·상위/하위 행(삭제 표기)·재고 회전 행(임박 강조)·품절 캡션·빈 판정.

function response(overrides: Partial<SellerProductStatsResponse> = {}): SellerProductStatsResponse {
  return {
    periodDays: 31,
    topProducts: [{ productKey: 'prd_A', productName: '통계상품A', revenue: 30_000, orderCount: 2, quantity: 3 }, { productName: '삭제상품', revenue: 1_000, orderCount: 1, quantity: 1 }],
    bottomProducts: [],
    unsoldProducts: [{ productKey: 'prd_A3', productName: '통계상품A3', basePrice: 10_000 }],
    stockTurnover: [
      { productKey: 'prd_A2', productName: '통계상품A2', inboundQuantity: 0, soldQuantity: 1, availableQuantity: 0, depletionDays: 0 },
      { productKey: 'prd_A', productName: '통계상품A', inboundQuantity: 80, soldQuantity: 3, availableQuantity: 5, depletionDays: 52 },
      { productKey: 'prd_A5', productName: '통계상품A5', inboundQuantity: 0, soldQuantity: 10, availableQuantity: 2, depletionDays: 7 },
      { productKey: 'prd_A3', productName: '통계상품A3', inboundQuantity: 20, soldQuantity: 0, availableQuantity: 10 },
    ],
    soldOutOptionCount: 2,
    saleOptionCount: 5,
    ...overrides,
  }
}

describe('seller-stats-query 상품(기간만)', () => {
  it('parse: preset·from·to만 · unit/compare/axis 무시 · toRouteQuery 기본 생략 · API 파라미터 from·to', () => {
    expect(parseSellerStatsRangeQuery({})).toEqual(DEFAULT_SELLER_STATS_RANGE_QUERY)
    expect(parseSellerStatsRangeQuery({ preset: 'custom', from: '2026-03-01', to: '2026-03-31', unit: 'WEEK', axis: 'OPTION' }))
      .toEqual({ preset: 'custom', from: '2026-03-01', to: '2026-03-31' })
    expect(toSellerStatsRangeRouteQuery(DEFAULT_SELLER_STATS_RANGE_QUERY)).toEqual({})
    expect(toSellerStatsRangeRouteQuery({ preset: 'custom', from: '2026-03-01', to: '2026-03-31' })).toEqual({ preset: 'custom', from: '2026-03-01', to: '2026-03-31' })
    expect(toSellerProductStatsApiParams({ from: '2026-03-01', to: '2026-03-31' })).toEqual({ from: '2026-03-01', to: '2026-03-31' })
    expect(resolveBackPath(`${SELLER_STATS_PRODUCTS_PATH}?preset=7d`, SELLER_PRODUCTS_PATH)).toBe(`${SELLER_STATS_PRODUCTS_PATH}?preset=7d`)
  })
})

describe('seller-product-stats-view', () => {
  it('소진 예상 표기: 판매 0(생략/null) → 판매 없음 · 0 → 재고 없음 · N → N일(천 단위 콤마)', () => {
    expect(formatDepletionDays(undefined)).toBe(DEPLETION_NO_SALES)
    expect(formatDepletionDays(null)).toBe(DEPLETION_NO_SALES)
    expect(formatDepletionDays(0)).toBe(DEPLETION_OUT_OF_STOCK)
    expect(formatDepletionDays(52)).toBe('52일')
    expect(formatDepletionDays(1234)).toBe('1,234일')
  })

  it('상위/하위 행: key 있으면 linkable · 없으면 삭제 표기·이동 불가 · 미판매 행', () => {
    const rows = productRankRows(response().topProducts)
    expect(rows[0]).toEqual({ id: 'prd_A', key: 'prd_A', name: '통계상품A', deleted: false, revenue: 30_000, orderCount: 2, quantity: 3, linkable: true })
    expect(rows[1]).toMatchObject({ id: 'row-1', key: null, name: '삭제상품', deleted: true, linkable: false })
    expect(unsoldRows(response().unsoldProducts)).toEqual([{ key: 'prd_A3', name: '통계상품A3', basePrice: 10_000 }])
  })

  it('재고 회전 행: 표기·임박 강조(재고 없음·7일 이내만·판매 없음은 강조 안 함)', () => {
    const rows = stockTurnoverRows(response().stockTurnover)
    expect(rows.map((row) => [row.key, row.depletionText, row.urgent])).toEqual([
      ['prd_A2', '재고 없음', true],
      ['prd_A', '52일', false],
      ['prd_A5', '7일', true],
      ['prd_A3', '판매 없음', false],
    ])
    expect(rows[1]).toMatchObject({ inboundQuantity: 80, soldQuantity: 3, availableQuantity: 5 })
  })

  it('품절 캡션·빈 판정', () => {
    expect(soldOutCaption(null)).toBe('')
    expect(soldOutCaption(response())).toBe('판매 중 옵션 5개 중 · 현재 시점(기간과 무관)')
    expect(soldOutCaption(response({ saleOptionCount: 0, soldOutOptionCount: 0 }))).toBe('판매 중 옵션 없음 · 현재 시점')
    expect(isProductStatsEmpty(null)).toBe(false)
    expect(isProductStatsEmpty(response())).toBe(false)
    expect(isProductStatsEmpty(response({ topProducts: [], unsoldProducts: [], stockTurnover: [] }))).toBe(true)
  })
})
