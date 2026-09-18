import { describe, it, expect } from 'vitest'
import {
  approveConfirmMessage,
  confirmPickupMessage,
  inspectPassLabel,
  inspectPassToast,
  inspectionChip,
  refundStatusChip,
  rejectReasonItems,
  validateExchangeShipmentForm,
  validateInspectForm,
  validateRejectForm,
} from '#layers/admin/app/lib/admin-claim-view'
import { ADMIN_CLAIM_ACTION_LABEL } from '#layers/admin/app/lib/constants/admin-claim'
import { claimRefundLabel } from '#layers/admin/app/lib/admin-order-view'
import { ADMIN_CLAIMS_PATH, ADMIN_ORDERS_PATH, ADMIN_PRODUCTS_PATH, resolveBackPath } from '#layers/admin/app/lib/admin-back-path'
import { ADMIN_REFUND_STATUS_SEMANTIC } from '#layers/admin/app/lib/constants/admin-order'
import {
  CLAIM_REJECT_MEMO_MAX,
  CLAIM_REJECT_REASON_CODES,
  CLAIM_REJECT_REASON_LABELS,
  REFUND_STATUS_LABELS,
  claimRejectReasonCodesFor,
  isClaimRejectReasonApplicable,
} from '~/lib/constants/claim'

// FE-28: 거부 사유 4층위 ④(코드·라벨·유형 제약)·환불 라벨·복귀 경로·거부 폼 검증.
describe('거부 사유 상수(claim.ts)', () => {
  it('4값·라벨은 BE SMS 문구와 동일', () => {
    expect(CLAIM_REJECT_REASON_CODES).toEqual(['ALREADY_SHIPPED', 'OUT_OF_POLICY', 'BUYER_WITHDRAWN', 'OTHER'])
    expect(CLAIM_REJECT_REASON_LABELS).toEqual({
      ALREADY_SHIPPED: '이미 발송됨', OUT_OF_POLICY: '정책상 불가', BUYER_WITHDRAWN: '구매자 철회', OTHER: '기타', INSPECTION_FAILED: '검수 불합격',
    })
  })

  it('ALREADY_SHIPPED는 CANCEL만·나머지는 유형 공용(BE Claim.reject 검증 1:1)', () => {
    expect(isClaimRejectReasonApplicable('ALREADY_SHIPPED', 'CANCEL')).toBe(true)
    expect(isClaimRejectReasonApplicable('ALREADY_SHIPPED', 'RETURN')).toBe(false)
    expect(isClaimRejectReasonApplicable('ALREADY_SHIPPED', 'EXCHANGE')).toBe(false)
    expect(claimRejectReasonCodesFor('CANCEL')).toHaveLength(4)
    expect(claimRejectReasonCodesFor('RETURN')).toEqual(['OUT_OF_POLICY', 'BUYER_WITHDRAWN', 'OTHER'])
    expect(claimRejectReasonCodesFor('EXCHANGE')).toEqual(['OUT_OF_POLICY', 'BUYER_WITHDRAWN', 'OTHER'])
  })

  it('환불 상태 라벨 3값·chip 의미 색', () => {
    expect(REFUND_STATUS_LABELS).toEqual({ PENDING: '환불 진행 중', COMPLETED: '환불 완료', FAILED: '환불 실패' })
    expect(ADMIN_REFUND_STATUS_SEMANTIC).toEqual({ PENDING: 'warning', COMPLETED: 'success', FAILED: 'danger' })
  })
})

describe('admin-claim-view', () => {
  it('rejectReasonItems: 취소 탭 4개(이미 발송됨 포함)·반품/교환 3개', () => {
    expect(rejectReasonItems('CANCEL').map((item) => item.value)).toEqual(CLAIM_REJECT_REASON_CODES)
    expect(rejectReasonItems('CANCEL')[0]).toEqual({ value: 'ALREADY_SHIPPED', title: '이미 발송됨' })
    expect(rejectReasonItems('RETURN').map((item) => item.value)).not.toContain('ALREADY_SHIPPED')
  })

  it('validateRejectForm: 사유 필수·메모 500자', () => {
    expect(validateRejectForm({ reasonCode: null, memo: '' })).toEqual({ reasonCode: '거부 사유를 선택하세요.' })
    expect(validateRejectForm({ reasonCode: 'OTHER', memo: 'a'.repeat(CLAIM_REJECT_MEMO_MAX) })).toEqual({})
    expect(validateRejectForm({ reasonCode: 'OTHER', memo: 'a'.repeat(CLAIM_REJECT_MEMO_MAX + 1) })).toHaveProperty('memo')
  })

  it('refundStatusChip: 값 없으면 null·있으면 라벨+의미', () => {
    expect(refundStatusChip(undefined)).toBeNull()
    expect(refundStatusChip('COMPLETED')).toEqual({ text: '환불 완료', semantic: 'success' })
    expect(refundStatusChip('FAILED')).toEqual({ text: '환불 실패', semantic: 'danger' })
  })

  it('approveConfirmMessage: 취소만 환불 안내 문구·교환은 재고 예약 안내(FE-30)', () => {
    expect(approveConfirmMessage('CANCEL', '상품A')).toContain('승인 즉시 환불이 진행됩니다.')
    expect(approveConfirmMessage('RETURN', '상품A')).not.toContain('환불')
    expect(approveConfirmMessage('EXCHANGE', '상품A')).toContain('교환 옵션 재고가 예약')
  })

  it('교환 문구·액션(FE-30): 회수 확인/검수 합격 문구 유형 분기·액션 라벨 7종(환불 개시 포함)·교환 발송 폼 검증', () => {
    expect(confirmPickupMessage('상품A')).toContain('환불은 검수 합격 시')
    expect(confirmPickupMessage('상품A', 'EXCHANGE')).toContain('교환품 발송은 검수 합격 후')
    expect(inspectPassLabel('RETURN')).toBe('합격 (환불 진행)')
    expect(inspectPassLabel('EXCHANGE')).toBe('합격 (교환품 발송 대기)')
    expect(inspectPassToast('EXCHANGE')).toContain('교환품 발송을 등록')
    expect(Object.keys(ADMIN_CLAIM_ACTION_LABEL)).toEqual(['APPROVE', 'REJECT', 'CONFIRM_PICKUP', 'INSPECT', 'REGISTER_EXCHANGE_SHIPMENT', 'MARK_EXCHANGE_DELIVERED', 'INITIATE_REFUND'])
    expect(ADMIN_CLAIM_ACTION_LABEL.INITIATE_REFUND).toBe('환불 개시') // Track 89-A
    expect(ADMIN_CLAIM_ACTION_LABEL.REGISTER_EXCHANGE_SHIPMENT).toBe('교환품 발송')
    expect(validateExchangeShipmentForm({ carrier: null, trackingNo: '' })).toEqual({ carrier: '택배사를 선택하세요.', trackingNo: '송장번호를 입력하세요.' })
    expect(validateExchangeShipmentForm({ carrier: 'CJ', trackingNo: 'X'.repeat(101) }).trackingNo).toContain('100자')
    expect(validateExchangeShipmentForm({ carrier: 'CJ', trackingNo: ' 1234 ' })).toEqual({})
  })

  it('claimRefundLabel: refundStatus 우선·없으면 취소 상태 추론 폴백(FE-27 회귀)', () => {
    expect(claimRefundLabel({ type: 'RETURN', status: 'COMPLETED', refundStatus: 'COMPLETED' })).toEqual({ text: '환불 완료', semantic: 'success' })
    expect(claimRefundLabel({ type: 'CANCEL', status: 'APPROVED', refundStatus: 'FAILED' })).toEqual({ text: '환불 실패', semantic: 'danger' })
    expect(claimRefundLabel({ type: 'CANCEL', status: 'APPROVED' })).toEqual({ text: '환불 진행 중', semantic: 'warning' })
    expect(claimRefundLabel({ type: 'RETURN', status: 'APPROVED' })).toBeNull()
  })
})

describe('resolveBackPath 클레임 목록 허용(FE-28)', () => {
  it('주문 base: 클레임 목록(쿼리 포함)도 그대로·상품·외부는 주문 목록 기본', () => {
    expect(resolveBackPath('/admin/orders/claims?type=CANCEL&page=1', ADMIN_ORDERS_PATH)).toBe('/admin/orders/claims?type=CANCEL&page=1')
    expect(resolveBackPath(ADMIN_CLAIMS_PATH, ADMIN_ORDERS_PATH)).toBe(ADMIN_CLAIMS_PATH)
    expect(resolveBackPath('/admin/orders?status=PAID', ADMIN_ORDERS_PATH)).toBe('/admin/orders?status=PAID')
    expect(resolveBackPath('/admin/orders/claimsX', ADMIN_ORDERS_PATH)).toBe(ADMIN_ORDERS_PATH)
    expect(resolveBackPath('/admin/products?page=2', ADMIN_ORDERS_PATH)).toBe(ADMIN_ORDERS_PATH)
  })

  it('상품 base는 클레임 목록을 허용하지 않는다(FE-26 회귀)', () => {
    expect(resolveBackPath(ADMIN_CLAIMS_PATH, ADMIN_PRODUCTS_PATH)).toBe(ADMIN_PRODUCTS_PATH)
    expect(resolveBackPath('/admin/products?status=SALE')).toBe('/admin/products?status=SALE')
  })
})

// FE-29: 반품 회수 확인·검수 헬퍼(문구·chip·FAIL 사유 목록·검수 폼 조건부 필수).
describe('반품 회수·검수 헬퍼(admin-claim-view.ts·FE-29)', () => {
  it('회수 확인 문구는 환불이 검수 합격 시 진행됨을 알린다', () => {
    expect(confirmPickupMessage('E2E 양말')).toContain('반품 요청 (E2E 양말)')
    expect(confirmPickupMessage('E2E 양말')).toContain('검수 합격 시')
  })

  it('검수 chip: 미검수 null·합격은 재입고/폐기 구분(success)·불합격 danger', () => {
    expect(inspectionChip(undefined, undefined)).toBeNull()
    expect(inspectionChip('PASS', true)).toEqual({ text: '검수 합격 · 재입고', semantic: 'success' })
    expect(inspectionChip('PASS', false)).toEqual({ text: '검수 합격 · 폐기', semantic: 'success' })
    expect(inspectionChip('FAIL', undefined)).toEqual({ text: '검수 불합격', semantic: 'danger' })
  })

  it('일반 거부 목록에는 INSPECTION_FAILED 없음(검수 FAIL 사유는 고정 상수·D-172)', () => {
    expect(rejectReasonItems('RETURN').map((item) => item.value)).not.toContain('INSPECTION_FAILED')
  })

  it('검수 폼: 결과 필수 → PASS는 재입고 필수 → FAIL은 재발송 택배사·송장(≤100) 필수·메모 500(사유 입력 없음)', () => {
    const base = { result: null, restock: null, memo: '', reshipCarrier: null, reshipTrackingNo: '' }
    expect(validateInspectForm(base)).toEqual({ result: '검수 결과를 선택하세요.' })
    expect(validateInspectForm({ ...base, result: 'PASS' })).toEqual({ restock: '재입고 여부를 선택하세요.' })
    expect(validateInspectForm({ ...base, result: 'PASS', restock: false })).toEqual({})
    const fail = validateInspectForm({ ...base, result: 'FAIL' })
    expect(Object.keys(fail).sort()).toEqual(['reshipCarrier', 'reshipTrackingNo'])
    expect(validateInspectForm({ ...base, result: 'FAIL', reshipCarrier: 'CJ', reshipTrackingNo: ' R-1 ' })).toEqual({})
    expect(validateInspectForm({ ...base, result: 'FAIL', reshipCarrier: 'CJ', reshipTrackingNo: 'x'.repeat(101) }).reshipTrackingNo).toContain('100자')
    expect(validateInspectForm({ ...base, result: 'FAIL', memo: 'm'.repeat(501), reshipCarrier: 'CJ', reshipTrackingNo: 'R' }).memo).toContain('500자')
  })
})
