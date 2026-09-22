import type { ApexOptions } from 'apexcharts'
import type { AdminDashboardDailyOrders, AdminDashboardMonthlyRevenue, AdminDashboardPending } from '#layers/admin/app/types/admin-dashboard'
import {
  ADMIN_CLAIMS_PATH,
  ADMIN_DELIVERIES_PATH,
  ADMIN_ORDERS_PATH,
  ADMIN_PRODUCTS_PATH,
  ADMIN_SELLERS_PATH,
  ADMIN_SETTLEMENTS_PATH,
} from '#layers/admin/app/lib/admin-back-path'
import { formatWon } from '#layers/admin/app/lib/format'

/**
 * 관리자 대시보드 표시 규칙 순수 함수(FE-33·admin-settlement-view 패턴). 증감률·배지 톤·처리 대기 링크/톤·차트 옵션을 여기 모아 vitest로
 * 고정하고 컴포넌트는 표시·배선만 한다.
 */

// 증감률·톤(ChangeTone·changeRate·formatChangeRate·changeTone)은 셀러 통계와 공용이라 app/lib/stats-view.ts로 이동(FE-52)·기존 이름 re-export
export { CHANGE_RATE_UNAVAILABLE, changeRate, formatChangeRate, changeTone } from '~/lib/stats-view'
export type { ChangeTone } from '~/lib/stats-view'
import type { ChangeTone } from '~/lib/stats-view'

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
 * 처리 대기 7칸 정의. 정산·클레임은 목록의 status 필터, 배송 대기는 주문 목록 status=PAID(BE는 품목 PAID 건수·주문 목록은 주문 단위라 근사).
 * 재고 임박은 상품 목록 stockFilter=LOW(Track 89-A·BE는 variant 건수·목록은 상품 단위라 근사).
 * 상품·셀러 승인 대기(Track 96-2 FE-54·C-01)는 각 목록 status=PENDING(BE 카운트와 목록 필터 조건 동일·삭제 제외).
 * 클레임 처리 대기(Track 96-4 FE-56·C-02)는 클레임 목록 action=FOLLOWUP(BE 카운트와 같은 Specification·후속 액션 5종).
 * 장기 배송중(Track 99 FE-61·D-210)은 배송 목록 status=SHIPPING(BE는 발송 후 3일 이상 건수·목록은 배송중 전체라 근사).
 */
export const PENDING_TILES: PendingTile[] = [
  { key: 'settlementPending', label: '정산 대기', to: `${ADMIN_SETTLEMENTS_PATH}?status=PENDING`, alertTone: 'warning' },
  { key: 'claimRequested', label: '클레임 요청', to: `${ADMIN_CLAIMS_PATH}?status=REQUESTED`, alertTone: 'warning' },
  { key: 'deliveryReady', label: '배송 대기', to: `${ADMIN_ORDERS_PATH}?status=PAID`, alertTone: 'warning' },
  { key: 'lowStock', label: '재고 임박', to: `${ADMIN_PRODUCTS_PATH}?stockFilter=LOW`, alertTone: 'danger' },
  { key: 'productPending', label: '상품 승인 대기', to: `${ADMIN_PRODUCTS_PATH}?status=PENDING`, alertTone: 'warning' },
  { key: 'sellerPending', label: '셀러 승인 대기', to: `${ADMIN_SELLERS_PATH}?status=PENDING`, alertTone: 'warning' },
  { key: 'claimFollowup', label: '클레임 처리 대기', to: `${ADMIN_CLAIMS_PATH}?action=FOLLOWUP`, alertTone: 'warning' },
  { key: 'longShipping', label: '장기 배송중', to: `${ADMIN_DELIVERIES_PATH}?status=SHIPPING`, alertTone: 'warning' },
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

/** 축 차트 계열(apexcharts 전역 ApexAxisChartSeries는 ambient라 레이어 타입으로 고정). null은 선 끊김(FE-34 비교 계열 후행 구간). type은 혼합 차트(막대+선·FE-35 가입 추이)의 계열별 유형·미지정이면 차트 type을 따른다. */
export type AdminChartSeries = { name: string; type?: 'line' | 'column' | 'area'; data: (number | null)[] }[]

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
