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

// 소요시간·클레임 요약·추이·분포 타입은 셀러 주문클레임 통계와 같은 BE record라 app/types/stats.ts로 이동(FE-52 90-E-2)·기존 이름으로 re-export
export type {
  LeadTimeMetric as AdminLeadTimeMetric,
  ClaimSummary as AdminClaimSummary,
  ClaimTrendBucket as AdminClaimTrendBucket,
  ClaimTypeShare as AdminClaimTypeShare,
  ClaimReasonShare as AdminClaimReasonShare,
} from '~/types/stats'
import type { LeadTimeMetric as AdminLeadTimeMetric, ClaimSummary as AdminClaimSummary, ClaimTrendBucket as AdminClaimTrendBucket, ClaimTypeShare as AdminClaimTypeShare, ClaimReasonShare as AdminClaimReasonShare } from '~/types/stats'

/** 각 구간은 표본 0이면 생략(null). */
export interface AdminOrderLeadTime {
  paidToShipped?: AdminLeadTimeMetric | null
  shippedToDelivered?: AdminLeadTimeMetric | null
  claimRequestedToClosed?: AdminLeadTimeMetric | null
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
