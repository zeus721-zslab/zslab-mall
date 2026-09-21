/**
 * 통계 요청 상수 단일 소스(Track 90-E-1·FE-52). 관리자 매출·주문·회원 통계(FE-34·35)와 셀러 매출 통계가 같은 집계 단위·비교 기간·기간 프리셋을
 * 쓰므로 레이어 밖 공용에 두고, 관리자 레이어(lib/constants/admin-sales-stats.ts)는 기존 이름 그대로 re-export한다(셀러 레이어는 admin import
 * 금지·no-admin-import.spec). 축(axis)은 관리자(CATEGORY·SELLER·PRODUCT)와 셀러(PRODUCT·OPTION·CATEGORY)가 달라 각 레이어에 둔다.
 * BE stats/enums StatsUnit·StatsCompare와 1:1이며 유니온 타입은 여기서만 export한다.
 */

export const STATS_UNITS = ['DAY', 'WEEK', 'MONTH'] as const
export type StatsUnit = (typeof STATS_UNITS)[number]
export const STATS_UNIT_LABELS: Record<StatsUnit, string> = { DAY: '일', WEEK: '주', MONTH: '월' }

export const STATS_COMPARES = ['NONE', 'PREVIOUS', 'YEAR_AGO'] as const
export type StatsCompare = (typeof STATS_COMPARES)[number]
export const STATS_COMPARE_LABELS: Record<StatsCompare, string> = { NONE: '없음', PREVIOUS: '직전 기간', YEAR_AGO: '전년 동기' }

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

/** 카테고리 축 안내(D-181 §1-A 5 — order_item에 카테고리 스냅샷이 없어 product.category_id 현행 경유·셀러 D-200 동일). */
export const CATEGORY_AXIS_NOTICE = '상품의 현재 카테고리 기준으로 집계됩니다. 상품의 카테고리를 변경하면 과거 주문의 귀속도 함께 바뀝니다.'
