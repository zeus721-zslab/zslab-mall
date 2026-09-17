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
 * BE OrderItemStatus.canTransitionTo(Claim 진입 전이·D-88)와 정합하되, 반품은 서비스 규칙(D-170·배송완료 기준 기한)상
 * DELIVERED만 허용하므로 SHIPPING → RETURN은 노출하지 않는다(FE-29):
 *   PAID·PREPARING → CANCEL(배송 전 취소) / DELIVERED → RETURN·EXCHANGE(수령 후 반품·교환). 그 외 상태는 요청 불가([] → 버튼 미노출).
 */
export function claimableTypes(itemStatusCode: string): ClaimType[] {
  switch (itemStatusCode) {
    case 'PAID':
    case 'PREPARING':
      return ['CANCEL']
    case 'DELIVERED':
      return ['RETURN', 'EXCHANGE']
    default:
      return []
  }
}

/** 반품 요청 사유(BE ClaimReasonCode.isApplicableTo(RETURN) 3값·D-170). 그 외 사유로 반품 요청 시 BE 422. */
export const RETURN_REASON_CODES: ClaimReasonCode[] = ['BUYER_CHANGED_MIND', 'PRODUCT_DEFECT', 'WRONG_PRODUCT']

/** 클레임 유형별 요청 사유 드롭다운 목록(정의 순서 유지). RETURN만 3값으로 제한되고 CANCEL·EXCHANGE는 전량이다. */
export function claimReasonCodesFor(claimType: ClaimType): ClaimReasonCode[] {
  return claimType === 'RETURN' ? RETURN_REASON_CODES : CLAIM_REASON_CODES
}

/** 사진 첨부를 허용하는 반품 사유(BE ClaimService.ATTACHABLE_RETURN_REASONS·D-171). */
export const CLAIM_ATTACHABLE_REASON_CODES: ClaimReasonCode[] = ['PRODUCT_DEFECT', 'WRONG_PRODUCT']

/** 반품 사진 첨부 상한(BE ClaimAttachmentService.MAX_ATTACHMENTS·ClaimRequestRequest @Size). */
export const CLAIM_ATTACHMENT_MAX = 5

/** 사진 첨부 허용 여부 — 반품 + 상품불량·오배송에서만 true(그 외 첨부 시 BE 400). */
export function isClaimAttachmentAllowed(claimType: ClaimType, reasonCode: ClaimReasonCode | ''): boolean {
  return claimType === 'RETURN' && reasonCode !== '' && CLAIM_ATTACHABLE_REASON_CODES.includes(reasonCode)
}

/** 클레임 거부 사유 코드(BE ClaimRejectReasonCode enum 5값·Track 80 D-169 + Track 81-A INSPECTION_FAILED). */
export type ClaimRejectReasonCode = 'ALREADY_SHIPPED' | 'OUT_OF_POLICY' | 'BUYER_WITHDRAWN' | 'OTHER' | 'INSPECTION_FAILED'

/** 거부 사유 코드→한글 라벨. BE SMS 본문 라벨(ClaimRejectReasonCode.getLabel)과 동일 문구로 맞춘다(사용자 혼란 방지). */
export const CLAIM_REJECT_REASON_LABELS: Record<ClaimRejectReasonCode, string> = {
  ALREADY_SHIPPED: '이미 발송됨',
  OUT_OF_POLICY: '정책상 불가',
  BUYER_WITHDRAWN: '구매자 철회',
  OTHER: '기타',
  INSPECTION_FAILED: '검수 불합격',
}

/** 드롭다운 노출용 거부 사유 코드 목록(정의 순서 유지). INSPECTION_FAILED는 검수 경로 전용이라 일반 거부 목록에서 제외한다. */
export const CLAIM_REJECT_REASON_CODES: ClaimRejectReasonCode[] = ['ALREADY_SHIPPED', 'OUT_OF_POLICY', 'BUYER_WITHDRAWN', 'OTHER']

/** 검수 불합격(FAIL) 사유는 INSPECTION_FAILED 고정(D-172 봉인·그 외 BE 400). 일반 거부 목록({@link CLAIM_REJECT_REASON_CODES})과 분리. */
export const CLAIM_INSPECTION_FAIL_REASON_CODE: ClaimRejectReasonCode = 'INSPECTION_FAILED'

/** 검수 결과 code(BE ClaimInspectionResult 2값·Track 81-A). 미검수는 null. */
export type ClaimInspectionResult = 'PASS' | 'FAIL'

export const CLAIM_INSPECTION_RESULT_LABELS: Record<ClaimInspectionResult, string> = {
  PASS: '검수 합격',
  FAIL: '검수 불합격',
}

/** 거부 사유 라벨 변환. 매핑에 없는 값은 원본 폴백(방어). */
export function claimRejectReasonLabel(code: string): string {
  return CLAIM_REJECT_REASON_LABELS[code as ClaimRejectReasonCode] ?? code
}

/**
 * 클레임 유형에서 선택 가능한 거부 사유인지 판정한다. BE Claim.reject 도메인 검증(ALREADY_SHIPPED는 CANCEL 전용·INSPECTION_FAILED는
 * 검수 FAIL 경로 전용이라 일반 거부에서는 유형 무관 400)과 1:1.
 */
export function isClaimRejectReasonApplicable(code: ClaimRejectReasonCode, claimType: ClaimType): boolean {
  if (code === 'ALREADY_SHIPPED') return claimType === 'CANCEL'
  if (code === 'INSPECTION_FAILED') return false
  return true
}

/** 클레임 유형별 거부 사유 드롭다운 목록(정의 순서 유지·유형 부적합 사유 제외). */
export function claimRejectReasonCodesFor(claimType: ClaimType): ClaimRejectReasonCode[] {
  return CLAIM_REJECT_REASON_CODES.filter((code) => isClaimRejectReasonApplicable(code, claimType))
}

/** 거부 메모 최대 길이(BE ClaimRejectRequest @Size·claim.reject_memo VARCHAR(500)). */
export const CLAIM_REJECT_MEMO_MAX = 500

/** 환불 상태 code(BE RefundStatus enum 3값). 클레임 응답 refundStatus는 환불 미생성 시 null. */
export type RefundStatus = 'PENDING' | 'COMPLETED' | 'FAILED'

/** 환불 상태 code→한글 라벨(구매자 시점 문구). */
export const REFUND_STATUS_LABELS: Record<RefundStatus, string> = {
  PENDING: '환불 진행 중',
  COMPLETED: '환불 완료',
  FAILED: '환불 실패',
}

/** 환불 상태 라벨 변환. 매핑에 없는 값은 원본 폴백(방어). */
export function refundStatusLabel(code: string): string {
  return REFUND_STATUS_LABELS[code as RefundStatus] ?? code
}
