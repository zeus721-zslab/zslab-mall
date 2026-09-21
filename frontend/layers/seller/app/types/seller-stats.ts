import type { SellerStatsAxis } from '#layers/seller/app/lib/constants/seller-stats'

/**
 * 셀러 매출 통계 API 타입(Track 90-E-1·D-200 `GET /api/v1/seller/stats/sales`·`/breakdown` 응답 1:1·backend stats/controller/response/SellerSales* 실측).
 * 요약·추이는 관리자와 같은 BE record라 공용(~/types/stats)을 쓴다. 값의 정의는 내 품목(order_item) 축(D-192: 자기 품목 total_price 합·
 * orderCount = 자기 품목이 포함된 주문 수). BE는 전역 NON_NULL 직렬화라 null은 키 생략 → optional.
 */

export type { SalesStatsResponse as SellerSalesStatsResponse, SalesSummary as SellerSalesSummary, SalesTrendBucket as SellerSalesTrendBucket } from '~/types/stats'

export interface SellerSalesBreakdownRow {
  /** PRODUCT = 상품 public_id·OPTION = variant public_id·CATEGORY = categoryId 문자열. soft-delete로 미존재면 생략. */
  key?: string | null
  /** PRODUCT = 상품명 스냅샷·OPTION = "상품명 / 옵션라벨" 스냅샷·CATEGORY = 현행 표시명(미존재면 생략). */
  name?: string | null
  revenue: number
  share: number
  orderCount: number
  quantity: number
  compareRevenue?: number | null
}

export interface SellerSalesBreakdownResponse {
  axis: SellerStatsAxis
  totalRevenue: number
  rows: SellerSalesBreakdownRow[]
}
