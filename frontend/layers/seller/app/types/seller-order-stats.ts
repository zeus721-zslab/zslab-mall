import type { ClaimReasonShare, ClaimSummary, ClaimTrendBucket, ClaimTypeShare, LeadTimeMetric } from '~/types/stats'

/**
 * 셀러 주문·클레임 통계 API 타입(Track 90-E-2·D-200 `GET /api/v1/seller/stats/orders` 응답 1:1·backend stats/controller/response/SellerOrderStats* 실측).
 * 관리자 주문클레임 통계를 내 품목 단위로 좁힌 것 — 퍼널 3단계(결제→출고→배송완료)·소요시간 2종·클레임 요약/추이/유형/사유 + 셀러 전용 상품별 분해.
 * BE는 전역 NON_NULL 직렬화라 compareClaimSummary·compareClaimTrend·leadTime 각 구간(표본 0)은 키 생략 → optional.
 */

/** 결제 코호트 퍼널 건수(도달률·이탈률은 FE 계산). */
export interface SellerOrderFunnel {
  paidItems: number
  shippedItems: number
  deliveredItems: number
}

/** 각 구간은 표본 0이면 생략(null). */
export interface SellerOrderLeadTime {
  paidToShipped?: LeadTimeMetric | null
  shippedToDelivered?: LeadTimeMetric | null
}

/** 클레임 상품별 분해(기간 내 요청 기준·건수 내림차순). productKey는 상품 public_id(soft-delete면 생략)·productName은 주문 시점 스냅샷. */
export interface SellerClaimProductShare {
  productKey?: string | null
  productName: string
  count: number
  share: number
}

export interface SellerOrderStatsResponse {
  funnel: SellerOrderFunnel
  leadTime: SellerOrderLeadTime
  claimSummary: ClaimSummary
  compareClaimSummary?: ClaimSummary | null
  claimTrend: ClaimTrendBucket[]
  /** claimTrend와 같은 길이(BE가 뒤를 0으로 채우거나 절단). */
  compareClaimTrend?: ClaimTrendBucket[] | null
  claimByType: ClaimTypeShare[]
  claimByReason: ClaimReasonShare[]
  claimByProduct: SellerClaimProductShare[]
}
