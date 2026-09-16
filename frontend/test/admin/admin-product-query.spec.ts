import { describe, it, expect } from 'vitest'
import {
  DEFAULT_ADMIN_PRODUCT_QUERY,
  hasActiveFilters,
  parseAdminProductQuery,
  toAdminProductApiParams,
  toAdminProductRouteQuery,
} from '#layers/admin/app/lib/admin-product-query'

// FE-25: URL query ↔ 화면 상태 ↔ BE 파라미터 순수 매핑.
describe('parseAdminProductQuery', () => {
  it('빈 query → 기본 상태(LATEST·page 0·size 20·필터 없음)', () => {
    expect(parseAdminProductQuery({})).toEqual(DEFAULT_ADMIN_PRODUCT_QUERY)
  })

  it('전체 query 파싱·trim·타입 변환', () => {
    expect(parseAdminProductQuery({
      keyword: ' 티셔츠 ', status: 'STOPPED', soldOut: 'true', sellerPublicId: 'slr_1', categoryId: '7', sort: 'NAME', page: '2', size: '50',
    })).toEqual({
      keyword: '티셔츠', status: 'STOPPED', soldOut: true, sellerPublicId: 'slr_1', categoryId: 7, sort: 'NAME', page: 2, size: 50,
    })
  })

  it('잘못된 값은 기본값으로 정규화(미지의 status·sort·음수 page·허용 외 size·NaN categoryId·soldOut=maybe)', () => {
    expect(parseAdminProductQuery({ status: 'BOGUS', sort: 'RANDOM', page: '-1', size: '33', categoryId: 'abc', soldOut: 'maybe' }))
      .toEqual(DEFAULT_ADMIN_PRODUCT_QUERY)
  })

  it('배열 query는 첫 값만 사용', () => {
    expect(parseAdminProductQuery({ status: ['SALE', 'STOPPED'] }).status).toBe('SALE')
  })
})

describe('toAdminProductRouteQuery', () => {
  it('기본값 항목은 생략(빈 객체)', () => {
    expect(toAdminProductRouteQuery(DEFAULT_ADMIN_PRODUCT_QUERY)).toEqual({})
  })

  it('비기본값만 문자열로 직렬화·왕복 시 동일', () => {
    const state = { ...DEFAULT_ADMIN_PRODUCT_QUERY, keyword: 'x', soldOut: false, categoryId: 3, sort: 'PRICE_ASC' as const, page: 1, size: 100 }
    const query = toAdminProductRouteQuery(state)
    expect(query).toEqual({ keyword: 'x', soldOut: 'false', categoryId: '3', sort: 'PRICE_ASC', page: '1', size: '100' })
    expect(parseAdminProductQuery(query)).toEqual(state)
  })
})

describe('toAdminProductApiParams', () => {
  it('sort·page·size는 항상 포함·null/빈 필터 제외', () => {
    expect(toAdminProductApiParams(DEFAULT_ADMIN_PRODUCT_QUERY)).toEqual({ sort: 'LATEST', page: 0, size: 20 })
  })

  it('필터가 있으면 BE 파라미터명 그대로(soldOut boolean·categoryId number)', () => {
    expect(toAdminProductApiParams({ ...DEFAULT_ADMIN_PRODUCT_QUERY, keyword: ' prd_1 ', status: 'SALE', soldOut: true, sellerPublicId: 's', categoryId: 2 }))
      .toEqual({ sort: 'LATEST', page: 0, size: 20, keyword: 'prd_1', status: 'SALE', soldOut: true, sellerPublicId: 's', categoryId: 2 })
  })
})

describe('hasActiveFilters', () => {
  it('정렬·페이지만 바뀐 상태는 필터 없음, 검색어/상태 등 하나라도 있으면 true', () => {
    expect(hasActiveFilters({ ...DEFAULT_ADMIN_PRODUCT_QUERY, sort: 'NAME', page: 3 })).toBe(false)
    expect(hasActiveFilters({ ...DEFAULT_ADMIN_PRODUCT_QUERY, keyword: 'a' })).toBe(true)
    expect(hasActiveFilters({ ...DEFAULT_ADMIN_PRODUCT_QUERY, soldOut: false })).toBe(true)
  })
})
