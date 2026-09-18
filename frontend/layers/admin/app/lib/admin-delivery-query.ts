import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type { AdminDeliveryApiParams, AdminDeliveryListQuery } from '#layers/admin/app/types/admin-delivery'
import {
  ADMIN_DELIVERY_CARRIER_LABEL,
  ADMIN_DELIVERY_STATUS_LABEL,
  type AdminDeliveryCarrier,
  type AdminDeliveryStatus,
} from '#layers/admin/app/lib/constants/admin-order'
import {
  ADMIN_DELIVERY_KEYWORD_MAX,
  ADMIN_DELIVERY_PAGE_SIZES,
  ADMIN_DELIVERY_SCOPE_LABEL,
  ADMIN_DELIVERY_SORT_OPTIONS,
  DEFAULT_ADMIN_DELIVERY_PAGE_SIZE,
  DEFAULT_ADMIN_DELIVERY_SCOPE,
  DEFAULT_ADMIN_DELIVERY_SORT,
  type AdminDeliveryScope,
  type AdminDeliverySort,
} from '#layers/admin/app/lib/constants/admin-delivery'
import { normalizeDateOnly, toPeriodEnd, toPeriodStart } from '#layers/admin/app/lib/admin-order-query'

/**
 * 관리자 배송 목록 상태 ↔ URL query ↔ BE 파라미터 순수 매핑(FE-37·admin-order-query 패턴). URL이 단일 소스라 새로고침·뒤로가기에도
 * 필터가 유지된다. 기본값과 같은 항목(scope=ORIGINAL·sort=LATEST·page 0·size 20)은 URL에서 생략하고, 잘못된 값은 기본값으로 정규화한다.
 * 기간은 발송일(shipped_at) 기준이며 URL·화면 yyyy-MM-dd → BE from=T00:00:00·to=T23:59:59.
 */

export const DEFAULT_ADMIN_DELIVERY_QUERY: AdminDeliveryListQuery = {
  keyword: '',
  scope: DEFAULT_ADMIN_DELIVERY_SCOPE,
  status: null,
  carrier: null,
  from: null,
  to: null,
  sort: DEFAULT_ADMIN_DELIVERY_SORT,
  page: 0,
  size: DEFAULT_ADMIN_DELIVERY_PAGE_SIZE,
}

function first(value: LocationQuery[string] | undefined): string | null {
  const single = Array.isArray(value) ? value[0] : value
  return typeof single === 'string' && single !== '' ? single : null
}

function isScope(value: string): value is AdminDeliveryScope {
  return value in ADMIN_DELIVERY_SCOPE_LABEL
}

function isDeliveryStatus(value: string): value is AdminDeliveryStatus {
  return value in ADMIN_DELIVERY_STATUS_LABEL
}

function isCarrier(value: string): value is AdminDeliveryCarrier {
  return value in ADMIN_DELIVERY_CARRIER_LABEL
}

function isSort(value: string): value is AdminDeliverySort {
  return ADMIN_DELIVERY_SORT_OPTIONS.some((option) => option.value === value)
}

/** route.query → 화면 상태. 검색어는 BE 한도(50자)로 잘라 400을 예방한다. */
export function parseAdminDeliveryQuery(query: LocationQuery): AdminDeliveryListQuery {
  const scope = first(query.scope)
  const status = first(query.status)
  const carrier = first(query.carrier)
  const sort = first(query.sort)
  const page = Number(first(query.page))
  const size = Number(first(query.size))
  return {
    keyword: (first(query.keyword)?.trim() ?? '').slice(0, ADMIN_DELIVERY_KEYWORD_MAX),
    scope: scope && isScope(scope) ? scope : DEFAULT_ADMIN_DELIVERY_SCOPE,
    status: status && isDeliveryStatus(status) ? status : null,
    carrier: carrier && isCarrier(carrier) ? carrier : null,
    from: normalizeDateOnly(first(query.from)),
    to: normalizeDateOnly(first(query.to)),
    sort: sort && isSort(sort) ? sort : DEFAULT_ADMIN_DELIVERY_SORT,
    page: Number.isInteger(page) && page > 0 ? page : 0,
    size: ADMIN_DELIVERY_PAGE_SIZES.includes(size) ? size : DEFAULT_ADMIN_DELIVERY_PAGE_SIZE,
  }
}

/** 화면 상태 → router.replace용 query(기본값 항목 생략). */
export function toAdminDeliveryRouteQuery(state: AdminDeliveryListQuery): LocationQueryRaw {
  const query: LocationQueryRaw = {}
  if (state.keyword.trim() !== '') query.keyword = state.keyword.trim()
  if (state.scope !== DEFAULT_ADMIN_DELIVERY_SCOPE) query.scope = state.scope
  if (state.status) query.status = state.status
  if (state.carrier) query.carrier = state.carrier
  if (state.from) query.from = state.from
  if (state.to) query.to = state.to
  if (state.sort !== DEFAULT_ADMIN_DELIVERY_SORT) query.sort = state.sort
  if (state.page > 0) query.page = String(state.page)
  if (state.size !== DEFAULT_ADMIN_DELIVERY_PAGE_SIZE) query.size = String(state.size)
  return query
}

/** 화면 상태 → BE GET /admin/deliveries 파라미터(scope·sort·page·size는 항상 포함·null·빈 값 제외·기간은 시각 부착). */
export function toAdminDeliveryApiParams(state: AdminDeliveryListQuery): AdminDeliveryApiParams {
  const params: AdminDeliveryApiParams = { scope: state.scope, sort: state.sort, page: state.page, size: state.size }
  const keyword = state.keyword.trim()
  if (keyword !== '') params.keyword = keyword
  if (state.status) params.status = state.status
  if (state.carrier) params.carrier = state.carrier
  if (state.from) params.from = toPeriodStart(state.from)
  if (state.to) params.to = toPeriodEnd(state.to)
  return params
}

/** 필터(검색·범위·상태·택배사·기간)가 하나라도 걸려 있는지 — 빈 상태 문구 분기용. scope는 기본값이 아닐 때만 필터로 본다. */
export function hasActiveFilters(state: AdminDeliveryListQuery): boolean {
  return (
    state.keyword.trim() !== '' ||
    state.scope !== DEFAULT_ADMIN_DELIVERY_SCOPE ||
    state.status !== null ||
    state.carrier !== null ||
    state.from !== null ||
    state.to !== null
  )
}
