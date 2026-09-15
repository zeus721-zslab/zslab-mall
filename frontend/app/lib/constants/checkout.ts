/**
 * 체크아웃 금액 요약 상수(FE-17). 서버 신규 주문은 shipping=0·discount=0 고정(D-61·CheckoutService.createOrder)이라
 * 화면 배송비도 0원으로 표시한다. 배송비 정책 트랙에서 교체.
 */
export const SHIPPING_FEE = 0
