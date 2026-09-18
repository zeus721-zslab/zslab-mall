import type { OrderStatusCode } from '~/lib/constants/order'
import type { ClaimStatus, ClaimType } from '~/lib/constants/claim'

/**
 * 관리자 대시보드 API 타입(FE-33·D-180 `GET /api/v1/admin/dashboard` 응답 1:1·backend dashboard/controller/response 실측).
 * 금액은 원 단위 정수, 시각(paidAt·requestedAt)은 KST 오프셋 ISO → formatDateTime. nullable(buyerName·sellerPublicId 등)은 BE NON_NULL
 * 직렬화로 생략될 수 있어 optional. monthlyRevenue는 6개·dailyOrders는 30개 고정(빈 구간 0은 BE가 채움).
 */

/** 기간 지표 1묶음(DashboardPeriodMetrics). netRevenue = revenue − refund. */
export interface AdminDashboardPeriodMetrics {
  revenue: number
  refund: number
  netRevenue: number
  orderCount: number
  newMemberCount: number
}

/** 오늘·이번 달과 비교값(전일 전체·전월 전체). 증감률은 FE가 계산한다. */
export interface AdminDashboardSummary {
  today: AdminDashboardPeriodMetrics
  previousDay: AdminDashboardPeriodMetrics
  thisMonth: AdminDashboardPeriodMetrics
  previousMonth: AdminDashboardPeriodMetrics
}

export interface AdminDashboardPending {
  settlementPending: number
  claimRequested: number
  deliveryReady: number
  lowStock: number
}

/** yearMonth "yyyy-MM". */
export interface AdminDashboardMonthlyRevenue {
  yearMonth: string
  revenue: number
  refund: number
  netRevenue: number
  orderCount: number
}

/** date "yyyy-MM-dd". */
export interface AdminDashboardDailyOrders {
  date: string
  orderCount: number
  revenue: number
}

export interface AdminDashboardRecentOrder {
  orderPublicId: string
  orderNo: string
  buyerName?: string
  totalPrice: number
  paidAt: string
  status: OrderStatusCode
}

export interface AdminDashboardRecentClaim {
  claimPublicId: string
  type: ClaimType
  status: ClaimStatus
  orderNo: string
  requestedAt?: string
}

export interface AdminDashboardTopSeller {
  sellerPublicId?: string
  sellerName?: string
  revenue: number
  orderItemCount: number
}

export interface AdminDashboardTopProduct {
  productPublicId?: string
  productName: string
  revenue: number
  quantity: number
}

export interface AdminDashboardResponse {
  summary: AdminDashboardSummary
  pending: AdminDashboardPending
  monthlyRevenue: AdminDashboardMonthlyRevenue[]
  dailyOrders: AdminDashboardDailyOrders[]
  recentOrders: AdminDashboardRecentOrder[]
  recentClaims: AdminDashboardRecentClaim[]
  topSellers: AdminDashboardTopSeller[]
  topProducts: AdminDashboardTopProduct[]
}
