import { describe, it, expect } from 'vitest'
import type { ApexOptions } from 'apexcharts'
import {
  DASHBOARD_MAX_PERIOD_DAYS,
  PENDING_TILES,
  dayLabel,
  formatPeriodLabel,
  isAllZero,
  matchingPreset,
  normalizeDateOnly,
  orderCountTrendChart,
  pendingChipClass,
  periodDays,
  presetPeriod,
  revenueTrendChart,
  toDateKey,
  validatePeriod,
} from '#layers/seller/app/lib/seller-dashboard-view'

// Track 90-B-3(D-192): 기간 프리셋·검증(BE 30일 기본·92일 최대와 1:1)·처리 대기 링크·dailyTrend → 차트 2종 파생.
const TODAY = new Date(2026, 8, 20) // 2026-09-20

describe('기간', () => {
  it('프리셋: 오늘 포함 최근 N일(7·30·90) · toDateKey 로컬 벽시계', () => {
    expect(toDateKey(TODAY)).toBe('2026-09-20')
    expect(presetPeriod(7, TODAY)).toEqual({ from: '2026-09-14', to: '2026-09-20' })
    expect(presetPeriod(30, TODAY)).toEqual({ from: '2026-08-22', to: '2026-09-20' })
    expect(presetPeriod(90, TODAY)).toEqual({ from: '2026-06-23', to: '2026-09-20' })
    expect(periodDays(presetPeriod(90, TODAY))).toBe(90)
  })

  it('validatePeriod: 형식 → from≤to → 최대 92일(93일부터 거부·BE MAX_PERIOD_DAYS)', () => {
    expect(validatePeriod({ from: '2026-09-01', to: '2026-09-20' })).toBeNull()
    expect(validatePeriod({ from: '2026/09/01', to: '2026-09-20' })).toContain('yyyy-MM-dd')
    expect(validatePeriod({ from: '2026-09-21', to: '2026-09-20' })).toBe('시작일이 종료일보다 늦습니다.')
    expect(validatePeriod({ from: '2026-01-01', to: '2026-04-02' })).toBeNull() // 92일
    expect(validatePeriod({ from: '2026-01-01', to: '2026-04-03' })).toContain(`최대 ${DASHBOARD_MAX_PERIOD_DAYS}일`) // 93일
    expect(DASHBOARD_MAX_PERIOD_DAYS).toBe(92)
  })

  it('matchingPreset: 프리셋과 정확 일치만 · 직접 입력은 null · normalizeDateOnly · formatPeriodLabel', () => {
    expect(matchingPreset(presetPeriod(30, TODAY), TODAY)).toBe(30)
    expect(matchingPreset({ from: '2026-08-23', to: '2026-09-20' }, TODAY)).toBeNull()
    expect(normalizeDateOnly('2026-09-20')).toBe('2026-09-20')
    expect(normalizeDateOnly('')).toBeNull()
    expect(normalizeDateOnly(undefined)).toBeNull()
    expect(formatPeriodLabel({ from: '2026-08-22', to: '2026-09-20' })).toBe('2026.08.22 ~ 2026.09.20')
  })
})

describe('처리 대기', () => {
  it('배송 대기만 품목 목록(status=PAID) 링크 · 클레임·재고·정산은 링크 없음(화면 부재·PENDING 404) · 힌트 문구', () => {
    const byKey = Object.fromEntries(PENDING_TILES.map((tile) => [tile.key, tile]))
    expect(byKey.deliveryReady?.to).toBe('/seller/orders?status=PAID')
    // Track 96-1 C-14: 4칸 전부 링크·"준비 중" 문구 없음
    expect(byKey.claimRequested?.to).toBe('/seller/claims?status=REQUESTED')
    expect(byKey.lowStock?.to).toBe('/seller/products/inventory')
    expect(byKey.settlementPending?.to).toBe('/seller/settlements')
    expect(byKey.settlementPending?.hint).toContain('확정 대기 정산 건수')
    // Track 99(FE-61·D-210): 장기 배송중 1칸 추가 — 배송 화면 status=SHIPPING
    expect(byKey.longShipping?.to).toBe('/seller/deliveries?status=SHIPPING')
    expect(byKey.longShipping?.hint).toContain('3일 이상')
    expect(PENDING_TILES.some((tile) => tile.hint.includes('준비 중'))).toBe(false)
    expect(PENDING_TILES.map((tile) => tile.key)).toEqual(['deliveryReady', 'claimRequested', 'lowStock', 'settlementPending', 'longShipping'])
  })

  it('pendingChipClass: 0건 neutral · 1건 이상 칸별 톤(재고 danger·나머지 warning)', () => {
    const byKey = Object.fromEntries(PENDING_TILES.map((tile) => [tile.key, tile]))
    expect(pendingChipClass(byKey.deliveryReady!, 0)).toBe('slr-chip slr-chip--neutral')
    expect(pendingChipClass(byKey.deliveryReady!, 2)).toBe('slr-chip slr-chip--warning')
    expect(pendingChipClass(byKey.lowStock!, 1)).toBe('slr-chip slr-chip--danger')
  })
})

describe('차트(dailyTrend 단일 배열 → 2종 파생)', () => {
  const rows = [
    { date: '2026-09-18', orderCount: 1, revenue: 32000 },
    { date: '2026-09-19', orderCount: 0, revenue: 0 },
    { date: '2026-09-20', orderCount: 2, revenue: 77000 },
  ]

  it('매출 막대: 카테고리 MM.dd·계열 revenue·y축/툴팁 원 포맷', () => {
    const spec = revenueTrendChart(rows)
    expect(spec.series).toEqual([{ name: '매출', data: [32000, 0, 77000] }])
    expect(spec.options.xaxis?.categories).toEqual(['09.18', '09.19', '09.20'])
    const yaxis = spec.options.yaxis as { labels: { formatter: (value: number) => string } }
    expect(yaxis.labels.formatter(32000)).toBe('32,000원')
    expect((spec.options.tooltip?.y as { formatter: (value: number) => string }).formatter(77000)).toBe('77,000원')
    expect(spec.options.chart?.animations?.enabled).toBe(false)
  })

  it('주문 건수 area: 계열 orderCount·y축 정수 "N건"·툴팁 "N건"', () => {
    const spec = orderCountTrendChart(rows)
    expect(spec.series).toEqual([{ name: '주문 건수', data: [1, 0, 2] }])
    const yaxis = spec.options.yaxis as { labels: { formatter: (value: number) => string } }
    expect(yaxis.labels.formatter(1.4)).toBe('1건')
    expect((spec.options.tooltip?.y as { formatter: (value: number) => string }).formatter(2)).toBe('2건')
    expect((spec.options as ApexOptions).stroke?.curve).toBe('smooth')
  })

  it('dayLabel·isAllZero', () => {
    expect(dayLabel('2026-09-18')).toBe('09.18')
    expect(isAllZero([0, 0])).toBe(true)
    expect(isAllZero([0, 1])).toBe(false)
    expect(isAllZero([])).toBe(true)
  })
})
