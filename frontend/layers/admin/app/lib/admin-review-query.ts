import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type { AdminReviewApiParams, AdminReviewListQuery } from '#layers/admin/app/types/admin-review'
import { ADMIN_REVIEW_PAGE_SIZES, DEFAULT_ADMIN_REVIEW_PAGE_SIZE } from '#layers/admin/app/lib/constants/admin-review'
import { isReviewStatus } from '~/lib/constants/review'

/**
 * 관리자 리뷰 목록 상태 ↔ URL query ↔ BE 파라미터 순수 매핑(Track 106-1 · admin-seller-query 패턴). 기본값과 같은 항목은 URL에서 빼고,
 * 잘못된 값(모르는 status·음수 page·허용 밖 size)은 기본값으로 정규화한다. 필터는 BE가 받는 상태 하나뿐이다.
 */

export const DEFAULT_ADMIN_REVIEW_QUERY: AdminReviewListQuery = {
  status: null,
  page: 0,
  size: DEFAULT_ADMIN_REVIEW_PAGE_SIZE,
}

function first(value: LocationQuery[string] | undefined): string | null {
  const single = Array.isArray(value) ? value[0] : value
  return typeof single === 'string' && single !== '' ? single : null
}

export function parseAdminReviewQuery(query: LocationQuery): AdminReviewListQuery {
  const status = first(query.status)
  const page = Number(first(query.page))
  const size = Number(first(query.size))
  return {
    status: isReviewStatus(status) ? status : null,
    page: Number.isInteger(page) && page > 0 ? page : 0,
    size: ADMIN_REVIEW_PAGE_SIZES.includes(size) ? size : DEFAULT_ADMIN_REVIEW_PAGE_SIZE,
  }
}

export function toAdminReviewRouteQuery(state: AdminReviewListQuery): LocationQueryRaw {
  const query: LocationQueryRaw = {}
  if (state.status) query.status = state.status
  if (state.page > 0) query.page = String(state.page)
  if (state.size !== DEFAULT_ADMIN_REVIEW_PAGE_SIZE) query.size = String(state.size)
  return query
}

export function toAdminReviewApiParams(state: AdminReviewListQuery): AdminReviewApiParams {
  const params: AdminReviewApiParams = { page: state.page, size: state.size }
  if (state.status) params.status = state.status
  return params
}

export function hasActiveReviewFilters(state: AdminReviewListQuery): boolean {
  return state.status !== null
}
