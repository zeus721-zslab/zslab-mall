import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import {
  DEFAULT_PERIOD_PRESET,
  DEFAULT_STATS_COMPARE,
  DEFAULT_STATS_UNIT,
  PERIOD_PRESETS,
  STATS_COMPARES,
  STATS_UNITS,
  type PeriodPreset,
  type StatsCompare,
  type StatsUnit,
} from '#layers/admin/app/lib/constants/admin-sales-stats'
import { normalizeDateOnly } from '#layers/admin/app/lib/admin-order-query'
import { resolvePeriod, type AdminSalesStatsApiParams } from '#layers/admin/app/lib/admin-sales-stats-query'

/**
 * 주문·회원 통계(FE-35) 화면 상태 ↔ URL query ↔ BE 파라미터 순수 매핑. 매출 통계(admin-sales-stats-query)와 같은 규칙(URL 단일 소스·
 * 프리셋은 오늘 기준·custom일 때만 from·to·기본값 생략·잘못된 값 정규화)이며 축·드릴다운(axis·parent)이 없어 preset|from|to|unit|compare만 다룬다.
 * 기간 계산(resolvePeriod·isPeriodInverted)은 매출 통계 헬퍼를 그대로 쓴다.
 */

export interface AdminStatsPeriodQuery {
  preset: PeriodPreset
  from: string | null
  to: string | null
  unit: StatsUnit
  compare: StatsCompare
}

export const DEFAULT_ADMIN_STATS_PERIOD_QUERY: AdminStatsPeriodQuery = {
  preset: DEFAULT_PERIOD_PRESET,
  from: null,
  to: null,
  unit: DEFAULT_STATS_UNIT,
  compare: DEFAULT_STATS_COMPARE,
}

function first(value: LocationQuery[string] | undefined): string | null {
  const single = Array.isArray(value) ? value[0] : value
  return typeof single === 'string' && single !== '' ? single : null
}

function isOneOf<T extends string>(values: readonly T[], value: string | null): value is T {
  return value !== null && (values as readonly string[]).includes(value)
}

/** route.query → 화면 상태. custom인데 from·to가 하나라도 없으면 기본 프리셋으로 되돌린다. */
export function parseAdminStatsPeriodQuery(query: LocationQuery): AdminStatsPeriodQuery {
  const presetRaw = first(query.preset)
  const from = normalizeDateOnly(first(query.from))
  const to = normalizeDateOnly(first(query.to))
  const isCustom = presetRaw === 'custom' && from !== null && to !== null
  const preset: PeriodPreset = isCustom ? 'custom' : isOneOf(PERIOD_PRESETS, presetRaw) && presetRaw !== 'custom' ? presetRaw : DEFAULT_PERIOD_PRESET
  const unitRaw = first(query.unit)
  const compareRaw = first(query.compare)
  return {
    preset,
    from: isCustom ? from : null,
    to: isCustom ? to : null,
    unit: isOneOf(STATS_UNITS, unitRaw) ? unitRaw : DEFAULT_STATS_UNIT,
    compare: isOneOf(STATS_COMPARES, compareRaw) ? compareRaw : DEFAULT_STATS_COMPARE,
  }
}

/** 화면 상태 → router.replace용 query(기본값 항목 생략·custom일 때만 from·to). */
export function toAdminStatsPeriodRouteQuery(state: AdminStatsPeriodQuery): LocationQueryRaw {
  const query: LocationQueryRaw = {}
  if (state.preset !== DEFAULT_PERIOD_PRESET) query.preset = state.preset
  if (state.preset === 'custom') {
    if (state.from) query.from = state.from
    if (state.to) query.to = state.to
  }
  if (state.unit !== DEFAULT_STATS_UNIT) query.unit = state.unit
  if (state.compare !== DEFAULT_STATS_COMPARE) query.compare = state.compare
  return query
}

/** 화면 상태 → 실제 조회 기간(매출 통계와 동일 규칙). custom인데 from·to가 비면 null. */
export function resolveStatsPeriod(state: AdminStatsPeriodQuery, today: Date): { from: string; to: string } | null {
  return resolvePeriod({ ...state, axis: 'CATEGORY', parent: null }, today)
}

/** GET 파라미터(주문·회원 통계 공통: from·to·unit·compare). */
export function toStatsApiParams(state: AdminStatsPeriodQuery, period: { from: string; to: string }): AdminSalesStatsApiParams {
  return { from: period.from, to: period.to, unit: state.unit, compare: state.compare }
}
