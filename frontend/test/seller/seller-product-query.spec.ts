import { describe, it, expect } from 'vitest'
import {
  DEFAULT_SELLER_PRODUCT_QUERY,
  hasActiveProductFilters,
  parseSellerProductQuery,
  toSellerProductApiParams,
  toSellerProductRouteQuery,
} from '#layers/seller/app/lib/seller-product-query'
import { SELLER_PRODUCT_STATUS_OPTIONS, SELLER_PRODUCT_SORT_OPTIONS } from '#layers/seller/app/lib/constants/seller-product'
import { SELLER_MENU, resolveActiveSellerMenuPath } from '#layers/seller/app/lib/constants/seller-menu'
import { toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'

// Track 90-C-3: 상품 목록 URL query ↔ 화면 상태 ↔ BE 파라미터(keyword·status·categoryId·sort·page·size) + 메뉴 활성화 + 에러 문구 등록.
describe('parseSellerProductQuery', () => {
  it('빈 query → 기본 상태(page 0·size 20·LATEST·필터 없음)', () => {
    expect(parseSellerProductQuery({})).toEqual(DEFAULT_SELLER_PRODUCT_QUERY)
  })

  it('전체 query 파싱·trim·타입 변환', () => {
    expect(parseSellerProductQuery({ keyword: ' 반찬통 ', status: 'PENDING', categoryId: '7', sort: 'PRICE_DESC', page: '2', size: '50' }))
      .toEqual({ keyword: '반찬통', status: 'PENDING', categoryId: 7, sort: 'PRICE_DESC', page: 2, size: 50 })
  })

  it('잘못된 값은 기본값으로(미지 status·sort·비정수/0 categoryId·음수 page·허용 외 size) · 검색어 50자 절단 · 배열은 첫 값', () => {
    expect(parseSellerProductQuery({ status: 'BOGUS', sort: 'RANDOM', categoryId: 'abc', page: '-1', size: '33' })).toEqual(DEFAULT_SELLER_PRODUCT_QUERY)
    expect(parseSellerProductQuery({ categoryId: '0' }).categoryId).toBeNull()
    expect(parseSellerProductQuery({ keyword: 'a'.repeat(60) }).keyword).toHaveLength(50)
    expect(parseSellerProductQuery({ status: ['STOPPED', 'SALE'] }).status).toBe('STOPPED')
  })

  it('상태 옵션 4종(판매중·승인대기·판매중지·반려) · 정렬 옵션 4종(BE SellerProductSort 1:1)', () => {
    expect(SELLER_PRODUCT_STATUS_OPTIONS.map((option) => option.value)).toEqual(['SALE', 'PENDING', 'STOPPED', 'REJECTED'])
    expect(SELLER_PRODUCT_SORT_OPTIONS.map((option) => option.value)).toEqual(['LATEST', 'NAME', 'PRICE_ASC', 'PRICE_DESC'])
  })
})

describe('toSellerProductRouteQuery · toSellerProductApiParams', () => {
  it('기본값 항목은 URL에서 생략 · API는 page/size/sort 항상 포함·null은 제외', () => {
    expect(toSellerProductRouteQuery(DEFAULT_SELLER_PRODUCT_QUERY)).toEqual({})
    expect(toSellerProductApiParams(DEFAULT_SELLER_PRODUCT_QUERY)).toEqual({ page: 0, size: 20, sort: 'LATEST' })
    const state = { keyword: ' 반찬통 ', status: 'SALE' as const, categoryId: 7, sort: 'NAME' as const, page: 1, size: 50 }
    expect(toSellerProductRouteQuery(state)).toEqual({ keyword: '반찬통', status: 'SALE', categoryId: '7', sort: 'NAME', page: '1', size: '50' })
    expect(toSellerProductApiParams(state)).toEqual({ page: 1, size: 50, sort: 'NAME', keyword: '반찬통', status: 'SALE', categoryId: 7 })
  })

  it('왕복: parse(toRoute(state)) === state', () => {
    const state = { keyword: '주전자', status: 'STOPPED' as const, categoryId: 3, sort: 'PRICE_ASC' as const, page: 3, size: 100 }
    expect(parseSellerProductQuery(toSellerProductRouteQuery(state) as Record<string, string>)).toEqual(state)
  })

  it('hasActiveProductFilters: 정렬·페이지는 필터가 아니다', () => {
    expect(hasActiveProductFilters(DEFAULT_SELLER_PRODUCT_QUERY)).toBe(false)
    expect(hasActiveProductFilters({ ...DEFAULT_SELLER_PRODUCT_QUERY, sort: 'NAME', page: 2 })).toBe(false)
    expect(hasActiveProductFilters({ ...DEFAULT_SELLER_PRODUCT_QUERY, categoryId: 1 })).toBe(true)
    expect(hasActiveProductFilters({ ...DEFAULT_SELLER_PRODUCT_QUERY, keyword: ' x ' })).toBe(true)
  })
})

describe('사이드바 상품·재고 활성화(90-C-3)', () => {
  it('상품 → /seller/products · 재고 → /seller/products/inventory · 나머지 미구현 항목은 여전히 비활성', () => {
    const productGroup = SELLER_MENU.find((group) => group.label === '상품')
    expect(productGroup?.children?.map((child) => [child.label, child.to])).toEqual([['상품', '/seller/products'], ['재고', '/seller/products/inventory']])
    // 비활성 = to 없는 항목: 통계 3(90-E) → 3개(90-B-3의 7개에서 상품·재고(90-C-3)·클레임(90-D-1)·비밀번호 변경(90-D-2) 4개 활성화)
    const disabled = SELLER_MENU.flatMap((group) => (group.children ? group.children.filter((child) => !child.to) : group.to ? [] : [group]))
    expect(disabled.map((item) => item.label)).toEqual(['주문·클레임', '상품'])
  })

  it('활성 판정: /seller/products/inventory는 재고만(정확 일치 우선·상품 prefix 매칭 아님) · /seller/products는 상품', () => {
    expect(resolveActiveSellerMenuPath('/seller/products/inventory')).toBe('/seller/products/inventory')
    expect(resolveActiveSellerMenuPath('/seller/products')).toBe('/seller/products')
  })
})

describe('에러 문구(90-C 상품·재고 코드)', () => {
  it('BE GlobalExceptionHandler 코드명 6종이 등록돼 상태 폴백이 아닌 전용 문구를 돌려준다', () => {
    const cases: [string, number, string][] = [
      ['PRODUCT_NOT_FOUND', 404, '상품을 찾을 수 없습니다'],
      ['PRODUCT_VARIANT_NOT_FOUND', 404, '상품 옵션(변형)을 찾을 수 없습니다'],
      ['CATEGORY_NOT_FOUND', 404, '카테고리를 찾을 수 없습니다'],
      ['PRODUCT_VARIANT_OPTION_CONFLICT', 409, '같은 옵션 조합'],
      ['PRODUCT_IMAGE_NOT_FOUND', 404, '상품 이미지를 찾을 수 없습니다'],
      ['INVENTORY_INVARIANT_VIOLATION', 422, '재고 수량이 맞지 않습니다'],
    ]
    for (const [code, status, expected] of cases) {
      expect(toSellerErrorMessage({ status, data: { code, detail: 'x' } })).toContain(expected)
    }
  })
})
