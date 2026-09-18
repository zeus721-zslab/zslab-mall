import { describe, it, expect } from 'vitest'
import {
  DEFAULT_ADMIN_DELIVERY_QUERY,
  hasActiveFilters,
  parseAdminDeliveryQuery,
  toAdminDeliveryApiParams,
  toAdminDeliveryRouteQuery,
} from '#layers/admin/app/lib/admin-delivery-query'

// FE-37: URL query ↔ 화면 상태 ↔ BE 파라미터 순수 매핑(scope 단일 축·발송일 기간).
describe('parseAdminDeliveryQuery', () => {
  it('빈 query → 기본 상태(scope ORIGINAL·LATEST·page 0·size 20·필터 없음)', () => {
    expect(parseAdminDeliveryQuery({})).toEqual(DEFAULT_ADMIN_DELIVERY_QUERY)
  })

  it('전체 query 파싱·trim·타입 변환', () => {
    expect(parseAdminDeliveryQuery({
      keyword: ' TRK-1 ', scope: 'RETURN', status: 'DELIVERED', carrier: 'HANJIN', from: '2026-09-01', to: '2026-09-16', sort: 'OLDEST', page: '2', size: '50',
    })).toEqual({
      keyword: 'TRK-1', scope: 'RETURN', status: 'DELIVERED', carrier: 'HANJIN', from: '2026-09-01', to: '2026-09-16', sort: 'OLDEST', page: 2, size: 50,
    })
  })

  it('잘못된 값은 기본값으로 정규화(미지의 scope/status/carrier/sort·날짜 형식·음수 page·허용 외 size)', () => {
    expect(parseAdminDeliveryQuery({
      scope: 'BOGUS', status: 'BOGUS', carrier: 'DHL', sort: 'RANDOM', from: '2026/09/01', to: 'x', page: '-1', size: '7',
    })).toEqual(DEFAULT_ADMIN_DELIVERY_QUERY)
  })

  it('검색어는 50자로 잘라 BE 400을 예방한다', () => {
    expect(parseAdminDeliveryQuery({ keyword: 'K'.repeat(60) }).keyword).toHaveLength(50)
  })
})

describe('toAdminDeliveryRouteQuery', () => {
  it('기본값 항목(scope ORIGINAL·sort·page 0·size 20)은 URL에서 생략', () => {
    expect(toAdminDeliveryRouteQuery(DEFAULT_ADMIN_DELIVERY_QUERY)).toEqual({})
  })

  it('기본값이 아닌 항목만 직렬화(scope 포함)', () => {
    expect(toAdminDeliveryRouteQuery({
      ...DEFAULT_ADMIN_DELIVERY_QUERY, keyword: ' T1 ', scope: 'ALL', status: 'SHIPPING', carrier: 'CJ', from: '2026-09-01', sort: 'OLDEST', page: 1, size: 100,
    })).toEqual({ keyword: 'T1', scope: 'ALL', status: 'SHIPPING', carrier: 'CJ', from: '2026-09-01', sort: 'OLDEST', page: '1', size: '100' })
  })
})

describe('toAdminDeliveryApiParams', () => {
  it('scope·sort·page·size는 항상 포함, 기간은 T00:00:00/T23:59:59 부착', () => {
    expect(toAdminDeliveryApiParams({ ...DEFAULT_ADMIN_DELIVERY_QUERY, keyword: 'DEMO00001167', carrier: 'POST', from: '2026-09-01', to: '2026-09-16' }))
      .toEqual({ scope: 'ORIGINAL', sort: 'LATEST', page: 0, size: 20, keyword: 'DEMO00001167', carrier: 'POST', from: '2026-09-01T00:00:00', to: '2026-09-16T23:59:59' })
  })

  it('null·빈 검색어는 제외', () => {
    expect(toAdminDeliveryApiParams({ ...DEFAULT_ADMIN_DELIVERY_QUERY, keyword: '  ', scope: 'CLAIM_OUTBOUND' }))
      .toEqual({ scope: 'CLAIM_OUTBOUND', sort: 'LATEST', page: 0, size: 20 })
  })
})

describe('hasActiveFilters', () => {
  it('기본 상태는 false, scope가 기본값(ORIGINAL)이 아니거나 필터가 하나라도 있으면 true', () => {
    expect(hasActiveFilters(DEFAULT_ADMIN_DELIVERY_QUERY)).toBe(false)
    expect(hasActiveFilters({ ...DEFAULT_ADMIN_DELIVERY_QUERY, scope: 'ALL' })).toBe(true)
    expect(hasActiveFilters({ ...DEFAULT_ADMIN_DELIVERY_QUERY, carrier: 'CJ' })).toBe(true)
    expect(hasActiveFilters({ ...DEFAULT_ADMIN_DELIVERY_QUERY, to: '2026-09-16' })).toBe(true)
    expect(hasActiveFilters({ ...DEFAULT_ADMIN_DELIVERY_QUERY, sort: 'OLDEST', page: 3 })).toBe(false)
  })
})
