import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type { AdminSettlementApiParams, AdminSettlementListQuery } from '#layers/admin/app/types/admin-settlement'
import {
  ADMIN_SETTLEMENT_KEYWORD_MAX,
  ADMIN_SETTLEMENT_PAGE_SIZES,
  ADMIN_SETTLEMENT_STATUS_OPTIONS,
  ADMIN_SETTLEMENT_YEAR_SPAN,
  DEFAULT_ADMIN_SETTLEMENT_PAGE_SIZE,
  type AdminSettlementStatus,
} from '#layers/admin/app/lib/constants/admin-settlement'

/**
 * 관리자 정산 목록 상태 ↔ URL query ↔ BE 파라미터 순수 매핑(Track 85 FE·admin-member-query 패턴). URL이 단일 소스라 새로고침·뒤로가기에도
 * 월·필터가 유지된다. year·month는 BE 필수 파라미터이고 기본값(지난달)이 시간에 따라 바뀌므로 URL에 항상 싣는다(공유 URL이 다음 달에 다른
 * 월을 가리키지 않게). 그 외 항목은 기본값과 같으면 URL에서 생략하고, 잘못된 값(미지의 status·음수 page·허용 외 size·범위 밖 월)은 기본값으로
 * 정규화한다.
 */

export interface YearMonth {
  year: number
  month: number
}

/** 지난달(정산은 마감된 월만 생성 가능하므로 기본 조회 월). 1월이면 전년 12월. */
export function defaultSettlementMonth(now: Date = new Date()): YearMonth {
  const year = now.getFullYear()
  const month = now.getMonth() + 1
  return month === 1 ? { year: year - 1, month: 12 } : { year, month: month - 1 }
}

export function defaultAdminSettlementQuery(now: Date = new Date()): AdminSettlementListQuery {
  const { year, month } = defaultSettlementMonth(now)
  return { year, month, status: null, keyword: '', page: 0, size: DEFAULT_ADMIN_SETTLEMENT_PAGE_SIZE }
}

function first(value: LocationQuery[string] | undefined): string | null {
  const single = Array.isArray(value) ? value[0] : value
  return typeof single === 'string' && single !== '' ? single : null
}

function isStatus(value: string): value is AdminSettlementStatus {
  return ADMIN_SETTLEMENT_STATUS_OPTIONS.some((option) => option.value === value)
}

/** 월 선택지 연도 목록(올해부터 과거 ADMIN_SETTLEMENT_YEAR_SPAN년·내림차순). */
export function settlementYearOptions(now: Date = new Date()): number[] {
  const thisYear = now.getFullYear()
  return Array.from({ length: ADMIN_SETTLEMENT_YEAR_SPAN + 1 }, (_, index) => thisYear - index)
}

function isYearInRange(year: number, now: Date): boolean {
  return settlementYearOptions(now).includes(year)
}

/** route.query → 화면 상태. 검색어는 BE 한도(50자)로 잘라 400을 예방한다. */
export function parseAdminSettlementQuery(query: LocationQuery, now: Date = new Date()): AdminSettlementListQuery {
  const defaults = defaultAdminSettlementQuery(now)
  const year = Number(first(query.year))
  const month = Number(first(query.month))
  const status = first(query.status)
  const page = Number(first(query.page))
  const size = Number(first(query.size))
  const validPeriod = Number.isInteger(year) && Number.isInteger(month) && month >= 1 && month <= 12 && isYearInRange(year, now)
  return {
    year: validPeriod ? year : defaults.year,
    month: validPeriod ? month : defaults.month,
    status: status && isStatus(status) ? status : null,
    keyword: (first(query.keyword)?.trim() ?? '').slice(0, ADMIN_SETTLEMENT_KEYWORD_MAX),
    page: Number.isInteger(page) && page > 0 ? page : 0,
    size: ADMIN_SETTLEMENT_PAGE_SIZES.includes(size) ? size : DEFAULT_ADMIN_SETTLEMENT_PAGE_SIZE,
  }
}

/** 화면 상태 → router.replace용 query(year·month 항상·나머지는 기본값 항목 생략). */
export function toAdminSettlementRouteQuery(state: AdminSettlementListQuery): LocationQueryRaw {
  const query: LocationQueryRaw = { year: String(state.year), month: String(state.month) }
  if (state.status) query.status = state.status
  if (state.keyword.trim() !== '') query.keyword = state.keyword.trim()
  if (state.page > 0) query.page = String(state.page)
  if (state.size !== DEFAULT_ADMIN_SETTLEMENT_PAGE_SIZE) query.size = String(state.size)
  return query
}

/** 화면 상태 → BE GET /admin/settlements 파라미터(year/month/page/size 항상·status/keyword는 값이 있을 때만). */
export function toAdminSettlementApiParams(state: AdminSettlementListQuery): AdminSettlementApiParams {
  const params: AdminSettlementApiParams = { year: state.year, month: state.month, page: state.page, size: state.size }
  if (state.status) params.status = state.status
  const keyword = state.keyword.trim()
  if (keyword !== '') params.keyword = keyword
  return params
}

/** 필터(상태·검색어)가 걸려 있는지 — 빈 상태 문구("조건에 맞는 정산 없음" vs "정산 없음") 분기용. 월은 필터가 아니다. */
export function hasActiveFilters(state: AdminSettlementListQuery): boolean {
  return state.status !== null || state.keyword.trim() !== ''
}
