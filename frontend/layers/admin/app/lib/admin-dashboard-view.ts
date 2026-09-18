import type { ApexOptions } from 'apexcharts'
import type { AdminDashboardDailyOrders, AdminDashboardMonthlyRevenue, AdminDashboardPending } from '#layers/admin/app/types/admin-dashboard'
import { ADMIN_CLAIMS_PATH, ADMIN_ORDERS_PATH, ADMIN_SETTLEMENTS_PATH } from '#layers/admin/app/lib/admin-back-path'
import { formatWon } from '#layers/admin/app/lib/format'

/**
 * 관리자 대시보드 표시 규칙 순수 함수(FE-33·admin-settlement-view 패턴). 증감률·배지 톤·처리 대기 링크/톤·차트 옵션을 여기 모아 vitest로
 * 고정하고 컴포넌트는 표시·배선만 한다.
 */

/** 증감 톤: up=연한 녹색·down=연한 빨강·flat=회색(0 또는 비교 불가). */
export type ChangeTone = 'up' | 'down' | 'flat'

const PERCENT = 100
const RATE_FRACTION_DIGITS = 1
export const CHANGE_RATE_UNAVAILABLE = '—'

/** (현재 − 비교) / 비교 × 100. 비교값 0(또는 음수·비정상)이면 계산 불가 null — Infinity·NaN을 만들지 않는다. */
export function changeRate(current: number, previous: number): number | null {
  if (!Number.isFinite(current) || !Number.isFinite(previous) || previous <= 0) return null
  return ((current - previous) / previous) * PERCENT
}

/** "+12.3%"·"-4.0%"·"0.0%"·비교 불가 "—". */
export function formatChangeRate(rate: number | null): string {
  if (rate === null) return CHANGE_RATE_UNAVAILABLE
  const fixed = rate.toFixed(RATE_FRACTION_DIGITS)
  if (rate > 0) return `+${fixed}%`
  return `${fixed}%`
}

export function changeTone(rate: number | null): ChangeTone {
  if (rate === null || rate === 0) return 'flat'
  return rate > 0 ? 'up' : 'down'
}

/** 배지 CSS 클래스(admin-vuetify.css .adm-chip--*). flat은 회색(neutral). */
export function changeChipClass(tone: ChangeTone): string {
  const suffix = tone === 'up' ? 'success' : tone === 'down' ? 'danger' : 'neutral'
  return `adm-chip adm-chip--${suffix}`
}

// ---------- 처리 대기 ----------

export type PendingKey = keyof AdminDashboardPending

export interface PendingTile {
  key: PendingKey
  label: string
  /** 이동 가능한 목록 경로(필터 포함). 연결할 화면이 없으면 null(카운트만). */
  to: string | null
  /** 1건 이상일 때 톤. 재고 임박만 danger. */
  alertTone: 'warning' | 'danger'
}

/**
 * 처리 대기 4칸 정의. 정산·클레임은 목록의 status 필터, 배송 대기는 주문 목록 status=PAID(BE는 품목 PAID 건수·주문 목록은 주문 단위라 근사).
 * 재고 임박은 재고 화면이 플레이스홀더이고 상품 목록에 재고 필터가 없어 링크 없음.
 */
export const PENDING_TILES: PendingTile[] = [
  { key: 'settlementPending', label: '정산 대기', to: `${ADMIN_SETTLEMENTS_PATH}?status=PENDING`, alertTone: 'warning' },
  { key: 'claimRequested', label: '클레임 요청', to: `${ADMIN_CLAIMS_PATH}?status=REQUESTED`, alertTone: 'warning' },
  { key: 'deliveryReady', label: '배송 대기', to: `${ADMIN_ORDERS_PATH}?status=PAID`, alertTone: 'warning' },
  { key: 'lowStock', label: '재고 임박', to: null, alertTone: 'danger' },
]

/** 0건은 회색(neutral), 1건 이상은 칸별 주의 톤. */
export function pendingChipClass(tile: PendingTile, count: number): string {
  return `adm-chip adm-chip--${count > 0 ? tile.alertTone : 'neutral'}`
}

// ---------- 차트 ----------

/** Vuetify 관리자 테마와 같은 색(lib/vuetify.ts primary·warning·info). */
const CHART_COLOR_PRIMARY = '#2563EB'
const CHART_COLOR_WARNING = '#F97316'
const CHART_COLOR_INFO = '#0EA5E9'
const CHART_FONT_FAMILY = 'inherit'

/** "2026-04" → "2026.04". */
export function monthLabel(yearMonth: string): string {
  return yearMonth.replace('-', '.')
}

/** "2026-09-18" → "09.18". */
export function dayLabel(date: string): string {
  return date.slice(5).replace('-', '.')
}

function baseOptions(categories: string[]): ApexOptions {
  return {
    chart: { toolbar: { show: false }, zoom: { enabled: false }, fontFamily: CHART_FONT_FAMILY, animations: { enabled: false } },
    dataLabels: { enabled: false },
    grid: { borderColor: '#E4E4E7', strokeDashArray: 4 },
    xaxis: { categories, axisBorder: { show: false }, axisTicks: { show: false }, labels: { style: { colors: '#71717A' } } },
    legend: { position: 'top', horizontalAlign: 'left' },
    tooltip: { shared: true, intersect: false },
  }
}

/** 축 차트 계열(apexcharts 전역 ApexAxisChartSeries는 ambient라 레이어 타입으로 고정). null은 선 끊김(FE-34 비교 계열 후행 구간). */
export type AdminChartSeries = { name: string; data: (number | null)[] }[]

export interface ChartSpec {
  series: AdminChartSeries
  options: ApexOptions
}

/** 최근 6개월 매출·환불 2계열 막대. y축·툴팁은 원 단위 콤마 포맷. 데이터 0이면 축만 그려진다. */
export function monthlyRevenueChart(rows: AdminDashboardMonthlyRevenue[]): ChartSpec {
  const base = baseOptions(rows.map((row) => monthLabel(row.yearMonth)))
  return {
    series: [
      { name: '매출', data: rows.map((row) => row.revenue) },
      { name: '환불', data: rows.map((row) => row.refund) },
    ],
    options: {
      ...base,
      colors: [CHART_COLOR_PRIMARY, CHART_COLOR_WARNING],
      plotOptions: { bar: { columnWidth: '45%', borderRadius: 4 } },
      yaxis: { labels: { formatter: (value: number) => formatWon(value), style: { colors: '#71717A' } }, min: 0, forceNiceScale: true },
      tooltip: { ...base.tooltip, y: { formatter: (value: number) => formatWon(value) } },
    },
  }
}

/** 최근 30일 일별 주문수 area. y축 정수·툴팁 "N건". */
export function dailyOrdersChart(rows: AdminDashboardDailyOrders[]): ChartSpec {
  const base = baseOptions(rows.map((row) => dayLabel(row.date)))
  return {
    series: [{ name: '주문수', data: rows.map((row) => row.orderCount) }],
    options: {
      ...base,
      colors: [CHART_COLOR_INFO],
      stroke: { curve: 'smooth', width: 2 },
      fill: { type: 'gradient', gradient: { shadeIntensity: 1, opacityFrom: 0.35, opacityTo: 0.05, stops: [0, 100] } },
      xaxis: { ...base.xaxis, tickAmount: 10 },
      yaxis: { labels: { formatter: (value: number) => `${Math.round(value)}건`, style: { colors: '#71717A' } }, min: 0, forceNiceScale: true },
      tooltip: { ...base.tooltip, y: { formatter: (value: number) => `${value}건` } },
    },
  }
}

/** 데이터가 전부 0인지(빈 상태 문구 판정). */
export function isAllZero(values: number[]): boolean {
  return values.every((value) => value === 0)
}
