/**
 * 관리자 매출 통계 상수 단일 소스(FE-34·D-181). 요청 enum 3종(BE stats/enums 1:1)·기간 프리셋·라벨. 유니온 타입은 여기서만 export한다.
 */

export const STATS_UNITS = ['DAY', 'WEEK', 'MONTH'] as const
export type StatsUnit = (typeof STATS_UNITS)[number]
export const STATS_UNIT_LABELS: Record<StatsUnit, string> = { DAY: '일', WEEK: '주', MONTH: '월' }

export const STATS_COMPARES = ['NONE', 'PREVIOUS', 'YEAR_AGO'] as const
export type StatsCompare = (typeof STATS_COMPARES)[number]
export const STATS_COMPARE_LABELS: Record<StatsCompare, string> = { NONE: '없음', PREVIOUS: '직전 기간', YEAR_AGO: '전년 동기' }

export const STATS_AXES = ['CATEGORY', 'SELLER', 'PRODUCT'] as const
export type StatsAxis = (typeof STATS_AXES)[number]
export const STATS_AXIS_LABELS: Record<StatsAxis, string> = { CATEGORY: '카테고리', SELLER: '셀러', PRODUCT: '상품' }

/** 기간 프리셋. custom은 URL의 from·to를 그대로 쓴다. */
export const PERIOD_PRESETS = ['7d', '30d', '3m', 'ytd', 'custom'] as const
export type PeriodPreset = (typeof PERIOD_PRESETS)[number]
export const PERIOD_PRESET_LABELS: Record<PeriodPreset, string> = {
  '7d': '최근 7일',
  '30d': '최근 30일',
  '3m': '최근 3개월',
  ytd: '올해',
  custom: '직접 지정',
}

export const DEFAULT_PERIOD_PRESET: PeriodPreset = '30d'
export const DEFAULT_STATS_UNIT: StatsUnit = 'DAY'
export const DEFAULT_STATS_COMPARE: StatsCompare = 'NONE'
export const DEFAULT_STATS_AXIS: StatsAxis = 'CATEGORY'

/** 삭제(soft-delete)돼 이름을 못 찾은 행의 대체 표기(D-181 §1-A 7·name null). */
export const DELETED_NAME_LABELS: Record<StatsAxis, string> = {
  CATEGORY: '(삭제된 카테고리)',
  SELLER: '(삭제된 셀러)',
  PRODUCT: '(삭제된 상품)',
}

/** 카테고리 축 안내(D-181 §1-A 5 — order_item에 카테고리 스냅샷이 없어 product.category_id 현행 경유). */
export const CATEGORY_AXIS_NOTICE = '상품의 현재 카테고리 기준으로 집계됩니다. 상품의 카테고리를 변경하면 과거 주문의 귀속도 함께 바뀝니다.'
