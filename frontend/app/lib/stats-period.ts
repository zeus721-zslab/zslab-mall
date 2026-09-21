import type { PeriodPreset } from '~/lib/constants/stats'

/**
 * 통계 기간 순수 함수 공용(Track 90-E-1·FE-52·관리자 admin-sales-stats-query에서 동작 무변경으로 이동·관리자는 re-export). 프리셋(7d·30d·3m·ytd)은
 * "오늘" 기준으로 매번 계산하고 custom일 때만 from·to를 쓴다. 날짜는 로컬 시간대 yyyy-MM-dd(운영자·셀러 PC = KST 전제·BE도 KST 벽시계).
 */

export interface StatsPeriodState {
  preset: PeriodPreset
  /** custom일 때만 의미. 프리셋이면 resolveStatsPeriod가 오늘 기준으로 채운다. */
  from: string | null
  to: string | null
}

export interface StatsPeriodRange {
  from: string
  to: string
}

const RECENT_7_DAYS = 7
const RECENT_30_DAYS = 30
const RECENT_MONTHS = 3
const MS_PER_DAY = 24 * 60 * 60 * 1000

/** Date → yyyy-MM-dd(로컬 시간대). */
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
export function presetPeriod(preset: Exclude<PeriodPreset, 'custom'>, today: Date): StatsPeriodRange {
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
export function resolveStatsPeriod(state: StatsPeriodState, today: Date): StatsPeriodRange | null {
  if (state.preset !== 'custom') return presetPeriod(state.preset, today)
  if (state.from === null || state.to === null) return null
  return { from: state.from, to: state.to }
}

/** 기간 역전(from > to) — BE 400 전에 화면이 막는다(yyyy-MM-dd 문자열 비교로 충분). */
export function isPeriodInverted(period: StatsPeriodRange | null): boolean {
  return period !== null && period.from > period.to
}

/** 기간 일수(양끝 포함). yyyy-MM-dd를 UTC 자정으로 읽어 DST 영향 없이 센다. */
export function periodDayCount(period: StatsPeriodRange): number {
  return Math.round((Date.parse(`${period.to}T00:00:00Z`) - Date.parse(`${period.from}T00:00:00Z`)) / MS_PER_DAY) + 1
}
