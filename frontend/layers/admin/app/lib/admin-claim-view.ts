import type { AdminSemantic } from '#layers/admin/app/lib/constants/semantic'
import { ADMIN_REFUND_STATUS_SEMANTIC } from '#layers/admin/app/lib/constants/admin-order'
import {
  CLAIM_REJECT_MEMO_MAX,
  CLAIM_REJECT_REASON_LABELS,
  claimRejectReasonCodesFor,
  claimTypeLabel,
  refundStatusLabel,
  type ClaimInspectionResult,
  type ClaimRejectReasonCode,
  type ClaimType,
  type RefundStatus,
} from '~/lib/constants/claim'
import { ADMIN_ORDER_TRACKING_NO_MAX, type AdminDeliveryCarrier } from '#layers/admin/app/lib/constants/admin-order'

/**
 * 관리자 클레임 화면 순수 헬퍼(FE-28). 목록·주문 상세·거부 다이얼로그가 공유한다. 라벨 단일 소스는 사용자 constants/claim.ts.
 */

/** 거부 사유 select 항목 — 클레임 유형에 부적합한 사유(ALREADY_SHIPPED는 CANCEL만)는 제외한다(BE 400 예방). */
export function rejectReasonItems(claimType: ClaimType): { value: ClaimRejectReasonCode; title: string }[] {
  return claimRejectReasonCodesFor(claimType).map((code) => ({ value: code, title: CLAIM_REJECT_REASON_LABELS[code] }))
}

export interface RejectFormInput {
  reasonCode: ClaimRejectReasonCode | null
  memo: string
}

/** 거부 폼 검증(사유 필수·메모 500자). BE @Valid와 동일 규칙을 제출 전에 적용해 400 왕복을 줄인다. */
export function validateRejectForm(input: RejectFormInput): Record<string, string> {
  const errors: Record<string, string> = {}
  if (!input.reasonCode) errors.reasonCode = '거부 사유를 선택하세요.'
  if (input.memo.length > CLAIM_REJECT_MEMO_MAX) errors.memo = `메모는 ${CLAIM_REJECT_MEMO_MAX}자 이하여야 합니다.`
  return errors
}

/** 환불 상태 chip(환불 미생성 시 null). CANCEL·RETURN·EXCHANGE 차액 모두 refundStatus 값 그대로 따른다. */
export function refundStatusChip(refundStatus: RefundStatus | undefined): { text: string; semantic: AdminSemantic } | null {
  if (!refundStatus) return null
  return { text: refundStatusLabel(refundStatus), semantic: ADMIN_REFUND_STATUS_SEMANTIC[refundStatus] }
}

/** 승인 확인 다이얼로그 문구(주문 상세·목록 공유). 취소는 승인 즉시 환불이 진행됨을 알린다. */
export function approveConfirmMessage(claimType: ClaimType, productName: string): string {
  const label = `${claimTypeLabel(claimType)} 요청 (${productName})`
  return claimType === 'CANCEL'
    ? `${label}을(를) 승인합니다.\n취소 요청은 승인 즉시 환불이 진행됩니다.`
    : `${label}을(를) 승인합니다.`
}

/** 회수 확인 다이얼로그 문구(FE-29). 회수 확인은 환불을 일으키지 않고 검수 단계로만 넘긴다(D-170). */
export function confirmPickupMessage(productName: string): string {
  return `반품 요청 (${productName})의 회수를 확인합니다.\n회수 확인 후 검수를 진행할 수 있으며, 환불은 검수 합격 시 진행됩니다.`
}

/** 검수 결과 chip(미검수 null). 합격은 재입고 여부를 함께 표기한다. */
export function inspectionChip(
  result: ClaimInspectionResult | undefined,
  restock: boolean | undefined,
): { text: string; semantic: AdminSemantic } | null {
  if (!result) return null
  if (result === 'FAIL') return { text: '검수 불합격', semantic: 'danger' }
  return { text: restock ? '검수 합격 · 재입고' : '검수 합격 · 폐기', semantic: 'success' }
}

export interface InspectFormInput {
  result: ClaimInspectionResult | null
  restock: boolean | null
  memo: string
  reshipCarrier: AdminDeliveryCarrier | null
  reshipTrackingNo: string
}

/**
 * 검수 폼 검증(FE-29·D-172). BE ClaimInspectRequest 조건부 필수(PASS: restock / FAIL: 재발송 택배사·송장)와 동일 규칙을 제출 전에 적용한다.
 * 불합격 사유는 INSPECTION_FAILED 고정이라 입력이 없다. 송장 규칙은 송장 등록 폼(validateShipmentForm)과 같다(≤100).
 */
export function validateInspectForm(input: InspectFormInput): Record<string, string> {
  const errors: Record<string, string> = {}
  if (!input.result) {
    errors.result = '검수 결과를 선택하세요.'
    return errors
  }
  if (input.result === 'PASS') {
    if (input.restock === null) errors.restock = '재입고 여부를 선택하세요.'
    return errors
  }
  if (input.memo.length > CLAIM_REJECT_MEMO_MAX) errors.memo = `메모는 ${CLAIM_REJECT_MEMO_MAX}자 이하여야 합니다.`
  if (!input.reshipCarrier) errors.reshipCarrier = '재발송 택배사를 선택하세요.'
  const trackingNo = input.reshipTrackingNo.trim()
  if (trackingNo === '') errors.reshipTrackingNo = '재발송 송장번호를 입력하세요.'
  else if (trackingNo.length > ADMIN_ORDER_TRACKING_NO_MAX) {
    errors.reshipTrackingNo = `송장번호는 ${ADMIN_ORDER_TRACKING_NO_MAX}자 이하여야 합니다.`
  }
  return errors
}
