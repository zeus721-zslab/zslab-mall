import { describe, it, expect } from 'vitest'
import {
  CLAIM_ATTACHMENT_MAX,
  CLAIM_INSPECTION_FAIL_REASON_CODES,
  CLAIM_REJECT_REASON_CODES,
  CLAIM_REJECT_REASON_LABELS,
  RETURN_REASON_CODES,
  claimReasonCodesFor,
  claimRejectReasonCodesFor,
  claimableTypes,
  isClaimAttachmentAllowed,
  isClaimRejectReasonApplicable,
} from '~/lib/constants/claim'
import { claimTimeline } from '~/lib/utils/claim-timeline'
import { precheckClaimAttachments, uploadItemErrorMessage } from '~/lib/utils/claim-attachment'
import { claimRequestErrorMessage } from '~/lib/utils/claim-request-error'
import type { ClaimDetail } from '~/types/claim'

// FE-29: 반품 요청 조건(사유 3값·DELIVERED만·첨부 허용 사유)·거부 사유 5값(INSPECTION_FAILED 일반 거부 제외)·타임라인·업로드 검증·422 문구.
describe('claimableTypes / 반품 사유(claim.ts)', () => {
  it('SHIPPING은 반품 버튼 없음·DELIVERED만 RETURN·EXCHANGE(D-170 DELIVERED 한정)', () => {
    expect(claimableTypes('SHIPPING')).toEqual([])
    expect(claimableTypes('DELIVERED')).toEqual(['RETURN', 'EXCHANGE'])
    expect(claimableTypes('PAID')).toEqual(['CANCEL'])
    expect(claimableTypes('CONFIRMED')).toEqual([])
  })

  it('반품 사유는 3값·취소/교환은 전량(BE ClaimReasonCode.isApplicableTo 1:1)', () => {
    expect(claimReasonCodesFor('RETURN')).toEqual(['BUYER_CHANGED_MIND', 'PRODUCT_DEFECT', 'WRONG_PRODUCT'])
    expect(claimReasonCodesFor('RETURN')).toBe(RETURN_REASON_CODES)
    expect(claimReasonCodesFor('CANCEL')).toHaveLength(10)
    expect(claimReasonCodesFor('EXCHANGE')).toHaveLength(10)
  })

  it('사진 첨부는 반품 + 상품불량/오배송에서만(D-171·그 외 BE 400)', () => {
    expect(isClaimAttachmentAllowed('RETURN', 'PRODUCT_DEFECT')).toBe(true)
    expect(isClaimAttachmentAllowed('RETURN', 'WRONG_PRODUCT')).toBe(true)
    expect(isClaimAttachmentAllowed('RETURN', 'BUYER_CHANGED_MIND')).toBe(false)
    expect(isClaimAttachmentAllowed('RETURN', '')).toBe(false)
    expect(isClaimAttachmentAllowed('CANCEL', 'PRODUCT_DEFECT')).toBe(false)
    expect(isClaimAttachmentAllowed('EXCHANGE', 'PRODUCT_DEFECT')).toBe(false)
    expect(CLAIM_ATTACHMENT_MAX).toBe(5)
  })
})

describe('거부 사유 INSPECTION_FAILED(claim.ts)', () => {
  it('라벨 "검수 불합격"·일반 거부 목록 4값 유지·검수 FAIL 목록은 INSPECTION_FAILED가 첫 항목', () => {
    expect(CLAIM_REJECT_REASON_LABELS.INSPECTION_FAILED).toBe('검수 불합격')
    expect(CLAIM_REJECT_REASON_CODES).toEqual(['ALREADY_SHIPPED', 'OUT_OF_POLICY', 'BUYER_WITHDRAWN', 'OTHER'])
    expect(CLAIM_INSPECTION_FAIL_REASON_CODES[0]).toBe('INSPECTION_FAILED')
    expect(CLAIM_INSPECTION_FAIL_REASON_CODES).not.toContain('ALREADY_SHIPPED')
  })

  it('일반 거부 드롭다운에서 INSPECTION_FAILED는 유형 무관 제외(BE 일반 reject 400)', () => {
    expect(isClaimRejectReasonApplicable('INSPECTION_FAILED', 'RETURN')).toBe(false)
    expect(isClaimRejectReasonApplicable('INSPECTION_FAILED', 'CANCEL')).toBe(false)
    expect(claimRejectReasonCodesFor('RETURN')).toEqual(['OUT_OF_POLICY', 'BUYER_WITHDRAWN', 'OTHER'])
    expect(claimRejectReasonCodesFor('CANCEL')).not.toContain('INSPECTION_FAILED')
  })
})

function returnDetail(overrides: Partial<ClaimDetail>): ClaimDetail {
  return {
    publicId: 'clm_1', orderItemPublicId: 'oit_1', claimType: 'RETURN', status: 'REQUESTED', reasonCode: 'PRODUCT_DEFECT',
    reasonDetail: null, requestedAt: '2026-09-10T10:00:00+09:00', processedAt: null, rejectReasonCode: null, rejectMemo: null,
    refundStatus: null, returnShipmentRequired: false, attachmentUrls: [], ...overrides,
  }
}
const shipment = { deliveryPublicId: 'dlv_1', direction: 'RETURN' as const, carrier: 'CJ' as const, trackingNo: 'R1', status: 'SHIPPING' as const, shippedAt: '2026-09-11T10:00:00+09:00', deliveredAt: null }

describe('claimTimeline(claim-timeline.ts)', () => {
  const labels = (detail: ClaimDetail) => claimTimeline(detail).map((step) => `${step.label}:${step.state}`)

  it('반품 6단 — 요청/승인/회수 송장/회수 확인/검수/환불 완료 단계 판정', () => {
    expect(labels(returnDetail({}))).toEqual(['요청:current', '승인:upcoming', '회수 송장:upcoming', '회수 확인:upcoming', '검수:upcoming', '환불 완료:upcoming'])
    expect(labels(returnDetail({ status: 'APPROVED', returnShipmentRequired: true }))[1]).toBe('승인:current')
    expect(labels(returnDetail({ status: 'APPROVED', returnShipment: shipment }))[2]).toBe('회수 송장:current')
    expect(labels(returnDetail({ status: 'APPROVED', returnShipment: shipment, pickedUpAt: '2026-09-12T10:00:00+09:00' }))[3]).toBe('회수 확인:current')
    expect(labels(returnDetail({ status: 'APPROVED', returnShipment: shipment, pickedUpAt: '2026-09-12T10:00:00+09:00', inspectionResult: 'PASS', refundStatus: 'PENDING' }))[4]).toBe('검수:current')
    const done = labels(returnDetail({ status: 'COMPLETED', returnShipment: shipment, pickedUpAt: '2026-09-12T10:00:00+09:00', inspectionResult: 'PASS', refundStatus: 'COMPLETED', processedAt: '2026-09-13T10:00:00+09:00' }))
    expect(done).toEqual(['요청:done', '승인:done', '회수 송장:done', '회수 확인:done', '검수:done', '환불 완료:current'])
  })

  it('단계 시각: 요청·회수 송장(shippedAt)·회수 확인(pickedUpAt)·완료(processedAt)만·승인/검수는 null', () => {
    const steps = claimTimeline(returnDetail({ status: 'COMPLETED', returnShipment: shipment, pickedUpAt: '2026-09-12T10:00:00+09:00', inspectionResult: 'PASS', processedAt: '2026-09-13T10:00:00+09:00' }))
    expect(steps.map((step) => step.at)).toEqual(['2026-09-10T10:00:00+09:00', null, '2026-09-11T10:00:00+09:00', '2026-09-12T10:00:00+09:00', null, '2026-09-13T10:00:00+09:00'])
  })

  it('검수 불합격은 5단 종결("검수 불합격" current·processedAt) / 승인 전 거절은 2단', () => {
    const failed = labels(returnDetail({ status: 'REJECTED', inspectionResult: 'FAIL', rejectReasonCode: 'INSPECTION_FAILED', returnShipment: shipment, pickedUpAt: '2026-09-12T10:00:00+09:00', processedAt: '2026-09-13T10:00:00+09:00' }))
    expect(failed).toEqual(['요청:done', '승인:done', '회수 송장:done', '회수 확인:done', '검수 불합격:current'])
    expect(labels(returnDetail({ status: 'REJECTED', rejectReasonCode: 'OUT_OF_POLICY' }))).toEqual(['요청:done', '거절:current'])
  })

  it('취소·교환은 기존 3단 유지', () => {
    expect(labels(returnDetail({ claimType: 'CANCEL', status: 'APPROVED' }))).toEqual(['요청:done', '승인:current', '완료:upcoming'])
    expect(labels(returnDetail({ claimType: 'EXCHANGE', status: 'COMPLETED' }))).toEqual(['요청:done', '승인:done', '완료:current'])
  })
})

describe('precheckClaimAttachments(claim-attachment.ts)', () => {
  const file = (name: string, type: string, size: number) => {
    const blob = new File([new Uint8Array(1)], name, { type })
    Object.defineProperty(blob, 'size', { value: size })
    return blob
  }

  it('형식·10MB·5장(현재 + 추가) 제한', () => {
    const { accepted, rejected } = precheckClaimAttachments([
      file('a.png', 'image/png', 100), file('b.gif', 'image/gif', 100), file('c.jpg', 'image/jpeg', 10 * 1024 * 1024 + 1),
    ], 0)
    expect(accepted.map((entry) => entry.name)).toEqual(['a.png'])
    expect(rejected.map((entry) => entry.reason)).toEqual(['jpg·png·webp만 첨부할 수 있습니다.', '파일당 10MB를 초과합니다.'])

    const over = precheckClaimAttachments([file('d.png', 'image/png', 1), file('e.png', 'image/png', 1)], 4)
    expect(over.accepted).toHaveLength(1)
    expect(over.rejected[0]!.reason).toContain('최대 5장')
  })

  it('서버 파일별 실패 코드 → 문구', () => {
    expect(uploadItemErrorMessage({ success: false, code: 'UNSUPPORTED_FORMAT' })).toContain('jpg·png·webp')
    expect(uploadItemErrorMessage({ success: false, message: '서버 문구' })).toBe('서버 문구')
    expect(uploadItemErrorMessage({ success: false })).toBe('업로드에 실패했습니다.')
  })
})

describe('claimRequestErrorMessage(claim-request-error.ts)', () => {
  it('422 detail 부분 일치로 기한·사유·불합격 이력·미배송완료 구분·그 외 일반 문구', () => {
    expect(claimRequestErrorMessage({ statusCode: 422, data: { detail: '반품 가능 기간(배송완료 후 7일)이 지났습니다: 배송완료 2026-09-01' } })).toContain('7일')
    expect(claimRequestErrorMessage({ statusCode: 422, data: { detail: '반품 사유로 사용할 수 없는 코드입니다: STOCK_DELAY' } })).toContain('사유')
    expect(claimRequestErrorMessage({ statusCode: 422, data: { detail: '검수 불합격 이력이 있는 품목은 반품을 다시 요청할 수 없습니다' } })).toContain('검수 불합격')
    expect(claimRequestErrorMessage({ statusCode: 422, data: { detail: '반품은 배송완료 품목만 요청할 수 있습니다: SHIPPING' } })).toContain('배송완료된 상품만')
    expect(claimRequestErrorMessage({ statusCode: 422, data: { detail: '현재 주문 품목 상태에서 CANCEL 요청이 불가합니다' } })).toBe('현재 상태에서는 요청할 수 없습니다.')
  })

  it('400은 첨부 문구 구분·401/404는 null(호출부 처리)', () => {
    expect(claimRequestErrorMessage({ statusCode: 400, data: { detail: '이미 다른 클레임에 연결된 첨부입니다' } })).toContain('사진 첨부')
    expect(claimRequestErrorMessage({ statusCode: 400, data: { detail: 'orderItemPublicId 형식' } })).toBe('요청 정보를 확인하세요.')
    expect(claimRequestErrorMessage({ statusCode: 404 })).toBeNull()
  })
})
