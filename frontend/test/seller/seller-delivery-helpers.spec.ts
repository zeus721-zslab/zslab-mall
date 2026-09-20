import { describe, it, expect } from 'vitest'
import {
  DEFAULT_SELLER_DELIVERY_QUERY,
  hasActiveFilters,
  parseSellerDeliveryQuery,
  toSellerDeliveryApiParams,
  toSellerDeliveryRouteQuery,
} from '#layers/seller/app/lib/seller-delivery-query'
import {
  canCorrectTracking,
  canMarkDelivered,
  deliveryClaimChip,
  hasRowActions,
  trackingCorrectionBlockedReason,
  validateTrackingCorrectionForm,
} from '#layers/seller/app/lib/seller-delivery-view'

// Track 90-B-3(D-191): 배송 목록 URL query ↔ 화면 상태 ↔ BE 파라미터(scope·status·carrier·shipped_at 기간·keyword·sort·page·size) + 행 액션 판정·송장 정정 폼 검증.
describe('parseSellerDeliveryQuery', () => {
  it('빈 query → 기본(ORIGINAL·LATEST·page 0·size 20)', () => {
    expect(parseSellerDeliveryQuery({})).toEqual(DEFAULT_SELLER_DELIVERY_QUERY)
  })

  it('전체 파싱 · 잘못된 값 정규화 · 검색어 50자 절단', () => {
    expect(parseSellerDeliveryQuery({ keyword: ' DEMO1 ', scope: 'RETURN', status: 'SHIPPING', carrier: 'CJ', from: '2026-09-01', to: '2026-09-20', sort: 'OLDEST', page: '1', size: '100' }))
      .toEqual({ keyword: 'DEMO1', scope: 'RETURN', status: 'SHIPPING', carrier: 'CJ', from: '2026-09-01', to: '2026-09-20', sort: 'OLDEST', page: 1, size: 100 })
    expect(parseSellerDeliveryQuery({ scope: 'X', status: 'FLYING', carrier: 'DHL', from: 'x', sort: 'RANDOM', page: '0', size: '7' })).toEqual(DEFAULT_SELLER_DELIVERY_QUERY)
    expect(parseSellerDeliveryQuery({ keyword: 'k'.repeat(70) }).keyword).toHaveLength(50)
  })
})

describe('toSellerDeliveryRouteQuery · toSellerDeliveryApiParams · hasActiveFilters', () => {
  it('기본값 생략 · API는 scope/sort/page/size 항상 포함·기간 시각 부착', () => {
    expect(toSellerDeliveryRouteQuery(DEFAULT_SELLER_DELIVERY_QUERY)).toEqual({})
    const state = { ...DEFAULT_SELLER_DELIVERY_QUERY, keyword: 'DEMO1', scope: 'ALL' as const, status: 'SHIPPING' as const, carrier: 'HANJIN' as const, from: '2026-09-01', to: '2026-09-20', sort: 'OLDEST' as const, page: 2, size: 50 }
    expect(toSellerDeliveryRouteQuery(state)).toEqual({ keyword: 'DEMO1', scope: 'ALL', status: 'SHIPPING', carrier: 'HANJIN', from: '2026-09-01', to: '2026-09-20', sort: 'OLDEST', page: '2', size: '50' })
    expect(toSellerDeliveryApiParams(state)).toEqual({ scope: 'ALL', sort: 'OLDEST', page: 2, size: 50, keyword: 'DEMO1', status: 'SHIPPING', carrier: 'HANJIN', from: '2026-09-01T00:00:00', to: '2026-09-20T23:59:59' })
    expect(toSellerDeliveryApiParams(DEFAULT_SELLER_DELIVERY_QUERY)).toEqual({ scope: 'ORIGINAL', sort: 'LATEST', page: 0, size: 20 })
    expect(hasActiveFilters(DEFAULT_SELLER_DELIVERY_QUERY)).toBe(false)
    expect(hasActiveFilters({ ...DEFAULT_SELLER_DELIVERY_QUERY, scope: 'ALL' })).toBe(true)
    expect(hasActiveFilters({ ...DEFAULT_SELLER_DELIVERY_QUERY, sort: 'OLDEST' })).toBe(false)
  })
})

describe('seller-delivery-view', () => {
  it('클레임 배지: 원 발송 null · 회수 warning · 교환품 발송 warning', () => {
    expect(deliveryClaimChip({ direction: 'OUTBOUND' })).toBeNull()
    expect(deliveryClaimChip({ direction: 'RETURN', claimType: 'RETURN' })).toEqual({ text: '반품 회수', semantic: 'warning' })
    expect(deliveryClaimChip({ direction: 'OUTBOUND', claimType: 'EXCHANGE' })).toEqual({ text: '교환품 발송', semantic: 'warning' })
  })

  it('행 액션: SHIPPING 원 발송만 배송완료·송장 정정 · 회수(RETURN)·교환품 발송(claimType)은 송장 정정만 · DELIVERED/READY는 없음', () => {
    expect(canMarkDelivered({ status: 'SHIPPING', direction: 'OUTBOUND' })).toBe(true)
    expect(canMarkDelivered({ status: 'SHIPPING', direction: 'RETURN' })).toBe(false)
    expect(canMarkDelivered({ status: 'DELIVERED', direction: 'OUTBOUND' })).toBe(false)
    // Track 92-a D-197: 교환품 발송(OUTBOUND·claimType)은 BE가 422로 막으므로 버튼도 내지 않는다(송장 정정은 그대로).
    expect(canMarkDelivered({ status: 'SHIPPING', direction: 'OUTBOUND', claimType: 'EXCHANGE' })).toBe(false)
    expect(hasRowActions({ status: 'SHIPPING', direction: 'OUTBOUND', claimType: 'EXCHANGE' })).toBe(true)
    expect(canCorrectTracking('SHIPPING')).toBe(true)
    expect(canCorrectTracking('READY')).toBe(false)
    expect(hasRowActions({ status: 'SHIPPING', direction: 'RETURN' })).toBe(true)
    expect(hasRowActions({ status: 'DELIVERED', direction: 'OUTBOUND' })).toBe(false)
    expect(trackingCorrectionBlockedReason('DELIVERED')).toContain('배송완료된 배송')
    expect(trackingCorrectionBlockedReason('READY')).toContain('배송중 상태에서만')
    expect(trackingCorrectionBlockedReason('SHIPPING')).toBeNull()
  })

  it('클레임 연결 OUTBOUND(검수 FAIL 재발송·claimType RETURN)도 배송완료 미노출', () => {
    // 클레임 유형이면 종류와 무관하게 숨긴다 — BE 가드는 claim_id 기준·FE 판정은 claimType 기준이므로 두 값이 함께 오는 계약을 테스트로 고정(외부 검토 r2).
    expect(canMarkDelivered({ status: 'SHIPPING', direction: 'OUTBOUND', claimType: 'RETURN' })).toBe(false)
  })

  it('validateTrackingCorrectionForm: 택배사·송장(≤100)·사유(≤200) 필수', () => {
    expect(validateTrackingCorrectionForm({ carrier: null, trackingNo: '', reason: '' })).toEqual({
      carrier: '택배사를 선택하세요.', trackingNo: '송장번호를 입력하세요.', reason: '사유를 입력하세요.',
    })
    expect(validateTrackingCorrectionForm({ carrier: 'CJ', trackingNo: 'x'.repeat(101), reason: 'r'.repeat(201) })).toEqual({
      trackingNo: '송장번호는 100자 이하여야 합니다.', reason: '사유는 200자 이하여야 합니다.',
    })
    expect(validateTrackingCorrectionForm({ carrier: 'CJ', trackingNo: '1', reason: '오타' })).toEqual({})
  })
})
