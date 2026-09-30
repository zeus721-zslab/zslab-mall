import { describe, it, expect } from 'vitest'
import { faqCategoryMoved, faqsInCategory } from '#layers/admin/app/lib/admin-faq-view'
import { ADMIN_MENU, resolveActiveMenuPath } from '#layers/admin/app/lib/constants/admin-menu'
import type { AdminFaqItem } from '#layers/admin/app/types/admin-faq'

/** Track 106-3 관리자 FAQ: 카테고리 안 노출 순서(정렬 요청 본문의 id 순서) · 카테고리 이동 안내 판정 · 메뉴 위치. */

function row(overrides: Partial<AdminFaqItem>): AdminFaqItem {
  return {
    id: 1,
    category: 'DELIVERY',
    question: '질문',
    answer: '답',
    sortOrder: 0,
    visible: true,
    updatedAt: '2026-09-30T12:00:00+09:00',
    ...overrides,
  }
}

describe('faqsInCategory', () => {
  it('해당 카테고리만(숨김 포함) sortOrder → id 순', () => {
    const items = [
      row({ id: 5, sortOrder: 1 }),
      row({ id: 3, category: 'CLAIM', sortOrder: 0 }),
      row({ id: 7, sortOrder: 0, visible: false }),
      row({ id: 4, sortOrder: 1 }),
    ]
    expect(faqsInCategory(items, 'DELIVERY').map((item) => item.id)).toEqual([7, 4, 5])
    expect(faqsInCategory(items, 'ACCOUNT')).toEqual([])
  })
})

describe('faqCategoryMoved', () => {
  it('수정 중 카테고리를 바꿨을 때만 true(등록은 false)', () => {
    expect(faqCategoryMoved(row({ category: 'DELIVERY' }), 'CLAIM')).toBe(true)
    expect(faqCategoryMoved(row({ category: 'DELIVERY' }), 'DELIVERY')).toBe(false)
    expect(faqCategoryMoved(null, 'CLAIM')).toBe(false)
  })
})

describe('관리자 메뉴', () => {
  it('고객센터 그룹의 FAQ 관리 · 활성 메뉴 경로', () => {
    const group = ADMIN_MENU.find((menu) => menu.label === '고객센터')
    expect(group?.children).toEqual([{ to: '/admin/faqs', label: 'FAQ 관리' }])
    expect(resolveActiveMenuPath('/admin/faqs')).toBe('/admin/faqs')
  })
})
