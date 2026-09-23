import type { ApexOptions } from 'apexcharts'
import type { SellerDashboardDailyTrend, SellerDashboardPending, SellerDashboardPeriod } from '#layers/seller/app/types/seller-dashboard'
import {
  SELLER_CLAIMS_PATH,
  SELLER_DELIVERIES_PATH,
  SELLER_INVENTORY_PATH,
  SELLER_ORDERS_PATH,
  SELLER_SETTLEMENTS_PATH,
} from '#layers/seller/app/lib/seller-back-path'
import { formatWon } from '#layers/seller/app/lib/format'

/**
 * 셀러 대시보드 표시 규칙 순수 함수(Track 90-B-3·관리자 admin-dashboard-view 복제·vitest 대상). 기간 프리셋·검증(BE D-192 한도와 1:1)·
 * 처리 대기 링크/톤·차트 옵션(dailyTrend 단일 배열 → 매출·주문 건수 2종)을 모아 두고 컴포넌트는 표시·배선만 한다.
 */

// ---------- 기간 ----------

/** BE SellerDashboardQueryService 상수와 1:1(DEFAULT_PERIOD_DAYS=30·MAX_PERIOD_DAYS=92). */
export const DASHBOARD_DEFAULT_PERIOD_DAYS = 30
export const DASHBOARD_MAX_PERIOD_DAYS = 92

export type DashboardPeriodPreset = 7 | 30 | 90

export const DASHBOARD_PERIOD_PRESETS: { value: DashboardPeriodPreset; label: string }[] = [
  { value: 7, label: '최근 7일' },
  { value: 30, label: '최근 30일' },
  { value: 90, label: '최근 90일' },
]

const DATE_ONLY_PATTERN = /^\d{4}-\d{2}-\d{2}$/
const MS_PER_DAY = 24 * 60 * 60 * 1000

/** Date(브라우저 로컬 = 운영 KST 전제·BE와 같은 벽시계) → "yyyy-MM-dd". */
export function toDateKey(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

/** 오늘 포함 최근 N일 기간(to=오늘·from=오늘−(N−1)). */
export function presetPeriod(days: DashboardPeriodPreset, today: Date = new Date()): SellerDashboardPeriod {
  const from = new Date(today.getFullYear(), today.getMonth(), today.getDate() - (days - 1))
  return { from: toDateKey(from), to: toDateKey(today) }
}

/** "yyyy-MM-dd" 형식만 통과(네이티브 date 입력값 방어). */
export function normalizeDateOnly(value: string | null | undefined): string | null {
  return typeof value === 'string' && DATE_ONLY_PATTERN.test(value) ? value : null
}

/** 양끝 포함 일수(from·to 모두 yyyy-MM-dd 전제). */
export function periodDays(period: SellerDashboardPeriod): number {
  const from = Date.UTC(Number(period.from.slice(0, 4)), Number(period.from.slice(5, 7)) - 1, Number(period.from.slice(8, 10)))
  const to = Date.UTC(Number(period.to.slice(0, 4)), Number(period.to.slice(5, 7)) - 1, Number(period.to.slice(8, 10)))
  return Math.round((to - from) / MS_PER_DAY) + 1
}

/** 기간 검증(BE 400 예방): 형식 → from≤to → 최대 92일. 통과면 null. */
export function validatePeriod(period: SellerDashboardPeriod): string | null {
  if (!normalizeDateOnly(period.from) || !normalizeDateOnly(period.to)) return '기간은 yyyy-MM-dd 형식이어야 합니다.'
  if (period.from > period.to) return '시작일이 종료일보다 늦습니다.'
  if (periodDays(period) > DASHBOARD_MAX_PERIOD_DAYS) return `기간은 최대 ${DASHBOARD_MAX_PERIOD_DAYS}일까지 조회할 수 있습니다.`
  return null
}

/** 현재 기간이 프리셋(오늘 기준)과 정확히 같으면 그 값, 아니면 null(직접 입력 상태). */
export function matchingPreset(period: SellerDashboardPeriod, today: Date = new Date()): DashboardPeriodPreset | null {
  for (const preset of DASHBOARD_PERIOD_PRESETS) {
    const candidate = presetPeriod(preset.value, today)
    if (candidate.from === period.from && candidate.to === period.to) return preset.value
  }
  return null
}

/** "2026-09-01" → "2026.09.01". 기간 라벨용. */
export function formatPeriodLabel(period: SellerDashboardPeriod): string {
  return `${period.from.replaceAll('-', '.')} ~ ${period.to.replaceAll('-', '.')}`
}

// ---------- 처리 대기 ----------

export type PendingKey = keyof SellerDashboardPending

export interface PendingTile {
  key: PendingKey
  label: string
  /** 이동할 셀러 화면 경로(필터 포함). */
  to: string
  alertTone: 'warning' | 'danger'
  /** 칸 아래 한 줄 설명(진입점·의미). */
  hint: string
}

/**
 * 처리 대기 5칸(Track 96-1 FE-53·C-14 4칸 + Track 99 FE-61 장기 배송중 1칸). 배송 대기(PAID 품목)는 품목 목록 status=PAID로, 클레임 요청은
 * 클레임 목록 status=REQUESTED로 정확히 연결된다. 재고 임박은 재고 화면에 임박 필터가 없어 화면 진입만 연결한다. 정산 예정은 PENDING 건수이며
 * 셀러 정산 목록에는 확정 대기 정산이 나오지 않지만(D-191 ε) 정산 화면 상단이 같은 건수를 안내하므로 화면 진입을 연결한다.
 * 장기 배송중은 배송 화면 status=SHIPPING으로 연결한다(BE는 발송 후 3일 이상 건수·목록은 배송중 전체라 근사).
 */
export const PENDING_TILES: PendingTile[] = [
  { key: 'deliveryReady', label: '배송 대기', to: `${SELLER_ORDERS_PATH}?status=PAID`, alertTone: 'warning', hint: '결제완료 품목 · 주문 화면에서 발송' },
  { key: 'claimRequested', label: '클레임 요청', to: `${SELLER_CLAIMS_PATH}?status=REQUESTED`, alertTone: 'warning', hint: '요청 상태 클레임 · 처리는 관리자가 진행' },
  { key: 'lowStock', label: '재고 임박', to: SELLER_INVENTORY_PATH, alertTone: 'danger', hint: '가용 재고 1~5 · 재고 화면에서 입고' },
  { key: 'settlementPending', label: '정산 예정', to: SELLER_SETTLEMENTS_PATH, alertTone: 'warning', hint: '확정 대기 정산 건수 · 확정 후 정산 화면에 표시' },
  { key: 'longShipping', label: '장기 배송중', to: `${SELLER_DELIVERIES_PATH}?status=SHIPPING`, alertTone: 'warning', hint: '발송 후 3일 이상 배송중 · 배송 화면에서 확인' },
]

/** 0건은 회색(neutral), 1건 이상은 칸별 주의 톤. */
export function pendingChipClass(tile: PendingTile, count: number): string {
  return `slr-chip slr-chip--${count > 0 ? tile.alertTone : 'neutral'}`
}

// ---------- 차트 ----------

/** 셀러 Vuetify 테마와 같은 색(lib/vuetify.ts primary teal·info). */
const CHART_COLOR_PRIMARY = '#0D9488'
const CHART_COLOR_INFO = '#0EA5E9'
const CHART_FONT_FAMILY = 'inherit'
/** x축 눈금 최대 개수(기간이 길어도 라벨이 겹치지 않게). */
const X_AXIS_TICKS = 10

/** "2026-09-18" → "09.18". */
export function dayLabel(date: string): string {
  return date.slice(5).replace('-', '.')
}

function baseOptions(categories: string[]): ApexOptions {
  return {
    chart: { toolbar: { show: false }, zoom: { enabled: false }, fontFamily: CHART_FONT_FAMILY, animations: { enabled: false } },
    dataLabels: { enabled: false },
    grid: { borderColor: '#E4E4E7', strokeDashArray: 4 },
    xaxis: { categories, axisBorder: { show: false }, axisTicks: { show: false }, labels: { style: { colors: '#71717A' } }, tickAmount: X_AXIS_TICKS },
    legend: { position: 'top', horizontalAlign: 'left' },
    tooltip: { shared: true, intersect: false },
  }
}

export type SellerChartSeries = { name: string; data: (number | null)[] }[]

export interface ChartSpec {
  series: SellerChartSeries
  options: ApexOptions
}

/** 일별 매출(자기 품목 합) 막대. y축·툴팁은 원 단위 콤마 포맷. */
export function revenueTrendChart(rows: SellerDashboardDailyTrend[]): ChartSpec {
  const base = baseOptions(rows.map((row) => dayLabel(row.date)))
  return {
    series: [{ name: '매출', data: rows.map((row) => row.revenue) }],
    options: {
      ...base,
      colors: [CHART_COLOR_PRIMARY],
      plotOptions: { bar: { columnWidth: '55%', borderRadius: 3 } },
      yaxis: { labels: { formatter: (value: number) => formatWon(value), style: { colors: '#71717A' } }, min: 0, forceNiceScale: true },
      tooltip: { ...base.tooltip, y: { formatter: (value: number) => formatWon(value) } },
    },
  }
}

/** 일별 주문 건수(DISTINCT 주문) area. y축 정수·툴팁 "N건". */
export function orderCountTrendChart(rows: SellerDashboardDailyTrend[]): ChartSpec {
  const base = baseOptions(rows.map((row) => dayLabel(row.date)))
  return {
    series: [{ name: '주문 건수', data: rows.map((row) => row.orderCount) }],
    options: {
      ...base,
      colors: [CHART_COLOR_INFO],
      stroke: { curve: 'smooth', width: 2 },
      fill: { type: 'gradient', gradient: { shadeIntensity: 1, opacityFrom: 0.35, opacityTo: 0.05, stops: [0, 100] } },
      yaxis: { labels: { formatter: (value: number) => `${Math.round(value)}건`, style: { colors: '#71717A' } }, min: 0, forceNiceScale: true },
      tooltip: { ...base.tooltip, y: { formatter: (value: number) => `${value}건` } },
    },
  }
}

/** 데이터가 전부 0인지(빈 상태 문구 판정). */
export function isAllZero(values: number[]): boolean {
  return values.every((value) => value === 0)
}
