import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { RouteLocationNormalized } from 'vue-router'
import sellerMiddleware from '#layers/seller/app/middleware/seller'

// 가드는 셀러 세션 스토어(/seller/me 확인 결과·D-235 F5)만 본다. 사용자 auth 스토어는 참조하지 않는다(호출 0 검증). D-3: 임시 비밀번호 세션은 변경 경로로만.
const { sellerAuthMock, useAuthStoreMock, navigateToMock } = vi.hoisted(() => ({
  sellerAuthMock: {
    isAuthenticated: false,
    role: null as string | null,
    passwordChangeRequired: false,
    ensureSession: vi.fn(async () => {}),
    clearSession: vi.fn(),
  },
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

describe('seller 미들웨어 (셀러 세션 기준)', () => {
  beforeEach(() => {
    navigateToMock.mockReset()
    useAuthStoreMock.mockClear()
    sellerAuthMock.ensureSession.mockClear()
    sellerAuthMock.clearSession.mockReset()
    sellerAuthMock.isAuthenticated = false
    sellerAuthMock.role = null
    sellerAuthMock.passwordChangeRequired = false
  })

  it('셀러 세션 없음 → 세션 확인 후 /seller/login?redirect=<복귀 경로> (사용자 세션 유무 무관·auth 스토어 미참조)', async () => {
    await sellerMiddleware(route('/seller', '/seller?tab=1'), from)
    expect(sellerAuthMock.ensureSession).toHaveBeenCalledTimes(1)
    expect(navigateToMock).toHaveBeenCalledWith(`/seller/login?redirect=${encodeURIComponent('/seller?tab=1')}`)
    expect(useAuthStoreMock).not.toHaveBeenCalled()
    expect(sellerAuthMock.clearSession).not.toHaveBeenCalled()
  })

  it('role≠SELLER(비정상) → 셀러 상태 해제 후 /seller/login', async () => {
    sellerAuthMock.isAuthenticated = true
    sellerAuthMock.role = 'ADMIN'
    await sellerMiddleware(route('/seller'), from)
    expect(sellerAuthMock.clearSession).toHaveBeenCalledTimes(1)
    expect(navigateToMock).toHaveBeenCalledWith('/seller/login')
  })

  it('SELLER 세션 → 통과(navigateTo 미호출)', async () => {
    sellerAuthMock.isAuthenticated = true
    sellerAuthMock.role = 'SELLER'
    const result = await sellerMiddleware(route('/seller'), from)
    expect(result).toBeUndefined()
    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('임시 비밀번호 세션(passwordChangeRequired) → 셀러 홈 진입 차단·비밀번호 변경 경로로', async () => {
    sellerAuthMock.isAuthenticated = true
    sellerAuthMock.role = 'SELLER'
    sellerAuthMock.passwordChangeRequired = true
    await sellerMiddleware(route('/seller'), from)
    expect(navigateToMock).toHaveBeenCalledWith('/seller/settings/password')
    expect(sellerAuthMock.clearSession).not.toHaveBeenCalled()
  })

  it('임시 비밀번호 세션이라도 비밀번호 변경 경로 자체는 통과(리다이렉트 루프 없음)', async () => {
    sellerAuthMock.isAuthenticated = true
    sellerAuthMock.role = 'SELLER'
    sellerAuthMock.passwordChangeRequired = true
    const result = await sellerMiddleware(route('/seller/settings/password'), from)
    expect(result).toBeUndefined()
    expect(navigateToMock).not.toHaveBeenCalled()
  })
})
