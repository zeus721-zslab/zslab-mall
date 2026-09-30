import { describe, it, expect } from 'vitest'
import {
  DEFAULT_ADMIN_INQUIRY_QUERY,
  hasActiveInquiryFilters,
  inquiryExcerpt,
  parseAdminInquiryQuery,
  toAdminInquiryApiParams,
  toAdminInquiryRouteQuery,
} from '#layers/admin/app/lib/admin-inquiry-query'
import { ADMIN_MENU, resolveActiveMenuPath } from '#layers/admin/app/lib/constants/admin-menu'

/** Track 106-4 관리자 문의 관리: URL query ↔ 상태 ↔ BE 파라미터(기본 미답변) · 발췌 · 메뉴 위치(고객센터 > FAQ 관리 다음). */

describe('admin-inquiry-query', () => {
  it('빈 query = 미답변·전체 카테고리 · 모르는 값은 기본값으로', () => {
    expect(parseAdminInquiryQuery({})).toEqual(DEFAULT_ADMIN_INQUIRY_QUERY)
    expect(parseAdminInquiryQuery({ answered: 'nope', category: 'REVIEW_QUESTION', page: '-1', size: '7' })).toEqual(DEFAULT_ADMIN_INQUIRY_QUERY)
    expect(parseAdminInquiryQuery({ answered: 'ALL', category: 'CLAIM', page: '2', size: '50' }))
      .toEqual({ answered: 'ALL', category: 'CLAIM', page: 2, size: 50 })
  })

  it('기본값은 URL에서 빼고, BE 파라미터에는 answered를 항상 싣는다', () => {
    expect(toAdminInquiryRouteQuery(DEFAULT_ADMIN_INQUIRY_QUERY)).toEqual({})
    expect(toAdminInquiryRouteQuery({ answered: 'ANSWERED', category: 'OTHER', page: 1, size: 10 }))
      .toEqual({ answered: 'ANSWERED', category: 'OTHER', page: '1', size: '10' })
    expect(toAdminInquiryApiParams(DEFAULT_ADMIN_INQUIRY_QUERY)).toEqual({ answered: 'UNANSWERED', page: 0, size: 20 })
    expect(toAdminInquiryApiParams({ ...DEFAULT_ADMIN_INQUIRY_QUERY, category: 'CLAIM' }))
      .toEqual({ answered: 'UNANSWERED', category: 'CLAIM', page: 0, size: 20 })
  })

  it('필터 활성 판정 · 발췌(줄바꿈 공백 · 60자 말줄임)', () => {
    expect(hasActiveInquiryFilters(DEFAULT_ADMIN_INQUIRY_QUERY)).toBe(false)
    expect(hasActiveInquiryFilters({ ...DEFAULT_ADMIN_INQUIRY_QUERY, answered: 'ALL' })).toBe(true)
    expect(hasActiveInquiryFilters({ ...DEFAULT_ADMIN_INQUIRY_QUERY, category: 'OTHER' })).toBe(true)
    expect(inquiryExcerpt('첫 줄\n둘째 줄')).toBe('첫 줄 둘째 줄')
    expect(inquiryExcerpt('가'.repeat(61))).toBe(`${'가'.repeat(60)}…`)
  })
})

describe('관리자 메뉴', () => {
  it('고객센터 = FAQ 관리 → 문의 관리 · 문의 관리 경로가 활성 메뉴로 잡힌다', () => {
    expect(ADMIN_MENU.find((group) => group.label === '고객센터')?.children).toEqual([
      { to: '/admin/faqs', label: 'FAQ 관리' },
      { to: '/admin/inquiries', label: '문의 관리' },
    ])
    expect(resolveActiveMenuPath('/admin/inquiries')).toBe('/admin/inquiries')
  })
})
