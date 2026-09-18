import { describe, it, expect } from 'vitest'
import {
  canCorrectTracking,
  deliveryClaimChip,
  toClaimListPath,
  trackingCorrectionBlockedReason,
} from '#layers/admin/app/lib/admin-delivery-view'

// FE-37: 배지 매핑·송장 수정 가능 판정·클레임 이동 경로 순수 함수.
describe('deliveryClaimChip', () => {
  it('원 발송(claimType 없음)은 배지 없음', () => {
    expect(deliveryClaimChip({ direction: 'OUTBOUND' })).toBeNull()
  })

  it('교환품 발송·재발송은 warning 배지, 회수는 유형별 라벨 + warning', () => {
    expect(deliveryClaimChip({ direction: 'OUTBOUND', claimType: 'EXCHANGE' })).toEqual({ text: '교환품 발송', semantic: 'warning' })
    expect(deliveryClaimChip({ direction: 'OUTBOUND', claimType: 'RETURN' })).toEqual({ text: '재발송', semantic: 'warning' })
    expect(deliveryClaimChip({ direction: 'RETURN', claimType: 'RETURN' })).toEqual({ text: '반품 회수', semantic: 'warning' })
    expect(deliveryClaimChip({ direction: 'RETURN', claimType: 'EXCHANGE' })).toEqual({ text: '교환 회수', semantic: 'warning' })
  })
})

describe('canCorrectTracking / trackingCorrectionBlockedReason', () => {
  it('SHIPPING만 수정 가능·READY/DELIVERED는 사유 문구', () => {
    expect(canCorrectTracking('SHIPPING')).toBe(true)
    expect(canCorrectTracking('READY')).toBe(false)
    expect(canCorrectTracking('DELIVERED')).toBe(false)
    expect(trackingCorrectionBlockedReason('SHIPPING')).toBeNull()
    expect(trackingCorrectionBlockedReason('DELIVERED')).toContain('배송완료')
    expect(trackingCorrectionBlockedReason('READY')).toContain('배송중')
  })
})

describe('toClaimListPath', () => {
  it('주문번호 정확 검색 + 유형으로 클레임 목록 경로를 만든다', () => {
    expect(toClaimListPath({ orderNo: '20260912-N028FM', claimType: 'RETURN' })).toBe('/admin/orders/claims?keyword=20260912-N028FM&type=RETURN')
  })

  it('주문번호가 없으면 유형만, 둘 다 없으면 목록 루트', () => {
    expect(toClaimListPath({ claimType: 'EXCHANGE' })).toBe('/admin/orders/claims?type=EXCHANGE')
    expect(toClaimListPath({})).toBe('/admin/orders/claims')
  })
})
