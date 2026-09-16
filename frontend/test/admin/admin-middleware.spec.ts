import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { RouteLocationNormalized } from 'vue-router'
import adminMiddleware from '#layers/admin/app/middleware/admin'

// FE-22d: 가드는 관리자 세션(admin_token) 스토어만 본다. 사용자 auth 스토어는 참조하지 않는다(호출 0 검증).
const { adminAuthMock, useAuthStoreMock, navigateToMock } = vi.hoisted(() => ({
  adminAuthMock: { isAuthenticated: false, role: null as string | null, logout: vi.fn() },
  useAuthStoreMock: vi.fn(() => ({ isAuthenticated: true, role: 'BUYER' })),
  navigateToMock: vi.fn(),
}))

vi.mock('#layers/admin/app/stores/adminAuth', () => ({ useAdminAuthStore: () => adminAuthMock }))
mockNuxtImport('useAuthStore', () => useAuthStoreMock)
mockNuxtImport('navigateTo', () => navigateToMock)

const to = { fullPath: '/admin/orders?page=2' } as RouteLocationNormalized
const from = { fullPath: '/' } as RouteLocationNormalized

describe('admin 미들웨어 (admin_token 기준)', () => {
  beforeEach(() => {
    navigateToMock.mockReset()
    useAuthStoreMock.mockClear()
    adminAuthMock.logout.mockReset()
    adminAuthMock.isAuthenticated = false
    adminAuthMock.role = null
  })

  it('admin_token 없음 → /admin/login?redirect=<복귀 경로> (사용자 세션 유무 무관·auth 스토어 미참조)', () => {
    adminMiddleware(to, from)
    expect(navigateToMock).toHaveBeenCalledWith(`/admin/login?redirect=${encodeURIComponent('/admin/orders?page=2')}`)
    expect(useAuthStoreMock).not.toHaveBeenCalled()
    expect(adminAuthMock.logout).not.toHaveBeenCalled()
  })

  it('admin_token role≠ADMIN(비정상) → admin_token 제거 후 /admin/login', () => {
    adminAuthMock.isAuthenticated = true
    adminAuthMock.role = 'BUYER'
    adminMiddleware(to, from)
    expect(adminAuthMock.logout).toHaveBeenCalledTimes(1)
    expect(navigateToMock).toHaveBeenCalledWith('/admin/login')
  })

  it('ADMIN 세션 → 통과(navigateTo 미호출)', () => {
    adminAuthMock.isAuthenticated = true
    adminAuthMock.role = 'ADMIN'
    const result = adminMiddleware(to, from)
    expect(result).toBeUndefined()
    expect(navigateToMock).not.toHaveBeenCalled()
  })
})
