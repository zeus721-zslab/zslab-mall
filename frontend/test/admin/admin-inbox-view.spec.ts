import { describe, it, expect } from 'vitest'
import {
  INBOX_BULK_APPROVE_MAX,
  INBOX_BULK_MAX,
  INBOX_BULK_NUDGE_MAX,
  adminInboxDeadlineChipClass,
  adminInboxSubtitle,
  bulkApproveConfirmMessage,
  claimBulkFailureMessage,
  claimSuggestionChipClass,
  inboxBulkKind,
  inboxInquiryAnswerItem,
  isBulkApprovable,
  selectedBulkKind,
  sellerNudgeConfirmMessage,
  summarizeClaimBulkApprove,
  summarizeSellerNudge,
  toggleBulkSelection,
} from '#layers/admin/app/lib/admin-inbox-view'
import { ADMIN_INBOX_PATH, ADMIN_MENU, resolveActiveMenuPath } from '#layers/admin/app/lib/constants/admin-menu'
import { type InboxItem, normalizeInboxItem } from '~/lib/inbox-view'
import type { ClaimSuggestion, ClaimType } from '~/lib/constants/claim'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import AdminInboxList from '#layers/admin/app/components/admin/AdminInboxList.vue'

function claimRow(ref: string, claimType: ClaimType, suggestion: ClaimSuggestion): InboxItem {
  return normalizeInboxItem({ type: 'CLAIM_REQUESTED', ref, title: '상품', subtitle: 'ORD1', overdue: false, targetKey: 'CLAIM', claimType, suggestion })
}

function sellerDelayRow(ref: string): InboxItem {
  return normalizeInboxItem({ type: 'SELLER_DELAY', ref, title: '셀러', subtitle: '발송 대기 1건', overdue: true, targetKey: 'SELLER' })
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

  it('warn W17: 행 부제 — 1:1 문의 카테고리 코드는 한글 라벨 · 모르는 코드는 원문 · 다른 유형은 부제 그대로', () => {
    const row = normalizeInboxItem({ type: 'INQUIRY_UNANSWERED', ref: 'inq_1', title: '배송이 늦어요', subtitle: 'DELIVERY', overdue: false, targetKey: 'INQUIRY' })
    expect(adminInboxSubtitle(row)).toBe('배송')
    expect(adminInboxSubtitle({ ...row, subtitle: 'WHATEVER' })).toBe('WHATEVER')
    expect(adminInboxSubtitle(claimRow('clm_1', 'RETURN', 'APPROVE'))).toBe('ORD1')
    expect(adminInboxSubtitle(sellerDelayRow('slr_1'))).toBe('발송 대기 1건')
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
    expect(claimBulkFailureMessage('LOCK_CONFLICT', 'could not execute statement')).toBe('다른 처리와 겹쳤습니다. 잠시 후 다시 시도해 주세요.')
    expect(claimBulkFailureMessage('UNKNOWN', '서버 문구')).toBe('서버 문구')
    expect(claimBulkFailureMessage(undefined, undefined)).toBe('처리하지 못했습니다.')
  })

  it('일괄 선택 종류(D-252): 승인 제안 클레임 · 셀러 지연만 체크 가능 · 같은 종류는 쌓고 다른 종류를 체크하면 그 행만 남는다 · 해제는 그 행만', () => {
    const items = [claimRow('clm_1', 'CANCEL', 'APPROVE'), claimRow('clm_2', 'RETURN', 'APPROVE'), sellerDelayRow('slr_1'), sellerDelayRow('slr_2'),
      claimRow('clm_3', 'RETURN', 'REVIEW')]
    const [claim1, claim2, seller1, seller2, review] = items.map((item) => item.key)
    expect(items.map(inboxBulkKind)).toEqual(['CLAIM_APPROVE', 'CLAIM_APPROVE', 'SELLER_NUDGE', 'SELLER_NUDGE', null])
    expect(INBOX_BULK_MAX).toEqual({ CLAIM_APPROVE: 20, SELLER_NUDGE: INBOX_BULK_NUDGE_MAX })
    expect(INBOX_BULK_NUDGE_MAX).toBe(20)

    const twoClaims = toggleBulkSelection(items, [claim1!], claim2!, true)
    expect(twoClaims).toEqual([claim1, claim2])
    expect(selectedBulkKind(items, twoClaims)).toBe('CLAIM_APPROVE')
    const switched = toggleBulkSelection(items, twoClaims, seller1!, true)
    expect(switched).toEqual([seller1])
    expect(toggleBulkSelection(items, switched, seller2!, true)).toEqual([seller1, seller2])
    expect(toggleBulkSelection(items, [seller1!, seller2!], seller1!, false)).toEqual([seller2])
    expect(toggleBulkSelection(items, [seller1!], review!, true)).toEqual([seller1])
    expect(selectedBulkKind(items, [])).toBeNull()
  })

  it('독촉 확인 문구: 1곳은 상호 · 여러 곳은 수 · 쿨다운 안내 · 마지막 줄 가역성(되돌릴 수 없음)', () => {
    expect(sellerNudgeConfirmMessage(['지연상회']).split('\n')).toEqual([
      '지연상회에 처리 지연 독촉 SMS를 보냅니다.',
      '기한이 지난 발송 대기·상품 Q&A 미답변 건수가 문자에 들어갑니다.',
      '24시간 안에 이미 독촉한 셀러에게는 보내지 않습니다.',
      '되돌릴 수 없습니다.',
    ])
    expect(sellerNudgeConfirmMessage(['A', 'B', 'C']).split('\n')[0]).toBe('선택한 셀러 3곳에 처리 지연 독촉 SMS를 보냅니다.')
  })

  it('독촉 결과 토스트: 전부 발송 info · 발송 외 결과 warning · 발송 0 + 실패 danger · 0건 결과는 문구에서 뺀다', () => {
    const response = (sent: number, failed: number, noRecipient: number, cooldown: number, noDelay: number) => ({
      results: Array.from({ length: sent + failed + noRecipient + cooldown + noDelay }, (_, index) => ({ sellerPublicId: `slr_${index}`, result: 'SENT' as const })),
      sentCount: sent, failedCount: failed, noRecipientCount: noRecipient, cooldownCount: cooldown, noDelayCount: noDelay,
    })
    expect(summarizeSellerNudge(response(2, 0, 0, 0, 0))).toEqual({ message: '셀러 독촉 — 발송 2', semantic: 'info', hasIssue: false })
    expect(summarizeSellerNudge(response(1, 0, 1, 1, 1))).toEqual({
      message: '셀러 독촉 — 발송 1 / 연락처 없음 1 / 24시간 내 독촉함 1 / 지연 없음 1', semantic: 'warning', hasIssue: true,
    })
    expect(summarizeSellerNudge(response(0, 2, 0, 0, 0)).semantic).toBe('danger')
    expect(summarizeSellerNudge(response(0, 0, 0, 2, 0)).semantic).toBe('warning')
  })

  it('메뉴: 인박스가 맨 위 · 오늘 건수 배지 · 활성 판정 · 고객센터 그룹 유지', () => {
    expect(ADMIN_MENU[0]).toEqual({ label: '인박스', to: ADMIN_INBOX_PATH, badge: 'INBOX_TODAY' })
    expect(ADMIN_MENU[1]?.to).toBe('/admin')
    expect(resolveActiveMenuPath('/admin/inbox')).toBe('/admin/inbox')
    expect(ADMIN_MENU.some((group) => group.label === '고객센터')).toBe(true)
  })
})

// UX-03 명도 대비: 보조 문구(제안 배지 포함)는 투명도 대신 색(클래스)으로 약하게 한다.
describe('관리자 인박스 목록 대비(UX-03)', () => {
  it('보조 문구에 adm-inbox-subtitle 클래스 · 제안 배지는 그 안에 남는다', async () => {
    const wrapper = await mountSuspended(AdminInboxList, {
      props: {
        items: [claimRow('clm_1', 'RETURN', 'APPROVE')], counts: [{ type: 'CLAIM_REQUESTED' as const, count: 1 }], tab: 'TODAY' as const, type: null,
        selectedKey: null, bulkSelected: [], loading: false, error: null, truncated: false, nowMs: 0,
      },
      global: { plugins: [createVuetify()] },
    })
    const subtitle = wrapper.get('[data-testid="inbox-item"] .v-list-item-subtitle')
    expect(subtitle.classes()).toContain('adm-inbox-subtitle')
    expect(subtitle.find('[data-testid="inbox-item-suggestion"]').exists()).toBe(true)
  })
})
