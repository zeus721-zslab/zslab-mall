import { describe, it, expect } from 'vitest'
import {
  DEFAULT_SELLER_INVENTORY_QUERY,
  hasActiveInventoryFilters,
  normalizeProductPublicId,
  parseSellerInventoryQuery,
  toSellerInventoryApiParams,
  toSellerInventoryRouteQuery,
} from '#layers/seller/app/lib/seller-inventory-query'
import { inventoryItemLabel, isOutOfStock, validateInventoryAdjustForm } from '#layers/seller/app/lib/seller-product-view'

// Track 90-C-3: 재고 목록 URL query ↔ 화면 상태 ↔ BE 파라미터(keyword·productPublicId·page·size) + 품절 판정·입출고 폼 검증·행 라벨.
const PRODUCT_ID = 'prd_01HZZZZZZZZZZZZZZZZZZZZZZZ'

describe('parseSellerInventoryQuery', () => {
  it('빈 query → 기본 상태', () => {
    expect(parseSellerInventoryQuery({})).toEqual(DEFAULT_SELLER_INVENTORY_QUERY)
  })

  it('전체 query 파싱·trim · productPublicId는 prd_+26자 형식만 통과', () => {
    expect(parseSellerInventoryQuery({ keyword: ' SKU-1 ', productPublicId: PRODUCT_ID, page: '1', size: '100' }))
      .toEqual({ keyword: 'SKU-1', productPublicId: PRODUCT_ID, page: 1, size: 100 })
    expect(normalizeProductPublicId('prd_short')).toBeNull()
    expect(normalizeProductPublicId('var_01HZZZZZZZZZZZZZZZZZZZZZZZ')).toBeNull()
    expect(parseSellerInventoryQuery({ productPublicId: '../etc' }).productPublicId).toBeNull()
  })

  it('기본값 항목은 URL에서 생략 · API는 page/size 항상 포함 · 왕복 보존', () => {
    expect(toSellerInventoryRouteQuery(DEFAULT_SELLER_INVENTORY_QUERY)).toEqual({})
    expect(toSellerInventoryApiParams(DEFAULT_SELLER_INVENTORY_QUERY)).toEqual({ page: 0, size: 20 })
    const state = { keyword: '주전자', productPublicId: PRODUCT_ID, page: 2, size: 50 }
    expect(toSellerInventoryRouteQuery(state)).toEqual({ keyword: '주전자', productPublicId: PRODUCT_ID, page: '2', size: '50' })
    expect(toSellerInventoryApiParams(state)).toEqual({ page: 2, size: 50, keyword: '주전자', productPublicId: PRODUCT_ID })
    expect(parseSellerInventoryQuery(toSellerInventoryRouteQuery(state) as Record<string, string>)).toEqual(state)
  })

  it('hasActiveInventoryFilters: 검색어 또는 상품 한정', () => {
    expect(hasActiveInventoryFilters(DEFAULT_SELLER_INVENTORY_QUERY)).toBe(false)
    expect(hasActiveInventoryFilters({ ...DEFAULT_SELLER_INVENTORY_QUERY, page: 3 })).toBe(false)
    expect(hasActiveInventoryFilters({ ...DEFAULT_SELLER_INVENTORY_QUERY, productPublicId: PRODUCT_ID })).toBe(true)
  })
})

describe('seller-product-view', () => {
  it('isOutOfStock: 가용 0 이하', () => {
    expect(isOutOfStock({ quantityAvailable: 0 })).toBe(true)
    expect(isOutOfStock({ quantityAvailable: -1 })).toBe(true)
    expect(isOutOfStock({ quantityAvailable: 1 })).toBe(false)
  })

  it('validateInventoryAdjustForm: 수량 양의 정수·사유 필수·255자', () => {
    expect(validateInventoryAdjustForm({ quantity: '', reason: '' })).toEqual({ quantity: '수량은 1 이상의 정수여야 합니다.', reason: '사유를 입력하세요.' })
    expect(validateInventoryAdjustForm({ quantity: '0', reason: '입고' })).toHaveProperty('quantity')
    expect(validateInventoryAdjustForm({ quantity: '1.5', reason: '입고' })).toHaveProperty('quantity')
    expect(validateInventoryAdjustForm({ quantity: '-3', reason: '입고' })).toHaveProperty('quantity')
    expect(validateInventoryAdjustForm({ quantity: '5', reason: 'x'.repeat(256) })).toEqual({ reason: '사유는 255자 이하여야 합니다.' })
    expect(validateInventoryAdjustForm({ quantity: ' 5 ', reason: ' 입고 ' })).toEqual({})
  })

  it('inventoryItemLabel: 상품명 (옵션) · 옵션 없으면 괄호 생략 · 상품명 없으면 대시', () => {
    expect(inventoryItemLabel({ productName: '반찬통', optionLabel: '색상: 블랙' })).toBe('반찬통 (색상: 블랙)')
    expect(inventoryItemLabel({ productName: '반찬통' })).toBe('반찬통')
    expect(inventoryItemLabel({ optionLabel: '색상: 블랙' })).toBe('— (색상: 블랙)')
  })
})
