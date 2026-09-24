import { describe, it, expect } from 'vitest'
import { orderSummaryStageLabel, orderSummaryStageLink, toOrderSummaryStages } from '~/lib/utils/order-summary-stages'

/**
 * Track 105-2d-FE1(FE-72) 마이페이지 주문 현황 단계 매핑. BE stages(D-223) → 표시 순서 5단계와 0건 흐림 판정만 고정한다.
 */
describe('toOrderSummaryStages — 요약 단계 매핑', () => {
  it('결제 완료 · 상품 준비 · 배송 중 · 배송 완료 · 구매 확정 순서로 건수를 옮긴다', () => {
    const stages = toOrderSummaryStages({ paid: 1, preparing: 2, shipping: 3, delivered: 4, confirmed: 5 })
    expect(stages.map(({ key, label, count }) => ({ key, label, count }))).toEqual([
      { key: 'paid', label: '결제 완료', count: 1 },
      { key: 'preparing', label: '상품 준비', count: 2 },
      { key: 'shipping', label: '배송 중', count: 3 },
      { key: 'delivered', label: '배송 완료', count: 4 },
      { key: 'confirmed', label: '구매 확정', count: 5 },
    ])
  })

  it('0건 단계만 흐리게(dimmed) 표시한다', () => {
    const stages = toOrderSummaryStages({ paid: 0, preparing: 3, shipping: 0, delivered: 1, confirmed: 0 })
    expect(stages.map((stage) => stage.dimmed)).toEqual([true, false, true, false, true])
  })

  it('전 단계 0건이면 전부 흐리다', () => {
    const stages = toOrderSummaryStages({ paid: 0, preparing: 0, shipping: 0, delivered: 0, confirmed: 0 })
    expect(stages.every((stage) => stage.dimmed && stage.count === 0)).toBe(true)
  })
})

// FE-80·D-224: 현황 숫자 → 그 단계 품목 상태로 거른 주문 목록(0건 단계도 같은 링크). 칩·빈 결과 문구는 같은 단계 라벨을 쓴다.
describe('orderSummaryStageLink · orderSummaryStageLabel — 홈 현황 링크', () => {
  it('5단계 링크 = /orders?itemStatus=PAID|PREPARING|SHIPPING|DELIVERED|CONFIRMED(탭 쿼리 없음 = 전체 주문)', () => {
    const stages = toOrderSummaryStages({ paid: 0, preparing: 1, shipping: 0, delivered: 2, confirmed: 0 })
    expect(stages.map((stage) => orderSummaryStageLink(stage.key))).toEqual([
      { path: '/orders', query: { itemStatus: 'PAID' } },
      { path: '/orders', query: { itemStatus: 'PREPARING' } },
      { path: '/orders', query: { itemStatus: 'SHIPPING' } },
      { path: '/orders', query: { itemStatus: 'DELIVERED' } },
      { path: '/orders', query: { itemStatus: 'CONFIRMED' } },
    ])
  })

  it('필터 값 → 현황과 같은 단계 라벨', () => {
    expect(orderSummaryStageLabel('PAID')).toBe('결제 완료')
    expect(orderSummaryStageLabel('PREPARING')).toBe('상품 준비')
    expect(orderSummaryStageLabel('DELIVERED')).toBe('배송 완료')
    expect(orderSummaryStageLabel('CONFIRMED')).toBe('구매 확정')
  })
})
