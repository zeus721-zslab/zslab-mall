import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type { AdminProductApiParams, AdminProductListQuery } from '#layers/admin/app/types/admin-product'
import {
  ADMIN_PRODUCT_PAGE_SIZES,
  ADMIN_PRODUCT_SORT_OPTIONS,
  ADMIN_PRODUCT_STATUS_LABEL,
  ADMIN_PRODUCT_STOCK_FILTER_OPTIONS,
  DEFAULT_ADMIN_PRODUCT_PAGE_SIZE,
  DEFAULT_ADMIN_PRODUCT_SORT,
  type AdminProductSort,
  type AdminProductStatus,
  type AdminProductStockFilter,
} from '#layers/admin/app/lib/constants/product'

/**
 * 관리자 상품 목록 상태 ↔ URL query ↔ BE 파라미터 순수 매핑(FE-25). URL이 단일 소스라 새로고침·뒤로가기에도 필터가 유지된다.
 * 기본값과 같은 항목은 URL에서 생략해 주소를 짧게 유지하고, 잘못된 값(미지의 status·sort·음수 page)은 기본값으로 정규화한다.
 */

export const DEFAULT_ADMIN_PRODUCT_QUERY: AdminProductListQuery = {
  keyword: '',
  status: null,
  soldOut: null,
  sellerPublicId: null,
  categoryId: null,
  stockFilter: null,
  sort: DEFAULT_ADMIN_PRODUCT_SORT,
  page: 0,
  size: DEFAULT_ADMIN_PRODUCT_PAGE_SIZE,
}

function first(value: LocationQuery[string] | undefined): string | null {
  const single = Array.isArray(value) ? value[0] : value
  return typeof single === 'string' && single !== '' ? single : null
}

function isStatus(value: string): value is AdminProductStatus {
  return value in ADMIN_PRODUCT_STATUS_LABEL
}

function isStockFilter(value: string): value is AdminProductStockFilter {
  return ADMIN_PRODUCT_STOCK_FILTER_OPTIONS.some((option) => option.value === value)
}

function isSort(value: string): value is AdminProductSort {
  return ADMIN_PRODUCT_SORT_OPTIONS.some((option) => option.value === value)
}

/** route.query → 화면 상태. */
export function parseAdminProductQuery(query: LocationQuery): AdminProductListQuery {
  const status = first(query.status)
  const soldOut = first(query.soldOut)
  const categoryId = Number(first(query.categoryId))
  const stockFilter = first(query.stockFilter)
  const sort = first(query.sort)
  const page = Number(first(query.page))
  const size = Number(first(query.size))
  return {
    keyword: first(query.keyword)?.trim() ?? '',
    status: status && isStatus(status) ? status : null,
    soldOut: soldOut === 'true' ? true : soldOut === 'false' ? false : null,
    sellerPublicId: first(query.sellerPublicId),
    categoryId: Number.isInteger(categoryId) && categoryId > 0 ? categoryId : null,
    stockFilter: stockFilter && isStockFilter(stockFilter) ? stockFilter : null,
    sort: sort && isSort(sort) ? sort : DEFAULT_ADMIN_PRODUCT_SORT,
    page: Number.isInteger(page) && page > 0 ? page : 0,
    size: ADMIN_PRODUCT_PAGE_SIZES.includes(size) ? size : DEFAULT_ADMIN_PRODUCT_PAGE_SIZE,
  }
}

/** 화면 상태 → router.replace용 query(기본값 항목 생략). */
export function toAdminProductRouteQuery(state: AdminProductListQuery): LocationQueryRaw {
  const query: LocationQueryRaw = {}
  if (state.keyword.trim() !== '') query.keyword = state.keyword.trim()
  if (state.status) query.status = state.status
  if (state.soldOut !== null) query.soldOut = String(state.soldOut)
  if (state.sellerPublicId) query.sellerPublicId = state.sellerPublicId
  if (state.categoryId !== null) query.categoryId = String(state.categoryId)
  if (state.stockFilter) query.stockFilter = state.stockFilter
  if (state.sort !== DEFAULT_ADMIN_PRODUCT_SORT) query.sort = state.sort
  if (state.page > 0) query.page = String(state.page)
  if (state.size !== DEFAULT_ADMIN_PRODUCT_PAGE_SIZE) query.size = String(state.size)
  return query
}

/** 화면 상태 → BE GET /admin/products 파라미터(null·빈 값 제외·page/size/sort는 항상 포함). */
export function toAdminProductApiParams(state: AdminProductListQuery): AdminProductApiParams {
  const params: AdminProductApiParams = { sort: state.sort, page: state.page, size: state.size }
  const keyword = state.keyword.trim()
  if (keyword !== '') params.keyword = keyword
  if (state.status) params.status = state.status
  if (state.soldOut !== null) params.soldOut = state.soldOut
  if (state.sellerPublicId) params.sellerPublicId = state.sellerPublicId
  if (state.categoryId !== null) params.categoryId = state.categoryId
  if (state.stockFilter) params.stockFilter = state.stockFilter
  return params
}

/** 필터(검색·상태·품절·셀러·카테고리·재고)가 하나라도 걸려 있는지 — 빈 상태 문구("결과 없음" vs "상품 없음") 분기용. */
export function hasActiveFilters(state: AdminProductListQuery): boolean {
  return (
    state.keyword.trim() !== '' ||
    state.status !== null ||
    state.soldOut !== null ||
    state.sellerPublicId !== null ||
    state.categoryId !== null ||
    state.stockFilter !== null
  )
}
