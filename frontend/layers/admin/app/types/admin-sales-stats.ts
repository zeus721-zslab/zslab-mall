import type { StatsAxis } from '#layers/admin/app/lib/constants/admin-sales-stats'

/**
 * 관리자 매출 통계 API 타입(FE-34·D-181 `GET /api/v1/admin/stats/sales`·`/breakdown` 응답 1:1·backend stats/controller/response 실측).
 * BE는 전역 NON_NULL 직렬화라 compareSummary·compareTrend·compareRevenue·parentKey·key·name은 값이 null이면 <b>키 자체가 생략</b>된다
 * → 전부 optional로 선언하고 lib/admin-sales-stats-view.ts `normalize*`가 `?? null`로 정규화한 뒤 화면에 넘긴다.
 * 금액은 원 단위 정수, share는 % 소수 2자리, avgItemsPerOrder는 소수 2자리.
 */

export interface AdminSalesSummary {
  revenue: number
  refund: number
  netRevenue: number
  orderCount: number
  itemQuantity: number
  avgOrderValue: number
  avgItemsPerOrder: number
}

/** bucketKey 일 yyyy-MM-dd·주 yyyy-'W'ww·월 yyyy-MM, bucketLabel은 주만 주 시작일(월요일). */
export interface AdminSalesTrendBucket {
  bucketKey: string
  bucketLabel: string
  revenue: number
  refund: number
  netRevenue: number
  orderCount: number
}

export interface AdminSalesStatsResponse {
  summary: AdminSalesSummary
  compareSummary?: AdminSalesSummary | null
  trend: AdminSalesTrendBucket[]
  /** trend와 같은 길이(BE가 뒤를 0으로 채우거나 절단·D-181 §1-A 4). */
  compareTrend?: AdminSalesTrendBucket[] | null
}

export interface AdminSalesBreakdownRow {
  /** CATEGORY = categoryId 문자열·SELLER/PRODUCT = public_id. soft-delete로 미존재면 생략. */
  key?: string | null
  name?: string | null
  revenue: number
  share: number
  orderCount: number
  quantity: number
  compareRevenue?: number | null
  drillable: boolean
}

export interface AdminSalesBreakdownResponse {
  axis: StatsAxis
  parentKey?: string | null
  totalRevenue: number
  rows: AdminSalesBreakdownRow[]
}
