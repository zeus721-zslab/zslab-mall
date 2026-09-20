import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type { SellerInventoryListQuery, SellerProductApiParams } from '#layers/seller/app/types/seller-product'
import {
  DEFAULT_SELLER_PRODUCT_PAGE_SIZE,
  SELLER_PRODUCT_KEYWORD_MAX,
  SELLER_PRODUCT_PAGE_SIZES,
} from '#layers/seller/app/lib/constants/seller-product'

/**
 * 셀러 재고 목록 상태 ↔ URL query ↔ BE 파라미터 순수 매핑(Track 90-C-3·seller-product-query 패턴). productPublicId는 `prd_` 접두 형식만 통과
 * (URL 오염값 방어·타 셀러 상품 id는 BE가 빈 결과로 돌려준다).
 */

export const DEFAULT_SELLER_INVENTORY_QUERY: SellerInventoryListQuery = {
  keyword: '',
  productPublicId: null,
  page: 0,
  size: DEFAULT_SELLER_PRODUCT_PAGE_SIZE,
}

/** ULID+접두 public_id(prd_ + 26자). */
const PRODUCT_PUBLIC_ID_PATTERN = /^prd_[0-9A-Za-z]{26}$/

function first(value: LocationQuery[string] | undefined): string | null {
  const single = Array.isArray(value) ? value[0] : value
  return typeof single === 'string' && single !== '' ? single : null
}

/** prd_ 형식만 통과. */
export function normalizeProductPublicId(value: string | null): string | null {
  return value !== null && PRODUCT_PUBLIC_ID_PATTERN.test(value) ? value : null
}

/** route.query → 화면 상태. 검색어는 BE 한도(50자)로 잘라 400을 예방한다. */
export function parseSellerInventoryQuery(query: LocationQuery): SellerInventoryListQuery {
  const page = Number(first(query.page))
  const size = Number(first(query.size))
  return {
    keyword: (first(query.keyword)?.trim() ?? '').slice(0, SELLER_PRODUCT_KEYWORD_MAX),
    productPublicId: normalizeProductPublicId(first(query.productPublicId)),
    page: Number.isInteger(page) && page > 0 ? page : 0,
    size: SELLER_PRODUCT_PAGE_SIZES.includes(size) ? size : DEFAULT_SELLER_PRODUCT_PAGE_SIZE,
  }
}

/** 화면 상태 → router.replace용 query(기본값 항목 생략). */
export function toSellerInventoryRouteQuery(state: SellerInventoryListQuery): LocationQueryRaw {
  const query: LocationQueryRaw = {}
  if (state.keyword.trim() !== '') query.keyword = state.keyword.trim()
  if (state.productPublicId) query.productPublicId = state.productPublicId
  if (state.page > 0) query.page = String(state.page)
  if (state.size !== DEFAULT_SELLER_PRODUCT_PAGE_SIZE) query.size = String(state.size)
  return query
}

/** 화면 상태 → BE GET /seller/inventories 파라미터(null·빈 값 제외·page/size는 항상 포함). */
export function toSellerInventoryApiParams(state: SellerInventoryListQuery): SellerProductApiParams {
  const params: SellerProductApiParams = { page: state.page, size: state.size }
  const keyword = state.keyword.trim()
  if (keyword !== '') params.keyword = keyword
  if (state.productPublicId) params.productPublicId = state.productPublicId
  return params
}

/** 필터(검색·상품 한정)가 하나라도 걸려 있는지 — 빈 상태 문구 분기용. */
export function hasActiveInventoryFilters(state: SellerInventoryListQuery): boolean {
  return state.keyword.trim() !== '' || state.productPublicId !== null
}
