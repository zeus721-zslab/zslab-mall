import { describe, it, expect } from 'vitest'
import { inboxQuestionAnswerItem, sellerInboxDeadlineChipClass } from '#layers/seller/app/lib/seller-inbox-view'
import { resolveActiveSellerMenuPath } from '#layers/seller/app/lib/constants/seller-menu'
import { normalizeInboxItem } from '~/lib/inbox-view'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import SellerInboxList from '#layers/seller/app/components/seller/SellerInboxList.vue'

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

// UX-03 명도 대비: 보조 문구는 투명도 대신 색(클래스) · 선택 칩만 #0F766E(테마 primary 미변경 · FE-112).
describe('셀러 인박스 목록 대비(UX-03)', () => {
  const row = normalizeInboxItem({ type: 'LOW_STOCK', ref: 'prd_1', title: '반찬통', subtitle: '재고 2', overdue: false, targetKey: 'PRODUCT' })
  const props = {
    items: [row], counts: [{ type: 'LOW_STOCK' as const, count: 1 }], tab: 'TODAY' as const, type: null, selectedKey: null,
    loading: false, error: null, truncated: false, nowMs: 0,
  }

  it('보조 문구에 slr-inbox-subtitle 클래스 · 선택 칩(전체)만 #0F766E 배경, 미선택 칩은 색 지정 없음', async () => {
    const wrapper = await mountSuspended(SellerInboxList, { props, global: { plugins: [createVuetify()] } })
    expect(wrapper.get('[data-testid="inbox-item"] .v-list-item-subtitle').classes()).toContain('slr-inbox-subtitle')
    const selectedStyle = wrapper.get('[data-testid="inbox-type-chip-ALL"]').attributes('style') ?? ''
    expect(selectedStyle).toMatch(/rgb\(15, 118, 110\)|#0F766E/i)
    expect(wrapper.get('[data-testid="inbox-type-chip-LOW_STOCK"]').attributes('style') ?? '').not.toMatch(/rgb\(15, 118, 110\)|#0F766E/i)

    await wrapper.setProps({ type: 'LOW_STOCK' })
    expect(wrapper.get('[data-testid="inbox-type-chip-LOW_STOCK"]').attributes('style') ?? '').toMatch(/rgb\(15, 118, 110\)|#0F766E/i)
  })
})
