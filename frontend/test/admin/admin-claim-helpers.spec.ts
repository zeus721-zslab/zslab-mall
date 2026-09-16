import { describe, it, expect } from 'vitest'
import {
  approveConfirmMessage,
  refundStatusChip,
  rejectReasonItems,
  validateRejectForm,
} from '#layers/admin/app/lib/admin-claim-view'
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
      ALREADY_SHIPPED: '이미 발송됨', OUT_OF_POLICY: '정책상 불가', BUYER_WITHDRAWN: '구매자 철회', OTHER: '기타',
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

  it('approveConfirmMessage: 취소만 환불 안내 문구', () => {
    expect(approveConfirmMessage('CANCEL', '상품A')).toContain('승인 즉시 환불이 진행됩니다.')
    expect(approveConfirmMessage('RETURN', '상품A')).not.toContain('환불')
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
