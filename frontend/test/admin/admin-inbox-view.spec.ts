import { describe, it, expect } from 'vitest'
import { adminInboxDeadlineChipClass, inboxInquiryAnswerItem } from '#layers/admin/app/lib/admin-inbox-view'
import { ADMIN_INBOX_PATH, ADMIN_MENU, resolveActiveMenuPath } from '#layers/admin/app/lib/constants/admin-menu'
import { normalizeInboxItem } from '~/lib/inbox-view'

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

  it('메뉴: 인박스가 맨 위 · 오늘 건수 배지 · 활성 판정 · 고객센터 그룹 유지', () => {
    expect(ADMIN_MENU[0]).toEqual({ label: '인박스', to: ADMIN_INBOX_PATH, badge: 'INBOX_TODAY' })
    expect(ADMIN_MENU[1]?.to).toBe('/admin')
    expect(resolveActiveMenuPath('/admin/inbox')).toBe('/admin/inbox')
    expect(ADMIN_MENU.some((group) => group.label === '고객센터')).toBe(true)
  })
})
