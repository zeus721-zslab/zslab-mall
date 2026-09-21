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
} from '~/lib/constants/stats'
import { isPeriodInverted, periodDayCount, resolveStatsPeriod, type StatsPeriodRange } from '~/lib/stats-period'
import { DEFAULT_SELLER_STATS_AXIS, SELLER_STATS_AXES, SELLER_STATS_MAX_PERIOD_DAYS, type SellerStatsAxis } from '#layers/seller/app/lib/constants/seller-stats'
import { normalizeDateOnly } from '#layers/seller/app/lib/seller-order-query'

/**
 * 셀러 통계 화면 상태 ↔ URL query ↔ BE 파라미터 순수 매핑(Track 90-E-1 매출·90-E-2 주문클레임·90-E-3 상품·관리자 admin-sales-stats-query·admin-stats-period-query
 * 복제·드릴다운 없음). URL이 단일
 * 소스라 새로고침·뒤로가기에도 기간·단위·비교·축이 유지된다. 프리셋은 "오늘" 기준으로 매번 계산하므로 URL에는 preset만 두고 custom일 때만
 * from·to를 싣는다. 기본값과 같은 항목은 URL에서 생략하고 잘못된 값은 기본값으로 정규화한다. 기간 계산은 공용(~/lib/stats-period).
 */

export interface SellerSalesStatsQuery {
  preset: PeriodPreset
  /** custom일 때만 의미. 프리셋이면 resolveStatsPeriod가 오늘 기준으로 채운다. */
  from: string | null
  to: string | null
  unit: StatsUnit
  compare: StatsCompare
  axis: SellerStatsAxis
}

export const DEFAULT_SELLER_SALES_STATS_QUERY: SellerSalesStatsQuery = {
  preset: DEFAULT_PERIOD_PRESET,
  from: null,
  to: null,
  unit: DEFAULT_STATS_UNIT,
  compare: DEFAULT_STATS_COMPARE,
  axis: DEFAULT_SELLER_STATS_AXIS,
}

function first(value: LocationQuery[string] | undefined): string | null {
  const single = Array.isArray(value) ? value[0] : value
  return typeof single === 'string' && single !== '' ? single : null
}

function isOneOf<T extends string>(values: readonly T[], value: string | null): value is T {
  return value !== null && (values as readonly string[]).includes(value)
}

/** route.query → 화면 상태. custom인데 from·to가 하나라도 없으면 기본 프리셋으로 되돌린다. */
export function parseSellerSalesStatsQuery(query: LocationQuery): SellerSalesStatsQuery {
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
    axis: isOneOf(SELLER_STATS_AXES, axisRaw) ? axisRaw : DEFAULT_SELLER_STATS_AXIS,
  }
}

/** 화면 상태 → router.replace용 query(기본값 항목 생략·custom일 때만 from·to). */
export function toSellerSalesStatsRouteQuery(state: SellerSalesStatsQuery): LocationQueryRaw {
  const query: LocationQueryRaw = {}
  if (state.preset !== DEFAULT_PERIOD_PRESET) query.preset = state.preset
  if (state.preset === 'custom') {
    if (state.from) query.from = state.from
    if (state.to) query.to = state.to
  }
  if (state.unit !== DEFAULT_STATS_UNIT) query.unit = state.unit
  if (state.compare !== DEFAULT_STATS_COMPARE) query.compare = state.compare
  if (state.axis !== DEFAULT_SELLER_STATS_AXIS) query.axis = state.axis
  return query
}

/** 화면 상태 → 실제 조회 기간. custom인데 from·to가 비면 null(요청 보류·입력 안내). */
export function resolveSellerStatsPeriod(state: SellerSalesStatsQuery, today: Date): StatsPeriodRange | null {
  return resolveStatsPeriod(state, today)
}

/** 기간 검증 메시지. 역전·365일 초과는 BE 400 전에 화면이 막는다(D-200). 문제 없으면 null. */
export function sellerStatsPeriodError(period: StatsPeriodRange | null): string | null {
  if (period === null) return null
  if (isPeriodInverted(period)) return '시작일이 종료일보다 늦습니다. 기간을 다시 확인해 주세요.'
  if (periodDayCount(period) > SELLER_STATS_MAX_PERIOD_DAYS) return `기간은 최대 ${SELLER_STATS_MAX_PERIOD_DAYS}일까지 조회할 수 있습니다.`
  return null
}

export interface SellerSalesStatsApiParams {
  from: string
  to: string
  unit: StatsUnit
  compare: StatsCompare
}

export interface SellerSalesBreakdownApiParams {
  from: string
  to: string
  compare: StatsCompare
  axis: SellerStatsAxis
}

/** 요약·추이 GET 파라미터. */
export function toSellerSalesApiParams(state: SellerSalesStatsQuery, period: StatsPeriodRange): SellerSalesStatsApiParams {
  return { from: period.from, to: period.to, unit: state.unit, compare: state.compare }
}

/** 분해·CSV GET 파라미터. */
export function toSellerBreakdownApiParams(state: SellerSalesStatsQuery, period: StatsPeriodRange): SellerSalesBreakdownApiParams {
  return { from: period.from, to: period.to, compare: state.compare, axis: state.axis }
}

// ---------- 주문·클레임(90-E-2·축 없음·preset|from|to|unit|compare) ----------

export type SellerStatsPeriodQuery = Omit<SellerSalesStatsQuery, 'axis'>

export const DEFAULT_SELLER_STATS_PERIOD_QUERY: SellerStatsPeriodQuery = {
  preset: DEFAULT_PERIOD_PRESET,
  from: null,
  to: null,
  unit: DEFAULT_STATS_UNIT,
  compare: DEFAULT_STATS_COMPARE,
}

/** route.query → 주문클레임 화면 상태(매출 파서에서 축만 뺀다·규칙 동일). */
export function parseSellerStatsPeriodQuery(query: LocationQuery): SellerStatsPeriodQuery {
  const { axis: _axis, ...rest } = parseSellerSalesStatsQuery(query)
  return rest
}

/** 화면 상태 → router.replace용 query(기본값 항목 생략·custom일 때만 from·to). */
export function toSellerStatsPeriodRouteQuery(state: SellerStatsPeriodQuery): LocationQueryRaw {
  return toSellerSalesStatsRouteQuery({ ...state, axis: DEFAULT_SELLER_STATS_AXIS })
}

/** 주문클레임 GET 파라미터(from·to·unit·compare·매출 요약과 동일 형태). */
export function toSellerOrderStatsApiParams(state: SellerStatsPeriodQuery, period: StatsPeriodRange): SellerSalesStatsApiParams {
  return { from: period.from, to: period.to, unit: state.unit, compare: state.compare }
}

// ---------- 상품(90-E-3·기간만·preset|from|to) ----------

export type SellerStatsRangeQuery = Pick<SellerSalesStatsQuery, 'preset' | 'from' | 'to'>

export const DEFAULT_SELLER_STATS_RANGE_QUERY: SellerStatsRangeQuery = { preset: DEFAULT_PERIOD_PRESET, from: null, to: null }

/** route.query → 상품 통계 화면 상태(기간만·unit/compare/axis 무시). */
export function parseSellerStatsRangeQuery(query: LocationQuery): SellerStatsRangeQuery {
  const { preset, from, to } = parseSellerSalesStatsQuery(query)
  return { preset, from, to }
}

/** 화면 상태 → router.replace용 query(기본 프리셋 생략·custom일 때만 from·to). */
export function toSellerStatsRangeRouteQuery(state: SellerStatsRangeQuery): LocationQueryRaw {
  return toSellerSalesStatsRouteQuery({ ...DEFAULT_SELLER_SALES_STATS_QUERY, ...state })
}

export interface SellerProductStatsApiParams {
  from: string
  to: string
}

export function toSellerProductStatsApiParams(period: StatsPeriodRange): SellerProductStatsApiParams {
  return { from: period.from, to: period.to }
}
