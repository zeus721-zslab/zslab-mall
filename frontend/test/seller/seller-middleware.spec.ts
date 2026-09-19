import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { RouteLocationNormalized } from 'vue-router'
import sellerMiddleware from '#layers/seller/app/middleware/seller'

// 가드는 셀러 세션(seller_token) 스토어만 본다. 사용자 auth 스토어는 참조하지 않는다(호출 0 검증). D-3: 임시 비밀번호 세션은 변경 경로로만.
const { sellerAuthMock, useAuthStoreMock, navigateToMock } = vi.hoisted(() => ({
  sellerAuthMock: { isAuthenticated: false, role: null as string | null, passwordChangeRequired: false, logout: vi.fn() },
  useAuthStoreMock: vi.fn(() => ({ isAuthenticated: true, role: 'BUYER' })),
  navigateToMock: vi.fn(),
}))

vi.mock('#layers/seller/app/stores/sellerAuth', () => ({ useSellerAuthStore: () => sellerAuthMock }))
mockNuxtImport('useAuthStore', () => useAuthStoreMock)
mockNuxtImport('navigateTo', () => navigateToMock)

function route(path: string, fullPath = path): RouteLocationNormalized {
  return { path, fullPath } as RouteLocationNormalized
}
const from = route('/')

describe('seller 미들웨어 (seller_token 기준)', () => {
  beforeEach(() => {
    navigateToMock.mockReset()
    useAuthStoreMock.mockClear()
    sellerAuthMock.logout.mockReset()
    sellerAuthMock.isAuthenticated = false
    sellerAuthMock.role = null
    sellerAuthMock.passwordChangeRequired = false
  })

  it('seller_token 없음 → /seller/login?redirect=<복귀 경로> (사용자 세션 유무 무관·auth 스토어 미참조)', () => {
    sellerMiddleware(route('/seller', '/seller?tab=1'), from)
    expect(navigateToMock).toHaveBeenCalledWith(`/seller/login?redirect=${encodeURIComponent('/seller?tab=1')}`)
    expect(useAuthStoreMock).not.toHaveBeenCalled()
    expect(sellerAuthMock.logout).not.toHaveBeenCalled()
  })

  it('seller_token role≠SELLER(비정상) → seller_token 제거 후 /seller/login', () => {
    sellerAuthMock.isAuthenticated = true
    sellerAuthMock.role = 'ADMIN'
    sellerMiddleware(route('/seller'), from)
    expect(sellerAuthMock.logout).toHaveBeenCalledTimes(1)
    expect(navigateToMock).toHaveBeenCalledWith('/seller/login')
  })

  it('SELLER 세션 → 통과(navigateTo 미호출)', () => {
    sellerAuthMock.isAuthenticated = true
    sellerAuthMock.role = 'SELLER'
    const result = sellerMiddleware(route('/seller'), from)
    expect(result).toBeUndefined()
    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('임시 비밀번호 세션(passwordChangeRequired) → 셀러 홈 진입 차단·비밀번호 변경 경로로', () => {
    sellerAuthMock.isAuthenticated = true
    sellerAuthMock.role = 'SELLER'
    sellerAuthMock.passwordChangeRequired = true
    sellerMiddleware(route('/seller'), from)
    expect(navigateToMock).toHaveBeenCalledWith('/seller/settings/password')
    expect(sellerAuthMock.logout).not.toHaveBeenCalled()
  })

  it('임시 비밀번호 세션이라도 비밀번호 변경 경로 자체는 통과(리다이렉트 루프 없음)', () => {
    sellerAuthMock.isAuthenticated = true
    sellerAuthMock.role = 'SELLER'
    sellerAuthMock.passwordChangeRequired = true
    const result = sellerMiddleware(route('/seller/settings/password'), from)
    expect(result).toBeUndefined()
    expect(navigateToMock).not.toHaveBeenCalled()
  })
})
