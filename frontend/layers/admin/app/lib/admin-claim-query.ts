import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type { AdminClaimApiParams, AdminClaimListQuery } from '#layers/admin/app/types/admin-claim'
import { CLAIM_STATUS_LABELS, REFUND_STATUS_LABELS, isClaimType, type ClaimStatus, type RefundStatus } from '~/lib/constants/claim'
import {
  ADMIN_ORDER_KEYWORD_MAX,
  ADMIN_ORDER_PAGE_SIZES,
  ADMIN_ORDER_SORT_OPTIONS,
  DEFAULT_ADMIN_ORDER_PAGE_SIZE,
  DEFAULT_ADMIN_ORDER_SORT,
  type AdminOrderSort,
} from '#layers/admin/app/lib/constants/admin-order'
import { normalizeDateOnly, toPeriodEnd, toPeriodStart } from '#layers/admin/app/lib/admin-order-query'

/**
 * 관리자 클레임 목록 상태 ↔ URL query ↔ BE 파라미터 순수 매핑(FE-28·admin-order-query 패턴 1:1). URL이 단일 소스라 새로고침·뒤로가기에도
 * 유형 탭·필터가 유지된다. 기본값과 같은 항목은 URL에서 생략하고, 잘못된 값(미지의 type·status·sort·날짜 형식·음수 page)은 기본값으로
 * 정규화한다. 기간 변환(yyyy-MM-dd → T00:00:00/T23:59:59)은 FE-27 함수를 재사용한다.
 */

export const DEFAULT_ADMIN_CLAIM_QUERY: AdminClaimListQuery = {
  type: null,
  status: null,
  refundStatus: null,
  keyword: '',
  from: null,
  to: null,
  sort: DEFAULT_ADMIN_ORDER_SORT,
  page: 0,
  size: DEFAULT_ADMIN_ORDER_PAGE_SIZE,
}

function first(value: LocationQuery[string] | undefined): string | null {
  const single = Array.isArray(value) ? value[0] : value
  return typeof single === 'string' && single !== '' ? single : null
}

function isClaimStatus(value: string): value is ClaimStatus {
  return value in CLAIM_STATUS_LABELS
}

function isRefundStatus(value: string): value is RefundStatus {
  return value in REFUND_STATUS_LABELS
}

function isSort(value: string): value is AdminOrderSort {
  return ADMIN_ORDER_SORT_OPTIONS.some((option) => option.value === value)
}

/** route.query → 화면 상태. 검색어는 BE 한도(50자)로 잘라 400을 예방한다. */
export function parseAdminClaimQuery(query: LocationQuery): AdminClaimListQuery {
  const type = first(query.type)
  const status = first(query.status)
  const refundStatus = first(query.refundStatus)
  const sort = first(query.sort)
  const page = Number(first(query.page))
  const size = Number(first(query.size))
  return {
    type: type && isClaimType(type) ? type : null,
    status: status && isClaimStatus(status) ? status : null,
    refundStatus: refundStatus && isRefundStatus(refundStatus) ? refundStatus : null,
    keyword: (first(query.keyword)?.trim() ?? '').slice(0, ADMIN_ORDER_KEYWORD_MAX),
    from: normalizeDateOnly(first(query.from)),
    to: normalizeDateOnly(first(query.to)),
    sort: sort && isSort(sort) ? sort : DEFAULT_ADMIN_ORDER_SORT,
    page: Number.isInteger(page) && page > 0 ? page : 0,
    size: ADMIN_ORDER_PAGE_SIZES.includes(size) ? size : DEFAULT_ADMIN_ORDER_PAGE_SIZE,
  }
}

/** 화면 상태 → router.replace용 query(기본값 항목 생략). */
export function toAdminClaimRouteQuery(state: AdminClaimListQuery): LocationQueryRaw {
  const query: LocationQueryRaw = {}
  if (state.type) query.type = state.type
  if (state.status) query.status = state.status
  if (state.refundStatus) query.refundStatus = state.refundStatus
  if (state.keyword.trim() !== '') query.keyword = state.keyword.trim()
  if (state.from) query.from = state.from
  if (state.to) query.to = state.to
  if (state.sort !== DEFAULT_ADMIN_ORDER_SORT) query.sort = state.sort
  if (state.page > 0) query.page = String(state.page)
  if (state.size !== DEFAULT_ADMIN_ORDER_PAGE_SIZE) query.size = String(state.size)
  return query
}

/** 화면 상태 → BE GET /admin/claims 파라미터(null·빈 값 제외·page/size/sort는 항상 포함·기간은 시각 부착). */
export function toAdminClaimApiParams(state: AdminClaimListQuery): AdminClaimApiParams {
  const params: AdminClaimApiParams = { sort: state.sort, page: state.page, size: state.size }
  if (state.type) params.type = state.type
  if (state.status) params.status = state.status
  if (state.refundStatus) params.refundStatus = state.refundStatus
  const keyword = state.keyword.trim()
  if (keyword !== '') params.keyword = keyword
  if (state.from) params.from = toPeriodStart(state.from)
  if (state.to) params.to = toPeriodEnd(state.to)
  return params
}

/** 필터(상태·환불 상태·기간·검색)가 하나라도 걸려 있는지 — 빈 상태 문구 분기용. 유형 탭은 필터로 치지 않는다. */
export function hasActiveClaimFilters(state: AdminClaimListQuery): boolean {
  return state.status !== null || state.refundStatus !== null || state.keyword.trim() !== '' || state.from !== null || state.to !== null
}
