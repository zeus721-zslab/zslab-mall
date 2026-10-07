/**
 * 체크아웃 금액 요약 상수(FE-17). 서버 신규 주문은 shipping=0·discount=0 고정(D-61·CheckoutService.createOrder)이라
 * 화면 배송비도 0원으로 표시한다. 배송비 정책 트랙에서 교체.
 */
export const SHIPPING_FEE = 0

// D-268 SEC-02 미결제 주문 한도 — SoT: BE GlobalExceptionHandler UNPAID_ORDER_LIMIT_EXCEEDED(422)·OrderService.MAX_UNPAID_ORDERS_PER_BUYER(3).
export const UNPAID_ORDER_LIMIT_EXCEEDED_CODE = 'UNPAID_ORDER_LIMIT_EXCEEDED'
export const UNPAID_ORDER_LIMIT_EXCEEDED_MESSAGE =
  '결제 대기 중인 주문이 3건 있습니다. 주문 내역에서 기존 주문을 결제해 주세요. 결제하지 않은 주문은 30분 후 자동 취소됩니다.'
