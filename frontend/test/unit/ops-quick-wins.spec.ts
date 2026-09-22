import { describe, it, expect } from 'vitest'
import { claimStageGuide } from '~/lib/utils/claim-timeline'
import { AUTO_CONFIRM_DAYS, AUTO_CONFIRM_GUIDE, PAYMENT_EXPIRE_GUIDE, PAYMENT_EXPIRE_MINUTES } from '~/lib/constants/order'
import { ELAPSED_DANGER_DAYS, ELAPSED_WARNING_DAYS, elapsedChip, elapsedDays, elapsedTone } from '~/lib/utils/elapsed-days'
import type { ClaimDetail } from '~/types/claim'

// Track 96-1(FE-53): 사용자 안내 문구(C-16)·경과 일수(C-15) 순수 함수. 문구는 시스템이 보장하는 기간(BE 설정값)만 담고 소요 기간 추정은 없다.

function detail(overrides: Partial<ClaimDetail>): ClaimDetail {
  return {
    publicId: 'clm_1', orderItemPublicId: 'oit_1', claimType: 'RETURN', status: 'REQUESTED', reasonCode: 'PRODUCT_DEFECT',
    reasonDetail: null, requestedAt: '2026-09-10T10:00:00+09:00', processedAt: null, rejectReasonCode: null, rejectMemo: null,
    refundStatus: null, returnShipmentRequired: false, attachmentUrls: [], ...overrides,
  }
}
const shipment = { deliveryPublicId: 'dlv_1', direction: 'RETURN' as const, carrier: 'CJ' as const, trackingNo: 'R1', status: 'SHIPPING' as const, shippedAt: '2026-09-11T10:00:00+09:00', deliveredAt: null }
const PICKED = '2026-09-12T10:00:00+09:00'

describe('claimStageGuide(C-16) — 타임라인과 같은 단계 판정·기간 문구 없음', () => {
  it('반품: 승인 대기 → 송장 등록 요청 → 회수 확인 대기 → 검수 대기 → 환불 진행 → 완료', () => {
    // Track 99 FE-61: 승인·검수 주체는 관리자라 구매자 문구의 "판매자"를 "쇼핑몰"로 고쳤다.
    expect(claimStageGuide(detail({}))).toBe('쇼핑몰의 승인을 기다리고 있습니다.')
    expect(claimStageGuide(detail({ status: 'APPROVED', returnShipmentRequired: true }))).toContain('회수 송장을 등록해 주세요')
    expect(claimStageGuide(detail({ status: 'APPROVED', returnShipment: shipment }))).toContain('회수 확인을 기다리고')
    expect(claimStageGuide(detail({ status: 'APPROVED', returnShipment: shipment, pickedUpAt: PICKED }))).toContain('검수 결과를 기다리고')
    expect(claimStageGuide(detail({ status: 'APPROVED', returnShipment: shipment, pickedUpAt: PICKED, inspectionResult: 'PASS' }))).toContain('환불을 진행하고')
    expect(claimStageGuide(detail({ status: 'COMPLETED', returnShipment: shipment, pickedUpAt: PICKED, inspectionResult: 'PASS' }))).toBe('반품과 환불이 완료되었습니다.')
  })

  it('거절 분기: 검수 불합격은 재발송 안내·일반 거절은 거절 문구 / 교환·취소 유형별', () => {
    expect(claimStageGuide(detail({ status: 'REJECTED', inspectionResult: 'FAIL' }))).toContain('다시 보내드립니다')
    expect(claimStageGuide(detail({ status: 'REJECTED' }))).toBe('반품 요청이 거절되었습니다.')
    expect(claimStageGuide(detail({ claimType: 'EXCHANGE', status: 'APPROVED' }))).toContain('회수 송장을 등록해 주세요')
    expect(claimStageGuide(detail({ claimType: 'EXCHANGE', status: 'APPROVED', returnShipment: shipment, pickedUpAt: PICKED, inspectionResult: 'PASS' }))).toContain('교환품 발송을 기다리고')
    expect(claimStageGuide(detail({ claimType: 'EXCHANGE', status: 'REJECTED', inspectionResult: 'FAIL' }))).toContain('교환이 거절되어')
    expect(claimStageGuide(detail({ claimType: 'CANCEL', status: 'APPROVED' }))).toContain('환불을 진행하고')
    expect(claimStageGuide(detail({ claimType: 'CANCEL', status: 'COMPLETED' }))).toBe('취소와 환불이 완료되었습니다.')
  })

  it('소요 기간 추정 문구 금지(시스템이 보장하지 않는 "N일 내"·"1~2일" 없음)', () => {
    const all = [
      detail({}), detail({ status: 'APPROVED', returnShipmentRequired: true }), detail({ status: 'APPROVED', returnShipment: shipment }),
      detail({ status: 'APPROVED', returnShipment: shipment, pickedUpAt: PICKED }), detail({ claimType: 'EXCHANGE', status: 'APPROVED', returnShipment: shipment, pickedUpAt: PICKED, inspectionResult: 'PASS' }),
      detail({ claimType: 'CANCEL', status: 'REQUESTED' }),
    ].map((row) => claimStageGuide(row))
    for (const text of all) expect(text).not.toMatch(/\d+\s*~?\s*\d*\s*일/)
  })
})

describe('주문 상세 안내 상수(C-16) — BE 설정값과 일치', () => {
  it('자동 구매확정 7일(ReturnWindowPolicy.WINDOW_DAYS) · 결제 만료 30분(PaymentService.PENDING_TTL)', () => {
    expect(AUTO_CONFIRM_DAYS).toBe(7)
    expect(PAYMENT_EXPIRE_MINUTES).toBe(30)
    expect(AUTO_CONFIRM_GUIDE).toBe('배송완료 7일 후 자동 구매확정됩니다.')
    expect(PAYMENT_EXPIRE_GUIDE).toBe('30분 내 결제되지 않으면 주문이 자동 취소됩니다.')
  })
})

describe('elapsed-days(C-15) — 경과 일수·임계 톤', () => {
  const NOW = new Date('2026-09-21T12:00:00+09:00')

  it('만 일수 내림·미래는 0·파싱 실패 null', () => {
    expect(elapsedDays('2026-09-21T09:00:00+09:00', NOW)).toBe(0)
    expect(elapsedDays('2026-09-18T13:00:00+09:00', NOW)).toBe(2) // 2일 23시간 → 2
    expect(elapsedDays('2026-09-18T11:00:00+09:00', NOW)).toBe(3)
    expect(elapsedDays('2026-09-22T00:00:00+09:00', NOW)).toBe(0)
    expect(elapsedDays('not-a-date', NOW)).toBeNull()
  })

  it('임계: 3일 이상 warning · 7일 이상 danger · 미만 null(중립)', () => {
    expect(ELAPSED_WARNING_DAYS).toBe(3)
    expect(ELAPSED_DANGER_DAYS).toBe(7)
    expect(elapsedTone(2)).toBeNull()
    expect(elapsedTone(3)).toBe('warning')
    expect(elapsedTone(6)).toBe('warning')
    expect(elapsedTone(7)).toBe('danger')
  })

  it('chip: 기준 시각 없음 null · 문구 "경과 N일" · 톤 접미', () => {
    expect(elapsedChip(undefined, NOW)).toBeNull()
    expect(elapsedChip('2026-09-20T12:00:00+09:00', NOW)).toEqual({ days: 1, text: '경과 1일', tone: 'neutral' })
    expect(elapsedChip('2026-09-10T12:00:00+09:00', NOW)).toEqual({ days: 11, text: '경과 11일', tone: 'danger' })
  })
})
