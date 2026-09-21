import type { StatsCompare } from '~/lib/constants/stats'
import { CLAIM_REASON_LABELS, CLAIM_TYPE_LABELS, type ClaimReasonCode, type ClaimType } from '~/lib/constants/claim'
import type { ClaimReasonShare, ClaimTrendBucket, ClaimTypeShare, SalesStatsResponse, SalesSummary, SalesTrendBucket } from '~/types/stats'

/**
 * 통계 표시 규칙 순수 함수 공용(Track 90-E-1·FE-52·90-E-2·관리자 admin-dashboard-view·admin-sales-stats-view·admin-order-stats-view에서 동작
 * 무변경으로 이동·관리자는 re-export). 증감률·톤, 응답 정규화, 비교 추이 후행 0 절단, x축 라벨, 분해 행 정렬, CSV 파일명 추출처럼 레이어 CSS·색·라우트에 묶이지 않는
 * 것만 둔다. 배지 CSS 클래스(adm-chip/slr-chip)·차트 색·카드 정의는 각 레이어가 가진다.
 */

// ---------- 증감 ----------

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

/** 증감 톤. inverse(환불)면 up↔down을 바꿔 "환불 증가 = 빨강"이 되게 한다. flat은 그대로. */
export function salesChangeTone(rate: number | null, inverse: boolean): ChangeTone {
  const tone = changeTone(rate)
  if (!inverse || tone === 'flat') return tone
  return tone === 'up' ? 'down' : 'up'
}

// ---------- 정규화 ----------

export interface NormalizedSalesStats {
  summary: SalesSummary
  compareSummary: SalesSummary | null
  trend: SalesTrendBucket[]
  compareTrend: SalesTrendBucket[] | null
}

/** BE NON_NULL 직렬화로 생략된 비교 필드를 null로 고정한다. */
export function normalizeSalesStats(response: SalesStatsResponse): NormalizedSalesStats {
  return {
    summary: response.summary,
    compareSummary: response.compareSummary ?? null,
    trend: response.trend,
    compareTrend: response.compareTrend ?? null,
  }
}

const ITEMS_PER_ORDER_FRACTION_DIGITS = 2

export function formatItemsPerOrder(value: number): string {
  return `${value.toFixed(ITEMS_PER_ORDER_FRACTION_DIGITS)}개`
}

// ---------- 추이 ----------

/** 구간이 모두 0(매출·환불·주문 없음)인지 — 후행 0 판정과 빈 상태 판정에 같이 쓴다. */
export function isZeroBucket(bucket: SalesTrendBucket): boolean {
  return bucket.revenue === 0 && bucket.refund === 0 && bucket.orderCount === 0
}

/**
 * 비교 추이의 순매출 계열. BE는 compareTrend를 trend 길이에 맞춰 뒤를 0으로 채우거나 절단하므로(D-181 §1-A 4), 실제 비교 구간이
 * 더 적을 때 끝이 0으로 급락해 보인다 → <b>후행</b> 0 구간만 null로 바꿔 선을 끊는다(중간 0은 실제 0이라 유지). 비교 없으면 null.
 */
export function compareNetSeries(compareTrend: SalesTrendBucket[] | null, length: number): (number | null)[] | null {
  if (compareTrend === null) return null
  const trimmed = compareTrend.slice(0, length)
  const values: (number | null)[] = trimmed.map((bucket) => bucket.netRevenue)
  for (let index = trimmed.length - 1; index >= 0 && isZeroBucket(trimmed[index]!); index--) {
    values[index] = null
  }
  while (values.length < length) values.push(null)
  return values
}

/** x축 라벨: yyyy-MM-dd(일·주 시작일)는 연도를 떼 MM-DD로 줄인다(30일·7일 조회에서 겹침 실측). 월(yyyy-MM)은 그대로. 툴팁은 전체 라벨. */
export function axisLabel(label: string): string {
  return /^\d{4}-\d{2}-\d{2}$/.test(label) ? label.slice(5) : label
}

/** 추이 전 구간이 0인지(빈 상태 캡션). */
export function isTrendEmpty(trend: SalesTrendBucket[]): boolean {
  return trend.every(isZeroBucket)
}

// ---------- 분해 테이블 ----------

/** 정렬 가능한 분해 행의 최소 형태(관리자·셀러 행 뷰가 모두 만족). */
export interface SortableBreakdownRow {
  name: string
  revenue: number
  share: number
  orderCount: number
  quantity: number
  compareRevenue: number | null
  rate: number | null
}

export type BreakdownSortKey = keyof SortableBreakdownRow

/** 클라이언트 정렬(BE는 매출 DESC 고정). null(비교 불가)은 방향과 무관하게 맨 뒤. 동률은 매출 DESC. */
export function sortBreakdownRows<T extends SortableBreakdownRow>(rows: T[], key: BreakdownSortKey, order: 'asc' | 'desc'): T[] {
  const direction = order === 'asc' ? 1 : -1
  return [...rows].sort((a, b) => {
    const left = a[key]
    const right = b[key]
    if (left === null && right === null) return b.revenue - a.revenue
    if (left === null) return 1
    if (right === null) return -1
    const primary = typeof left === 'string' && typeof right === 'string' ? left.localeCompare(right, 'ko-KR') : Number(left) - Number(right)
    return primary !== 0 ? primary * direction : b.revenue - a.revenue
  })
}

/** 비교 열 표시 여부. */
export function showsCompare(compare: StatsCompare): boolean {
  return compare !== 'NONE'
}

// ---------- CSV ----------

const CSV_DEFAULT_NAME = 'sales-breakdown.csv'

/**
 * Content-Disposition에서 파일명 추출: filename*=UTF-8''(RFC 5987) 우선·없으면 filename="…"·둘 다 없으면 기본명.
 * BE는 `attachment; filename="…csv"; filename*=UTF-8''매출통계_…csv`(D-181 §1-A 10·D-200 동일)를 보낸다.
 */
export function csvFileNameFrom(contentDisposition: string | null): string {
  if (!contentDisposition) return CSV_DEFAULT_NAME
  const extended = /filename\*=UTF-8''([^;]+)/i.exec(contentDisposition)
  if (extended?.[1]) {
    try {
      return decodeURIComponent(extended[1].trim())
    } catch {
      // 잘못된 percent-encoding이면 ASCII filename으로 폴백
    }
  }
  const plain = /filename="([^"]+)"/i.exec(contentDisposition) ?? /filename=([^;]+)/i.exec(contentDisposition)
  return plain?.[1]?.trim() || CSV_DEFAULT_NAME
}

// ---------- 주문·클레임(90-E-2·관리자 admin-order-stats-view에서 이동) ----------

const PERCENT_FRACTION_DIGITS = 2
const HOURS_PER_DAY = 24
const HOURS_FRACTION_DIGITS = 1

/** 도달률·이탈률 표기(소수 1자리·null은 "—"). */
export function formatRate(rate: number | null): string {
  return rate === null ? '—' : `${rate.toFixed(RATE_FRACTION_DIGITS)}%`
}

/** 비율 표기(소수 2자리·클레임률·환불률·비중). */
export function formatPercent(value: number): string {
  return `${value.toFixed(PERCENT_FRACTION_DIGITS)}%`
}

/** 24h 미만은 "N.N시간", 이상은 "N일 M시간"(시간은 반올림·24시간 올림 시 일 증가). */
export function formatHours(hours: number): string {
  if (hours < HOURS_PER_DAY) return `${hours.toFixed(HOURS_FRACTION_DIGITS)}시간`
  let days = Math.floor(hours / HOURS_PER_DAY)
  let remainder = Math.round(hours - days * HOURS_PER_DAY)
  if (remainder === HOURS_PER_DAY) {
    days += 1
    remainder = 0
  }
  return remainder === 0 ? `${days}일` : `${days}일 ${remainder}시간`
}

export const LEAD_TIME_EMPTY = '데이터 없음'

/** 분자/분모 × 100(분모 0이면 0). 퍼널 도달률·이탈률용. */
export function percentOf(numerator: number, denominator: number): number {
  return denominator === 0 ? 0 : (numerator / denominator) * 100
}

export function isZeroClaimBucket(bucket: ClaimTrendBucket): boolean {
  return bucket.claimCount === 0 && bucket.refundAmount === 0 && bucket.refundCount === 0
}

/** 비교 환불률 계열. BE가 뒤를 0으로 채우므로 후행 0 구간만 null로 끊는다(compareNetSeries와 동일 규칙). */
export function compareRefundRateSeries(compareTrend: ClaimTrendBucket[] | null, length: number): (number | null)[] | null {
  if (compareTrend === null) return null
  const trimmed = compareTrend.slice(0, length)
  const values: (number | null)[] = trimmed.map((bucket) => bucket.refundRate)
  for (let index = trimmed.length - 1; index >= 0 && isZeroClaimBucket(trimmed[index]!); index--) {
    values[index] = null
  }
  while (values.length < length) values.push(null)
  return values
}

export function isClaimTrendEmpty(trend: ClaimTrendBucket[]): boolean {
  return trend.every(isZeroClaimBucket)
}

export interface DistributionRowView {
  key: string
  label: string
  count: number
  share: number
}

/** 유형 라벨(단일 소스 claim.ts). */
export function claimTypeLabel(type: ClaimType): string {
  return CLAIM_TYPE_LABELS[type] ?? type
}

/** 사유 라벨. BE reason_code는 무제약 VARCHAR라 enum 밖 값은 원문 그대로 표기한다(D-182). */
export function claimReasonLabel(code: string): string {
  return CLAIM_REASON_LABELS[code as ClaimReasonCode] ?? code
}

export function claimTypeRows(rows: ClaimTypeShare[]): DistributionRowView[] {
  return rows.map((row) => ({ key: row.type, label: claimTypeLabel(row.type), count: row.count, share: row.share }))
}

export function claimReasonRows(rows: ClaimReasonShare[]): DistributionRowView[] {
  return rows.map((row) => ({ key: row.reasonCode, label: claimReasonLabel(row.reasonCode), count: row.count, share: row.share }))
}
