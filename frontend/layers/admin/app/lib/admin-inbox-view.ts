import type { AdminInquiryListItem } from '#layers/admin/app/types/admin-inquiry'
import type { AdminClaimBulkApproveResponse } from '#layers/admin/app/types/admin-claim'
import type { AdminSellerNudgeResponse } from '#layers/admin/app/types/admin-seller-delay'
import type { AdminSemantic } from '#layers/admin/app/lib/constants/semantic'
import type { InboxDeadlineTone, InboxItem } from '~/lib/inbox-view'
import { inquiryCategoryLabel, isInquiryCategory } from '~/lib/constants/inquiry'
import { CLAIM_TYPE_LABELS, type ClaimSuggestion, type ClaimType } from '~/lib/constants/claim'
import { IRREVERSIBLE, riskConfirmMessage } from '~/lib/utils/risk-confirm'

/**
 * 관리자 인박스 표시 규칙(D-248 · 공용 규칙은 app/lib/inbox-view). 레이어 CSS 클래스와 관리자 다이얼로그 입력 변환, 클레임 일괄 승인(D-250)·셀러 지연
 * 독촉(D-252) 규칙을 둔다.
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

/** 행 부제 표시: 1:1 문의는 카테고리 코드를 한글 라벨로(warn W17 · 모르는 코드는 원문), 그 외 유형은 부제 그대로. */
export function adminInboxSubtitle(item: InboxItem): string | null {
  if (item.type === 'INQUIRY_UNANSWERED' && item.subtitle !== null) return inquiryCategoryLabel(item.subtitle)
  return item.subtitle
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
  LOCK_CONFLICT: '다른 처리와 겹쳤습니다. 잠시 후 다시 시도해 주세요.',
  CLAIM_NOT_FOUND: '클레임을 찾을 수 없습니다.',
}

/** 일괄 승인 실패 항목 문구(서버 message는 내부 식별자가 섞여 있어 코드 문구를 쓴다 · 모르는 코드만 서버 message). */
export function claimBulkFailureMessage(code: string | undefined, message: string | undefined): string {
  if (code && CLAIM_BULK_FAILURE_MESSAGES[code]) return CLAIM_BULK_FAILURE_MESSAGES[code]
  return message && message !== '' ? message : '처리하지 못했습니다.'
}

// ---------- 일괄 선택 종류(클레임 승인 D-250 · 셀러 독촉 D-252) ----------

/** 한 번에 한 종류만 고른다 — 다른 종류 행을 체크하면 기존 선택을 비우고 그 행부터 다시 고른다. */
export type InboxBulkKind = 'CLAIM_APPROVE' | 'SELLER_NUDGE'

/** 셀러 독촉 최대 셀러 수(BE SellerDelayNudgeRequest.MAX_SELLERS와 같은 값). */
export const INBOX_BULK_NUDGE_MAX = 20

export const INBOX_BULK_MAX: Record<InboxBulkKind, number> = {
  CLAIM_APPROVE: INBOX_BULK_APPROVE_MAX,
  SELLER_NUDGE: INBOX_BULK_NUDGE_MAX,
}

/** 행의 일괄 처리 종류(체크 상자를 그리지 않는 행은 null). */
export function inboxBulkKind(item: InboxItem): InboxBulkKind | null {
  if (isBulkApprovable(item)) return 'CLAIM_APPROVE'
  if (item.type === 'SELLER_DELAY') return 'SELLER_NUDGE'
  return null
}

/** 지금 선택의 종류(선택이 비었거나 목록에서 사라졌으면 null). */
export function selectedBulkKind(items: InboxItem[], selected: string[]): InboxBulkKind | null {
  const first = items.find((item) => selected.includes(item.key))
  return first ? inboxBulkKind(first) : null
}

/** 체크 상자 토글 후 선택. 다른 종류를 체크하면 그 행 하나만 남긴다(체크 해제는 그 행만 뺀다). */
export function toggleBulkSelection(items: InboxItem[], selected: string[], key: string, checked: boolean): string[] {
  const rest = selected.filter((selectedKey) => selectedKey !== key)
  if (!checked) return rest
  const target = items.find((item) => item.key === key)
  const kind = target ? inboxBulkKind(target) : null
  if (kind === null) return rest
  return selectedBulkKind(items, rest) === kind || rest.length === 0 ? [...rest, key] : [key]
}

// ---------- 셀러 지연 독촉(D-252) ----------

/** 독촉 확인 문구 — 대상 셀러 수(1곳이면 상호) · 24시간 쿨다운 · 마지막 줄은 가역성(SMS는 되돌릴 수 없음). */
export function sellerNudgeConfirmMessage(companyNames: string[]): string {
  const target = companyNames.length === 1 ? `${companyNames[0]}에` : `선택한 셀러 ${companyNames.length}곳에`
  return riskConfirmMessage(
    `${target} 처리 지연 독촉 SMS를 보냅니다.\n기한이 지난 발송 대기·상품 Q&A 미답변 건수가 문자에 들어갑니다.\n24시간 안에 이미 독촉한 셀러에게는 보내지 않습니다.`,
    IRREVERSIBLE,
  )
}

/** 독촉 결과 토스트: 발송 없음 + 실패 있음 danger · 발송 외 결과가 있으면 warning · 전부 발송 info. */
export function summarizeSellerNudge(response: AdminSellerNudgeResponse): { message: string; semantic: AdminSemantic; hasIssue: boolean } {
  const parts = [`발송 ${response.sentCount}`]
  if (response.failedCount > 0) parts.push(`실패 ${response.failedCount}`)
  if (response.noRecipientCount > 0) parts.push(`연락처 없음 ${response.noRecipientCount}`)
  if (response.cooldownCount > 0) parts.push(`24시간 내 독촉함 ${response.cooldownCount}`)
  if (response.noDelayCount > 0) parts.push(`지연 없음 ${response.noDelayCount}`)
  const hasIssue = response.sentCount < response.results.length
  const semantic: AdminSemantic = !hasIssue ? 'info' : response.sentCount === 0 && response.failedCount > 0 ? 'danger' : 'warning'
  return { message: `셀러 독촉 — ${parts.join(' / ')}`, semantic, hasIssue }
}
