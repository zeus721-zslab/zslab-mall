import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type { AdminMemberApiParams, AdminMemberListQuery } from '#layers/admin/app/types/admin-member'
import {
  ADMIN_MEMBER_KEYWORD_MAX,
  ADMIN_MEMBER_PAGE_SIZES,
  ADMIN_MEMBER_SORT_OPTIONS,
  DEFAULT_ADMIN_MEMBER_PAGE_SIZE,
  DEFAULT_ADMIN_MEMBER_SORT,
  type AdminMemberSort,
  type AdminMemberStatus,
} from '#layers/admin/app/lib/constants/admin-member'

/**
 * 관리자 회원 목록 상태 ↔ URL query ↔ BE 파라미터 순수 매핑(Track 84 FE·admin-order-query 패턴). URL이 단일 소스라 새로고침·뒤로가기에도
 * 필터가 유지된다. 기본값과 같은 항목은 URL에서 생략하고, 잘못된 값(미지의 sort·음수 page·허용 외 size)은 기본값으로 정규화한다.
 * status(ACTIVE|WITHDRAWN)는 페이지(일반회원·탈퇴회원)가 고정 주입하므로 URL·상태에 없고 API 파라미터에서만 붙는다.
 */

export const DEFAULT_ADMIN_MEMBER_QUERY: AdminMemberListQuery = {
  keyword: '',
  sort: DEFAULT_ADMIN_MEMBER_SORT,
  page: 0,
  size: DEFAULT_ADMIN_MEMBER_PAGE_SIZE,
}

function first(value: LocationQuery[string] | undefined): string | null {
  const single = Array.isArray(value) ? value[0] : value
  return typeof single === 'string' && single !== '' ? single : null
}

function isSort(value: string): value is AdminMemberSort {
  return ADMIN_MEMBER_SORT_OPTIONS.some((option) => option.value === value)
}

/** route.query → 화면 상태. 검색어는 BE 한도(50자)로 잘라 400을 예방한다. */
export function parseAdminMemberQuery(query: LocationQuery): AdminMemberListQuery {
  const sort = first(query.sort)
  const page = Number(first(query.page))
  const size = Number(first(query.size))
  return {
    keyword: (first(query.keyword)?.trim() ?? '').slice(0, ADMIN_MEMBER_KEYWORD_MAX),
    sort: sort && isSort(sort) ? sort : DEFAULT_ADMIN_MEMBER_SORT,
    page: Number.isInteger(page) && page > 0 ? page : 0,
    size: ADMIN_MEMBER_PAGE_SIZES.includes(size) ? size : DEFAULT_ADMIN_MEMBER_PAGE_SIZE,
  }
}

/** 화면 상태 → router.replace용 query(기본값 항목 생략). */
export function toAdminMemberRouteQuery(state: AdminMemberListQuery): LocationQueryRaw {
  const query: LocationQueryRaw = {}
  if (state.keyword.trim() !== '') query.keyword = state.keyword.trim()
  if (state.sort !== DEFAULT_ADMIN_MEMBER_SORT) query.sort = state.sort
  if (state.page > 0) query.page = String(state.page)
  if (state.size !== DEFAULT_ADMIN_MEMBER_PAGE_SIZE) query.size = String(state.size)
  return query
}

/** 화면 상태 + 페이지 고정 status → BE GET /admin/members 파라미터(빈 검색어 제외·status/sort/page/size는 항상 포함). */
export function toAdminMemberApiParams(state: AdminMemberListQuery, status: AdminMemberStatus): AdminMemberApiParams {
  const params: AdminMemberApiParams = { status, sort: state.sort, page: state.page, size: state.size }
  const keyword = state.keyword.trim()
  if (keyword !== '') params.keyword = keyword
  return params
}

/** 필터(검색어)가 걸려 있는지 — 빈 상태 문구("결과 없음" vs "회원 없음") 분기용. */
export function hasActiveFilters(state: AdminMemberListQuery): boolean {
  return state.keyword.trim() !== ''
}

/**
 * 목록 행 번호(최신 가입 순 역번호): totalCount − page×size − rowIndex. 첫 페이지 첫 행이 전체 건수, 마지막 행이 1이 되며
 * 정렬이 OLDEST여도 같은 식을 쓴다(번호는 "몇 번째로 오래된 회원"이 아니라 페이지 위치 표시). 음수·0은 1로 보정하지 않고 그대로
 * 둔다(totalCount와 페이지가 어긋난 늦은 응답은 재조회로 해소).
 */
export function memberRowNumber(totalCount: number, page: number, size: number, rowIndex: number): number {
  return totalCount - page * size - rowIndex
}
