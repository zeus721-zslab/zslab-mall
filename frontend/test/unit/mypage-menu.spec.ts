import { describe, it, expect } from 'vitest'
import { ACCOUNT_MENU_ITEMS, MYPAGE_HUB_MENU_ITEMS, MYPAGE_MENU_ITEMS } from '~/lib/constants/mypage-menu'

/**
 * Track 105-2d-FE1(FE-72) 마이페이지 메뉴 정의 단일 소스. renew 사이드 메뉴 순서와 헤더·classic 허브 파생 규칙만 고정한다.
 */
describe('MYPAGE_MENU_ITEMS — 사이드 메뉴 순서', () => {
  it('홈 · 주문 내역 · 회원 정보 · 배송지 관리 · 비밀번호 변경 · 회원 탈퇴 순서다', () => {
    expect(MYPAGE_MENU_ITEMS.map(({ to, label }) => ({ to, label }))).toEqual([
      { to: '/mypage', label: '홈' },
      { to: '/orders', label: '주문 내역' },
      { to: '/mypage/profile', label: '회원 정보' },
      { to: '/mypage/addresses', label: '배송지 관리' },
      { to: '/mypage/password', label: '비밀번호 변경' },
      { to: '/mypage/withdraw', label: '회원 탈퇴' },
    ])
  })

  it('취소·반품·교환 항목은 없다(주문 내역 탭으로 통합·FE-63)', () => {
    expect(MYPAGE_MENU_ITEMS.some((item) => item.label.includes('취소'))).toBe(false)
  })
})

describe('ACCOUNT_MENU_ITEMS — 헤더 계정 메뉴 파생', () => {
  it('회원 탈퇴를 빼고 같은 순서를 따르며, 홈은 "마이페이지"로 표시한다', () => {
    expect(ACCOUNT_MENU_ITEMS).toEqual([
      { to: '/mypage', label: '마이페이지' },
      { to: '/orders', label: '주문 내역' },
      { to: '/mypage/profile', label: '회원 정보' },
      { to: '/mypage/addresses', label: '배송지 관리' },
      { to: '/mypage/password', label: '비밀번호 변경' },
    ])
    expect(ACCOUNT_MENU_ITEMS.some((item) => item.to === '/mypage/withdraw')).toBe(false)
  })
})

describe('MYPAGE_HUB_MENU_ITEMS — classic 허브 파생', () => {
  it('허브 자신(홈)만 빼고 설명을 포함한다', () => {
    expect(MYPAGE_HUB_MENU_ITEMS.map((item) => item.to)).toEqual([
      '/orders',
      '/mypage/profile',
      '/mypage/addresses',
      '/mypage/password',
      '/mypage/withdraw',
    ])
    expect(MYPAGE_HUB_MENU_ITEMS.every((item) => item.description.length > 0)).toBe(true)
  })
})
