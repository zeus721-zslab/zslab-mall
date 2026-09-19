import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type { AdminSellerApiParams, AdminSellerListQuery } from '#layers/admin/app/types/admin-seller'
import {
  ADMIN_SELLER_KEYWORD_MAX,
  ADMIN_SELLER_PAGE_SIZES,
  ADMIN_SELLER_STATUSES,
  DEFAULT_ADMIN_SELLER_PAGE_SIZE,
  type AdminSellerStatus,
} from '#layers/admin/app/lib/constants/admin-seller'

/**
 * 관리자 셀러 목록 상태 ↔ URL query ↔ BE 파라미터 순수 매핑(FE-40·admin-member-query 패턴). URL이 단일 소스라 새로고침·뒤로가기에도
 * 필터가 유지된다. 기본값과 같은 항목은 URL에서 생략하고 잘못된 값(미지의 status·음수 page·허용 외 size)은 기본값으로 정규화한다.
 * 정렬은 BE 고정(등록일 desc)이라 파라미터가 없다.
 */

export const DEFAULT_ADMIN_SELLER_QUERY: AdminSellerListQuery = {
  status: null,
  keyword: '',
  page: 0,
  size: DEFAULT_ADMIN_SELLER_PAGE_SIZE,
}

function first(value: LocationQuery[string] | undefined): string | null {
  const single = Array.isArray(value) ? value[0] : value
  return typeof single === 'string' && single !== '' ? single : null
}

function isStatus(value: string): value is AdminSellerStatus {
  return (ADMIN_SELLER_STATUSES as string[]).includes(value)
}

export function parseAdminSellerQuery(query: LocationQuery): AdminSellerListQuery {
  const status = first(query.status)
  const page = Number(first(query.page))
  const size = Number(first(query.size))
  return {
    status: status && isStatus(status) ? status : null,
    keyword: (first(query.keyword)?.trim() ?? '').slice(0, ADMIN_SELLER_KEYWORD_MAX),
    page: Number.isInteger(page) && page > 0 ? page : 0,
    size: ADMIN_SELLER_PAGE_SIZES.includes(size) ? size : DEFAULT_ADMIN_SELLER_PAGE_SIZE,
  }
}

export function toAdminSellerRouteQuery(state: AdminSellerListQuery): LocationQueryRaw {
  const query: LocationQueryRaw = {}
  if (state.status) query.status = state.status
  if (state.keyword.trim() !== '') query.keyword = state.keyword.trim()
  if (state.page > 0) query.page = String(state.page)
  if (state.size !== DEFAULT_ADMIN_SELLER_PAGE_SIZE) query.size = String(state.size)
  return query
}

export function toAdminSellerApiParams(state: AdminSellerListQuery): AdminSellerApiParams {
  const params: AdminSellerApiParams = { page: state.page, size: state.size }
  if (state.status) params.status = state.status
  const keyword = state.keyword.trim()
  if (keyword !== '') params.keyword = keyword
  return params
}

export function hasActiveFilters(state: AdminSellerListQuery): boolean {
  return state.status !== null || state.keyword.trim() !== ''
}
