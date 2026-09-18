import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type { AdminOperatorApiParams, AdminOperatorListQuery } from '#layers/admin/app/types/admin-operator'
import {
  ADMIN_OPERATOR_KEYWORD_MAX,
  ADMIN_OPERATOR_PAGE_SIZES,
  ADMIN_OPERATOR_ROLE_LABEL,
  ADMIN_OPERATOR_STATUS_OPTIONS,
  DEFAULT_ADMIN_OPERATOR_PAGE_SIZE,
  DEFAULT_ADMIN_OPERATOR_STATUS,
  type AdminOperatorRole,
  type AdminOperatorStatus,
} from '#layers/admin/app/lib/constants/admin-operator'

/**
 * 운영자 목록 상태 ↔ URL query ↔ BE 파라미터 순수 매핑(FE-39·admin-member-query 패턴). URL이 단일 소스라 새로고침·뒤로가기에도
 * 필터가 유지된다. 기본값과 같은 항목은 URL에서 생략하고, 잘못된 값(미지의 role·status·음수 page·허용 외 size)은 기본값으로 정규화한다.
 */

export const DEFAULT_ADMIN_OPERATOR_QUERY: AdminOperatorListQuery = {
  role: null,
  status: DEFAULT_ADMIN_OPERATOR_STATUS,
  keyword: '',
  page: 0,
  size: DEFAULT_ADMIN_OPERATOR_PAGE_SIZE,
}

function first(value: LocationQuery[string] | undefined): string | null {
  const single = Array.isArray(value) ? value[0] : value
  return typeof single === 'string' && single !== '' ? single : null
}

function isRole(value: string): value is AdminOperatorRole {
  return value in ADMIN_OPERATOR_ROLE_LABEL
}

function isStatus(value: string): value is AdminOperatorStatus {
  return ADMIN_OPERATOR_STATUS_OPTIONS.some((option) => option.value === value)
}

/** route.query → 화면 상태. 검색어는 BE 한도(50자)로 잘라 400을 예방한다. */
export function parseAdminOperatorQuery(query: LocationQuery): AdminOperatorListQuery {
  const role = first(query.role)
  const status = first(query.status)
  const page = Number(first(query.page))
  const size = Number(first(query.size))
  return {
    role: role && isRole(role) ? role : null,
    status: status && isStatus(status) ? status : DEFAULT_ADMIN_OPERATOR_STATUS,
    keyword: (first(query.keyword)?.trim() ?? '').slice(0, ADMIN_OPERATOR_KEYWORD_MAX),
    page: Number.isInteger(page) && page > 0 ? page : 0,
    size: ADMIN_OPERATOR_PAGE_SIZES.includes(size) ? size : DEFAULT_ADMIN_OPERATOR_PAGE_SIZE,
  }
}

/** 화면 상태 → router.replace용 query(기본값 항목 생략). */
export function toAdminOperatorRouteQuery(state: AdminOperatorListQuery): LocationQueryRaw {
  const query: LocationQueryRaw = {}
  if (state.role) query.role = state.role
  if (state.status !== DEFAULT_ADMIN_OPERATOR_STATUS) query.status = state.status
  if (state.keyword.trim() !== '') query.keyword = state.keyword.trim()
  if (state.page > 0) query.page = String(state.page)
  if (state.size !== DEFAULT_ADMIN_OPERATOR_PAGE_SIZE) query.size = String(state.size)
  return query
}

/** 화면 상태 → BE GET /admin/admin-operators 파라미터(전체 역할·빈 검색어 제외·status/page/size는 항상 포함). */
export function toAdminOperatorApiParams(state: AdminOperatorListQuery): AdminOperatorApiParams {
  const params: AdminOperatorApiParams = { status: state.status, page: state.page, size: state.size }
  if (state.role) params.role = state.role
  const keyword = state.keyword.trim()
  if (keyword !== '') params.keyword = keyword
  return params
}

/** 필터(역할·탈퇴·검색어)가 걸려 있는지 — 빈 상태 문구 분기용. */
export function hasActiveFilters(state: AdminOperatorListQuery): boolean {
  return state.role !== null || state.status !== DEFAULT_ADMIN_OPERATOR_STATUS || state.keyword.trim() !== ''
}
