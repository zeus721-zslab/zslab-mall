import type { CartItemView } from '~/types/cart'
import { SHIPPING_FEE } from '~/lib/constants/checkout'

/**
 * 체크아웃 주문 요약 순수 로직(FE-17). 장바구니 품목(CartItemView[])에서 주문 상품 목록·금액 요약을 산출해
 * 페이지(뷰)와 분리해 단위 테스트 가능하게 한다.
 *
 * 서버는 selected 전체를 주문에 포함하고(CartCheckoutService) 신규 주문 경로는 상품 status를 재검증하지 않으므로(D-63),
 * selected ∧ !purchasable 품목이 하나라도 있으면 화면 금액과 실제 결제 금액이 어긋날 수 있다. 따라서 그 경우
 * hasUnpurchasableSelected=true로 알려 호출부가 결제를 차단하고, 금액·종류·수량은 selected ∧ purchasable 기준으로만
 * 계산한다(차단이 풀린 상태에서는 selected 전체 = purchasable이라 서버 금액과 일치).
 */
export interface CheckoutSummary {
  /** 주문 상품 목록(selected 전체·구매 불가 품목 포함·표시용). */
  items: CartItemView[]
  /** selected 품목 중 구매 불가(purchasable=false)가 1개 이상 있는지. true면 결제 차단 대상. */
  hasUnpurchasableSelected: boolean
  /** 총 상품 종류 수(selected ∧ purchasable). */
  kindCount: number
  /** 총 수량(selected ∧ purchasable). */
  totalQuantity: number
  /** 상품 금액 합계 = Σ(displayPrice × quantity)(selected ∧ purchasable). */
  productTotal: number
  /** 배송비(현재 0원 고정·SHIPPING_FEE). */
  shippingFee: number
  /** 최종 결제 금액 = productTotal + shippingFee. */
  finalAmount: number
}

/** 장바구니 품목에서 체크아웃 요약을 만든다. 미선택 품목은 목록·금액 모두에서 제외한다. */
export function buildCheckoutSummary(cartItems: CartItemView[]): CheckoutSummary {
  const selectedItems = cartItems.filter((item) => item.selected)
  const purchasableItems = selectedItems.filter((item) => item.purchasable)
  const productTotal = purchasableItems.reduce((sum, item) => sum + item.displayPrice * item.quantity, 0)
  return {
    items: selectedItems,
    hasUnpurchasableSelected: purchasableItems.length !== selectedItems.length,
    kindCount: purchasableItems.length,
    totalQuantity: purchasableItems.reduce((sum, item) => sum + item.quantity, 0),
    productTotal,
    shippingFee: SHIPPING_FEE,
    finalAmount: productTotal + SHIPPING_FEE,
  }
}
