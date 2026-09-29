import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { RouteLocationNormalized } from 'vue-router'
import adminMiddleware from '#layers/admin/app/middleware/admin'

// FE-22d·D-235 F5: 가드는 관리자 세션 스토어(/admin/me 확인 결과)만 본다. 사용자 auth 스토어는 참조하지 않는다(호출 0 검증).
const { adminAuthMock, useAuthStoreMock, navigateToMock } = vi.hoisted(() => ({
  adminAuthMock: { isAuthenticated: false, role: null as string | null, ensureSession: vi.fn(async () => {}), clearSession: vi.fn() },
  useAuthStoreMock: vi.fn(() => ({ isAuthenticated: true, role: 'BUYER' })),
  navigateToMock: vi.fn(),
}))

vi.mock('#layers/admin/app/stores/adminAuth', () => ({ useAdminAuthStore: () => adminAuthMock }))
mockNuxtImport('useAuthStore', () => useAuthStoreMock)
mockNuxtImport('navigateTo', () => navigateToMock)

const to = { fullPath: '/admin/orders?page=2' } as RouteLocationNormalized
const from = { fullPath: '/' } as RouteLocationNormalized

describe('admin 미들웨어 (관리자 세션 기준)', () => {
  beforeEach(() => {
    navigateToMock.mockReset()
    useAuthStoreMock.mockClear()
    adminAuthMock.ensureSession.mockClear()
    adminAuthMock.clearSession.mockReset()
    adminAuthMock.isAuthenticated = false
    adminAuthMock.role = null
  })

  it('관리자 세션 없음 → 세션 확인 후 /admin/login?redirect=<복귀 경로> (사용자 세션 유무 무관·auth 스토어 미참조)', async () => {
    await adminMiddleware(to, from)
    expect(adminAuthMock.ensureSession).toHaveBeenCalledTimes(1)
    expect(navigateToMock).toHaveBeenCalledWith(`/admin/login?redirect=${encodeURIComponent('/admin/orders?page=2')}`)
    expect(useAuthStoreMock).not.toHaveBeenCalled()
    expect(adminAuthMock.clearSession).not.toHaveBeenCalled()
  })

  it('role≠ADMIN(비정상) → 관리자 상태 해제 후 /admin/login', async () => {
    adminAuthMock.isAuthenticated = true
    adminAuthMock.role = 'BUYER'
    await adminMiddleware(to, from)
    expect(adminAuthMock.clearSession).toHaveBeenCalledTimes(1)
    expect(navigateToMock).toHaveBeenCalledWith('/admin/login')
  })

  it('ADMIN 세션 → 통과(navigateTo 미호출)', async () => {
    adminAuthMock.isAuthenticated = true
    adminAuthMock.role = 'ADMIN'
    const result = await adminMiddleware(to, from)
    expect(result).toBeUndefined()
    expect(navigateToMock).not.toHaveBeenCalled()
  })
})
