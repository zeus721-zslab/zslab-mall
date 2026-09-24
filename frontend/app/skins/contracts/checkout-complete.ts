/**
 * pages/checkout/complete.vue → CheckoutCompleteView. orderPublicId는 없으면 빈 문자열(주문 목록으로 유도 · 링크 전용).
 * orderNo는 주문 상세 조회로 얻은 사람이 읽는 주문번호다 — 조회 전·실패·옛 응답이면 빈 문자열이라 번호 줄을 생략한다(Track 105-4g-3).
 */
export interface CheckoutCompletePageVm {
  orderPublicId: string
  orderNo: string
}
