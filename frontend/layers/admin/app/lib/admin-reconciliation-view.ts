import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type { AdminReconciliationDetail, AdminReconciliationIssue, AdminReconciliationListQuery } from '#layers/admin/app/types/admin-reconciliation'
import {
  DEFAULT_RECONCILIATION_PAGE_SIZE,
  RECONCILIATION_ISSUE_STATUS_LABEL,
  RECONCILIATION_ISSUE_TYPE_LABEL,
  RECONCILIATION_PAGE_SIZES,
  RECONCILIATION_REASON_LABEL,
  type ReconciliationIssueStatus,
  type ReconciliationIssueType,
} from '#layers/admin/app/lib/constants/reconciliation'
import { ADMIN_DELIVERY_STATUS_LABEL, ADMIN_PAYMENT_STATUS_LABEL } from '#layers/admin/app/lib/constants/admin-order'
import { orderStatusLabel } from '~/lib/constants/order'
import { claimStatusLabel, claimTypeLabel, orderItemStatusLabel } from '~/lib/constants/claim'
import { formatWon } from '#layers/admin/app/lib/format'
import { IRREVERSIBLE, riskConfirmMessage } from '~/lib/utils/risk-confirm'

/**
 * 관리자 불일치 화면 표시 규칙·URL 매핑 순수 함수(Track 104-2 FE-66·admin-delivery-query 패턴). 목록 화면과 주문 상세 불일치 섹션이 같은
 * 함수로 유형 라벨·요약·세부 사실을 만든다.
 */

// ---------- 목록 조회 조건 ↔ URL ----------

/** 기본 조회 = 확인 필요(OPEN)만. 대시보드 칸도 status=OPEN으로 들어온다. */
export const DEFAULT_RECONCILIATION_QUERY: AdminReconciliationListQuery = {
  status: 'OPEN',
  type: null,
  page: 0,
  size: DEFAULT_RECONCILIATION_PAGE_SIZE,
}

/** URL에서 상태 "전체"를 나타내는 값(기본값 OPEN과 구분하려고 생략 대신 명시한다). */
export const RECONCILIATION_STATUS_ALL = 'ALL'

function first(value: LocationQuery[string] | undefined): string | null {
  const single = Array.isArray(value) ? value[0] : value
  return typeof single === 'string' && single !== '' ? single : null
}

function isStatus(value: string): value is ReconciliationIssueStatus {
  return value in RECONCILIATION_ISSUE_STATUS_LABEL
}

function isIssueType(value: string): value is ReconciliationIssueType {
  return value in RECONCILIATION_ISSUE_TYPE_LABEL
}

/** route.query → 화면 상태. 잘못된 값은 기본값으로 정규화한다. */
export function parseReconciliationQuery(query: LocationQuery): AdminReconciliationListQuery {
  const status = first(query.status)
  const type = first(query.type)
  const page = Number(first(query.page))
  const size = Number(first(query.size))
  return {
    status: status === RECONCILIATION_STATUS_ALL ? null : status && isStatus(status) ? status : DEFAULT_RECONCILIATION_QUERY.status,
    type: type && isIssueType(type) ? type : null,
    page: Number.isInteger(page) && page > 0 ? page : 0,
    size: RECONCILIATION_PAGE_SIZES.includes(size) ? size : DEFAULT_RECONCILIATION_PAGE_SIZE,
  }
}

/** 화면 상태 → router.replace용 query(기본값 항목 생략·상태 전체는 ALL로 명시). */
export function toReconciliationRouteQuery(state: AdminReconciliationListQuery): LocationQueryRaw {
  const query: LocationQueryRaw = {}
  if (state.status !== DEFAULT_RECONCILIATION_QUERY.status) query.status = state.status ?? RECONCILIATION_STATUS_ALL
  if (state.type) query.type = state.type
  if (state.page > 0) query.page = String(state.page)
  if (state.size !== DEFAULT_RECONCILIATION_PAGE_SIZE) query.size = String(state.size)
  return query
}

/** 화면 상태 → BE GET /admin/reconciliation-issues 파라미터(null 제외). */
export function toReconciliationApiParams(state: AdminReconciliationListQuery): Record<string, string | number> {
  const params: Record<string, string | number> = { page: state.page, size: state.size }
  if (state.status) params.status = state.status
  if (state.type) params.type = state.type
  return params
}

// ---------- 표시 ----------

export function reconciliationTypeLabel(issueType: ReconciliationIssueType): string {
  return RECONCILIATION_ISSUE_TYPE_LABEL[issueType]
}

/** 한 줄 요약 = 세부 사유 라벨(모르는 사유는 원문·사유 없으면 유형 라벨). */
export function reconciliationSummary(issue: Pick<AdminReconciliationIssue, 'issueType' | 'detail'>): string {
  const reason = issue.detail.reason
  if (typeof reason !== 'string' || reason === '') return reconciliationTypeLabel(issue.issueType)
  return RECONCILIATION_REASON_LABEL[reason] ?? reason
}

function labelOf(labels: Record<string, string>, code: string): string {
  return labels[code] ?? code
}

function textOf(value: string | number | boolean | undefined): string | null {
  if (value === undefined || value === '') return null
  return String(value)
}

/** 세부 키 → 화면 표기(라벨 · 값 변환). 표에 없는 키(attemptKey 원문 등 조사용 값 일부)는 아래 순서로만 보인다. */
const FACT_FORMATTERS: { key: string; label: string; format: (value: string) => string }[] = [
  { key: 'paymentStatus', label: '결제', format: (value) => labelOf(ADMIN_PAYMENT_STATUS_LABEL, value) },
  { key: 'orderStatus', label: '주문', format: orderStatusLabel },
  { key: 'itemStatus', label: '품목', format: orderItemStatusLabel },
  { key: 'claimType', label: '클레임 유형', format: claimTypeLabel },
  { key: 'claimStatus', label: '클레임', format: claimStatusLabel },
  { key: 'deliveryStatus', label: '배송', format: (value) => labelOf(ADMIN_DELIVERY_STATUS_LABEL, value) },
  { key: 'paymentAmount', label: '결제액', format: (value) => formatWon(Number(value)) },
  { key: 'refundAmount', label: '환불액', format: (value) => formatWon(Number(value)) },
  { key: 'refundedAmount', label: '환불 완료 합계', format: (value) => formatWon(Number(value)) },
  { key: 'completedTotalAfter', label: '완료 시 환불 합계', format: (value) => formatWon(Number(value)) },
  { key: 'callbackType', label: '통지', format: (value) => value },
  { key: 'callbackStatus', label: '통지', format: (value) => value },
  { key: 'attemptKey', label: '결제 시도', format: (value) => value },
]

/** 세부에서 운영자가 확인할 사실을 "라벨 값" 목록으로 뽑는다(값이 있는 키만·정해진 순서). */
export function reconciliationFacts(detail: AdminReconciliationDetail): string[] {
  return FACT_FORMATTERS.flatMap(({ key, label, format }) => {
    const value = textOf(detail[key])
    return value === null ? [] : [`${label} ${format(value)}`]
  })
}

/** 누가 기록했는지(점검 스케줄러 = detectedBy SYSTEM · 그 외는 PG 통지·환불 완료 처리 중 기록). */
export function reconciliationSourceLabel(detail: AdminReconciliationDetail): string {
  return detail.detectedBy === 'SYSTEM' ? '정기 점검' : '처리 중 기록'
}

/** 해결 확인 문구(FE-64 형식 — 가역성 줄이 마지막). 해결은 표시만 바꾸고 다시 열 수 없다. */
export function resolveConfirmMessage(issue: Pick<AdminReconciliationIssue, 'issueType'>): string {
  return riskConfirmMessage(
    `"${reconciliationTypeLabel(issue.issueType)}" 불일치를 해결됨으로 표시합니다. 결제·주문·환불 데이터는 바뀌지 않으니 보정은 먼저 해당 화면에서 하세요`,
    IRREVERSIBLE,
  )
}
