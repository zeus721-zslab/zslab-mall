/**
 * 관리자 매출 통계 상수 단일 소스(FE-34·D-181). 집계 단위·비교 기간·기간 프리셋·카테고리 안내는 셀러 통계와 공용이라 app/lib/constants/stats.ts로
 * 옮기고(FE-52) 여기서 기존 이름 그대로 re-export한다. 축(CATEGORY·SELLER·PRODUCT)·삭제 표기는 관리자 전용이라 여기 남는다.
 */

export {
  STATS_UNITS,
  STATS_UNIT_LABELS,
  STATS_COMPARES,
  STATS_COMPARE_LABELS,
  PERIOD_PRESETS,
  PERIOD_PRESET_LABELS,
  DEFAULT_PERIOD_PRESET,
  DEFAULT_STATS_UNIT,
  DEFAULT_STATS_COMPARE,
  CATEGORY_AXIS_NOTICE,
} from '~/lib/constants/stats'
export type { StatsUnit, StatsCompare, PeriodPreset } from '~/lib/constants/stats'

export const STATS_AXES = ['CATEGORY', 'SELLER', 'PRODUCT'] as const
export type StatsAxis = (typeof STATS_AXES)[number]
export const STATS_AXIS_LABELS: Record<StatsAxis, string> = { CATEGORY: '카테고리', SELLER: '셀러', PRODUCT: '상품' }

export const DEFAULT_STATS_AXIS: StatsAxis = 'CATEGORY'

/** 삭제(soft-delete)돼 이름을 못 찾은 행의 대체 표기(D-181 §1-A 7·name null). */
export const DELETED_NAME_LABELS: Record<StatsAxis, string> = {
  CATEGORY: '(삭제된 카테고리)',
  SELLER: '(삭제된 셀러)',
  PRODUCT: '(삭제된 상품)',
}
