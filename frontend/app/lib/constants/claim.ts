/**
 * 클레임/품목상태 라벨·규칙 단일 소스(FE-14·CLAUDE.md 4층위 enum 잠금 (4)프론트). order.ts 패턴 정합
 * (유니온 타입 + Record 라벨맵 + label 함수). BE는 StatusView.label=code로 내려주므로 code→한글은 FE가 담당한다.
 *
 * 실측 근거: OrderItemStatus.java(12값·enums), ClaimType.java(3값), ClaimReasonCode.java(10값),
 * claimableTypes 매트릭스는 OrderItemStatus.canTransitionTo(Claim 진입 전이)와 1:1 정합.
 */

/** 주문 품목 상태 code(BE OrderItemStatus enum 12값). */
export type OrderItemStatusCode =
  | 'ORDERED'
  | 'PAID'
  | 'PREPARING'
  | 'SHIPPING'
  | 'DELIVERED'
  | 'CONFIRMED'
  | 'CANCEL_REQUESTED'
  | 'CANCELLED'
  | 'RETURN_REQUESTED'
  | 'RETURNED'
  | 'EXCHANGE_REQUESTED'
  | 'EXCHANGED'

/** 품목 상태 code→한글 라벨(도메인 의미대로). */
export const ORDER_ITEM_STATUS_LABELS: Record<OrderItemStatusCode, string> = {
  ORDERED: '주문접수',
  PAID: '결제완료',
  PREPARING: '상품준비중',
  SHIPPING: '배송중',
  DELIVERED: '배송완료',
  CONFIRMED: '구매확정',
  CANCEL_REQUESTED: '취소요청',
  CANCELLED: '취소완료',
  RETURN_REQUESTED: '반품요청',
  RETURNED: '반품완료',
  EXCHANGE_REQUESTED: '교환요청',
  EXCHANGED: '교환완료',
}

/** 품목 상태 code를 한글 라벨로 변환한다. 매핑에 없는 code는 원본 code를 폴백 반환한다(방어). */
export function orderItemStatusLabel(code: string): string {
  return ORDER_ITEM_STATUS_LABELS[code as OrderItemStatusCode] ?? code
}

/** 클레임 유형 code(BE ClaimType enum 3값). */
export type ClaimType = 'CANCEL' | 'RETURN' | 'EXCHANGE'

/** 클레임 유형 code→한글 라벨. */
export const CLAIM_TYPE_LABELS: Record<ClaimType, string> = {
  CANCEL: '취소',
  RETURN: '반품',
  EXCHANGE: '교환',
}

/** 클레임 유형 라벨 변환. 매핑에 없는 값은 원본 폴백. */
export function claimTypeLabel(code: string): string {
  return CLAIM_TYPE_LABELS[code as ClaimType] ?? code
}

/** 주어진 문자열이 유효한 ClaimType인지 판정한다(query 파싱 방어). */
export function isClaimType(value: string): value is ClaimType {
  return value === 'CANCEL' || value === 'RETURN' || value === 'EXCHANGE'
}

/** 클레임 처리 상태 code(BE ClaimStatus enum 4값). */
export type ClaimStatus = 'REQUESTED' | 'APPROVED' | 'REJECTED' | 'COMPLETED'

/** 클레임 상태 code→한글 라벨(BE 전이 매트릭스 의미대로). */
export const CLAIM_STATUS_LABELS: Record<ClaimStatus, string> = {
  REQUESTED: '요청',
  APPROVED: '승인',
  REJECTED: '거절',
  COMPLETED: '완료',
}

/** 클레임 상태 라벨 변환. 매핑에 없는 값은 원본 폴백(방어). */
export function claimStatusLabel(code: string): string {
  return CLAIM_STATUS_LABELS[code as ClaimStatus] ?? code
}

/** 클레임 사유 코드(BE ClaimReasonCode enum 10값). */
export type ClaimReasonCode =
  | 'BUYER_CHANGED_MIND'
  | 'DUPLICATE_ORDER'
  | 'PAYMENT_ISSUE'
  | 'ORDER_MISTAKE'
  | 'STOCK_DELAY'
  | 'PRODUCT_DEFECT'
  | 'DAMAGED_ON_ARRIVAL'
  | 'WRONG_PRODUCT'
  | 'DELIVERY_DELAY'
  | 'OTHER'

/** 사유 코드→한글 라벨(BE Javadoc 의미대로). */
export const CLAIM_REASON_LABELS: Record<ClaimReasonCode, string> = {
  BUYER_CHANGED_MIND: '단순 변심',
  DUPLICATE_ORDER: '중복 주문',
  PAYMENT_ISSUE: '결제 오류',
  ORDER_MISTAKE: '주문 실수',
  STOCK_DELAY: '재고 지연',
  PRODUCT_DEFECT: '상품 불량',
  DAMAGED_ON_ARRIVAL: '배송 중 파손',
  WRONG_PRODUCT: '오배송',
  DELIVERY_DELAY: '배송 지연',
  OTHER: '기타',
}

/** 드롭다운 노출용 사유 코드 목록(정의 순서 유지). */
export const CLAIM_REASON_CODES: ClaimReasonCode[] = [
  'BUYER_CHANGED_MIND',
  'DUPLICATE_ORDER',
  'PAYMENT_ISSUE',
  'ORDER_MISTAKE',
  'STOCK_DELAY',
  'PRODUCT_DEFECT',
  'DAMAGED_ON_ARRIVAL',
  'WRONG_PRODUCT',
  'DELIVERY_DELAY',
  'OTHER',
]

/**
 * 품목 상태 code에서 구매자가 요청 가능한 클레임 유형 목록을 반환한다.
 * BE OrderItemStatus.canTransitionTo(Claim 진입 전이·D-88)와 1:1 정합:
 *   PAID·PREPARING → CANCEL(배송 전 취소) / SHIPPING → RETURN(출고 후 반품) /
 *   DELIVERED → RETURN·EXCHANGE(수령 후 반품·교환). 그 외 상태는 요청 불가([] → 버튼 미노출).
 */
export function claimableTypes(itemStatusCode: string): ClaimType[] {
  switch (itemStatusCode) {
    case 'PAID':
    case 'PREPARING':
      return ['CANCEL']
    case 'SHIPPING':
      return ['RETURN']
    case 'DELIVERED':
      return ['RETURN', 'EXCHANGE']
    default:
      return []
  }
}
