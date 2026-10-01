import type { SellerNudgeResult } from '~/lib/constants/inbox'

/**
 * 셀러 지연 패널·독촉 API 타입(D-252 · BE SellerDelayResponse·SellerDelayNudgeResponse 1:1). NON_NULL 직렬화라 독촉 이력이 없으면
 * lastNudgedAt이 빠진다(optional).
 */
export interface AdminSellerDelay {
  sellerPublicId: string
  companyName: string
  deliveryReadyOverdueCount: number
  questionUnansweredOverdueCount: number
  /** 마지막 독촉 발송(SENT) 시각(+09:00 ISO). */
  lastNudgedAt?: string
}

export interface AdminSellerNudgeItem {
  sellerPublicId: string
  result: SellerNudgeResult
}

/** 일괄 독촉 응답(셀러별 결과와 무관하게 200 · 입력 순서). */
export interface AdminSellerNudgeResponse {
  results: AdminSellerNudgeItem[]
  sentCount: number
  failedCount: number
  noRecipientCount: number
  cooldownCount: number
  noDelayCount: number
}
