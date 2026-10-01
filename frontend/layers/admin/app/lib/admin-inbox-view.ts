import type { AdminInquiryListItem } from '#layers/admin/app/types/admin-inquiry'
import type { AdminClaimBulkApproveResponse } from '#layers/admin/app/types/admin-claim'
import type { AdminSemantic } from '#layers/admin/app/lib/constants/semantic'
import type { InboxDeadlineTone, InboxItem } from '~/lib/inbox-view'
import { isInquiryCategory } from '~/lib/constants/inquiry'
import { CLAIM_TYPE_LABELS, type ClaimSuggestion, type ClaimType } from '~/lib/constants/claim'
import { IRREVERSIBLE, riskConfirmMessage } from '~/lib/utils/risk-confirm'

/**
 * 관리자 인박스 표시 규칙(D-248 · 공용 규칙은 app/lib/inbox-view). 레이어 CSS 클래스와 관리자 다이얼로그 입력 변환, 클레임 일괄 승인(D-250) 규칙을 둔다.
 */

const DEADLINE_CHIP_SUFFIX: Record<InboxDeadlineTone, string> = {
  overdue: 'danger',
  imminent: 'warning',
  normal: 'info',
  none: 'neutral',
}

export function adminInboxDeadlineChipClass(tone: InboxDeadlineTone): string {
  return `adm-chip adm-chip--${DEADLINE_CHIP_SUFFIX[tone]}`
}

/**
 * 1:1 문의 항목 → 답변 다이얼로그 입력(AdminInquiryListItem 필수 필드). 인박스 행은 title = 문의 본문 · subtitle = 카테고리 코드 · baseAt = 작성 시각
 * (BE InquiryUnansweredInboxSource)이다. 카테고리가 알 수 없는 값이면 null(답변 버튼을 숨긴다).
 */
export function inboxInquiryAnswerItem(item: InboxItem): AdminInquiryListItem | null {
  if (item.type !== 'INQUIRY_UNANSWERED' || !isInquiryCategory(item.subtitle)) return null
  return { inquiryId: item.sourceRef, category: item.subtitle, content: item.title, createdAt: item.baseAt ?? '' }
}

// ---------- 클레임 처리 제안 · 일괄 승인(D-250) ----------

/** 일괄 승인 최대 건수(BE AdminClaimBulkApproveRequest.MAX_ITEMS와 같은 값). */
export const INBOX_BULK_APPROVE_MAX = 20

const SUGGESTION_CHIP_SUFFIX: Record<ClaimSuggestion, string> = {
  APPROVE: 'success',
  REVIEW: 'warning',
}

export function claimSuggestionChipClass(suggestion: ClaimSuggestion): string {
  return `adm-chip adm-chip--${SUGGESTION_CHIP_SUFFIX[suggestion]}`
}

/** 일괄 승인 대상: 클레임 접수 행 중 승인 제안만(검토 필요는 상세 패널에서 하나씩 판단한다). */
export function isBulkApprovable(item: InboxItem): boolean {
  return item.type === 'CLAIM_REQUESTED' && item.suggestion === 'APPROVE'
}

/** 일괄 승인 확인 문구 — 유형별 건수 · 취소가 있으면 즉시 환불 경고 · 마지막 줄은 가역성(되돌릴 수 없음). */
export function bulkApproveConfirmMessage(items: InboxItem[]): string {
  const countByType = new Map<ClaimType, number>()
  for (const item of items) {
    if (item.claimType !== null) countByType.set(item.claimType, (countByType.get(item.claimType) ?? 0) + 1)
  }
  const breakdown = (Object.keys(CLAIM_TYPE_LABELS) as ClaimType[])
    .filter((type) => countByType.has(type))
    .map((type) => `${CLAIM_TYPE_LABELS[type]} ${countByType.get(type)}건`)
    .join(' · ')
  const lines = [`선택한 클레임 ${items.length}건을 승인합니다.`, breakdown]
  if (countByType.has('CANCEL')) lines.push('취소는 승인 즉시 환불이 진행됩니다.')
  lines.push('승인 직전에 제안을 다시 확인해, 그사이 승인 제안이 아니게 된 건은 승인하지 않습니다.')
  return riskConfirmMessage(lines.join('\n'), IRREVERSIBLE)
}

/** 일괄 승인 결과 토스트: 전부 실패 danger · 일부 실패 warning · 전부 성공 info(단건 승인 토스트와 같은 중립). */
export function summarizeClaimBulkApprove(response: AdminClaimBulkApproveResponse): { message: string; semantic: AdminSemantic; hasFailure: boolean } {
  const hasFailure = response.failureCount > 0
  const semantic: AdminSemantic = hasFailure ? (response.successCount === 0 ? 'danger' : 'warning') : 'info'
  return { message: `일괄 승인 — 성공 ${response.successCount} / 실패 ${response.failureCount}`, semantic, hasFailure }
}

const CLAIM_BULK_FAILURE_MESSAGES: Record<string, string> = {
  CLAIM_SUGGESTION_MISMATCH: '지금은 승인 제안이 아니어서 승인하지 않았습니다. 상세에서 확인해 주세요.',
  CLAIM_STATE_INVALID: '이미 처리됐거나 승인할 수 없는 상태입니다.',
  INVENTORY_INVARIANT_VIOLATION: '교환 옵션 재고가 부족해 승인하지 못했습니다.',
  OPTIMISTIC_LOCK_FAILURE: '다른 처리와 겹쳤습니다. 다시 시도해 주세요.',
  CLAIM_NOT_FOUND: '클레임을 찾을 수 없습니다.',
}

/** 일괄 승인 실패 항목 문구(서버 message는 내부 식별자가 섞여 있어 코드 문구를 쓴다 · 모르는 코드만 서버 message). */
export function claimBulkFailureMessage(code: string | undefined, message: string | undefined): string {
  if (code && CLAIM_BULK_FAILURE_MESSAGES[code]) return CLAIM_BULK_FAILURE_MESSAGES[code]
  return message && message !== '' ? message : '처리하지 못했습니다.'
}
