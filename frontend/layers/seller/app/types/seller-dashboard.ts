import type { ClaimStatus, ClaimType, OrderItemStatusCode } from '~/lib/constants/claim'

/**
 * 셀러 대시보드 API 타입(Track 90-B-3·D-192 `GET /api/v1/seller/dashboard?from=&to=` 응답 1:1·backend dashboard/controller/response/SellerDashboard* 실측).
 * 금액은 원 단위 정수·품목(order_item) 축(order.total_price 아님). 시각(paidAt·requestedAt)은 KST 오프셋 ISO(KstOffsetSerializer) → formatDateTime.
 * period.from/to는 LocalDate "yyyy-MM-dd"(양끝 포함). dailyTrend는 기간 내 매일 1행(빈 날 0은 BE가 채움). nullable(optionLabel·productPublicId)은
 * BE NON_NULL 직렬화로 생략될 수 있어 optional.
 */

export interface SellerDashboardPeriod {
  from: string
  to: string
}

/** 기간 요약. orderCount = 자기 품목이 포함된 주문 수(COUNT DISTINCT order) — /seller/order-items totalCount(품목 행 수)와 다르다(D-192). */
export interface SellerDashboardSummary {
  revenue: number
  refund: number
  netRevenue: number
  orderCount: number
}

/** 처리 대기 4종(기간 무관). settlementPending은 건수(금액 아님·D-191 ε). */
export interface SellerDashboardPending {
  deliveryReady: number
  claimRequested: number
  lowStock: number
  settlementPending: number
}

/** 일별 추이 1행. orderCount는 DISTINCT 주문 수·revenue는 자기 품목 합. 차트 2종이 이 배열에서 파생된다. */
export interface SellerDashboardDailyTrend {
  date: string
  orderCount: number
  revenue: number
}

/** 최근 결제 자기 품목(축약 DTO·상세 링크는 /seller/orders/{orderItemId}). */
export interface SellerDashboardRecentOrderItem {
  orderItemId: string
  orderNo: string
  productName: string
  optionLabel?: string
  quantity: number
  totalPrice: number
  itemStatus: OrderItemStatusCode
  paidAt: string
}

/** 최근 자기 품목 클레임(구매자 정보 없음). 클레임 상세 화면은 90-D — 링크하지 않는다. */
export interface SellerDashboardRecentClaim {
  claimPublicId: string
  type: ClaimType
  status: ClaimStatus
  orderNo: string
  requestedAt?: string
}

export interface SellerDashboardTopProduct {
  productPublicId?: string
  productName: string
  revenue: number
  quantity: number
}

export interface SellerDashboardResponse {
  period: SellerDashboardPeriod
  summary: SellerDashboardSummary
  pending: SellerDashboardPending
  dailyTrend: SellerDashboardDailyTrend[]
  recentOrderItems: SellerDashboardRecentOrderItem[]
  recentClaims: SellerDashboardRecentClaim[]
  topProducts: SellerDashboardTopProduct[]
}
