import { describe, it, expect } from 'vitest'
import {
  FAQ_CATEGORIES,
  faqCategoryLabel,
  faqFirstCategories,
  isFaqAssistantExcluded,
  orderedFaqCategories,
} from '~/lib/constants/faq'

/** Track 106-3 채팅 도우미: 카테고리 상수(BE FaqCategory 선언 순서) · 페이지별 첫 칩 매핑 · 제외 경로. */

describe('FAQ 카테고리 상수(4층위 (4)프론트)', () => {
  it('BE FaqCategory 5종과 같은 값·선언 순서 · 라벨 · 모르는 값은 code 그대로', () => {
    expect(FAQ_CATEGORIES).toEqual(['ORDER_PAYMENT', 'DELIVERY', 'CLAIM', 'ACCOUNT', 'REVIEW_QUESTION'])
    expect(FAQ_CATEGORIES.map(faqCategoryLabel)).toEqual(['주문·결제', '배송', '취소·반품·교환', '회원·계정', '리뷰·문의'])
    expect(faqCategoryLabel('UNKNOWN')).toBe('UNKNOWN')
  })
})

describe('faqFirstCategories', () => {
  it.each([
    ['/products/prd_01KX', ['DELIVERY', 'CLAIM']],
    ['/cart', ['ORDER_PAYMENT']],
    ['/checkout', ['ORDER_PAYMENT']],
    ['/checkout/complete', ['ORDER_PAYMENT']],
    ['/orders', ['DELIVERY', 'CLAIM']],
    ['/orders/ord_01KX', ['DELIVERY', 'CLAIM']],
    ['/claims/new', ['CLAIM']],
    ['/claims/clm_01KX', ['CLAIM']],
    ['/login', ['ACCOUNT']],
    ['/signup', ['ACCOUNT']],
    ['/mypage', ['ACCOUNT']],
    ['/mypage/profile', ['ACCOUNT']],
    ['/mypage/addresses', ['ACCOUNT']],
    ['/mypage/password', ['ACCOUNT']],
    ['/mypage/withdraw', ['ACCOUNT']],
    ['/mypage/questions', ['REVIEW_QUESTION']],
    ['/orders/', ['DELIVERY', 'CLAIM']],
  ])('%s → %j', (path, expected) => {
    expect(faqFirstCategories(path)).toEqual(expected)
  })

  it.each(['/', '/products', '/categories/3', '/search', '/help', '/없는-경로'])('%s → 첫 칩 없음', (path) => {
    expect(faqFirstCategories(path)).toEqual([])
  })
})

describe('isFaqAssistantExcluded', () => {
  it.each(['/payment/mock', '/reviews/new', '/reviews/rvw_01KX/edit'])('%s → 제외', (path) => {
    expect(isFaqAssistantExcluded(path)).toBe(true)
  })

  it.each(['/', '/payment', '/reviews', '/products/prd_01KX', '/checkout', '/mypage/questions'])('%s → 노출', (path) => {
    expect(isFaqAssistantExcluded(path)).toBe(false)
  })
})

describe('orderedFaqCategories', () => {
  it('첫 칩 카테고리를 앞에 두고 나머지는 선언 순서', () => {
    expect(orderedFaqCategories(['DELIVERY', 'CLAIM'])).toEqual(['DELIVERY', 'CLAIM', 'ORDER_PAYMENT', 'ACCOUNT', 'REVIEW_QUESTION'])
    expect(orderedFaqCategories([])).toEqual(FAQ_CATEGORIES)
  })
})
