import type { ClaimType } from '~/lib/constants/claim'

/**
 * 관리자 주문·클레임 통계 API 타입(FE-35·D-182 `GET /api/v1/admin/stats/orders` 응답 1:1·backend stats/controller/response 실측).
 * BE는 전역 NON_NULL 직렬화라 compareClaimSummary·compareClaimTrend·leadTime 각 구간(표본 0)은 값이 null이면 <b>키 자체가 생략</b>된다
 * → optional로 선언하고 lib/admin-order-stats-view.ts `normalizeOrderStats`가 `?? null`로 정규화한다.
 * 비율은 % 소수 2자리, 소요시간은 시간 단위 소수 2자리, 금액은 원 단위 정수.
 */

/** 결제 코호트 퍼널 건수(도달률·이탈률은 FE 계산). */
export interface AdminOrderFunnel {
  paidItems: number
  shippedItems: number
  deliveredItems: number
  confirmedItems: number
  cancelledItems: number
  returnedItems: number
}

export interface AdminLeadTimeMetric {
  avgHours: number
  medianHours: number
  count: number
}

/** 각 구간은 표본 0이면 생략(null). */
export interface AdminOrderLeadTime {
  paidToShipped?: AdminLeadTimeMetric | null
  shippedToDelivered?: AdminLeadTimeMetric | null
  claimRequestedToClosed?: AdminLeadTimeMetric | null
}

export interface AdminClaimSummary {
  claimCount: number
  claimRate: number
  refundAmount: number
  refundRate: number
  refundCount: number
  paidItemCount: number
}

/** bucketKey·bucketLabel 규약은 매출 추이와 같다(주는 주 시작일 라벨). */
export interface AdminClaimTrendBucket {
  bucketKey: string
  bucketLabel: string
  claimCount: number
  claimRate: number
  refundAmount: number
  refundRate: number
  refundCount: number
}

export interface AdminClaimTypeShare {
  type: ClaimType
  count: number
  share: number
}

/** reasonCode는 DB 무제약 VARCHAR라 enum 외 값도 원문으로 온다(FE 라벨 매핑·미매핑 원문 표기). */
export interface AdminClaimReasonShare {
  reasonCode: string
  count: number
  share: number
}

export interface AdminOrderStatsResponse {
  funnel: AdminOrderFunnel
  leadTime: AdminOrderLeadTime
  claimSummary: AdminClaimSummary
  compareClaimSummary?: AdminClaimSummary | null
  claimTrend: AdminClaimTrendBucket[]
  /** claimTrend와 같은 길이(BE가 뒤를 0으로 채우거나 절단). */
  compareClaimTrend?: AdminClaimTrendBucket[] | null
  claimByType: AdminClaimTypeShare[]
  claimByReason: AdminClaimReasonShare[]
}
