import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import AppHeader from '~/components/AppHeader.vue'

// AppHeader가 실제 소비하는 최소 인터페이스만 mock한다(store 전체 흉내 금지).
// auth = { isAuthenticated, logout } / cart = { count, clear } / route.meta.middleware.
// mockNuxtImport는 hoisting되므로 홀더는 vi.hoisted로 먼저 만든다.
const { authMock, cartMock, navigateToMock, routeMetaMock } = vi.hoisted(() => ({
  authMock: { isAuthenticated: false, logout: vi.fn() },
  cartMock: { count: 0, clear: vi.fn() },
  navigateToMock: vi.fn(),
  // it별로 middleware를 갈아끼우기 위한 가변 meta 홀더.
  routeMetaMock: { middleware: undefined as unknown },
}))

mockNuxtImport('useAuthStore', () => () => authMock)
mockNuxtImport('useCartStore', () => () => cartMock)
mockNuxtImport('navigateTo', () => navigateToMock)
mockNuxtImport('useRoute', () => () => ({ meta: routeMetaMock }))

const TRIGGER = '[data-testid="account-menu-trigger"]'
const LOGOUT_ITEM = '[data-testid="account-menu-logout"]'

/** 드롭다운 항목 순서·경로(AppHeader.accountMenuItems + 로그아웃). 회원 탈퇴는 의도적으로 없다. */
const EXPECTED_LINKS: { href: string; label: string }[] = [
  { href: '/mypage', label: '마이페이지' },
  { href: '/orders', label: '주문내역' },
  { href: '/mypage/profile', label: '회원정보 수정' },
  { href: '/mypage/password', label: '비밀번호 변경' },
  { href: '/mypage/addresses', label: '배송지 관리' },
  { href: '/claims', label: '취소·반품·교환 내역' },
]

/**
 * reka-ui@2.10 DropdownMenuTrigger는 click(button 0·ctrlKey false)으로 토글되고, 콘텐츠는 Portal로 document.body에 렌더된다.
 * 따라서 열기는 click 이벤트, 조회는 wrapper가 아닌 document 기준으로 한다.
 */
async function openAccountMenu(wrapper: VueWrapper): Promise<void> {
  await wrapper.find(TRIGGER).trigger('click')
  await flushPromises()
}

function menuContent(): HTMLElement | null {
  return document.querySelector('[data-slot="dropdown-menu-content"]')
}

describe('AppHeader', () => {
  beforeEach(() => {
    authMock.isAuthenticated = false
    authMock.logout.mockReset()
    cartMock.count = 0
    cartMock.clear.mockReset()
    navigateToMock.mockReset()
    routeMetaMock.middleware = undefined
    // Portal 잔존물이 it 간 누적되지 않도록 body를 비운다.
    document.body.innerHTML = ''
  })

  it('미인증 → 로그인 링크가 있고 계정 트리거가 없다', async () => {
    authMock.isAuthenticated = false
    const wrapper = await mountSuspended(AppHeader)
    expect(wrapper.find('a[href="/login"]').exists()).toBe(true)
    expect(wrapper.find(TRIGGER).exists()).toBe(false)
    expect(wrapper.text()).not.toContain('로그아웃')
  })

  it('인증 → "내 계정" 트리거가 있고 로그인 링크가 없다', async () => {
    authMock.isAuthenticated = true
    const wrapper = await mountSuspended(AppHeader)
    const trigger = wrapper.find(TRIGGER)
    expect(trigger.exists()).toBe(true)
    expect(trigger.text()).toBe('내 계정')
    expect(wrapper.find('a[href="/login"]').exists()).toBe(false)
  })

  it('cart.count=0 → 뱃지 없음 / count>0 → 뱃지에 숫자 표시', async () => {
    cartMock.count = 0
    const empty = await mountSuspended(AppHeader)
    // 장바구니 링크는 항상 존재하나 count 0이면 뱃지 숫자가 렌더되지 않는다.
    const cartLinkEmpty = empty.find('a[href="/cart"]')
    expect(cartLinkEmpty.text()).not.toContain('3')

    cartMock.count = 3
    const filled = await mountSuspended(AppHeader)
    const cartLinkFilled = filled.find('a[href="/cart"]')
    expect(cartLinkFilled.text()).toContain('3')
  })

  it('트리거 열기 → 링크 6개(순서·경로) + 로그아웃 항목, 회원 탈퇴 없음', async () => {
    authMock.isAuthenticated = true
    const wrapper = await mountSuspended(AppHeader)
    await openAccountMenu(wrapper)

    const content = menuContent()
    expect(content).not.toBeNull()
    const links = Array.from(content!.querySelectorAll('a')).map((anchor) => ({
      href: anchor.getAttribute('href'),
      label: anchor.textContent?.trim(),
    }))
    expect(links).toEqual(EXPECTED_LINKS)
    expect(content!.querySelector(LOGOUT_ITEM)?.textContent?.trim()).toBe('로그아웃')
    expect(content!.textContent).not.toContain('회원 탈퇴')
    expect(content!.querySelector('a[href="/mypage/withdraw"]')).toBeNull()
  })

  it('BUYER 보호 페이지에서 로그아웃 → logout·clear·navigateTo(/) 호출', async () => {
    authMock.isAuthenticated = true
    routeMetaMock.middleware = 'buyer'
    const wrapper = await mountSuspended(AppHeader)
    await openAccountMenu(wrapper)
    menuContent()!.querySelector<HTMLElement>(LOGOUT_ITEM)!.click()
    await nextTick()
    expect(authMock.logout).toHaveBeenCalledTimes(1)
    expect(cartMock.clear).toHaveBeenCalledTimes(1)
    expect(navigateToMock).toHaveBeenCalledWith('/')
  })

  it('공개 페이지에서 로그아웃 → logout·clear 호출·navigateTo 미호출', async () => {
    authMock.isAuthenticated = true
    routeMetaMock.middleware = undefined
    const wrapper = await mountSuspended(AppHeader)
    await openAccountMenu(wrapper)
    menuContent()!.querySelector<HTMLElement>(LOGOUT_ITEM)!.click()
    await nextTick()
    expect(authMock.logout).toHaveBeenCalledTimes(1)
    expect(cartMock.clear).toHaveBeenCalledTimes(1)
    expect(navigateToMock).not.toHaveBeenCalled()
  })
})
