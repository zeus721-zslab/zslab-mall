import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { ref, type Ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { useSellerAuthStore, SELLER_TOKEN_COOKIE } from '#layers/seller/app/stores/sellerAuth'
import { useAuthStore } from '~/stores/auth'

// useCookie를 이름별 ref로 대체해 (a) 쿠키명·옵션(path=/seller) (b) 로그아웃 격리 (c) role 불일치 저장 거절 (d) passwordChangeRequired(D-3)를 검증한다.
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

const fetchMock = vi.fn()

const SELLER_COOKIE_OPTIONS = { path: '/seller', sameSite: 'lax', secure: true, maxAge: 3600 }

/** 서명 검증 없는 표시용 디코드만 쓰므로 header.payload.signature 형식의 가짜 JWT로 충분하다. */
function fakeJwt(role: string, expOffsetSeconds = 3600): string {
  const base64url = (value: string) => Buffer.from(value).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  const exp = Math.floor(Date.now() / 1000) + expOffsetSeconds
  return `${base64url('{"alg":"HS256"}')}.${base64url(JSON.stringify({ role, exp }))}.sig`
}

describe('sellerAuth 스토어 (Track 90-A 세션 분리)', () => {
  beforeEach(() => {
    cookieRefs.clear()
    useCookieMock.mockClear()
    fetchMock.mockReset()
    vi.stubGlobal('$fetch', fetchMock)
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('쿠키명 seller_token · path=/seller · lax·secure·maxAge 3600 (변경 강제 쿠키도 같은 옵션)', () => {
    useSellerAuthStore()
    expect(SELLER_TOKEN_COOKIE).toBe('seller_token')
    expect(useCookieMock).toHaveBeenCalledWith('seller_token', SELLER_COOKIE_OPTIONS)
    expect(useCookieMock).toHaveBeenCalledWith('seller_password_change_required', SELLER_COOKIE_OPTIONS)
  })

  it('로그인 성공(role=SELLER) → seller_token 저장·role SELLER·변경 강제 없음', async () => {
    fetchMock.mockResolvedValue({ token: fakeJwt('SELLER'), passwordChangeRequired: false })
    const sellerAuth = useSellerAuthStore()
    await sellerAuth.login('seller@zslab.local', 'pw')
    expect(fetchMock).toHaveBeenCalledWith('/v1/auth/login', expect.objectContaining({ method: 'POST', body: { email: 'seller@zslab.local', password: 'pw', role: 'SELLER' } }))
    expect(sellerAuth.isAuthenticated).toBe(true)
    expect(sellerAuth.role).toBe('SELLER')
    expect(sellerAuth.passwordChangeRequired).toBe(false)
  })

  it('로그인 응답 passwordChangeRequired=true → 변경 강제 상태 켜짐(D-3 구매자형)', async () => {
    fetchMock.mockResolvedValue({ token: fakeJwt('SELLER'), passwordChangeRequired: true })
    const sellerAuth = useSellerAuthStore()
    await sellerAuth.login('seller@zslab.local', 'pw')
    expect(sellerAuth.isAuthenticated).toBe(true)
    expect(sellerAuth.passwordChangeRequired).toBe(true)
  })

  it('응답 토큰 role≠SELLER → 저장 거절·throw', async () => {
    fetchMock.mockResolvedValue({ token: fakeJwt('BUYER') })
    const sellerAuth = useSellerAuthStore()
    await expect(sellerAuth.login('buyer@zslab.local', 'pw')).rejects.toThrow()
    expect(sellerAuth.token).toBeNull()
    expect(sellerAuth.isAuthenticated).toBe(false)
  })

  it('만료된 토큰 → isAuthenticated false', () => {
    const sellerAuth = useSellerAuthStore()
    sellerAuth.token = fakeJwt('SELLER', -60)
    expect(sellerAuth.expired).toBe(true)
    expect(sellerAuth.isAuthenticated).toBe(false)
  })

  it('데모 로그인 성공(role=SELLER) → 서버 라우트 POST(자격증명 없는 본문)·seller_token 저장', async () => {
    fetchMock.mockResolvedValue({ token: fakeJwt('SELLER'), passwordChangeRequired: false })
    const sellerAuth = useSellerAuthStore()
    await sellerAuth.loginDemo()
    expect(fetchMock).toHaveBeenCalledWith('/_seller-demo/login', { method: 'POST' })
    expect(sellerAuth.isAuthenticated).toBe(true)
    expect(sellerAuth.role).toBe('SELLER')
  })

  it('데모 로그인 응답 토큰 role≠SELLER → 저장 거절·throw', async () => {
    fetchMock.mockResolvedValue({ token: fakeJwt('ADMIN') })
    const sellerAuth = useSellerAuthStore()
    await expect(sellerAuth.loginDemo()).rejects.toThrow()
    expect(sellerAuth.token).toBeNull()
  })

  it('markSuspended → suspended true · 로그아웃·재로그인 시 해제', async () => {
    const sellerAuth = useSellerAuthStore()
    sellerAuth.token = fakeJwt('SELLER')
    sellerAuth.markSuspended()
    expect(sellerAuth.suspended).toBe(true)
    expect(sellerAuth.isAuthenticated).toBe(true) // 정지는 세션을 끊지 않는다
    sellerAuth.logout()
    expect(sellerAuth.suspended).toBe(false)
    sellerAuth.markSuspended()
    fetchMock.mockResolvedValue({ token: fakeJwt('SELLER') })
    await sellerAuth.login('seller@zslab.local', 'pw')
    expect(sellerAuth.suspended).toBe(false)
  })

  it('로그아웃 격리: 셀러 로그아웃 시 auth_token 유지·변경 강제 쿠키 제거, 사용자 로그아웃 시 seller_token 유지', () => {
    const sellerAuth = useSellerAuthStore()
    const userAuth = useAuthStore()
    sellerAuth.token = fakeJwt('SELLER')
    cookieRefs.get('seller_password_change_required')!.value = true
    userAuth.token = fakeJwt('BUYER')

    sellerAuth.logout()
    expect(sellerAuth.token).toBeNull()
    expect(sellerAuth.passwordChangeRequired).toBe(false)
    expect(userAuth.token).not.toBeNull()

    sellerAuth.token = fakeJwt('SELLER')
    userAuth.logout()
    expect(userAuth.token).toBeNull()
    expect(sellerAuth.token).not.toBeNull()
    // 셀러 스토어는 seller_token + seller_password_change_required, 사용자 스토어는 auth_token + password_change_required만 읽는다(admin_token 무관)
    expect([...cookieRefs.keys()]).toEqual(['seller_token', 'seller_password_change_required', 'auth_token', 'password_change_required'])
  })
})
