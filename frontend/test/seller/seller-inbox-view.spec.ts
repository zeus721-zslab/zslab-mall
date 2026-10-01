import { describe, it, expect } from 'vitest'
import { inboxQuestionAnswerItem, sellerInboxDeadlineChipClass } from '#layers/seller/app/lib/seller-inbox-view'
import { resolveActiveSellerMenuPath } from '#layers/seller/app/lib/constants/seller-menu'
import { normalizeInboxItem } from '~/lib/inbox-view'

// 셀러 인박스 레이어 규칙(FE-101): 기한 칩 색 · Q&A 답변 다이얼로그 입력 변환 · 메뉴 활성 판정.
describe('셀러 인박스', () => {
  it('기한 칩: 셀러 칩 클래스(slr-chip)', () => {
    expect(sellerInboxDeadlineChipClass('overdue')).toBe('slr-chip slr-chip--danger')
    expect(sellerInboxDeadlineChipClass('none')).toBe('slr-chip slr-chip--neutral')
  })

  it('Q&A 행 → 답변 다이얼로그 입력(질문 본문·상품명·작성 시각) · 다른 유형은 null', () => {
    const row = normalizeInboxItem({
      type: 'QUESTION_UNANSWERED', ref: 'pqn_1', title: '사이즈 문의', subtitle: '반찬통', baseAt: '2026-10-01T09:00:00+09:00', overdue: true, targetKey: 'PRODUCT_QUESTION',
    })
    expect(inboxQuestionAnswerItem(row)).toEqual({ questionId: 'pqn_1', content: '사이즈 문의', productName: '반찬통', createdAt: '2026-10-01T09:00:00+09:00' })
    expect(inboxQuestionAnswerItem({ ...row, subtitle: null })).toEqual({ questionId: 'pqn_1', content: '사이즈 문의', createdAt: '2026-10-01T09:00:00+09:00' })
    expect(inboxQuestionAnswerItem({ ...row, type: 'LOW_STOCK' })).toBeNull()
  })

  it('메뉴 활성: /seller/inbox 정확 일치', () => {
    expect(resolveActiveSellerMenuPath('/seller/inbox')).toBe('/seller/inbox')
  })
})
