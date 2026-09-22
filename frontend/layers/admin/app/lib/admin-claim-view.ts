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
  type ClaimStatus,
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

/** 승인 확인 다이얼로그 문구(주문 상세·목록 공유). 취소는 승인 즉시 환불, 교환은 교환 옵션 재고 예약(부족 시 422)을 알린다(FE-30·D-177). */
export function approveConfirmMessage(claimType: ClaimType, productName: string): string {
  const label = `${claimTypeLabel(claimType)} 요청 (${productName})`
  if (claimType === 'CANCEL') return `${label}을(를) 승인합니다.\n취소 요청은 승인 즉시 환불이 진행됩니다.`
  if (claimType === 'EXCHANGE') return `${label}을(를) 승인합니다.\n승인 시 교환 옵션 재고가 예약되며, 재고가 부족하면 승인되지 않습니다.`
  return `${label}을(를) 승인합니다.`
}

/** 회수 확인 다이얼로그 문구(FE-29·FE-30). 회수 확인은 환불·발송을 일으키지 않고 검수 단계로만 넘긴다(D-170·D-177). */
export function confirmPickupMessage(productName: string, claimType: ClaimType = 'RETURN'): string {
  if (claimType === 'EXCHANGE') {
    return `교환 요청 (${productName})의 회수를 확인합니다.\n회수 확인 후 검수를 진행할 수 있으며, 교환품 발송은 검수 합격 후 등록합니다.`
  }
  return `반품 요청 (${productName})의 회수를 확인합니다.\n회수 확인 후 검수를 진행할 수 있으며, 환불은 검수 합격 시 진행됩니다.`
}

/** 검수 다이얼로그의 합격 의미(FE-30): 반품은 환불 자동 진행, 교환은 교환품 발송 대기. */
export function inspectPassLabel(claimType: ClaimType): string {
  return claimType === 'EXCHANGE' ? '합격 (교환품 발송 대기)' : '합격 (환불 진행)'
}

/** 검수 다이얼로그 제목(FE-61). 본문·합격 라벨과 같은 기준으로 유형을 구분한다. */
export function inspectDialogTitle(claimType: ClaimType): string {
  return claimType === 'EXCHANGE' ? '교환 검수' : '반품 검수'
}

/**
 * 검수 합격 직후 교환품 발송 다이얼로그를 이어 열 조건(FE-61). 교환 + 합격 + 갱신된 행이 실제로 발송 등록을 허용할 때만 연다 —
 * 세 조건 중 하나라도 어긋나면(반품·불합격·경합으로 액션이 사라짐) 현행대로 목록에 머문다.
 *
 * @param claimType        갱신된 행의 클레임 유형
 * @param result           방금 처리한 검수 결과
 * @param availableActions 갱신된 행의 BE 처리 가능 액션
 */
export function shouldChainExchangeShipment(claimType: ClaimType, result: ClaimInspectionResult,
        availableActions: string[]): boolean {
  return claimType === 'EXCHANGE' && result === 'PASS' && availableActions.includes('REGISTER_EXCHANGE_SHIPMENT')
}

export function inspectPassToast(claimType: ClaimType): string {
  return claimType === 'EXCHANGE' ? '검수 합격 처리했습니다. 교환품 발송을 등록하세요.' : '검수 합격 처리했습니다. 환불이 진행됩니다.'
}

export interface ExchangeShipmentFormInput {
  carrier: AdminDeliveryCarrier | null
  trackingNo: string
}

/** 교환품 발송 폼 검증(FE-30). 송장 규칙은 검수 FAIL 재발송·품목 발송 폼과 같다(택배사 필수·송장 1~100자). */
export function validateExchangeShipmentForm(input: ExchangeShipmentFormInput): Record<string, string> {
  const errors: Record<string, string> = {}
  if (!input.carrier) errors.carrier = '택배사를 선택하세요.'
  const trackingNo = input.trackingNo.trim()
  if (trackingNo === '') errors.trackingNo = '송장번호를 입력하세요.'
  else if (trackingNo.length > ADMIN_ORDER_TRACKING_NO_MAX) errors.trackingNo = `송장번호는 ${ADMIN_ORDER_TRACKING_NO_MAX}자 이하여야 합니다.`
  return errors
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

/** 회수 대기 판정·표시에 필요한 행 필드만 추린 입력(테스트에서 목록 행 전체를 만들지 않도록·Track 101-A). */
export interface PickupWaitingInput {
  type: ClaimType
  status: ClaimStatus
  pickedUpAt?: string
  returnShipment?: unknown
}

/**
 * 구매자 회수 송장 등록을 기다리는 행인지 판정한다(Track 101-A). 승인된 반품·교환인데 회수 송장도 회수 확인도 없는 상태로,
 * BE availableActions가 빈 목록을 내려 화면에 "—"만 남던 구간이다(AdminClaimQueryService.availableActions 1:1).
 * 다른 이유로 액션이 없는 행(완료·거부 등)은 false다.
 */
export function isWaitingForReturnShipment(item: PickupWaitingInput): boolean {
  if (item.type !== 'RETURN' && item.type !== 'EXCHANGE') return false
  return item.status === 'APPROVED' && !item.pickedUpAt && !item.returnShipment
}

/** 회수 대기 행의 "관리" 칸 안내 문구. 대기 상태가 아니면 null(호출부가 기존 "—" 표기를 유지한다). */
export function pickupWaitingLabel(item: PickupWaitingInput): string | null {
  return isWaitingForReturnShipment(item) ? '구매자 회수 송장 등록 대기' : null
}
