import { describe, it, expect } from 'vitest'
import {
  DEFAULT_SELLER_ORDER_QUERY,
  hasActiveFilters,
  isPeriodInverted,
  parseSellerOrderQuery,
  toSellerOrderApiParams,
  toSellerOrderRouteQuery,
} from '#layers/seller/app/lib/seller-order-query'
import { canMarkDelivered, canPrepareShipment, itemLabel, validateShipmentForm } from '#layers/seller/app/lib/seller-order-view'
import { SELLER_ORDER_ITEM_STATUS_CODES } from '#layers/seller/app/lib/constants/seller-order'
import { resolveBackPath, SELLER_DASHBOARD_PATH, SELLER_DELIVERIES_PATH, SELLER_ORDERS_PATH } from '#layers/seller/app/lib/seller-back-path'

// Track 90-B-3(D-191): 품목 목록 URL query ↔ 화면 상태 ↔ BE 파라미터(status·paid_at 기간·keyword·page·size — sort 없음) + 발송 판정·폼 검증 + 복귀 경로.
describe('parseSellerOrderQuery', () => {
  it('빈 query → 기본 상태(page 0·size 20·필터 없음)', () => {
    expect(parseSellerOrderQuery({})).toEqual(DEFAULT_SELLER_ORDER_QUERY)
  })

  it('전체 query 파싱·trim·타입 변환', () => {
    expect(parseSellerOrderQuery({ keyword: ' 반찬통 ', status: 'PAID', from: '2026-09-01', to: '2026-09-20', page: '2', size: '50' }))
      .toEqual({ keyword: '반찬통', status: 'PAID', from: '2026-09-01', to: '2026-09-20', page: 2, size: 50 })
  })

  it('잘못된 값은 기본값으로(미지 status·날짜 형식·음수 page·허용 외 size) · 검색어 50자 절단 · 배열은 첫 값', () => {
    expect(parseSellerOrderQuery({ status: 'BOGUS', from: '20260901', to: '2026-9-1', page: '-1', size: '33' })).toEqual(DEFAULT_SELLER_ORDER_QUERY)
    expect(parseSellerOrderQuery({ keyword: 'a'.repeat(60) }).keyword).toHaveLength(50)
    expect(parseSellerOrderQuery({ status: ['SHIPPING', 'PAID'] }).status).toBe('SHIPPING')
  })

  it('필터 옵션에 ORDERED(미결제)는 없다 — BE가 미결제 품목을 제외한다', () => {
    expect(SELLER_ORDER_ITEM_STATUS_CODES).not.toContain('ORDERED')
    expect(SELLER_ORDER_ITEM_STATUS_CODES).toContain('PAID')
  })
})

describe('toSellerOrderRouteQuery · toSellerOrderApiParams', () => {
  it('기본값 항목은 URL에서 생략 · API는 page/size 항상 포함·기간은 T00:00:00/T23:59:59 부착', () => {
    expect(toSellerOrderRouteQuery(DEFAULT_SELLER_ORDER_QUERY)).toEqual({})
    const state = { keyword: ' 반찬통 ', status: 'PAID' as const, from: '2026-09-01', to: '2026-09-20', page: 1, size: 50 }
    expect(toSellerOrderRouteQuery(state)).toEqual({ keyword: '반찬통', status: 'PAID', from: '2026-09-01', to: '2026-09-20', page: '1', size: '50' })
    expect(toSellerOrderApiParams(state)).toEqual({ page: 1, size: 50, keyword: '반찬통', status: 'PAID', from: '2026-09-01T00:00:00', to: '2026-09-20T23:59:59' })
    expect(toSellerOrderApiParams(DEFAULT_SELLER_ORDER_QUERY)).toEqual({ page: 0, size: 20 })
  })

  it('hasActiveFilters · isPeriodInverted', () => {
    expect(hasActiveFilters(DEFAULT_SELLER_ORDER_QUERY)).toBe(false)
    expect(hasActiveFilters({ ...DEFAULT_SELLER_ORDER_QUERY, status: 'PAID' })).toBe(true)
    expect(hasActiveFilters({ ...DEFAULT_SELLER_ORDER_QUERY, page: 3 })).toBe(false)
    expect(isPeriodInverted({ from: '2026-09-21', to: '2026-09-20' })).toBe(true)
    expect(isPeriodInverted({ from: '2026-09-20', to: null })).toBe(false)
  })
})

describe('seller-order-view', () => {
  it('발송 대상은 PAID만 · 배송완료 대상은 원 발송 SHIPPING', () => {
    expect(canPrepareShipment({ itemStatus: 'PAID' })).toBe(true)
    expect(canPrepareShipment({ itemStatus: 'PREPARING' })).toBe(false)
    expect(canPrepareShipment({ itemStatus: 'CANCEL_REQUESTED' })).toBe(false)
    expect(canMarkDelivered({ delivery: { deliveryId: 'd', carrier: 'CJ', trackingNo: '1', status: 'SHIPPING' } })).toBe(true)
    expect(canMarkDelivered({ delivery: { deliveryId: 'd', carrier: 'CJ', trackingNo: '1', status: 'DELIVERED' } })).toBe(false)
    expect(canMarkDelivered({})).toBe(false)
  })

  it('validateShipmentForm: 택배사·송장번호 필수·형식(공백 제거 후 숫자·영문·하이픈 8~20자·D-227)', () => {
    expect(validateShipmentForm({ carrier: null, trackingNo: ' ' })).toEqual({ carrier: '택배사를 선택하세요.', trackingNo: '송장번호를 입력하세요.' })
    expect(validateShipmentForm({ carrier: 'CJ', trackingNo: 'ㅕㅕㅕ' })).toEqual({ trackingNo: '송장번호는 숫자·영문·하이픈 8~20자로 입력해 주세요.' })
    expect(validateShipmentForm({ carrier: 'CJ', trackingNo: ' 12345678 ' })).toEqual({})
  })

  it('itemLabel: 옵션 유무', () => {
    expect(itemLabel({ productName: '반찬통', optionLabel: '6종', quantity: 2 })).toBe('반찬통 (6종) · 2개')
    expect(itemLabel({ productName: '반찬통', quantity: 1 })).toBe('반찬통 · 1개')
  })
})

describe('resolveBackPath(셀러)', () => {
  it('base 정확·쿼리 포함 허용 · 품목 상세는 대시보드·배송 목록 진입 허용 · 그 외는 base로', () => {
    expect(resolveBackPath(undefined, SELLER_ORDERS_PATH)).toBe(SELLER_ORDERS_PATH)
    expect(resolveBackPath('/seller/orders?status=PAID&page=1', SELLER_ORDERS_PATH)).toBe('/seller/orders?status=PAID&page=1')
    expect(resolveBackPath(SELLER_DASHBOARD_PATH, SELLER_ORDERS_PATH)).toBe(SELLER_DASHBOARD_PATH)
    expect(resolveBackPath('/seller/deliveries?scope=ALL', SELLER_ORDERS_PATH)).toBe('/seller/deliveries?scope=ALL')
    expect(resolveBackPath('/admin/orders', SELLER_ORDERS_PATH)).toBe(SELLER_ORDERS_PATH)
    expect(resolveBackPath('https://evil.example', SELLER_ORDERS_PATH)).toBe(SELLER_ORDERS_PATH)
    expect(resolveBackPath(['/seller/orders/x'], SELLER_ORDERS_PATH)).toBe(SELLER_ORDERS_PATH)
    expect(resolveBackPath(SELLER_DASHBOARD_PATH, SELLER_DELIVERIES_PATH)).toBe(SELLER_DELIVERIES_PATH)
  })
})
