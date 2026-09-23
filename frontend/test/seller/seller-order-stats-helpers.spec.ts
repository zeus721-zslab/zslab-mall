import { describe, it, expect } from 'vitest'
import {
  DEFAULT_SELLER_STATS_PERIOD_QUERY,
  parseSellerStatsPeriodQuery,
  toSellerOrderStatsApiParams,
  toSellerStatsPeriodRouteQuery,
} from '#layers/seller/app/lib/seller-stats-query'
import {
  normalizeSellerOrderStats,
  sellerClaimProductRows,
  sellerClaimSummaryCards,
  sellerClaimTrendChart,
  sellerDonutChart,
  sellerFunnelStages,
  sellerLeadTimeCards,
} from '#layers/seller/app/lib/seller-order-stats-view'
import { claimReasonRows, claimTypeRows, compareRefundRateSeries, formatHours, formatPercent, formatRate, isClaimTrendEmpty } from '~/lib/stats-view'
import { resolveBackPath, SELLER_PRODUCTS_PATH, SELLER_STATS_ORDERS_PATH } from '#layers/seller/app/lib/seller-back-path'
import type { SellerOrderStatsResponse } from '#layers/seller/app/types/seller-order-stats'
import type { ClaimSummary, ClaimTrendBucket } from '~/types/stats'

// Track 90-E-2 셀러 주문클레임 통계 순수 함수: 기간 query(축 없음)·정규화·퍼널 3단계 비율·소요시간 카드(중앙값 값·평균 캡션)·요약 4장 톤 반전·추이·도넛·상품별 행·공용 이동 함수.

function summary(overrides: Partial<ClaimSummary> = {}): ClaimSummary {
  return { claimCount: 3, claimRate: 75, refundAmount: 15_000, refundRate: 35.71, refundCount: 2, paidItemCount: 4, ...overrides }
}

function bucket(key: string, claimCount: number, refundAmount = 0, refundRate = 0): ClaimTrendBucket {
  return { bucketKey: key, bucketLabel: key, claimCount, claimRate: claimCount * 10, refundAmount, refundRate, refundCount: refundAmount > 0 ? 1 : 0 }
}

function response(): SellerOrderStatsResponse {
  return {
    funnel: { paidItems: 4, shippedItems: 3, deliveredItems: 2 },
    leadTime: { paidToShipped: { avgHours: 32, medianHours: 24, count: 3 } },
    claimSummary: summary(),
    claimTrend: [bucket('2026-03-01', 0), bucket('2026-03-02', 1, 10_000, 20)],
    claimByType: [{ type: 'CANCEL', count: 1, share: 33.33 }, { type: 'RETURN', count: 1, share: 33.33 }, { type: 'EXCHANGE', count: 1, share: 33.33 }],
    claimByReason: [{ reasonCode: 'PRODUCT_DEFECT', count: 2, share: 66.67 }, { reasonCode: 'UNKNOWN_X', count: 1, share: 33.33 }],
    claimByProduct: [{ productKey: 'prd_A2', productName: '통계상품A2', count: 2, share: 66.67 }, { productName: '삭제상품', count: 1, share: 33.33 }],
  }
}

describe('seller-stats-query 주문클레임(기간만)', () => {
  it('parse: 축 없음·기본값 · 잘못된 값 정규화 · toRouteQuery는 축을 싣지 않음 · API 파라미터', () => {
    expect(parseSellerStatsPeriodQuery({})).toEqual(DEFAULT_SELLER_STATS_PERIOD_QUERY)
    expect(parseSellerStatsPeriodQuery({ preset: 'custom', from: '2026-03-01', to: '2026-03-31', unit: 'WEEK', compare: 'PREVIOUS', axis: 'OPTION' }))
      .toEqual({ preset: 'custom', from: '2026-03-01', to: '2026-03-31', unit: 'WEEK', compare: 'PREVIOUS' })
    expect(toSellerStatsPeriodRouteQuery(DEFAULT_SELLER_STATS_PERIOD_QUERY)).toEqual({})
    expect(toSellerStatsPeriodRouteQuery({ preset: '7d', from: null, to: null, unit: 'MONTH', compare: 'YEAR_AGO' })).toEqual({ preset: '7d', unit: 'MONTH', compare: 'YEAR_AGO' })
    expect(toSellerOrderStatsApiParams({ ...DEFAULT_SELLER_STATS_PERIOD_QUERY, unit: 'WEEK' }, { from: '2026-03-01', to: '2026-03-31' }))
      .toEqual({ from: '2026-03-01', to: '2026-03-31', unit: 'WEEK', compare: 'NONE' })
  })

  it('back 경로: 상품 상세는 주문클레임 통계(쿼리 포함)로 복귀 허용', () => {
    expect(resolveBackPath(`${SELLER_STATS_ORDERS_PATH}?preset=7d`, SELLER_PRODUCTS_PATH)).toBe(`${SELLER_STATS_ORDERS_PATH}?preset=7d`)
  })
})

describe('seller-order-stats-view', () => {
  it('정규화: 생략된 소요시간·비교 → null · 상품별 배열 유지', () => {
    const normalized = normalizeSellerOrderStats(response())
    expect(normalized.leadTime).toEqual({ paidToShipped: { avgHours: 32, medianHours: 24, count: 3 }, shippedToDelivered: null })
    expect(normalized.compareClaimSummary).toBeNull()
    expect(normalized.compareClaimTrend).toBeNull()
    expect(normalized.claimByProduct).toHaveLength(2)
  })

  it('퍼널 3단계: 결제 100%·발송 75%(이탈 25%)·배송완료 50%(이탈 33.3%) · null/0이면 0·— ', () => {
    const stages = sellerFunnelStages({ paidItems: 4, shippedItems: 3, deliveredItems: 2 })
    expect(stages.map((stage) => [stage.key, stage.count, stage.reachRate, stage.dropRate])).toEqual([
      ['paidItems', 4, 100, null],
      ['shippedItems', 3, 75, 25],
      ['deliveredItems', 2, 50, (1 / 3) * 100],
    ])
    expect(formatRate(stages[2]!.dropRate)).toBe('33.3%')
    expect(sellerFunnelStages(null).map((stage) => stage.reachRate)).toEqual([0, 0, 0])
    expect(sellerFunnelStages({ paidItems: 0, shippedItems: 0, deliveredItems: 0 })[1]?.dropRate).toBeNull()
  })

  it('소요시간 카드 2장: 값 = 중앙값·캡션 표본·평균 · 표본 0은 데이터 없음 · formatHours 24h 경계', () => {
    const cards = sellerLeadTimeCards({ paidToShipped: { avgHours: 32, medianHours: 24, count: 3 }, shippedToDelivered: null })
    expect(cards.map((card) => card.key)).toEqual(['paidToShipped', 'shippedToDelivered'])
    expect(cards[0]).toMatchObject({ label: '결제 → 발송', median: '1일', average: '1일 8시간', caption: '표본 3건 · 종결 시각 기준', empty: false })
    expect(cards[1]).toMatchObject({ label: '발송 → 배송완료', median: '데이터 없음', average: '—', caption: '표본 0건', empty: true })
    expect(formatHours(23.96)).toBe('24.0시간')
    expect(formatHours(47.6)).toBe('2일')
    expect(formatHours(72)).toBe('3일')
  })

  it('클레임 요약 4장: 전부 톤 반전(증가 = danger) · 비교 없으면 — · 포맷', () => {
    const cards = sellerClaimSummaryCards(summary(), summary({ claimCount: 1, claimRate: 100, refundAmount: 30_000, refundRate: 50 }))
    expect(cards.map((card) => card.key)).toEqual(['claimCount', 'claimRate', 'refundAmount', 'refundRate'])
    expect(cards[0]).toMatchObject({ label: '클레임 건수', value: '3건', compareValue: '1건', rateText: '+200.0%', rateClass: 'slr-chip slr-chip--danger' })
    expect(cards[1]).toMatchObject({ value: '75.00%', rateText: '-25.0%', rateClass: 'slr-chip slr-chip--success' })
    expect(cards[2]).toMatchObject({ value: '15,000원', compareValue: '30,000원', rateText: '-50.0%', rateClass: 'slr-chip slr-chip--success' })
    expect(sellerClaimSummaryCards(summary(), null)[3]).toMatchObject({ value: '35.71%', compareValue: null, rateText: '—', rateClass: 'slr-chip slr-chip--neutral' })
    expect(sellerClaimSummaryCards(null, null)[0]?.value).toBe('—')
    expect(formatPercent(35.714)).toBe('35.71%')
  })

  it('추이 차트: 2계열 + 비교 환불률 점선(후행 0 → null) · 셀러 teal 환불률 · 빈 판정', () => {
    const trend = [bucket('2026-03-01', 0), bucket('2026-03-02', 1, 10_000, 20)]
    const spec = sellerClaimTrendChart(trend, null)
    expect(spec.series.map((series) => series.name)).toEqual(['클레임률', '환불률'])
    expect(spec.series[1]?.data).toEqual([0, 20])
    expect(spec.options.colors).toEqual(['#F97316', '#0D9488'])
    const compared = sellerClaimTrendChart(trend, [bucket('2026-02-01', 1, 5_000, 10), bucket('2026-02-02', 0)])
    expect(compared.series[2]).toEqual({ name: '비교 환불률', data: [10, null] })
    expect(compareRefundRateSeries([bucket('a', 0, 0, 0)], 2)).toEqual([null, null])
    expect(isClaimTrendEmpty([bucket('a', 0)])).toBe(true)
    expect(isClaimTrendEmpty(trend)).toBe(false)
  })

  it('분포·도넛·상품별 행: 유형/사유 라벨(미매핑 원문) · 합 0이면 empty · 상품별 linkable = productKey 있는 행', () => {
    const data = response()
    expect(claimTypeRows(data.claimByType).map((row) => row.label)).toEqual(['취소', '반품', '교환'])
    expect(claimReasonRows(data.claimByReason).map((row) => row.label)[1]).toBe('UNKNOWN_X')
    const donut = sellerDonutChart(claimTypeRows(data.claimByType), '건')
    expect(donut.series).toEqual([1, 1, 1])
    expect(donut.empty).toBe(false)
    expect(donut.options.colors?.[0]).toBe('#0D9488')
    expect(sellerDonutChart([], '건').empty).toBe(true)
    const rows = sellerClaimProductRows(data.claimByProduct)
    expect(rows[0]).toEqual({ id: 'prd_A2', key: 'prd_A2', name: '통계상품A2', count: 2, share: 66.67, linkable: true })
    expect(rows[1]).toEqual({ id: 'row-1', key: null, name: '삭제상품', count: 1, share: 33.33, linkable: false })
  })
})
