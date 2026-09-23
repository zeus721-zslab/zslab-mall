import type { ReconciliationIssueStatus, ReconciliationIssueType } from '#layers/admin/app/lib/constants/reconciliation'

/**
 * 관리자 불일치 API 타입(Track 104-2 D-216·FE-66·BE AdminReconciliationIssueResponse 1:1). null 값은 BE NON_NULL 직렬화로 키가 빠져
 * optional이다(매칭 주문 없는 PG 통지는 orderId·orderNo 없음·미해결은 resolved* 없음).
 */

/** 기록 시점 세부(reason·통지 원문 값·조회 시점 상태). 키는 유형별로 다르다. */
export type AdminReconciliationDetail = Record<string, string | number | boolean>

export interface AdminReconciliationIssue {
  issueId: number
  issueType: ReconciliationIssueType
  status: ReconciliationIssueStatus
  /** 주문 public id(ord_). */
  orderId?: string
  orderNo?: string
  pgTid?: string
  pgRefundId?: string
  detail: AdminReconciliationDetail
  detectedAt: string
  resolvedAt?: string
  resolvedByName?: string
  /** 시스템 자동 해소(매칭 없던 PG 통지가 재전송으로 매칭됨·처리자 없음). */
  resolvedBySystem: boolean
  resolutionMemo?: string
}

export interface AdminReconciliationIssuePage {
  items: AdminReconciliationIssue[]
  page: number
  size: number
  totalCount: number
  hasNext: boolean
}

/** 목록 조회 조건(URL query 단일 소스). null = 전체. */
export interface AdminReconciliationListQuery {
  status: ReconciliationIssueStatus | null
  type: ReconciliationIssueType | null
  page: number
  size: number
}

export interface AdminReconciliationResolveBody {
  memo: string
}
