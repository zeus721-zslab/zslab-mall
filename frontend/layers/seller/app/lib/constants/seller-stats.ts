/**
 * 셀러 통계 상수 단일 소스(Track 90-E-1·FE-52·D-200). 집계 단위·비교 기간·기간 프리셋은 공용(~/lib/constants/stats)을 쓰고, 셀러 매출 분해 축
 * (BE stats/enums/SellerStatsAxis 1:1)·기간 상한·삭제 표기만 여기 둔다. 축 유니온 타입은 여기서만 export한다.
 */

export const SELLER_STATS_AXES = ['PRODUCT', 'OPTION', 'CATEGORY'] as const
export type SellerStatsAxis = (typeof SELLER_STATS_AXES)[number]
export const SELLER_STATS_AXIS_LABELS: Record<SellerStatsAxis, string> = { PRODUCT: '상품', OPTION: '옵션', CATEGORY: '카테고리' }

export const DEFAULT_SELLER_STATS_AXIS: SellerStatsAxis = 'PRODUCT'

/** BE SellerSalesStatsQueryService.MAX_PERIOD_DAYS(365)와 1:1 — 초과는 BE 400이며 화면이 먼저 막는다. */
export const SELLER_STATS_MAX_PERIOD_DAYS = 365

/** 삭제(soft-delete)돼 key를 못 찾은 행의 대체 표기(D-181 §1-A 7 관례·상품·옵션 이름은 스냅샷이라 name은 항상 있다). */
export const SELLER_DELETED_NAME_LABELS: Record<SellerStatsAxis, string> = {
  PRODUCT: '(삭제된 상품)',
  OPTION: '(삭제된 옵션)',
  CATEGORY: '(삭제된 카테고리)',
}
