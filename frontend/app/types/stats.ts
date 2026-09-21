/**
 * 통계 응답 공용 타입(Track 90-E-1·FE-52). 관리자 매출 통계(D-181)와 셀러 매출 통계(D-200)는 BE가 같은 record(SalesSummaryResponse·
 * SalesTrendBucketResponse)를 내리므로 형태를 여기 한 번 선언하고 관리자 types/admin-sales-stats.ts는 기존 이름으로 re-export한다.
 * BE는 전역 NON_NULL 직렬화라 null 값은 키 자체가 생략된다 → optional. 금액은 원 단위 정수, avgItemsPerOrder는 소수 2자리.
 */

export interface SalesSummary {
  revenue: number
  refund: number
  netRevenue: number
  orderCount: number
  itemQuantity: number
  avgOrderValue: number
  avgItemsPerOrder: number
}

/** bucketKey 일 yyyy-MM-dd·주 yyyy-'W'ww·월 yyyy-MM, bucketLabel은 주만 주 시작일(월요일). */
export interface SalesTrendBucket {
  bucketKey: string
  bucketLabel: string
  revenue: number
  refund: number
  netRevenue: number
  orderCount: number
}

export interface SalesStatsResponse {
  summary: SalesSummary
  compareSummary?: SalesSummary | null
  trend: SalesTrendBucket[]
  /** trend와 같은 길이(BE가 뒤를 0으로 채우거나 절단·D-181 §1-A 4). */
  compareTrend?: SalesTrendBucket[] | null
}
