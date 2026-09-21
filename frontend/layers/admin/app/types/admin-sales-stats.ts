import type { StatsAxis } from '#layers/admin/app/lib/constants/admin-sales-stats'

/**
 * 관리자 매출 통계 API 타입(FE-34·D-181 `GET /api/v1/admin/stats/sales`·`/breakdown` 응답 1:1·backend stats/controller/response 실측).
 * BE는 전역 NON_NULL 직렬화라 compareSummary·compareTrend·compareRevenue·parentKey·key·name은 값이 null이면 <b>키 자체가 생략</b>된다
 * → 전부 optional로 선언하고 lib/admin-sales-stats-view.ts `normalize*`가 `?? null`로 정규화한 뒤 화면에 넘긴다.
 * 금액은 원 단위 정수, share는 % 소수 2자리, avgItemsPerOrder는 소수 2자리.
 */

// 요약·추이·요약+추이 응답은 셀러 매출 통계와 같은 BE record라 app/types/stats.ts로 이동(FE-52)·기존 이름으로 re-export
export type {
  SalesSummary as AdminSalesSummary,
  SalesTrendBucket as AdminSalesTrendBucket,
  SalesStatsResponse as AdminSalesStatsResponse,
} from '~/types/stats'

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
