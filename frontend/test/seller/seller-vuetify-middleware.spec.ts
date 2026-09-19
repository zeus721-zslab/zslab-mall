import { describe, it, expect, beforeEach, vi } from 'vitest'
import type { RouteLocationNormalized } from 'vue-router'
import sellerVuetifyMiddleware from '#layers/seller/app/middleware/seller-vuetify'

// Vuetify 로딩 조건(관리자 FE-22c 동형): 로그인 경로가 아니면서 셀러 세션(seller_token)이 아니면 ensureSellerVuetify를 호출하지 않는다.
const { sellerAuthMock, ensureSellerVuetifyMock } = vi.hoisted(() => ({
  sellerAuthMock: { isAuthenticated: false, role: null as string | null },
  ensureSellerVuetifyMock: vi.fn(async () => {}),
}))

vi.mock('#layers/seller/app/lib/vuetify', () => ({ ensureSellerVuetify: ensureSellerVuetifyMock }))
vi.mock('#layers/seller/app/stores/sellerAuth', () => ({ useSellerAuthStore: () => sellerAuthMock }))

const from = { path: '/', fullPath: '/' } as RouteLocationNormalized
function route(path: string): RouteLocationNormalized {
  return { path, fullPath: path } as RouteLocationNormalized
}

describe('seller-vuetify 미들웨어', () => {
  beforeEach(() => {
    ensureSellerVuetifyMock.mockClear()
    sellerAuthMock.isAuthenticated = false
    sellerAuthMock.role = null
  })

  it('셀러 세션 없이 보호 경로(사용자 BUYER 세션만 있는 경우 포함) → Vuetify 미로드', async () => {
    await sellerVuetifyMiddleware(route('/seller'), from)
    expect(ensureSellerVuetifyMock).not.toHaveBeenCalled()
  })

  it('seller_token role≠SELLER → Vuetify 미로드', async () => {
    sellerAuthMock.isAuthenticated = true
    sellerAuthMock.role = 'BUYER'
    await sellerVuetifyMiddleware(route('/seller'), from)
    expect(ensureSellerVuetifyMock).not.toHaveBeenCalled()
  })

  it('셀러 세션 → Vuetify 로드', async () => {
    sellerAuthMock.isAuthenticated = true
    sellerAuthMock.role = 'SELLER'
    await sellerVuetifyMiddleware(route('/seller'), from)
    expect(ensureSellerVuetifyMock).toHaveBeenCalledTimes(1)
  })

  it('미인증이라도 /seller/login → Vuetify 로드(로그인 화면도 Vuetify 폼)', async () => {
    await sellerVuetifyMiddleware(route('/seller/login'), from)
    expect(ensureSellerVuetifyMock).toHaveBeenCalledTimes(1)
  })
})
