import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { ref } from 'vue'
import AppHeader from '~/components/AppHeader.vue'
import type { CategorySummary } from '~/types/category'

// AppHeader가 실제 소비하는 최소 인터페이스만 mock한다(store 전체 흉내 금지).
// auth = { isAuthenticated, logout } / cart = { count, clear } / route.meta.middleware·route.query.keyword /
// useCategories = { data, error }(FE-20 카테고리 드롭다운).
// mockNuxtImport는 hoisting되므로 홀더는 vi.hoisted로 먼저 만든다.
const { authMock, cartMock, navigateToMock, routeMock, useCategoriesMock } = vi.hoisted(() => ({
  authMock: { isAuthenticated: false, logout: vi.fn() },
  cartMock: { count: 0, clear: vi.fn() },
  navigateToMock: vi.fn(),
  // it별로 middleware·query를 갈아끼우기 위한 가변 route 홀더.
  routeMock: { meta: { middleware: undefined as unknown }, query: {} as Record<string, string> },
  useCategoriesMock: vi.fn(),
}))

mockNuxtImport('useAuthStore', () => () => authMock)
mockNuxtImport('useCartStore', () => () => cartMock)
mockNuxtImport('navigateTo', () => navigateToMock)
mockNuxtImport('useRoute', () => () => routeMock)
mockNuxtImport('useCategories', () => useCategoriesMock)

const TRIGGER = '[data-testid="account-menu-trigger"]'
const LOGOUT_ITEM = '[data-testid="account-menu-logout"]'
const ACCOUNT_CONTENT = '[data-testid="account-menu-content"]'
const CATEGORY_TRIGGER = '[data-testid="category-menu-trigger"]'
const CATEGORY_CONTENT = '[data-testid="category-menu-content"]'
const SEARCH_FORM = '[data-testid="search-form"]'
const SEARCH_INPUT = '[data-testid="search-input"]'

function categoriesState(overrides: { data?: CategorySummary[] | null; error?: unknown }) {
  return { data: ref(overrides.data ?? null), pending: ref(false), error: ref(overrides.error ?? null), refresh: vi.fn() }
}

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

// 계정·카테고리 두 드롭다운이 공존하므로 data-slot이 아닌 각 content의 data-testid로 조회한다(FE-20).
function menuContent(): HTMLElement | null {
  return document.querySelector(ACCOUNT_CONTENT)
}

async function openCategoryMenu(wrapper: VueWrapper): Promise<HTMLElement | null> {
  await wrapper.find(CATEGORY_TRIGGER).trigger('click')
  await flushPromises()
  return document.querySelector(CATEGORY_CONTENT)
}

describe('AppHeader', () => {
  beforeEach(() => {
    authMock.isAuthenticated = false
    authMock.logout.mockReset()
    cartMock.count = 0
    cartMock.clear.mockReset()
    navigateToMock.mockReset()
    routeMock.meta.middleware = undefined
    routeMock.query = {}
    useCategoriesMock.mockReset()
    useCategoriesMock.mockReturnValue(categoriesState({ data: [{ categoryId: 1, displayName: '데모', sortOrder: 0 }] }))
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
    routeMock.meta.middleware = 'buyer'
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
    routeMock.meta.middleware = undefined
    const wrapper = await mountSuspended(AppHeader)
    await openAccountMenu(wrapper)
    menuContent()!.querySelector<HTMLElement>(LOGOUT_ITEM)!.click()
    await nextTick()
    expect(authMock.logout).toHaveBeenCalledTimes(1)
    expect(cartMock.clear).toHaveBeenCalledTimes(1)
    expect(navigateToMock).not.toHaveBeenCalled()
  })

  // ==================== FE-20 검색 submit ====================

  it('검색 submit → trim 값으로 /search?keyword= 이동', async () => {
    const wrapper = await mountSuspended(AppHeader)
    await wrapper.find(SEARCH_INPUT).setValue('  베이직  ')
    await wrapper.find(SEARCH_FORM).trigger('submit')
    expect(navigateToMock).toHaveBeenCalledWith({ path: '/search', query: { keyword: '베이직' } })
  })

  it('빈 값·공백 검색 submit → 이동 없음', async () => {
    const wrapper = await mountSuspended(AppHeader)
    await wrapper.find(SEARCH_FORM).trigger('submit')
    await wrapper.find(SEARCH_INPUT).setValue('   ')
    await wrapper.find(SEARCH_FORM).trigger('submit')
    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('/search?keyword= 진입 → 입력창에 query.keyword 반영', async () => {
    routeMock.query = { keyword: '후디' }
    const wrapper = await mountSuspended(AppHeader)
    expect((wrapper.find(SEARCH_INPUT).element as HTMLInputElement).value).toBe('후디')
  })

  // ==================== FE-20 카테고리 드롭다운 ====================

  it('카테고리 메뉴 열기 → 전체 상품(/products) + 카테고리(/categories/[id]) 순서·경로', async () => {
    useCategoriesMock.mockReturnValue(categoriesState({
      data: [
        { categoryId: 1, displayName: '데모', sortOrder: 0 },
        { categoryId: 2, displayName: '의류', sortOrder: 1 },
      ],
    }))
    const wrapper = await mountSuspended(AppHeader)
    const content = await openCategoryMenu(wrapper)
    expect(content).not.toBeNull()
    const links = Array.from(content!.querySelectorAll('a')).map((anchor) => ({
      href: anchor.getAttribute('href'),
      label: anchor.textContent?.trim(),
    }))
    expect(links).toEqual([
      { href: '/products', label: '전체 상품' },
      { href: '/categories/1', label: '데모' },
      { href: '/categories/2', label: '의류' },
    ])
  })

  it('카테고리 조회 실패 → "전체 상품"만', async () => {
    useCategoriesMock.mockReturnValue(categoriesState({ data: null, error: new Error('x') }))
    const wrapper = await mountSuspended(AppHeader)
    const content = await openCategoryMenu(wrapper)
    const links = Array.from(content!.querySelectorAll('a')).map((anchor) => anchor.getAttribute('href'))
    expect(links).toEqual(['/products'])
  })

  it('카테고리 빈 목록 → "전체 상품"만', async () => {
    useCategoriesMock.mockReturnValue(categoriesState({ data: [] }))
    const wrapper = await mountSuspended(AppHeader)
    const content = await openCategoryMenu(wrapper)
    const links = Array.from(content!.querySelectorAll('a')).map((anchor) => anchor.getAttribute('href'))
    expect(links).toEqual(['/products'])
  })
})
