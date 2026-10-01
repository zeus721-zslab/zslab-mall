import { describe, it, expect } from 'vitest'
import {
  INBOX_BULK_APPROVE_MAX,
  adminInboxDeadlineChipClass,
  bulkApproveConfirmMessage,
  claimBulkFailureMessage,
  claimSuggestionChipClass,
  inboxInquiryAnswerItem,
  isBulkApprovable,
  summarizeClaimBulkApprove,
} from '#layers/admin/app/lib/admin-inbox-view'
import { ADMIN_INBOX_PATH, ADMIN_MENU, resolveActiveMenuPath } from '#layers/admin/app/lib/constants/admin-menu'
import { type InboxItem, normalizeInboxItem } from '~/lib/inbox-view'
import type { ClaimSuggestion, ClaimType } from '~/lib/constants/claim'

function claimRow(ref: string, claimType: ClaimType, suggestion: ClaimSuggestion): InboxItem {
  return normalizeInboxItem({ type: 'CLAIM_REQUESTED', ref, title: '상품', subtitle: 'ORD1', overdue: false, targetKey: 'CLAIM', claimType, suggestion })
}

// 관리자 인박스 레이어 규칙(FE-101): 기한 칩 색 · 문의 답변 다이얼로그 입력 변환 · 메뉴 최상단 배지.
describe('관리자 인박스', () => {
  it('기한 칩: 경과 빨강 · 임박 노랑 · 여유 파랑 · 없음 회색', () => {
    expect(adminInboxDeadlineChipClass('overdue')).toBe('adm-chip adm-chip--danger')
    expect(adminInboxDeadlineChipClass('imminent')).toBe('adm-chip adm-chip--warning')
    expect(adminInboxDeadlineChipClass('normal')).toBe('adm-chip adm-chip--info')
    expect(adminInboxDeadlineChipClass('none')).toBe('adm-chip adm-chip--neutral')
  })

  it('1:1 문의 행 → 답변 다이얼로그 입력(본문·카테고리·작성 시각) · 알 수 없는 카테고리·다른 유형은 null', () => {
    const row = normalizeInboxItem({
      type: 'INQUIRY_UNANSWERED', ref: 'inq_1', title: '배송이 늦어요', subtitle: 'DELIVERY', baseAt: '2026-10-01T09:00:00+09:00', overdue: false, targetKey: 'INQUIRY',
    })
    expect(inboxInquiryAnswerItem(row)).toEqual({ inquiryId: 'inq_1', category: 'DELIVERY', content: '배송이 늦어요', createdAt: '2026-10-01T09:00:00+09:00' })
    expect(inboxInquiryAnswerItem({ ...row, subtitle: 'WHATEVER' })).toBeNull()
    expect(inboxInquiryAnswerItem({ ...row, type: 'SELLER_REVIEW' })).toBeNull()
  })

  it('클레임 제안 칩: 승인 제안 초록 · 검토 필요 노랑 · 일괄 승인 대상은 클레임 접수의 승인 제안만', () => {
    expect(claimSuggestionChipClass('APPROVE')).toBe('adm-chip adm-chip--success')
    expect(claimSuggestionChipClass('REVIEW')).toBe('adm-chip adm-chip--warning')
    expect(isBulkApprovable(claimRow('clm_1', 'CANCEL', 'APPROVE'))).toBe(true)
    expect(isBulkApprovable(claimRow('clm_2', 'RETURN', 'REVIEW'))).toBe(false)
    expect(isBulkApprovable(normalizeInboxItem({ type: 'CLAIM_FOLLOWUP', ref: 'clm_3:REFUND', overdue: false, targetKey: 'CLAIM' }))).toBe(false)
    expect(INBOX_BULK_APPROVE_MAX).toBe(20)
  })

  it('일괄 승인 확인 문구: 유형별 건수 · 취소가 있을 때만 즉시 환불 경고 · 서버 재확인 안내 · 마지막 줄 가역성', () => {
    const withCancel = bulkApproveConfirmMessage([
      claimRow('clm_1', 'RETURN', 'APPROVE'), claimRow('clm_2', 'CANCEL', 'APPROVE'), claimRow('clm_3', 'CANCEL', 'APPROVE'),
    ])
    expect(withCancel.split('\n')).toEqual([
      '선택한 클레임 3건을 승인합니다.',
      '취소 2건 · 반품 1건',
      '취소는 승인 즉시 환불이 진행됩니다.',
      '승인 직전에 제안을 다시 확인해, 그사이 승인 제안이 아니게 된 건은 승인하지 않습니다.',
      '되돌릴 수 없습니다.',
    ])
    expect(bulkApproveConfirmMessage([claimRow('clm_4', 'EXCHANGE', 'APPROVE')])).not.toContain('즉시 환불')
  })

  it('일괄 승인 결과: 전부 성공 info · 일부 실패 warning · 전부 실패 danger · 실패 문구는 코드 우선', () => {
    const result = (successCount: number, failureCount: number) => ({ results: [], successCount, failureCount })
    expect(summarizeClaimBulkApprove(result(3, 0))).toEqual({ message: '일괄 승인 — 성공 3 / 실패 0', semantic: 'info', hasFailure: false })
    expect(summarizeClaimBulkApprove(result(2, 1)).semantic).toBe('warning')
    expect(summarizeClaimBulkApprove(result(0, 2)).semantic).toBe('danger')
    expect(claimBulkFailureMessage('CLAIM_SUGGESTION_MISMATCH', '승인 제안이 아닙니다: 증빙 없음')).toBe('지금은 승인 제안이 아니어서 승인하지 않았습니다. 상세에서 확인해 주세요.')
    expect(claimBulkFailureMessage('CLAIM_STATE_INVALID', '이미 처리된 클레임입니다: status=APPROVED')).toBe('이미 처리됐거나 승인할 수 없는 상태입니다.')
    expect(claimBulkFailureMessage('UNKNOWN', '서버 문구')).toBe('서버 문구')
    expect(claimBulkFailureMessage(undefined, undefined)).toBe('처리하지 못했습니다.')
  })

  it('메뉴: 인박스가 맨 위 · 오늘 건수 배지 · 활성 판정 · 고객센터 그룹 유지', () => {
    expect(ADMIN_MENU[0]).toEqual({ label: '인박스', to: ADMIN_INBOX_PATH, badge: 'INBOX_TODAY' })
    expect(ADMIN_MENU[1]?.to).toBe('/admin')
    expect(resolveActiveMenuPath('/admin/inbox')).toBe('/admin/inbox')
    expect(ADMIN_MENU.some((group) => group.label === '고객센터')).toBe(true)
  })
})
