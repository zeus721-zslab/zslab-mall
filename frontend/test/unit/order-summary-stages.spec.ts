import { describe, it, expect } from 'vitest'
import { toOrderSummaryStages } from '~/lib/utils/order-summary-stages'

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
