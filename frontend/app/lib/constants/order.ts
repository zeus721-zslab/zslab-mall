/**
 * 주문 상태 라벨 단일 소스(FE-12a·FE-12c·BE OrderStatus enum 9값 대응·CLAUDE.md 4층위 enum 잠금 (4)프론트).
 * BE는 StatusView.label=code(한글 미제공)로 내려주므로 code→한글 매핑은 FE가 담당한다. 유니온 타입으로 매직 문자열을 막는다.
 * PAYMENT_EXPIRED(미결제 종료)는 목록 API에서 제외되어 통상 노출되지 않으나, 라벨은 방어적으로 정의한다(FE-12c).
 */

/** 주문 상태 code(BE OrderStatus enum 9값). */
export type OrderStatusCode =
  | 'PENDING_PAYMENT'
  | 'PAID'
  | 'PREPARING'
  | 'SHIPPING'
  | 'DELIVERED'
  | 'CONFIRMED'
  | 'CANCELLED'
  | 'PARTIAL_CANCEL'
  | 'PAYMENT_EXPIRED'

/** code→한글 라벨 매핑(표시 문구 확정). */
export const ORDER_STATUS_LABELS: Record<OrderStatusCode, string> = {
  PENDING_PAYMENT: '결제대기',
  PAID: '결제완료',
  PREPARING: '상품준비중',
  SHIPPING: '배송중',
  DELIVERED: '배송완료',
  CONFIRMED: '구매확정',
  CANCELLED: '취소',
  PARTIAL_CANCEL: '부분취소',
  PAYMENT_EXPIRED: '미결제 종료',
}

/** 상태 code를 한글 라벨로 변환한다. 매핑에 없는 code는 원본 code를 그대로 폴백 반환한다(방어). */
export function orderStatusLabel(code: string): string {
  return ORDER_STATUS_LABELS[code as OrderStatusCode] ?? code
}
