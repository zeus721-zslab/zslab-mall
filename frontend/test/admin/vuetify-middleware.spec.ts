import { describe, it, expect, beforeEach, vi } from 'vitest'
import type { RouteLocationNormalized } from 'vue-router'
import vuetifyMiddleware from '#layers/admin/app/middleware/vuetify'

// Vuetify 로딩 조건(FE-22c·FE-22d): 로그인 경로가 아니면서 관리자 세션(admin_token)이 아니면 ensureVuetify를 호출하지 않는다.
const { adminAuthMock, ensureVuetifyMock } = vi.hoisted(() => ({
  adminAuthMock: { isAuthenticated: false, role: null as string | null },
  ensureVuetifyMock: vi.fn(async () => {}),
}))

vi.mock('#layers/admin/app/lib/vuetify', () => ({ ensureVuetify: ensureVuetifyMock }))
vi.mock('#layers/admin/app/stores/adminAuth', () => ({ useAdminAuthStore: () => adminAuthMock }))

const from = { path: '/', fullPath: '/' } as RouteLocationNormalized
function route(path: string): RouteLocationNormalized {
  return { path, fullPath: path } as RouteLocationNormalized
}

describe('vuetify 미들웨어', () => {
  beforeEach(() => {
    ensureVuetifyMock.mockClear()
    adminAuthMock.isAuthenticated = false
    adminAuthMock.role = null
  })

  it('관리자 세션 없이 보호 경로(사용자 BUYER 세션만 있는 경우 포함) → Vuetify 미로드', async () => {
    await vuetifyMiddleware(route('/admin/orders'), from)
    expect(ensureVuetifyMock).not.toHaveBeenCalled()
  })

  it('admin_token role≠ADMIN → Vuetify 미로드', async () => {
    adminAuthMock.isAuthenticated = true
    adminAuthMock.role = 'BUYER'
    await vuetifyMiddleware(route('/admin'), from)
    expect(ensureVuetifyMock).not.toHaveBeenCalled()
  })

  it('관리자 세션 → Vuetify 로드', async () => {
    adminAuthMock.isAuthenticated = true
    adminAuthMock.role = 'ADMIN'
    await vuetifyMiddleware(route('/admin/orders'), from)
    expect(ensureVuetifyMock).toHaveBeenCalledTimes(1)
  })

  it('미인증이라도 /admin/login → Vuetify 로드(로그인 화면도 Vuetify 폼)', async () => {
    await vuetifyMiddleware(route('/admin/login'), from)
    expect(ensureVuetifyMock).toHaveBeenCalledTimes(1)
  })
})
