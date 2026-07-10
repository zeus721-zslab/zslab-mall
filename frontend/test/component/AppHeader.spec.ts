import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
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

describe('AppHeader', () => {
  beforeEach(() => {
    authMock.isAuthenticated = false
    authMock.logout.mockReset()
    cartMock.count = 0
    cartMock.clear.mockReset()
    navigateToMock.mockReset()
    routeMetaMock.middleware = undefined
  })

  it('미인증 → 로그인 링크가 있고 로그아웃 버튼이 없다', async () => {
    authMock.isAuthenticated = false
    const wrapper = await mountSuspended(AppHeader)
    const loginLink = wrapper.find('a[href="/login"]')
    expect(loginLink.exists()).toBe(true)
    expect(wrapper.text()).not.toContain('로그아웃')
  })

  it('인증 → 로그아웃 버튼이 있다', async () => {
    authMock.isAuthenticated = true
    const wrapper = await mountSuspended(AppHeader)
    expect(wrapper.text()).toContain('로그아웃')
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

  it('BUYER 보호 페이지에서 로그아웃 → logout·clear·navigateTo(/) 호출', async () => {
    authMock.isAuthenticated = true
    routeMetaMock.middleware = 'buyer'
    const wrapper = await mountSuspended(AppHeader)
    await wrapper.find('button').trigger('click')
    expect(authMock.logout).toHaveBeenCalledTimes(1)
    expect(cartMock.clear).toHaveBeenCalledTimes(1)
    expect(navigateToMock).toHaveBeenCalledWith('/')
  })

  it('공개 페이지에서 로그아웃 → logout·clear 호출·navigateTo 미호출', async () => {
    authMock.isAuthenticated = true
    routeMetaMock.middleware = undefined
    const wrapper = await mountSuspended(AppHeader)
    await wrapper.find('button').trigger('click')
    expect(authMock.logout).toHaveBeenCalledTimes(1)
    expect(cartMock.clear).toHaveBeenCalledTimes(1)
    expect(navigateToMock).not.toHaveBeenCalled()
  })
})
