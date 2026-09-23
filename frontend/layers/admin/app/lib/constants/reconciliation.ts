import type { AdminSemantic } from '#layers/admin/app/lib/constants/semantic'
import { ADMIN_ORDER_PAGE_SIZES, DEFAULT_ADMIN_ORDER_PAGE_SIZE } from '#layers/admin/app/lib/constants/admin-order'

/**
 * 주문·결제 불일치 상수 단일 소스(Track 104-2 D-216·FE-66·CLAUDE.md 4층위 enum 잠금 (4)프론트). 유형·상태는 BE
 * {@code ReconciliationIssueType}·{@code ReconciliationIssueStatus}와 V35 DDL ENUM과 1:1이다(값을 바꾸면 넷을 함께 고친다).
 */

/** BE ReconciliationIssueType 12값. */
export type ReconciliationIssueType =
  | 'PG_PAYMENT_SUCCESS_CONFLICT'
  | 'PG_PAYMENT_CANCEL_ON_PAID'
  | 'PG_TID_CONFLICT'
  | 'PG_UNMATCHED_CALLBACK'
  | 'PG_REFUND_EXCEEDS_PAYMENT'
  | 'PG_REFUND_SUCCESS_ON_FAILED'
  | 'PAYMENT_CANCELLED_WITHOUT_REFUND'
  | 'FULL_REFUND_PAYMENT_NOT_CANCELLED'
  | 'FULL_REFUND_WITH_CONFIRMED_ITEM'
  | 'REFUND_ON_INVALID_CLAIM'
  | 'ITEM_STATE_DRIFT'
  | 'PG_REFUND_FAIL_ON_COMPLETED'

export const RECONCILIATION_ISSUE_TYPE_LABEL: Record<ReconciliationIssueType, string> = {
  PG_PAYMENT_SUCCESS_CONFLICT: 'PG 결제 성공 충돌',
  PG_PAYMENT_CANCEL_ON_PAID: '결제완료 건의 PG 취소 통지',
  PG_TID_CONFLICT: 'PG 거래번호 중복',
  PG_UNMATCHED_CALLBACK: '대상 없는 PG 통지',
  PG_REFUND_EXCEEDS_PAYMENT: '결제액 초과 환불',
  PG_REFUND_SUCCESS_ON_FAILED: '실패 처리된 환불의 PG 성공',
  PAYMENT_CANCELLED_WITHOUT_REFUND: '환불 없이 결제 취소',
  FULL_REFUND_PAYMENT_NOT_CANCELLED: '전액 환불·결제 미취소',
  FULL_REFUND_WITH_CONFIRMED_ITEM: '구매확정 품목 있는 전액 환불',
  REFUND_ON_INVALID_CLAIM: '환불 대상 아닌 클레임의 환불',
  ITEM_STATE_DRIFT: '품목 상태 어긋남',
  PG_REFUND_FAIL_ON_COMPLETED: '완료된 환불의 PG 실패 통지',
}

export const RECONCILIATION_ISSUE_TYPE_OPTIONS: { value: ReconciliationIssueType; title: string }[] = (
  Object.keys(RECONCILIATION_ISSUE_TYPE_LABEL) as ReconciliationIssueType[]
).map((value) => ({ value, title: RECONCILIATION_ISSUE_TYPE_LABEL[value] }))

/** BE ReconciliationIssueStatus 2값. */
export type ReconciliationIssueStatus = 'OPEN' | 'RESOLVED'

export const RECONCILIATION_ISSUE_STATUS_LABEL: Record<ReconciliationIssueStatus, string> = {
  OPEN: '확인 필요',
  RESOLVED: '해결됨',
}

export const RECONCILIATION_ISSUE_STATUS_SEMANTIC: Record<ReconciliationIssueStatus, AdminSemantic> = {
  OPEN: 'warning',
  RESOLVED: 'success',
}

export const RECONCILIATION_ISSUE_STATUS_OPTIONS: { value: ReconciliationIssueStatus; title: string }[] = (
  ['OPEN', 'RESOLVED'] as ReconciliationIssueStatus[]
).map((value) => ({ value, title: RECONCILIATION_ISSUE_STATUS_LABEL[value] }))

/**
 * 불일치 세부 사유(detail.reason) 라벨. BE 기록 지점이 넣는 문자열 코드(PaymentService·RefundService·점검 패턴 ReconciliationCheckPattern)와
 * 1:1이다. 모르는 코드는 화면이 원문을 그대로 보인다(사유 코드는 DB 컬럼이 아니라 세부 JSON 값이라 잠금 대상이 아니다).
 */
export const RECONCILIATION_REASON_LABEL: Record<string, string> = {
  DUPLICATE_PAID_PAYMENT: '같은 주문에 결제완료 건이 이미 있는데 다른 결제의 성공 통지가 왔습니다',
  ORDER_NOT_PENDING_PAYMENT: '결제대기가 아닌 주문(만료·취소)에 결제 성공 통지가 왔습니다',
  ITEM_NOT_PAYABLE: '결제할 수 없는 상태의 품목이 있는 주문에 결제 성공 통지가 왔습니다',
  TERMINAL_PAYMENT: '이미 끝난 결제(실패·취소·만료)에 성공 통지가 왔습니다',
  PG_TID_ALREADY_USED: '다른 결제에 이미 기록된 PG 거래번호로 성공 통지가 왔습니다',
  CANCEL_ON_PAID: '결제완료 결제에 PG 취소 통지가 왔습니다(환불 없이 결제를 취소하지 않았습니다)',
  NO_MATCHING_PAYMENT: '일치하는 결제 시도가 없는 결제 통지입니다',
  NO_MATCHING_REFUND: '일치하는 환불이 없는 환불 통지입니다',
  REFUND_ALREADY_FAILED: '실패 처리한 환불에 PG 환불 성공 통지가 왔습니다',
  REFUND_ALREADY_COMPLETED: '완료 처리한 환불에 PG 환불 실패 통지가 왔습니다(환불은 완료 상태로 두었습니다)',
  PAY1_EXCEEDED: '환불 완료 합계가 결제액을 넘어 환불을 완료 처리하지 않았습니다',
  PAYMENT_NOT_CANCELLABLE: '전액 환불인데 결제를 취소 상태로 바꿀 수 없어 환불을 완료 처리하지 않았습니다',
  CLAIM_NOT_REFUNDABLE: '교환 또는 거부된 클레임에 환불이 완료됐습니다',
  FULL_REFUND_WITH_CONFIRMED_ITEM: '구매확정 품목이 있는 주문의 결제가 전액 환불됐습니다',
  PAYMENT_CANCELLED_WITHOUT_REFUND: '환불·클레임 없이 결제만 취소 상태입니다',
  FULL_REFUND_PAYMENT_NOT_CANCELLED: '환불 합계가 결제액과 같은데 결제가 결제완료로 남아 있습니다',
  CLAIM_COMPLETED_ITEM_NOT_TRANSITIONED: '클레임은 완료됐는데 품목이 요청 상태에 머물러 있습니다',
  CLAIM_REJECTED_ITEM_NOT_RESTORED: '클레임은 거부됐는데 품목이 요청 상태에 머물러 있습니다',
  SHIPPED_ITEM_NOT_TRANSITIONED: '배송중인데 품목이 발송 전 상태에 머물러 있습니다',
  DELIVERED_ITEM_NOT_TRANSITIONED: '배송완료인데 품목이 배송완료 전 상태에 머물러 있습니다',
}

/** 해결 메모 최대 길이(BE AdminReconciliationIssueResolveRequest @Size(max=500)·resolution_memo VARCHAR(500)). */
export const RECONCILIATION_RESOLUTION_MEMO_MAX = 500

export const RECONCILIATION_PAGE_SIZES: number[] = ADMIN_ORDER_PAGE_SIZES
export const DEFAULT_RECONCILIATION_PAGE_SIZE = DEFAULT_ADMIN_ORDER_PAGE_SIZE
