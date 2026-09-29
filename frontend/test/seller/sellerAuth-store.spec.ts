import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { ref, type Ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { useSellerAuthStore } from '#layers/seller/app/stores/sellerAuth'
import { useAuthStore } from '~/stores/auth'

// D-235 PR2: 셀러 인증은 BE HttpOnly 쿠키라 스토어는 토큰을 읽지 않고, 로그인 상태는 /seller/me 결과로만 판정한다.
// useCookie를 이름별 ref로 대체해 (a) 변경 강제 쿠키 옵션(path=/seller) (b) 상태 격리 (c) passwordChangeRequired(D-3)를 검증한다.
const { cookieRefs, useCookieMock } = vi.hoisted(() => {
  const cookieRefs = new Map<string, Ref<string | boolean | null>>()
  return {
    cookieRefs,
    useCookieMock: vi.fn((name: string, _options?: unknown) => {
      if (!cookieRefs.has(name)) cookieRefs.set(name, ref<string | boolean | null>(null))
      return cookieRefs.get(name)
    }),
  }
})

mockNuxtImport('useCookie', () => useCookieMock)

// nuxt 4.5부터 $fetch는 auto-import(모듈 로드 시 globalThis.$fetch 고정)라 전역 stub이 닿지 않는다(FE-90).
const { fetchMock } = vi.hoisted(() => ({ fetchMock: vi.fn() }))
mockNuxtImport('$fetch', () => fetchMock)

const SELLER_COOKIE_OPTIONS = { path: '/seller', sameSite: 'lax', secure: true, maxAge: 3600 }

describe('sellerAuth 스토어 (Track 90-A 세션 분리 · D-235 쿠키 인증)', () => {
  beforeEach(() => {
    cookieRefs.clear()
    useCookieMock.mockClear()
    fetchMock.mockReset()
    setActivePinia(createPinia())
  })

  it('JS 쿠키는 변경 강제 상태 하나뿐(path=/seller · lax·secure·maxAge 3600) — 토큰 쿠키 없음(F7)', () => {
    useSellerAuthStore()
    expect(useCookieMock).toHaveBeenCalledTimes(1)
    expect(useCookieMock).toHaveBeenCalledWith('seller_password_change_required', SELLER_COOKIE_OPTIONS)
  })

  it('로그인 성공 → POST /v1/seller/auth/login(role 없는 본문) → 로그인 상태·role SELLER·변경 강제 없음', async () => {
    fetchMock.mockResolvedValue({ token: 'ignored', passwordChangeRequired: false })
    const sellerAuth = useSellerAuthStore()
    await sellerAuth.login('seller@zslab.local', 'pw')
    expect(fetchMock).toHaveBeenCalledWith('/v1/seller/auth/login', expect.objectContaining({ method: 'POST', body: { email: 'seller@zslab.local', password: 'pw' } }))
    expect(sellerAuth.isAuthenticated).toBe(true)
    expect(sellerAuth.role).toBe('SELLER')
    expect(sellerAuth.passwordChangeRequired).toBe(false)
  })

  it('로그인 응답 passwordChangeRequired=true → 변경 강제 상태 켜짐(D-3 구매자형)', async () => {
    fetchMock.mockResolvedValue({ passwordChangeRequired: true })
    const sellerAuth = useSellerAuthStore()
    await sellerAuth.login('seller@zslab.local', 'pw')
    expect(sellerAuth.isAuthenticated).toBe(true)
    expect(sellerAuth.passwordChangeRequired).toBe(true)
  })

  it('로그인 실패(401 throw) → 로그인 상태 아님', async () => {
    fetchMock.mockRejectedValue({ statusCode: 401 })
    const sellerAuth = useSellerAuthStore()
    await expect(sellerAuth.login('seller@zslab.local', 'wrong')).rejects.toBeDefined()
    expect(sellerAuth.isAuthenticated).toBe(false)
  })

  it('데모 로그인 성공 → 서버 라우트 POST(자격증명 없는 본문) → 로그인 상태', async () => {
    fetchMock.mockResolvedValue({ passwordChangeRequired: false })
    const sellerAuth = useSellerAuthStore()
    await sellerAuth.loginDemo()
    expect(fetchMock).toHaveBeenCalledWith('/_seller-demo/login', { method: 'POST', onRequest: expect.any(Function) })
    expect(sellerAuth.isAuthenticated).toBe(true)
    expect(sellerAuth.role).toBe('SELLER')
  })

  it('ensureSession: /v1/seller/me 200 → 로그인 상태 · 401 → 로그인 상태 아님 · 이미 확인했으면 다시 묻지 않는다', async () => {
    fetchMock.mockResolvedValue({ sellerPublicId: 'slr_A' })
    const sellerAuth = useSellerAuthStore()
    await sellerAuth.ensureSession()
    await sellerAuth.ensureSession()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledWith('/v1/seller/me', expect.objectContaining({ baseURL: '/api' }))
    expect(sellerAuth.isAuthenticated).toBe(true)

    setActivePinia(createPinia())
    const signedOut = useSellerAuthStore()
    fetchMock.mockRejectedValueOnce({ statusCode: 401 })
    await signedOut.ensureSession()
    expect(signedOut.signedIn).toBe(false)
  })

  it('markSuspended → suspended true · 상태 해제·재로그인 시 해제', async () => {
    fetchMock.mockResolvedValue({ passwordChangeRequired: false })
    const sellerAuth = useSellerAuthStore()
    await sellerAuth.login('seller@zslab.local', 'pw')
    sellerAuth.markSuspended()
    expect(sellerAuth.suspended).toBe(true)
    expect(sellerAuth.isAuthenticated).toBe(true) // 정지는 세션을 끊지 않는다
    sellerAuth.clearSession()
    expect(sellerAuth.suspended).toBe(false)
    sellerAuth.markSuspended()
    await sellerAuth.login('seller@zslab.local', 'pw')
    expect(sellerAuth.suspended).toBe(false)
  })

  it('로그아웃 → POST /v1/seller/auth/logout(CSRF onRequest) 후 로그인 상태·변경 강제 상태 해제', async () => {
    fetchMock.mockResolvedValueOnce({ passwordChangeRequired: true })
    const sellerAuth = useSellerAuthStore()
    await sellerAuth.login('seller@zslab.local', 'pw')
    fetchMock.mockResolvedValueOnce(undefined)
    await sellerAuth.logout()
    expect(fetchMock).toHaveBeenLastCalledWith('/v1/seller/auth/logout', expect.objectContaining({ method: 'POST', onRequest: expect.any(Function) }))
    expect(sellerAuth.isAuthenticated).toBe(false)
    expect(sellerAuth.passwordChangeRequired).toBe(false)
  })

  it('상태 격리: 셀러 상태 해제 시 구매자 상태 유지·변경 강제 쿠키 제거, 구매자 상태 해제 시 셀러 상태 유지', async () => {
    fetchMock.mockResolvedValue({ passwordChangeRequired: false })
    const sellerAuth = useSellerAuthStore()
    const userAuth = useAuthStore()
    await sellerAuth.login('seller@zslab.local', 'pw')
    cookieRefs.get('seller_password_change_required')!.value = true
    userAuth.signedIn = true

    sellerAuth.clearSession()
    expect(sellerAuth.isAuthenticated).toBe(false)
    expect(sellerAuth.passwordChangeRequired).toBe(false)
    expect(userAuth.isAuthenticated).toBe(true)

    await sellerAuth.login('seller@zslab.local', 'pw')
    userAuth.clearSession()
    expect(userAuth.isAuthenticated).toBe(false)
    expect(sellerAuth.isAuthenticated).toBe(true)
    // 셀러 스토어는 seller_password_change_required, 사용자 스토어는 password_change_required만 읽는다(토큰 쿠키 없음)
    expect([...cookieRefs.keys()]).toEqual(['seller_password_change_required', 'password_change_required'])
  })
})
