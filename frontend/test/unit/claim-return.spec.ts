import { describe, it, expect } from 'vitest'
import {
  CLAIM_ATTACHMENT_MAX,
  CLAIM_INSPECTION_FAIL_REASON_CODE,
  CLAIM_REJECT_REASON_CODES,
  CLAIM_REJECT_REASON_LABELS,
  RETURN_REASON_CODES,
  claimReasonCodesFor,
  claimRejectReasonCodesFor,
  claimableTypes,
  isClaimAttachmentAllowed,
  isClaimRejectReasonApplicable,
} from '~/lib/constants/claim'
import { exchangeOptionCandidates, variantOptionLabel } from '~/lib/utils/claim-exchange-options'
import { claimTimeline } from '~/lib/utils/claim-timeline'
import { CLAIM_ATTACHMENT_MAX_BYTES, precheckClaimAttachments, uploadItemErrorMessage, uploadRequestErrorMessage } from '~/lib/utils/claim-attachment'
import { claimRequestErrorMessage } from '~/lib/utils/claim-request-error'
import type { ClaimDetail } from '~/types/claim'

// FE-29: 반품 요청 조건(사유 3값·DELIVERED만·첨부 허용 사유)·거부 사유 5값(INSPECTION_FAILED 일반 거부 제외)·타임라인·업로드 검증·422 문구.
describe('claimableTypes / 반품 사유(claim.ts)', () => {
  it('SHIPPING은 반품 버튼 없음·DELIVERED만 RETURN·EXCHANGE(D-170 DELIVERED 한정) / 교환 완료 품목은 RETURN만(FE-30-4)', () => {
    expect(claimableTypes('SHIPPING')).toEqual([])
    expect(claimableTypes('DELIVERED')).toEqual(['RETURN', 'EXCHANGE'])
    expect(claimableTypes('DELIVERED', true)).toEqual(['RETURN'])
    expect(claimableTypes('PAID')).toEqual(['CANCEL'])
    expect(claimableTypes('PAID', true)).toEqual(['CANCEL'])
    expect(claimableTypes('CONFIRMED')).toEqual([])
  })

  it('반품·교환 사유는 3값·취소는 전량(BE ClaimReasonCode.isApplicableTo 1:1·D-177 결정 7)', () => {
    expect(claimReasonCodesFor('RETURN')).toEqual(['BUYER_CHANGED_MIND', 'PRODUCT_DEFECT', 'WRONG_PRODUCT'])
    expect(claimReasonCodesFor('RETURN')).toBe(RETURN_REASON_CODES)
    expect(claimReasonCodesFor('EXCHANGE')).toBe(RETURN_REASON_CODES)
    expect(claimReasonCodesFor('CANCEL')).toHaveLength(10)
  })

  it('사진 첨부는 반품·교환 + 상품불량/오배송에서만(D-171·D-177 결정 3·그 외 BE 400)', () => {
    expect(isClaimAttachmentAllowed('RETURN', 'PRODUCT_DEFECT')).toBe(true)
    expect(isClaimAttachmentAllowed('RETURN', 'WRONG_PRODUCT')).toBe(true)
    expect(isClaimAttachmentAllowed('RETURN', 'BUYER_CHANGED_MIND')).toBe(false)
    expect(isClaimAttachmentAllowed('RETURN', '')).toBe(false)
    expect(isClaimAttachmentAllowed('CANCEL', 'PRODUCT_DEFECT')).toBe(false)
    expect(isClaimAttachmentAllowed('EXCHANGE', 'PRODUCT_DEFECT')).toBe(true)
    expect(isClaimAttachmentAllowed('EXCHANGE', 'BUYER_CHANGED_MIND')).toBe(false)
    expect(CLAIM_ATTACHMENT_MAX).toBe(5)
  })
})

describe('거부 사유 INSPECTION_FAILED(claim.ts)', () => {
  it('라벨 "검수 불합격"·일반 거부 목록 4값 유지·검수 FAIL 사유는 INSPECTION_FAILED 고정(D-172)', () => {
    expect(CLAIM_REJECT_REASON_LABELS.INSPECTION_FAILED).toBe('검수 불합격')
    expect(CLAIM_REJECT_REASON_CODES).toEqual(['ALREADY_SHIPPED', 'OUT_OF_POLICY', 'BUYER_WITHDRAWN', 'OTHER'])
    expect(CLAIM_INSPECTION_FAIL_REASON_CODE).toBe('INSPECTION_FAILED')
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

  it('취소는 기존 3단 유지', () => {
    expect(labels(returnDetail({ claimType: 'CANCEL', status: 'APPROVED' }))).toEqual(['요청:done', '승인:current', '완료:upcoming'])
  })

  it('교환 7단(FE-30-2): 신청/승인/회수/검수/교환품 발송/배송완료/완료 단계 판정·시각은 pickedUpAt·reshipment shippedAt/deliveredAt·processedAt', () => {
    const exchange = (overrides: Partial<ClaimDetail>) => returnDetail({ claimType: 'EXCHANGE', ...overrides })
    const outbound = { ...shipment, deliveryPublicId: 'dlv_2', direction: 'OUTBOUND' as const, trackingNo: 'X1', shippedAt: '2026-09-14T10:00:00+09:00' }
    expect(labels(exchange({ status: 'REQUESTED' }))).toEqual(['신청:current', '승인:upcoming', '회수:upcoming', '검수:upcoming', '교환품 발송:upcoming', '배송완료:upcoming', '완료:upcoming'])
    expect(labels(exchange({ status: 'APPROVED' }))[1]).toBe('승인:current')
    expect(labels(exchange({ status: 'APPROVED', returnShipment: shipment }))[2]).toBe('회수:current')
    expect(labels(exchange({ status: 'APPROVED', returnShipment: shipment, pickedUpAt: '2026-09-12T10:00:00+09:00' }))[3]).toBe('검수:current')
    expect(labels(exchange({ status: 'APPROVED', returnShipment: shipment, pickedUpAt: '2026-09-12T10:00:00+09:00', inspectionResult: 'PASS' }))[4]).toBe('교환품 발송:current')
    const shipping = exchange({ status: 'APPROVED', returnShipment: shipment, pickedUpAt: '2026-09-12T10:00:00+09:00', inspectionResult: 'PASS', reshipment: outbound })
    expect(labels(shipping)[5]).toBe('배송완료:current')
    expect(claimTimeline(shipping).map((step) => step.at)).toEqual([shipping.requestedAt, null, '2026-09-12T10:00:00+09:00', null, '2026-09-14T10:00:00+09:00', null, null])
    const done = exchange({ status: 'COMPLETED', returnShipment: shipment, pickedUpAt: '2026-09-12T10:00:00+09:00', inspectionResult: 'PASS', reshipment: { ...outbound, status: 'DELIVERED', deliveredAt: '2026-09-15T10:00:00+09:00' }, processedAt: '2026-09-15T10:00:00+09:00' })
    expect(labels(done)).toEqual(['신청:done', '승인:done', '회수:done', '검수:done', '교환품 발송:done', '배송완료:done', '완료:current'])
    expect(claimTimeline(done)[5]!.at).toBe('2026-09-15T10:00:00+09:00')
    // 검수 불합격은 4단 종결·승인 전 거절은 2단
    expect(labels(exchange({ status: 'REJECTED', inspectionResult: 'FAIL', returnShipment: shipment, pickedUpAt: '2026-09-12T10:00:00+09:00', processedAt: '2026-09-13T10:00:00+09:00' })))
      .toEqual(['신청:done', '승인:done', '회수:done', '검수 불합격:current'])
    expect(labels(exchange({ status: 'REJECTED', rejectReasonCode: 'OUT_OF_POLICY' }))).toEqual(['신청:done', '거절:current'])
  })
})

describe('exchangeOptionCandidates(claim-exchange-options.ts)', () => {
  const variants = [
    { variantPublicId: 'var_A', salePrice: 10000, soldOut: false, options: [{ groupName: '색상', value: '빨강' }] },
    { variantPublicId: 'var_B', salePrice: 10000, soldOut: false, options: [{ groupName: '색상', value: '파랑' }] },
    { variantPublicId: 'var_C', salePrice: 10500, soldOut: false, options: [{ groupName: '색상', value: '노랑' }] },
    { variantPublicId: 'var_D', salePrice: 10000, soldOut: true, options: [{ groupName: '색상', value: '검정' }] },
    { variantPublicId: 'var_E', salePrice: 10000, soldOut: false, options: [] },
  ]

  it('같은 가격만·품절 제외·현재 옵션 제외·라벨은 "그룹: 값"(옵션 없으면 기본 옵션)', () => {
    const candidates = exchangeOptionCandidates(variants, 'var_A', 10000)
    expect(candidates.map((candidate) => candidate.variantPublicId)).toEqual(['var_B', 'var_E'])
    expect(candidates[0]!.label).toBe('색상: 파랑')
    expect(candidates[1]!.label).toBe('기본 옵션')
    expect(variantOptionLabel({ options: [{ groupName: '색상', value: '빨강' }, { groupName: '사이즈', value: 'L' }] })).toBe('색상: 빨강 / 사이즈: L')
  })

  it('후보 0건: 단가가 맞는 다른 옵션이 없거나 전부 품절', () => {
    expect(exchangeOptionCandidates(variants, 'var_A', 9000)).toEqual([])
    expect(exchangeOptionCandidates([variants[0]!, variants[3]!], 'var_A', 10000)).toEqual([])
  })
})

describe('precheckClaimAttachments(claim-attachment.ts)', () => {
  const file = (name: string, type: string, size: number) => {
    const blob = new File([new Uint8Array(1)], name, { type })
    Object.defineProperty(blob, 'size', { value: size })
    return blob
  }

  it('형식·5MB(경계 포함 통과·+1 거절·D-174)·5장(현재 + 추가) 제한', () => {
    expect(CLAIM_ATTACHMENT_MAX_BYTES).toBe(5 * 1024 * 1024)
    const { accepted, rejected } = precheckClaimAttachments([
      file('a.png', 'image/png', 100), file('b.gif', 'image/gif', 100), file('c.jpg', 'image/jpeg', CLAIM_ATTACHMENT_MAX_BYTES + 1),
      file('edge.jpg', 'image/jpeg', CLAIM_ATTACHMENT_MAX_BYTES),
    ], 0)
    expect(accepted.map((entry) => entry.name)).toEqual(['a.png', 'edge.jpg'])
    expect(rejected.map((entry) => entry.reason)).toEqual(['jpg·png·webp만 첨부할 수 있습니다.', '파일당 5MB를 초과합니다.'])

    const over = precheckClaimAttachments([file('d.png', 'image/png', 1), file('e.png', 'image/png', 1)], 4)
    expect(over.accepted).toHaveLength(1)
    expect(over.rejected[0]!.reason).toContain('최대 5장')
  })

  it('서버 파일별 실패 코드 → 문구(IMAGE_TOO_LARGE 해상도 안내·D-174)', () => {
    expect(uploadItemErrorMessage({ success: false, code: 'UNSUPPORTED_FORMAT' })).toContain('jpg·png·webp')
    expect(uploadItemErrorMessage({ success: false, code: 'FILE_TOO_LARGE' })).toBe('파일당 5MB를 초과합니다.')
    expect(uploadItemErrorMessage({ success: false, code: 'IMAGE_TOO_LARGE' })).toBe('이미지 해상도가 너무 큽니다. 한 변 8,000px 이하로 줄여 주세요.')
    expect(uploadItemErrorMessage({ success: false, message: '서버 문구' })).toBe('서버 문구')
    expect(uploadItemErrorMessage({ success: false })).toBe('업로드에 실패했습니다.')
  })

  it('요청 단위 실패 문구: 413 → 5MB / 400 미연결 상한(detail 부분 일치) → 20장 안내 / 400 그 외 → 장수 안내 / 기타 → 일반', () => {
    expect(uploadRequestErrorMessage({ statusCode: 413 })).toBe('파일당 5MB를 초과합니다.')
    expect(uploadRequestErrorMessage({ statusCode: 400, data: { code: 'MALFORMED_REQUEST', detail: '연결되지 않은 첨부가 너무 많습니다(최대 20개·현재 20개).' } }))
      .toContain('최대 20장')
    expect(uploadRequestErrorMessage({ statusCode: 400, data: { code: 'MALFORMED_REQUEST', detail: '반품 사진은 최대 5장까지 첨부할 수 있습니다.' } }))
      .toContain('최대 5장')
    expect(uploadRequestErrorMessage(new Error('network'))).toContain('잠시 후')
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

  it('교환 422 문구(FE-30): 기한·사유·재교환·같은 상품/가격/옵션·판매 중지·FAIL 이력 / 400 교환 옵션', () => {
    expect(claimRequestErrorMessage({ statusCode: 422, data: { detail: '교환 가능 기간(배송완료 후 7일)이 지났습니다' } })).toContain('교환 가능 기간')
    expect(claimRequestErrorMessage({ statusCode: 422, data: { detail: '교환 사유로 사용할 수 없는 코드입니다: OTHER' } })).toContain('교환을 요청할 수 없습니다')
    expect(claimRequestErrorMessage({ statusCode: 422, data: { detail: '이미 교환이 완료된 품목은 다시 교환할 수 없습니다(반품은 가능)' } })).toBe('이미 교환한 상품은 다시 교환할 수 없습니다.')
    expect(claimRequestErrorMessage({ statusCode: 422, data: { detail: '같은 상품의 옵션으로만 교환할 수 있습니다' } })).toContain('같은 상품')
    expect(claimRequestErrorMessage({ statusCode: 422, data: { detail: '같은 가격의 옵션으로만 교환할 수 있습니다: 주문 단가=10000' } })).toContain('같은 가격')
    expect(claimRequestErrorMessage({ statusCode: 422, data: { detail: '같은 옵션으로는 교환할 수 없습니다: variantId=1' } })).toContain('같은 옵션')
    expect(claimRequestErrorMessage({ statusCode: 422, data: { detail: '판매 중이 아닌 옵션으로는 교환할 수 없습니다(NOT_ON_SALE)' } })).toContain('판매 중이 아닌')
    expect(claimRequestErrorMessage({ statusCode: 422, data: { detail: '검수 불합격 이력이 있는 품목은 교환을 다시 요청할 수 없습니다' } })).toContain('반품·교환')
    expect(claimRequestErrorMessage({ statusCode: 400, data: { detail: '교환 요청은 교환 옵션(exchangeVariantId)이 필수입니다.' } })).toContain('교환 옵션')
  })

  it('400은 첨부 문구 구분·401/404는 null(호출부 처리)', () => {
    expect(claimRequestErrorMessage({ statusCode: 400, data: { detail: '이미 다른 클레임에 연결된 첨부입니다' } })).toContain('사진 첨부')
    expect(claimRequestErrorMessage({ statusCode: 400, data: { detail: 'orderItemPublicId 형식' } })).toBe('요청 정보를 확인하세요.')
    expect(claimRequestErrorMessage({ statusCode: 404 })).toBeNull()
  })
})
