import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type { SellerClaimApiParams, SellerClaimListQuery } from '#layers/seller/app/types/seller-claim'
import { CLAIM_STATUS_LABELS, isClaimType, type ClaimStatus } from '~/lib/constants/claim'
import {
  DEFAULT_SELLER_CLAIM_PAGE_SIZE,
  SELLER_CLAIM_KEYWORD_MAX,
  SELLER_CLAIM_PAGE_SIZES,
} from '#layers/seller/app/lib/constants/seller-claim'
import { normalizeDateOnly, toPeriodEnd, toPeriodStart } from '#layers/seller/app/lib/seller-order-query'

/**
 * 셀러 클레임 목록 상태 ↔ URL query ↔ BE 파라미터 순수 매핑(Track 90-D-1·seller-order-query 복제). URL이 단일 소스라 새로고침·뒤로가기에도
 * 필터가 유지된다. 기본값과 같은 항목은 URL에서 생략하고, 잘못된 값(미지의 type/status·날짜 형식·음수 page)은 기본값으로 정규화한다.
 * 기간은 yyyy-MM-dd로 다루고 BE(LocalDateTime ISO·requested_at 기준·포함 경계)에는 from=T00:00:00·to=T23:59:59를 부착해 보낸다(주문 목록과 같은 헬퍼).
 */

export const DEFAULT_SELLER_CLAIM_QUERY: SellerClaimListQuery = {
  keyword: '',
  type: null,
  status: null,
  from: null,
  to: null,
  page: 0,
  size: DEFAULT_SELLER_CLAIM_PAGE_SIZE,
}

function first(value: LocationQuery[string] | undefined): string | null {
  const single = Array.isArray(value) ? value[0] : value
  return typeof single === 'string' && single !== '' ? single : null
}

function isClaimStatus(value: string): value is ClaimStatus {
  return value in CLAIM_STATUS_LABELS
}

/** route.query → 화면 상태. 검색어는 BE 한도(50자)로 잘라 400을 예방한다. */
export function parseSellerClaimQuery(query: LocationQuery): SellerClaimListQuery {
  const type = first(query.type)
  const status = first(query.status)
  const page = Number(first(query.page))
  const size = Number(first(query.size))
  return {
    keyword: (first(query.keyword)?.trim() ?? '').slice(0, SELLER_CLAIM_KEYWORD_MAX),
    type: type && isClaimType(type) ? type : null,
    status: status && isClaimStatus(status) ? status : null,
    from: normalizeDateOnly(first(query.from)),
    to: normalizeDateOnly(first(query.to)),
    page: Number.isInteger(page) && page > 0 ? page : 0,
    size: SELLER_CLAIM_PAGE_SIZES.includes(size) ? size : DEFAULT_SELLER_CLAIM_PAGE_SIZE,
  }
}

/** 화면 상태 → router.replace용 query(기본값 항목 생략). */
export function toSellerClaimRouteQuery(state: SellerClaimListQuery): LocationQueryRaw {
  const query: LocationQueryRaw = {}
  if (state.keyword.trim() !== '') query.keyword = state.keyword.trim()
  if (state.type) query.type = state.type
  if (state.status) query.status = state.status
  if (state.from) query.from = state.from
  if (state.to) query.to = state.to
  if (state.page > 0) query.page = String(state.page)
  if (state.size !== DEFAULT_SELLER_CLAIM_PAGE_SIZE) query.size = String(state.size)
  return query
}

/** 화면 상태 → BE GET /seller/claims 파라미터(null·빈 값 제외·page/size는 항상 포함·기간은 시각 부착). */
export function toSellerClaimApiParams(state: SellerClaimListQuery): SellerClaimApiParams {
  const params: SellerClaimApiParams = { page: state.page, size: state.size }
  const keyword = state.keyword.trim()
  if (keyword !== '') params.keyword = keyword
  if (state.type) params.type = state.type
  if (state.status) params.status = state.status
  if (state.from) params.from = toPeriodStart(state.from)
  if (state.to) params.to = toPeriodEnd(state.to)
  return params
}

/** 필터(검색·유형·상태·기간)가 하나라도 걸려 있는지 — 빈 상태 문구("결과 없음" vs "클레임 없음") 분기용. */
export function hasActiveClaimFilters(state: SellerClaimListQuery): boolean {
  return state.keyword.trim() !== '' || state.type !== null || state.status !== null || state.from !== null || state.to !== null
}
