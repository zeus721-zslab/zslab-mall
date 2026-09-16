import { describe, it, expect } from 'vitest'
import {
  DEFAULT_ADMIN_ORDER_QUERY,
  hasActiveFilters,
  isPeriodInverted,
  normalizeDateOnly,
  parseAdminOrderQuery,
  toAdminOrderApiParams,
  toAdminOrderRouteQuery,
  toPeriodEnd,
  toPeriodStart,
} from '#layers/admin/app/lib/admin-order-query'

// FE-27: URL query ↔ 화면 상태 ↔ BE 파라미터 순수 매핑(9 파라미터·기간 변환).
describe('parseAdminOrderQuery', () => {
  it('빈 query → 기본 상태(LATEST·page 0·size 20·필터 없음)', () => {
    expect(parseAdminOrderQuery({})).toEqual(DEFAULT_ADMIN_ORDER_QUERY)
  })

  it('전체 query 파싱·trim·타입 변환', () => {
    expect(parseAdminOrderQuery({
      keyword: ' ORD-1 ', status: 'PAID', paymentStatus: 'PAID', deliveryStatus: 'SHIPPING',
      from: '2026-09-01', to: '2026-09-16', sort: 'OLDEST', page: '2', size: '50',
    })).toEqual({
      keyword: 'ORD-1', status: 'PAID', paymentStatus: 'PAID', deliveryStatus: 'SHIPPING',
      from: '2026-09-01', to: '2026-09-16', sort: 'OLDEST', page: 2, size: 50,
    })
  })

  it('잘못된 값은 기본값으로 정규화(미지의 status 3종·sort·날짜 형식·음수 page·허용 외 size)', () => {
    expect(parseAdminOrderQuery({
      status: 'BOGUS', paymentStatus: 'DONE', deliveryStatus: 'FLYING', from: '20260901', to: '2026-9-1', sort: 'RANDOM', page: '-1', size: '33',
    })).toEqual(DEFAULT_ADMIN_ORDER_QUERY)
  })

  it('검색어는 BE 한도 50자로 잘라 400을 예방·배열 query는 첫 값만', () => {
    expect(parseAdminOrderQuery({ keyword: 'a'.repeat(60) }).keyword).toHaveLength(50)
    expect(parseAdminOrderQuery({ status: ['PAID', 'SHIPPING'] }).status).toBe('PAID')
  })
})

describe('toAdminOrderRouteQuery', () => {
  it('기본값 항목은 생략·나머지는 문자열로', () => {
    expect(toAdminOrderRouteQuery(DEFAULT_ADMIN_ORDER_QUERY)).toEqual({})
    expect(toAdminOrderRouteQuery({
      ...DEFAULT_ADMIN_ORDER_QUERY, keyword: ' x ', status: 'CANCELLED', from: '2026-09-01', sort: 'OLDEST', page: 3, size: 100,
    })).toEqual({ keyword: 'x', status: 'CANCELLED', from: '2026-09-01', sort: 'OLDEST', page: '3', size: '100' })
  })

  it('parse ↔ toRouteQuery 왕복 보존', () => {
    const state = { ...DEFAULT_ADMIN_ORDER_QUERY, paymentStatus: 'FAILED' as const, to: '2026-09-30', page: 1 }
    expect(parseAdminOrderQuery(toAdminOrderRouteQuery(state))).toEqual(state)
  })
})

describe('toAdminOrderApiParams · 기간 변환', () => {
  it('page/size/sort 항상 포함·null·빈 값 제외', () => {
    expect(toAdminOrderApiParams(DEFAULT_ADMIN_ORDER_QUERY)).toEqual({ sort: 'LATEST', page: 0, size: 20 })
  })

  it('from은 T00:00:00·to는 T23:59:59(종료일 포함)를 부착해 BE LocalDateTime ISO로 보낸다', () => {
    expect(toPeriodStart('2026-09-01')).toBe('2026-09-01T00:00:00')
    expect(toPeriodEnd('2026-09-16')).toBe('2026-09-16T23:59:59')
    expect(toAdminOrderApiParams({
      ...DEFAULT_ADMIN_ORDER_QUERY, keyword: 'ORD-1', status: 'PAID', paymentStatus: 'PAID', deliveryStatus: 'READY',
      from: '2026-09-01', to: '2026-09-16',
    })).toEqual({
      sort: 'LATEST', page: 0, size: 20, keyword: 'ORD-1', status: 'PAID', paymentStatus: 'PAID', deliveryStatus: 'READY',
      from: '2026-09-01T00:00:00', to: '2026-09-16T23:59:59',
    })
  })

  it('normalizeDateOnly는 yyyy-MM-dd만 통과', () => {
    expect(normalizeDateOnly('2026-09-16')).toBe('2026-09-16')
    expect(normalizeDateOnly('2026-09-16T10:00')).toBeNull()
    expect(normalizeDateOnly('')).toBeNull()
    expect(normalizeDateOnly(null)).toBeNull()
  })
})

describe('hasActiveFilters · isPeriodInverted', () => {
  it('정렬·페이지·크기만 다르면 필터 없음, 검색·상태·기간 중 하나라도 있으면 있음', () => {
    expect(hasActiveFilters({ ...DEFAULT_ADMIN_ORDER_QUERY, sort: 'OLDEST', page: 4, size: 100 })).toBe(false)
    expect(hasActiveFilters({ ...DEFAULT_ADMIN_ORDER_QUERY, keyword: 'x' })).toBe(true)
    expect(hasActiveFilters({ ...DEFAULT_ADMIN_ORDER_QUERY, deliveryStatus: 'DELIVERED' })).toBe(true)
    expect(hasActiveFilters({ ...DEFAULT_ADMIN_ORDER_QUERY, to: '2026-09-16' })).toBe(true)
  })

  it('기간 역전은 둘 다 있고 from > to일 때만', () => {
    expect(isPeriodInverted({ from: '2026-09-16', to: '2026-09-01' })).toBe(true)
    expect(isPeriodInverted({ from: '2026-09-01', to: '2026-09-01' })).toBe(false)
    expect(isPeriodInverted({ from: '2026-09-16', to: null })).toBe(false)
  })
})
