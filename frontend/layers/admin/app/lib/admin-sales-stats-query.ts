import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import {
  DEFAULT_PERIOD_PRESET,
  DEFAULT_STATS_AXIS,
  DEFAULT_STATS_COMPARE,
  DEFAULT_STATS_UNIT,
  PERIOD_PRESETS,
  STATS_AXES,
  STATS_COMPARES,
  STATS_UNITS,
  type PeriodPreset,
  type StatsAxis,
  type StatsCompare,
  type StatsUnit,
} from '#layers/admin/app/lib/constants/admin-sales-stats'
import { normalizeDateOnly } from '#layers/admin/app/lib/admin-order-query'

/**
 * 매출 통계 화면 상태 ↔ URL query ↔ BE 파라미터 순수 매핑(FE-34·admin-order-query 패턴). URL이 단일 소스라 새로고침·뒤로가기에도
 * 기간·단위·비교·축·드릴다운이 유지된다. 프리셋(7d·30d·3m·ytd)은 "오늘" 기준으로 매번 계산하므로 URL에는 preset만 두고, custom일 때만
 * from·to를 URL에 싣는다. 기본값과 같은 항목은 URL에서 생략하고 잘못된 값은 기본값으로 정규화한다.
 */

export interface AdminSalesStatsQuery {
  preset: PeriodPreset
  /** custom일 때만 의미. 프리셋이면 resolvePeriod가 오늘 기준으로 채운다. */
  from: string | null
  to: string | null
  unit: StatsUnit
  compare: StatsCompare
  axis: StatsAxis
  /** 드릴다운 parentKey(CATEGORY = categoryId·SELLER = public_id). 없으면 축 최상위. */
  parent: string | null
}

export const DEFAULT_ADMIN_SALES_STATS_QUERY: AdminSalesStatsQuery = {
  preset: DEFAULT_PERIOD_PRESET,
  from: null,
  to: null,
  unit: DEFAULT_STATS_UNIT,
  compare: DEFAULT_STATS_COMPARE,
  axis: DEFAULT_STATS_AXIS,
  parent: null,
}

const RECENT_7_DAYS = 7
const RECENT_30_DAYS = 30
const RECENT_MONTHS = 3

function first(value: LocationQuery[string] | undefined): string | null {
  const single = Array.isArray(value) ? value[0] : value
  return typeof single === 'string' && single !== '' ? single : null
}

function isOneOf<T extends string>(values: readonly T[], value: string | null): value is T {
  return value !== null && (values as readonly string[]).includes(value)
}

/** Date → yyyy-MM-dd(로컬 시간대·관리자 PC = KST 전제·BE도 KST 벽시계). */
export function toDateOnly(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function shiftDays(date: Date, days: number): Date {
  const next = new Date(date)
  next.setDate(next.getDate() + days)
  return next
}

/** 프리셋 → 기간(오늘 포함·종료일 = 오늘). 7일/30일은 오늘 포함 N일, 3개월은 3개월 전 같은 날 +1일, 올해는 1월 1일부터. */
export function presetPeriod(preset: Exclude<PeriodPreset, 'custom'>, today: Date): { from: string; to: string } {
  const to = toDateOnly(today)
  switch (preset) {
    case '7d':
      return { from: toDateOnly(shiftDays(today, -(RECENT_7_DAYS - 1))), to }
    case '30d':
      return { from: toDateOnly(shiftDays(today, -(RECENT_30_DAYS - 1))), to }
    case '3m': {
      const start = new Date(today)
      start.setMonth(start.getMonth() - RECENT_MONTHS)
      return { from: toDateOnly(shiftDays(start, 1)), to }
    }
    case 'ytd':
      return { from: `${today.getFullYear()}-01-01`, to }
  }
}

/** 화면 상태 → 실제 조회 기간. custom인데 from·to가 비면 null(요청 보류·입력 안내). */
export function resolvePeriod(state: AdminSalesStatsQuery, today: Date): { from: string; to: string } | null {
  if (state.preset !== 'custom') return presetPeriod(state.preset, today)
  if (state.from === null || state.to === null) return null
  return { from: state.from, to: state.to }
}

/** 기간 역전(from > to) — BE 400 전에 화면이 막는다(yyyy-MM-dd 문자열 비교로 충분). */
export function isPeriodInverted(period: { from: string; to: string } | null): boolean {
  return period !== null && period.from > period.to
}

/** route.query → 화면 상태. custom인데 from·to가 하나라도 없으면 기본 프리셋으로 되돌린다. */
export function parseAdminSalesStatsQuery(query: LocationQuery): AdminSalesStatsQuery {
  const presetRaw = first(query.preset)
  const from = normalizeDateOnly(first(query.from))
  const to = normalizeDateOnly(first(query.to))
  const isCustom = presetRaw === 'custom' && from !== null && to !== null
  const preset: PeriodPreset = isCustom ? 'custom' : isOneOf(PERIOD_PRESETS, presetRaw) && presetRaw !== 'custom' ? presetRaw : DEFAULT_PERIOD_PRESET
  const unitRaw = first(query.unit)
  const compareRaw = first(query.compare)
  const axisRaw = first(query.axis)
  return {
    preset,
    from: isCustom ? from : null,
    to: isCustom ? to : null,
    unit: isOneOf(STATS_UNITS, unitRaw) ? unitRaw : DEFAULT_STATS_UNIT,
    compare: isOneOf(STATS_COMPARES, compareRaw) ? compareRaw : DEFAULT_STATS_COMPARE,
    axis: isOneOf(STATS_AXES, axisRaw) ? axisRaw : DEFAULT_STATS_AXIS,
    parent: first(query.parent),
  }
}

/** 화면 상태 → router.replace용 query(기본값 항목 생략·custom일 때만 from·to). */
export function toAdminSalesStatsRouteQuery(state: AdminSalesStatsQuery): LocationQueryRaw {
  const query: LocationQueryRaw = {}
  if (state.preset !== DEFAULT_PERIOD_PRESET) query.preset = state.preset
  if (state.preset === 'custom') {
    if (state.from) query.from = state.from
    if (state.to) query.to = state.to
  }
  if (state.unit !== DEFAULT_STATS_UNIT) query.unit = state.unit
  if (state.compare !== DEFAULT_STATS_COMPARE) query.compare = state.compare
  if (state.axis !== DEFAULT_STATS_AXIS) query.axis = state.axis
  if (state.parent) query.parent = state.parent
  return query
}

export interface AdminSalesStatsApiParams {
  from: string
  to: string
  unit: StatsUnit
  compare: StatsCompare
}

export interface AdminSalesBreakdownApiParams {
  from: string
  to: string
  compare: StatsCompare
  axis: StatsAxis
  parentKey?: string
}

/** 요약·추이 GET 파라미터. */
export function toSalesApiParams(state: AdminSalesStatsQuery, period: { from: string; to: string }): AdminSalesStatsApiParams {
  return { from: period.from, to: period.to, unit: state.unit, compare: state.compare }
}

/** 분해·CSV GET 파라미터(parentKey는 있을 때만). PRODUCT 축은 BE가 parentKey를 400으로 거부하므로 붙이지 않는다. */
export function toBreakdownApiParams(state: AdminSalesStatsQuery, period: { from: string; to: string }): AdminSalesBreakdownApiParams {
  const params: AdminSalesBreakdownApiParams = { from: period.from, to: period.to, compare: state.compare, axis: state.axis }
  if (state.parent && state.axis !== 'PRODUCT') params.parentKey = state.parent
  return params
}
