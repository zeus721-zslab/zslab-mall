import { describe, it, expect } from 'vitest'
import { buildCheckoutSummary } from '~/lib/utils/checkout-summary'
import { SHIPPING_FEE } from '~/lib/constants/checkout'
import type { CartItemView } from '~/types/cart'

function item(overrides: Partial<CartItemView> & { variantPublicId: string }): CartItemView {
  return {
    quantity: 1,
    selected: true,
    productName: '상품',
    sellerName: '판매자',
    displayPrice: 1000,
    quantityAvailable: 10,
    purchasable: true,
    thumbnailUrl: null,
    ...overrides,
  }
}

describe('buildCheckoutSummary', () => {
  it('빈 목록이면 품목 0·금액 0·구매 불가 없음을 반환한다', () => {
    const summary = buildCheckoutSummary([])
    expect(summary.items).toEqual([])
    expect(summary.hasUnpurchasableSelected).toBe(false)
    expect(summary.kindCount).toBe(0)
    expect(summary.totalQuantity).toBe(0)
    expect(summary.productTotal).toBe(0)
    expect(summary.finalAmount).toBe(SHIPPING_FEE)
  })

  it('미선택 품목은 목록·금액 모두에서 제외한다', () => {
    const summary = buildCheckoutSummary([
      item({ variantPublicId: 'var_a', displayPrice: 1000, quantity: 2 }),
      item({ variantPublicId: 'var_b', displayPrice: 5000, quantity: 1, selected: false }),
    ])
    expect(summary.items.map((cartItem) => cartItem.variantPublicId)).toEqual(['var_a'])
    expect(summary.kindCount).toBe(1)
    expect(summary.totalQuantity).toBe(2)
    expect(summary.productTotal).toBe(2000)
  })

  it('합계·총 수량·종류 수를 selected 품목의 displayPrice × quantity로 계산한다', () => {
    const summary = buildCheckoutSummary([
      item({ variantPublicId: 'var_a', displayPrice: 1500, quantity: 2 }),
      item({ variantPublicId: 'var_b', displayPrice: 3000, quantity: 3 }),
    ])
    expect(summary.kindCount).toBe(2)
    expect(summary.totalQuantity).toBe(5)
    expect(summary.productTotal).toBe(12000)
    expect(summary.hasUnpurchasableSelected).toBe(false)
  })

  it('배송비 0원을 반영해 최종 결제 금액 = 상품 금액 합계가 된다', () => {
    const summary = buildCheckoutSummary([item({ variantPublicId: 'var_a', displayPrice: 7000, quantity: 1 })])
    expect(SHIPPING_FEE).toBe(0)
    expect(summary.shippingFee).toBe(0)
    expect(summary.finalAmount).toBe(summary.productTotal)
    expect(summary.finalAmount).toBe(7000)
  })

  it('선택 중 구매 불가 품목이 있으면 목록에는 남기되 금액·종류·수량에서 제외하고 플래그를 세운다', () => {
    const summary = buildCheckoutSummary([
      item({ variantPublicId: 'var_a', displayPrice: 1000, quantity: 2 }),
      item({ variantPublicId: 'var_dangling', displayPrice: 0, quantity: 4, purchasable: false, productName: null }),
    ])
    expect(summary.items).toHaveLength(2)
    expect(summary.hasUnpurchasableSelected).toBe(true)
    expect(summary.kindCount).toBe(1)
    expect(summary.totalQuantity).toBe(2)
    expect(summary.productTotal).toBe(2000)
  })

  it('구매 불가 품목이 미선택이면 플래그를 세우지 않는다', () => {
    const summary = buildCheckoutSummary([
      item({ variantPublicId: 'var_a' }),
      item({ variantPublicId: 'var_b', purchasable: false, selected: false }),
    ])
    expect(summary.hasUnpurchasableSelected).toBe(false)
  })
})
