import { describe, it, expect } from 'vitest'
import { isPeriodInverted, periodDayCount, presetPeriod, resolveStatsPeriod, toDateOnly } from '~/lib/stats-period'
import {
  axisLabel,
  changeRate,
  changeTone,
  compareNetSeries,
  csvFileNameFrom,
  formatChangeRate,
  formatItemsPerOrder,
  isTrendEmpty,
  normalizeSalesStats,
  salesChangeTone,
  showsCompare,
  sortBreakdownRows,
  type SortableBreakdownRow,
} from '~/lib/stats-view'
import { CATEGORY_AXIS_NOTICE, DEFAULT_PERIOD_PRESET, PERIOD_PRESETS, STATS_COMPARES, STATS_UNITS } from '~/lib/constants/stats'
import type { SalesTrendBucket } from '~/types/stats'

// FE-52 공용 통계 순수 함수(관리자 admin-sales-stats-query·admin-sales-stats-view·admin-dashboard-view에서 이동). 관리자 helper spec은 re-export 경유로
// 같은 함수를 다시 검증하므로 여기서는 이동 후 동작 고정 + 셀러가 새로 쓰는 periodDayCount만 본다.

const TODAY = new Date(2026, 8, 21) // 2026-09-21(월)

function bucket(key: string, revenue: number, refund = 0, orderCount = revenue > 0 ? 1 : 0): SalesTrendBucket {
  return { bucketKey: key, bucketLabel: key, revenue, refund, netRevenue: revenue - refund, orderCount }
}

describe('constants/stats', () => {
  it('BE enum 1:1 · 기본 프리셋 30d · 카테고리 안내 문구 보유', () => {
    expect([...STATS_UNITS]).toEqual(['DAY', 'WEEK', 'MONTH'])
    expect([...STATS_COMPARES]).toEqual(['NONE', 'PREVIOUS', 'YEAR_AGO'])
    expect([...PERIOD_PRESETS]).toEqual(['7d', '30d', '3m', 'ytd', 'custom'])
    expect(DEFAULT_PERIOD_PRESET).toBe('30d')
    expect(CATEGORY_AXIS_NOTICE).toContain('현재 카테고리')
  })
})

describe('stats-period', () => {
  it('프리셋 → 기간(오늘 포함) · custom은 from·to 그대로 · 한쪽 비면 null', () => {
    expect(presetPeriod('7d', TODAY)).toEqual({ from: '2026-09-15', to: '2026-09-21' })
    expect(presetPeriod('30d', TODAY)).toEqual({ from: '2026-08-23', to: '2026-09-21' })
    expect(presetPeriod('3m', TODAY)).toEqual({ from: '2026-06-22', to: '2026-09-21' })
    expect(presetPeriod('ytd', TODAY)).toEqual({ from: '2026-01-01', to: '2026-09-21' })
    expect(resolveStatsPeriod({ preset: '7d', from: null, to: null }, TODAY)).toEqual({ from: '2026-09-15', to: '2026-09-21' })
    expect(resolveStatsPeriod({ preset: 'custom', from: '2026-03-01', to: '2026-03-31' }, TODAY)).toEqual({ from: '2026-03-01', to: '2026-03-31' })
    expect(resolveStatsPeriod({ preset: 'custom', from: '2026-03-01', to: null }, TODAY)).toBeNull()
    expect(toDateOnly(new Date(2026, 0, 5))).toBe('2026-01-05')
  })

  it('역전 판정 · 일수(양끝 포함): 1일=1 · 3월=31 · 365일 경계 · 윤년 무관 UTC 계산', () => {
    expect(isPeriodInverted({ from: '2026-03-11', to: '2026-03-10' })).toBe(true)
    expect(isPeriodInverted({ from: '2026-03-10', to: '2026-03-10' })).toBe(false)
    expect(isPeriodInverted(null)).toBe(false)
    expect(periodDayCount({ from: '2026-03-10', to: '2026-03-10' })).toBe(1)
    expect(periodDayCount({ from: '2026-03-01', to: '2026-03-31' })).toBe(31)
    expect(periodDayCount({ from: '2025-04-01', to: '2026-03-31' })).toBe(365)
    expect(periodDayCount({ from: '2025-03-31', to: '2026-03-31' })).toBe(366)
  })
})

describe('stats-view 증감·정규화', () => {
  it('changeRate·formatChangeRate·changeTone·salesChangeTone(환불 반전)', () => {
    expect(changeRate(120, 100)).toBeCloseTo(20)
    expect(changeRate(100, 0)).toBeNull()
    expect(formatChangeRate(12.34)).toBe('+12.3%')
    expect(formatChangeRate(-4)).toBe('-4.0%')
    expect(formatChangeRate(null)).toBe('—')
    expect(changeTone(5)).toBe('up')
    expect(changeTone(-5)).toBe('down')
    expect(changeTone(0)).toBe('flat')
    expect(salesChangeTone(5, true)).toBe('down')
    expect(salesChangeTone(-5, true)).toBe('up')
    expect(salesChangeTone(null, true)).toBe('flat')
    expect(formatItemsPerOrder(1.5)).toBe('1.50개')
  })

  it('normalizeSalesStats: 생략된 비교 필드 → null · showsCompare', () => {
    const summary = { revenue: 1, refund: 0, netRevenue: 1, orderCount: 1, itemQuantity: 1, avgOrderValue: 1, avgItemsPerOrder: 1 }
    expect(normalizeSalesStats({ summary, trend: [] })).toEqual({ summary, compareSummary: null, trend: [], compareTrend: null })
    expect(showsCompare('NONE')).toBe(false)
    expect(showsCompare('PREVIOUS')).toBe(true)
  })
})

describe('stats-view 추이·정렬·CSV', () => {
  it('compareNetSeries: 후행 0만 null · 중간 0 유지 · 길이 맞춤 · 비교 없으면 null', () => {
    const compare = [bucket('a', 100), bucket('b', 0), bucket('c', 50), bucket('d', 0), bucket('e', 0)]
    expect(compareNetSeries(compare, 5)).toEqual([100, 0, 50, null, null])
    expect(compareNetSeries(compare, 6)).toEqual([100, 0, 50, null, null, null])
    expect(compareNetSeries(compare, 3)).toEqual([100, 0, 50])
    expect(compareNetSeries(null, 3)).toBeNull()
    expect(isTrendEmpty([bucket('a', 0), bucket('b', 0)])).toBe(true)
    expect(isTrendEmpty([bucket('a', 0), bucket('b', 1)])).toBe(false)
    expect(axisLabel('2026-09-21')).toBe('09-21')
    expect(axisLabel('2026-09')).toBe('2026-09')
  })

  it('sortBreakdownRows: 제네릭 행 · null은 항상 뒤 · 동률은 매출 DESC · 이름은 ko-KR', () => {
    const rows: (SortableBreakdownRow & { id: string })[] = [
      { id: 'a', name: '나', revenue: 100, share: 50, orderCount: 1, quantity: 1, compareRevenue: null, rate: null },
      { id: 'b', name: '가', revenue: 300, share: 30, orderCount: 3, quantity: 3, compareRevenue: 200, rate: 50 },
      { id: 'c', name: '다', revenue: 200, share: 20, orderCount: 2, quantity: 2, compareRevenue: 400, rate: -50 },
    ]
    expect(sortBreakdownRows(rows, 'rate', 'desc').map((row) => row.id)).toEqual(['b', 'c', 'a'])
    expect(sortBreakdownRows(rows, 'rate', 'asc').map((row) => row.id)).toEqual(['c', 'b', 'a'])
    expect(sortBreakdownRows(rows, 'name', 'asc').map((row) => row.id)).toEqual(['b', 'a', 'c'])
    expect(sortBreakdownRows(rows, 'revenue', 'asc').map((row) => row.id)).toEqual(['a', 'c', 'b'])
  })

  it('csvFileNameFrom: filename* 우선 · filename 폴백 · 없으면 기본명', () => {
    expect(csvFileNameFrom("attachment; filename=\"seller-sales-breakdown-product-2026-03-01_2026-03-31.csv\"; filename*=UTF-8''%EB%A7%A4%EC%B6%9C%ED%86%B5%EA%B3%84_%EC%83%81%ED%92%88_2026-03-01_2026-03-31.csv"))
      .toBe('매출통계_상품_2026-03-01_2026-03-31.csv')
    expect(csvFileNameFrom('attachment; filename="plain.csv"')).toBe('plain.csv')
    expect(csvFileNameFrom(null)).toBe('sales-breakdown.csv')
  })
})
